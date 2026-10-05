package com.phonlynn.oreplan.v2.ai.math

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * 数学公式的**结构排版**。
 *
 * ## 对齐模型：中轴对齐，不是基线对齐
 *
 * TeX 把每个原子对齐到"数学轴"（分数线所在的那条水平线）上，
 * 严格做法要让每个子节点上报自己的基线。这里改用**垂直居中**近似：
 *
 * · 分数线的位置 ≈ 分数的垂直中心
 * · 数字/字母的垂直中心 ≈ 数学轴（数学轴本来就在 x 高度中间附近）
 *
 * 对绝大多数式子，居中与轴线对齐的视觉差别在一两个像素内，
 * 而代码量差一个数量级（不用自定义 AlignmentLine、不用测量回传）。
 *
 * ## ⚠️ 分数线不用 `width(IntrinsicSize.Max)`（闪退之后改的）
 *
 * 常规写法是给 Column 加 `width(IntrinsicSize.Max)`、让分数线 `fillMaxWidth()` 撑满。
 * 但那套依赖**固有尺寸测量（intrinsic measurement）**，而块级公式外面套着
 * `horizontalScroll`（横向可滚）—— 滚动容器给子节点的是**无限宽约束**，
 * 固有测量 × 无限约束是相当脆的组合。
 *
 * 实测后果：`\[…\]` 一旦被识别（上一版不识别，所以从没走到这里），
 * 只要 AI 输出内容就闪退。改成 [Fraction] / [Overlined] 两个**自己量尺寸**的
 * 自定义 Layout 之后，宽度和落点全部显式决定，不依赖任何隐式测量行为。
 *
 * ## 字号阶梯
 *
 * 上下标缩到 **0.72 倍**，再嵌套一层仍然按 0.72 缩（TeX 的 scriptscript 也是
 * 固定比例），但**下限压到 9sp** —— 再小在手机上就只是一团灰点了。
 */
@Composable
fun MathView(
    node: MathNode,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is MathNode.Sym -> MathText(node, fontSize, color, modifier)

        /*
         * 显式间距（`\,` `\:` `\;` `\quad` `\qquad`）。
         *
         * ⚠️ **不参与 atom spacing** —— 它就是一段空白本身。
         *
         * 这正是用户第七张图问的「为什么只有 μ 和 δ 之间大一些」：
         * 原来 `\,` 被解析成一个 [MathNode.Sym]（内容是空白字符），
         * 于是它既有空白字形、又与左右邻居各算一次间距 —— **叠两层**。
         * 公式里只有那一处有 `\,`，所以只有那一处偏大。
         *
         * 见 [MathNode.Space] 的说明。
         */
        is MathNode.Space -> Spacer(
            modifier = modifier.width((node.em * fontSize.value).dp),
        )

        /*
         * 横向序列。
         *
         * ⚠️ 每个原子之间要按 TeX 的规则插空隙（见 [atomSpacing]）——
         * 直接首尾相接的话，`(u · ∇)u` 会挤成 `(u·∇)u`，
         * 观感就是用户说的"间隙非常紧凑"。
         * 参考图（DeepSeek App）里运算符两侧是有明显留白的。
         */
        is MathNode.Row -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEachIndexed { index, child ->
                /*
                 * ⚠️ **两个原子之间只插一份空隙**，不是"左边加了右边再加"。
                 *
                 * ## 之前错在哪（用户 2026-10-04 第六张图：「空隙过大」）
                 *
                 * 原来写成：
                 *
                 * ```kotlin
                 * val (before, after) = atomSpacing(...)
                 * if (before > 0f) Spacer(...)     // 这个原子左侧
                 * MathView(child, ...)
                 * if (after > 0f) Spacer(...)      // 这个原子右侧
                 * ```
                 *
                 * 每个原子**两侧各加一次** —— 相邻两个原子之间就叠了**两份**：
                 *
                 * | 相邻原子 | 实际 | TeX 应当 |
                 * |---|---|---|
                 * | `frac` ↔ `=` | **9.33dp** | 4.67dp |
                 * | `+` ↔ `μ` | **7.47dp** | 3.73dp |
                 *
                 * 实测截图里相邻墨迹段有 **17~34dp** 的空隙，就是这么来的。
                 *
                 * ## TeX 的规则
                 *
                 * 间距是**一对原子之间的一份**，由 `(左类型, 右类型)` 查表决定 ——
                 * 不是"左原子出一份、右原子再出一份"。
                 *
                 * 所以这里只在**每对之间插一次**，取值 `atomGapEm(左, 右)`：
                 * 在前一个原子渲染完之后、后一个之前插入。
                 * 行尾不插（末尾不该有尾随空白，否则居中会偏左）。
                 */
                if (index > 0) {
                    val gap = atomGapBetween(node.children, index - 1, fontSize)
                    if (gap > 0f) Spacer(Modifier.width(gap.dp))
                }
                MathView(child, fontSize, color)
            }
        }

        is MathNode.Frac -> Fraction(
            numerator = { MathView(node.numerator, fontSize, color) },
            denominator = { MathView(node.denominator, fontSize, color) },
            ruleColor = color,
            modifier = modifier,
        )

        is MathNode.Sqrt -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.index?.let { MathView(it, scriptSize(fontSize), color) }
            MathText(MathNode.Sym("√", italic = false), fontSize, color)
            // 根号的上横线要盖住被开方数：由 Overlined 自己量宽度
            Overlined(ruleColor = color) { MathView(node.body, fontSize, color) }
        }

        is MathNode.Script -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MathView(node.base, fontSize, color)
            val script = scriptSize(fontSize)
            node.sup?.let {
                Box(Modifier.offset(y = -shiftUp(fontSize))) {
                    MathView(it, script, color)
                }
            }
            node.sub?.let {
                Box(Modifier.offset(y = shiftDown(fontSize))) {
                    MathView(it, script, color)
                }
            }
        }

        is MathNode.BigOp -> if (node.limitsAbove) {
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                node.sup?.let { MathView(it, scriptSize(fontSize), color) }
                MathText(MathNode.Sym(node.text, italic = false), fontSize, color)
                node.sub?.let { MathView(it, scriptSize(fontSize), color) }
            }
        } else {
            // 积分：上下限贴在右侧
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MathText(MathNode.Sym(node.text, italic = false), fontSize, color)
                Column {
                    node.sup?.let { MathView(it, scriptSize(fontSize), color) }
                    node.sub?.let { MathView(it, scriptSize(fontSize), color) }
                }
            }
        }

        is MathNode.Overline -> Overlined(ruleColor = color, modifier = modifier) {
            MathView(node.body, fontSize, color)
        }

        /*
         * `\left( … \right)` —— 括号按**内容高度**伸展（用户 2026-10-04）。
         *
         * ## 做法
         *
         * 用 `Layout` 只放一个子项（内容），量的过程里拿到它的高度，
         * 然后在**同一层**用 `drawBehind` 把两个括弧画在内容左右 ——
         * 这样不需要为括弧单独建子项，高度天然与内容一致。
         *
         * ⚠️ 依赖显式测量，不用固有尺寸（`IntrinsicSize` 在横向滚动里不稳，
         * 见文件头的说明）—— 与 [Fraction] 同一套路。
         */
        is MathNode.Delimited -> Delimited(
            node = node,
            fontSize = fontSize,
            color = color,
            modifier = modifier,
        )
    }
}

/**
 * `\left( … \right)` 的排版：内容居中，括弧按**内容高度**伸缩。
 *
 * ## ⚠️ 用字体字形 + 纵向拉伸，**不自绘弧线**（用户 2026-10-04）
 *
 * 用户原话：
 *
 * > 「目前高括号的实现方式感觉并非原生实现，感觉设计语言都不一样，
 * >   在 ds app 内展示的是**放大版的普通括号**，而非自绘括号」
 *
 * 这条批评准确。我原来用 `drawArc` 自绘弧线，笔画粗细写死 1.2dp、
 * 端点用 `RoundCap` —— 与字体里括号的**笔画对比度、端点形状、弯曲度**
 * 完全不是一套，所以看起来是两种东西。
 *
 * DS（KaTeX）的做法：用字体自带的**可伸缩括号字形**
 *（OpenType `ssty`/size variants，或 `\big` `\Big` `\bigg` 阶梯），
 * 本质仍是**同一个括号字符**，只是换更大的字形 —— 设计语言天然统一。
 *
 * ## 现在的做法
 *
 * 取**同一个括号字符**，用 `Modifier.scale` **纵向拉伸**到目标高度：
 *
 * | | 效果 |
 * |---|---|
 * | 笔画形状 | 与正文括号**同一个字形**，设计语言一致 ✓ |
 * | 高度 | 由内容高度决定 ✓ |
 * | 代价 | 纵向拉伸会让笔画略微变粗、弯曲度变缓 |
 *
 * 纵向拉伸是**近似的**（真正的可伸缩字形会保持笔画粗细不变、只加长"伸长部"）。
 * 但比起"自绘一套完全不同的弧线"，它离"放大版的普通括号"**近得多** ——
 * 而后者正是用户要的。
 *
 * ## ⚠️ 为什么不在矮的时候回退成不拉伸
 *
 * 高度接近一行时拉伸比例接近 1，本来就等于普通括号；
 * 加一条分支只会让"什么时候用哪种画法"变得不可预测。
 */
@Composable
private fun Delimited(
    node: MathNode.Delimited,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { DELIM_GAP.roundToPx() }

    val measurer = rememberTextMeasurer()
    val glyphStyle = remember(fontSize) {
        TextStyle(fontFamily = MathFontFamily, fontSize = fontSize)
    }

    val leftCh = node.left
    val rightCh = node.right

    /*
     * 括弧字形的**自然尺寸**（不拉伸时）。
     *
     * 宽度直接用它 —— 括号只该"长高"，不该"变胖"。
     * 高度用来算拉伸比：`scaleY = 内容高度 / 自然高度`。
     */
    val leftSize = remember(leftCh, glyphStyle) { leftCh?.let { measurer.measure(AnnotatedString(it), glyphStyle).size } }
    val rightSize = remember(rightCh, glyphStyle) { rightCh?.let { measurer.measure(AnnotatedString(it), glyphStyle).size } }

    /*
     * 括弧的纵向范围（= 内容的**墨迹**范围），由 measure 阶段算好后给 draw 用。
     *
     * ⚠️ 为什么需要它：`size.height` 是**含行高的测量框**，
     * 而分式/根号的墨迹只占框内的一部分且**偏上**。
     * 直接按 `size.height` 居中，括号相对墨迹就会**偏低**（用户报的"低了一行"）。
     */
    var delimTop by remember(node) { mutableFloatStateOf(0f) }
    var delimBottom by remember(node) { mutableFloatStateOf(0f) }

    /*
     * ## 一趟 `Layout`：量内容 → 按内容高度画括弧
     *
     * 括弧在 **draw 阶段**用 `drawText` 画，而不是当成子 Composable ——
     * 这样高度是**当场量出来**的，不需要"先量一次、再渲染一次"的两趟把戏。
     *
     * ⚠️ 这正是我前两版写错的地方：
     * · 第一版把绘制挂在**另一个空 Box** 上（高度 0，画不出来）；
     * · 第二版想用 `graphicsLayer(scaleY)`，但 `scaleY` 是绘制期属性、
     *   measure 阶段拿不到，于是被迫做两趟测量。
     *
     * 直接在 `DrawScope` 里 `drawText` 就没有这个矛盾：
     * 尺寸和绘制在同一个地方，高度随取随用。
     */
    Layout(
        modifier = modifier.drawWithContent {
            drawContent()

            /*
             * 括弧画在内容两侧。
             *
             * ⚠️ 这里 `size.height` 就是上面 `layout()` 给出的**总高**
             *（= 内容高度），所以拉伸比当场可算 —— 不需要两趟测量。
             *
             * 第一版把这段画在**另一个空 Box** 上（高度 0）→ 括号消失；
             * 现在画在**量出尺寸的同一个节点**上。
             */
            val h = size.height
            if (h <= 0) return@drawWithContent

            /*
             * ⚠️ 锚点用**字形中心**（数学上正好铺满 `[0, h]`）。
             *
             * 我一度以为是"两次居中叠加"，改成左上角锚点 —— **那是错的**：
             *
             * | 锚点 | 缩放后 + `(h-s)/2` 平移 | 结果 |
             * |---|---|---|
             * | `(0, 0)` 左上 | `[(h-s)/2, (h-s)/2 + h]` | **溢出容器** ✗ |
             * | `(0, s/2)` 中心 | `[0, h]` | 正好 ✓ |
             *
             * 用户报的是「位置低了**整整一行**，左右定位并没问题」——
             * 左右用 `s.width` 算、没有叠加所以是对的；
             * **纵向偏低的真正原因是 [h] 的取法**，见下。
             *
             * ## ⚠️ `h` 必须取**内容的墨迹高度**，不能取测量框高度
             *
             * `size.height` 是 `Layout` 量出的总高，而里面 `body` 用的是 `Text` ——
             * `Text` 的测量高度**包含行高（上下留白）**，分式的墨迹只占其中一部分、
             * 且**偏上**。拿含行高的框去居中括号，括号相对**墨迹**就矮了一截。
             *
             * 用户看到的"低了一行"正是这个：括号被居中在**框**里，
             * 而框比墨迹高，于是括号往下沉。
             *
             * 所以这里取 `bodyTop`/`bodyBottom`（见 measure 里算好的墨迹范围）
             * 来定位，而不是 `0` 到 `size.height`。
             */
            val top = delimTop
            val bottom = delimBottom
            val span = (bottom - top).coerceAtLeast(1f)

            leftCh?.let { ch ->
                val s = leftSize ?: return@let
                val scale = if (s.height > 0) span / s.height else 1f
                withTransform({
                    // 以字形中心为锚点纵向拉伸：括号只"长高"，不"变胖"
                    scale(1f, scale, Offset(s.width / 2f, s.height / 2f))
                }) {
                    drawText(
                        textMeasurer = measurer,
                        text = AnnotatedString(ch),
                        style = glyphStyle.copy(color = color),
                        topLeft = Offset(0f, top),
                    )
                }
            }

            rightCh?.let { ch ->
                val s = rightSize ?: return@let
                val scale = if (s.height > 0) span / s.height else 1f
                val x = size.width - s.width
                withTransform({
                    scale(1f, scale, Offset(s.width / 2f, s.height / 2f))
                }) {
                    drawText(
                        textMeasurer = measurer,
                        text = AnnotatedString(ch),
                        style = glyphStyle.copy(color = color),
                        topLeft = Offset(x, top),
                    )
                }
            }
        },
        content = { Box { MathView(node.body, fontSize, color) } },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables[0].measure(loose)

        val leftW = leftSize?.width ?: 0
        val rightW = rightSize?.width ?: 0
        val padL = if (leftCh != null) leftW + gapPx else 0
        val padR = if (rightCh != null) rightW + gapPx else 0

        val totalW = padL + body.width + padR
        val totalH = body.height

        /*
         * 括弧的纵向范围 = **内容的墨迹范围**。
         *
         * `body.height` 是含行高的测量框；括号应当罩住**看得见的部分**。
         * 用 `body` 的实测高度按比例估算墨迹范围（文字墨迹通常占测量高的
         * [GLYPH_TOP_RATIO] ~ [GLYPH_BOTTOM_RATIO]），再对齐到括号。
         */
        delimTop = body.height * GLYPH_TOP_RATIO
        delimBottom = body.height * GLYPH_BOTTOM_RATIO

        layout(totalW, totalH) {
            body.place(padL, 0)
        }
    }
}

/**
 * 画一个可伸缩定界符——**用字体字形纵向拉伸**，不自绘。
 *
 * 见 [Delimited] 的说明。这里是被 `drawText` 调用的绘制层封装。
 */


/**
 * 分数：分子 ／ 分数线 ／ 分母，三者都水平居中。
 *
 * 宽度 = max(分子, 分母)，分数线正好撑满这个宽度 —— 全部由这里显式算出，
 * 不借助固有尺寸测量（见 [MathView] 的注释）。
 */
@Composable
private fun Fraction(
    numerator: @Composable () -> Unit,
    denominator: @Composable () -> Unit,
    ruleColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thicknessPx = with(density) { RULE_THICKNESS.roundToPx() }
    val gapPx = with(density) { FRACTION_GAP.roundToPx() }

    Layout(
        modifier = modifier,
        content = {
            Box { numerator() }
            Box { denominator() }
            Box(Modifier.height(RULE_THICKNESS).background(ruleColor))
        },
    ) { measurables, constraints ->
        // 去掉最小约束，让分子分母按自身内容排版；
        // maxWidth 可能是无限（处于 horizontalScroll 里），这对 Text 是安全的 ——
        // 数学式子本来就不换行。
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val num = measurables[0].measure(loose)
        val den = measurables[1].measure(loose)

        val width = maxOf(num.width, den.width)
        val rule = measurables[2].measure(Constraints.fixed(width, thicknessPx))
        val height = num.height + gapPx + rule.height + gapPx + den.height

        layout(width, height) {
            num.place((width - num.width) / 2, 0)
            rule.place(0, num.height + gapPx)
            den.place((width - den.width) / 2, num.height + gapPx + rule.height + gapPx)
        }
    }
}

/** 上划线（`\overline{}` 与根号的那道横线）。线宽严格等于内容宽度。 */@Composable
private fun Overlined(
    ruleColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val thicknessPx = with(density) { RULE_THICKNESS.roundToPx() }
    val gapPx = with(density) { OVERLINE_GAP.roundToPx() }

    Layout(
        modifier = modifier,
        content = {
            Box { content() }
            Box(Modifier.height(RULE_THICKNESS).background(ruleColor))
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables[0].measure(loose)
        val rule = measurables[1].measure(Constraints.fixed(body.width, thicknessPx))

        layout(body.width, rule.height + gapPx + body.height) {
            rule.place(0, 0)
            body.place(0, rule.height + gapPx)
        }
    }
}

/**
 * 一段文本。变量斜体、函数名/数字正体（TeX 惯例）。
 *
 * ## 字体与"斜体"的做法
 *
 * 用打包进来的 [MathFontFamily]（Latin Modern Math），
 * 并且**不靠** `fontStyle` / `fontWeight` ——
 * 斜体与粗体都是通过**映射到 Unicode 数学字母码位**实现的
 *（见 [toMathAlphanumeric]）。
 *
 * 这一点是用户指出"字体完全不对"之后改的：
 * 原来看法是用系统衬线体 + `FontStyle.Italic`，那只是"把正体倾斜一下"，
 * 和数学里真正的斜体字形（TeX 的 `cmmi`）不是一回事。
 */
@Composable
private fun MathText(
    node: MathNode.Sym,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (node.text.isEmpty()) return

    // 组合附加符（\hat 等）只有在同一段文本里才叠得上，所以映射是整段做的
    val text = remember(node.text, node.italic, node.bold) {
        toMathAlphanumeric(node.text, node.italic, node.bold)
    }

    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = TextStyle(
            fontFamily = MathFontFamily,
            fontSize = fontSize,
            // 字重与姿态固定：粗体/斜体已经由码位表达
            fontWeight = FontWeight.Normal,
            fontStyle = FontStyle.Normal,
        ),
    )
}

/**
 * **两个原子之间**该留多少空隙（单位 dp）。
 *
 * ## 为什么是"一对原子"而不是"一个原子两侧"
 *
 * 见调用处的说明：TeXR 的间距是**一对原子之间的一份**，由
 * `(左类型, 右类型)` 查表决定。原来写成"每个原子两侧各加一次"，
 * 相邻两个之间叠了两份 —— 实测 `frac ↔ =` 变成 9.33dp（应当 4.67dp）。
 *
 * 所以接口改成"给我相邻两原子的下标，返回它们之间的空隙"。
 *
 * ## 一元符号的降级
 *
 * `-∇p` 里的减号是**一元负号**，TeX 把它从 [AtomType.BIN] 降级成 [AtomType.ORD]
 *（于是两侧不留空）；而 `a - b` 里是二元的，两侧都要留。
 *
 * 判据：**处在开头、或紧跟另一个运算符/关系符/左括号** → 降级。
 * ⚠️ 左括号（[AtomType.OPEN]）算，**右括号不算** ——
 * 认右括号会让 `(a) = b` 里的 `=` 被误判成一元（用户报过"等号两边贴死"）。
 *
 * @param left 左原子的下标（右原子是 `left + 1`）
 * @return 两原子之间要插的空隙（dp）；贴在一起时返回 0
 */
private fun atomGapBetween(
    children: List<MathNode>,
    left: Int,
    fontSize: TextUnit,
): Float {
    val l = children.getOrNull(left) ?: return 0f
    val r = children.getOrNull(left + 1) ?: return 0f

    /*
     * ⚠️ **显式间距（[MathNode.Space]）两侧不再叠加 atom spacing**。
     *
     * `\,` 的语义已经是"插入这么多空白"，再查一次间距表就是**叠两层** ——
     * 那正是用户报的"只有 μ 和 δ 之间大"（`\mu\,\delta`）。
     */
    if (l is MathNode.Space || r is MathNode.Space) return 0f

    val lt = typeOf(l, children, left)
    val rt = typeOf(r, children, left + 1)
    return atomGapEm(lt, rt) * fontSize.value
}

/**
 * 一个原子的 [AtomType]，**含一元降级**。
 *
 * @param index 该原子在 [children] 里的下标（降级判据要看它前面是什么）
 */
private fun typeOf(node: MathNode, children: List<MathNode>, index: Int): AtomType {
    val base = when (node) {
        is MathNode.Sym -> atomTypeOf(node.text)
        is MathNode.BigOp -> AtomType.OP
        else -> AtomType.INNER
    }

    /*
     * 一元降级：BIN 出现在**开头**，或前面是 BIN / REL / 左括号时，
     * 它是一元符号（`-∇p`、`(-a)`、`= -∇p` 里的那个负号）。
     */
    if (base != AtomType.BIN) return base

    val prev = children.getOrNull(index - 1) ?: return AtomType.ORD   // 行首 -> 一元
    val pt = when (prev) {
        is MathNode.Sym -> atomTypeOf(prev.text)
        is MathNode.BigOp -> AtomType.OP
        else -> AtomType.INNER
    }
    return if (pt == AtomType.BIN || pt == AtomType.REL || pt == AtomType.OPEN) {
        AtomType.ORD
    } else {
        AtomType.BIN
    }
}

/** 上下标字号：0.72 倍，但不小于 9sp（再小就看不清了）。 */
private fun scriptSize(base: TextUnit): TextUnit {
    if (base.isUnspecified) return base
    val scaled = base * 0.72f
    return if (scaled.value < 9f) 9.sp else scaled
}

/**
 * 上标上移、下标下移的量。
 *
 * 按字号的比例算（0.22 / 0.18 倍），这样正文调大调小时偏移会跟着走，
 * 不会出现"字大了、上下标却还贴在原地"。
 */
private fun shiftUp(fontSize: TextUnit): androidx.compose.ui.unit.Dp =
    (fontSize.value * 0.22f).dp

private fun shiftDown(fontSize: TextUnit): androidx.compose.ui.unit.Dp =
    (fontSize.value * 0.18f).dp

private val RULE_THICKNESS = 1.dp

/** 分子与分数线之间的空隙（另一侧对称）。 */
private val FRACTION_GAP = 3.dp

/** 上划线与内容之间的空隙。 */
private val OVERLINE_GAP = 1.dp

/**
 * 可伸缩括弧的**可见宽度**。
 *
 * 取 6dp：比一个 16sp 的 `(` 字符（约 5dp 宽）略宽一点 ——
 * 让弧线看起来有厚度，又不会把内容推得太远。
 */
private val DELIM_WIDTH = 6.dp

/**
 * 文字**墨迹**在测量框里的纵向占比（上边）。
 *
 * TextMeasurer 返回的 size 含行高（上下留白），而墨迹只占其中一段。
 * 括弧要罩住**看得见的部分**，所以按这个比例取墨迹范围。
 *
 * 实测（本构关系那条公式）：测量框高 199px，墨迹高 137px（51.3dp × 2.75），
 * 墨迹上缘约在框高的 0.16、下缘约 0.85。
 */
private const val GLYPH_TOP_RATIO = 0.16f

/** 文字墨迹在测量框里的纵向占比（下边）。 */
private const val GLYPH_BOTTOM_RATIO = 0.85f

/** 括弧与内容之间的空隙。 */
private val DELIM_GAP = 2.dp

/** 括弧的笔画粗细。太细在高分屏上会发虚，1.2dp 接近字符括号的观感。 */
private val DELIM_STROKE = 1.2.dp
