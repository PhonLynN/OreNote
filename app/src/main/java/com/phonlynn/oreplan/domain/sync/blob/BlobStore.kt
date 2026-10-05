package com.phonlynn.oreplan.domain.sync.blob

import com.phonlynn.oreplan.domain.sync.crypto.SyncCrypto
import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附件二进制在云端的读写。
 *
 * ## 存储形状
 *
 * ```
 * <prefix>/blobs/<hash 前两位>/<完整 hash>     ← 内容寻址，密文
 * ```
 *
 * ## 加密与去重的关系（要说清楚，否则会写错）
 *
 * ⚠️ **去重是"密文层面的去重"，不是"明文层面"**。
 *
 * 同一份明文每次加密都会因随机 nonce 产生**不同密文**，所以：
 *  · 不能靠"算出密文哈希"来判断云端有没有 —— 每次都不一样；
 *  · 去重的依据是**明文的 sha256**（就是对象名）；
 *  · 但"对象名相同"只说明**本地**算出的明文哈希相同，
 *    云端那份是不是同一份明文，取决于它当初也是用同一把主密钥加密的。
 *
 * 结论：**同一把主密钥下**去重正确（同一账号的用户就是这种情况）；
 * 换过密钥后，同一个 hash 对应的云端密文解不开 —— 那种情况下
 * 本实现会**重新上传**（`HEAD` 只能证明对象存在、不能证明能解开），
 * 覆盖掉那份解不开的旧对象。这正是我们要的：用户换了密钥之后，
 * 数据应当可以被新密钥重新建立起来。
 */
@Singleton
class BlobStore @Inject constructor(
    /**
     * 分片上传器。有默认值是为了让单元测试能直接 `BlobStore()` 构造，
     * 而 Dagger 注入时用无参构造 —— 所以这里**必须同时有 `@Inject` 与默认值**，
     * 否则 Dagger 会因为"参数有默认值"而找不到可用的构造。
     */
    private val uploader: MultipartUploader = MultipartUploader(),
) {

    /** 上传结果：是否真的传了（false = 云端已有，跳过）。 */
    data class UploadResult(val hash: String, val uploaded: Boolean, val bytes: Long)

    /**
     * 上传一个文件（如果云端还没有）。
     *
     * @param knownHashes 本次同步已知的云端对象集合（避免每个文件一次 HEAD 往返）
     * @param onProgress 分片进度回调
     */
    suspend fun upload(
        client: R2Client,
        config: R2Config,
        file: File,
        key: ByteArray,
        knownHashes: Set<String> = emptySet(),
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): UploadResult {
        val hash = ContentHash.sha256(file)
        val objectKey = blobObjectKey(config, hash)

        // 云端已有同 hash 的对象 ⇒ 跳过上传。
        // 这是内容寻址最大的收益：同一张图被多条记录引用时只传一次。
        if (knownHashes.contains(hash) || client.headObject(objectKey).isSuccess) {
            return UploadResult(hash, uploaded = false, bytes = file.length())
        }

        val aad = objectKey.toByteArray()
        if (file.length() < MultipartUploader.MULTIPART_THRESHOLD) {
            val cipher = SyncCrypto.encrypt(file.readBytes(), key, aad = aad)
            val response = client.putObject(objectKey, cipher)
            if (!response.isSuccess) {
                throw com.phonlynn.oreplan.domain.sync.r2.R2Exception.from(response, "上传附件失败")
            }
        } else {
            uploadMultipart(client, config, file, objectKey, key, aad, onProgress)
        }
        return UploadResult(hash, uploaded = true, bytes = file.length())
    }

    /**
     * 下载一个附件并落到 [target]（如果云端有）。
     *
     * @return true = 下到了；false = 云端没有这个对象
     */
    suspend fun download(
        client: R2Client,
        config: R2Config,
        hash: String,
        key: ByteArray,
        target: File,
    ): Boolean {
        val objectKey = blobObjectKey(config, hash)
        val response = client.getObject(objectKey)
        if (!response.isSuccess) return false

        val plain = SyncCrypto.decrypt(response.body, key, aad = objectKey.toByteArray())
        // 校验完整性：下载后重算哈希必须与对象名一致。
        // 对不上说明传输损坏或被篡改 —— 那种文件写进本地只会让用户更困惑，
        // 不如明确失败（下次同步会重试）。
        val actual = ContentHash.sha256(plain)
        if (actual != hash) {
            throw BlobIntegrityException(hash, actual)
        }
        target.parentFile?.mkdirs()
        target.writeBytes(plain)
        return true
    }

    /** 云端是否已有这个哈希。（设置页/统计用） */
    suspend fun exists(client: R2Client, config: R2Config, hash: String): Boolean =
        client.headObject(blobObjectKey(config, hash)).isSuccess

    // ---------------------------------------------------------------- 内部

    private suspend fun uploadMultipart(
        client: R2Client,
        config: R2Config,
        file: File,
        objectKey: String,
        key: ByteArray,
        aad: ByteArray,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ) {
        /*
         * ⚠️ **先整体加密，再切分片**（而不是逐片加密）。
         *
         * 一开始我写的是"逐片加密、每片的 AAD 带上片号"，看起来更严谨，
         * 但它让**单次上传与分片上传产出的对象格式不同**：
         *  · 单次：一个完整 GCM 密文，AAD = objectKey
         *  · 分片：N 个独立密文拼接，AAD 各带片号
         * 而下载侧并不知道这个对象当初是怎么传的 —— 于是**大附件传上去就再也解不开**。
         * 这个 bug 被 `BlobStoreTest.大文件走分片上传且往返一致` 抓到。
         *
         * 改成"整体加密后切密文"之后，两条路径产出的对象**格式完全一致**，
         * 下载侧永远只需要 `decrypt(整个对象, aad = objectKey)`。
         * 用一点点内存（密文要整个放内存）换掉了一整类格式不一致的 bug。
         *
         * 断点续传的价值不受影响：它省的是"不重传已成功的片"，
         * 而那取决于分片号与 ETag，与加密方式无关 —— 重新加密再切一遍很便宜。
         */
        val cipher = SyncCrypto.encrypt(file.readBytes(), key, aad = aad)

        val session = uploader.begin(client, config, objectKey)
        try {
            val total = uploader.partCount(cipher.size.toLong())
            val uploaded = ArrayList<MultipartUploader.UploadedPart>()
            for (partNumber in 1..total) {
                val (offset, length) = uploader.partRange(cipher.size.toLong(), partNumber)
                val slice = cipher.copyOfRange(offset.toInt(), (offset + length).toInt())
                val response = client.putObjectPart(objectKey, session.uploadId, partNumber, slice)
                if (!response.isSuccess) {
                    throw com.phonlynn.oreplan.domain.sync.r2.R2Exception.from(
                        response,
                        "上传第 $partNumber 片失败",
                    )
                }
                val eTag = response.eTag?.trim('"')
                    ?: throw com.phonlynn.oreplan.domain.sync.r2.R2Exception(
                        response.status,
                        "第 $partNumber 片没有返回 ETag",
                        "",
                    )
                uploaded += MultipartUploader.UploadedPart(partNumber, eTag)
                onProgress(partNumber, total)
            }
            uploader.complete(client, config, session, uploaded)
        } catch (e: Throwable) {
            // 失败时**尽力放弃**，否则已传的分片会一直占存储并计费，
            // 而用户只看到"上传失败了"。清理本身的失败不该掩盖原始错误。
            runCatching { uploader.abort(client, config, session) }
            throw e
        }
    }

    private fun readPart(file: File, offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return bytes
    }
}

/** 下载后哈希对不上：传输损坏或内容被改过。**可重试**（不是永久错误）。 */
class BlobIntegrityException(val expected: String, val actual: String) :
    Exception("附件完整性校验失败（期望 ${expected.take(12)}…，实际 ${actual.take(12)}…）")
