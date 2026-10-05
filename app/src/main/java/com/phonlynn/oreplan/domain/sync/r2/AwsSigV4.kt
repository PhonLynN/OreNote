package com.phonlynn.oreplan.domain.sync.r2

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 签名 —— Cloudflare R2 的 S3 兼容 API 用它做认证。
 *
 * ## 为什么要自己实现，而不引 AWS SDK
 *
 * R2 的 S3 API 只需要**四个操作**（GET/PUT/HEAD/DELETE 对象），
 * 而 AWS SDK for Kotlin 会带进几十个模块与数千个类。
 * 本项目的 APK 只有 4.47MB，且"体积轻便"是用户的四条验收标准之一 ——
 * 为四个 HTTP 方法引一个 SDK 不划算。
 *
 * SigV4 本身是**纯函数**（HMAC-SHA256 链），没有平台依赖，因此可以：
 *  ① 零依赖；② 用单元测试逐字节对照 AWS 官方测试向量验证正确性。
 *
 * ## 规范来源
 *
 * `AWS Signature Version 4` 官方文档的签名步骤。关键是**签名串的构造顺序**
 * 与**规范化请求**的每一条规则 —— 任何一处（头名大小写、URI 编码、空行数量）
 * 出错，服务端只会回一个笼统的 `403 SignatureDoesNotMatch`，
 * 极难定位。所以这里把每一步都拆成有名字的小函数，便于测试与排查。
 *
 * ## 本实现刻意不支持的东西
 *
 * · 临时凭据（STS session token）—— 用户用的是长期 R2 API Token
 * · 分片上传的预签名 URL —— S5 用分片上传时会直接签请求，不需要预签名
 * · 多值 header、查询参数排序 —— R2 的这几个操作用不到（如需要再加）
 */
object AwsSigV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"

    /** 签名结果：要放进请求头的两项。 */
    data class Signed(
        val authorization: String,
        val amzDate: String,
        val contentSha256: String,
    )

    /**
     * 给一次请求签名。
     *
     * ⚠️ **[host] 与 [headers] 里的 `host` 必须一致** —— 参与签名的是 [headers]，
     * 而 [host] 只是用来给调用方一个"我签的是哪台主机"的自证。
     * 两者不一致时签名**不会**报错、却也不会体现 [host] 的差异（这是个静默陷阱），
     * 所以这里主动校验并抛错，而不是让它悄悄过去。
     *
     * @param method HTTP 方法（GET/PUT/HEAD/DELETE），大小写不敏感
     * @param host 形如 `<account>.r2.cloudflarestorage.com`
     * @param path `/bucket/key`（**已是编码后的形态**，见 [encodePath]）
     * @param query 规范化查询串（已排序，见 [canonicalQuery]）；无则空串
     * @param payloadSha256 请求体的十六进制 sha256；无体时为 [EMPTY_SHA256]
     * @param headers 参与签名的**规范头**（名字大小写不敏感，值会 trim）
     * @param region R2 固定 `auto`
     * @param amzDate `yyyyMMdd'T'HHmmss'Z'`
     */
    fun sign(
        method: String,
        host: String,
        path: String,
        query: String,
        payloadSha256: String,
        headers: Map<String, String>,
        accessKeyId: String,
        secretAccessKey: String,
        region: String = "auto",
        amzDate: String,
    ): Signed {
        val dateStamp = amzDate.substring(0, 8) // yyyyMMdd

        // ① 规范头：名字小写、按字典序、每个「名:值」后必须跟一个换行。
        //    参与签名的头集合**必须与**实际发出的头一致（至少包含 host、x-amz-*）。
        val lowercased = headers.entries.associate { it.key.lowercase() to it.value.trim() }
        val hostHeader = lowercased["host"]
        require(hostHeader == null || hostHeader == host) {
            "headers 里的 host（$hostHeader）与参数 host（$host）不一致 —— " +
                "签名只会采用 headers 里的值，参数 host 将被静默忽略"
        }
        val signedHeaderNames = lowercased.keys.sorted()
        val canonicalHeaders = buildString {
            signedHeaderNames.forEach { name ->
                append(name)
                append(':')
                append(lowercased.getValue(name))
                append('\n')
            }
        }
        val signedHeaders = signedHeaderNames.joinToString(";")

        // ② 规范请求
        val canonicalRequest = buildString {
            append(method.uppercase()).append('\n')
            append(path).append('\n')
            append(query).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(payloadSha256)
        }

        // ③ 待签串
        val scope = "$dateStamp/$region/$SERVICE/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        // ④ 派生签名密钥（逐级 HMAC，不可跳过中间层）
        val signingKey = deriveKey(secretAccessKey, dateStamp, region, SERVICE)
        val signature = hmacHex(signingKey, stringToSign)

        val authorization = "$ALGORITHM Credential=$accessKeyId/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        return Signed(authorization = authorization, amzDate = amzDate, contentSha256 = payloadSha256)
    }

    /**
     * 规范化查询串：按键名字典序排列，每个键值都做 URI 编码。
     *
     * R2 的 `ListObjectsV2` 会用到（`list-type=2&prefix=...&continuation-token=...`）。
     * 顺序错了签名就对不上，所以统一由本函数生成，**不在调用处手写字符串**。
     */
    fun canonicalQuery(params: Map<String, String>): String =
        params.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    /**
     * 路径编码。**每个 segment 单独编码**，斜杠保留。
     *
     * 为什么要单独编码：对象名里可能有中文、空格、`+`。
     * 整体编码会把 `/` 也变成 `%2F`，路径就散了；
     * 不编码则中文会以原始字节发出去，签名与传输不一致。
     */
    fun encodePath(bucket: String, key: String): String {
        val encodedKey = key.split('/').joinToString("/") { encode(it) }
        return "/$bucket/$encodedKey"
    }

    /** AWS 的 URI 编码规则：非 unreserved 字符一律百分号编码（含 `~` 之外的全部）。 */
    fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = b.toInt().toChar()
            when {
                c.isLetterOrDigit() && c.code < 128 -> append(c)
                c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                else -> append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
    }

    /** 空体的 sha256（常量，避免每次重算）。 */
    val EMPTY_SHA256: String = sha256Hex(ByteArray(0))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun deriveKey(secret: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, data: String): String = hmac(key, data).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
