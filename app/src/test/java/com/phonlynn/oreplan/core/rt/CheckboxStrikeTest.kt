package com.phonlynn.oreplan.core.rt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复选框勾选态 → 删除线的**校正**规则（用户 2026-09-28 明确要求）。
 *
 * 三条口径钉死在这里：
 *
 * 1. 勾选 → 整段有删除线；取消勾选 → 删除线同步消除；
 * 2. **按状态赋值，不是取反**：整段已经符合状态时一个字都不动；
 * 3. **不与复选框绑定**：只在校正那一次生效，之后用户手动改删除线不会被拉回来。
 */
class CheckboxStrikeTest {

    private fun checkDoc(text: String): RichDoc =
        RichDoc.ofText(text).setLineKind(0, RichLineKind.CHECK)

    private fun struckAll(doc: RichDoc, line: Int = 0): Boolean {
        val t = doc.lines[line].text
        return t.isNotEmpty() && (0 until t.length).all { doc.lines[line].isStrikeAt(it) }
    }

    @Test
    fun `勾选 → 整段加删除线`() {
        val doc = checkDoc("买牛奶").toggleCheck(0)
        assertTrue(doc.lines[0].checked)
        assertTrue(struckAll(doc))
    }

    @Test
    fun `取消勾选 → 删除线同步消除`() {
        val doc = checkDoc("买牛奶").toggleCheck(0).toggleCheck(0)
        assertFalse(doc.lines[0].checked)
        assertFalse(struckAll(doc))
    }

    @Test
    fun `整段已经有删除线时勾选：不重写属性（按状态赋值，不是取反）`() {
        val doc = checkDoc("买牛奶").setAttr(0, 0, 3, RichAttr.STRIKE, true)
        val after = doc.toggleCheck(0)
        assertTrue(struckAll(after))
        // 属性区间没有被重建过：仍然是一段
        assertEquals(1, after.lines[0].spans.size)
    }

    @Test
    fun `局部删除线在勾选后补全为整段`() {
        val doc = checkDoc("买牛奶").setAttr(0, 0, 1, RichAttr.STRIKE, true)
        assertTrue(struckAll(doc.toggleCheck(0)))
    }

    @Test
    fun `不绑定：勾选后手动去掉删除线不会被拉回，再切勾选态才重新校正`() {
        var doc = checkDoc("买牛奶").toggleCheck(0)
        doc = doc.setAttr(0, 0, 3, RichAttr.STRIKE, false)
        assertFalse(struckAll(doc))
        // 取消再勾选 → 重新按状态校正
        doc = doc.toggleCheck(0).toggleCheck(0)
        assertTrue(struckAll(doc))
    }

    @Test
    fun `空文本不炸`() {
        val doc = checkDoc("").toggleCheck(0)
        assertTrue(doc.lines[0].checked)
        assertFalse(struckAll(doc))
    }

    @Test
    fun `非复选框段落 toggleCheck 什么都不做`() {
        val doc = RichDoc.ofText("普通段落")
        val after = doc.toggleCheck(0)
        assertFalse(after.lines[0].checked)
        assertEquals(doc.lines[0].spans, after.lines[0].spans)
    }
}
