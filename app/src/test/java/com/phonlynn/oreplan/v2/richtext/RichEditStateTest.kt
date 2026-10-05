package com.phonlynn.oreplan.v2.richtext

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.phonlynn.oreplan.core.rt.RichAttr
import com.phonlynn.oreplan.core.rt.RichEditState
import com.phonlynn.oreplan.core.rt.RichDoc
import com.phonlynn.oreplan.core.rt.RichLayout
import com.phonlynn.oreplan.core.rt.RichLine
import com.phonlynn.oreplan.core.rt.RichLineKind
import com.phonlynn.oreplan.core.rt.RichTextMark
import com.phonlynn.oreplan.core.rt.orderedNumberAt
import com.phonlynn.oreplan.core.rt.selectionInLine
import com.phonlynn.oreplan.core.rt.toggleCheck
import com.phonlynn.oreplan.core.rt.setLineKind
import com.phonlynn.oreplan.core.rt.toggleAttr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **编辑器状态机回归测试**（2026-09-20 重写配套）。
 *
 * 这里验证 [RichEditState.onFieldChange] 把「输入框事件」翻译成显式操作的正确性。
 *
 * 为什么要单独测这一层：旧实现的全部故障都发生在这一层——它用文本 diff 反推意图。
 * 现在改为显式操作，必须确保「事件 → 操作」的翻译在各类编辑序列下都对。
 * 测试直接用 `TextFieldValue` 驱动状态机，等价于真实输入框回调。
 */
class RichEditStateTest {

    /** 造一个状态。 */
    private fun stateFor(doc: RichDoc): RichEditState =
        RichEditState.ofDoc(doc).also { it.baseStyle = TextStyle.Default }

    /** 由纯文本造一个状态。 */
    private fun stateOf(text: String): RichEditState = stateFor(RichDoc.ofText(text))

    // ------------------------------------------------------------------
    // 事件模拟
    //
    // 真实输入框给的是**显示坐标**（带符号前缀与 ZWSP）。这里用状态自己的
    // 换算函数把内容下标翻译过去，正是输入框会看到的那套坐标。
    // ------------------------------------------------------------------

    /** 模拟：把光标摆到内容坐标 [at] 处（不改变文本）。 */
    private fun RichEditState.moveCaret(at: Int) {
        onFieldChange(field.copy(selection = TextRange(at)))
    }

    /** 模拟：在内容坐标 [at] 处插入文字。 */
    private fun RichEditState.insert(at: Int, text: String) {
        moveCaret(at)
        val d = at
        val next = field.text.substring(0, d) + text + field.text.substring(d)
        onFieldChange(TextFieldValue(next, TextRange(d + text.length)))
    }

    /** 模拟：退格（删内容坐标 [at] 前一个字符）。 */
    private fun RichEditState.backspace(at: Int) {
        if (at <= 0) return
        moveCaret(at)
        val from = at - 1
        val to = at
        val next = field.text.substring(0, from) + field.text.substring(to)
        onFieldChange(TextFieldValue(next, TextRange(from)))
    }

    /** 模拟：在内容选区 `[from, to)` 上替换文字（输入法提交 / 框选输入）。 */
    private fun RichEditState.replaceSel(from: Int, to: Int, text: String) {
        val a = from
        val b = to
        onFieldChange(field.copy(selection = TextRange(a, b)))
        val next = field.text.substring(0, a) + text + field.text.substring(b)
        onFieldChange(TextFieldValue(next, TextRange(a + text.length)))
    }

    /** 模拟：选中内容区间后按删除。 */
    private fun RichEditState.deleteSel(from: Int, to: Int) {
        val a = from
        val b = to
        onFieldChange(field.copy(selection = TextRange(a, b)))
        val next = field.text.substring(0, a) + field.text.substring(b)
        onFieldChange(TextFieldValue(next, TextRange(a)))
    }

    // ================================================================
    // 基本输入
    // ================================================================

    @Test
    fun `输入：末尾追加文字`() {
        val s = stateOf("甲")
        s.insert(1, "乙")
        assertEquals("甲乙", s.doc.plainText)
        assertEquals(2, (s.field.selection.start))
    }

    @Test
    fun `输入：中间插入文字`() {
        val s = stateOf("甲乙")
        s.insert(1, "X")
        assertEquals("甲X乙", s.doc.plainText)
    }

    @Test
    fun `输入：开头插入文字`() {
        val s = stateOf("乙")
        s.insert(0, "甲")
        assertEquals("甲乙", s.doc.plainText)
    }

    @Test
    fun `输入：连续打字顺序正确`() {
        val s = stateOf("")
        s.insert(0, "a")
        s.insert(1, "b")
        s.insert(2, "c")
        assertEquals("abc", s.doc.plainText)
    }

    // ================================================================
    // 退格
    // ================================================================

    @Test
    fun `退格：删除前一个字符`() {
        val s = stateOf("甲乙")
        s.backspace(2)
        assertEquals("甲", s.doc.plainText)
    }

    @Test
    fun `退格：行首合并两段`() {
        val s = stateOf("甲\n乙")
        // 光标在第二段行首（全局 2）
        s.backspace(2)
        assertEquals("甲乙", s.doc.plainText)
        assertEquals(1, s.doc.lines.size)
    }

    @Test
    fun `退格：连续退格不出错`() {
        val s = stateOf("abcd")
        s.backspace(4)
        s.backspace(3)
        s.backspace(2)
        s.backspace(1)
        assertEquals("", s.doc.plainText)
    }

    // ================================================================
    // 输入法（旧实现丢字的地方）
    // ================================================================

    /**
     * 组合 → 提交：在「甲」后输入拼音组合，再提交为「你」。
     *
     * 事件序列模拟真实输入框：
     *  1. 组合中文本「甲ni」，选区在末尾；
     *  2. 提交：文本变「甲你」，选区在末尾。
     * 旧实现把第 2 步判成删除（长度 3→2），删掉的是 i，结果「甲i」。
     */
    @Test
    fun `输入法：组合提交为候选字不丢字`() {
        val s = stateOf("甲")
        // 组合中：ni 进入（作为一次插入）
        s.insert(1, "ni")
        assertEquals("甲ni", s.doc.plainText)
        // 提交：把组合区间 [1,3) 替换为「你」
        s.replaceSel(1, 3, "你")
        assertEquals("甲你", s.doc.plainText)
    }

    /**
     * 输入法取消组合：组合内容应被删掉，原文不受影响。
     */
    @Test
    fun `输入法：取消组合回到原文`() {
        val s = stateOf("甲")
        s.insert(1, "ni")
        assertEquals("甲ni", s.doc.plainText)
        s.deleteSel(1, 3)
        assertEquals("甲", s.doc.plainText)
    }

    /** 长拼音提交（多字母 → 一个汉字）。 */
    @Test
    fun `输入法：长拼音提交不丢字`() {
        val s = stateOf("甲")
        s.insert(1, "zhongguo")
        assertEquals("甲zhongguo", s.doc.plainText)
        s.replaceSel(1, 9, "中国")
        assertEquals("甲中国", s.doc.plainText)
    }

    /** 连续两次组合提交。 */
    @Test
    fun `输入法：连续提交两次`() {
        val s = stateOf("")
        s.insert(0, "ni")
        s.replaceSel(0, 2, "你")
        assertEquals("你", s.doc.plainText)
        s.insert(1, "hao")
        s.replaceSel(1, 4, "好")
        assertEquals("你好", s.doc.plainText)
    }

    // ================================================================
    // 选区（旧实现删不干净的地方）
    // ================================================================

    /** 全选删除多行。 */
    @Test
    fun `选区：全选删除多行文档`() {
        val s = stateOf("甲\n乙")
        s.deleteSel(0, 3)
        assertEquals("", s.doc.plainText)
        assertEquals(1, s.doc.lines.size)
    }

    /** 全选后输入替换。 */
    @Test
    fun `选区：全选后输入整体替换`() {
        val s = stateOf("旧内容")
        s.replaceSel(0, 3, "新")
        assertEquals("新", s.doc.plainText)
    }

    /** 框选删除中间一段。 */
    @Test
    fun `选区：框选删除中间一段`() {
        val s = stateOf("甲乙丙")
        s.deleteSel(1, 2)
        assertEquals("甲丙", s.doc.plainText)
    }

    /** 跨段框选删除。 */
    @Test
    fun `选区：跨段框选删除`() {
        val s = stateOf("甲\n乙\n丙")
        // 删 [1,4)：换行 + 乙 + 换行
        s.deleteSel(1, 4)
        assertEquals("甲丙", s.doc.plainText)
        assertEquals(1, s.doc.lines.size)
    }

    // ================================================================
    // 属性（输入模式）
    // ================================================================

    @Test
    fun `属性：输入模式下新字带属性`() {
        val s = stateOf("甲")
        s.pendingAttrs = setOf(RichAttr.BOLD)
        s.insert(1, "乙")
        assertFalse(s.doc.lines[0].isBoldAt(0))
        assertTrue(s.doc.lines[0].isBoldAt(1))
    }

    @Test
    fun `属性：三种属性可同时生效`() {
        val s = stateOf("")
        s.pendingAttrs = setOf(RichAttr.BOLD, RichAttr.UNDERLINE, RichAttr.STRIKE)
        s.insert(0, "x")
        assertTrue(s.doc.lines[0].isBoldAt(0))
        assertTrue(s.doc.lines[0].isUnderlineAt(0))
        assertTrue(s.doc.lines[0].isStrikeAt(0))
    }

    /** 输入模式关闭后，新字不再带属性。 */
    @Test
    fun `属性：关闭输入模式后新字无属性`() {
        val s = stateOf("")
        s.pendingAttrs = setOf(RichAttr.BOLD)
        s.insert(0, "a")
        s.pendingAttrs = emptySet()
        s.insert(1, "b")
        assertTrue(s.doc.lines[0].isBoldAt(0))
        assertFalse(s.doc.lines[0].isBoldAt(1))
    }

    // ================================================================
    // 撤销 / 重做
    // ================================================================

    @Test
    fun `撤销：回到上一版`() {
        val s = stateOf("")
        s.insert(0, "a")
        s.insert(1, "b")
        assertEquals("ab", s.doc.plainText)
        s.undo()
        assertEquals("a", s.doc.plainText)
        s.undo()
        assertEquals("", s.doc.plainText)
    }

    @Test
    fun `重做：恢复被撤销的内容`() {
        val s = stateOf("")
        s.insert(0, "a")
        s.insert(1, "b")
        s.undo()
        assertEquals("a", s.doc.plainText)
        s.redo()
        assertEquals("ab", s.doc.plainText)
    }

    @Test
    fun `撤销：无历史时是空操作`() {
        val s = stateOf("甲")
        s.undo()
        assertEquals("甲", s.doc.plainText)
    }

    // ================================================================
    // 回写宿主（落库字符串）
    // ================================================================

    /** 每次编辑都应把新格式回写宿主。 */
    @Test
    fun `回写：编辑后宿主拿到新内容`() {
        val s = stateOf("")
        var emitted: String? = null
        s.bindEmitter { emitted = it }
        s.insert(0, "甲")
        assertEquals("甲", RichDoc.decode(emitted).plainText)
    }

    /** 宿主回灌自己的值（回声）不应重置文档。 */
    @Test
    fun `回写：忽略自己的回声`() {
        val s = stateOf("")
        s.insert(0, "甲")
        val snapshot = s.doc
        s.syncFromHost(s.encoded())
        assertEquals(snapshot, s.doc)
    }

    /** 宿主主动改值（切卡片）应生效。 */
    @Test
    fun `回写：宿主主动改值会生效`() {
        val s = stateOf("甲")
        s.syncFromHost(RichDoc.ofText("乙").encode())
        assertEquals("乙", s.doc.plainText)
    }

    // ================================================================
    // 往返（编辑 → 落库 → 读回 → 继续编辑）
    // ================================================================

    /** 带格式编辑后落库，读回再编辑，格式仍在。 */
    @Test
    fun `往返：编辑落库读回后格式保留`() {
        val s = stateOf("")
        s.pendingAttrs = setOf(RichAttr.BOLD)
        s.insert(0, "ab")
        val encoded = s.encoded()
        val back = RichDoc.decode(encoded)
        assertTrue(back.lines[0].isBoldAt(0))
        assertTrue(back.lines[0].isBoldAt(1))
        assertEquals("ab", back.plainText)
    }

    /** 列表行编辑后落库，行类型保留。 */
    @Test
    fun `往返：列表行编辑后类型保留`() {
        val s = stateOf("")
        // 设为无序列表
        s.applyDocKeepSelection(s.doc.setLineKind(0, RichLineKind.BULLET))
        // 光标已经由 applyDocKeepSelection 放在正文位置，直接在该处输入
        s.insert(s.field.selection.min, "项")
        val back = RichDoc.decode(s.encoded())
        assertEquals(RichLineKind.BULLET, back.lines[0].kind)
        assertEquals("项", back.lines[0].text)
    }

    // ================================================================
    // 稳定性
    // ================================================================

    /** 大量输入不出错。 */
    @Test
    fun `稳定性：连续输入 200 次`() {
        val s = stateOf("")
        repeat(200) { i -> s.insert(i, "字") }
        assertEquals(200, s.doc.plainText.length)
        assertEquals(200, (s.field.selection.start))
    }

    /** 输入后全部退格，回到空。 */
    @Test
    fun `稳定性：输入后再全部退格`() {
        val s = stateOf("")
        repeat(50) { i -> s.insert(i, "x") }
        for (i in 50 downTo 1) s.backspace(i)
        assertEquals("", s.doc.plainText)
    }

    /** 无选区时移动光标不应改动文档。 */
    @Test
    fun `稳定性：只移动光标不改文档`() {
        val s = stateOf("甲乙")
        val before = s.doc
        s.moveCaret(0)
        s.moveCaret(2)
        s.moveCaret(1)
        assertEquals(before, s.doc)
    }
    // ================================================================
    // 工具栏按钮与宿主回灌（2026-09-20 修的「列表按钮没反应」）
    // ================================================================

    /**
     * 点「无序列表」后，宿主回灌一个**过期的值**不得把段类型冲掉。
     *
     * 这正是「列表/复选框按钮点了没反应」的根因：
     * 按钮改的是段类型（纯文字不变、JSON 变了），修改先 emit 给宿主；
     * 宿主的 ViewModel 更新有一帧延迟，那一帧里 `LaunchedEffect(value)`
     * 会拿着旧值调用 syncFromHost，把刚设好的类型整个回滚。
     */
    @Test
    fun `列表按钮：宿主回灌过期值不冲掉段类型`() {
        val s = stateOf("测试")
        val staleHost = s.encoded()   // 宿主当前持有的（旧）值
        var host = staleHost
        s.bindEmitter { host = it }

        // 点「无序列表」
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        assertEquals(RichLineKind.BULLET, s.doc.lines[0].kind)

        // 宿主延迟，先回灌旧值
        s.syncFromHost(staleHost)
        assertEquals(RichLineKind.BULLET, s.doc.lines[0].kind)
    }

    /** 再点一次「无序列表」应取消（回到普通段落）。 */
    @Test
    fun `列表按钮：再点一次取消`() {
        val s = stateOf("测试")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        assertEquals(RichLineKind.BULLET, s.doc.lines[0].kind)
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        assertEquals(RichLineKind.TEXT, s.doc.lines[0].kind)
    }

    /** 复选框按钮同理：设置后不被回灌冲掉。 */
    @Test
    fun `复选框按钮：宿主回灌过期值不冲掉段类型`() {
        val s = stateOf("待办")
        val staleHost = s.encoded()
        var host = staleHost
        s.bindEmitter { host = it }
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.CHECK))
        s.syncFromHost(staleHost)
        assertEquals(RichLineKind.CHECK, s.doc.lines[0].kind)
    }

    /** 宿主真正换内容（切卡片）时仍要生效。 */
    @Test
    fun `宿主换内容仍然生效`() {
        val s = stateOf("甲")
        s.syncFromHost(RichDoc.ofText("完全不同的新内容").encode())
        assertEquals("完全不同的新内容", s.doc.plainText)
    }

    /** 新增属性（斜体/高亮）经工具栏切换后也能正常回灌。 */
    @Test
    fun `新增属性：工具栏切换后回灌不丢`() {
        val s = stateOf("abc")
        val staleHost = s.encoded()
        s.bindEmitter { }
        val sel = s.field.selection
        val (li, from, to) = s.doc.selectionInLine(
            (sel.min), (sel.max),
        )
        // 模拟：全选后点斜体
        s.applyDocKeepSelection(s.doc.toggleAttr(li, 0, 3, RichAttr.ITALIC))
        assertTrue(s.doc.lines[0].isItalicAt(0))
        s.syncFromHost(staleHost)
        assertTrue(s.doc.lines[0].isItalicAt(0))
    }
    // ================================================================
    // 列表：点击按钮立刻可见 + 回车续行（2026-09-20 用户指出的缺失）
    // ================================================================

    /** 模拟按回车（输入框给出新文本 + 新光标）。坐标是显示坐标。 */
    private fun RichEditState.enter(at: Int) {
        moveCaret(at)
        val d = at
        val next = field.text.substring(0, d) + "\n" + field.text.substring(d)
        onFieldChange(TextFieldValue(next, TextRange(d + 1)))
    }

    /**
     * 在**正文末尾**按回车（跳过前缀）。
     *
     * 列表行的前缀是列表标记本身，用户是在正文里打字的，
     * 所以续行测试必须从正文末尾回车，而不是从行的最开头。
     */
    private fun RichEditState.enterAtContentEnd() {
        val text = field.text
        val lineStart = text.lastIndexOf('\n') + 1
        val hasPrefix = lineStart + 1 < text.length &&
            RichTextMark.isMark(text[lineStart]) &&
            text[lineStart + 1] == RichTextMark.IDEOGRAPHIC_SPACE
        val contentStart = if (hasPrefix) lineStart + RichTextMark.prefixLengthOf(RichLineKind.BULLET) else lineStart
        enter(text.length.coerceAtLeast(contentStart))
    }

    // ---------------------------------------------------------------- 列表结构
    //
    // 2026-09-22 重写：列表符号已改为**排版引擎绘制的 Bullet 注解**，
    // 不再往文本流塞占位字符。因此断言从「前缀字符串」改为
    // 「段落缩进 + 渲染产物」，后者才是真正决定屏幕上看起来对不对的东西。

    /** 点「无序列表」后：该段变成列表段，且需要绘制符号。 */
    @Test
    fun `列表：点击后段落变成列表且需要画符号`() {
        val s = stateOf("内容")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        assertEquals(RichLineKind.BULLET, s.doc.lines[0].kind)
        // 符号由绘制层画（缩进改由布局内边距提供，不再是排版属性）。
        assertEquals(true, RichLayout.hasSymbol(RichLineKind.BULLET))
        // 普通段落不需要符号 —— 没有列表的地方不占任何空间。
        assertEquals(false, RichLayout.hasSymbol(RichLineKind.TEXT))
    }

    /** 点「复选框」后：段落变成复选框段。 */
    @Test
    fun `列表：复选框点击后段落变成复选框`() {
        val s = stateOf("待办")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.CHECK))
        assertEquals(RichLineKind.CHECK, s.doc.lines[0].kind)
        assertFalse(s.doc.lines[0].checked)
    }

    /** 再点一次取消后回到普通段落（不再需要符号）。 */
    @Test
    fun `列表：取消后回到普通段落`() {
        val s = stateOf("内容")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        assertEquals(RichLineKind.TEXT, s.doc.lines[0].kind)
        assertEquals(false, RichLayout.hasSymbol(RichLineKind.TEXT))
    }

    /** 有序列表的编号只画不写：文本流里绝不能出现编号字符。 */
    @Test
    fun `列表：有序编号不写进文本流`() {
        val s = stateOf("甲")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.ORDERED))
        s.enterAtContentEnd()
        // 两段都还是有序段。
        assertEquals(RichLineKind.ORDERED, s.doc.lines[0].kind)
        assertEquals(RichLineKind.ORDERED, s.doc.lines[1].kind)
        // 纯文字里没有编号（编号由绘制层按位置算）。
        assertEquals(true, s.doc.plainText.startsWith("甲"))
        assertEquals(false, s.doc.plainText.contains("1."))
        // 编号按位置实时算。
        assertEquals(1, orderedNumberAt(s.doc, 0))
        assertEquals(2, orderedNumberAt(s.doc, 1))
        // 有序段需要绘制符号（编号）。
        assertEquals(true, RichLayout.hasSymbol(RichLineKind.ORDERED))
    }

    /** 列表行回车：新行仍是列表（续行）。 */
    @Test
    fun `列表：回车续行`() {
        val s = stateOf("甲")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        s.enter(1)
        assertEquals(2, s.doc.lines.size)
        assertEquals(RichLineKind.BULLET, s.doc.lines[1].kind)
    }

    /** 空列表行回车：退出列表（不是再续一个空项）。 */
    @Test
    fun `列表：空列表行回车退出列表`() {
        val s = stateOf("")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        s.enter(0)
        assertEquals(1, s.doc.lines.size)
        assertEquals(RichLineKind.TEXT, s.doc.lines[0].kind)
    }

    /** 复选框行回车：新行是未勾选的新项。 */
    @Test
    fun `列表：复选框回车续行且新项未勾选`() {
        val s = stateOf("甲")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.CHECK))
        s.applyDocKeepSelection(s.doc.toggleCheck(0))
        assertTrue(s.doc.lines[0].checked)
        s.enter(1)
        assertEquals(RichLineKind.CHECK, s.doc.lines[1].kind)
        assertFalse(s.doc.lines[1].checked)
    }

    /** 有序列表回车：新行仍是有序，且编号按位置自动递增。 */
    @Test
    fun `列表：有序列表回车编号递增`() {
        val s = stateOf("甲")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.ORDERED))
        s.enterAtContentEnd()
        assertEquals(2, s.doc.lines.size)
        assertEquals(RichLineKind.ORDERED, s.doc.lines[1].kind)
        // 编号不落库、由位置算出（插入/删除自动重排）。
        assertEquals(1, orderedNumberAt(s.doc, 0))
        assertEquals(2, orderedNumberAt(s.doc, 1))
    }

    /** 粘贴含换行的文本不算「单次回车」，走原路径但保留段类型。 */
    @Test
    fun `列表：粘贴多行仍保留列表类型`() {
        val s = stateOf("甲")
        val li = s.contentAnchorIn(s.field.text, s.field.selection.min).first
        s.applyDocKeepSelection(s.doc.setLineKind(li, RichLineKind.BULLET))
        // 粘贴 "乙\n丙"。
        //
        // 真实粘贴是**在光标处**插入的，而且编辑框里本来就带着前缀，
        // 所以要用 field.text 造新文本（用 doc.plainText 会丢掉前缀）。
        val cur = s.field.text
        val at = s.field.selection.min
        onFieldChange(s, cur.substring(0, at) + "乙\n丙" + cur.substring(at))
        val kinds = s.doc.lines.map { it.kind }
        assertEquals(true, kinds.all { it == RichLineKind.BULLET })
    }

    /**
     * 有序编号规则（用户 2026-09-30）：**编号连续、类型不转换**。
     *
     * 只有「空行 / 主动转纯文本」才截断有序列表；夹在中间的
     * 无序行 / 复选框行 **不占号、不重置**。
     */
    @Test
    fun `有序编号：中间夹无序行，编号继续数`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", kind = RichLineKind.ORDERED),
                RichLine("乙", kind = RichLineKind.BULLET),   // 不占号、不打断
                RichLine("丙", kind = RichLineKind.ORDERED),
            ),
        )
        assertEquals(1, orderedNumberAt(doc, 0))
        assertEquals(2, orderedNumberAt(doc, 2))   // 丙 = 2（跳过无序的乙）
    }

    @Test
    fun `有序编号：中间夹复选框行，编号继续数`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", kind = RichLineKind.ORDERED),
                RichLine("乙", kind = RichLineKind.CHECK),
                RichLine("丙", kind = RichLineKind.ORDERED),
            ),
        )
        assertEquals(2, orderedNumberAt(doc, 2))
    }

    @Test
    fun `有序编号：纯文本行截断，重新起算`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", kind = RichLineKind.ORDERED),
                RichLine("说明段落", kind = RichLineKind.TEXT),   // 截断
                RichLine("丙", kind = RichLineKind.ORDERED),
            ),
        )
        assertEquals(1, orderedNumberAt(doc, 2))   // 截断后重新从 1
    }

    @Test
    fun `有序编号：空行（纯文本）截断，重新起算`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", kind = RichLineKind.ORDERED),
                RichLine("", kind = RichLineKind.TEXT),          // 空行 = 截断
                RichLine("丙", kind = RichLineKind.ORDERED),
            ),
        )
        assertEquals(1, orderedNumberAt(doc, 2))
    }

    @Test
    fun `有序编号：连续无序行不影响编号`() {
        val doc = RichDoc(
            listOf(
                RichLine("甲", kind = RichLineKind.ORDERED),
                RichLine("乙", kind = RichLineKind.BULLET),
                RichLine("丙", kind = RichLineKind.CHECK),
                RichLine("丁", kind = RichLineKind.BULLET),
                RichLine("戊", kind = RichLineKind.ORDERED),
            ),
        )
        assertEquals(2, orderedNumberAt(doc, 4))   // 戊 = 2
    }

    private fun onFieldChange(s: RichEditState, text: String) {
        s.onFieldChange(TextFieldValue(text, TextRange(text.length)))
    }
}
