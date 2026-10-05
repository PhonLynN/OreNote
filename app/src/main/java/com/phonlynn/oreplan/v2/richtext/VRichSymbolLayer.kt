package com.phonlynn.oreplan.v2.richtext

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.core.rt.RichDoc
import com.phonlynn.oreplan.core.rt.RichLayout
import com.phonlynn.oreplan.core.rt.RichTextMark
import com.phonlynn.oreplan.core.rt.RichLineKind
import com.phonlynn.oreplan.core.rt.lineStartGlobal
import com.phonlynn.oreplan.core.rt.symbolTextAt
import com.phonlynn.oreplan.v2.theme.VColors

/**
 * **列表符号的自绘层**（2026-09-23 第二次重写定稿）。
 *
 * # 为什么自己画
 *
 * 上一版用字体字形（`●` / `☐` / `☑`），有两个无法接受的问题：
 *
 * 1. **尺寸不可控**：字形的实心圆接近 0.9em，用户反馈「真的太大」；换字体就变样。
 * 2. **垂直位置不可控**：字形自带 baseline 偏移，对齐取决于字体度量。
 *
 * 自己画之后，直径、圆角、线宽、对勾形状全部由 [RichLayout] 决定，与字体无关。
 *
 * ---
 *
 * # 🎛 要改排版参数？
 *
 * 全部在 **`core/rt/RichLayout.kt`**，本文件只是把那些常量**用**出来。
 * 对应关系（改参数时按这张表定位）：
 *
 * | 本文件里的位置 | 对应参数 |
 * | --- | --- |
 * | `indentPx` 的计算 | `RichLayout.IndentEm`（列表整体右移多少） |
 * | `centerX` 的计算 | **不用调**：自动取实测缩进区的中点 |
 * | `SymbolItem.Dot` 的 `diameterPx` | `RichLayout.BulletDiameterEm`（圆点大小） |
 * | `SymbolItem.Check` 的 `sizePx` | `RichLayout.CheckBoxSizeEm`（方框大小） |
 * | `drawCheckBox` 的 `corner` | `RichLayout.CheckBoxCornerRatio`（方框圆角） |
 * | `drawCheckBox` 的 `stroke` | `RichLayout.CheckBoxStrokeRatio`（方框描边） |
 * | `drawCheckMark` 的 `inset` | `RichLayout.CheckMarkInsetRatio`（对勾大小，**反向**） |
 * | `drawCheckMark` 的 `stroke` | `RichLayout.CheckMarkStrokeRatio`（对勾粗细） |
 * | `drawCheckMark` 的 `p1/p2/p3` | `RichLayout.CheckMarkP1X` … `P3Y`（对勾形状） |
 * | `buildSymbolItems` 里编号的字号 | `RichLayout.OrderFontScale`（编号大小） |
 *
 * ---
 *
 * # 位置怎么定（关键）
 *
 * 拿该行**首字符的真实位置**，而不是估算：
 *
 * - 横向：`getBoundingBox(段首).left` 就是正文左边缘（排版引擎算出来的）。
 *   缩进由前缀里的空白字符提供，所以这个值是 `容器左边 + 实际缩进宽度`。
 * - 符号中心 = `正文左边缘 / 2` = **缩进区中点**（取实测值，不依赖常量）。
 * - 纵向：与首字符的字形盒**垂直居中**。
 *
 * ## ⚠️ 空列表项要兜底
 *
 * 空段的段首字符是换行符，`getBoundingBox` 可能给出零宽盒（甚至 left = 0）。
 * 直接拿来算会把符号画到容器外面去。所以宽度无效时，
 * 用**已知的缩进量**当正文左边缘（这个值是确定的，不依赖测量）。
 *
 * # 实现要点：只用一层 Canvas
 *
 * 整个符号层是**一个** `Canvas`，所有符号在 `DrawScope` 里按绝对坐标绘制。
 * 不嵌套 Box/offset —— 那样每个符号都会成为独立布局节点，
 * 位置变化会反馈回布局，既慢又容易形成环。
 */
@Composable
internal fun RichSymbolLayer(
    items: List<SymbolItem>,
    style: androidx.compose.ui.text.TextStyle,
    symbolColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    val minStrokePx = with(density) { RichLayout.CheckBoxMinStrokeDp.dp.toPx() }

    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxSize()) {
            items.forEach { item ->
                when (item) {
                    is SymbolItem.Dot -> drawCircle(
                        color = symbolColor,
                        radius = item.diameterPx / 2f,
                        center = item.center,
                    )

                    is SymbolItem.Check -> drawCheckBox(
                        center = item.center,
                        size = item.sizePx,
                        checked = item.checked,
                        color = symbolColor,
                        accent = VColors.accent,
                        minStrokePx = minStrokePx,
                    )

                    is SymbolItem.Order -> {
                        val measured = measure.measure(
                            androidx.compose.ui.text.AnnotatedString(item.text),
                            style.copy(
                                color = symbolColor,
                                fontSize = style.fontSize * RichLayout.OrderFontScale,
                            ),
                        )
                        // 有基线就用基线对齐（编号与正文坐在同一条基线上）；
                        // 没有（异常情况）才退回盒子居中。
                        val top = item.baselineY
                            ?.let { base -> base - measured.getLineBaseline(0) }
                            ?: (item.center.y - measured.size.height / 2f)
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(item.center.x - measured.size.width / 2f, top),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 命中测试：点击位置是否落在某个复选框上。
 *
 * 命中区放大到 1.6 倍边长（复选框本身不大，直接用它的尺寸会点不中，
 * 用户曾反馈过「勾不上」）。
 */
private fun hitTestCheck(
    items: List<SymbolItem>,
    position: Offset,
    fontPx: Float,
): Int? {
    val pad = fontPx * RichLayout.CheckBoxSizeEm * 0.8f
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    items.forEach { item ->
        if (item !is SymbolItem.Check) return@forEach
        val half = item.sizePx / 2f + pad
        if (
            position.x >= item.center.x - half && position.x <= item.center.x + half &&
            position.y >= item.center.y - half && position.y <= item.center.y + half
        ) {
            // 取最靠近的（重叠时优先中心更近的）。
            val d = (position - item.center).getDistance()
            if (d < bestDistance) {
                best = item.lineIndex
                bestDistance = d
            }
        }
    }
    return best
}

/** 一个符号的绘制指令（组合期算好，绘制期只画）。 */
internal sealed interface SymbolItem {
    val center: Offset

    data class Dot(override val center: Offset, val diameterPx: Float) : SymbolItem

    data class Check(
        override val center: Offset,
        val sizePx: Float,
        val checked: Boolean,
        /** 该复选框对应第几段（点击回写用）。 */
        val lineIndex: Int,
    ) : SymbolItem

    data class Order(
        override val center: Offset,
        val text: String,
        /** 正文基线（相对本层坐标）。编号按它对齐，null 时退回居中。 */
        val baselineY: Float? = null,
    ) : SymbolItem
}

/**
 * 由排版结果算出全部符号的绘制指令。
 *
 * 抽成纯函数（除测量外无副作用），便于单测「符号位置是否落在缩进区中点」。
 */
private fun buildSymbolItems(
    doc: RichDoc,
    tl: TextLayoutResult,
    style: androidx.compose.ui.text.TextStyle,
    symbolColor: Color,
    fontPx: Float,
    /** 复选框相对文字中心的垂直下移量（px）；编号不受影响。 */
    checkOffsetPx: Float = 0f,
    /** 圆点相对文字中心的垂直下移量（px）。 */
    dotOffsetPx: Float = 0f,
): List<SymbolItem> {
    val out = ArrayList<SymbolItem>(doc.lines.size)
    val textLen = tl.layoutInput.text.length
    // 限行时（BasicText.maxLines / lineLimit）排版只覆盖前若干行，末行之后的段落
    // **没有排版**：硬查它们的行号会落到末行（或第 0 行），画出不属于任何文字的
    // 幽灵符号。用「末行的结束偏移」当闸门，超出就整段跳过。
    // 未限行时末行结束偏移 = 全文长度，行为与以前完全一致。
    val lastVisibleOffset = if (tl.lineCount > 0) {
        runCatching { tl.getLineEnd(tl.lineCount - 1, visibleEnd = true) }.getOrDefault(textLen)
    } else {
        0
    }

    var lineStart = 0
    doc.lines.forEachIndexed { index, line ->
        val prefixLen = RichTextMark.prefixLengthOf(line.kind)
        val contentStart = lineStart + prefixLen
        val lineDisplayLen = prefixLen + line.text.length + 1 // + 换行符

        if (RichLayout.hasSymbol(line.kind) && contentStart <= lastVisibleOffset) {
            val textLenSafe = contentStart.coerceAtMost(textLen)
            val lineIdx = runCatching { tl.getLineForOffset(textLenSafe) }.getOrDefault(0)

            // ---- 横向：正文左边缘 ----
            //
            // ## 为什么用 `getHorizontalPosition` 而不是 `getBoundingBox`
            //
            // `getBoundingBox(首字符).left` 包含**字形自身的左侧边距**（side bearing），
            // 所以它会比真正的正文起点偏右 1~2px。空行没有字形、只能用标称值兜底，
            // 于是「空行 → 输入第一个字」时符号会**抖一下**（实测用户报告过）。
            //
            // `getHorizontalPosition` 返回的是**光标**在该位置的 x ——
            // 它就是正文的起点，不含字形边距，**空行也有光标**，
            // 所以空行与非空行用的是同一个口径 → 不会跳。
            val indentPx = fontPx * RichLayout.IndentEm
            val textLeft = runCatching {
                tl.getHorizontalPosition(textLenSafe, usePrimaryDirection = true)
            }.getOrNull()?.takeIf { it > 0f } ?: indentPx

            // ---- 纵向 ----
            //
            // 圆点与复选框：与**首字符字形盒**垂直居中（视觉最准）。
            // 空行没有字形盒，退回用该排版行的中点。
            val glyphBox = if (line.text.isNotEmpty()) {
                runCatching { tl.getBoundingBox(textLenSafe) }.getOrNull()
            } else {
                null
            }
            val centerY = if (glyphBox != null && glyphBox.height > 0f) {
                (glyphBox.top + glyphBox.bottom) / 2f
            } else {
                (tl.getLineTop(lineIdx) + tl.getLineBottom(lineIdx)) / 2f
            }

            // 符号中心 = **缩进区中点**（缩进区 = [容器左边 0, 正文左边缘]）。
            // 取实测值而不是固定偏移，所以改缩进量、遇到字体宽度差异都不会偏。
            val centerX = textLeft / 2f
            val center = Offset(centerX, centerY)

            when (line.kind) {
                RichLineKind.BULLET -> out.add(
                    SymbolItem.Dot(
                        // 圆点统一下移（见 RichLayout.DotOffsetDp）；命中测试用同一份 center。
                        center = center.copy(y = center.y + dotOffsetPx),
                        diameterPx = fontPx * RichLayout.BulletDiameterEm,
                    ),
                )

                RichLineKind.CHECK -> out.add(
                    SymbolItem.Check(
                        // 复选框统一下移（见 RichLayout.CheckBoxOffsetDp）；命中测试用同一份 center。
                        center = center.copy(y = center.y + checkOffsetPx),
                        sizePx = fontPx * RichLayout.CheckBoxSizeEm,
                        checked = line.checked,
                        lineIndex = index,
                    ),
                )

                RichLineKind.ORDERED -> out.add(
                    SymbolItem.Order(
                        center = center,
                        text = doc.symbolTextAt(index),
                        // 编号按**基线**对齐（与正文坐在同一条基线上）。
                        //
                        // 不能像圆点那样「把字体盒子居中」：数字字形没有下伸部，
                        // 而字体盒子包含 descent，居中的结果是数字整体**偏上**
                        //（用户实测反馈「有序列表符号微微偏上」）。
                        // 基线对齐既是排版惯例，视觉上也正好落在文字中间。
                        baselineY = runCatching { tl.getLineBaseline(lineIdx) }.getOrNull(),
                    ),
                )

                RichLineKind.TEXT -> Unit
            }
        }

        lineStart += lineDisplayLen
    }
    return out
}

/**
 * 自绘复选框。
 *
 * 「自己画」的全部意义在这里：圆角、描边、对勾形状都是显式参数，
 * 不依赖任何字体字形，所以**放大字号不会变形**。
 */
private fun DrawScope.drawCheckBox(
    center: Offset,
    size: Float,
    checked: Boolean,
    color: Color,
    accent: Color,
    minStrokePx: Float,
) {
    val left = center.x - size / 2f
    val top = center.y - size / 2f
    val corner = size * RichLayout.CheckBoxCornerRatio
    val radius = CornerRadius(corner, corner)

    if (checked) {
        // 勾选态：实心填充 + 白色对勾。
        drawRoundRect(
            color = accent,
            topLeft = Offset(left, top),
            size = Size(size, size),
            cornerRadius = radius,
        )
        drawCheckMark(left = left, top = top, size = size, color = Color.White, minStrokePx = minStrokePx)
    } else {
        // 未勾选态：较浅的描边方框。
        val stroke = (size * RichLayout.CheckBoxStrokeRatio).coerceAtLeast(minStrokePx)
        drawRoundRect(
            color = color.copy(alpha = 0.55f),
            topLeft = Offset(left, top),
            size = Size(size, size),
            cornerRadius = radius,
            style = Stroke(width = stroke),
        )
    }
}

/**
 * 在方框内画一个对勾（两条线段）。
 *
 * 折点全部由 [RichLayout] 的比例参数决定；[RichLayout.CheckMarkInsetRatio]
 * 保证笔画完全落在框内，否则会溢出圆角、与未勾选态的描边不吻合。
 */
private fun DrawScope.drawCheckMark(
    left: Float,
    top: Float,
    size: Float,
    color: Color,
    minStrokePx: Float,
) {
    val inset = size * RichLayout.CheckMarkInsetRatio
    val l = left + inset
    val t = top + inset
    val w = size - inset * 2f

    val p1 = Offset(l + w * RichLayout.CheckMarkP1X, t + w * RichLayout.CheckMarkP1Y)
    val p2 = Offset(l + w * RichLayout.CheckMarkP2X, t + w * RichLayout.CheckMarkP2Y)
    val p3 = Offset(l + w * RichLayout.CheckMarkP3X, t + w * RichLayout.CheckMarkP3Y)

    val stroke = (size * RichLayout.CheckMarkStrokeRatio).coerceAtLeast(minStrokePx)
    drawLine(color, p1, p2, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, p2, p3, strokeWidth = stroke, cap = StrokeCap.Round)
}


// ================================================================
// 符号元素的计算 与 复选框点击拦截
// ================================================================

/**
 * 计算当前文档需要绘制的全部符号。
 *
 * 抽成 composable 是为了让**调用方**也能拿到同一份几何
 *（点击命中必须与绘制用同一份数据，才不会「看着点到了却没反应」）。
 */
@Composable
internal fun rememberSymbolItems(
    doc: RichDoc,
    layout: TextLayoutResult?,
    style: androidx.compose.ui.text.TextStyle,
    symbolColor: Color,
): List<SymbolItem> {
    val tl = layout ?: return emptyList()
    val density = LocalDensity.current
    val fontPx = with(density) { style.fontSize.toPx() }
    if (fontPx <= 0f) return emptyList()
    val checkOffsetPx = with(density) { RichLayout.CheckBoxOffsetDp.dp.toPx() }
    val dotOffsetPx = with(density) { RichLayout.DotOffsetDp.dp.toPx() }
    return remember(tl, doc, style, symbolColor, fontPx, checkOffsetPx, dotOffsetPx) {
        buildSymbolItems(doc, tl, style, symbolColor, fontPx, checkOffsetPx, dotOffsetPx)
    }
}

/**
 * 复选框点击拦截（挂在**外层容器**上）。
 *
 * ## 为什么必须挂在容器上，而不是符号层自己
 *
 * 符号层铺满整块文字区。若它自己挂 `pointerInput`，`detectTapGestures`
 * 会**吃掉所有点击**，导致：
 *
 * - 白板卡片：点文字区进不了聚焦浮层（点击被吞）；
 * - 编辑页：点文字区无法把光标放到正文（`BasicTextField` 收不到点击），
 *   光标一直停在标题上。
 *
 * ## 挂在容器上 + 只在命中复选框时才消费
 *
 * 用 [PointerEventPass.Initial] 在**父节点**观察按下事件：
 * - 命中复选框 → `consume()` 并回调（于是卡片不会进聚焦、光标不会乱跳）；
 * - 未命中 → **什么都不做** → 事件照常传给子节点
 *（文本框聚焦、卡片点击都能正常工作）。
 */
internal fun Modifier.richCheckBoxTaps(
    items: List<SymbolItem>,
    style: androidx.compose.ui.text.TextStyle,
    onToggleCheck: ((Int) -> Unit)?,
): Modifier {
    if (onToggleCheck == null) return this
    return this.pointerInput(items, style, onToggleCheck) {
        val fontPx = style.fontSize.toPx()
        awaitEachGesture {
            // 不要求 down 未被消费：文本框自己也会消费 down（聚焦 / 落光标），
            // 我们只判断「这一次手势是不是落在复选框上的轻点」。
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            val hit = hitTestCheck(items, down.position, fontPx) ?: return@awaitEachGesture
            // 命中复选框：等它抬起。
            val up = waitForUpOrCancellation(pass = PointerEventPass.Main) ?: return@awaitEachGesture
            // 只认**轻点**：长按（选词）与拖动（拖手柄选范围）都不切换、也不消费 ——
            // 那些手势属于框架的选区，抢走就会出现「长按一下整段被选中」「范围乱跳」。
            val heldMs = up.uptimeMillis - down.uptimeMillis
            val moved = (up.position - down.position).getDistance()
            if (heldMs > viewConfiguration.longPressTimeoutMillis || moved > viewConfiguration.touchSlop) {
                return@awaitEachGesture
            }
            up.consume()
            onToggleCheck(hit)
        }
    }
}
