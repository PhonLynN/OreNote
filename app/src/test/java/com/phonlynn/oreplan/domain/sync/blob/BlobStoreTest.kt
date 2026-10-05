package com.phonlynn.oreplan.domain.sync.blob

import com.phonlynn.oreplan.domain.sync.crypto.SyncCrypto
import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import com.phonlynn.oreplan.domain.sync.r2.R2Exception
import com.phonlynn.oreplan.domain.sync.r2.Transport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 附件二进制的上传/下载（走真实的加密与分片逻辑，只有网络是假的）。
 *
 * ## 这一层为什么必须测透
 *
 * 附件是**用户最不容易重新生成的数据**：笔记可以重打，一张拍下来的照片没了就是没了。
 * 而它的失败模式又特别隐蔽 —— 文件看着传上去了，下载回来却是坏的。
 * 所以这里逐项验证：密文不含明文、下载后逐字节一致、去重真的生效、
 * 完整性校验会拦住损坏的内容。
 */
class BlobStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val masterKey = ByteArray(32) { it.toByte() }
    private val config = R2Config(
        accountId = "acc",
        bucket = "bucket",
        accessKeyId = "ak",
        secretAccessKey = "sk",
        keyPrefix = "orenote/",
    )

    /** 内存版 R2，支持分片上传的完整流程。 */
    private class FakeR2 {
        val objects = linkedMapOf<String, ByteArray>()
        /** uploadId → 已上传的分片（片号 → 密文） */
        val pending = linkedMapOf<String, MutableMap<Int, ByteArray>>()
        var uploadedParts = 0
        var aborted = 0
        var failPartNumber: Int? = null

        fun transport(): Transport = object : Transport {
            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): R2Client.Response {
                val key = url.substringAfter("://").substringAfter("/").substringAfter("/")
                    .substringBefore("?")
                val query = url.substringAfter("?", "")
                return when {
                    method == "HEAD" -> objects[key]
                        ?.let { R2Client.Response(200, ByteArray(0), "\"e\"", it.size.toLong()) }
                        ?: R2Client.Response(404, ByteArray(0), null, 0)

                    method == "GET" && url.contains("list-type=2") ->
                        R2Client.Response(200, listXml(), null, 0)

                    method == "GET" -> objects[key]
                        ?.let { R2Client.Response(200, it, "\"e\"", it.size.toLong()) }
                        ?: R2Client.Response(404, ByteArray(0), null, 0)

                    method == "POST" && query.startsWith("uploads") -> {
                        val id = "upload-${pending.size + 1}"
                        pending[id] = linkedMapOf()
                        R2Client.Response(200, "<UploadId>$id</UploadId>".toByteArray(), null, 0)
                    }

                    method == "PUT" && query.contains("partNumber") -> {
                        val partNumber = query.substringAfter("partNumber=").substringBefore("&").toInt()
                        val uploadId = query.substringAfter("uploadId=").substringBefore("&")
                        if (failPartNumber == partNumber) {
                            return R2Client.Response(500, ByteArray(0), null, 0)
                        }
                        pending[uploadId]?.put(partNumber, body ?: ByteArray(0))
                        uploadedParts++
                        R2Client.Response(200, ByteArray(0), "\"etag-$partNumber\"", 0)
                    }

                    method == "POST" && query.contains("uploadId") -> {
                        // Complete：把分片按序拼起来存进对象
                        val uploadId = query.substringAfter("uploadId=").substringBefore("&")
                        val parts = pending.remove(uploadId).orEmpty().toSortedMap()
                        objects[key] = parts.values.fold(ByteArray(0)) { acc, b -> acc + b }
                        R2Client.Response(200, "<CompleteMultipartUploadResult/>".toByteArray(), null, 0)
                    }

                    method == "DELETE" && query.contains("uploadId") -> {
                        val uploadId = query.substringAfter("uploadId=").substringBefore("&")
                        pending.remove(uploadId)
                        aborted++
                        R2Client.Response(204, ByteArray(0), null, 0)
                    }

                    method == "PUT" -> {
                        objects[key] = body ?: ByteArray(0)
                        R2Client.Response(200, ByteArray(0), "\"etag\"", 0)
                    }

                    else -> R2Client.Response(405, ByteArray(0), null, 0)
                }
            }
        }

        private fun listXml(): ByteArray = buildString {
            append("<?xml version=\"1.0\"?><ListBucketResult>")
            objects.keys.forEach { k ->
                append("<Contents><Key>").append(k).append("</Key>")
                append("<ETag>\"e\"</ETag><Size>1</Size></Contents>")
            }
            append("</ListBucketResult>")
        }.toByteArray()
    }

    private fun client(r2: FakeR2) = R2Client(config, r2.transport())

    private fun fileWith(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    // ---------------------------------------------------------------- 单次上传

    @Test
    fun `小文件上传下载往返一致`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val original = "一张图片的字节内容".toByteArray()
        val file = fileWith("small.bin", original)

        val result = store.upload(client(r2), config, file, masterKey)
        assertTrue("小文件应走单次上传", result.uploaded)

        val target = File(temp.root, "downloaded/small.bin")
        assertTrue(store.download(client(r2), config, result.hash, masterKey, target))
        assertTrue("下载回来的必须逐字节相同", original.contentEquals(target.readBytes()))
    }

    /** 服务端只存密文 —— 客户端加密的实证。 */
    @Test
    fun `云端存的是密文而非明文`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val secret = "SECRET-IMAGE-BYTES-期末复习".toByteArray()
        val file = fileWith("s.bin", secret)

        store.upload(client(r2), config, file, masterKey)

        val stored = r2.objects.values.single()
        assertFalse(
            "桶里的字节不该含明文",
            String(stored, Charsets.ISO_8859_1).contains("SECRET-IMAGE"),
        )
        // 用密钥能解开
        val key = r2.objects.keys.single()
        val plain = SyncCrypto.decrypt(stored, masterKey, aad = key.toByteArray())
        assertTrue(secret.contentEquals(plain))
    }

    /** 对象名是**内容哈希**，不是文件名 —— 跨端可寻址的前提。 */
    @Test
    fun `对象名是内容哈希`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val bytes = "内容".toByteArray()
        val result = store.upload(client(r2), config, fileWith("任意名字.png", bytes), masterKey)

        assertEquals(ContentHash.sha256(bytes), result.hash)
        assertEquals(
            "对象路径应为 <prefix>/blobs/<前两位>/<hash>",
            config.key("blobs", result.hash.substring(0, 2), result.hash),
            r2.objects.keys.single(),
        )
    }

    // ---------------------------------------------------------------- 去重

    /**
     * **内容寻址最大的收益**：同内容只传一次。
     *
     * 同一张图被两条记录引用（或重复导入）时，云端只存一份。
     * 若这条挂了，用户的存储占用会成倍增长，而且每次同步都在重传同样的图。
     */
    @Test
    fun `相同内容第二次上传被跳过`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val bytes = "同一张图".toByteArray()

        val first = store.upload(client(r2), config, fileWith("a.png", bytes), masterKey)
        assertTrue("第一次应真的上传", first.uploaded)

        val second = store.upload(client(r2), config, fileWith("b.png", bytes), masterKey)
        assertFalse("同内容的第二次不该再传", second.uploaded)
        assertEquals("云端只该有一个对象", 1, r2.objects.size)
        assertEquals(first.hash, second.hash)
    }

    /** 用已知哈希集合跳过 HEAD 往返 —— 附件多起来时能省几十次请求。 */
    @Test
    fun `已知哈希集合可以跳过探测`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val bytes = "内容".toByteArray()
        val hash = ContentHash.sha256(bytes)

        val result = store.upload(
            client(r2), config, fileWith("x.png", bytes), masterKey,
            knownHashes = setOf(hash),
        )

        assertFalse("已知云端有就不该传", result.uploaded)
        assertTrue("也不该产生对象", r2.objects.isEmpty())
    }

    /** 内容不同 ⇒ 不同哈希 ⇒ 两个对象（不能互相覆盖）。 */
    @Test
    fun `不同内容产生不同对象`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()

        val a = store.upload(client(r2), config, fileWith("a.png", byteArrayOf(1, 2, 3)), masterKey)
        val b = store.upload(client(r2), config, fileWith("b.png", byteArrayOf(1, 2, 4)), masterKey)

        assertFalse(a.hash == b.hash)
        assertEquals(2, r2.objects.size)
    }

    // ---------------------------------------------------------------- 分片

    /** 超过阈值走分片上传，且结果必须与单次上传**完全等价**。 */
    @Test
    fun `大文件走分片上传且往返一致`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        // 略大于阈值，切成 2 片
        val size = (MultipartUploader.MULTIPART_THRESHOLD + 1024).toInt()
        val original = ByteArray(size) { (it % 251).toByte() }
        val file = fileWith("big.pdf", original)

        val result = store.upload(client(r2), config, file, masterKey)

        assertTrue("应上传", result.uploaded)
        assertTrue("应确实走了分片（至少 2 片）", r2.uploadedParts >= 2)

        val target = File(temp.root, "down/big.pdf")
        assertTrue(store.download(client(r2), config, result.hash, masterKey, target))
        assertTrue("大文件下载后必须逐字节一致", original.contentEquals(target.readBytes()))
    }

    /** 分片上传失败时要**放弃**，否则已传分片会一直占存储并计费。 */
    @Test
    fun `分片失败时会放弃上传`() = runTest {
        val r2 = FakeR2().apply { failPartNumber = 2 }
        val store = BlobStore()
        val size = (MultipartUploader.MULTIPART_THRESHOLD * 2 + 10).toInt()
        val file = fileWith("fail.bin", ByteArray(size))

        val error = runCatching { store.upload(client(r2), config, file, masterKey) }.exceptionOrNull()

        assertTrue("应抛出异常", error != null)
        assertEquals("必须调用过 Abort", 1, r2.aborted)
        assertTrue("云端不该留下半个对象", r2.objects.isEmpty())
    }

    // ---------------------------------------------------------------- 完整性

    /**
     * **下载后哈希校验**：内容损坏时必须失败，而不是把坏文件写进本地。
     *
     * 写进去的后果是用户点开图片看到损坏内容、却以为"同步成功了" ——
     * 明确失败至少能让下次同步重试。
     */
    @Test
    fun `下载内容损坏时校验失败`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val bytes = "原始内容".toByteArray()
        val uploaded = store.upload(client(r2), config, fileWith("x.png", bytes), masterKey)

        // 用**别的密钥**重新加密同一位置（模拟内容被换过/损坏）
        val objectKey = r2.objects.keys.single()
        val wrongKey = ByteArray(32) { (it + 7).toByte() }
        r2.objects[objectKey] = SyncCrypto.encrypt(
            "被篡改的内容".toByteArray(),
            wrongKey,
            aad = objectKey.toByteArray(),
        )

        // 解不开（认证失败）——这也是一种保护
        val error = runCatching {
            store.download(client(r2), config, uploaded.hash, masterKey, File(temp.root, "o.png"))
        }.exceptionOrNull()
        assertTrue("必须失败，而不是写入坏内容", error != null)
    }

    /** 云端没有这个对象 ⇒ 返回 false（而不是抛异常）——调用方要能区分"没有"与"出错"。 */
    @Test
    fun `云端没有对象时下载返回 false`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val missing = "0".repeat(64)

        assertFalse(store.download(client(r2), config, missing, masterKey, File(temp.root, "x")))
        assertNull("不该产生文件", File(temp.root, "x").takeIf { it.exists() })
    }

    @Test
    fun `exists 反映云端是否有该对象`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val bytes = "内容".toByteArray()
        val hash = ContentHash.sha256(bytes)

        assertFalse("上传前不存在", store.exists(client(r2), config, hash))
        store.upload(client(r2), config, fileWith("x.png", bytes), masterKey)
        assertTrue("上传后存在", store.exists(client(r2), config, hash))
    }

    // ---------------------------------------------------------------- 边界

    @Test
    fun `空文件也能往返`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val result = store.upload(client(r2), config, fileWith("empty.bin", ByteArray(0)), masterKey)

        val target = File(temp.root, "down/empty.bin")
        assertTrue(store.download(client(r2), config, result.hash, masterKey, target))
        assertEquals(0, target.length())
    }

    /** 下载会创建目标目录（按需下载时目标目录往往还不存在）。 */
    @Test
    fun `下载会自动创建目标目录`() = runTest {
        val r2 = FakeR2()
        val store = BlobStore()
        val bytes = "x".toByteArray()
        val result = store.upload(client(r2), config, fileWith("a.png", bytes), masterKey)

        val target = File(temp.root, "deep/nested/dir/a.png")
        assertTrue(store.download(client(r2), config, result.hash, masterKey, target))
        assertTrue(target.isFile)
    }

    /** 认证失败要能识别出来（设置页据此提示检查凭据）。 */
    @Test
    fun `认证失败抛出可识别的异常`() = runTest {
        val failing = object : Transport {
            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ) = R2Client.Response(403, ByteArray(0), null, 0)
        }
        val store = BlobStore()
        val file = fileWith("x.bin", "内容".toByteArray())

        val error = runCatching {
            store.upload(R2Client(config, failing), config, file, masterKey)
        }.exceptionOrNull()

        assertTrue(error is R2Exception)
        assertTrue("应能识别为凭据问题", (error as R2Exception).isAuthFailure)
    }
}
