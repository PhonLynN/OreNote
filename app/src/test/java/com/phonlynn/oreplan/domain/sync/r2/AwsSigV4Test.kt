package com.phonlynn.oreplan.domain.sync.r2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SigV4 签名的正确性。
 *
 * ## 为什么这一层必须有测试
 *
 * 签名错了，R2 只会回一个笼统的 `403 SignatureDoesNotMatch` ——
 * 它**不会告诉你哪一步错了**。而签名串里有十几处细节（头名大小写、排序、
 * 换行个数、URI 编码大小写、密钥派生层级），任何一处都可能写错。
 * 在真机上排查这件事等于盲猜，所以这里把每一步都钉死。
 *
 * ## 验证策略：自证 + 交叉
 *
 * 1. **自证**：手工按规范算一遍关键中间值（如 sha256 空串的已知常量）
 * 2. **交叉**：[ReferenceSigV4] 是本测试里**独立重写**的一份实现
 *    （不同写法、不同代码路径），两者对同一输入必须给出同一签名。
 *    这能抓出「实现内部的对称错误」—— 那种错误用固定期望值也能抓到，
 *    但独立实现还能顺带验证"我理解的规范"与"代码写法"两件事一致。
 * 3. **敏感性**：任何输入变一位，签名必须变 —— 否则说明某个字段没参与签名。
 */
class AwsSigV4Test {

    private val accessKey = "AKIAIOSFODNN7EXAMPLE"
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val amzDate = "20260830T123600Z"
    private val host = "abc123.r2.cloudflarestorage.com"

    /** 空串的 sha256 是固定常量，用它验证摘要实现本身没写反。 */
    @Test
    fun `空串 sha256 等于已知常量`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AwsSigV4.EMPTY_SHA256,
        )
    }

    @Test
    fun `sha256 对已知输入正确`() {
        // "abc" 的 sha256 是公开的标准测试向量
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AwsSigV4.sha256Hex("abc".toByteArray()),
        )
    }

    /**
     * URI 编码必须严格按 AWS 规则：unreserved 之外全部百分号编码，
     * **大写**十六进制，空格是 `%20` 而不是 `+`。
     */
    @Test
    fun `URI 编码符合 AWS 规则`() {
        assertEquals("abcXYZ019", AwsSigV4.encode("abcXYZ019"))
        assertEquals("-_.~", AwsSigV4.encode("-_.~"))
        assertEquals("a%20b", AwsSigV4.encode("a b"))
        assertEquals("a%2Bb", AwsSigV4.encode("a+b"))
        assertEquals("a%2Fb", AwsSigV4.encode("a/b"))
        // 大写十六进制
        assertEquals("%2B", AwsSigV4.encode("+"))
        // 斜杠在 key 里保留、在其它地方编码
        assertEquals("/bucket/a/b", AwsSigV4.encodePath("bucket", "a/b"))
    }

    /** 中文对象名：按 UTF-8 逐字节编码。 */
    @Test
    fun `中文路径按 UTF-8 编码`() {
        val encoded = AwsSigV4.encodePath("bucket", "笔记/2026.bin")
        // 「笔」= E7 AC 94，「记」= E8 AE B0
        assertEquals("/bucket/%E7%AC%94%E8%AE%B0/2026.bin", encoded)
    }

    @Test
    fun `规范化查询串按键名排序并编码`() {
        val q = AwsSigV4.canonicalQuery(
            mapOf("prefix" to "orenote/", "list-type" to "2", "max-keys" to "1000"),
        )
        assertEquals("list-type=2&max-keys=1000&prefix=orenote%2F", q)
    }

    // ---------------------------------------------------------------- 交叉验证

    @Test
    fun `与独立实现给出的签名一致_GET`() {
        val path = "/mybucket/orenote/entities/item/abc.bin"
        val headers = mapOf(
            "host" to host,
            "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256,
            "x-amz-date" to amzDate,
        )

        val ours = AwsSigV4.sign(
            method = "GET",
            host = host,
            path = path,
            query = "",
            payloadSha256 = AwsSigV4.EMPTY_SHA256,
            headers = headers,
            accessKeyId = accessKey,
            secretAccessKey = secret,
            amzDate = amzDate,
        )
        val theirs = ReferenceSigV4.sign(
            method = "GET",
            host = host,
            path = path,
            query = "",
            payloadSha256 = AwsSigV4.EMPTY_SHA256,
            headers = headers,
            accessKeyId = accessKey,
            secretAccessKey = secret,
            region = "auto",
            service = "s3",
            amzDate = amzDate,
        )

        assertEquals(theirs, ours.authorization)
    }

    @Test
    fun `与独立实现给出的签名一致_PUT_带体`() {
        val body = "hello oreplan".toByteArray()
        val bodyHash = AwsSigV4.sha256Hex(body)
        val path = "/mybucket/orenote/entities/board_card/x.bin"
        val headers = mapOf(
            "host" to host,
            "content-type" to "application/octet-stream",
            "x-amz-content-sha256" to bodyHash,
            "x-amz-date" to amzDate,
        )

        val ours = AwsSigV4.sign(
            method = "PUT",
            host = host,
            path = path,
            query = "",
            payloadSha256 = bodyHash,
            headers = headers,
            accessKeyId = accessKey,
            secretAccessKey = secret,
            amzDate = amzDate,
        )
        val theirs = ReferenceSigV4.sign(
            method = "PUT",
            host = host,
            path = path,
            query = "",
            payloadSha256 = bodyHash,
            headers = headers,
            accessKeyId = accessKey,
            secretAccessKey = secret,
            region = "auto",
            service = "s3",
            amzDate = amzDate,
        )

        assertEquals(theirs, ours.authorization)
    }

    @Test
    fun `与独立实现给出的签名一致_带查询串`() {
        val query = AwsSigV4.canonicalQuery(mapOf("list-type" to "2", "prefix" to "orenote/"))
        val path = "/mybucket"
        val headers = mapOf(
            "host" to host,
            "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256,
            "x-amz-date" to amzDate,
        )

        val ours = AwsSigV4.sign(
            method = "GET",
            host = host,
            path = path,
            query = query,
            payloadSha256 = AwsSigV4.EMPTY_SHA256,
            headers = headers,
            accessKeyId = accessKey,
            secretAccessKey = secret,
            amzDate = amzDate,
        )
        val theirs = ReferenceSigV4.sign(
            method = "GET",
            host = host,
            path = path,
            query = query,
            payloadSha256 = AwsSigV4.EMPTY_SHA256,
            headers = headers,
            accessKeyId = accessKey,
            secretAccessKey = secret,
            region = "auto",
            service = "s3",
            amzDate = amzDate,
        )

        assertEquals(theirs, ours.authorization)
    }

    // ---------------------------------------------------------------- 敏感性与格式

    /**
     * 头**顺序不影响**签名结果 —— 规范化会排序。
     * 这条很重要：否则调用方传 Map 的顺序会变成隐患。
     */
    @Test
    fun `头顺序不影响签名`() {
        val a = authOf(headers = linkedMapOf("host" to host, "x-amz-date" to amzDate, "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256))
        val b = authOf(headers = linkedMapOf("x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256, "x-amz-date" to amzDate, "host" to host))
        assertEquals(a, b)
    }

    @Test
    fun `方法大小写不影响签名`() {
        assertEquals(authOf(method = "get"), authOf(method = "GET"))
    }

    /** 每一处输入变一点，签名都必须变 —— 否则说明该字段没进签名。 */
    @Test
    fun `任何输入变化都会改变签名`() {
        val base = authOf()
        assertNotEquals("路径变了签名必须变", base, authOf(path = "/mybucket/other.bin"))
        assertNotEquals("密钥变了签名必须变", base, authOf(secret = "another-secret"))
        assertNotEquals("日期变了签名必须变", base, authOf(date = "20260830T123601Z"))
        assertNotEquals("方法变了签名必须变", base, authOf(method = "PUT"))
        assertNotEquals("查询串变了签名必须变", base, authOf(query = "list-type=2"))
        assertNotEquals("体哈希变了签名必须变", base, authOf(payloadHash = "00".repeat(32)))
        assertNotEquals(
            "host 变了签名必须变",
            base,
            authOf(
                hostOverride = "other.r2.cloudflarestorage.com",
                headers = mapOf(
                    "host" to "other.r2.cloudflarestorage.com",
                    "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256,
                    "x-amz-date" to amzDate,
                ),
            ),
        )
        assertNotEquals("access key 变了签名必须变", base, authOf(accessKeyOverride = "AKIAOTHERKEY"))
        assertNotEquals("region 变了签名必须变", base, authOf(regionOverride = "us-east-1"))
    }

    /**
     * **host 一致性校验**：`headers["host"]` 与参数 `host` 不一致时必须显式报错。
     *
     * 不报错的话，参数 `host` 会被静默忽略（签名只采用 headers 里的值）——
     * 调用方以为自己签的是 A 主机，实际签的是 B，而签名本身还"看起来正常"。
     * 这类错误在真机上表现为莫名其妙的 403。
     */
    @Test
    fun `host 参数与头不一致时抛错`() {
        val error = runCatching {
            AwsSigV4.sign(
                method = "GET",
                host = "a.r2.cloudflarestorage.com",
                path = "/b/k",
                query = "",
                payloadSha256 = AwsSigV4.EMPTY_SHA256,
                headers = mapOf(
                    "host" to "b.r2.cloudflarestorage.com",
                    "x-amz-date" to amzDate,
                    "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256,
                ),
                accessKeyId = accessKey,
                secretAccessKey = secret,
                amzDate = amzDate,
            )
        }.exceptionOrNull()

        assertTrue("必须抛 IllegalArgument 而不是静默忽略", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("不一致"))
    }

    /** Authorization 头必须是 AWS 认可的确切格式。 */
    @Test
    fun `授权头格式符合规范`() {
        val signed = signWith()

        assertTrue("必须以算法名开头", signed.authorization.startsWith("AWS4-HMAC-SHA256 "))
        assertTrue("必须含 Credential", signed.authorization.contains("Credential=$accessKey/20260830/auto/s3/aws4_request"))
        assertTrue("必须含 SignedHeaders", signed.authorization.contains("SignedHeaders="))
        assertTrue("必须含 Signature", signed.authorization.contains("Signature="))
        // 算法名与 Credential 之间是空格，其余用逗号分隔
        assertTrue(
            signed.authorization.substringAfter("AWS4-HMAC-SHA256 ").contains(", SignedHeaders="),
        )
        // 签名是 64 位小写十六进制
        val sig = signed.authorization.substringAfter("Signature=")
        assertEquals(64, sig.length)
        assertTrue("签名必须是十六进制", sig.all { it in "0123456789abcdef" })
    }

    /** 参与签名的头列表必须与实际发出的头一致（含 host 与 x-amz-*）。 */
    @Test
    fun `签名头列表包含 host 与全部 x-amz 头`() {
        val signed = signWith(
            headers = mapOf(
                "host" to host,
                "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256,
                "x-amz-date" to amzDate,
                "content-type" to "application/octet-stream",
            ),
        )
        val signedHeaders = signed.authorization
            .substringAfter("SignedHeaders=")
            .substringBefore(",")

        assertEquals(
            "必须小写、排序、分号分隔",
            "content-type;host;x-amz-content-sha256;x-amz-date",
            signedHeaders,
        )
    }

    /** 头的值要 trim（AWS 明确要求去掉首尾空白）。 */
    @Test
    fun `头的值被 trim`() {
        val withSpace = signWith(headers = mapOf("host" to host, "x-amz-date" to " $amzDate ", "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256))
        assertEquals(authOf(), withSpace.authorization)
    }

    // ---------------------------------------------------------------- 辅助

    private fun signWith(
        method: String = "GET",
        path: String = "/mybucket/orenote/entities/item/abc.bin",
        query: String = "",
        secret: String = this.secret,
        date: String = amzDate,
        hostOverride: String = host,
        payloadHash: String = AwsSigV4.EMPTY_SHA256,
        accessKeyOverride: String = accessKey,
        regionOverride: String = "auto",
        headers: Map<String, String> = mapOf(
            "host" to host,
            "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256,
            "x-amz-date" to amzDate,
        ),
    ): AwsSigV4.Signed = AwsSigV4.sign(
        method = method,
        host = hostOverride,
        path = path,
        query = query,
        payloadSha256 = payloadHash,
        headers = headers,
        accessKeyId = accessKeyOverride,
        secretAccessKey = secret,
        region = regionOverride,
        amzDate = date,
    )

    /** 只要授权头文本的便捷重载。 */
    private fun authOf(
        method: String = "GET",
        path: String = "/mybucket/orenote/entities/item/abc.bin",
        query: String = "",
        secret: String = this.secret,
        date: String = amzDate,
        hostOverride: String = host,
        payloadHash: String = AwsSigV4.EMPTY_SHA256,
        accessKeyOverride: String = accessKey,
        regionOverride: String = "auto",
        headers: Map<String, String> = mapOf(
            "host" to host,
            "x-amz-content-sha256" to AwsSigV4.EMPTY_SHA256,
            "x-amz-date" to amzDate,
        ),
    ): String = signWith(
        method = method,
        path = path,
        query = query,
        secret = secret,
        date = date,
        hostOverride = hostOverride,
        payloadHash = payloadHash,
        accessKeyOverride = accessKeyOverride,
        regionOverride = regionOverride,
        headers = headers,
    ).authorization
}
