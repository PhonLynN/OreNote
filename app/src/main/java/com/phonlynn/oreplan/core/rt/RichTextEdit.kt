package com.phonlynn.oreplan.core.rt

/**
 * **编辑操作**（2026-09-23，语义沿用 2026-09-20 定稿的显式操作集）。
 *
 * # 为什么是「显式操作」而不是「文本 diff 反推」
 *
 * 更早的实现拿 `BasicTextField.onValueChange(next)` 的**新旧文本公共前后缀**
 * 去反推用户做了什么。那个方法丢掉了**选区**与**操作类型**两个信息，
 * 实测必然出错：
 *
 * | 操作 | 期望 | 旧实现实测 |
 * | --- | --- | --- |
 * | 中文输入法组合提交（`ni` → `你`） | `甲你` | `甲i`（**丢字**） |
 * | 全选删除多行 | 清空 | `- \n- 乙`（删不干净） |
 * | 行首退格合并两行 | `甲乙` | 不合并 |
 *
 * 现在反过来：**先把输入事件翻译成明确的操作**（插入 / 替换选区 / 删除 /
 * 换行 / 合并行），再对文档施加该操作。每个操作都是纯函数，
 * 输入输出都有明确语义，可以逐项单测。
 *
 * # 通用约定
 *
 * - 所有函数**不修改**入参，返回新文档。
 * - 所有坐标都会被夹到合法范围，**绝不抛异常**（用户乱点也不该崩）。
 * - 返回值统一为 [EditResult]：新文档 + 新光标（全局下标）。
 */

/** 编辑结果：新文档 + 新光标位置（全局下标）。 */
data class EditResult(val doc: RichDoc, val caret: Int) {
    /** 便捷：新光标换算成段内坐标。 */
    val caretPos: CaretPos get() = doc.caretOf(caret)
}

/** 空文档（单个空段）的便捷值。 */
private fun emptyDoc(): RichDoc = RichDoc(listOf(RichLine("")))

/** 确保文档至少有一段。 */
internal fun RichDoc.sanitized(): RichDoc = if (lines.isEmpty()) emptyDoc() else this

// ---------------------------------------------------------------- 插入 / 替换

/**
 * 用 [text] **替换**全局区间 `[from, to)`，并把光标放在插入内容之后。
 *
 * 这是最通用的操作，覆盖：
 *  · 普通打字（`from == to`，即插入）；
 *  · **输入法组合提交**（选区被候选字替换）；
 *  · 框选后输入 / 粘贴（选区被新内容替换）；
 *  · 全选删除（`text` 为空）。
 *
 * [text] 可含换行：按行拆分，首行接在光标前、末行接上光标后，
 * 中间各段独立成行，**并继承插入点所在段的段类型**（列表里粘贴多行仍然是列表）。
 *
 * [pending] 是「输入模式」下待套用的属性集（工具栏切换时非空）。
 * **只作用于本次插入的新文字**，不碰已有文字。
 */
fun RichDoc.replaceRange(
    from: Int,
    to: Int,
    text: String,
    pending: Set<RichAttr> = emptySet(),
): EditResult {
    val doc = sanitized()
    val a = minOf(from, to).coerceIn(0, doc.textLength)
    val b = maxOf(from, to).coerceIn(0, doc.textLength)

    // 先删掉被替换的内容（复用删除逻辑，保证属性搬运一致）。
    val afterDelete = if (a < b) doc.deleteGlobal(a, b) else doc
    if (text.isEmpty()) return EditResult(afterDelete, a)

    val start = afterDelete.caretOf(a)
    return afterDelete.insertAt(start.line, start.col, text, pending)
}

/**
 * 纯插入（等价于空区间的 [replaceRange]）。
 */
fun RichDoc.insertText(
    line: Int,
    col: Int,
    text: String,
    pending: Set<RichAttr> = emptySet(),
): EditResult {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    val lineText = doc.lines[li].text
    val pos = col.coerceIn(0, lineText.length)
    return doc.insertAt(li, pos, text, pending)
}

/**
 * 在 `(line, col)` 处插入文本。含换行时拆段。
 *
 * 属性搬运规则（关键）：
 *  · 插入点**之前**的区间原样保留；
 *  · 插入点**之后**的区间整体右移；
 *  · 横跨插入点的区间被切开（两侧都保留）；
 *  · 新插入的字符**不带任何属性**，除非 [pending] 指定。
 */
internal fun RichDoc.insertAt(
    line: Int,
    col: Int,
    text: String,
    pending: Set<RichAttr>,
): EditResult {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    val target = doc.lines[li]
    val pos = col.coerceIn(0, target.text.length)

    // 无换行：单段内插入。
    if (!text.contains('\n')) {
        val newText = target.text.substring(0, pos) + text + target.text.substring(pos)
        val shifted = target.spans.map { sp ->
            when {
                sp.end <= pos -> sp
                sp.start >= pos -> sp.shift(text.length)
                else -> sp.copy(end = sp.end + text.length)
            }
        }
        var newLine = target.copy(text = newText, spans = shifted)
        val end = pos + text.length
        if (pending.isNotEmpty() && end > pos) {
            newLine = newLine.withAttrs(pos, end, pending)
        }
        val lines = doc.lines.toMutableList()
        lines[li] = newLine.normalized()
        return EditResult(RichDoc(lines), doc.globalOf(li, pos) + text.length)
    }

    // 含换行：拆分。
    val parts = text.split('\n')
    val head = target.text.substring(0, pos)
    val tail = target.text.substring(pos)

    // 第一段：光标前的内容 + 首段文字；属性只保留光标前的部分 + 首段新增。
    val headSpans = target.spans.mapNotNull { sp ->
        if (sp.start >= pos) null
        else if (sp.end > pos) sp.copy(end = pos) else sp
    }.toMutableList()

    val headText = head + parts[0]
    if (pending.isNotEmpty() && parts[0].isNotEmpty()) {
        headSpans.addAll(pending.map { it.toSpan(head.length, head.length + parts[0].length) })
    }

    // 尾部属性整体右移到末段（末段文字长度 = lastPart.length）。
    val lastPart = parts.last()
    val tailSpans = target.spans.mapNotNull { sp ->
        if (sp.end <= pos) null
        else {
            val ns = maxOf(sp.start, pos) - pos + lastPart.length
            val ne = sp.end - pos + lastPart.length
            if (ne <= ns) null else sp.copy(start = ns, end = ne)
        }
    }.toMutableList()
    if (pending.isNotEmpty() && lastPart.isNotEmpty()) {
        tailSpans.addAll(pending.map { it.toSpan(0, lastPart.length) })
    }

    // 拼出新段序列：首段 + 中间各段 + 末段，再替换掉原段。
    val built = ArrayList<RichLine>(parts.size)
    built.add(RichLine(headText, target.kind, target.checked, normalizeSpans(headSpans)).normalized())
    for (k in 1 until parts.size - 1) {
        val mid = RichLine(parts[k], kind = target.kind, checked = false)
        built.add((if (pending.isEmpty()) mid else mid.withAttrs(0, parts[k].length, pending)).normalized())
    }
    val lastIndex = parts.size - 1
    built.add(
        RichLine(lastPart + tail, target.kind, target.checked, normalizeSpans(tailSpans)).normalized(),
    )

    val lines = doc.lines.toMutableList()
    lines[li] = built[0]
    for (k in 1..lastIndex) lines.add(li + k, built[k])

    val caretLine = li + lastIndex
    val caretCol = lastPart.length
    val newDoc = RichDoc(lines)
    return EditResult(newDoc, newDoc.globalOf(caretLine, caretCol))
}

// ---------------------------------------------------------------- 删除

/**
 * 删除全局区间 `[from, to)`。
 *
 * - 同段内：直接删除，属性随之裁剪。
 * - 跨段：首段保留前半、末段保留后半，中间段整体删除，两段**合并为一段**
 *   （段类型取首段），这是所有编辑器的标准行为。
 */
fun RichDoc.deleteRange(from: Int, to: Int): EditResult {
    val doc = sanitized()
    val a = minOf(from, to).coerceIn(0, doc.textLength)
    val b = maxOf(from, to).coerceIn(0, doc.textLength)
    if (a == b) return EditResult(doc, a)
    return EditResult(doc.deleteGlobal(a, b), a)
}

/**
 * 行首退格：把第 [line] 段并入上一段。
 *
 * **不继承**上一段的段类型（按退格就是要退出当前结构）。
 * 首段调用时返回 null（无处可并）。
 */
fun RichDoc.mergeWithPrevious(line: Int): EditResult? {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    if (li == 0) return null
    val prev = doc.lines[li - 1]
    val cur = doc.lines[li]
    val joinAt = prev.text.length
    val merged = RichLine(
        text = prev.text + cur.text,
        kind = prev.kind,
        checked = prev.checked,
        spans = normalizeSpans(prev.spans + cur.spans.map { it.shift(joinAt) }),
    ).normalized()
    val lines = doc.lines.toMutableList()
    lines[li - 1] = merged
    lines.removeAt(li)
    val newDoc = RichDoc(lines)
    return EditResult(newDoc, newDoc.globalOf(li - 1, joinAt))
}

/**
 * 删除**全局区间**的内部实现。
 *
 * 跨段时把首段前半与末段后半合并，段类型取首段。
 */
private fun RichDoc.deleteGlobal(from: Int, to: Int): RichDoc {
    val start = caretOf(from)
    val end = caretOf(to)

    // 同段：单段内删除。
    if (start.line == end.line) {
        val line = lines[start.line]
        val newText = line.text.substring(0, start.col) + line.text.substring(end.col)
        val removed = end.col - start.col
        val spans = line.spans.mapNotNull { sp ->
            when {
                sp.end <= start.col -> sp
                sp.start >= end.col -> sp.shift(-removed)
                else -> {
                    // 与删除区间相交：保留左右两侧，拼成一个区间。
                    val s = minOf(sp.start, start.col)
                    val e = maxOf(start.col, sp.end - removed)
                    if (e <= s) null else sp.copy(start = s, end = e)
                }
            }
        }
        val linesNew = lines.toMutableList()
        linesNew[start.line] = line.copy(text = newText, spans = spans).normalized()
        return RichDoc(linesNew)
    }

    // 跨段：首段前半 + 末段后半，中间段删除。
    val head = lines[start.line]
    val tail = lines[end.line]
    val headText = head.text.substring(0, start.col)
    val tailText = tail.text.substring(end.col)
    val joinAt = headText.length

    val headSpans = head.spans.mapNotNull { it.clamp(0, start.col) }
    val tailShifted = tail.spans.mapNotNull { sp ->
        sp.clamp(end.col, tail.text.length)?.shift(joinAt - end.col)
    }
    val merged = RichLine(
        text = headText + tailText,
        kind = head.kind,
        checked = head.checked,
        spans = normalizeSpans(headSpans + tailShifted),
    ).normalized()

    val linesNew = lines.toMutableList()
    linesNew[start.line] = merged
    // 从后往前删避免下标偏移。
    for (i in end.line downTo start.line + 1) linesNew.removeAt(i)
    return RichDoc(linesNew)
}

// ---------------------------------------------------------------- 回车 / 行首退格（语义规则）

/**
 * **回车**：在全局下标 [at] 处插入换行。
 *
 * 规则（用户明确要求的行为）：
 *
 * 1. 普通段落 → 拆成两段，两段都是普通段落；
 * 2. **非空**列表项 → 拆成两段，**新段继承列表类型**（续行）；
 * 3. **空**列表项 → **退出列表**：本段变回普通段落（不再续一个退不掉的空项）。
 *
 * 后两条只有本函数知道，所以编辑层识别到回车必须调它，
 * 不能当成普通插入（否则会续出一个退不掉的空列表项）。
 */
fun RichDoc.splitLineAt(at: Int): EditResult {
    val doc = sanitized()
    val pos = doc.caretOf(at.coerceIn(0, doc.textLength))
    return doc.splitLine(pos.line, pos.col)
}

/**
 * **行首退格**：光标位于某一段的行首时按下退格。
 *
 * 这是用户报告「退格退不掉列表」「从下到上连续退格会卡住」的直接修法。
 * 旧实现走通用删除，只能把两段文字接起来，无法表达「退出列表」这一步。
 *
 * 规则（与通用编辑器一致，也是用户要求的行为）：
 *
 * | 当前段 | 行为 |
 * | --- | --- |
 * | 首段 | 无上一段可并；若是列表则**退出列表**（变回普通段落），否则无操作 |
 * | 列表段 | **先退出列表**（只改段类型，文字不动，光标留在原地） |
 * | 普通段 | 并入上一段（保持上一段的类型） |
 *
 * 「先退列表、再并段」分两步是刻意的：用户连续退格时，
 * 第一次退出列表、第二次才合并 —— 与 iOS / Android 原生编辑器手感一致，
 * 也避免了「一下退太多」的突兀。
 */
fun RichDoc.backspaceAtLineStart(at: Int): EditResult {
    val doc = sanitized()
    val pos = doc.caretOf(at.coerceIn(0, doc.textLength))
    val li = pos.line
    val target = doc.lines.getOrNull(li) ?: return EditResult(doc, at)

    // 1) 当前段是列表 → 先退出列表。
    if (target.kind != RichLineKind.TEXT) {
        val lines = doc.lines.toMutableList()
        lines[li] = target.copy(kind = RichLineKind.TEXT, checked = false)
        return EditResult(RichDoc(lines), at)
    }

    // 2) 首段且不是列表 → 没有可并的上一段。
    if (li == 0) return EditResult(doc, at)

    // 3) 普通段 → 与上一段合并（保持上一段的类型）。
    //    复用既有实现，它有完整的属性搬运逻辑。
    return doc.mergeWithPrevious(li) ?: EditResult(doc, at)
}

/**
 * **行尾回车**：等价于在段末调用 [splitLineAt]。
 *
 * 单独开一个名字是为了让调用处读起来就是用户的心智模型
 *（「本行写完了，下一行开始」）。
 */
fun RichDoc.enterAtLineEnd(line: Int): EditResult {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    return doc.splitLine(li, doc.lines[li].text.length)
}

// ---------------------------------------------------------------- 换行

/**
 * 在第 [line] 段的 [col] 处插入换行。
 *
 * 规则：
 *  · 新段**继承**当前段的类型（列表里回车仍是列表）；
 *  · **空列表项回车 = 退出列表**：把该段变回普通段落，而不是再续一个空项；
 *  · 有序列表的编号不写进文字，由渲染层按位置算。
 */
fun RichDoc.splitLine(line: Int, col: Int): EditResult {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    val target = doc.lines[li]
    val pos = col.coerceIn(0, target.text.length)

    // 空列表项回车 → 退出列表（保持光标不动，避免视觉跳变）。
    val isEmptyListItem = target.text.isEmpty() && target.kind != RichLineKind.TEXT
    if (isEmptyListItem) {
        val lines = doc.lines.toMutableList()
        lines[li] = target.copy(kind = RichLineKind.TEXT, checked = false)
        return EditResult(RichDoc(lines), doc.globalOf(li, 0))
    }

    val headText = target.text.substring(0, pos)
    val tailText = target.text.substring(pos)

    val headSpans = target.spans.mapNotNull { it.clamp(0, pos) }
    val tailSpans = target.spans.mapNotNull { sp ->
        sp.clamp(pos, target.text.length)?.shift(-pos)
    }

    val lines = doc.lines.toMutableList()
    lines[li] = RichLine(headText, target.kind, target.checked, normalizeSpans(headSpans)).normalized()
    lines.add(li + 1, RichLine(tailText, target.kind, false, normalizeSpans(tailSpans)).normalized())

    val newDoc = RichDoc(lines)
    return EditResult(newDoc, newDoc.globalOf(li + 1, 0))
}

// ---------------------------------------------------------------- 属性 / 段类型

/**
 * 在段 [line] 的 `[from, to)` 上切换属性 [attr]。
 *
 * 规则：全部已带 → 全部去掉；否则一律设为带上（掺杂时不再逐字翻转，
 * 避免「越点越乱」）。各属性**互不干扰**。
 */
fun RichDoc.toggleAttr(line: Int, from: Int, to: Int, attr: RichAttr): RichDoc {
    val ctx = attrContext(line, from, to) ?: return sanitized()
    val allOn = (ctx.a until ctx.b).all { ctx.target.hasAttr(it, attr) }
    return applyAttrSet(ctx, attr, on = !allOn)
}

/**
 * 在段 [line] 的 `[from, to)` 上把属性 [attr] **设为** [on]。
 *
 * 用途：**有选区**时的「设置模式」。用户选好文字后点按钮，
 * 期待的是「让选中文字变成这个样式」，而不是翻转。
 */
fun RichDoc.setAttr(line: Int, from: Int, to: Int, attr: RichAttr, on: Boolean): RichDoc {
    val ctx = attrContext(line, from, to) ?: return sanitized()
    return applyAttrSet(ctx, attr, on = on)
}

/**
 * 设置第 [line] 段的类型。
 *
 * 同类型再点一次 = 取消（回到普通段落）；退出复选框时清掉勾选态。
 * 复选框改为勾选态时保留原勾选值（用户点类型按钮不该重置勾选）。
 */
fun RichDoc.setLineKind(line: Int, kind: RichLineKind): RichDoc {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    val target = doc.lines[li]
    val next = if (target.kind == kind) RichLineKind.TEXT else kind
    val lines = doc.lines.toMutableList()
    lines[li] = target.copy(
        kind = next,
        checked = if (next == RichLineKind.CHECK) target.checked else false,
    )
    return RichDoc(lines)
}

/**
 * 切换第 [line] 段的勾选态（仅复选框段有意义）。
 *
 * 勾选态变化后**顺带校正删除线**（见 [syncStrikeToCheck]）——
 * 编辑端（点复选框）与展示端（聚焦页/卡片上点复选框）都走这个函数，
 * 所以两端行为天然一致，不需要各自实现一遍。
 */
fun RichDoc.toggleCheck(line: Int): RichDoc {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    val target = doc.lines[li]
    if (target.kind != RichLineKind.CHECK) return doc
    val lines = doc.lines.toMutableList()
    lines[li] = target.copy(checked = !target.checked)
    return RichDoc(lines).syncStrikeToCheck(li)
}

/**
 * 让整段的删除线**符合**复选框状态：勾选 → 整段有删除线，取消 → 整段没有。
 *
 * 两条刻意为之的约束（用户 2026-09-28 明确要求）：
 *
 * 1. **按状态赋值，不是取反**。所以先读当前删除线状态：整段已经符合就一个字都不动
 *    （不产生多余的历史记录，也不打扰用户手写的局部格式）。
 * 2. **不与复选框绑定**。只在这里做一次校正；之后用户手动加/去删除线不会被拉回来。
 */
private fun RichDoc.syncStrikeToCheck(line: Int): RichDoc {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    val target = doc.lines[li]
    val len = target.text.length
    if (len == 0) return doc
    val allStruck = (0 until len).all { target.isStrikeAt(it) }
    val wantStruck = target.checked
    if (allStruck == wantStruck) return doc
    return doc.setAttr(li, 0, len, RichAttr.STRIKE, wantStruck)
}

// ---------------------------------------------------------------- 属性内部实现

private data class AttrContext(
    val doc: RichDoc,
    val li: Int,
    val target: RichLine,
    val a: Int,
    val b: Int,
)

/**
 * 属性操作的前置处理：规整文档、夹住段号与区间。
 * 区间为空（`a == b`）时返回 null，调用方直接放弃 —— 没有可改的字符。
 */
private fun RichDoc.attrContext(line: Int, from: Int, to: Int): AttrContext? {
    val doc = sanitized()
    val li = line.coerceIn(0, doc.lines.lastIndex)
    val target = doc.lines[li]
    val len = target.text.length
    val a = minOf(from, to).coerceIn(0, len)
    val b = maxOf(from, to).coerceIn(0, len)
    if (a == b) return null
    return AttrContext(doc, li, target, a, b)
}

/**
 * 把段内 `[a, b)` 区间内 [attr] 统一设为 [on]，其余属性原样保留。
 *
 * 实现：逐字符取「完整属性集」→ 只改写目标属性 → 把连续同属性集的
 * 字符压回区间。区间外的 span 原样保留，与区间相交的拆成左右两段。
 */
private fun applyAttrSet(ctx: AttrContext, attr: RichAttr, on: Boolean): RichDoc {
    val target = ctx.target

    val attrs = Array(ctx.b - ctx.a) { i ->
        val sp = target.spanAt(ctx.a + i)
        mapOf(
            RichAttr.BOLD to (if (attr == RichAttr.BOLD) on else sp?.bold == true),
            RichAttr.UNDERLINE to (if (attr == RichAttr.UNDERLINE) on else sp?.underline == true),
            RichAttr.STRIKE to (if (attr == RichAttr.STRIKE) on else sp?.strike == true),
            RichAttr.ITALIC to (if (attr == RichAttr.ITALIC) on else sp?.italic == true),
            RichAttr.HIGHLIGHT to (if (attr == RichAttr.HIGHLIGHT) on else sp?.highlight == true),
        )
    }

    // 区间外的原样保留；与区间相交的拆成左右两段（中段丢弃、稍后重建）。
    val result = ArrayList<RichSpan>()
    target.spans.forEach { sp ->
        if (sp.end <= ctx.a || sp.start >= ctx.b) {
            result.add(sp)
        } else {
            if (sp.start < ctx.a) result.add(sp.copy(end = ctx.a))
            if (sp.end > ctx.b) result.add(sp.copy(start = ctx.b))
        }
    }

    fun spanOf(set: Map<RichAttr, Boolean>): RichSpan = RichSpan(
        start = 0,
        end = 0,
        bold = set[RichAttr.BOLD] == true,
        underline = set[RichAttr.UNDERLINE] == true,
        strike = set[RichAttr.STRIKE] == true,
        italic = set[RichAttr.ITALIC] == true,
        highlight = set[RichAttr.HIGHLIGHT] == true,
    )

    // 按连续相同属性集压缩回区间。
    var i = 0
    while (i < attrs.size) {
        var j = i + 1
        while (j < attrs.size && attrs[j] == attrs[i]) j++
        val s = spanOf(attrs[i])
        if (s.hasAny()) result.add(s.copy(start = ctx.a + i, end = ctx.a + j))
        i = j
    }

    val lines = ctx.doc.lines.toMutableList()
    lines[ctx.li] = target.copy(spans = normalizeSpans(result)).normalized()
    return RichDoc(lines)
}
