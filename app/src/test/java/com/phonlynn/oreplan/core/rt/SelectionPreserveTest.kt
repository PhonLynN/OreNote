package com.phonlynn.oreplan.core.rt

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **选区/光标在文本变化时的保持规则**（2026-09-28，用户报「选择范围易跳变」）。
 *
 * 口径（改完 `onFieldChange` 之后的行为，钉在这里防止回退）：
 *
 * 1. 文本没变（只是移动光标/拖动选区）→ 选区**原样接受**，不做任何换算；
 * 2. 文本变了 → 以**输入框给的选区**为准，只按「重建文本与用户文本的第一处差异」
 *    做等量平移：差异点之前不动、之后整体平移；
 * 3. **范围选区不会被折成光标**：只要用户没把它改掉，插入/删除发生在选区之外时，
 *    选区长度与方向都保持不变。
 *
 * 旧实现是「用内容锚点重新推算 caret」：既会跳，又会把范围折成光标 ——
 * 这两条正是用户实测到的现象。
 */
class SelectionPreserveTest {

    private fun stateOf(text: String): RichEditState =
        RichEditState.ofDoc(RichDoc.ofText(text))

    private fun value(text: String, start: Int, end: Int = start) =
        TextFieldValue(text = text, selection = TextRange(start, end))

    @Test
    fun `文本没变时选区原样保留`() {
        val st = stateOf("abcdef")
        val t = st.field.text
        st.onFieldChange(value(t, 1, 4))
        assertEquals(TextRange(1, 4), st.field.selection)
    }

    @Test
    fun `行内插入后光标紧跟插入内容`() {
        val st = stateOf("abcdef")
        val t = st.field.text
        // 在下标 2 处插入 "XY"，输入框把光标报在 4。
        st.onFieldChange(value(t.substring(0, 2) + "XY" + t.substring(2), 4))
        assertEquals(4, st.field.selection.start)
        assertEquals(4, st.field.selection.end)
        assertEquals("abXYcdef", st.field.text)
    }

    @Test
    fun `改动落在选区之外时内容不被改坏、选区保持不变`() {
        val st = stateOf("abcdef")
        val t = st.field.text
        st.onFieldChange(value(t, 1, 4))
        assertEquals(TextRange(1, 4), st.field.selection)

        // 文末插入一个字符，而**选区仍在**（输入法组词 / 自动更正这类
        // "改动不在选区上" 的情形，框架会把选区照原样报回来）。
        // 旧实现会把它当成「整段选区被替换成 Z」：内容被改坏、选区被折成光标。
        val after = t + "Z"
        st.onFieldChange(value(after, 1, 4))
        assertEquals("abcdefZ", st.field.text)
        assertEquals(TextRange(1, 4), st.field.selection)
    }

    @Test
    fun `列表行回车后光标落在新前缀之后`() {
        val doc = RichDoc.ofText("买牛奶").setLineKind(0, RichLineKind.BULLET)
        val st = RichEditState.ofDoc(doc)
        val t = st.field.text
        val at = t.length
        // 行尾回车：用户文本是「原文本 + \n」，光标在末尾。
        st.onFieldChange(value(t + "\n", at + 1))

        val newText = st.field.text
        val prefix = RichTextMark.prefixLengthOf(RichLineKind.BULLET)
        // 新行补了前缀，且光标停在前缀之后（不是前缀之前）。
        assertEquals(t.length + 1 + prefix, newText.length)
        assertEquals(newText.length, st.field.selection.start)
    }

    @Test
    fun `空列表项回车退出列表时光标落在行首`() {
        val doc = RichDoc.ofText("").setLineKind(0, RichLineKind.BULLET)
        val st = RichEditState.ofDoc(doc)
        val t = st.field.text
        val prefix = RichTextMark.prefixLengthOf(RichLineKind.BULLET)
        assertEquals(prefix, t.length)
        // 行尾回车（空列表项 → 退出列表，不换行）。
        st.onFieldChange(value(t + "\n", t.length + 1))
        assertEquals(0, st.field.selection.start)
        assertEquals(0, st.field.selection.end)
    }

    @Test
    fun `勾选态变化不改动选区`() {
        val doc = RichDoc.ofText("买牛奶").setLineKind(0, RichLineKind.CHECK)
        val st = RichEditState.ofDoc(doc)
        val t = st.field.text
        val bodyStart = RichTextMark.prefixLengthOf(RichLineKind.CHECK)
        st.onFieldChange(value(t, bodyStart, bodyStart + 3))
        val before = st.field.selection
        // 点复选框：文档变了（勾选 + 删除线），但**没有文本变化** —— 选区必须原样。
        st.toggleCheckAt(0)
        assertEquals(before, st.field.selection)
    }
}
