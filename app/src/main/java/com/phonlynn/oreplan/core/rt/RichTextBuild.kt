package com.phonlynn.oreplan.core.rt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/**
 * **显示文字的唯一构造入口**（2026-09-23 第三次重写定稿）。
 *
 * # 为什么推翻了「段落缩进」方案
 *
 * 上一版用 `TextIndent`（段落级样式）给列表行做缩进。它有一个**结构性**的致命问题：
 *
 * - Compose 的 `MultiParagraph` 是拿**段落样式区间**来切段落的，
 *   不是拿 `\n` 切；
 * - 于是每一行的样式区间必然让某个 `\n` 落在**段落边界**上；
 * - 而**以 `\n` 结尾的段落会多排出一个空行**。
 *
 * 结果：每个列表行后面凭空多一个空行（行距翻倍），
 * 用户看到的就是「整个白板所有文字页面全部崩坏」。
 *
 * 这个冲突无法调和：只要用段落样式做逐行缩进，就一定会产生空行。
 *
 * # 现在的做法：用**文本流里的不可见前缀**
 *
 * ```
 * 普通行： 正文
 * 列表行：[标记字符][宽度字符]正文
 * ```
 *
 * - **标记字符**：四个不可见字符，分别表示无序 / 有序 / 未勾选 / 已勾选。
 * - **缩进字符**：跟在标记字符后面的空白字符（全角空格 1em / EN SPACE 0.5em），
 *   **它们就是缩进本身** —— 用真实空白字符而不是 `letterSpacing`，
 *   因为实测后者在编辑框里不生效。
 *
 * 符号（圆点/方框/编号）不占文本流，由绘制层**画在实测缩进区的中点上**。
 *
 * 好处：
 *
 * 1. **没有段落样式** → 整份文本是**一个**段落 → `\n` 只是普通换行 →
 *    **不可能多出空行**。
 * 2. **文本流就是模型本身** —— 段类型与勾选态都能从文本解析出来，
 *    不需要任何「文档坐标 ↔ 显示坐标」的换算层。
 *    （前两次的编辑 bug 全部源于那个换算层。）
 * 3. 前缀是普通字符，所以**删除前缀 = 退出列表**，
 *    这是天然行为，不需要特判，也就不会「卡住」。
 *
 * # 落库仍然是干净的
 *
 * 前缀**不落库**：解析时剥掉，只存正文（见 [RichTextCodec]）。
 */
object RichTextMark {

    /**
     * ## 不可见的标记字符
     *
     * 四个字符分别表示无序 / 有序 / 未勾选 / 已勾选。
     *
     * 刻意选这些：**零宽、无字形、正常输入法打不出来**（不会与用户输入混淆）。
     * 它们的宽度由后面的**缩进字符**提供（见 [indentChars]）。
     */
    const val BULLET: Char = '\u200B'  // ZWSP
    const val ORDERED: Char = '\u200C' // ZWNJ
    const val CHECK_OFF: Char = '\u200D' // ZWJ
    const val CHECK_ON: Char = '\u2060' // WORD JOINER

    /**
     * ## 提供缩进宽度的不可见字符
     *
     * 全部是**空白字符**（看不见）+ **宽度固定**（不依赖字体的字宽猜测）：
     *
     * | 字符 | 宽度 |
     * | --- | --- |
     * | `U+3000` 全角空格 | 1em（= 1 个中文字符宽） |
     * | `U+2002` EN SPACE | 0.5em（半 个中文字符宽） |
     *
     * 为什么不用 `letterSpacing` 去“补”宽度：实测在编辑框里它**不生效**，
     * 导致缩进只有 1em 而不是想要的 1.5em。用真实的空白字符是确定的。
     */
    const val IDEOGRAPHIC_SPACE: Char = '\u3000'
    const val EN_SPACE: Char = '\u2002'

    /** 所有提供宽度的字符。 */
    val INDENT_CHARS: Set<Char> = setOf(IDEOGRAPHIC_SPACE, EN_SPACE)

    /**
     * 按当前 [RichLayout.IndentEm] 生成缩进字符。
     *
     * 支持 0.5 的整数倍（1.0 / 1.5 / 2.0 / 2.5…）：
     * 整数部分用全角空格，余下的 0.5 用 EN SPACE。
     */
    fun indentChars(): String {
        val em = RichLayout.IndentEm
        val full = kotlin.math.floor(em).toInt().coerceAtLeast(0)
        val half = (em - full) >= 0.45f
        return buildString(full + 1) {
            repeat(full) { append(IDEOGRAPHIC_SPACE) }
            if (half) append(EN_SPACE)
        }.ifEmpty { IDEOGRAPHIC_SPACE.toString() }
    }

    /** 全部标记字符，用于快速识别与清洗。 */
    val ALL: Set<Char> = setOf(BULLET, ORDERED, CHECK_OFF, CHECK_ON)

    /** 判断一个字符是不是标记字符。 */
    fun isMark(c: Char): Boolean = c in ALL

    /** 判断一个字符是不是缩进字符。 */
    fun isIndentChar(c: Char): Boolean = c in INDENT_CHARS

    /** 由段类型 + 勾选态得出标记字符；普通段落返回 null。 */
    fun markOf(kind: RichLineKind, checked: Boolean): Char? = when (kind) {
        RichLineKind.TEXT -> null
        RichLineKind.BULLET -> BULLET
        RichLineKind.ORDERED -> ORDERED
        RichLineKind.CHECK -> if (checked) CHECK_ON else CHECK_OFF
    }

    /** 由标记字符反推段类型与勾选态。 */
    fun kindOf(mark: Char): Pair<RichLineKind, Boolean>? = when (mark) {
        BULLET -> RichLineKind.BULLET to false
        ORDERED -> RichLineKind.ORDERED to false
        CHECK_OFF -> RichLineKind.CHECK to false
        CHECK_ON -> RichLineKind.CHECK to true
        else -> null
    }

    /** 一行的前缀字符数：标记字符 + 缩进字符。 */
    fun prefixLengthOf(kind: RichLineKind): Int =
        if (kind == RichLineKind.TEXT) 0 else 1 + indentChars().length

    /** 构造一行的前缀。 */
    fun prefixOf(kind: RichLineKind, checked: Boolean): String {
        val m = markOf(kind, checked) ?: return ""
        return m + indentChars()
    }

    /**
     * 剥掉文本里**混在正文中间**的标记字符（以及紧随其后的宽度字符）。
     *
     * ## 什么时候会出现中间的标记
     *
     * 最典型的是**行首退格合并两行**：
     * ```
     * 合并前： [标记][宽]甲 \n [标记][宽]乙
     * 删掉换行后：[标记][宽]甲 [标记][宽]乙
     * ```
     * 第二行的前缀就落到了正文中间。若不清理，它虽然不可见，
     * 但它的**宽度字符（全角空格）会留下一个肉眼可见的大空格**，
     * 而且会被当成正文写进库里（实测过：合并后存成 `甲　乙`）。
     *
     * 标记字符是正常输入法打不出来的，所以把它们当作「残留」直接剔除是安全的。
     */
    fun stripMarks(text: String): String {
        if (text.isEmpty()) return text
        if (text.none { isMark(it) }) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isMark(c)) {
                // 标记连同紧随其后的缩进字符一起去掉（它们是一对）。
                i++
                while (i < text.length && isIndentChar(text[i])) i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * 解析一行：得出段类型、勾选态、以及**去掉前缀后的正文**。
     *
     * 规则：必须是「标记字符 + 至少一个缩进字符」开头才算列表行。
     * 若只有标记字符、没有缩进字符（用户把缩进删了），
     * 视为普通行并把残留字符剥掉 —— 不让不可见字符留在正文里。
     */
    fun parseLine(raw: String): Triple<RichLineKind, Boolean, String> {
        if (raw.isNotEmpty() && isMark(raw[0]) && raw.length >= 2 && isIndentChar(raw[1])) {
            val parsedKind = kindOf(raw[0])
            if (parsedKind != null) {
                var i = 1
                while (i < raw.length && isIndentChar(raw[i])) i++
                return Triple(parsedKind.first, parsedKind.second, stripMarks(raw.substring(i)))
            }
        }
        if (raw.isNotEmpty() && isMark(raw[0])) {
            // 前缀残缺（缩进字符被删）→ 当作普通行，剥掉残留标记。
            return Triple(RichLineKind.TEXT, false, stripMarks(raw.substring(1)))
        }
        return Triple(RichLineKind.TEXT, false, stripMarks(raw))
    }
}

/**
 * 把整份文档构造成**显示用**的 `AnnotatedString`。
 *
 * 展示端与编辑端调的**都是它**，产物逐字符相同，所以两端不可能有差异。
 */
fun buildDisplayText(
    doc: RichDoc,
    baseStyle: TextStyle,
    symbolColor: Color,
    checkedColor: Color,
    highlightColor: Color = HighlightColor,
): AnnotatedString {
    val sb = StringBuilder()
    /** 每段正文在显示文字里的起点（前缀之后）。 */
    val contentStarts = ArrayList<Int>(doc.lines.size)

    doc.lines.forEachIndexed { index, line ->
        if (index > 0) sb.append('\n')
        sb.append(RichTextMark.prefixOf(line.kind, line.checked))
        contentStarts.add(sb.length)
        sb.append(line.text)
    }

    return buildAnnotatedString {
        append(sb.toString())

        doc.lines.forEachIndexed { index, line ->
            val contentStart = contentStarts[index]
            val prefixLen = RichTextMark.prefixLengthOf(line.kind)

            // 行内属性：五种各自 addStyle，互不覆盖。只作用于正文。
            val contentEnd = contentStart + line.text.length
            line.spans.forEach { sp ->
                val s = (contentStart + sp.start).coerceIn(contentStart, contentEnd)
                val e = (contentStart + sp.end).coerceIn(s, contentEnd)
                if (e <= s) return@forEach
                if (sp.bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
                if (sp.italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, e)
                when {
                    sp.underline && sp.strike -> addStyle(
                        SpanStyle(textDecoration = TextDecoration.Underline + TextDecoration.LineThrough),
                        s, e,
                    )

                    sp.underline -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
                    sp.strike -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), s, e)
                }
                if (sp.highlight) addStyle(SpanStyle(background = highlightColor), s, e)
            }

            // 3) 已勾选的复选框项：正文整体转浅灰。
            if (line.isCheckedItem() && contentEnd > contentStart) {
                addStyle(SpanStyle(color = checkedColor), contentStart, contentEnd)
            }
        }
    }
}

/**
 * 从显示文字**解析**出文档模型。
 *
 * 这是「文本即模型」的入口：段类型与勾选态全部来自前缀字符，
 * 正文则是剥掉前缀后的内容。**没有第二条真值来源**，
 * 所以不存在「文本和模型不同步」这一类 bug。
 */
fun parseDisplayText(text: String): RichDoc {
    val lines = text.split('\n').map { raw ->
        val (kind, checked, body) = RichTextMark.parseLine(raw)
        RichLine(text = body, kind = kind, checked = checked)
    }
    return RichDoc(lines.ifEmpty { listOf(RichLine("")) })
}

/**
 * 某一行的符号显示文字（仅用于无障碍/日志；符号本身是自绘的）。
 */
fun RichDoc.symbolTextAt(index: Int): String {
    val line = lines.getOrNull(index) ?: return ""
    return when (line.kind) {
        RichLineKind.TEXT -> ""
        RichLineKind.BULLET -> "项目符号"
        RichLineKind.ORDERED -> "${orderedNumberAt(this, index)}."
        RichLineKind.CHECK -> if (line.checked) "已勾选" else "未勾选"
    }
}

/**
 * 荧光笔高亮的背景色。
 *
 * 用柔和的暖黄色，在白底与软色卡片上都能看清，且不抢文字对比度。
 */
val HighlightColor: Color = Color(0xFFFFE9A8)
