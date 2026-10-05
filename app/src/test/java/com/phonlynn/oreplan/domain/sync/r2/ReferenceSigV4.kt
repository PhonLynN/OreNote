package com.phonlynn.oreplan.domain.sync.r2

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SigV4 的**独立参考实现** —— 只用于测试，不参与打包。
 *
 * ## 为什么要写两份
 *
 * 签名错了只会得到 `403 SignatureDoesNotMatch`，无法定位。
 * 一份实现自己测自己，无法发现"我对规范的理解整体偏了"这类错误。
 *
 * 这份参考实现刻意用**不同的写法**：
 *  · 用 `StringBuilder` 逐步累积，而不是 `buildString` + `append`
 *  · 头排序用显式插入排序，而不是 `sorted()`
 *  · 密钥派生用递归，而不是顺序调用
 *  · 十六进制用查表，而不是 `"%02x".format`
 *
 * 两份实现在同一输入上给出同一签名，才说明**规范本身被正确实现了**
 * （而不仅是"两份代码碰巧一样"）。
 */
internal object ReferenceSigV4 {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sign(
        method: String,
        host: String,
        path: String,
        query: String,
        payloadSha256: String,
        headers: Map<String, String>,
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        service: String,
        amzDate: String,
    ): String {
        val dateStamp = amzDate.substring(0, 8)

        // 头：小写名字、字典序（这里用显式插入排序，写法与主实现不同）
        val pairs = ArrayList<Pair<String, String>>()
        for ((rawName, rawValue) in headers) {
            val name = rawName.lowercase()
            val value = rawValue.trim()
            var inserted = false
            for (i in pairs.indices) {
                if (name < pairs[i].first) {
                    pairs.add(i, name to value)
                    inserted = true
                    break
                }
            }
            if (!inserted) pairs.add(name to value)
        }

        val canonicalHeaders = StringBuilder()
        for ((name, value) in pairs) {
            canonicalHeaders.append(name).append(':').append(value).append('\n')
        }
        val signedHeaders = pairs.joinToString(";") { it.first }

        val canonicalRequest = StringBuilder()
            .append(method.uppercase()).append('\n')
            .append(path).append('\n')
            .append(query).append('\n')
            .append(canonicalHeaders.toString()).append('\n')
            .append(signedHeaders).append('\n')
            .append(payloadSha256)
            .toString()

        val scope = listOf(dateStamp, region, service, "aws4_request").joinToString("/")
        val stringToSign = StringBuilder()
            .append("AWS4-HMAC-SHA256").append('\n')
            .append(amzDate).append('\n')
            .append(scope).append('\n')
            .append(hex(sha256(canonicalRequest.toByteArray(Charsets.UTF_8))))
            .toString()

        // 递归式密钥派生
        val signingKey = derive(
            ("AWS4$secretAccessKey").toByteArray(Charsets.UTF_8),
            listOf(dateStamp, region, service, "aws4_request"),
        )
        val signature = hex(hmac(signingKey, stringToSign))

        return "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun derive(key: ByteArray, steps: List<String>): ByteArray =
        if (steps.isEmpty()) key else derive(hmac(key, steps.first()), steps.drop(1))

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
