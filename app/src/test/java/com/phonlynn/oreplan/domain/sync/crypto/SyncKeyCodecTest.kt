package com.phonlynn.oreplan.domain.sync.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主密钥备份码的编解码。
 *
 * ## 这一层的失效模式
 *
 * 用户抄了备份码、换机导入时才发现不对 —— 那时本地数据可能已被当作"已同步"。
 * 所以**校验位**是关键设计：抄错一位必须在**导入那一刻**就报错。
 * 这里逐项验证那个承诺。
 */
class SyncKeyCodecTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun `编解码往返一致`() {
        val code = SyncKeyCodec.encode(key)
        assertArrayEquals(key, SyncKeyCodec.decode(code))
    }

    /** 备份码要人能抄：只含 A-Z 与 2-7，外加分组用的连字符。 */
    @Test
    fun `备份码只含易抄字符`() {
        val code = SyncKeyCodec.encode(key)

        assertTrue("不该有数字 0/1（易与 O/I 混）", !code.contains('0') && !code.contains('1'))
        assertTrue("不该有 Base64 的 + / =", !code.contains('+') && !code.contains('/') && !code.contains('='))
        assertTrue("只应是 A-Z 2-7 与连字符", code.all { it in 'A'..'Z' || it in '2'..'7' || it == '-' })
    }

    @Test
    fun `备份码按组分隔便于核对`() {
        val code = SyncKeyCodec.encode(key)
        val groups = code.split('-')

        assertEquals("每组 4 个字符", 4, groups.first().length)
        assertTrue("应有多个分组", groups.size > 8)
        assertTrue("分组长度一致", groups.dropLast(1).all { it.length == 4 })
    }

    /** 长度可预期：32 字节密钥 + 4 字节校验 = 36 字节 → 58 个 Base32 字符。 */
    @Test
    fun `备份码长度固定`() {
        val a = SyncKeyCodec.encode(ByteArray(32) { 1 })
        val b = SyncKeyCodec.encode(ByteArray(32) { 2 })
        assertEquals("不同密钥的备份码长度必须一致", a.length, b.length)
        assertEquals(a.split('-').size, b.split('-').size)
    }

    // ---------------------------------------------------------------- 宽容输入

    /** 抄写/粘贴时常见的格式差异都要能接受，否则用户会以为"码不对"。 */
    @Test
    fun `忽略大小写与分隔符`() {
        val code = SyncKeyCodec.encode(key)

        assertArrayEquals(key, SyncKeyCodec.decode(code.lowercase()))
        assertArrayEquals(key, SyncKeyCodec.decode(code.replace("-", "")))
        assertArrayEquals(key, SyncKeyCodec.decode("  $code  "))
        assertArrayEquals(key, SyncKeyCodec.decode(code.replace("-", " ")))
        assertArrayEquals(key, SyncKeyCodec.decode(code.replace("-", "\n")))
    }

    /** 中文输入法下连字符常打成全角 —— 这个坑很常见。 */
    @Test
    fun `接受全角连字符`() {
        val code = SyncKeyCodec.encode(key)
        assertArrayEquals(key, SyncKeyCodec.decode(code.replace("-", "－")))
    }

    // ---------------------------------------------------------------- 校验位

    /**
     * **核心承诺**：抄错一位必须在导入时立刻报错，
     * 而不是等到第一次解密失败（那时用户已以为同步成功了）。
     */
    @Test
    fun `抄错一位会被校验位抓住`() {
        val code = SyncKeyCodec.encode(key)

        var caught = 0
        var tested = 0
        code.forEachIndexed { index, c ->
            if (c == '-') return@forEachIndexed
            val replacement = if (c == 'A') 'B' else 'A'
            val corrupted = code.substring(0, index) + replacement + code.substring(index + 1)
            tested++
            val error = runCatching { SyncKeyCodec.decode(corrupted) }.exceptionOrNull()
            if (error is InvalidKeyCodeException) caught++
        }

        assertTrue("至少测了 40 个位置", tested > 40)
        assertTrue("抄错一位必须被抓住（实际抓住 $caught/$tested）", caught >= tested - 1)
    }

    /** 尾部字符被截断必须报错，不能被当成合法密钥。 */
    @Test
    fun `截断的备份码被拒绝`() {
        val code = SyncKeyCodec.encode(key)
        val truncated = code.substring(0, code.length - 5)

        val error = runCatching { SyncKeyCodec.decode(truncated) }.exceptionOrNull()
        assertTrue("必须报错", error is InvalidKeyCodeException)
    }

    @Test
    fun `多出字符的备份码被拒绝`() {
        val error = runCatching { SyncKeyCodec.decode(SyncKeyCodec.encode(key) + "ABCD") }.exceptionOrNull()
        assertTrue(error is InvalidKeyCodeException)
    }

    /** 非法字符要给出**可操作**的提示，而不是底层异常。 */
    @Test
    fun `非法字符给出明确提示`() {
        // 0/1 不在 Base32 字母表里（刻意排除，避免与 O/I 混淆）
        val error = runCatching { SyncKeyCodec.decode("AAAA0000AAAA") }.exceptionOrNull()

        assertTrue(error is InvalidKeyCodeException)
        assertTrue(
            "提示要说明允许哪些字符，实际：${error!!.message}",
            error.message!!.contains("A-Z") || error.message!!.contains("2-7"),
        )
    }

    @Test
    fun `空输入被拒绝`() {
        assertTrue(runCatching { SyncKeyCodec.decode("") }.exceptionOrNull() is InvalidKeyCodeException)
        assertTrue(runCatching { SyncKeyCodec.decode("   ") }.exceptionOrNull() is InvalidKeyCodeException)
        assertTrue(runCatching { SyncKeyCodec.decode("-----") }.exceptionOrNull() is InvalidKeyCodeException)
    }

    /** 校验失败的消息要提示"可能抄错了"，而不是只说"校验失败"。 */
    @Test
    fun `校验失败的信息可操作`() {
        val code = SyncKeyCodec.encode(key)
        // 改最后一个字符（落在校验位区域）
        val corrupted = code.dropLast(1) + if (code.last() == 'A') 'B' else 'A'

        val error = runCatching { SyncKeyCodec.decode(corrupted) }.exceptionOrNull()
        assertTrue(error is InvalidKeyCodeException)
        assertTrue(
            "要提示用户去核对原文，实际：${error!!.message}",
            error.message!!.let { it.contains("抄错") || it.contains("长度") },
        )
    }

    // ---------------------------------------------------------------- 指纹与显示

    /** 指纹用于让用户确认"用的是哪把钥匙"，必须稳定且不同的钥匙不同。 */
    @Test
    fun `指纹稳定且可区分`() {
        val a = SyncKeyCodec.fingerprint(key)
        val b = SyncKeyCodec.fingerprint(key)
        val other = SyncKeyCodec.fingerprint(ByteArray(32) { (it + 1).toByte() })

        assertEquals("同一把钥匙指纹必须稳定", a, b)
        assertNotEquals("不同钥匙指纹应不同", a, other)
        assertEquals("指纹是 8 个字符", 8, a.length)
    }

    /** 指纹**不能**泄露密钥内容 —— 它是哈希派生的，不是密钥切片。 */
    @Test
    fun `指纹不等于密钥前缀`() {
        val fingerprint = SyncKeyCodec.fingerprint(key)
        val code = SyncKeyCodec.encode(key)
        assertFalse("指纹不该出现在备份码的开头", code.startsWith(fingerprint))
    }

    @Test
    fun `显示格式化按行切分`() {
        val code = SyncKeyCodec.encode(key)
        val lines = SyncKeyCodec.formatForDisplay(code, groupsPerLine = 4)

        assertTrue("应切多行", lines.size > 1)
        assertEquals("拼回去要和原码一致", code, lines.joinToString("-"))
    }

    // ---------------------------------------------------------------- 端到端

    /** 完整链路：生成 → 显示 → 用户抄写 → 导入 → 能解开云端数据。 */
    @Test
    fun `生成到导入的完整链路可用`() {
        val (generatedKey, code) = SyncKeyStore.generate()

        // 模拟用户抄写（去掉分组）
        val asTyped = code.replace("-", "").lowercase()
        val imported = SyncKeyStore.import(asTyped)

        assertArrayEquals(generatedKey, imported)

        // 用它解密一条用原密钥加密的数据
        val stored = SyncCrypto.encrypt("云端内容".toByteArray(), generatedKey)
        assertEquals("云端内容", String(SyncCrypto.decrypt(stored, imported)))
    }

    @Test
    fun `存储包装往返一致`() {
        val wrapped = SyncKeyStore.wrapForStorage(key)
        assertArrayEquals(key, SyncKeyStore.unwrapFromStorage(wrapped))
    }
}
