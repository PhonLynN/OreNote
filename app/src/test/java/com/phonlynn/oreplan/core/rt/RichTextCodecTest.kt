package com.phonlynn.oreplan.core.rt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **编解码回归测试**（2026-09-23 重写配套）。
 *
 * 这里钉死三件事：
 *
 * 1. **符号与 0 宽字符绝不落库** —— 落库 JSON 的 `t` 永远是纯正文；
 * 2. **v1 数据照常读** —— 新旧只差一个版本号，无迁移；
 * 3. **绝不丢字** —— 非 JSON 内容按纯文本兜底。
 */
class RichTextCodecTest {

    // ================================================================
    // 一、往返
    // ================================================================

    @Test
    fun `往返：纯文本`() {
        val doc = RichDoc.ofText("第一行\n第二行")
        assertEquals(doc, RichDoc.decode(doc.encode()))
    }

    @Test
    fun `往返：五种行内属性`() {
        val doc = RichDoc(
            listOf(
                // 注意：文字长度必须与 span 区间匹配，否则 normalized() 会裁掉越界部分。
                RichLine(
                    "五种属性", kind = RichLineKind.TEXT,
                    spans = listOf(
                        RichSpan(0, 1, bold = true),
                        RichSpan(1, 2, underline = true),
                        RichSpan(2, 3, strike = true),
                        RichSpan(3, 4, italic = true),
                    ),
                ),
            ),
        )
        val back = RichDoc.decode(doc.encode())
        assertEquals(doc, back)
        assertTrue(back.lines[0].isBoldAt(0))
        assertTrue(back.lines[0].isUnderlineAt(1))
        assertTrue(back.lines[0].isStrikeAt(2))
        assertTrue(back.lines[0].isItalicAt(3))
    }

    @Test
    fun `往返：四种段类型`() {
        val doc = RichDoc(
            listOf(
                RichLine("普通", RichLineKind.TEXT),
                RichLine("无序", RichLineKind.BULLET),
                RichLine("有序", RichLineKind.ORDERED),
                RichLine("待办", RichLineKind.CHECK, checked = true),
            ),
        )
        assertEquals(doc, RichDoc.decode(doc.encode()))
    }

    @Test
    fun `往返：多次往返稳定`() {
        var doc = RichDoc.ofText("甲")
        doc = doc.setLineKind(0, RichLineKind.BULLET)
        doc = doc.setAttr(0, 0, 1, RichAttr.BOLD, on = true)
        var s = doc.encode()
        repeat(5) { s = RichDoc.decode(s).encode() }
        assertEquals(doc, RichDoc.decode(s))
    }

    // ================================================================
    // 二、符号与 0 宽字符绝不落库（本次重写的核心约定）
    // ================================================================

    @Test
    fun `符号不落库：列表段只存纯正文`() {
        val doc = RichDoc(listOf(RichLine("列表项", RichLineKind.BULLET)))
        val encoded = doc.encode()
        assertFalse("圆点不得出现在落库 JSON 里", encoded.contains("●"))
        assertFalse(encoded.contains("\u2022"))
        assertTrue(encoded.contains("\"t\":\"列表项\""))
    }

    @Test
    fun `符号不落库：复选框不存方框字符`() {
        val doc = RichDoc(listOf(RichLine("待办", RichLineKind.CHECK, checked = true)))
        val encoded = doc.encode()
        assertFalse(encoded.contains("☐"))
        assertFalse(encoded.contains("☑"))
    }

    @Test
    fun `0宽字符不落库`() {
        // 构造一份「不该出现」的脏数据：正文里混了 0 宽空格。
        val dirty = "{\"v\":2,\"lines\":[{\"t\":\"甲\u200B乙\",\"k\":\"text\"}]}"
        val doc = RichDoc.decode(dirty)
        assertEquals("甲乙", doc.lines[0].text)
        assertFalse(doc.encode().contains("\u200B"))
    }

    @Test
    fun `落库字符串里没有任何不可见字符`() {
        val doc = RichDoc(
            listOf(
                RichLine("", RichLineKind.BULLET),
                RichLine("内容", RichLineKind.ORDERED),
                RichLine("待办", RichLineKind.CHECK),
            ),
        )
        val encoded = doc.encode()
        val invisible = encoded.filter { it.code == 0x200B || it.code == 0xFEFF }
        assertTrue("落库串里不得有 0 宽字符：$encoded", invisible.isEmpty())
    }

    // ================================================================
    // 三、版本兼容
    // ================================================================

    @Test
    fun `v1 数据照常读`() {
        val v1 = "{\"v\":1,\"lines\":[{\"t\":\"标题\",\"k\":\"text\"," +
            "\"s\":[{\"a\":0,\"b\":2,\"bold\":true}]}]}"
        val doc = RichDoc.decode(v1)
        assertEquals("标题", doc.lines[0].text)
        assertTrue(doc.lines[0].isBoldAt(0))
        // 写回时升到 v2。
        assertTrue(doc.encode().contains("\"v\":2"))
    }

    @Test
    fun `v1 的列表段读出来仍是列表段`() {
        val v1 = "{\"v\":1,\"lines\":[{\"t\":\"项\",\"k\":\"bullet\"}]}"
        assertEquals(RichLineKind.BULLET, RichDoc.decode(v1).lines[0].kind)
    }

    @Test
    fun `缺字段的 JSON 用默认值补齐`() {
        val partial = "{\"lines\":[{\"t\":\"甲\"},{\"t\":\"乙\",\"k\":\"check\"}]}"
        val doc = RichDoc.decode(partial)
        assertEquals(2, doc.lines.size)
        assertEquals(RichLineKind.TEXT, doc.lines[0].kind)
        assertEquals(RichLineKind.CHECK, doc.lines[1].kind)
        assertFalse(doc.lines[1].checked)
    }

    @Test
    fun `未知段类型按普通段落处理`() {
        val weird = "{\"lines\":[{\"t\":\"甲\",\"k\":\"未来类型\"}]}"
        assertEquals(RichLineKind.TEXT, RichDoc.decode(weird).lines[0].kind)
    }

    // ================================================================
    // 四、兜底：绝不丢字
    // ================================================================

    @Test
    fun `兜底：纯文本按行拆段`() {
        val doc = RichDoc.decode("甲\n乙\n丙")
        assertEquals(3, doc.lines.size)
        assertEquals("甲", doc.lines[0].text)
        assertEquals("丙", doc.lines[2].text)
    }

    @Test
    fun `兜底：旧 Markdown 标记原文保留`() {
        // 更早的版本用 Markdown 标记存在正文里。不能丢字，只是没有格式。
        val doc = RichDoc.decode("**加粗** 和 ++下划线++")
        assertEquals("**加粗** 和 ++下划线++", doc.lines[0].text)
        assertEquals(RichLineKind.TEXT, doc.lines[0].kind)
    }

    @Test
    fun `兜底：坏 JSON 当作纯文本`() {
        val doc = RichDoc.decode("{\"v\":2,\"lines\":[不是合法 JSON")
        assertEquals("{\"v\":2,\"lines\":[不是合法 JSON", doc.lines[0].text)
    }

    @Test
    fun `兜底：空输入得到空文档`() {
        assertEquals(RichDoc.EMPTY, RichDoc.decode(null))
        assertEquals(RichDoc.EMPTY, RichDoc.decode(""))
    }

    @Test
    fun `兜底：lines 为空数组得到空文档`() {
        assertEquals(RichDoc.EMPTY, RichDoc.decode("{\"v\":2,\"lines\":[]}"))
    }

    // ================================================================
    // 五、纯文字提取（内部规则：凡读正文必先过它）
    // ================================================================

    @Test
    fun `纯文字提取：不含任何结构字段`() {
        val doc = RichDoc(
            listOf(
                RichLine("标题", RichLineKind.TEXT, spans = listOf(RichSpan(0, 2, bold = true))),
                RichLine("列表", RichLineKind.BULLET),
            ),
        )
        val raw = doc.encode()
        val plain = plainTextOf(raw)
        assertEquals("标题\n列表", plain)
        assertFalse(plain.contains("v\""))
        assertFalse(plain.contains("bold"))
        assertFalse(plain.contains("bullet"))
    }

    @Test
    fun `摘要提取：不含 JSON 结构`() {
        val raw = RichDoc(listOf(RichLine("这是一张卡片的正文"))).encode()
        val preview = plainPreviewOf(raw, 6)
        assertEquals("这是一张卡片", preview)
        assertFalse(preview.startsWith("{\"v\""))
    }

    @Test
    fun `摘要提取：换行转空格`() {
        val raw = RichDoc.ofText("第一行\n第二行").encode()
        assertEquals("第一行 第二行", plainPreviewOf(raw, 20))
    }

    // ================================================================
    // 六、净化是幂等的
    // ================================================================

    @Test
    fun `净化：不含 0 宽字符时原样返回`() {
        assertEquals("甲乙", RichTextCodec.sanitizeContent("甲乙"))
    }

    @Test
    fun `净化：重复调用结果不变`() {
        val once = RichTextCodec.sanitizeContent("甲\u200B乙")
        val twice = RichTextCodec.sanitizeContent(once)
        assertEquals("甲乙", once)
        assertEquals(once, twice)
    }
}
