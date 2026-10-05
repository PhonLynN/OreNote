package com.phonlynn.oreplan.core.rt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue

/**
 * **编辑状态机**（2026-09-23 第三次重写定稿）。
 *
 * # 不变式（整个类的正确性都建在它上面）
 *
 * > `field.text` **永远等于** `rawOf(doc)` —— 即当前文档的规范显示文字。
 *
 * 于是没有「两份真值」需要同步，也没有「文档坐标 ↔ 显示坐标」的换算层。
 * 前两次的编辑 bug（光标乱跳、回车插错位置、退格退不掉列表、连续退格卡住）
 * 全部源于那个换算层处理「没有内容含义的分隔字符」。
 *
 * # 模型来自文本
 *
 * 段类型与勾选态**写在文本流的前缀字符里**（见 [RichTextMark]）：
 * ```
 * 文本流 ──parseDisplayText──▶ RichDoc
 *    ▲                            │
 *    └────buildDisplayText────────┘
 * ```
 * 因此「删掉前缀就退出列表」「合并两行后残留前缀自动被剥掉」
 * 这些都是**解析的自然结果**，不需要写特判，也就不会卡住。
 *
 * # 只有回车需要接管
 *
 * 因为回车是**语义操作**：列表行续行要补前缀、空列表项要退出列表。
 * 其余一切编辑都按用户的原始输入接受，然后做一次「解析 → 重建」规范化。
 */
class RichEditState private constructor(initialDoc: RichDoc) {

    // ---------------------------------------------------------------- 真值

    private var document: RichDoc = initialDoc

    /** 当前文档（只读）。 */
    val doc: RichDoc get() = document

    private var styleState: TextStyle by mutableStateOf(TextStyle.Default)
    private var symbolColorState: Color by mutableStateOf(Color.Unspecified)
    private var checkedColorState: Color by mutableStateOf(Color.Unspecified)

    /**
     * 输入框视图。**唯一真值**：`field.text` 恒等于规范显示文字。
     *
     * 必须用 [TextFieldValue] 的 AnnotatedString 重载，否则输入框里看不到格式，
     * 看起来就是「按钮全部失效」。
     */
    var field: TextFieldValue by mutableStateOf(
        TextFieldValue(
            annotatedString = buildDisplayText(
                initialDoc, TextStyle.Default, Color.Unspecified, Color.Unspecified,
            ),
            selection = TextRange(canonicalTextOf(initialDoc).length),
        ),
    )
        private set

    /** 变更计数器：驱动工具栏高亮与外部重新读值。 */
    var revision: Int by mutableStateOf(0)
        private set

    /** 输入模式：未选中文字时按下属性按钮后，后续输入自动带这些属性。 */
    var pendingAttrs: Set<RichAttr> by mutableStateOf(emptySet())

    /** 三点菜单是否展开。提升到这里避免实例重建时被重置。 */
    var menuOpen: Boolean by mutableStateOf(false)

    /** 是否聚焦。 */
    var focused: Boolean by mutableStateOf(false)

    // ---------------------------------------------------------------- 样式

    /**
     * 正文字体样式。宿主在组合期写入。
     *
     * **必须与展示端传同一个值**，否则两端的行高/字号会不同。
     * 样式只影响绘制，不改文字，所以直接刷新带样式字符串并保留选区。
     */
    var baseStyle: TextStyle
        get() = styleState
        set(value) {
            if (value == styleState) return
            styleState = value
            refreshStyledText()
        }

    /** 符号颜色（圆点/编号/复选框）。 */
    var symbolColor: Color
        get() = symbolColorState
        set(value) {
            if (value == symbolColorState) return
            symbolColorState = value
            refreshStyledText()
        }

    /** 已勾选复选框项的正文颜色。 */
    var checkedColor: Color
        get() = checkedColorState
        set(value) {
            if (value == checkedColorState) return
            checkedColorState = value
            refreshStyledText()
        }

    private fun refreshStyledText() {
        val sel = field.selection
        val text = field.text
        field = TextFieldValue(
            annotatedString = displayOf(document),
            selection = TextRange(
                sel.min.coerceIn(0, text.length),
                sel.max.coerceIn(0, text.length),
            ),
            composition = field.composition,
        )
    }

    private fun displayOf(d: RichDoc) =
        buildDisplayText(d, styleState, symbolColorState, checkedColorState)

    // ---------------------------------------------------------------- 撤销 / 重做

    private val undoStack = ArrayDeque<Pair<String, Int>>()
    private val redoStack = ArrayDeque<Pair<String, Int>>()
    private val historyLimit = 100

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun snapshot() = field.text to field.selection.min

    private fun pushHistory(before: Pair<String, Int>) {
        undoStack.addLast(before)
        while (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * 把光标放到某个**显示偏移**处（双击进编辑时按点击位置定位用）。
     *
     * 显示偏移 = [field] 里 text 的下标（含列表符号前缀），
     * 与 TextLayoutResult.getOffsetForPosition 的返回值同一口径。
     */
    fun placeCursorAt(displayOffset: Int) {
        val text = field.text
        field = TextFieldValue(
            annotatedString = displayOf(document),
            selection = TextRange(displayOffset.coerceIn(0, text.length)),
        )
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        applyCanonicalText(prev.first, prev.second)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        applyCanonicalText(next.first, next.second)
    }

    /** 直接应用一段规范文本（撤销/重做用）。 */
    private fun applyCanonicalText(text: String, caret: Int) {
        document = parseDisplayText(text)
        field = TextFieldValue(
            annotatedString = displayOf(document),
            selection = TextRange(caret.coerceIn(0, text.length)),
        )
        revision++
        emit()
    }

    // ---------------------------------------------------------------- 与宿主的同步

    private var onEmit: ((String) -> Unit)? = null

    /**
     * 自己发出过的全部字符串（含构造初值）。
     *
     * 宿主的更新有一帧延迟：它可能拿一个稍早的值回来调 [syncFromHost]。
     * 只看「最后一次发出的值」会漏判，把刚做的修改冲掉
     *（表现为「列表/复选框按钮点了没反应」）。
     */
    private val seenValues = LinkedHashSet<String>()
    private val seenLimit = 128

    init {
        // 把初始值也记进来：宿主可能拿「我还没改之前」的值来调 syncFromHost，
        // 若不记录，那次回灌会把刚做的修改（例如刚切成列表）整个冲掉。
        seenValues.add(document.encode())
    }

    internal fun bindEmitter(emit: (String) -> Unit) {
        onEmit = emit
    }

    private fun emit() {
        val s = document.encode()
        seenValues.add(s)
        while (seenValues.size > seenLimit) {
            seenValues.remove(seenValues.first())
        }
        onEmit?.invoke(s)
    }

    /**
     * 宿主字符串变化时同步。三级判据：
     *  1. 等于本地文档 → 一致，不动作；
     *  2. 命中 [seenValues] → 自己的回声 / 宿主落后，忽略；
     *  3. 其余 → 宿主确实换了内容，重建。
     */
    fun syncFromHost(value: String) {
        if (value == document.encode()) return
        if (seenValues.contains(value)) return
        val newDoc = RichDoc.decode(value)
        document = newDoc
        val text = canonicalTextOf(newDoc)
        field = TextFieldValue(displayOf(newDoc), TextRange(text.length))
        revision++
    }

    // ---------------------------------------------------------------- 编辑事件

    /**
     * 输入框文本变化。
     *
     * 只有**回车**需要接管；其余一律接受用户的原始输入，随后做一次规范化。
     */
    fun onFieldChange(next: TextFieldValue) {
        val oldText = field.text
        val newText = next.text

        // 1. 文本没变：只更新选区/组合态（移动光标、IME 组合中）。
        if (oldText == newText) {
            field = next
            return
        }

        val oldSel = field.selection
        val change = resolveChange(oldText, newText)

        // 2. 回车 → 语义处理（列表续行 / 空项退出）。
        val isPlainEnter = change.inserted == "\n" && change.from == change.to
        val (editedText, caretInEdited) = if (isPlainEnter) {
            handleEnter(oldText, change.from)
        } else {
            handleInsert(oldText, newText, change, next.selection.min)
        }

        // 3. 把这次改动落到文档上。
        //
        // ## 为什么要分成两步（关键）
        //
        // 文本流里**只有块结构**（段类型/勾选态），**没有行内属性**
        //（加粗等属性是任意区间，无法用单个字符表达）。
        // 所以：
        //
        // - **正文 + 行内属性**：交给文档级编辑操作（它能正确搬运属性）；
        // - **块结构**：以文本解析为准（前缀字符就是用户改出来的）。
        //
        // 只靠解析会把行内属性洗掉（实测：输入第二个字符后加粗就没了）。
        val cFrom = contentOffsetOfDisplay(oldText, change.from)
        val cTo = contentOffsetOfDisplay(oldText, change.to)
        val cInserted = RichTextMark.stripMarks(change.inserted)

        val before = oldText to oldSel.min
        if (editedText != before.first) pushHistory(before)

        // 3a. 文档级编辑（保内容与行内属性）。
        val editedDoc = document.replaceRange(cFrom, cTo, cInserted, pendingAttrs).doc

        // 3b. 块结构以文本解析为准。
        val parsed = parseDisplayText(editedText)
        val merged = mergeStructure(editedDoc, parsed)

        // 4. 重建：文本 = 规范显示文字（可能因前缀补全而与 editedText 略异）。
        document = merged
        val rebuilt = canonicalTextOf(merged)
        // 选区 / 光标：**以输入框给我们的那个为准**，只按「我们改动了哪里」做等量平移
        //（用户 2026-09-28 报「选择范围易跳变」，根因就在这几行）。
        //
        // 旧写法用「内容锚点」重新推算光标（contentAnchorIn → displayOffsetFor）：
        // 那是个启发式 —— 输入法组词、前缀增删、光标落在前缀上时算出来的位置会跳；
        // 而且它只保留 caret（选区被**折成光标**），已经选中的范围会在任何一次
        // 文本变化后凭空消失。
        //
        // 现在只做**确定性平移**：比较「用户给的文本」与「我们重建的规范文本」，
        // 取第一处差异；差异点**之前**的下标原样不动，**之后**（含该点本身）整体平移
        // —— 列表续行时那个位置正好要插入前缀，光标应落在前缀之后。
        // min / max 两端各平移一次 → 范围的长度与方向都不变。
        val (diffAt, lenDelta) = firstDifference(newText, rebuilt)
        fun shift(i: Int): Int =
            (if (i < diffAt) i else i + lenDelta).coerceIn(0, rebuilt.length)
        val nextSel = next.selection

        field = TextFieldValue(
            annotatedString = displayOf(merged),
            selection = TextRange(shift(nextSel.min), shift(nextSel.max)),
            composition = next.composition,
        )
        revision++
        emit()
    }

    /**
     * 把解析出来的**块结构**（段类型/勾选态）合并到文档上，**保留行内属性**。
     *
     * 两者内容应当一致（都在描述同一次编辑）。若不一致（说明坐标映射有偏差），
     * 以**结构为准**——宁可丢格式，也不能让段类型错乱。
     */
    private fun mergeStructure(contentDoc: RichDoc, parsed: RichDoc): RichDoc {
        val sameShape = contentDoc.lines.size == parsed.lines.size &&
            contentDoc.lines.map { it.text } == parsed.lines.map { it.text }
        if (!sameShape) return parsed
        return RichDoc(
            contentDoc.lines.mapIndexed { i, line ->
                val p = parsed.lines[i]
                line.copy(kind = p.kind, checked = p.checked).normalized()
            },
        )
    }

    /** 显示坐标 → **全局内容下标**（前缀与换行符不计入内容长度）。 */
    private fun contentOffsetOfDisplay(text: String, display: Int): Int {
        val d = parseDisplayText(text)
        val upto = display.coerceIn(0, text.length)
        val li = lineIndexAt(text, upto)
        val lineStart = lineStartOf(text, upto)
        val line = d.lines.getOrNull(li) ?: return 0
        val prefix = RichTextMark.prefixLengthOf(line.kind)
        val col = (upto - lineStart - prefix).coerceIn(0, line.text.length)
        var acc = 0
        for (i in 0 until li) acc += d.lines[i].text.length + 1
        return acc + col
    }

    /**
     * 处理回车。
     *
     * 语义（用户明确要求）：
     * - 普通行 → 单纯换行；
     * - 列表行且**有内容** → 换行 **并补上新行前缀**（续行；复选项不继承勾选）；
     * - 列表行且**内容为空** → **退出列表**（去掉前缀，不换行）。
     */
    private fun handleEnter(oldText: String, at: Int): Pair<String, Int> {
        val start = lineStartOf(oldText, at)
        val end = lineEndOf(oldText, at)
        val (kind, _, body) = RichTextMark.parseLine(oldText.substring(start, end))

        // 普通行 → 直接换行。
        if (kind == RichLineKind.TEXT) {
            return oldText.substring(0, at) + "\n" + oldText.substring(at) to (at + 1)
        }

        // 空列表项 → 退出列表：去掉前缀，不换行。
        if (body.isEmpty()) {
            val prefixLen = RichTextMark.prefixLengthOf(kind)
            val text = oldText.substring(0, start) +
                oldText.substring((start + prefixLen).coerceAtMost(oldText.length))
            return text to start
        }

        // 有内容的列表项 → 续行：换行 + 新前缀。
        val prefix = RichTextMark.prefixOf(kind, checked = false)
        val text = oldText.substring(0, at) + "\n" + prefix + oldText.substring(at)
        return text to (at + 1 + prefix.length)
    }

    /**
     * 处理普通插入（含粘贴）。
     *
     * 两件语义：
     * 1. **多行粘贴续行**：在列表行里粘贴多行时，后续行补上同类型前缀
     *（否则粘进来的行会变成普通段落，与「列表里回车续行」不一致）；
     * 2. 其余一律按用户输入接受。
     */
    private fun handleInsert(
        oldText: String,
        newText: String,
        change: Change,
        caret: Int,
    ): Pair<String, Int> {
        if (!change.inserted.contains('\n')) return newText to caret

        // 插入点所在行的列表类型。
        val lineStart = lineStartOf(oldText, change.from)
        val lineEnd = lineEndOf(oldText, change.from)
        val (kind, _, _) = RichTextMark.parseLine(oldText.substring(lineStart, lineEnd))
        if (kind == RichLineKind.TEXT) return newText to caret

        // 给粘贴进来的每一新行补前缀（第一行是接在当前行后面，不需要）。
        val prefix = RichTextMark.prefixOf(kind, checked = false)
        val prefixed = change.inserted.replace("\n", "\n" + prefix)
        val text = oldText.substring(0, change.from) + prefixed +
            oldText.substring((change.from + change.inserted.length).coerceAtMost(oldText.length))
        val delta = prefixed.length - change.inserted.length
        return text to (caret + delta)
    }

    /** 一次编辑的等价三元组：被替换区间 `[from, to)` 与插入内容。 */
    private data class Change(val from: Int, val to: Int, val inserted: String)

    /**
     * 把一次编辑归约为三元组。**旧选区限定了可变动区间**，
     * 所以不会出现「输入法提交被当成删除」这类错判。
     */
    private fun resolveChange(oldText: String, newText: String): Change {
        // 用**公共前缀 / 公共后缀**定位这次编辑（文本编辑器的通用做法）：
        // 光标处插入、删一个字、替换一段、粘贴多行、输入法提交，全都得到准确结果，
        // 而且**与选区无关** —— 改动落在选区之外时也不会算错。
        //
        // 旧实现分两种情形：光标态按长度差猜位置、选区态一律当成「整段选区被替换」。
        // 后者在「选区还在、改动发生在选区之外」时会把内容改坏
        //（例如选着一段文字、输入法在末尾提交），也是用户 2026-09-28 报的选区问题之一。
        var p = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (p < maxPrefix && oldText[p] == newText[p]) p++
        var s = 0
        val maxSuffix = minOf(oldText.length - p, newText.length - p)
        while (s < maxSuffix &&
            oldText[oldText.length - 1 - s] == newText[newText.length - 1 - s]
        ) {
            s++
        }
        return Change(
            from = p,
            to = oldText.length - s,
            inserted = newText.substring(p, newText.length - s),
        )
    }

    // ---------------------------------------------------------------- 文本 ↔ 模型

    // ---------------------------------------------------------------- 工具栏入口

    /**
     * 应用一次文档变更（工具栏改段类型 / 属性）。
     *
     * 用**内容位置**（段号 + 段内列）作为锚点来还原光标，
     * 这样增删前缀（2 个字符）不会让光标跑偏。
     */
    internal fun applyDocKeepSelection(newDoc: RichDoc) {
        if (newDoc == document) return
        val before = snapshot()
        val sel = field.selection
        val text = field.text

        val anchorMin = contentAnchorIn(text, sel.min)
        val anchorMax = contentAnchorIn(text, sel.max)

        pushHistory(before)
        document = newDoc
        val newText = canonicalTextOf(newDoc)
        field = TextFieldValue(
            annotatedString = displayOf(newDoc),
            selection = TextRange(
                displayOffsetFor(newDoc, newText, anchorMin).coerceIn(0, newText.length),
                displayOffsetFor(newDoc, newText, anchorMax).coerceIn(0, newText.length),
            ),
        )
        revision++
        emit()
    }

    /** 切换第 [index] 段的勾选态。 */
    fun toggleCheckAt(index: Int) {
        applyDocKeepSelection(document.toggleCheck(index))
    }

    /** 光标（显示坐标）→ 内容锚点 `(段号, 段内列)`。 */
    fun contentAnchorIn(text: String, displayOffset: Int): Pair<Int, Int> {
        val d = parseDisplayText(text)
        val upto = displayOffset.coerceIn(0, text.length)
        val li = lineIndexAt(text, upto)
        val start = lineStartOf(text, upto.coerceAtLeast(0))
        val line = d.lines.getOrNull(li)
        val prefix = RichTextMark.prefixLengthOf(line?.kind ?: RichLineKind.TEXT)
        val max = line?.text?.length ?: 0
        return li to (upto - start - prefix).coerceIn(0, max)
    }

    /** 内容锚点 → 显示坐标。 */
    private fun displayOffsetFor(d: RichDoc, text: String, anchor: Pair<Int, Int>): Int {
        val (line, col) = anchor
        val li = line.coerceIn(0, d.lines.lastIndex.coerceAtLeast(0))
        // 累加前面各行的显示长度（前缀 + 正文 + 换行符）。
        var acc = 0
        for (i in 0 until li) {
            val l = d.lines.getOrNull(i) ?: break
            acc += RichTextMark.prefixLengthOf(l.kind) + l.text.length
            if (i < d.lines.lastIndex) acc += 1
        }
        val prefix = RichTextMark.prefixLengthOf(d.lines.getOrNull(li)?.kind ?: RichLineKind.TEXT)
        val max = d.lines.getOrNull(li)?.text?.length ?: 0
        return (acc + prefix + col.coerceIn(0, max)).coerceIn(0, text.length)
    }

    // ---------------------------------------------------------------- 文本工具

    private fun lineStartOf(text: String, at: Int): Int {
        val from = (at - 1).coerceAtLeast(0)
        if (at <= 0) return 0
        val i = text.lastIndexOf('\n', from)
        return if (i < 0) 0 else i + 1
    }

    private fun lineEndOf(text: String, at: Int): Int {
        val i = text.indexOf('\n', at.coerceIn(0, text.length))
        return if (i < 0) text.length else i
    }

    private fun lineIndexAt(text: String, at: Int): Int {
        var count = 0
        for (i in 0 until at.coerceIn(0, text.length)) if (text[i] == '\n') count++
        return count
    }

    /**
     * 两段文本的**第一处差异下标**，以及「b 相对 a 的长度差」。
     *
     * 用途：把输入框给的选区下标平移到重建后的规范文本上。
     * 只依赖「差异在哪、差多少」，不按内容猜位置 —— 猜就是会跳。
     */
    private fun firstDifference(a: String, b: String): Pair<Int, Int> {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i to (b.length - a.length)
    }

    /** 当前文档的落库字符串。 */
    fun encoded(): String = document.encode()

    companion object {
        /**
         * 规范显示文字：每段 = 前缀 + 正文，段间以 `\n` 连接。
         *
         * **这是显示流与编辑流共用的唯一形态**，所以两端逐字符一致。
         */
        fun canonicalTextOf(doc: RichDoc): String = buildString {
            doc.lines.forEachIndexed { i, line ->
                if (i > 0) append('\n')
                append(RichTextMark.prefixOf(line.kind, line.checked))
                append(line.text)
            }
        }

        fun of(raw: String?): RichEditState = ofDoc(RichDoc.decode(raw))

        fun ofDoc(doc: RichDoc): RichEditState = RichEditState(doc)
    }
}
