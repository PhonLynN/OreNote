package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.sync.crypto.SyncCrypto
import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import com.phonlynn.oreplan.domain.sync.r2.R2Exception
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次同步的结果。**要给用户看的**，所以是可读文案而不是状态码。
 */
data class SyncOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val merged: Int = 0,
    val deletedLocally: Int = 0,
    val skipped: Int = 0,
    val conflicts: Int = 0,
    /** 非 null = 失败了，值是给用户看的原因。 */
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null

    val didNothing: Boolean get() = uploaded == 0 && downloaded == 0 && merged == 0 && deletedLocally == 0

    /** 给设置页那一行显示的一句话。 */
    fun summary(): String = when {
        error != null -> error
        didNothing -> "已是最新"
        else -> buildString {
            if (downloaded > 0) append("下载 $downloaded")
            if (deletedLocally > 0) {
                if (isNotEmpty()) append(" · ")
                append("删除 $deletedLocally")
            }
            if (uploaded > 0) {
                if (isNotEmpty()) append(" · ")
                append("上传 $uploaded")
            }
            if (merged > 0) {
                if (isNotEmpty()) append(" · ")
                append("合并 $merged")
            }
        }
    }

    /** 把多次同步的结果合成一个（全量同步按表跑多次）。 */
    operator fun plus(other: SyncOutcome): SyncOutcome = SyncOutcome(
        uploaded = uploaded + other.uploaded,
        downloaded = downloaded + other.downloaded,
        merged = merged + other.merged,
        deletedLocally = deletedLocally + other.deletedLocally,
        skipped = skipped + other.skipped,
        conflicts = conflicts + other.conflicts,
        // 任一表失败就让整体失败，并把原因带出来 —— 静默吞掉一张表的失败
        // 会让用户以为"同步成功了"，而某类数据其实一直没上去。
        error = error ?: other.error,
    )
}

/**
 * 同步引擎：**一套流程，服务所有表**。
 *
 * ## 一次同步的顺序（顺序本身是正确性的一部分）
 *
 * ```
 * ① 拉云端清单（ListObjectsV2，只有 key + ETag，很便宜）
 * ② 逐条比对：本机 vs 云端 → 合并 → 写回本机
 * ③ 把本机该传的传上去（含墓碑）
 * ```
 *
 * **必须先合并再上传**：反过来的话，本机一个旧版本会覆盖云端的较新版本，
 * 而那台设备再也拿不回它的改动（它以为已经同步成功了）。
 *
 * ## 为什么逐个对象处理而不是批量
 *
 * 一个对象坏了（解密失败、JSON 损坏）不该让整次同步失败 ——
 * 那种设计会让一个历史脏数据永久阻塞同步。所以逐条 `runCatching`，
 * 坏的计入 skipped 并继续。
 *
 * ## 为什么各表的差异走 [SyncTableAdapter] 而不是把本类复制八份
 *
 * 这个流程里每一条纪律都是踩坑换来的（见下面各处的 ⚠️ 注释）。
 * 复制八份 = 把同样的坑埋八遍。所以差异抽到适配器，流程只有一份。
 */
@Singleton
class SyncEngine @Inject constructor(
    private val adapters: List<@JvmSuppressWildcards SyncTableAdapter>,
    private val stampWriter: SyncStampWriter,
    private val tombstones: TombstoneRegistry,
    /**
     * 附件二进制同步。
     *
     * 有默认值（不可用）是为了让**不涉及附件的单元测试**能直接构造引擎 ——
     * 那些测试验证的是表同步流程，不该为了构造参数去搭一整套附件环境。
     * 生产路径由 `SyncModule` 注入真实实现。
     */
    private val blobSync: BlobSync? = null,
) {

    /**
     * 全量同步：先逐表同步**索引与数据**，最后传**附件二进制**。
     *
     * ## 为什么二进制放在最后
     *
     * 索引行很小，几秒钟就能同步完 —— 完成后另一台设备**立刻看得见**
     * "这条待办有一张图"，只是图还没下载。
     * 若把二进制混在中间传，一个几十 MB 的 PDF 会卡住后面所有表的同步，
     * 用户看到的是"同步卡住了"，而不是"数据已同步、附件在传"。
     *
     * 另外：二进制失败**不该让整次同步失败** ——
     * 文字数据已经同步好了，那是有价值的成果，不该因为一张图传不上去而回滚观感。
     * 所以附件的结果单独累加，且不计入致命错误判断。
     */
    suspend fun syncAll(
        client: R2Client,
        config: R2Config,
        key: ByteArray,
        onBlobProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncOutcome {
        var total = SyncOutcome()
        var fatal = false
        for (adapter in adapters.sortedBy { it.entity.table }) {
            val outcome = syncTable(adapter, client, config, key)
            total += outcome
            // 认证类失败（401/403）对每张表都会发生，继续跑下去只是重复失败、
            // 而且会让用户多等几十次往返。直接中断，把原因带出去。
            if (outcome.error != null && isFatal(outcome.error)) {
                fatal = true
                break
            }
        }
        if (fatal) return total

        // 最后：把本机有、云端没有的附件二进制传上去。
        // 失败只记 skipped，不改写 error —— 文字数据已经同步成功了。
        val sync = blobSync ?: return total
        val blobs = runCatching { sync.uploadPending(client, config, key, onBlobProgress) }
            .getOrElse { BlobSync.Result() }
        return total.copy(uploaded = total.uploaded + blobs.uploaded, skipped = total.skipped + blobs.failed)
    }

    /** 认证/配置类错误：换张表也不会好，应当立即停止。 */
    private fun isFatal(message: String): Boolean =
        message.contains("凭据") || message.contains("存储桶")

    /** 单张表同步。 */
    suspend fun syncTable(
        adapter: SyncTableAdapter,
        client: R2Client,
        config: R2Config,
        key: ByteArray,
    ): SyncOutcome {
        val entity = adapter.entity
        var uploaded = 0
        var downloaded = 0
        var merged = 0
        var conflicts = 0
        var skipped = 0
        var deletedLocally = 0

        // ① 云端清单
        val remoteObjects = runCatching { client.listObjects(config.key(entity.table) + "/") }
            .getOrElse { e -> return SyncOutcome(error = describeFailure(e)) }
        // key → ETag。ETag 用于条件写（防覆盖并发修改）
        val remoteByKey = remoteObjects.associate { it.key to it.eTag }

        // ② 本机全量（**含墓碑** —— 删除也要同步）。一次读完，避免逐条查库。
        val localById = runCatching { adapter.readAll() }.getOrElse { e ->
            return SyncOutcome(error = e.message ?: "读取本机数据失败")
        }
        val localIdSet = localById.keys

        // ③ 逐条合并
        val toUpload = ArrayList<Pair<String, SyncEnvelope>>()
        val allIds = (localIdSet + remoteByKey.keys.map { keyToId(it) }).filter { it.isNotBlank() }.toSet()

        for (id in allIds) {
            val objectKey = config.key(entity.table, "$id.bin")
            val localEnvelope = localById[id]

            val remoteEnvelope = if (remoteByKey.containsKey(objectKey)) {
                fetchAndDecrypt(client, objectKey, key) ?: run {
                    skipped++
                    null
                }
            } else {
                null
            }

            val result = SyncMerge.merge(localEnvelope, remoteEnvelope) ?: continue
            if (result.conflict) conflicts++

            /*
             * 本机要不要按合并结果更新？
             *
             * ⚠️ **本机没有这条时必须更新** —— 那是"首次下载"。
             * 我第一版写成"合并结果≠远端才算要更新"，结果 `local=null, remote=存在` 时
             * 合并结果就等于远端 ⇒ 判为"不用更新" ⇒ **新设备永远同步不下来任何东西**。
             * 这个 bug 在两端都是空库时看不出来，只在"一端有数据、另一端首次接入"时暴露，
             * 而那恰恰是用户第一次用这个功能时的场景。
             */
            val localNeedsUpdate = localEnvelope == null ||
                result.winner.rev != localEnvelope.rev ||
                result.winner.deletedAt != localEnvelope.deletedAt ||
                result.winner.payload.toString() != localEnvelope.payload.toString()

            if (localNeedsUpdate && remoteEnvelope != null) {
                runCatching { adapter.applyToLocal(result.winner, localIdSet.contains(id)) }
                    .onFailure { skipped++ }

                if (result.winner.isTombstone) {
                    // ⚠️ 远端把它删了：**必须同时更新内存登记处**，
                    // 否则这条会一直显示在界面上，直到下次冷启动装载墓碑 ——
                    // 用户看到的是"我在另一台删了，这台还看得见"，会怀疑同步没生效。
                    tombstones.markDeleted(entity, id)
                    deletedLocally++
                } else {
                    // 远端把它改活了（比如另一端撤销了删除）：登记处也要相应解除，
                    // 否则数据在库里、界面上却永远不出现。
                    tombstones.unmarkDeleted(entity, id)
                    downloaded++
                }
                if (result.conflict) merged++
            }

            // 本机这份要不要上传。
            //
            // ⚠️ 两个容易写错的点：
            //  ① 判据用 `needsUpload(local, remote)` —— 拿**本机**那份去比，
            //     不能拿合并结果比（合并结果必然 ≥ 两边，那样永远为真、每次都全量上传）；
            //  ② 上传的**内容**是合并结果 `result.winner`，不是本机那份 ——
            //     否则远端独有的字段会被本机这份覆盖掉（"改不同字段都保留"就白做了）。
            if (localEnvelope != null && SyncMerge.needsUpload(localEnvelope, remoteEnvelope)) {
                toUpload += objectKey to result.winner
            }
        }

        // ④ 上传
        for ((objectKey, envelope) in toUpload) {
            val ok = upload(client, objectKey, envelope, key, remoteByKey[objectKey])
            if (ok) uploaded++ else skipped++
        }

        return SyncOutcome(
            uploaded = uploaded,
            downloaded = downloaded,
            merged = merged,
            conflicts = conflicts,
            deletedLocally = deletedLocally,
            skipped = skipped,
        )
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun fetchAndDecrypt(
        client: R2Client,
        objectKey: String,
        key: ByteArray,
    ): SyncEnvelope? = runCatching {
        val response = client.getObject(objectKey)
        if (!response.isSuccess) return@runCatching null
        val plain = SyncCrypto.decrypt(response.body, key, aad = objectKey.toByteArray())
        SyncEnvelope.fromJson(String(plain, Charsets.UTF_8))
    }.getOrNull()

    private suspend fun upload(
        client: R2Client,
        objectKey: String,
        envelope: SyncEnvelope,
        key: ByteArray,
        knownETag: String?,
    ): Boolean = runCatching {
        val plain = envelope.toJson().toByteArray(Charsets.UTF_8)
        val cipher = SyncCrypto.encrypt(plain, key, aad = objectKey.toByteArray())
        val response = if (knownETag == null) {
            // 云端没有：用 If-None-Match 防止覆盖另一台设备刚放上去的
            client.putObject(objectKey, cipher, ifNoneMatch = "*")
        } else {
            // 云端有：用 If-Match 保证"我改的还是我看到的那一版"
            client.putObject(objectKey, cipher, ifMatch = "\"$knownETag\"")
        }
        response.isSuccess
    }.getOrDefault(false)

    private fun keyToId(objectKey: String): String =
        objectKey.substringAfterLast('/').removeSuffix(".bin")

    private fun describeFailure(e: Throwable): String = when (e) {
        is R2Exception -> when {
            e.isAuthFailure -> "凭据不对，或令牌缺少 Object Read & Write 权限"
            e.status == 404 -> "找不到存储桶，检查桶名与 Account ID"
            else -> "云端返回 HTTP ${e.status}"
        }
        else -> e.message ?: "网络不可达"
    }
}
