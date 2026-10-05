package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.sync.blob.BlobStore
import com.phonlynn.oreplan.domain.sync.blob.ContentHash
import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附件二进制的同步编排。
 *
 * ## 职责边界
 *
 * · `AttachmentSyncAdapter` 同步**索引行**（谁有哪张图）
 * · 本类同步**二进制本身**（那张图的内容）
 *
 * 分开的理由见 `AttachmentSyncAdapter` 的注释：让"数据一致"与"文件到达"
 * 成为两件可以分别完成的事。用户先看到"有这张图"，再等它下载。
 *
 * ## 哈希缓存在哪
 *
 * `entity_ext` 抽屉的 `sync.blobHash`（`SyncMeta.KEY_BLOB_HASH`）——
 * 老表 `attachments` 一列都不加。
 * 缓存它的意义：算 sha256 要读一遍整个文件，几十张图就是几十次全量读。
 * 只在**第一次上传后**算一次并记住，后续同步直接读抽屉。
 */
@Singleton
class BlobSync @Inject constructor(
    private val attachmentRepository: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val blobStore: BlobStore,
    private val extRepo: com.phonlynn.oreplan.domain.repository.EntityExtRepository,
) {

    data class Result(val uploaded: Int = 0, val skipped: Int = 0, val failed: Int = 0)

    /**
     * 把本机有、云端没有的附件传上去。
     *
     * 跳过的情况有三类，都**不算失败**（不该让同步报错）：
     *  ① 云端已有同 hash 的对象（内容寻址的去重收益）；
     *  ② 本机文件已丢失（恢复备份后会出现"索引在、文件不在"）；
     *  ③ 该附件已同步过且哈希未变（读抽屉缓存，不必重算）。
     */
    suspend fun uploadPending(
        client: R2Client,
        config: R2Config,
        key: ByteArray,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val attachments = attachmentRepository.getAll()
        if (attachments.isEmpty()) return Result()

        // 云端已有的 blob 哈希集合：一次列举，避免每个文件一次 HEAD 往返。
        // 附件多起来时这一次列举能省下几十次请求。
        val knownHashes = runCatching {
            client.listObjects(config.key("blobs") + "/")
                .mapNotNull { it.key.substringAfterLast('/').takeIf { h -> h.length == 64 } }
                .toSet()
        }.getOrDefault(emptySet())

        var uploaded = 0
        var skipped = 0
        var failed = 0
        var done = 0
        val total = attachments.size

        attachments.forEach { attachment ->
            done++
            val file = storage.fileOf(attachment)
            if (!file.isFile) {
                // 索引在、文件不在：不是错误，是"这台设备本来就没有"。
                // 它会在真正需要时按需下载。
                skipped++
                onProgress(done, total)
                return@forEach
            }

            val cachedHash = extRepo.get(SyncEntity.ATTACHMENT.ext, attachment.id)
                .text(SyncMeta.KEY_BLOB_HASH)

            // 缓存命中且云端已有 ⇒ 完全不必读文件、不必算哈希
            if (cachedHash != null && knownHashes.contains(cachedHash)) {
                skipped++
                onProgress(done, total)
                return@forEach
            }

            runCatching {
                val result = blobStore.upload(
                    client = client,
                    config = config,
                    file = file,
                    key = key,
                    knownHashes = knownHashes,
                )
                // 记住哈希：下次同步直接命中缓存，不必再读一遍文件
                extRepo.update(SyncEntity.ATTACHMENT.ext, attachment.id) {
                    it.putText(SyncMeta.KEY_BLOB_HASH, result.hash)
                }
                if (result.uploaded) uploaded++ else skipped++
            }.onFailure {
                failed++
            }
            onProgress(done, total)
        }

        return Result(uploaded = uploaded, skipped = skipped, failed = failed)
    }

    /**
     * 按需下载一个附件（用户点开时才调用）。
     *
     * @return true = 文件现在可用了
     */
    suspend fun downloadOnDemand(
        client: R2Client,
        config: R2Config,
        attachmentId: String,
        key: ByteArray,
    ): Boolean {
        val attachment = attachmentRepository.findById(attachmentId) ?: return false
        val target = storage.fileOf(attachment)
        if (target.isFile) return true // 已经有了

        val hash = extRepo.get(SyncEntity.ATTACHMENT.ext, attachmentId)
            .text(SyncMeta.KEY_BLOB_HASH)
            ?: computeAndCacheHash(attachmentId, target)

        return blobStore.download(client, config, hash, key, target)
    }

    /** 该附件的内容哈希（没有就算一次并缓存）。 */
    suspend fun hashOf(attachmentId: String): String? {
        extRepo.get(SyncEntity.ATTACHMENT.ext, attachmentId)
            .text(SyncMeta.KEY_BLOB_HASH)
            ?.let { return it }
        val attachment = attachmentRepository.findById(attachmentId) ?: return null
        val file = storage.fileOf(attachment)
        if (!file.isFile) return null
        return computeAndCacheHash(attachmentId, file)
    }

    private suspend fun computeAndCacheHash(attachmentId: String, file: java.io.File): String {
        val hash = ContentHash.sha256(file)
        extRepo.update(SyncEntity.ATTACHMENT.ext, attachmentId) {
            it.putText(SyncMeta.KEY_BLOB_HASH, hash)
        }
        return hash
    }
}
