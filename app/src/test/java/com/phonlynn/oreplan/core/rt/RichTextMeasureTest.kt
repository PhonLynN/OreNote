package com.phonlynn.oreplan.core.rt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **渲染内核不变式测试**（2026-09-23 第三次重写配套）。
 *
 * # 这一版要钉死的核心事实
 *
 * ## 1. 绝不使用段落级样式（血的教训）
 *
 * Compose 的 `MultiParagraph` 是拿**段落样式区间**切段落的，不是拿 `\n` 切。
 * 只要用 `TextIndent` 之类的段落样式做逐行缩进，就一定会让某个 `\n`
 * 落在段落边界上，而**以 `\n` 结尾的段落会多排出一个空行**。
 *
 * 上一版就是这么把「每个列表行后面凭空多一个空行」搞出来的
 *（用户看到的是「所有文字页面全部崩坏」）。
 *
 * 所以现在的铁律是：**列表缩进绝不能来自段落样式**，
 * 只能来自文本流里的前缀字符。
 *
 * ## 2. 缩进由前缀字符提供
 *
 * 列表行的文字前面有 `[标记字符][宽度字符]`，它们的宽度就是缩进。
 * 因为它们是普通字符，所以整份文本仍然是**一个**段落。
 */
class RichTextMeasureTest {

    private val style = TextStyle(fontSize = 15.sp)
    private val symbolColor = Color(0xFF5F6B64)
    private val checkedColor = Color(0xFF6F7A73)

    private fun display(d: RichDoc) = buildDisplayText(d, style, symbolColor, checkedColor)

    // ================================================================
    // 一、绝不使用段落级样式（本版最重要的铁律）
    // ================================================================

    @Test
    fun `任何文档都不产生段落级样式`() {
        val docs = listOf(
            RichDoc.ofText("甲\n乙\n丙"),
            RichDoc(listOf(RichLine("甲", RichLineKind.BULLET))),
            RichDoc(
                listOf(
                    RichLine("甲", RichLineKind.TEXT),
                    RichLine("乙", RichLineKind.BULLET),
                    RichLine("丙", RichLineKind.CHECK, checked = true),
                    RichLine("丁", RichLineKind.ORDERED),
                ),
            ),
        )
        docs.forEach { d ->
            val ps: List<androidx.compose.ui.text.AnnotatedString.Range<ParagraphStyle>> =
                display(d).paragraphStyles
            assertTrue(
                "不得使用段落级样式（会切碎段落并凭空产生空行）：$ps",
                ps.isEmpty(),
            )
        }
    }

    @Test
    fun `没有任何段落缩进`() {
        val d = RichDoc(
            listOf(
                RichLine("甲", RichLineKind.BULLET),
                RichLine("乙", RichLineKind.ORDERED),
                RichLine("丙", RichLineKind.CHECK),
            ),
        )
        display(d).paragraphStyles.forEach { r ->
            assertEquals(TextIndent.None, r.item.textIndent)
        }
    }

    @Test
    fun `文本流里没有换行符之外的空白撑高`() {
        // 整份文本只有一个段落 → 行数 = 段数（\n 数量 + 1）→ 不会多出空行。
        val d = RichDoc(
            listOf(
                RichLine("甲", RichLineKind.TEXT),
                RichLine("乙", RichLineKind.BULLET),
                RichLine("丙", RichLineKind.TEXT),
            ),
        )
        val text = display(d).text
        assertEquals("文本流必须由各段以 \\n 连接而成", 2, text.count { it == '\n' })
    }

    // ================================================================
    // 二、前缀结构
    // ================================================================

    @Test
    fun `列表行以标记字符加宽度字符开头`() {
        RichLineKind.entries.filter { it != RichLineKind.TEXT }.forEach { kind ->
            val d = RichDoc(listOf(RichLine("甲", kind)))
            val shown = display(d).text
            assertTrue(
                "$kind 应以标记字符开头，实际首字符码位=${shown[0].code}",
                RichTextMark.isMark(shown[0]),
            )
            assertTrue(
                "第 2 个字符应为缩进字符（提供缩进宽度）",
                RichTextMark.isIndentChar(shown[1]),
            )
            assertEquals("甲", shown.substring(RichTextMark.prefixLengthOf(kind)))
        }
    }

    @Test
    fun `普通行没有任何前缀`() {
        val d = RichDoc(listOf(RichLine("甲", RichLineKind.TEXT)))
        assertEquals("甲", display(d).text)
    }

    @Test
    fun `四种标记互不相同且都能反推`() {
        val marks = listOf(
            RichTextMark.BULLET to (RichLineKind.BULLET to false),
            RichTextMark.ORDERED to (RichLineKind.ORDERED to false),
            RichTextMark.CHECK_OFF to (RichLineKind.CHECK to false),
            RichTextMark.CHECK_ON to (RichLineKind.CHECK to true),
        )
        assertEquals("标记字符不能重复", 4, marks.map { it.first }.toSet().size)
        marks.forEach { (mark, expect) ->
            assertEquals(expect, RichTextMark.kindOf(mark))
        }
    }

    @Test
    fun `标记字符都是不可见且不可输入的`() {
        // 必须是零宽/无字形的字符，否则会在正文里露出来。
        listOf(
            RichTextMark.BULLET, RichTextMark.ORDERED,
            RichTextMark.CHECK_OFF, RichTextMark.CHECK_ON,
        ).forEach { c ->
            assertTrue("标记字符应为不可见控制类字符：U+${c.code.toString(16)}", c.code in 0x2000..0x206F)
        }
    }

    // ================================================================
    // 三、解析往返（文本即模型）
    // ================================================================

    @Test
    fun `往返：文本到模型到文本稳定`() {
        val d = RichDoc(
            listOf(
                RichLine("普通", RichLineKind.TEXT),
                RichLine("无序", RichLineKind.BULLET),
                RichLine("有序", RichLineKind.ORDERED),
                RichLine("已勾选", RichLineKind.CHECK, checked = true),
                RichLine("未勾选", RichLineKind.CHECK, checked = false),
            ),
        )
        val text = RichEditState.canonicalTextOf(d)
        val back = parseDisplayText(text)
        assertEquals(d.lines.size, back.lines.size)
        d.lines.zip(back.lines).forEach { (a, b) ->
            assertEquals(a.text, b.text)
            assertEquals(a.kind, b.kind)
            assertEquals(a.checked, b.checked)
        }
        assertEquals(text, RichEditState.canonicalTextOf(back))
    }

    @Test
    fun `解析：只在行首识别前缀`() {
        // 正文中间出现标记字符时，必须被剥掉（合并两行时会遇到）。
        val raw = RichTextMark.BULLET.toString() + RichTextMark.IDEOGRAPHIC_SPACE + "甲" +
            RichTextMark.BULLET + RichTextMark.IDEOGRAPHIC_SPACE + "乙"
        val doc = parseDisplayText(raw)
        assertEquals(1, doc.lines.size)
        assertEquals("甲乙", doc.lines[0].text)
        assertEquals(RichLineKind.BULLET, doc.lines[0].kind)
    }

    @Test
    fun `解析：残缺前缀视为普通行且不留残留`() {
        // 宽度字符被删 → 只剩标记字符
        val raw = RichTextMark.BULLET.toString() + "甲"
        val doc = parseDisplayText(raw)
        assertEquals(RichLineKind.TEXT, doc.lines[0].kind)
        assertEquals("甲", doc.lines[0].text)
        assertFalse("正文里不能残留标记字符", doc.lines[0].text.any { RichTextMark.isMark(it) })
    }

    @Test
    fun `解析：不会把正文里的不可见字符留在结果里`() {
        val messy = "甲" + RichTextMark.CHECK_ON + RichTextMark.IDEOGRAPHIC_SPACE + "乙"
        val doc = parseDisplayText(messy)
        assertEquals("甲乙", doc.lines[0].text)
    }

    // ================================================================
    // 四、行内属性
    // ================================================================

    @Test
    fun `属性：加粗区间落在正文上而不是前缀上`() {
        val d = RichDoc(
            listOf(
                RichLine("加粗", RichLineKind.BULLET, spans = listOf(RichSpan(0, 2, bold = true))),
            ),
        )
        val ann = display(d)
        val bold = ann.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bold.size)
        // 前缀占 2 个字符，所以正文起点 = 2。
        val prefixLen = RichTextMark.prefixLengthOf(RichLineKind.BULLET)
        assertEquals("加粗区间必须落在正文上", prefixLen, bold[0].start)
        assertEquals(prefixLen + 2, bold[0].end)
    }

    @Test
    fun `属性：五种属性互不覆盖`() {
        val d = RichDoc(
            listOf(
                RichLine(
                    "abcde",
                    spans = listOf(
                        RichSpan(0, 1, bold = true),
                        RichSpan(1, 2, underline = true),
                        RichSpan(2, 3, strike = true),
                        RichSpan(3, 4, italic = true),
                        RichSpan(4, 5, highlight = true),
                    ),
                ),
            ),
        )
        val ann = display(d)
        assertEquals(5, ann.spanStyles.size)
        assertEquals(0, ann.spanStyles[0].start)
        assertEquals(5, ann.spanStyles[4].end)
    }

    @Test
    fun `属性：已勾选项正文转浅灰`() {
        val d = RichDoc(listOf(RichLine("待办", RichLineKind.CHECK, checked = true)))
        val ann = display(d)
        val colored = ann.spanStyles.filter { it.item.color == checkedColor }
        assertEquals(1, colored.size)
        val prefixLen = RichTextMark.prefixLengthOf(RichLineKind.CHECK)
        assertEquals(prefixLen, colored[0].start)
        assertEquals(prefixLen + 2, colored[0].end)
    }

    @Test
    fun `前缀里的缩进字符宽度合计等于 IndentEm`() {
        // 缩进不能靠 letterSpacing（实测在编辑框里不生效），
        // 而是靠真实空白字符：全角空格 1em + EN SPACE 0.5em。
        val chars = RichTextMark.indentChars()
        val width = chars.sumOf {
            if (it == RichTextMark.IDEOGRAPHIC_SPACE) 1.0 else 0.5
        }
        assertEquals(
            "缩进字符总宽应等于 IndentEm",
            RichLayout.IndentEm.toDouble(),
            width,
            0.001,
        )
        val d = RichDoc(listOf(RichLine("甲", RichLineKind.BULLET)))
        val shown = display(d).text
        chars.forEachIndexed { i, c ->
            assertEquals("前缀第 ${i + 1} 个字符应为缩进字符", c, shown[1 + i])
        }
    }

    // ================================================================
    // 五、两端一致
    // ================================================================

    @Test
    fun `一致：展示端与编辑端的文本逐字符相同`() {
        val d = RichDoc(
            listOf(
                RichLine("甲", RichLineKind.TEXT),
                RichLine("乙", RichLineKind.BULLET),
                RichLine("丙", RichLineKind.CHECK, checked = true),
                RichLine("丁", RichLineKind.ORDERED),
            ),
        )
        val displaySide = display(d)
        val state = RichEditState.ofDoc(d).also { it.baseStyle = style }
        state.symbolColor = symbolColor
        state.checkedColor = checkedColor

        assertEquals(displaySide.text, state.field.text)
        assertEquals(displaySide.spanStyles.size, state.field.annotatedString.spanStyles.size)
        displaySide.spanStyles.zip(state.field.annotatedString.spanStyles).forEach { (a, b) ->
            assertEquals(a.item, b.item)
            assertEquals(a.start, b.start)
            assertEquals(a.end, b.end)
        }
    }

    @Test
    fun `一致：规范文本与解析结果互为逆运算`() {
        val docs = listOf(
            RichDoc.ofText("甲\n乙"),
            RichDoc(listOf(RichLine("甲", RichLineKind.BULLET), RichLine("乙", RichLineKind.TEXT))),
            RichDoc(listOf(RichLine("", RichLineKind.CHECK))),
        )
        docs.forEach { d ->
            assertEquals(d, parseDisplayText(RichEditState.canonicalTextOf(d)))
        }
    }

    // ================================================================
    // 六、落库干净
    // ================================================================

    @Test
    fun `落库串里没有标记字符也没有宽度字符`() {
        val d = RichDoc(
            listOf(
                RichLine("甲", RichLineKind.BULLET),
                RichLine("乙", RichLineKind.CHECK, checked = true),
                RichLine("丙", RichLineKind.ORDERED),
            ),
        )
        val json = d.encode()
        RichTextMark.ALL.forEach { c ->
            assertFalse("落库串不得含标记字符 U+${c.code.toString(16)}: $json", json.contains(c))
        }
        assertFalse("落库串不得含宽度字符：$json", json.contains(RichTextMark.IDEOGRAPHIC_SPACE))
    }

    @Test
    fun `纯文字提取不含任何显示层字符`() {
        val d = RichDoc(
            listOf(
                RichLine("甲", RichLineKind.BULLET),
                RichLine("乙", RichLineKind.CHECK, checked = true),
            ),
        )
        val plain = plainTextOf(d.encode())
        assertEquals("甲\n乙", plain)
        assertFalse(plain.any { RichTextMark.isMark(it) || it == RichTextMark.IDEOGRAPHIC_SPACE })
    }
}
