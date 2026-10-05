package com.phonlynn.oreplan.v2.screens

import com.phonlynn.oreplan.core.rt.RichDoc
import com.phonlynn.oreplan.core.rt.RichLine
import com.phonlynn.oreplan.core.rt.RichLineKind
import com.phonlynn.oreplan.core.rt.RichSpan
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * **自动宽度判定回归测试**（2026-09-20）。
 *
 * 背景：富文本改造后正文落库改成 JSON。`isWide` 原先直接对 `body` 字符串
 * 数字符，于是**结构字符**（`{"v":1,"lines":[{"t":"...`,`k`,`s` 等）
 * 也被算进字数，任何卡片都远超阈值 → 全部变宽卡，表现为「自动宽度失效」。
 *
 * 这里把「字数必须按纯文字算」钉死，防止以后再把原始字符串当文字用。
 */
class BoardCardWidthTest {

    private fun card(
        title: String? = null,
        body: String? = null,
        widthMode: String? = null,
    ) = BoardCard(
        id = "c1",
        type = BoardCardType.QUICK,
        title = title,
        body = body,
        widthMode = widthMode,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /** 构造一段富文本正文（JSON 落库形式）。 */
    private fun richBody(text: String, bold: Boolean = false): String =
        RichDoc(
            listOf(
                RichLine(
                    text,
                    RichLineKind.TEXT,
                    false,
                    if (bold) listOf(RichSpan(0, text.length, bold = true)) else emptyList(),
                ),
            ),
        ).encode()

    // ================================================================
    // 核心回归：JSON 结构字符不得计入字数
    // ================================================================

    /**
     * 短正文的卡片**不应**被判为宽卡。
     *
     * 这是本次 bug 的直接复现：JSON 落库后，即使正文只有两个字，
     * 原始字符串也有几十个字符（`{"v":1,"lines":[{"t":"短","k":"text"}]}`）。
     */
    @Test
    fun `短正文不应因 JSON 结构字符被判为宽卡`() {
        val c = card(body = richBody("短"))
        // 正文只有 1 个字，远低于默认阈值 24。
        assertFalse(c.isWide(24))
    }

    /** 带属性的短正文同样不应被判为宽卡（属性字段也不该计入）。 */
    @Test
    fun `带属性的短正文不应被判为宽卡`() {
        val c = card(body = richBody("短", bold = true))
        assertFalse(c.isWide(24))
    }

    /** 长正文仍应被判为宽卡（阈值判定本身不能失效）。 */
    @Test
    fun `长正文仍应被判为宽卡`() {
        val long = "字".repeat(30)
        val c = card(body = richBody(long))
        assertTrue(c.isWide(24))
    }

    /** 标题 + 正文合起来算字数。 */
    @Test
    fun `标题与正文合计字数`() {
        // 标题 12 字 + 正文 12 字 = 24，刚好达到阈值。
        val c = card(title = "标".repeat(12), body = richBody("文".repeat(12)))
        assertTrue(c.isWide(24))
        assertFalse(c.isWide(25))
    }

    /** 空白字符不计入字数。 */
    @Test
    fun `空白字符不计入字数`() {
        val c = card(body = richBody("字".repeat(10) + " ".repeat(50)))
        assertFalse(c.isWide(24))
    }

    /** 多行正文：换行不计入，各行文字合计。 */
    @Test
    fun `多行正文按各行文字合计`() {
        val body = RichDoc(
            listOf(
                RichLine("字".repeat(10)),
                RichLine("字".repeat(10)),
                RichLine("字".repeat(10), kind = RichLineKind.BULLET),
            ),
        ).encode()
        val c = card(body = body)
        assertTrue(c.isWide(24))
    }

    // ================================================================
    // 宽度模式优先级
    // ================================================================

    /** 手动「整行」始终为宽卡，与字数无关。 */
    @Test
    fun `手动整行始终为宽卡`() {
        val c = card(body = richBody("短"), widthMode = "full")
        assertTrue(cardIsFullWidth(c, 24, hasImages = false))
    }

    /** 手动「半宽」始终为窄卡，即使字数很多。 */
    @Test
    fun `手动半宽始终为窄卡`() {
        val c = card(body = richBody("字".repeat(100)), widthMode = "half")
        assertFalse(cardIsFullWidth(c, 24, hasImages = false))
    }

    /** 自动模式下有图则整行（窄卡放不下多张图）。 */
    @Test
    fun `自动模式有图则整行`() {
        val c = card(body = richBody("短"))
        assertTrue(cardIsFullWidth(c, 24, hasImages = true))
    }

    /** 自动模式无图时按字数判定。 */
    @Test
    fun `自动模式无图按字数判定`() {
        val short = card(body = richBody("短"))
        val long = card(body = richBody("字".repeat(30)))
        assertFalse(cardIsFullWidth(short, 24, hasImages = false))
        assertTrue(cardIsFullWidth(long, 24, hasImages = false))
    }

    // ================================================================
    // 边界
    // ================================================================

    /** 正文为空/为 null 都不该崩，且不算宽。 */
    @Test
    fun `空正文不算宽`() {
        assertFalse(card(body = null).isWide(24))
        assertFalse(card(body = "").isWide(24))
        assertFalse(card(body = RichDoc.ofText("").encode()).isWide(24))
    }

    /** 阈值恰好相等时视为宽卡（>= 语义）。 */
    @Test
    fun `字数等于阈值时为宽卡`() {
        val c = card(body = richBody("字".repeat(24)))
        assertTrue(c.isWide(24))
    }

    /** 阈值下的边界：少一个字就是窄卡。 */
    @Test
    fun `字数少于阈值时为窄卡`() {
        val c = card(body = richBody("字".repeat(23)))
        assertFalse(c.isWide(24))
    }

    /** 非 JSON 的旧纯文本正文也应按纯文字判定（兜底路径）。 */
    @Test
    fun `旧纯文本正文按纯文字判定`() {
        val short = card(body = "短的纯文本")
        val long = card(body = "字".repeat(30))
        assertFalse(short.isWide(24))
        assertTrue(long.isWide(24))
    }

    /** 列表标记（无序/有序/复选框）不写成文字，因此不计入字数。 */
    @Test
    fun `列表结构不额外计入字数`() {
        val body = RichDoc(
            listOf(
                RichLine("字".repeat(5), kind = RichLineKind.BULLET),
                RichLine("字".repeat(5), kind = RichLineKind.ORDERED),
                RichLine("字".repeat(5), kind = RichLineKind.CHECK),
            ),
        ).encode()
        val c = card(body = body)
        // 实际只有 15 个字，低于 24。
        assertFalse(c.isWide(24))
        assertEquals(15, com.phonlynn.oreplan.core.rt.plainTextOf(body).count { !it.isWhitespace() })
    }
}
