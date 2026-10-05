package com.phonlynn.oreplan.core.rt

import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「不产生空行」回归测试**（2026-09-23，用户报「全页崩坏」后新增）。
 *
 * # 这一条守住的是那次崩坏
 *
 * 用户当时看到：列表行之间出现巨大空隙，整页像坏掉。
 * 根因是我用 `TextIndent`（段落级样式）做逐行缩进，而 Compose 是拿
 * **段落样式区间**切段落的 —— 于是每个 `\n` 都落在段落边界上，
 * 而**以 `\n` 结尾的段落会多排出一个空行**，列表行距直接翻倍。
 *
 * # 判据
 *
 * 段落数必须等于**文档段数**。
 *
 * 判断「会不会多空行」的本质是数段落：整份文本必须以 `\n` 连接、
 * 并且**没有**任何段落级样式去切碎它 —— 这样 `MultiParagraph` 只会
 * 得到一个段落，`\n` 只是普通换行，行数就等于段数。
 */
class NoParagraphStyleTest {

    private val style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp)

    private fun shown(doc: RichDoc) = buildDisplayText(
        doc, style, androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Gray,
    )

    /** 各种文档形状都要满足「不切段落」。 */
    private fun assertOneParagraph(doc: RichDoc, label: String) {
        val ann = shown(doc)
        assertEquals(
            "$label：段落级样式必须为空（否则会切碎段落、凭空多出空行）",
            0,
            ann.paragraphStyles.size,
        )
        // 文本流 = 各段以 \n 连接 → 换行符数量 = 段数 - 1
        assertEquals(
            "$label：换行符数量应等于段数-1",
            (doc.lines.size - 1).coerceAtLeast(0),
            ann.text.count { it == '\n' },
        )
    }

    @Test
    fun `列表与普通段落混合时不切段落`() {
        assertOneParagraph(
            RichDoc(
                listOf(
                    RichLine("复选框可以点：", RichLineKind.TEXT),
                    RichLine("已完成的项", RichLineKind.CHECK, checked = true),
                    RichLine("未完成的项", RichLineKind.CHECK),
                    RichLine("回车应该继续同类列表。", RichLineKind.TEXT),
                ),
            ),
            "用户实际遇到的那张卡",
        )
    }

    @Test
    fun `三行无序列表不切段落`() {
        assertOneParagraph(
            RichDoc(
                listOf(
                    RichLine("甲", RichLineKind.BULLET),
                    RichLine("乙", RichLineKind.BULLET),
                    RichLine("丙", RichLineKind.BULLET),
                ),
            ),
            "连续列表",
        )
    }

    @Test
    fun `全普通段落不切段落`() {
        assertOneParagraph(RichDoc.ofText("甲\n乙\n丙"), "普通段落")
    }

    @Test
    fun `空段不切段落`() {
        assertOneParagraph(
            RichDoc(listOf(RichLine("甲"), RichLine(""), RichLine("乙"))),
            "含空段",
        )
    }

    @Test
    fun `空列表项不切段落`() {
        assertOneParagraph(
            RichDoc(
                listOf(
                    RichLine("甲", RichLineKind.BULLET),
                    RichLine("", RichLineKind.BULLET),
                ),
            ),
            "空列表项",
        )
    }

    @Test
    fun `单段不切段落`() {
        assertOneParagraph(RichDoc(listOf(RichLine("甲", RichLineKind.BULLET))), "单段")
    }

    @Test
    fun `空文档不崩溃`() {
        val ann = shown(RichDoc.EMPTY)
        assertTrue(ann.paragraphStyles.isEmpty())
        assertEquals("", ann.text)
    }

    @Test
    fun `行数等于段数的充分条件已满足`() {
        // 段落数 = 1、且无段落样式 → \n 只产生换行不产生空行。
        val d = RichDoc(
            listOf(
                RichLine("甲", RichLineKind.TEXT),
                RichLine("乙", RichLineKind.BULLET),
                RichLine("丙", RichLineKind.CHECK),
                RichLine("丁", RichLineKind.ORDERED),
            ),
        )
        val ann = shown(d)
        assertEquals("段落样式数", 0, ann.paragraphStyles.size)
        assertEquals("换行符数", 3, ann.text.count { it == '\n' })
        assertEquals("段数", 4, d.lines.size)
    }
}
