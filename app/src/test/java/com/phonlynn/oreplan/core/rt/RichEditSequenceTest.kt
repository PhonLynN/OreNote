package com.phonlynn.oreplan.core.rt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **编辑序列回归测试**（2026-09-20 重写配套）。
 *
 * 这里的每一项都对应旧实现**实测会坏**的编辑序列。旧方案用「文本 diff 反推意图」，
 * 因此下面每一条都曾经出错；现在改为显式操作后必须全部正确。
 * 以后凡是在编辑上踩到坑，都先在这里补一条再修。
 */
class RichEditSequenceTest {

    // 便捷：把文档与选区当成编辑器的状态推进。
    private fun RichDoc.type(from: Int, to: Int, text: String, pending: Set<RichAttr> = emptySet()) =
        replaceRange(from, to, text, pending)

    // ================================================================
    // 一、中文输入法（旧实现「丢字」的根源）
    // ================================================================

    /**
     * 组合态 → 提交：`甲` 上输入拼音 `ni` 组合，再提交为 `你`。
     *
     * 旧实现看到的是「文本从 `甲ni` 变成 `甲你`」——长度由 3 变 2，
     * 于是判定为「删除了 1 个字符」，实际删掉的是 `i`，结果是 `甲i`。
     *
     * 正确做法：输入法提交是一次**替换选区**（把组合区间换成候选字）。
     */
    @Test
    fun `输入法：组合态提交候选字不丢字`() {
        var doc = RichDoc.ofText("甲")
        // 组合中：ni 进入（编辑框显示，但这里模拟为组合区间 [1,3) 的内容）
        doc = doc.type(1, 1, "ni").doc
        assertEquals("甲ni", doc.plainText)
        // 提交：把组合区间 [1,3) 替换为「你」
        val r = doc.type(1, 3, "你")
        assertEquals("甲你", r.doc.plainText)
        assertEquals(2, r.caret)
    }

    /**
     * 组合态 → 取消：输入法取消组合时，组合内容应被清掉、原文字不受影响。
     */
    @Test
    fun `输入法：取消组合回到原文`() {
        var doc = RichDoc.ofText("甲")
        doc = doc.type(1, 1, "ni").doc
        // 取消：删掉组合区间
        val r = doc.deleteRange(1, 3)
        assertEquals("甲", r.doc.plainText)
    }

    /**
     * 组合态中夹杂已有文字：`甲xx乙`，在 xx 处组合再提交，两侧文字必须完好。
     */
    @Test
    fun `输入法：两侧文字在提交后完好`() {
        var doc = RichDoc.ofText("甲乙")
        doc = doc.type(1, 1, "zhong").doc
        assertEquals("甲zhong乙", doc.plainText)
        val r = doc.type(1, 6, "中")
        assertEquals("甲中乙", r.doc.plainText)
    }

    // ================================================================
    // 二、全选删除（旧实现「删不干净」）
    // ================================================================

    /**
     * 全选删除两行：旧实现只删了首行，剩下 `- \n- 乙`。
     * 正确：整个文档清空为一个空段落。
     */
    @Test
    fun `全选删除：多行文档应被完全清空`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", kind = RichLineKind.BULLET),
                RichLine("乙", kind = RichLineKind.BULLET),
            ),
        )
        assertEquals(3, doc.textLength) // 甲 \n 乙
        val r = doc.deleteRange(0, doc.textLength)
        assertEquals("", r.doc.plainText)
        assertEquals(1, r.doc.lines.size)
        assertEquals(0, r.caret)
    }

    /** 全选删除带格式的多行，格式也必须一并消失。 */
    @Test
    fun `全选删除：格式一并清除`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", spans = listOf(RichSpan(0, 1, bold = true))),
                RichLine("乙", kind = RichLineKind.CHECK, checked = true),
            ),
        )
        val r = doc.deleteRange(0, 3)
        assertEquals("", r.doc.plainText)
        assertTrue(r.doc.lines[0].spans.isEmpty())
        assertEquals(RichLineKind.TEXT, r.doc.lines[0].kind)
    }

    /** 全选后直接输入：应当整体被新文字替换，而不是插入。 */
    @Test
    fun `全选后输入：整体替换`() {
        val doc = RichDoc.ofText("旧内容")
        val r = doc.replaceRange(0, doc.textLength, "新")
        assertEquals("新", r.doc.plainText)
        assertEquals(1, r.doc.lines.size)
    }

    // ================================================================
    // 三、跨行选区替换
    // ================================================================

    /** 跨行框选后输入：被选中的内容（含换行）被新文字替掉。 */
    @Test
    fun `跨行替换：中间段被替换`() {
        val doc = RichDoc.ofText("甲\n乙\n丙")
        // 坐标：甲=0, \n=1, 乙=2, \n=3, 丙=4（总长 5）
        // 选中「乙\n」即 [2,4)，替换为 X：删掉后为「甲\n丙」，再在位置 2 插入 X
        val r = doc.replaceRange(2, 4, "X")
        assertEquals("甲\nX丙", r.doc.plainText)
        assertEquals(2, r.doc.lines.size)
    }

    /** 跨行替换带换行的文字：可以重新拆段。 */
    @Test
    fun `跨行替换：插入内容含换行时正确拆段`() {
        val doc = RichDoc.ofText("甲\n乙\n丙")
        // 替换 [2,4) 为含换行的「X\nY」→ 甲、X、Y丙
        val r = doc.replaceRange(2, 4, "X\nY")
        assertEquals("甲\nX\nY丙", r.doc.plainText)
        assertEquals(3, r.doc.lines.size)
    }

    /** 跨行替换后光标应落在插入内容之后。 */
    @Test
    fun `跨行替换：光标位置正确`() {
        val doc = RichDoc.ofText("甲\n乙\n丙")
        val r = doc.replaceRange(2, 4, "XY")
        assertEquals("甲\nXY丙", r.doc.plainText)
        // 甲(0) \n(1) X(2) Y(3) → 光标在 4
        assertEquals(4, r.caret)
    }

    /** 跨行删除：把中间整段连同换行一起删掉，首尾拼接。 */
    @Test
    fun `跨行删除：保留首尾`() {
        val doc = RichDoc.ofText("abc\ndef\nghi")
        // 删掉 "c\ndef\ng" 即 [2,9) → "abhi"
        val r = doc.deleteRange(2, 9)
        assertEquals("abhi", r.doc.plainText)
    }

    // ================================================================
    // 四、退格（行首合并 / 段内删除）
    // ================================================================

    /**
     * 行首退格：把第二段并入第一段。
     * 旧实现根本无法表达这个意图（diff 只能看出少了一个字符）。
     */
    @Test
    fun `退格：行首并入上一段`() {
        val doc = RichDoc.ofText("甲\n乙")
        val r = doc.mergeWithPrevious(1)
        assertEquals("甲乙", r!!.doc.plainText)
        assertEquals(1, r.doc.lines.size)
        assertEquals(1, r.caret)
    }

    /** 首段行首退格：无可并，返回 null（调用方据此忽略）。 */
    @Test
    fun `退格：首段行首无可并`() {
        val doc = RichDoc.ofText("甲")
        assertNull(doc.mergeWithPrevious(0))
    }

    /** 段内退格：删掉前一个字符。 */
    @Test
    fun `退格：段内删除前一个字符`() {
        val doc = RichDoc.ofText("甲乙")
        // 甲乙：甲=0, 乙=1。删 [1,2) → 「甲」
        val r = doc.deleteRange(1, 2)
        assertEquals("甲", r.doc.plainText)
        assertEquals(1, r.caret)
    }

    /** 行首退格不继承上一段的段类型（按退格就是要退出结构）。 */
    @Test
    fun `退格：合并后沿用上一段的段类型`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", kind = RichLineKind.BULLET),
                RichLine("乙"),
            ),
        )
        val r = doc.mergeWithPrevious(1)
        assertEquals(RichLineKind.BULLET, r!!.doc.lines[0].kind)
        assertEquals("甲乙", r.doc.lines[0].text)
    }

    // ================================================================
    // 五、粘贴（含多行）
    // ================================================================

    /** 粘贴多行：拆成多段（首行接前文、末行接后文、中间各自成段）。 */
    @Test
    fun `粘贴：多行文本拆成多段`() {
        val doc = RichDoc.ofText("甲")
        val r = doc.insertText(0, 1, "乙\n丙\n丁")
        // 甲+乙 = 首段；丙 = 中段；丁 = 末段 → 共 3 段
        assertEquals("甲乙\n丙\n丁", r.doc.plainText)
        assertEquals(3, r.doc.lines.size)
    }

    /** 在列表里粘贴多行：新段保持列表类型（不会突然变成普通段落）。 */
    @Test
    fun `粘贴：列表内保持列表类型`() {
        val doc = RichDoc(listOf(RichLine("甲", kind = RichLineKind.BULLET)))
        val r = doc.insertText(0, 1, "\n乙\n丙")
        assertEquals(3, r.doc.lines.size)
        assertTrue(r.doc.lines.all { it.kind == RichLineKind.BULLET })
    }

    /** 粘贴到段落中间：首行接前文、末行接后文。 */
    @Test
    fun `粘贴：段落中间拆分正确`() {
        val doc = RichDoc.ofText("AB")
        val r = doc.insertText(0, 1, "X\nY")
        // A 之后插入「X\nY」：首段 AX、末段 YB
        assertEquals("AX\nYB", r.doc.plainText)
        assertEquals(2, r.doc.lines.size)
    }

    // ================================================================
    // 六、回车（换行 / 列表续行 / 退出列表）
    // ================================================================

    /** 普通段落回车：拆成两段。 */
    @Test
    fun `回车：段落拆成两段`() {
        val doc = RichDoc.ofText("甲乙")
        val r = doc.splitLine(0, 1)
        assertEquals(2, r.doc.lines.size)
        assertEquals("甲", r.doc.lines[0].text)
        assertEquals("乙", r.doc.lines[1].text)
        // 新光标 = 第二段行首 = 全局下标 2（甲(0) \n(1) → 第二段起始 2）
        assertEquals(2, r.caret)
    }

    /** 列表里回车：新段继承列表类型。 */
    @Test
    fun `回车：列表续行继承类型`() {
        val doc = RichDoc(listOf(RichLine("甲", kind = RichLineKind.BULLET)))
        val r = doc.splitLine(0, 1)
        assertEquals(RichLineKind.BULLET, r.doc.lines[1].kind)
    }

    /** 有序列表回车：新段同样是有序（编号由渲染层算）。 */
    @Test
    fun `回车：有序列表续行`() {
        val doc = RichDoc(listOf(RichLine("甲", kind = RichLineKind.ORDERED)))
        val r = doc.splitLine(0, 1)
        assertEquals(RichLineKind.ORDERED, r.doc.lines[1].kind)
    }

    /** 复选框回车：新段是未勾选的复选框。 */
    @Test
    fun `回车：复选框续行且新项未勾选`() {
        val doc = RichDoc(listOf(RichLine("甲", kind = RichLineKind.CHECK, checked = true)))
        val r = doc.splitLine(0, 1)
        assertEquals(RichLineKind.CHECK, r.doc.lines[1].kind)
        assertFalse(r.doc.lines[1].checked)
    }

    /** 空列表项回车：退出列表（变回普通段落），而不是再续一个空项。 */
    @Test
    fun `回车：空列表项退出列表`() {
        val doc = RichDoc(listOf(RichLine("", kind = RichLineKind.BULLET)))
        val r = doc.splitLine(0, 0)
        assertEquals(RichLineKind.TEXT, r.doc.lines[0].kind)
        assertEquals(1, r.doc.lines.size)
    }

    /** 行尾回车：新段为空。 */
    @Test
    fun `回车：行尾生成空段`() {
        val doc = RichDoc.ofText("甲")
        val r = doc.splitLine(0, 1)
        assertEquals(2, r.doc.lines.size)
        assertEquals("", r.doc.lines[1].text)
    }

    /** 行首回车：原段变空，内容全部后移。 */
    @Test
    fun `回车：行首内容全部后移`() {
        val doc = RichDoc.ofText("甲")
        val r = doc.splitLine(0, 0)
        assertEquals("", r.doc.lines[0].text)
        assertEquals("甲", r.doc.lines[1].text)
        // 新光标 = 第二段行首 = 全局 1（空段(0) \n(1) → 第二段起始 1）
        assertEquals(1, r.caret)
    }

    // ================================================================
    // 七、属性输入模式
    // ================================================================

    /** 输入模式下打的字自动带属性，且原文字不受影响。 */
    @Test
    fun `输入模式：新字带属性，旧字不受影响`() {
        val doc = RichDoc.ofText("甲")
        val r = doc.insertText(0, 1, "乙", setOf(RichAttr.BOLD))
        assertFalse(r.doc.lines[0].isBoldAt(0))
        assertTrue(r.doc.lines[0].isBoldAt(1))
    }

    /** 三种属性可**同时**生效（旧实现用单值，会把别的顶掉）。 */
    @Test
    fun `输入模式：三种属性同时生效`() {
        val doc = RichDoc.ofText("")
        val r = doc.insertText(0, 0, "x", setOf(RichAttr.BOLD, RichAttr.UNDERLINE, RichAttr.STRIKE))
        assertTrue(r.doc.lines[0].isBoldAt(0))
        assertTrue(r.doc.lines[0].isUnderlineAt(0))
        assertTrue(r.doc.lines[0].isStrikeAt(0))
    }

    /** 输入模式下打多个字符：整段新增文字都带属性。 */
    @Test
    fun `输入模式：整段新增文字带属性`() {
        val doc = RichDoc.ofText("")
        val r = doc.insertText(0, 0, "abc", setOf(RichAttr.UNDERLINE))
        assertTrue(r.doc.lines[0].isUnderlineAt(0))
        assertTrue(r.doc.lines[0].isUnderlineAt(2))
    }

    /** 输入模式下的文字落库再读回，属性仍在（往返无损）。 */
    @Test
    fun `输入模式：往返后属性保留`() {
        val doc = RichDoc.ofText("")
        val r = doc.insertText(0, 0, "abc", setOf(RichAttr.BOLD))
        val back = RichDoc.decode(r.doc.encode())
        assertTrue(back.lines[0].isBoldAt(0))
        assertEquals("abc", back.lines[0].text)
    }

    // ================================================================
    // 八、属性切换（选区）
    // ================================================================

    /** 选区全未带 → 全部加上。 */
    @Test
    fun `属性：选区全未带则加上`() {
        val doc = RichDoc.ofText("abcd")
        val r = doc.toggleAttr(0, 1, 3, RichAttr.BOLD)
        assertFalse(r.lines[0].isBoldAt(0))
        assertTrue(r.lines[0].isBoldAt(1))
        assertTrue(r.lines[0].isBoldAt(2))
    }

    /** 选区全已带 → 全部去掉。 */
    @Test
    fun `属性：选区全已带则去掉`() {
        val doc = RichDoc.ofText("abc").let { d ->
            d.copy(lines = listOf(d.lines[0].copy(spans = listOf(RichSpan(0, 3, bold = true)))))
        }
        val r = doc.toggleAttr(0, 0, 3, RichAttr.BOLD)
        assertTrue(r.lines[0].spans.isEmpty())
    }

    /**
     * 掺杂 → 不再逐字翻转，而是「设为带上」。
     *
     * 用户 2026-09-21 反馈：选中一段部分加粗的文字点「加重」，
     * 逐字翻转会把已加粗的取消、未加粗的加上，看起来「越点越乱」。
     * 现在统一为「全带则取消，否则全部设为带上」。
     */
    @Test
    fun `属性：掺杂则统一设为带上`() {
        val doc = RichDoc(
            listOf(RichLine("abc", spans = listOf(RichSpan(0, 1, bold = true)))),
        )
        // 对 [0,3) 切换 bold：原本掺杂（0 粗、1/2 不粗）→ 全部设为带上。
        val r = doc.toggleAttr(0, 0, 3, RichAttr.BOLD)
        assertTrue(r.lines[0].isBoldAt(0))
        assertTrue(r.lines[0].isBoldAt(1))
        assertTrue(r.lines[0].isBoldAt(2))
    }

    /**
     * [setAttr]：有选区时的「设置模式」——直接把区间设为指定态，不翻转。
     */
    @Test
    fun `setAttr：设为带上（不翻转）`() {
        val doc = RichDoc(
            listOf(RichLine("abc", spans = listOf(RichSpan(0, 1, bold = true)))),
        )
        val r = doc.setAttr(0, 0, 3, RichAttr.BOLD, on = true)
        assertTrue(r.lines[0].isBoldAt(0))
        assertTrue(r.lines[0].isBoldAt(1))
        assertTrue(r.lines[0].isBoldAt(2))
    }

    /** [setAttr]：设为去掉 —— 已全带时不会因为「翻转」而变成加上。 */
    @Test
    fun `setAttr：设为去掉`() {
        val doc = RichDoc(
            listOf(RichLine("abc", spans = listOf(RichSpan(0, 3, bold = true)))),
        )
        val r = doc.setAttr(0, 0, 3, RichAttr.BOLD, on = false)
        assertFalse(r.lines[0].isBoldAt(0))
        assertFalse(r.lines[0].isBoldAt(1))
        assertFalse(r.lines[0].isBoldAt(2))
    }

    /** [setAttr]：只改目标属性，已有其他属性原样保留。 */
    @Test
    fun `setAttr：不影响其他属性`() {
        val doc = RichDoc(
            listOf(RichLine("ab", spans = listOf(RichSpan(0, 2, bold = true)))),
        )
        val r = doc.setAttr(0, 0, 2, RichAttr.UNDERLINE, on = true)
        assertTrue(r.lines[0].isBoldAt(0))
        assertTrue(r.lines[0].isUnderlineAt(0))
    }

    /** 三种属性互不干扰：加下划线不影响已有的加重。 */
    @Test
    fun `属性：互不干扰`() {
        val doc = RichDoc(
            listOf(RichLine("ab", spans = listOf(RichSpan(0, 2, bold = true)))),
        )
        val r = doc.toggleAttr(0, 0, 2, RichAttr.UNDERLINE)
        assertTrue(r.lines[0].isBoldAt(0))
        assertTrue(r.lines[0].isUnderlineAt(0))
    }

    /** 只切换一个字，相邻字符不受影响。 */
    @Test
    fun `属性：只影响选区内的字符`() {
        val doc = RichDoc.ofText("abc")
        val r = doc.toggleAttr(0, 1, 2, RichAttr.BOLD)
        assertFalse(r.lines[0].isBoldAt(0))
        assertTrue(r.lines[0].isBoldAt(1))
        assertFalse(r.lines[0].isBoldAt(2))
    }

    /** 空选区（只有光标）不改变任何属性。 */
    @Test
    fun `属性：空选区不改变文档`() {
        val doc = RichDoc.ofText("abc")
        assertEquals(doc, doc.toggleAttr(0, 1, 1, RichAttr.BOLD))
    }

    // ================================================================
    // 九、段类型切换
    // ================================================================

    /** 设为无序列表；再点一次取消。 */
    @Test
    fun `段类型：设置与取消`() {
        val doc = RichDoc.ofText("甲")
        val bullet = doc.setLineKind(0, RichLineKind.BULLET)
        assertEquals(RichLineKind.BULLET, bullet.lines[0].kind)
        val back = bullet.setLineKind(0, RichLineKind.BULLET)
        assertEquals(RichLineKind.TEXT, back.lines[0].kind)
    }

    /** 段类型切换不影响文字与行内属性。 */
    @Test
    fun `段类型：不影响文字与属性`() {
        val doc = RichDoc(
            listOf(RichLine("甲", spans = listOf(RichSpan(0, 1, bold = true)))),
        )
        val r = doc.setLineKind(0, RichLineKind.ORDERED)
        assertEquals("甲", r.lines[0].text)
        assertTrue(r.lines[0].isBoldAt(0))
    }

    /** 退出复选框时清掉勾选态。 */
    @Test
    fun `段类型：退出复选框清掉勾选`() {
        val doc = RichDoc(listOf(RichLine("甲", kind = RichLineKind.CHECK, checked = true)))
        val r = doc.setLineKind(0, RichLineKind.CHECK) // 同类型 → 取消
        assertEquals(RichLineKind.TEXT, r.lines[0].kind)
        assertFalse(r.lines[0].checked)
    }

    /** 勾选切换只对复选框段有效。 */
    @Test
    fun `勾选：只对复选框段生效`() {
        val doc = RichDoc(listOf(RichLine("甲", kind = RichLineKind.CHECK)))
        val r = doc.toggleCheck(0)
        assertTrue(r.lines[0].checked)
        // 普通段落调用无效果。
        val plain = RichDoc.ofText("甲")
        assertEquals(plain, plain.toggleCheck(0))
    }

    // ================================================================
    // 十、连击与边界（稳定性）
    // ================================================================

    /** 连续打字 100 次，文字与光标都必须正确。 */
    @Test
    fun `稳定性：连续输入不出错`() {
        var doc = RichDoc.ofText("")
        var caret = 0
        repeat(100) {
            val r = doc.replaceRange(caret, caret, "字")
            doc = r.doc
            caret = r.caret
        }
        assertEquals(100, doc.plainText.length)
        assertEquals(100, caret)
        assertEquals(1, doc.lines.size)
    }

    /** 连续退格 50 次：不能越界、不能抛异常。 */
    @Test
    fun `稳定性：连续退格不越界`() {
        var doc = RichDoc.ofText("字".repeat(50))
        var caret = 50
        repeat(80) {
            val r = if (caret > 0) doc.deleteRange(caret - 1, caret) else EditResult(doc, caret)
            doc = r.doc
            caret = r.caret
        }
        assertEquals("", doc.plainText)
    }

    /** 越界坐标一律夹到合法范围（用户乱点不该崩）。 */
    @Test
    fun `边界：越界坐标被夹住`() {
        val doc = RichDoc.ofText("甲")
        assertEquals("甲X", doc.replaceRange(999, 999, "X").doc.plainText)
        assertEquals("", doc.deleteRange(-5, 999).doc.plainText)
        assertEquals(0, doc.deleteRange(-5, 999).caret)
    }

    /** 空文档上的各种操作都不应崩。 */
    @Test
    fun `边界：空文档操作安全`() {
        val doc = RichDoc(listOf(RichLine("")))
        assertEquals("X", doc.replaceRange(0, 0, "X").doc.plainText)
        assertEquals("", doc.deleteRange(0, 0).doc.plainText)
        // 空文档回车：拆成两个空段（空段落无“退出结构”语义）。
        assertEquals(2, doc.splitLine(0, 0).doc.lines.size)
        assertEquals(doc, doc.toggleAttr(0, 0, 0, RichAttr.BOLD))
    }

    /** 光标换算：全局 ↔ 段内 必须互逆。 */
    @Test
    fun `光标：全局与段内互逆`() {
        val doc = RichDoc.ofText("甲\n乙丙\nde")
        for (g in 0..doc.textLength) {
            val pos = doc.caretOf(g)
            assertEquals(g, doc.globalOf(pos.line, pos.col))
        }
    }

    /** 光标落在换行符上时归到下一段行首。 */
    @Test
    fun `光标：换行处归到下一段行首`() {
        // 甲\n乙：甲=0, \n=1, 乙=2
        val doc = RichDoc.ofText("甲\n乙")
        // 位置 1 = 第一段段末
        assertEquals(CaretPos(0, 1), doc.caretOf(1))
        // 位置 2 = 第二段行首
        assertEquals(CaretPos(1, 0), doc.caretOf(2))
    }

    /** 选区换算：跨段选区返回正确的单段交集。 */
    @Test
    fun `选区：跨段选区取光标所在段的交集`() {
        // 甲甲\n乙乙：甲=0,甲=1,\n=2,乙=3,乙=4
        val doc = RichDoc.ofText("甲甲\n乙乙")
        // 选区 [1,4)：跨两段。光标取选区末端所在段（第 1 段）。
        val (line, from, to) = doc.selectionInLine(1, 4)
        assertEquals(1, line)
        assertEquals(0, from)
        assertEquals(1, to)
    }

    // ================================================================
    // 十一、往返（落库 → 读回）
    // ================================================================

    /** 复杂文档落库往返必须完全无损。 */
    @Test
    fun `往返：复杂文档无损`() {
        val doc = RichDoc(
            listOf(
                RichLine("标题", spans = listOf(RichSpan(0, 2, bold = true))),
                RichLine("列表项", kind = RichLineKind.BULLET),
                RichLine("待办1", kind = RichLineKind.CHECK, checked = true),
                RichLine("待办2", kind = RichLineKind.CHECK, checked = false),
                RichLine("有序", kind = RichLineKind.ORDERED),
                RichLine("多属性", spans = listOf(RichSpan(0, 3, bold = true, underline = true, strike = true))),
            ),
        )
        val back = RichDoc.decode(doc.encode())
        assertEquals(doc, back)
    }

    /** 落库里**不得**出现 Markdown 标记符（非 md 的核心要求）。 */
    @Test
    fun `往返：落库不含 Markdown 标记符`() {
        val doc = RichDoc(
            listOf(
                RichLine("加粗", spans = listOf(RichSpan(0, 2, bold = true))),
                RichLine("列表", kind = RichLineKind.BULLET),
                RichLine("待办", kind = RichLineKind.CHECK, checked = true),
            ),
        )
        val encoded = doc.encode()
        // 文字本身不含 * ~ + - [ ] 等标记；它们只可能出现在 JSON 结构里。
        assertFalse(encoded.contains("**"))
        assertFalse(encoded.contains("~~"))
        assertFalse(encoded.contains("++"))
        assertFalse(encoded.contains("[x]"))
        assertFalse(encoded.contains("[ ]"))
    }

    /** 纯文本兜底：解析失败的旧数据按纯文本读入，不丢文字。 */
    @Test
    fun `往返：非法格式按纯文本兜底`() {
        val doc = RichDoc.decode("这不是 JSON\n第二行")
        assertEquals("这不是 JSON\n第二行", doc.plainText)
        assertEquals(2, doc.lines.size)
    }
    // ================================================================
    // 十二、新增属性（斜体 / 荧光笔高亮，2026-09-20）
    // ================================================================

    /** 斜体可单独开关，不影响其他属性。 */
    @Test
    fun `新增属性：斜体开关`() {
        val doc = RichDoc.ofText("abc")
        val on = doc.toggleAttr(0, 0, 3, RichAttr.ITALIC)
        assertTrue(on.lines[0].isItalicAt(0))
        assertFalse(on.lines[0].isBoldAt(0))
        val off = on.toggleAttr(0, 0, 3, RichAttr.ITALIC)
        assertFalse(off.lines[0].isItalicAt(0))
    }

    /** 荧光笔高亮可单独开关。 */
    @Test
    fun `新增属性：荧光笔高亮开关`() {
        val doc = RichDoc.ofText("abc")
        val on = doc.toggleAttr(0, 0, 3, RichAttr.HIGHLIGHT)
        assertTrue(on.lines[0].isHighlightAt(1))
        val off = on.toggleAttr(0, 0, 3, RichAttr.HIGHLIGHT)
        assertFalse(off.lines[0].isHighlightAt(1))
    }

    /** 五种属性可同时生效且互不干扰。 */
    @Test
    fun `新增属性：五种属性同时生效`() {
        var doc = RichDoc.ofText("x")
        listOf(
            RichAttr.BOLD, RichAttr.UNDERLINE, RichAttr.STRIKE,
            RichAttr.ITALIC, RichAttr.HIGHLIGHT,
        ).forEach { attr -> doc = doc.toggleAttr(0, 0, 1, attr) }
        val line = doc.lines[0]
        assertTrue(line.isBoldAt(0))
        assertTrue(line.isUnderlineAt(0))
        assertTrue(line.isStrikeAt(0))
        assertTrue(line.isItalicAt(0))
        assertTrue(line.isHighlightAt(0))
    }

    /** 新属性落库往返无损。 */
    @Test
    fun `新增属性：往返无损`() {
        var doc = RichDoc.ofText("ab")
        doc = doc.toggleAttr(0, 0, 2, RichAttr.ITALIC)
        doc = doc.toggleAttr(0, 0, 2, RichAttr.HIGHLIGHT)
        val back = RichDoc.decode(doc.encode())
        assertTrue(back.lines[0].isItalicAt(0))
        assertTrue(back.lines[0].isHighlightAt(1))
    }

    /** 输入模式下新属性也适用（打字自动带属性）。 */
    @Test
    fun `新增属性：输入模式下新字带属性`() {
        val doc = RichDoc.ofText("")
        val r = doc.insertText(0, 0, "z", setOf(RichAttr.ITALIC, RichAttr.HIGHLIGHT))
        assertTrue(r.doc.lines[0].isItalicAt(0))
        assertTrue(r.doc.lines[0].isHighlightAt(0))
    }

    /** 旧数据（无新字段的 JSON）读回不应崩，新属性默认为 false。 */
    @Test
    fun `新增属性：旧 JSON 缺字段时兼容`() {
        val oldJson = """{"v":1,"lines":[{"t":"旧数据","k":"text","s":[{"a":0,"b":1,"bold":true}]}]}"""
        val doc = RichDoc.decode(oldJson)
        assertEquals("旧数据", doc.plainText)
        assertTrue(doc.lines[0].isBoldAt(0))
        assertFalse(doc.lines[0].isItalicAt(0))
        assertFalse(doc.lines[0].isHighlightAt(0))
    }
}
