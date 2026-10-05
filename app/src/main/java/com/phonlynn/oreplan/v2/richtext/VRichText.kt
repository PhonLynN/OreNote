package com.phonlynn.oreplan.v2.richtext

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.phonlynn.oreplan.core.rt.RichDoc
import com.phonlynn.oreplan.core.rt.buildDisplayText
import com.phonlynn.oreplan.core.rt.richDocOf
import com.phonlynn.oreplan.core.rt.toggleCheck
import com.phonlynn.oreplan.v2.theme.VColors

/**
 * 富文本**展示**组件（2026-09-23 第二次重写定稿）。
 *
 * # 与编辑端共用同一个内核
 *
 * 三件必须一致的东西两端都从这里取：
 *
 * | | 取值 |
 * | --- | --- |
 * | 显示文字 | [buildDisplayText]（唯一构造入口，**只有正文与换行**） |
 * | 段落缩进 | 同一个 `TextIndent`（列表段 1.5em） |
 * | 字体样式 | 调用方传入的 `TextStyle`，编辑端传同一个对象 |
 * | 测量 | `BasicText` → 内部 `TextDelegate`（与 `BasicTextField` 同一引擎） |
 *
 * # 结构：文本 + 符号层
 *
 * ```
 * Box {
 *   BasicText(显示文字)      ← 排版、缩进、折行全在这里
 *   RichSymbolLayer(...)    ← 自绘圆点/编号/复选框，位置来自上者的 getBoundingBox
 * }
 * ```
 *
 * 符号层叠在文本层上、**不占文本流**，所以编辑行为不受它影响。
 */
@Composable
fun VRichText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = VColors.ink,
    /** **最多显示几段**（不是几行）。按段落截断用这个。 */
    maxLines: Int = Int.MAX_VALUE,
    /**
     * **最多显示几个视觉行**（真正按行截断）。
     *
     * 与 [maxLines] 的区别很重要：一个没有任何换行的长段落是**一段**，
     * 用 [maxLines] 永远截不掉它（用户 2026-09-27 报告的现象：多少字都不截断）。
     * 卡片预览那种「最多 N 行」的需求必须用本参数。
     */
    lineLimit: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    /**
     * 按行截断时底部的渐隐遮罩（详见 [RichTextFadeMask]）。
     * 行数没到 [RichTextFadeMask.fromLine] 时自动不画。
     */
    fadeMask: RichTextFadeMask? = null,
    /** 符号颜色（圆点/编号/复选框）。 */
    symbolColor: Color = VColors.ink2,
    /** 已勾选的复选框项，其正文转浅灰。 */
    checkedColor: Color = VColors.ink3,
    /** 复选框勾选回调。传了才可点。 */
    onTextChange: ((String) -> Unit)? = null,
) {
    val doc = remember(text) { richDocOf(text) }
    RichTextBlock(
        doc = doc,
        style = style,
        color = color,
        maxLines = maxLines,
        lineLimit = lineLimit,
        overflow = overflow,
        fadeMask = fadeMask,
        symbolColor = symbolColor,
        checkedColor = checkedColor,
        modifier = modifier,
        onToggleCheck = onTextChange?.let { cb -> { i -> cb(doc.toggleCheck(i).encode()) } },
    )
}

/** 展示组件的主体：一次测量 + 文本层 + 自绘符号层。 */
@Composable
internal fun RichTextBlock(
    doc: RichDoc,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    lineLimit: Int = Int.MAX_VALUE,
    overflow: TextOverflow,
    fadeMask: RichTextFadeMask? = null,
    symbolColor: Color,
    checkedColor: Color,
    modifier: Modifier = Modifier,
    onToggleCheck: ((Int) -> Unit)? = null,
) {
    val shown = doc.lines.size.coerceAtMost(maxLines)
    if (shown <= 0) return

    // 只对要显示的前若干段构造显示文字；后面的段根本不参与测量。
    val visibleDoc = remember(doc, shown) {
        if (shown == doc.lines.size) doc else RichDoc(doc.lines.take(shown))
    }
    val displayText = remember(visibleDoc, style, symbolColor, checkedColor) {
        buildDisplayText(visibleDoc, style, symbolColor, checkedColor)
    }
    val drawStyle = remember(style, color) { style.copy(color = color) }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val items = rememberSymbolItems(
        doc = visibleDoc,
        layout = layout,
        style = style,
        symbolColor = symbolColor,
    )

    Box(
        modifier
            .fillMaxWidth()
            // 复选框点击拦截挂在**容器**上，只在真的点中复选框时消费事件；
            // 其余情况一律放行 —— 否则卡片点击会被吃掉，进不了聚焦浮层。
            .richCheckBoxTaps(items = items, style = style, onToggleCheck = onToggleCheck),
    ) {
        BasicText(
            text = displayText,
            modifier = Modifier.fillMaxWidth(),
            style = drawStyle,
            maxLines = lineLimit,
            overflow = overflow,
            onTextLayout = { layout = it },
        )
        RichSymbolLayer(
            items = items,
            style = style,
            symbolColor = symbolColor,
            modifier = Modifier.matchParentSize(),
        )
        // 渐隐遮罩画在**本块内部**、符号层之后：
        //  · 被本块边界裁剪 → 结构上不可能盖到图片/标签（它们在同级的其它块里）；
        //  · 位置来自排版结果的**真实行几何**（不是「行高 × 7」估算），行高不等也准；
        //  · 与卡片高度、卡片底部无关：永远钉在第 fromLine 行。
        val l = layout
        if (fadeMask != null && l != null && l.lineCount > 0 && l.multiParagraph.didExceedMaxLines) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        // 底部一整块：从倒数第 lines 行的顶边，到下边界的最后一行底边。
                        val last = l.lineCount - 1
                        val first = (l.lineCount - fadeMask.lines).coerceAtLeast(0)
                        val topY = l.getLineTop(first)
                        val bottomY = l.getLineBottom(last)
                        if (bottomY > topY) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(fadeMask.color.copy(alpha = 0f), fadeMask.color),
                                    startY = topY,
                                    endY = bottomY,
                                ),
                                topLeft = Offset(0f, topY),
                                size = Size(size.width, bottomY - topY),
                            )
                        }
                    },
            )
        }
    }
}

/**
 * 正文被按行截断时，**底部那一整块**渐隐遮罩（用户 2026-09-27 定稿）。
 *
 * 语义就一条：在正文区底部画一块 [lines] 行高、从**透明渐变到 [color]（卡片底色）**
 * 的遮罩 —— 和 Tab 栏下方的渐隐是同一件事，不做「第几行到第几行」的分段。
 *
 * 效果自然成立：
 *  · 倒数第二行落在遮罩的上半段（透明度较高）→ 只是略微被压住；
 *  · 最后一行落在下半段（越往下越不透明）→ 下半部分被盖掉。
 *
 * 只在正文**确实被截断**时才画（[TextLayoutResult.didExceedMaxLines]）：
 * 文字没截断的卡片，下面本来就没有被藏起来的内容，不需要提示。
 */
data class RichTextFadeMask(
    /** 遮罩颜色 = 卡片底色（淡出到它，视觉上等于「文字淡出」）。 */
    val color: Color,
    /** 遮罩高度（行数）。默认两行。 */
    val lines: Int = 2,
)


