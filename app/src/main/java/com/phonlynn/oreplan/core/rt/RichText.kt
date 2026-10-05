package com.phonlynn.oreplan.core.rt

/**
 * **富文本文档模型**（2026-09-23 重写定稿）。
 *
 * # 设计取向：只有一个模型，一个测量，两个绘制
 *
 * 旧实现（`core/rich/`）在同一份模型上跑了**两种互不相容的排版机制**：
 *
 * | | 展示端 | 编辑端 |
 * | --- | --- | --- |
 * | 结构 | 逐段一个 `Text`，段间靠 `Column` 间距 | 单一 `BasicTextField` 文本流 |
 * | 行距来源 | `Column` 的 `LineGapDp` | `\n` 自带的整行行高 |
 * | 缩进来源 | 文本的 `Modifier.padding` | 同左（整框 padding） |
 * | 符号 | 绘制层叠在左槽位 | `onTextLayout` 反查后 `offset` 叠画 |
 *
 * 两套机制的行盒模型先天不同——**编辑端列表行距偏大是结构性的，不是参数没调对**。
 * 这就是「怎么修都出 bug」的根源：修好一处必然弄坏另一处。
 *
 * 本版的做法是让两端**共用同一个 `TextLayoutResult`**（见 [RichTextMeasure]），
 * 且**列表符号回归文本流**（见 [RichLineKind] 的说明）。
 * 于是两端不是「对齐」，而是**同一个值**，不存在差异的可能。
 *
 * # 两种文字口径
 *
 * - **纯正文（content）**：[RichLine.text]。落库就是它，**不含任何结构符号**。
 * - **显示文字（display）**：文本流里每段带符号前缀与 0 宽空格。由 [RichTextBuild] 构造。
 *
 * 两者的映射只存在于编解码与构造/编辑两层，模型本身始终是纯正文。
 *
 * # 不变式（所有编辑函数都必须维持）
 *
 * 1. `lines` 至少有一个元素（空文档 = 一个空段）。
 * 2. 每个 [RichSpan] 满足 `0 <= start < end <= text.length`，且 [RichSpan.hasAny] 为真。
 * 3. 同一段内 `spans` 按 `start` 升序、**互不重叠**，相邻同属性区间已合并。
 * 4. `checked` 只在 [RichLineKind.CHECK] 段上有意义。
 *
 * 纯 Kotlin、无 Compose 依赖，便于单测。
 */

/** 段类型。 */
enum class RichLineKind {
    /** 普通段落。 */
    TEXT,

    /** 无序列表项。 */
    BULLET,

    /** 有序列表项（编号按位置算，不落库）。 */
    ORDERED,

    /** 复选框项。 */
    CHECK,
}

/** 行内属性。 */
enum class RichAttr { BOLD, UNDERLINE, STRIKE, ITALIC, HIGHLIGHT }

/**
 * 行内样式区间：五种属性互相独立、可任意组合。
 *
 * 为什么用「多个单属性区间」而不是「一个带 5 个布尔的多属性区间」：
 * 同一段文字同时加粗 + 下划线时，两次操作各自只改自己那一位。
 * 若合并成一个区间，第二次操作会覆盖掉第一次的属性。
 */
data class RichSpan(
    /** 起始字符下标（含），口径是 [RichLine.text] 的纯正文。 */
    val start: Int,
    /** 结束字符下标（不含）。 */
    val end: Int,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val italic: Boolean = false,
    /** 荧光笔高亮背景。 */
    val highlight: Boolean = false,
) {
    val length: Int get() = (end - start).coerceAtLeast(0)

    val isEmpty: Boolean get() = end <= start

    fun hasAny(): Boolean = bold || underline || strike || italic || highlight

    /** 是否与另一区间的**属性**完全相同（位置无关，用于合并相邻段）。 */
    fun sameAttrs(other: RichSpan): Boolean =
        bold == other.bold && underline == other.underline &&
            strike == other.strike && italic == other.italic &&
            highlight == other.highlight

    /** 本区间与 [other] 属性相同且相接/重叠时并入；否则返回 null。 */
    fun mergeIfSame(other: RichSpan): RichSpan? =
        if (sameAttrs(other) && start <= other.end && other.start <= end) {
            copy(start = minOf(start, other.start), end = maxOf(end, other.end))
        } else {
            null
        }

    /** 裁到 `[from, to)` 范围内；完全在外则返回 null。 */
    fun clamp(from: Int, to: Int): RichSpan? {
        val s = maxOf(start, from)
        val e = minOf(end, to)
        return if (e <= s) null else copy(start = s, end = e)
    }

    /** 整体平移 [delta]。 */
    fun shift(delta: Int): RichSpan = copy(start = start + delta, end = end + delta)
}

/**
 * 一段：纯正文 + 段类型 + 勾选态 + 行内样式区间。
 *
 * [text] **绝不含列表符号**（`•` / `n.` / `☐`）也**绝不含 0 宽空格**——
 * 它们只存在于 [RichTextBuild] 构造出的显示文字里。
 */
data class RichLine(
    val text: String = "",
    val kind: RichLineKind = RichLineKind.TEXT,
    /** 复选框是否勾选（仅 [RichLineKind.CHECK] 有意义）。 */
    val checked: Boolean = false,
    /** 行内样式区间：按 start 升序、互不重叠。 */
    val spans: List<RichSpan> = emptyList(),
) {
    /**
     * 取覆盖 [index] 处的**合并后**属性。
     *
     * 注意：同一位置可能存在多个区间（同时加粗 + 下划线时两种属性各存一个区间），
     * 所以必须把覆盖该位置的所有区间取并集，不能只取第一个——否则看起来就是
     * 「加了第二个属性、第一个属性反而没了」。
     */
    fun spanAt(index: Int): RichSpan? {
        var bold = false
        var underline = false
        var strike = false
        var italic = false
        var highlight = false
        var found = false
        spans.forEach { sp ->
            if (index >= sp.start && index < sp.end && sp.hasAny()) {
                found = true
                bold = bold || sp.bold
                underline = underline || sp.underline
                strike = strike || sp.strike
                italic = italic || sp.italic
                highlight = highlight || sp.highlight
            }
        }
        if (!found) return null
        return RichSpan(index, index + 1, bold, underline, strike, italic, highlight)
    }

    /** 该位置是否带某种属性。 */
    fun hasAttr(index: Int, attr: RichAttr): Boolean {
        val sp = spanAt(index) ?: return false
        return when (attr) {
            RichAttr.BOLD -> sp.bold
            RichAttr.UNDERLINE -> sp.underline
            RichAttr.STRIKE -> sp.strike
            RichAttr.ITALIC -> sp.italic
            RichAttr.HIGHLIGHT -> sp.highlight
        }
    }

    // 便捷查询（单属性）。语义与 [hasAttr] 相同，读起来更短。
    fun isBoldAt(index: Int): Boolean = hasAttr(index, RichAttr.BOLD)
    fun isUnderlineAt(index: Int): Boolean = hasAttr(index, RichAttr.UNDERLINE)
    fun isStrikeAt(index: Int): Boolean = hasAttr(index, RichAttr.STRIKE)
    fun isItalicAt(index: Int): Boolean = hasAttr(index, RichAttr.ITALIC)
    fun isHighlightAt(index: Int): Boolean = hasAttr(index, RichAttr.HIGHLIGHT)

    /** 把样式区间裁到文字长度内并重新规整（改文字后必须调用）。 */
    fun normalized(): RichLine {
        val n = text.length
        val clamped = spans.mapNotNull { sp ->
            val s = sp.start.coerceIn(0, n)
            val e = sp.end.coerceIn(0, n)
            if (e <= s || !sp.hasAny()) null else sp.copy(start = s, end = e)
        }
        return copy(spans = normalizeSpans(clamped))
    }

    /** 便捷：按属性列表给整段套样式。 */
    fun withAttrs(start: Int, end: Int, attrs: Set<RichAttr>): RichLine {
        if (end <= start || attrs.isEmpty()) return this
        val extra = attrs.map { it.toSpan(start, end) }
        return copy(spans = normalizeSpans(spans + extra)).normalized()
    }
}

/** 富文本文档：有序的段列表。 */
data class RichDoc(val lines: List<RichLine>) {

    /** 纯正文（各段用 `\n` 连接，**不含任何结构符号**）。 */
    val plainText: String get() = lines.joinToString("\n") { it.text }

    val textLength: Int get() = plainText.length

    val lineCount: Int get() = lines.size

    /**
     * 是否「完全空白、可视为尚未开始输入」。
     *
     * 与 `plainText.isEmpty()` 的区别：列表符号属于显示层，不进纯正文。
     * 所以一行设为列表后 `plainText` 仍是空串，但它已经不是「空白段落」——
     * 输入提示应当消失。
     */
    val isBlank: Boolean
        get() = lines.size == 1 &&
            lines[0].kind == RichLineKind.TEXT &&
            lines[0].text.isEmpty()

    /** 是否含任何非普通段落（用于决定要不要走结构化渲染）。 */
    val hasStructure: Boolean get() = lines.any { it.kind != RichLineKind.TEXT }

    /** 序列化为落库字符串。 */
    fun encode(): String = RichTextCodec.encode(this)

    companion object {
        val EMPTY = RichDoc(listOf(RichLine("")))

        /** 从落库字符串解析（含旧格式与纯文本兜底）。 */
        fun decode(raw: String?): RichDoc = RichTextCodec.decode(raw)

        /** 由纯文本构造（每行一段、无格式）。 */
        fun ofText(text: String): RichDoc = RichDoc(text.split('\n').map { RichLine(it) })
    }
}

/**
 * 规整样式区间：排序 → 丢弃空/无属性 → 合并相邻同属性。
 *
 * 这是维持模型不变式的唯一入口，所有编辑操作末尾都要经过它。
 */
fun normalizeSpans(spans: List<RichSpan>): List<RichSpan> {
    val valid = spans
        .filter { !it.isEmpty && it.hasAny() }
        .sortedWith(compareBy({ it.start }, { it.end }))
    val out = ArrayList<RichSpan>(valid.size)
    for (sp in valid) {
        val merged = out.lastOrNull()?.mergeIfSame(sp)
        if (merged != null) {
            out[out.size - 1] = merged
        } else {
            out.add(sp)
        }
    }
    return out
}

/** 把属性转成一个覆盖 `[start, end)` 的区间。 */
internal fun RichAttr.toSpan(start: Int, end: Int): RichSpan = when (this) {
    RichAttr.BOLD -> RichSpan(start, end, bold = true)
    RichAttr.UNDERLINE -> RichSpan(start, end, underline = true)
    RichAttr.STRIKE -> RichSpan(start, end, strike = true)
    RichAttr.ITALIC -> RichSpan(start, end, italic = true)
    RichAttr.HIGHLIGHT -> RichSpan(start, end, highlight = true)
}

/** 该段是否为「已勾选的复选框项」（渲染时文字变浅灰）。 */
fun RichLine.isCheckedItem(): Boolean = kind == RichLineKind.CHECK && checked
