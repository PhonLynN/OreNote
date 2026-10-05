package com.phonlynn.oreplan.domain.sync.r2

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * R2 客户端的请求构造与错误分类。
 *
 * 用注入的假 [Transport] 捕获"实际发出的请求"，因此能验证：
 *  · 签名头确实带上了、且与实际发出的头一致；
 *  · 条件写头（If-Match / If-None-Match）确实发出去了；
 *  · 状态码被正确分类（这是上层决定"重试还是冲突"的依据）。
 */
class R2ClientTest {

    private val config = R2Config(
        accountId = "acc123",
        bucket = "orenote-sync",
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        keyPrefix = "orenote/",
    )

    private val fixedInstant = Instant.parse("2026-08-30T12:36:00Z")

    /** 记录最后一次请求的假传输层。 */
    private class FakeTransport(
        var respond: (String, String, Map<String, String>, ByteArray?) -> R2Client.Response,
    ) : Transport {
        var lastMethod: String? = null
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: ByteArray? = null
        var calls = 0

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): R2Client.Response {
            calls++
            lastMethod = method
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return respond(method, url, headers, body)
        }
    }

    private fun ok(body: ByteArray = ByteArray(0), eTag: String? = "\"abc\"") =
        R2Client.Response(200, body, eTag, body.size.toLong())

    private fun client(t: Transport) = R2Client(config, t) { fixedInstant }

    // ---------------------------------------------------------------- 请求构造

    @Test
    fun `headBucket 打的是桶根路径且带签名`() = runTest {
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).headBucket()

        assertEquals("HEAD", t.lastMethod)
        assertEquals("https://acc123.r2.cloudflarestorage.com/orenote-sync", t.lastUrl)
        assertTrue("必须带 Authorization", t.lastHeaders.containsKey("Authorization"))
        assertTrue(
            "授权头格式正确",
            t.lastHeaders["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/"),
        )
        // 签名里声明的头，除 `host` 外都必须真的发出。
        // `host` 是唯一例外：它是受限头，由 URL 自动生成，手动设置会被静默忽略；
        // 服务端用它收到的 Host 重算签名，与这里 URL 里的 host 一致。
        val signedHeaders = t.lastHeaders["Authorization"]!!
            .substringAfter("SignedHeaders=").substringBefore(",")
            .split(";")
        assertEquals("host 必须参与签名（否则服务端算不出同样的签名）", true, signedHeaders.contains("host"))
        signedHeaders.filter { it != "host" }.forEach { name ->
            assertTrue(
                "签名声明了 $name，实际请求里必须有它",
                t.lastHeaders.keys.any { it.equals(name, ignoreCase = true) },
            )
        }
        assertFalse(
            "host 不该出现在实际请求头里（会被 HttpURLConnection 静默忽略）",
            t.lastHeaders.keys.any { it.equals("host", ignoreCase = true) },
        )
    }

    /** S3 要求每个请求都带 `x-amz-content-sha256`；没有体时是空串哈希。 */
    @Test
    fun `每个请求都带 content sha256`() = runTest {
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).headObject("orenote/entities/item/a.bin")

        assertEquals(AwsSigV4.EMPTY_SHA256, t.lastHeaders["x-amz-content-sha256"])
    }

    @Test
    fun `上传带体时 sha256 是体的哈希`() = runTest {
        val body = "hello".toByteArray()
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).putObject("orenote/entities/item/a.bin", body)

        assertEquals(AwsSigV4.sha256Hex(body), t.lastHeaders["x-amz-content-sha256"])
        assertEquals("请求体必须原样传出", "hello", String(t.lastBody!!))
    }

    /** 对象 key 里的前缀与目录分隔要编码正确（`/` 保留、其余编码）。 */
    @Test
    fun `对象路径按前缀与 key 拼接并编码`() = runTest {
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).getObject(config.key("entities", "board_card", "a b.bin"))

        assertEquals(
            "https://acc123.r2.cloudflarestorage.com/orenote-sync/orenote/entities/board_card/a%20b.bin",
            t.lastUrl,
        )
    }

    // ---------------------------------------------------------------- 条件写

    /**
     * **并发保护的核心**：覆盖写必须带 `If-Match`（已知 ETag）。
     *
     * 两台设备同时改同一对象时，后写的那台会拿到 412 —— 于是它知道
     * "远端已经被别人改过"，该重新合并而不是硬覆盖。
     * 少了这个头，就是**静默地丢掉另一台设备的修改**。
     */
    @Test
    fun `条件覆盖写带上 If-Match`() = runTest {
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).putObject("k", ByteArray(0), ifMatch = "\"v1\"")

        assertEquals("\"v1\"", t.lastHeaders["if-match"])
        assertNull("不该同时带 If-None-Match", t.lastHeaders["if-none-match"])
    }

    /** 首次上传用 `If-None-Match: *`：仅当远端不存在时才写，防止覆盖别的设备的内容。 */
    @Test
    fun `首次上传带 If-None-Match 星号`() = runTest {
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).putObject("k", ByteArray(0), ifNoneMatch = "*")

        assertEquals("*", t.lastHeaders["if-none-match"])
        assertNull(t.lastHeaders["if-match"])
    }

    /** 条件写头必须**参与签名**，否则中间人可篡改条件、绕过并发保护。 */
    @Test
    fun `条件写头参与签名`() = runTest {
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).putObject("k", ByteArray(0), ifMatch = "\"v1\"")

        val signed = t.lastHeaders["Authorization"]!!
            .substringAfter("SignedHeaders=").substringBefore(",")
        assertTrue("If-Match 必须在签名头列表里", signed.contains("if-match"))
    }

    @Test
    fun `上传带 content-type 并参与签名`() = runTest {
        val t = FakeTransport { _, _, _, _ -> ok() }
        client(t).putObject("k", ByteArray(0), contentType = "application/json")

        assertEquals("application/json", t.lastHeaders["content-type"])
        val signed = t.lastHeaders["Authorization"]!!
            .substringAfter("SignedHeaders=").substringBefore(",")
        assertTrue(signed.contains("content-type"))
    }

    // ---------------------------------------------------------------- 错误分类

    @Test
    fun `412 被识别为并发冲突`() = runTest {
        val t = FakeTransport { _, _, _, _ -> R2Client.Response(412, ByteArray(0), null, 0) }
        val response = client(t).putObject("k", ByteArray(0), ifMatch = "\"v1\"")

        assertTrue("412 必须能被上层识别为冲突", response.isPreconditionFailed)
        assertFalse(response.isSuccess)
    }

    @Test
    fun `404 被识别为不存在而不是异常`() = runTest {
        val t = FakeTransport { _, _, _, _ -> R2Client.Response(404, ByteArray(0), null, 0) }
        val response = client(t).getObject("missing")

        assertTrue(response.isNotFound)
    }

    @Test
    fun `403 被识别为凭据问题并给出提示`() = runTest {
        val xml = "<Error><Code>SignatureDoesNotMatch</Code></Error>".toByteArray()
        val t = FakeTransport { _, _, _, _ -> R2Client.Response(403, xml, null, xml.size.toLong()) }
        val e = R2Exception.from(R2Client.Response(403, xml, null, xml.size.toLong()), "测试")

        assertTrue(e.isAuthFailure)
        assertTrue("消息要提示去查凭据", e.message!!.contains("凭据"))
        assertTrue("响应体片段要保留，便于排查", e.bodySnippet.contains("SignatureDoesNotMatch"))
    }

    @Test
    fun `412 的异常被识别为冲突`() {
        val e = R2Exception.from(R2Client.Response(412, ByteArray(0), null, 0), "写对象")
        assertTrue(e.isConflict)
    }

    // ---------------------------------------------------------------- List 翻页

    @Test
    fun `listObjects 自动翻页直到没有 token`() = runTest {
        var page = 0
        val t = FakeTransport { _, url, _, _ ->
            page++
            when {
                url.contains("continuation-token") -> R2Client.Response(
                    200,
                    listXml(listOf("c" to "\"e3\"")),
                    null,
                    0,
                )
                else -> R2Client.Response(
                    200,
                    listXml(listOf("a" to "\"e1\"", "b" to "\"e2\""), nextToken = "TOKEN"),
                    null,
                    0,
                )
            }
        }

        val objects = client(t).listObjects("orenote/")

        assertEquals(listOf("a", "b", "c"), objects.map { it.key })
        assertEquals(2, t.calls)
    }

    @Test
    fun `listObjects 一次返回时不分页`() = runTest {
        val t = FakeTransport { _, _, _, _ ->
            R2Client.Response(200, listXml(listOf("only" to "\"e\"")), null, 0)
        }

        val objects = client(t).listObjects("orenote/")

        assertEquals(listOf("only"), objects.map { it.key })
        assertEquals(1, t.calls)
    }

    @Test
    fun `listObjects 出错时抛异常并带状态码`() = runTest {
        val t = FakeTransport { _, _, _, _ -> R2Client.Response(403, ByteArray(0), null, 0) }

        val e = runCatching { client(t).listObjects("orenote/") }.exceptionOrNull()

        assertTrue(e is R2Exception)
        assertEquals(403, (e as R2Exception).status)
    }

    /** 列表请求必须带 `list-type=2`，否则 S3 会按 v1 语义返回（不含 continuation-token）。 */
    @Test
    fun `列表请求带 list-type 2 与 prefix`() = runTest {
        val t = FakeTransport { _, _, _, _ ->
            R2Client.Response(200, listXml(emptyList()), null, 0)
        }
        client(t).listObjects("orenote/entities/")

        assertTrue(t.lastUrl!!.contains("list-type=2"))
        assertTrue("前缀里的斜杠要编码", t.lastUrl!!.contains("prefix=orenote%2Fentities%2F"))
    }

    // ---------------------------------------------------------------- 辅助

    private fun listXml(
        entries: List<Pair<String, String>>,
        nextToken: String? = null,
    ): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult>")
        entries.forEach { (key, etag) ->
            append("<Contents><Key>").append(key).append("</Key>")
            append("<ETag>").append(etag).append("</ETag>")
            append("<Size>10</Size></Contents>")
        }
        nextToken?.let { append("<NextContinuationToken>").append(it).append("</NextContinuationToken>") }
        append("</ListBucketResult>")
    }.toByteArray()
}
