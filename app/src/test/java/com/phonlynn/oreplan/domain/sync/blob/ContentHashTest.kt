package com.phonlynn.oreplan.domain.sync.blob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 内容寻址与分片切分。
 *
 * 这一层错了的后果：**附件永久损坏或互相覆盖**。
 * 而附件（图片、PDF）是用户最不容易重新生成的数据 —— 笔记可以重打，
 * 一张拍下来的照片没了就是没了。
 */
class ContentHashTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---------------------------------------------------------------- 哈希

    /** 空内容的 sha256 是公开常量，用它证明摘要实现本身没写反。 */
    @Test
    fun `空文件哈希等于已知常量`() {
        val file = temp.newFile("empty").apply { writeBytes(ByteArray(0)) }
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.sha256(file),
        )
    }

    @Test
    fun `abc 的哈希等于已知常量`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.sha256("abc".toByteArray()),
        )
    }

    /** 同内容必同哈希 —— 这是"跨端可寻址"与"去重"的前提。 */
    @Test
    fun `同样的内容得到同样的哈希`() {
        val a = temp.newFile("a").apply { writeBytes("同样的内容".toByteArray()) }
        val b = temp.newFile("b").apply { writeBytes("同样的内容".toByteArray()) }

        assertEquals("文件名不同不影响哈希", ContentHash.sha256(a), ContentHash.sha256(b))
    }

    /** 差一个字节就必须不同 —— 否则两个文件会互相覆盖。 */
    @Test
    fun `内容差一字节则哈希不同`() {
        val a = temp.newFile("a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = temp.newFile("b").apply { writeBytes(byteArrayOf(1, 2, 4)) }

        assertNotEquals(ContentHash.sha256(a), ContentHash.sha256(b))
    }

    /** 流式实现要能处理比缓冲区大的文件（否则大附件会被截断计算）。 */
    @Test
    fun `大文件哈希正确`() {
        val bytes = ByteArray(300 * 1024) { (it % 251).toByte() }
        val file = temp.newFile("big").apply { writeBytes(bytes) }

        assertEquals(
            "流式与一次性计算必须一致",
            ContentHash.sha256(bytes),
            ContentHash.sha256(file),
        )
    }

    @Test
    fun `中文内容不影响哈希正确性`() {
        val text = "期末复习计划 · 第 1-3 章"
        val file = temp.newFile("cn").apply { writeBytes(text.toByteArray()) }
        assertEquals(ContentHash.sha256(text.toByteArray()), ContentHash.sha256(file))
    }

    // ---------------------------------------------------------------- 对象 key

    /** 分两级目录：单目录下几万个对象时列举会明显变慢。 */
    @Test
    fun `对象 key 按前两位分片`() {
        val hash = "ab" + "c".repeat(62)
        assertEquals("blobs/ab/$hash", ContentHash.objectKey(hash))
    }

    @Test
    fun `对象 key 拒绝过短的哈希`() {
        val e = runCatching { ContentHash.objectKey("a") }.exceptionOrNull()
        assertTrue("应抛参数异常", e is IllegalArgumentException)
    }

    @Test
    fun `不同哈希落在不同分片目录的可能性`() {
        val keys = (0..255).map { i ->
            ContentHash.objectKey("%02x".format(i) + "0".repeat(62))
        }
        assertTrue("应产生多个不同的分片目录", keys.distinct().size > 100)
    }

    // ---------------------------------------------------------------- 分片数学

    @Test
    fun `分片数与边界`() {
        val u = MultipartUploader(partSize = 100)

        assertEquals("空文件也要 1 片（否则 Complete 会失败）", 1, u.partCount(0))
        assertEquals(1, u.partCount(1))
        assertEquals(1, u.partCount(100))
        assertEquals("刚好超一片", 2, u.partCount(101))
        assertEquals(2, u.partCount(200))
        assertEquals(3, u.partCount(201))
    }

    @Test
    fun `分片偏移与长度正确`() {
        val u = MultipartUploader(partSize = 100)
        val size = 250L

        assertEquals(0L to 100, u.partRange(size, 1))
        assertEquals(100L to 100, u.partRange(size, 2))
        assertEquals("最后一片是余数", 200L to 50, u.partRange(size, 3))
    }

    /** 所有分片拼起来必须正好等于原文件长度 —— 少一字节就是文件损坏。 */
    @Test
    fun `所有分片长度之和等于文件长度`() {
        val u = MultipartUploader(partSize = 7)
        listOf(0L, 1L, 6L, 7L, 8L, 100L, 1023L).forEach { size ->
            val total = (1..u.partCount(size)).sumOf { n -> u.partRange(size, n).second.toLong() }
            assertEquals("size=$size 的分片总长不对", size, total)
        }
    }

    @Test
    fun `默认分片大小满足 S3 的五兆下限`() {
        assertTrue(
            "S3 要求除最后一片外每片至少 5MB，实际 ${MultipartUploader.DEFAULT_PART_SIZE}",
            MultipartUploader.DEFAULT_PART_SIZE >= 5 * 1024 * 1024,
        )
    }

    /** 8MB × 10000 片 = 80GB，远超个人附件量级（S3 的分片数上限是 10000）。 */
    @Test
    fun `分片数不会触及上限`() {
        val u = MultipartUploader()
        assertTrue("1GB 文件的片数应远小于 10000", u.partCount(1024L * 1024 * 1024) < 200)
    }

    // ---------------------------------------------------------------- 组装

    /** 按分片切出来的字节拼回去，必须与原文件**逐字节相同**。 */
    @Test
    fun `按分片切开再拼回等于原文件`() {
        val u = MultipartUploader(partSize = 13)
        val original = ByteArray(100) { (it * 7 % 256).toByte() }
        val file = temp.newFile("round").apply { writeBytes(original) }

        val assembled = ByteArray(original.size)
        var offset = 0
        for (n in 1..u.partCount(file.length())) {
            val (start, length) = u.partRange(file.length(), n)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                raf.readFully(assembled, offset, length)
            }
            offset += length
        }

        assertTrue("拼回的内容必须与原文件逐字节相同", original.contentEquals(assembled))
    }

    /** 加密后的分片大小 = 明文 + nonce(12) + tag(16)。用来估算云端占用。 */
    @Test
    fun `加密会带来固定的额外开销`() {
        val plain = ByteArray(1000)
        val cipher = com.phonlynn.oreplan.domain.sync.crypto.SyncCrypto.encrypt(
            plain,
            ByteArray(32) { it.toByte() },
        )
        assertEquals("12(nonce) + 16(tag)", 28, cipher.size - plain.size)
    }
}

/** 让 `File` 在测试里更顺手。 */
private fun File.readBytesSafe(): ByteArray = readBytes()
