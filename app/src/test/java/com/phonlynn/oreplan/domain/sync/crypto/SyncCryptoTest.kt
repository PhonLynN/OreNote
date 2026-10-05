package com.phonlynn.oreplan.domain.sync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * 云端对象的加解密。
 *
 * 这一层错了的后果是**不可逆的**：数据要么泄不出去、要么永久解不开
 * （主密钥丢了客户端加密就无法恢复）。所以边界要全部钉住。
 */
class SyncCryptoTest {

    /** 固定种子的随机数，让"随机 nonce"相关的断言可复现。 */
    private fun seededRandom(seed: Long = 42L) = SecureRandom().apply { setSeed(seed) }

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `加密解密往返一致`() {
        val plaintext = "{\"id\":\"abc\",\"title\":\"期末复习计划\"}".toByteArray()
        val stored = SyncCrypto.encrypt(plaintext, key, random = seededRandom())

        assertArrayEquals(plaintext, SyncCrypto.decrypt(stored, key))
    }

    @Test
    fun `空内容也能往返`() {
        val stored = SyncCrypto.encrypt(ByteArray(0), key, random = seededRandom())
        assertEquals(0, SyncCrypto.decrypt(stored, key).size)
    }

    @Test
    fun `大内容往返一致`() {
        // 模拟一个较大的实体（含随机字节，避免压缩类优化影响判断）
        val big = ByteArray(512 * 1024).also { seededRandom(7).nextBytes(it) }
        assertArrayEquals(big, SyncCrypto.decrypt(SyncCrypto.encrypt(big, key, random = seededRandom()), key))
    }

    /** 密文必须在前 12 字节带 nonce，且总长 = 12 + 明文长 + 16（GCM tag）。 */
    @Test
    fun `输出格式是 nonce 加密文加 tag`() {
        val plaintext = ByteArray(100) { 1 }
        val stored = SyncCrypto.encrypt(plaintext, key, random = seededRandom())

        assertEquals("12(nonce) + 100 + 16(tag)", 128, stored.size)
    }

    /**
     * **最重要的纪律**：同一明文两次加密必须产出**不同密文**（随机 nonce）。
     * 若相同，说明用了固定 nonce —— GCM 下那是灾难性失效。
     */
    @Test
    fun `相同明文两次加密产生不同密文`() {
        val plaintext = "same content".toByteArray()
        val a = SyncCrypto.encrypt(plaintext, key, random = seededRandom(1))
        val b = SyncCrypto.encrypt(plaintext, key, random = seededRandom(2))

        assertNotEquals("nonce 必须每次不同", a.toList(), b.toList())
        // 但都能解回同一个明文
        assertArrayEquals(plaintext, SyncCrypto.decrypt(a, key))
        assertArrayEquals(plaintext, SyncCrypto.decrypt(b, key))
    }

    @Test
    fun `同一随机源连续加密也产生不同 nonce`() {
        val random = seededRandom(99)
        val plaintext = "x".toByteArray()
        val a = SyncCrypto.encrypt(plaintext, key, random = random)
        val b = SyncCrypto.encrypt(plaintext, key, random = random)

        // 取前 12 字节（nonce）比较
        assertNotEquals(
            "同一 SecureRandom 连续取值也必须给出不同 nonce",
            a.copyOfRange(0, 12).toList(),
            b.copyOfRange(0, 12).toList(),
        )
    }

    // ---------------------------------------------------------------- 失败路径

    @Test
    fun `密钥不同时解密失败`() {
        val stored = SyncCrypto.encrypt("secret".toByteArray(), key, random = seededRandom())
        val wrongKey = ByteArray(32) { (it + 1).toByte() }

        val e = runCatching { SyncCrypto.decrypt(stored, wrongKey) }.exceptionOrNull()
        assertTrue("必须是 SyncCryptoException", e is SyncCryptoException)
    }

    /** 密文被改一位就必须解不开 —— 这是 GCM 完整性校验的意义。 */
    @Test
    fun `密文被篡改时解密失败`() {
        val stored = SyncCrypto.encrypt("important data".toByteArray(), key, random = seededRandom())
        stored[20] = (stored[20].toInt() xor 0x01).toByte()

        assertTrue(runCatching { SyncCrypto.decrypt(stored, key) }.exceptionOrNull() is SyncCryptoException)
    }

    /** 认证标签（最后 16 字节）被改也必须失败。 */
    @Test
    fun `认证标签被篡改时解密失败`() {
        val stored = SyncCrypto.encrypt("important data".toByteArray(), key, random = seededRandom())
        stored[stored.size - 1] = (stored[stored.size - 1].toInt() xor 0x01).toByte()

        assertTrue(runCatching { SyncCrypto.decrypt(stored, key) }.exceptionOrNull() is SyncCryptoException)
    }

    /**
     * **AAD 绑定对象身份**：把密文从 A 对象挪到 B 对象就解不开。
     * 这阻止"调换密文"这类攻击（例如把 A 的删除墓碑换成 B 的内容）。
     */
    @Test
    fun `aad 不匹配时解密失败`() {
        val aadA = "orenote/entities/item/A.bin".toByteArray()
        val aadB = "orenote/entities/item/B.bin".toByteArray()
        val stored = SyncCrypto.encrypt("data".toByteArray(), key, aad = aadA, random = seededRandom())

        // 正确的 aad 能解开
        assertArrayEquals("data".toByteArray(), SyncCrypto.decrypt(stored, key, aad = aadA))
        // 换成另一个对象的 aad 就解不开
        assertTrue(
            runCatching { SyncCrypto.decrypt(stored, key, aad = aadB) }.exceptionOrNull()
                is SyncCryptoException,
        )
    }

    @Test
    fun `aad 与不传 aad 不兼容`() {
        val stored = SyncCrypto.encrypt("d".toByteArray(), key, aad = "x".toByteArray(), random = seededRandom())
        assertTrue(
            runCatching { SyncCrypto.decrypt(stored, key) }.exceptionOrNull() is SyncCryptoException,
        )
    }

    /** 太短的内容（不是本应用写的）要给出明确错误，而不是底层异常。 */
    @Test
    fun `过短的输入被拒绝`() {
        val e = runCatching { SyncCrypto.decrypt(ByteArray(5), key) }.exceptionOrNull()
        assertTrue(e is SyncCryptoException)
        assertTrue("消息要说明原因", e!!.message!!.contains("长度不足"))
    }

    @Test
    fun `空输入被拒绝`() {
        assertTrue(runCatching { SyncCrypto.decrypt(ByteArray(0), key) }.exceptionOrNull() is SyncCryptoException)
    }

    @Test
    fun `密钥长度不对时立即报错`() {
        val shortKey = ByteArray(16)
        assertTrue(
            runCatching { SyncCrypto.encrypt("x".toByteArray(), shortKey) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { SyncCrypto.decrypt(ByteArray(100), shortKey) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    // ---------------------------------------------------------------- 密钥生成

    @Test
    fun `生成的主密钥长度是 32 字节且随机`() {
        val a = SyncCrypto.newKey(seededRandom(1))
        val b = SyncCrypto.newKey(seededRandom(2))

        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertFalse("两把密钥不该相同", a.contentEquals(b))
    }

    @Test
    fun `生成的密钥可用于加解密`() {
        val generated = SyncCrypto.newKey(seededRandom(5))
        val stored = SyncCrypto.encrypt("hello".toByteArray(), generated, random = seededRandom())
        assertArrayEquals("hello".toByteArray(), SyncCrypto.decrypt(stored, generated))
    }

    /**
     * 密文里**不应出现明文片段**（防止"以为加密了其实没有"）。
     */
    @Test
    fun `密文不含明文片段`() {
        val plaintext = "SECRET-MARKER-期未复习".toByteArray()
        val stored = SyncCrypto.encrypt(plaintext, key, random = seededRandom())

        val haystack = stored.toList()
        val needle = plaintext.toList()
        assertFalse("密文中不该出现完整明文", haystack.windowed(needle.size).any { it == needle })
    }
}
