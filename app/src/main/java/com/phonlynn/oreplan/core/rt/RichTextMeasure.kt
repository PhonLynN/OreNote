package com.phonlynn.oreplan.core.rt

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * **一次测量**（2026-09-23 重写定稿）。
 *
 * # 这是整个重写的关键
 *
 * 展示端与编辑端共用**同一个** [TextLayoutResult]。两端拿到的行高、
 * 断行位置、缩进、符号位置不是「对齐」而是同一个值——不存在差异的可能。
 *
 * # 怎么拿到这个 layout
 *
 * `TextDelegate` / `MultiParagraphIntrinsics` / `TextLayoutResultProxy` 在
 * Compose 1.12 里都是 `internal`，应用层无法直接构造。但**用
 * `BasicText(onTextLayout = …)` 就能拿到它**——这正是 Compose 的
 * `Text` / `BasicTextField` 内部走的同一条排版管线。
 *
 * 所以：
 *
 * - 展示端：`BasicText(文本) { layout = it }`，符号层拿这个 layout 算位置
 * - 编辑端：`BasicTextField` 自己测量，同样通过 `onTextLayout` 把 layout 交出来
 *
 * # 缩进从哪来（重要）
 *
 * **不用段落样式**，而是文本流里的**前缀字符**（见 [RichTextMark]）。
 * 原因：Compose 是拿段落样式区间切段落的，只要用 `TextIndent` 做逐行缩进，
 * 就会有某个 `\n` 落在段落边界上，而**以 `\n` 结尾的段落会多排一个空行**
 *（曾经因此让整个白板的所有文字页行距翻倍）。
 *
 * 前缀是普通字符，所以整份文本只是一个段落，`\n` 只产生换行。
 *
 * 两端拿到的是**同一份输入**（同一个 `AnnotatedString` + 同一个 `TextStyle`
 * + 同一个宽度约束）经**同一个引擎**算出的结果。这不是「尽量对齐」，
 * 而是同一个函数对同一个输入求值的必然一致。
 */
object RichTextMeasure {

    /**
     * 把内容宽度转成排版约束。
     *
     * 高度不设限，让文本自然展开；展示端需要限行时用 `maxLines` 参数
     * 交给 `BasicText`（由它传给 `TextDelegate`，与编辑端同一路径）。
     */
    fun constraintsFor(widthPx: Int): Constraints = Constraints(
        minWidth = 0,
        maxWidth = widthPx.coerceAtLeast(0),
        minHeight = 0,
        maxHeight = Constraints.Infinity,
    )

    /** 便捷：直接算像素宽度的约束（用于测试与需要显式测量的场合）。 */
    fun constraintsForWidth(widthPx: Int): Constraints = constraintsFor(widthPx)

    /** 默认布局方向。RTL 未做支持，与项目其余部分一致。 */
    val DefaultLayoutDirection: LayoutDirection = LayoutDirection.Ltr
}

/**
 * 渲染缓存：落库字符串 → [RichDoc]。
 *
 * 卡片墙每张卡每次重组都会解析；相同文本复用上次结果即可。
 * [RichDoc] 与 [RichLine] 都是不可变数据，共享是安全的。
 */
object RichDocCache {
    private const val MAX = 256

    private val map = object : LinkedHashMap<String, RichDoc>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RichDoc>?): Boolean =
            size > MAX
    }

    /** 解析落库字符串（带缓存）。 */
    @Synchronized
    fun parse(raw: String?): RichDoc {
        val key = raw.orEmpty()
        map[key]?.let { return it }
        val doc = RichTextCodec.decode(key)
        map[key] = doc
        return doc
    }
}

/** 便捷：从落库字符串解析（走缓存）。 */
fun richDocOf(raw: String?): RichDoc = RichDocCache.parse(raw)

/**
 * 便捷：取一段落库字符串的**纯文字**（不带任何结构/格式）。
 *
 * 用途：搜索索引、摘要、导出等需要「只看文字」的地方。
 * 落在 JSON 结构字段（`v`/`lines`/`t`/`k`/`s`）上的内容绝不会混进来。
 *
 * > **通用规则**：只要不是把正文整个交给富文本渲染器，就必须先过这个函数。
 */
fun plainTextOf(raw: String?): String = RichDocCache.parse(raw).plainText

/**
 * 便捷：取一段落库字符串的纯文字并截断（用作卡片名/摘要）。
 *
 * 卡片没有标题时，显示正文开头作为标识——正文是 JSON，
 * 直接截断会显示成 `{"v":2,"lin`，所以必须先转纯文字。
 */
fun plainPreviewOf(raw: String?, maxChars: Int = 16): String =
    plainTextOf(raw).replace('\n', ' ').trim().take(maxChars)
