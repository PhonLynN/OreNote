package com.phonlynn.oreplan.domain.sync.r2

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * R2 的 S3 兼容 API 客户端（**只用 JDK 自带的 `HttpURLConnection`**）。
 *
 * ## 为什么不引 OkHttp / AWS SDK
 *
 * 需要的只有四个操作（GET/PUT/HEAD/DELETE 对象）加一个 List。
 * OkHttp 会给 4.47MB 的 APK 添一个网络栈，AWS SDK 更是几十个模块。
 * 而"体积轻便"是用户的四条验收标准之一。
 * `HttpURLConnection` 支持全部所需能力（自定义头、流式读写、超时），零依赖。
 *
 * ## 这个类的边界
 *
 * 它**只管 HTTP 与签名**，不理解"实体""墓碑""合并"这些概念（那是上层的事）。
 * 因此它可以被完整地单元测试（注入一个假的 [Transport]）。
 *
 * ## 错误语义
 *
 * R2 的错误响应体是 XML，但**错误码在 HTTP 状态里**，所以这里按状态码分类，
 * 并把响应体前若干字节带进异常消息（排查时那串 XML 往往直接说明问题）。
 */
class R2Client(
    private val config: R2Config,
    private val transport: Transport = HttpUrlConnectionTransport(),
    /** 注入时钟，便于测试签名与时间相关的分支。 */
    private val clock: () -> Instant = { Instant.now() },
) {

    /** 一次响应：状态码 + 体 + 关键头。 */
    data class Response(
        val status: Int,
        val body: ByteArray,
        val eTag: String?,
        val contentLength: Long,
    ) {
        val isSuccess: Boolean get() = status in 200..299
        val isNotFound: Boolean get() = status == 404
        /** 条件写失败（If-Match / If-None-Match 不满足）—— 并发冲突的信号。 */
        val isPreconditionFailed: Boolean get() = status == 412
        val bodyText: String get() = String(body, Charsets.UTF_8)
    }

    /** 一次 ListObjectsV2 的结果。 */
    data class ListResult(
        val keys: List<ObjectInfo>,
        val nextToken: String?,
    )

    data class ObjectInfo(val key: String, val eTag: String, val size: Long)

    // ---------------------------------------------------------------- 操作

    /** 探测桶是否可达（设置页的"测试连接"用它）。 */
    suspend fun headBucket(): Response =
        request("HEAD", path = "/${config.bucket}", query = "")

    /** 取对象。不存在时返回 404 响应而不是抛异常（调用方要区分"没有"与"出错"）。 */
    suspend fun getObject(key: String): Response =
        request("GET", path = AwsSigV4.encodePath(config.bucket, key), query = "")

    /** 取对象的元信息（不下载体）。用于判断远端是否已有、以及拿 ETag。 */
    suspend fun headObject(key: String): Response =
        request("HEAD", path = AwsSigV4.encodePath(config.bucket, key), query = "")

    /**
     * 上传对象。
     *
     * @param ifNoneMatch 传 `"*"` 表示"仅当对象**不存在**时才写"（首次上传，
     *   防止覆盖别的设备已经放上去的内容）；不传则无条件写。
     * @param ifMatch 传已知 ETag 表示"仅当远端还是这一版时才写"——
     *   **这是并发保护的核心**：两台设备同时改同一对象时，
     *   后写的那台会拿到 412，从而知道要重新合并而不是硬覆盖。
     */
    suspend fun putObject(
        key: String,
        body: ByteArray,
        contentType: String = "application/octet-stream",
        ifNoneMatch: String? = null,
        ifMatch: String? = null,
    ): Response {
        val extra = buildMap {
            put("content-type", contentType)
            ifNoneMatch?.let { put("if-none-match", it) }
            ifMatch?.let { put("if-match", it) }
        }
        return request(
            method = "PUT",
            path = AwsSigV4.encodePath(config.bucket, key),
            query = "",
            body = body,
            extraHeaders = extra,
        )
    }

    suspend fun deleteObject(key: String): Response =
        request("DELETE", path = AwsSigV4.encodePath(config.bucket, key), query = "")

    // ---------------------------------------------------------------- 分片上传

    /**
     * 发起一次分片上传（`POST <key>?uploads`）。
     *
     * 响应体里带 `UploadId`，后续每一片都要带上它。
     */
    suspend fun postObject(key: String, query: String): Response =
        request(
            method = "POST",
            path = AwsSigV4.encodePath(config.bucket, key),
            query = query,
        )

    /**
     * 上传一个分片（`PUT <key>?partNumber=N&uploadId=...`）。
     *
     * ⚠️ 响应头里的 **ETag 必须留好**：Complete 时要按分片号顺序提交，
     * 服务端据此校验每一片确实是它收到的那份。
     */
    suspend fun putObjectPart(
        objectKey: String,
        uploadId: String,
        partNumber: Int,
        body: ByteArray,
    ): Response = request(
        method = "PUT",
        path = AwsSigV4.encodePath(config.bucket, objectKey),
        query = AwsSigV4.canonicalQuery(
            mapOf("partNumber" to partNumber.toString(), "uploadId" to uploadId),
        ),
        body = body,
    )

    /**
     * 提交分片上传（`POST <key>?uploadId=...`，体是分片清单 XML）。
     *
     * 清单**必须按分片号升序**，否则 S3 会拒绝（`InvalidPartOrder`）。
     */
    suspend fun completeMultipart(
        objectKey: String,
        uploadId: String,
        parts: List<Pair<Int, String>>,
    ): Response {
        val xml = buildString {
            append("<CompleteMultipartUpload>")
            parts.forEach { (number, eTag) ->
                append("<Part><PartNumber>").append(number).append("</PartNumber>")
                append("<ETag>\"").append(eTag).append("\"</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }.toByteArray(Charsets.UTF_8)
        return request(
            method = "POST",
            path = AwsSigV4.encodePath(config.bucket, objectKey),
            query = AwsSigV4.canonicalQuery(mapOf("uploadId" to uploadId)),
            body = xml,
        )
    }

    /**
     * 放弃一次分片上传（`DELETE <key>?uploadId=...`）。
     *
     * **必须尽力调用**：不放弃的话，已上传的分片会一直占着存储并计费，
     * 而用户那边只看到"上传失败了"。
     */
    suspend fun abortMultipart(objectKey: String, uploadId: String): Response =
        request(
            method = "DELETE",
            path = AwsSigV4.encodePath(config.bucket, objectKey),
            query = AwsSigV4.canonicalQuery(mapOf("uploadId" to uploadId)),
        )

    /**
     * 列出某前缀下的对象（首次同步时用来知道云端已有什么）。
     *
     * 自动翻页：一次最多 1000 个，同步空间里的对象数可能远超这个数，
     * 调用方不该关心分页。
     */
    suspend fun listObjects(prefix: String, pageSize: Int = 1000): List<ObjectInfo> {
        val out = ArrayList<ObjectInfo>()
        var token: String? = null
        do {
            val params = buildMap {
                put("list-type", "2")
                put("prefix", prefix)
                put("max-keys", pageSize.toString())
                token?.let { put("continuation-token", it) }
            }
            val response = request(
                method = "GET",
                path = "/${config.bucket}",
                query = AwsSigV4.canonicalQuery(params),
            )
            if (!response.isSuccess) throw R2Exception.from(response, "列出对象失败")
            val page = R2ListParser.parse(response.bodyText)
            out += page.keys
            token = page.nextToken
        } while (token != null)
        return out
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun request(
        method: String,
        path: String,
        query: String,
        body: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val payloadHash = if (body == null) AwsSigV4.EMPTY_SHA256 else AwsSigV4.sha256Hex(body)
        val amzDate = AMZ_DATE.format(clock().atZone(ZoneOffset.UTC))

        // 参与签名的头 = host + x-amz-* + 调用方指定的（含 content-type）
        val headers = buildMap {
            put("host", config.host)
            put("x-amz-content-sha256", payloadHash)
            put("x-amz-date", amzDate)
            extraHeaders.forEach { (k, v) -> put(k.lowercase(), v) }
        }

        val signed = AwsSigV4.sign(
            method = method,
            host = config.host,
            path = path,
            query = query,
            payloadSha256 = payloadHash,
            headers = headers,
            accessKeyId = config.accessKeyId,
            secretAccessKey = config.secretAccessKey,
            region = config.region,
            amzDate = amzDate,
        )

        val url = buildString {
            append("https://").append(config.host).append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }

        val sentHeaders = buildMap {
            // ⚠️ **`host` 只参与签名，不放进请求头**。
            // `HttpURLConnection` 把 Host 列为受限头（由 URL 自动生成），
            // 手动 setRequestProperty("Host", ...) 会被**静默忽略**，
            // 于是"发出的头"与"签名的头"列表不一致 —— 排查时会误以为是签名算错了。
            // 服务端算签名时用的是它自己收到的 Host（就是 URL 里那个），所以两边一致。
            headers.forEach { (k, v) -> if (k != "host") put(k, v) }
            put("Authorization", signed.authorization)
            if (body != null) put("Content-Length", body.size.toString())
        }

        return transport.execute(method, url, sentHeaders, body)
    }
}

/** 传输层抽象：真实实现走 `HttpURLConnection`，测试注入假的。 */
interface Transport {
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): R2Client.Response
}

/**
 * 真实传输：JDK `HttpURLConnection`。
 *
 * 刻意设置**超时**：没有超时的网络调用在弱网下会挂住协程 ——
 * 表现为"同步卡住不动、还不报错"，是后台任务里最难查的一类问题。
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : Transport {

    override suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): R2Client.Response {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            // 错误响应体在 errorStream，不在 inputStream —— 少了这一步就看不到 R2 的报错原因
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)
            return R2Client.Response(
                status = status,
                body = responseBody,
                eTag = connection.getHeaderField("ETag"),
                contentLength = connection.getHeaderFieldLong("Content-Length", -1L),
            )
        } finally {
            connection.disconnect()
        }
    }
}

/** R2 返回的非 2xx。消息里带上响应体前 512 字节 —— R2 的 XML 直接说明原因。 */
class R2Exception(
    val status: Int,
    message: String,
    val bodySnippet: String,
) : Exception(message) {

    /** 凭据无效 / 权限不足。设置页据此提示"检查 Access Key 与权限"。 */
    val isAuthFailure: Boolean get() = status == 401 || status == 403

    /** 条件写失败：并发冲突，上层应重新合并后重试。 */
    val isConflict: Boolean get() = status == 412

    companion object {
        fun from(response: R2Client.Response, what: String): R2Exception {
            val snippet = response.bodyText.take(512)
            val hint = when (response.status) {
                401, 403 -> "（凭据或权限问题：检查 Access Key ID / Secret 与令牌的 Object Read & Write 权限）"
                404 -> "（桶或对象不存在：检查存储桶名与 Account ID）"
                412 -> "（并发冲突：远端已被别的设备改过）"
                else -> ""
            }
            return R2Exception(
                status = response.status,
                message = "$what：HTTP ${response.status}$hint",
                bodySnippet = snippet,
            )
        }
    }
}

private val AMZ_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/**
 * R2 的连接与身份。
 *
 * [host] 由 accountId 推出（R2 的 S3 端点固定是
 * `<ACCOUNT_ID>.r2.cloudflarestorage.com`）。
 * [region] 恒为 `auto` —— R2 用它做 S3 兼容，而不是真实区域。
 */
data class R2Config(
    val accountId: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val keyPrefix: String = "orenote/",
    val region: String = "auto",
) {
    val host: String get() = "$accountId.r2.cloudflarestorage.com"

    /** 同步空间下某个逻辑路径对应的对象 key。 */
    fun key(vararg parts: String): String =
        keyPrefix.trimEnd('/') + "/" + parts.joinToString("/")

    companion object {
        const val ENTITIES_DIR = "entities"
        const val BLOBS_DIR = "blobs"
        const val META_FILE = "meta.json"
    }
}
