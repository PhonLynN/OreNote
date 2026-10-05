package com.phonlynn.oreplan.domain.sync.blob

import com.phonlynn.oreplan.domain.sync.r2.AwsSigV4
import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import com.phonlynn.oreplan.domain.sync.r2.R2Exception
import java.io.File

/**
 * 附件的**分片上传**。
 *
 * ## 为什么需要它
 *
 * `PutObject` 一次上传适合小文件，但一个几十 MB 的 PDF 走单次请求有两个实际问题：
 *  ① 中间断了要从头再来（移动网络下几乎必然发生）；
 *  ② 单请求的耗时可能触发超时。
 *
 * 分片上传把大文件切成若干片，每片独立上传，某片失败只需重传那一片。
 * R2 的 S3 API 已完整实现这一组操作（`CreateMultipartUpload` /
 * `UploadPart` / `CompleteMultipartUpload` / `AbortMultipartUpload`）。
 *
 * ## 分片大小的选择
 *
 * S3 要求除最后一片外**每片至少 5MB**，最多 10000 片。
 * 取 8MB：既满足下限，又让 80MB 的文件只需 10 片（请求数少）；
 * 再大的文件也远不到 10000 片的上限（8MB × 10000 = 80GB）。
 *
 * ## 断点续传
 *
 * 进度（uploadId + 已完成的分片号）由调用方持久化 —— 本类不碰存储，
 * 只负责"按给定的已完成集合继续传"。这样重新同步时不必从零开始。
 */
class MultipartUploader(
    private val partSize: Int = DEFAULT_PART_SIZE,
) {

    /** 一片已上传的结果：分片号 + ETag（完成时必须按顺序带上）。 */
    data class UploadedPart(val partNumber: Int, val eTag: String)

    data class Session(val uploadId: String, val objectKey: String)

    /** 开始一次分片上传。 */
    suspend fun begin(client: R2Client, config: R2Config, objectKey: String): Session {
        val response = client.postObject(
            key = objectKey,
            query = "uploads=",
            // 分片上传的发起是 POST ?uploads，返回体里有 UploadId
        )
        if (!response.isSuccess) throw R2Exception.from(response, "发起分片上传失败")
        val uploadId = extractTag(response.bodyText, "UploadId")
            ?: throw R2Exception(response.status, "云端没有返回 UploadId", response.bodyText.take(256))
        return Session(uploadId, objectKey)
    }

    /**
     * 上传所有分片。
     *
     * @param alreadyUploaded 已完成的分片（断点续传用）；这些会被跳过
     * @param onProgress 每传完一片回调一次（已传片数, 总片数），供界面显示进度
     * @return 全部完成的分片（按分片号升序，这是 Complete 要求的顺序）
     */
    suspend fun uploadParts(
        client: R2Client,
        config: R2Config,
        session: Session,
        file: File,
        alreadyUploaded: List<UploadedPart> = emptyList(),
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<UploadedPart> {
        val total = partCount(file.length())
        val done = alreadyUploaded.associateBy { it.partNumber }.toMutableMap()

        for (partNumber in 1..total) {
            if (done.containsKey(partNumber)) continue
            val (offset, length) = partRange(file.length(), partNumber)
            val bytes = readPart(file, offset, length)
            val response = client.putObjectPart(
                objectKey = session.objectKey,
                uploadId = session.uploadId,
                partNumber = partNumber,
                body = bytes,
            )
            if (!response.isSuccess) {
                throw R2Exception.from(response, "上传第 $partNumber 片失败")
            }
            // ⚠️ 分片的 ETag 必须原样记下来：Complete 时要按顺序提交，
            // 服务端据此校验每一片确实是它收到的那份。
            val eTag = response.eTag?.trim('"')
                ?: throw R2Exception(response.status, "第 $partNumber 片没有返回 ETag", "")
            done[partNumber] = UploadedPart(partNumber, eTag)
            onProgress(done.size, total)
        }
        return done.values.sortedBy { it.partNumber }
    }

    /** 提交，完成整个对象。 */
    suspend fun complete(
        client: R2Client,
        config: R2Config,
        session: Session,
        parts: List<UploadedPart>,
    ) {
        val response = client.completeMultipart(
            objectKey = session.objectKey,
            uploadId = session.uploadId,
            parts = parts.map { it.partNumber to it.eTag },
        )
        if (!response.isSuccess) throw R2Exception.from(response, "提交分片上传失败")
    }

    /**
     * 放弃一次上传，让云端回收已上传的分片。
     *
     * **必须尽力调用**：不放弃的话，那些分片会一直占着存储并计费，
     * 而用户那边只看到"上传失败了"。失败时抛异常由调用方吞掉 ——
     * 清理失败不该掩盖原始错误。
     */
    suspend fun abort(client: R2Client, config: R2Config, session: Session) {
        client.abortMultipart(objectKey = session.objectKey, uploadId = session.uploadId)
    }

    // ---------------------------------------------------------------- 内部

    /** 分片数。空文件也算 1 片（否则 Complete 会因为没有分片而失败）。 */
    fun partCount(size: Long): Int =
        if (size <= 0L) 1 else ((size + partSize - 1) / partSize).toInt()

    /** 第 N 片（1 基）在文件里的偏移与长度。 */
    fun partRange(size: Long, partNumber: Int): Pair<Long, Int> {
        val offset = (partNumber - 1).toLong() * partSize
        val length = minOf(partSize.toLong(), size - offset).coerceAtLeast(0L)
        return offset to length.toInt()
    }

    private fun readPart(file: File, offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return bytes
    }

    /** 从 XML 里取一个标签的值（分片上传的响应形状固定，不值得引解析器）。 */
    private fun extractTag(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** 8MB：满足 S3 的 5MB 下限，且 80MB 只需 10 片。 */
        const val DEFAULT_PART_SIZE = 8 * 1024 * 1024

        /** 超过这个大小就走分片；以下用单次 PutObject。 */
        const val MULTIPART_THRESHOLD = 8L * 1024 * 1024
    }
}

/** 供测试与配置使用的 AAD 构造（与实体对象一致：绑定对象 key）。 */
internal fun blobAad(config: R2Config, hash: String): ByteArray =
    config.key(ContentHash.objectKey(hash)).toByteArray()

/** 便于测试：把对象 key 转成完整路径。 */
internal fun blobObjectKey(config: R2Config, hash: String): String =
    config.key(ContentHash.objectKey(hash))

/** 供 [AwsSigV4] 的调用方复用的空串哈希（分片请求没有整体体）。 */
internal val EMPTY_SHA: String get() = AwsSigV4.EMPTY_SHA256
