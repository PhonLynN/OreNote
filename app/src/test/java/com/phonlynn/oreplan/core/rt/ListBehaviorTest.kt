package com.phonlynn.oreplan.core.rt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **列表的行语义回归测试**（2026-09-23 第二次重写配套）。
 *
 * 这里钉死用户在真机上报告的四个行为。它们曾经全部坏掉，
 * 根因是把列表符号做成了文本流里的真实字符（于是分隔用的不可见字符
 * 参与了每一次编辑，光标会停在「没有内容含义」的位置）。
 *
 * 四项都必须永远成立：
 *
 * 1. 列表行回车 → **续行**（新段仍是列表），且光标在**新段**里；
 * 2. 空列表项回车 → **退出列表**（不再是列表）；
 * 3. 列表行行首退格 → **退出列表**（文字不动）；
 * 4. 连续退格 → 交替「退出列表 / 合并上一行」，**不会卡住**。
 */
class ListBehaviorTest {

    private fun bullet(doc: RichDoc, line: Int) = doc.setLineKind(line, RichLineKind.BULLET)
    private fun check(doc: RichDoc, line: Int) = doc.setLineKind(line, RichLineKind.CHECK)

    // ================================================================
    // 1. 回车续行
    // ================================================================

    @Test
    fun `回车：列表行续行且新段仍是列表`() {
        var doc = RichDoc.ofText("甲")
        doc = bullet(doc, 0)
        // 光标在行尾（内容下标 1）
        val r = doc.splitLineAt(1)
        assertEquals(2, r.doc.lines.size)
        assertEquals(RichLineKind.BULLET, r.doc.lines[0].kind)
        assertEquals(RichLineKind.BULLET, r.doc.lines[1].kind)
    }

    @Test
    fun `回车：光标落在新段里而不是旧段`() {
        var doc = RichDoc.ofText("甲")
        doc = bullet(doc, 0)
        val r = doc.splitLineAt(1)
        // 光标应当在新段的行首：内容坐标 = "甲\n" 的长度 = 2
        assertEquals(2, r.caret)
        val pos = r.doc.caretOf(r.caret)
        assertEquals(1, pos.line)
        assertEquals(0, pos.col)
    }

    @Test
    fun `回车：空列表项退出列表`() {
        var doc = RichDoc.ofText("")
        doc = bullet(doc, 0)
        val r = doc.splitLineAt(0)
        // 空项回车 = 退出列表，不新起一段
        assertEquals(1, r.doc.lines.size)
        assertEquals(RichLineKind.TEXT, r.doc.lines[0].kind)
    }

    @Test
    fun `回车：复选框续行且新项未勾选`() {
        var doc = RichDoc.ofText("甲")
        doc = check(doc, 0)
        doc = doc.toggleCheck(0)
        assertTrue(doc.lines[0].checked)
        val r = doc.splitLineAt(1)
        assertEquals(RichLineKind.CHECK, r.doc.lines[1].kind)
        assertFalse("新项不应继承勾选态", r.doc.lines[1].checked)
    }

    @Test
    fun `回车：普通段落不附带列表类型`() {
        val doc = RichDoc.ofText("甲")
        val r = doc.splitLineAt(1)
        assertEquals(RichLineKind.TEXT, r.doc.lines[0].kind)
        assertEquals(RichLineKind.TEXT, r.doc.lines[1].kind)
    }

    // ================================================================
    // 2. 行首退格退出列表
    // ================================================================

    @Test
    fun `行首退格：列表段退出列表且文字不变`() {
        var doc = RichDoc.ofText("甲")
        doc = bullet(doc, 0)
        val r = doc.backspaceAtLineStart(0)
        assertEquals(RichLineKind.TEXT, r.doc.lines[0].kind)
        assertEquals("甲", r.doc.lines[0].text)
        assertEquals("文字不应被改动", "甲", r.doc.plainText)
    }

    @Test
    fun `行首退格：复选框退出时清掉勾选态`() {
        var doc = RichDoc.ofText("甲")
        doc = check(doc, 0)
        doc = doc.toggleCheck(0)
        val r = doc.backspaceAtLineStart(0)
        assertEquals(RichLineKind.TEXT, r.doc.lines[0].kind)
        assertFalse(r.doc.lines[0].checked)
    }

    @Test
    fun `行首退格：普通段首段无操作`() {
        val doc = RichDoc.ofText("甲")
        val r = doc.backspaceAtLineStart(0)
        assertEquals(doc, r.doc)
    }

    @Test
    fun `行首退格：普通段非首段合并到上一段`() {
        val doc = RichDoc.ofText("甲\n乙")
        // 光标在第二段行首（内容下标 2）
        val r = doc.backspaceAtLineStart(2)
        assertEquals(1, r.doc.lines.size)
        assertEquals("甲乙", r.doc.plainText)
        assertEquals(1, r.caret)
    }

    // ================================================================
    // 3. 连续退格不卡住（用户报告「会卡住」）
    // ================================================================

    @Test
    fun `连续退格：三行列表可以从末尾一路退干净`() {
        var doc = RichDoc.ofText("甲\n乙\n丙")
        doc = bullet(doc, 0)
        doc = bullet(doc, 1)
        doc = bullet(doc, 2)

        // 每轮：光标在行首 → 退出列表；否则按普通退格删一个字符。
        var caret = doc.textLength
        var guard = 0
        while (guard++ < 40) {
            if (doc.textLength == 0 && doc.lines.size == 1 && doc.lines[0].kind == RichLineKind.TEXT) break
            val pos = doc.caretOf(caret)
            if (pos.col == 0) {
                val r = doc.backspaceAtLineStart(caret)
                doc = r.doc
                caret = r.caret
            } else {
                val r = doc.deleteRange(caret - 1, caret)
                doc = r.doc
                caret = r.caret
            }
        }

        assertEquals("最终应为单个空普通段", 1, doc.lines.size)
        assertEquals(RichLineKind.TEXT, doc.lines[0].kind)
        assertEquals("", doc.plainText)
    }

    @Test
    fun `连续退格：每一步都真的改变了状态（没有卡住）`() {
        var doc = RichDoc.ofText("甲\n乙")
        doc = bullet(doc, 0)
        doc = bullet(doc, 1)
        var caret = doc.textLength
        var steps = 0
        // 只要还有「可退的东西」，每一步都必须让状态前进。
        while (steps++ < 20) {
            val before = doc to caret
            val pos = doc.caretOf(caret)
            val r = if (pos.col == 0) doc.backspaceAtLineStart(caret) else doc.deleteRange(caret - 1, caret)
            doc = r.doc
            caret = r.caret
            if (doc.textLength == 0 && doc.lines[0].kind == RichLineKind.TEXT) break
            assertTrue(
                "第 $steps 步没有产生任何变化（卡住）：${before.first.plainText} kinds=${before.first.lines.map { it.kind }}",
                (doc to caret) != before,
            )
        }
    }

    // ================================================================
    // 4. 这些规则不依赖任何不可见字符
    // ================================================================

    @Test
    fun `文本流：列表段落里没有符号字符`() {
        var doc = RichDoc.ofText("甲")
        doc = bullet(doc, 0)
        doc = check(doc, 0)
        assertEquals("甲", doc.plainText)
        assertFalse(doc.encode().contains("•"))
        assertFalse(doc.encode().contains("☐"))
        assertFalse(doc.encode().contains("\u200B"))
    }
}
