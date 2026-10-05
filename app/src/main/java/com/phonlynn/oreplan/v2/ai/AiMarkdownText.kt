package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.ai.math.MathFontFamily
import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.MathView
import com.phonlynn.oreplan.v2.ai.math.flatten
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText

/*
 * =====================================================================================
 *  AI 回复的 Markdown 渲染 —— 架构说明（改代码前先读这一段）
 * =====================================================================================
 *
 * ## 一、为什么要重写解析器
 *
 * 老实现是"**先按行预处理、把粘连的标记拆成新行，再按行判定块类型**"：
 *
 * ```
 * splitGluedBlockMarkers() → splitLineAtGluedMarkers() → splitTrailingRule()
 *   → splitHeadingAndBody()/sentenceCut() → splitInlineBullets()
 *   → splitInlineOrdered() → consumeIndentedContinuation()
 * ```
 *
 * 这些年一层层加上去的补丁各自对付一种"模型没换行"的写法。它们的共同前提是
 * **块标记只可能出现在行首**（`trimmed.startsWith("#")` 之类），
 * 而模型的真实输出违反这个前提，于是每遇到一种新写法就再加一层补丁。
 *
 * 真正的原因是：**块判定不该锚在行首**。
 *
 * 现在改成**游标扫描器**（[MarkdownParser]）：游标可以在**任何位置**停下 ——
 * 行首、行中间都行。一段正文/一条列表项一直读到"下一个块从这里开始"为止，
 * 于是 `…至今未解##常见简化`、`方程组- **注意**`、`工程计算---` 这些
 * **不需要任何预处理**就自然分行，上面那一串补丁全部删除。
 *
 * ## 二、行内与块级的分隔符必须**同一套**
 *
 * 老实现里 `$$` 是"块级只在行首、行内主动排除"的 —— 模型把 `$$…$$`
 * 写在句子中间时两条路都走不到，于是**原样露出**一串 LaTeX。
 * 现在 `$$` / `\[` / `\(` 三种写法在**行内**都认（见 [appendInlineMarkup]），
 * 块级与行内不再各自维护一张表。
 *
 * ## 三、强调里面的标记要**递归解析**
 *
 * `**$\mathbf{u}$**流速场` 这种写法在老实现里只消费掉 `**`、把里面的
 * `$\mathbf{u}$` 原样抄进结果 —— 用户看到的是裸的美元号和反斜杠命令。
 * 现在强调 / 链接 / 删除线的**内部会再走一遍行内扫描**（[appendInlineMarkup] 的 `depth`）。
 *
 * ## 四、⚠️ 这个文件**不允许出现正则**
 *
 * 曾经的 `Regex("^\\begin\{([^}]*)}")` 在真机上让 `<clinit>` 抛
 * `PatternSyntaxException`（Android 用 ICU 正则，它不接受孤立的 `}`），
 * 表现是"只要 AI 输出内容就闪退"，而 **JVM 单测全是绿的**。
 *
 * 所以这里一律手写字符扫描：没有引擎差异，也没有这类地雷。
 * 这条约定由单测 `MarkdownRendererInvariantsTest` 守着。
 * =====================================================================================
 */

/**
 * 把 AI 回复的 Markdown 渲染成 Compose 组件。
 *
 * 支持范围（对话场景实际会出现的那些）：
 *
 * · 块级：标题 1–6、无序列表、有序列表、任务清单 `- [ ]`、表格、分隔线、
 *   代码围栏（带语言标记）、引用、块级公式 `$$…$$` / `\[…\]` / `\begin{env}`
 * · 行内：`**粗体**`、`*斜体*`、`` `代码` ``、`~~删除线~~`、`[文字](链接)`、
 *   图片 `![说明](地址)`、行内公式 `$…$` / `\(…\)` / `\[…\]` / `$$…$$`
 *
 * 列表支持缩进（两个空格或一个 Tab 一级），最多渲染三级。
 *
 * ## 行内公式为什么是"压平"的
 *
 * 正文是一整段 `AnnotatedString`，而 `AnnotatedString` 里塞不进 composable ——
 * 上下叠的分数需要真的嵌套布局。所以行内公式走 [flatten]（`\frac{a}{b}` → `a/b`），
 * 只有**块级**公式走 [MathView] 的结构排版。
 *
 * 这也是行内公式的通行写法：行内的分数本来就习惯写成 `a/b`。
 *
 * @param baseStyle 正文样式。标题、代码块的字号都由它**按倍数推导** ——
 *   写死绝对值的话，调用方一改基准字号就会有某一档明显不搭。
 */
@Composable
fun AiMarkdownText(
    markdown: String,
    baseStyle: TextStyle = AiTypo.body,
    color: Color = VColors.ink,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }

    /*
     * 段间距的基准：按字号推导（0.8 倍 ≈ 12.8dp）。
     *
     * 行距来自 lineHeight —— 正文 16sp / 1.50 ⇒ 行间空白约 8sp。
     * 段间距**必须明显大于行距**，否则段落之间看起来跟换行没区别，整段文字糊成一片。
     * 具体倍数见 [gapBefore] 的表。
     */
    val unit = with(LocalDensity.current) { baseStyle.fontSize.toDp() }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(Modifier.height(gapBefore(blocks[index - 1], block, unit)))
            }

            when (block) {
                /*
                 * 标题。
                 *
                 * ⚠️ 必须走 [InlineText]（会跑 `renderInline`），不能直接 `VText(block.text)` ——
                 * 后者只接受 `String`、不解析行内标记，标题里的 `**对流项**` 会原样露出
                 * （用户第五张截图报的就是这个：列表里是粗体、标题里是星号）。
                 *
                 * 标题本身已经是 SemiBold，所以那层粗体不再额外加粗 ——
                 * 我们要的只是"标记被吃掉"。
                 *
                 * 字号按正文的**倍数**推导（取自设计稿 19 / 17 / 15，正文 15）——
                 * 写死绝对值的话，正文一改，三级标题就会比正文还小。
                 */
                /*
                 * 标题。
                 *
                 * ⚠️ 必须走 [InlineText]（会跑 `renderInline`），不能直接 `VText(block.text)` ——
                 * 后者只接受 `String`、不解析行内标记，标题里的 `**对流项**` 会原样露出。
                 *
                 * ## 字号倍数按 DS 实测重定（用户 2026-10-04）
                 *
                 * 用户原话：「大标题字体大小和普通字体**一致**，没有层次感」。
                 *
                 * 旧的倍数是 `1.27 / 1.13 / **1.00**` —— 三级标题（`###`）
                 * **正好等于正文**，所以 `### 三、可压缩的一般形式` 看起来和正文一样大。
                 *
                 * 像素实测（同宽 1011px 双端截图，行带高度）：
                 *
                 * | | 正文 | 标题各档 |
                 * |---|---|---|
                 * | DS | 38 px | **49 / 50 / 52 / 53 / 57 px** |
                 * | 我们（改前） | 39 px | 只有 63 px 一档 |
                 *
                 * DS 的标题是正文的 **1.29 ~ 1.50 倍**，而且**分好几档**；
                 * 我们只有一档，且 `###` 完全没放大。
                 *
                 * 现在按 DS 的比例定：
                 *
                 * | 级别 | 倍数 | 16sp 下 |
                 * |---|---|---|
                 * | `#` | 1.50 | 24sp |
                 * | `##` | 1.35 | 21.6sp |
                 * | `###` | 1.20 | 19.2sp |
                 * | `####` 及更深 | 1.10 | 17.6sp |
                 */
                is Block.Heading -> InlineText(
                    text = block.text,
                    style = baseStyle.copy(
                        fontSize = baseStyle.fontSize * when (block.level) {
                            1 -> 1.50f
                            2 -> 1.35f
                            3 -> 1.20f
                            else -> 1.10f
                        },
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = color,
                )

                is Block.Bullet -> BulletRow(
                    indent = block.indent,
                    marker = bulletMarker(block.indent),
                    style = baseStyle,
                ) {
                    InlineText(block.text, baseStyle, color, Modifier.weight(1f))
                }

                is Block.Ordered -> BulletRow(
                    indent = block.indent,
                    marker = block.marker,
                    style = baseStyle,
                ) {
                    InlineText(block.text, baseStyle, color, Modifier.weight(1f))
                }

                is Block.Task -> BulletRow(indent = block.indent, marker = "", style = baseStyle) {
                    TaskCheckbox(block.checked)
                    InlineText(
                        block.text,
                        baseStyle,
                        if (block.checked) VColors.ink3 else color,
                        Modifier.weight(1f),
                        strike = block.checked,
                    )
                }

                /*
                 * 代码块：长行**横向滚动**，不折行。
                 *
                 * 代码折行会把缩进和语句结构弄乱，读起来比截断还糟。
                 * 放进 `horizontalScroll` 之后子节点拿到无限宽约束，`Text` 就不会软换行。
                 *
                 * 内容**不做任何 Markdown 解析**（代码里的 `**` 是运算符，不是粗体），
                 * 所以这里用 `VText` 而不是 `InlineText`。
                 */
                is Block.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(VColors.surface2, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        // 语言标记（```kotlin）不显示 —— 对话里它只是噪音
                        VText(
                            block.text,
                            baseStyle.copy(
                                fontFamily = FontFamily.Monospace,
                                // 等宽体比正文略小一档（设计稿 13 / 正文 15 ≈ 0.87）
                                fontSize = baseStyle.fontSize * 0.87f,
                            ),
                            color = color,
                        )
                    }
                }

                is Block.Quote -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    /*
                     * ⚠️ `height(IntrinsicSize.Min)` + 竖线 `fillMaxHeight()`：
                     * 让竖线**跟着引用正文的高度走**，多行引用才是一条贯通的线。
                     * 写死 18dp 的话，两行以上的引用只有第一行旁边有竖线。
                     */
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(VColors.line, RoundedCornerShape(1.dp)),
                    )
                    InlineText(block.text, baseStyle, VColors.ink3, Modifier.weight(1f))
                }

                /*
                 * 分隔线。
                 *
                 * ⚠️ **上下留白要"格外"大**（用户明确要求）：
                 * 分隔线的语义是"这里换了一个话题"，留白不够就只是"一条多余的线"。
                 * 30dp（自身）+ 两侧块间距（约 16dp）= 每侧约 46dp。
                 */
                Block.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 30.dp)
                        .height(1.dp)
                        .background(VColors.line),
                )

                is Block.Table -> MarkdownTable(block, baseStyle, color)

                /*
                 * 块级公式。
                 *
                 * ## 窄了就横向滚动，**绝不折行**
                 *
                 * 数学式子折行是错的：分数线会被从中间拆开、根号会断开。
                 *
                 * ## 为什么还要 BoxWithConstraints
                 *
                 * 光是套一层 `horizontalScroll` 的话，子节点拿到无限宽约束，
                 * 短的公式会被顶到最左边、不再居中。
                 * 先用 `widthIn(min = 视口宽)` 把内容撑到至少一屏宽，里面再居中 ——
                 * 短公式居中、长公式滚动，两边都对。
                 *
                 * ## ⚠️ 上下留白按**实测**给足（用户 2026-10-04）
                 *
                 * 用户原话：「ds在由大标题分割的块之间给了非常大的间隙」。
                 *
                 * 像素实测（两张**同宽 1011px** 的截图，可直接比）：
                 *
                 * | | DS | OreNote（改前） |
                 * |---|---|---|
                 * | 块间距最大档 | **227.5 px** | **162.5 px** |
                 *
                 * 差 **65px ≈ 24dp**。而公式是"块间距最大档"的主要来源，
                 * 所以这里从 `6.dp` 提到 `[MATH_BLOCK_VERTICAL]`。
                 */
                is Block.Math -> {
                    val tree = remember(block.latex) { MathParser.parse(block.latex) }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MATH_BLOCK_VERTICAL),
                    ) {
                        val viewport = maxWidth
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.widthIn(min = viewport),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (tree != null) {
                                    MathView(tree, baseStyle.fontSize * 1.05f, color)
                                } else {
                                    // 解析不出来时老实显示原文，不猜
                                    VText(block.latex, baseStyle, color = VColors.ink3)
                                }
                            }
                        }
                    }
                }

                is Block.Paragraph -> InlineText(block.text, baseStyle, color)
            }
        }
    }
}

/**
 * 块级公式的**上下留白**。
 *
 * ## 保持 6dp —— 我一度改成 24dp，那是**改错了对象**
 *
 * 用户指出「大标题分割的块之间间隙非常大」，指的是**标题前后**的间距，
 * 我却去改了公式留白（还振振有词地写在注释里说是"优先级更高"）。
 * 用户当场纠正：「大标题本身的前后留白你不动反而动我没提过的公式留白？」
 *
 * 公式**自身**的上下内边距本来就不是问题所在 ——
 * 真正错的是 [gapBefore] 里"标题之前"被定成了最小档（1.3 倍），
 * 而实测 DS 里它是**最大档**（是下方留白的近 3 倍）。
 * 那一处已按实测修正；这里因此**回到 6dp**。
 *
 * 教训：用户点名的是哪个元素，就改哪个元素。
 * 拿"我量到的另一个数字"去替换用户明确指出来的位置，是自作主张。
 */
private val MATH_BLOCK_VERTICAL = 6.dp

/** 列表项：它们彼此之间要用**小间距**，否则清单会散开。 */
private val Block.isListItem: Boolean
    get() = this is Block.Bullet || this is Block.Ordered || this is Block.Task

/**
 * 无序列表的符号，**按缩进级别换**。
 *
 * 三个级别都用同一个 `•` 的话，层级完全看不出来 ——
 * 而模型缩进得很随意，符号是唯一的视觉线索。
 */
private fun bulletMarker(indent: Int): String = when (indent.coerceIn(0, 2)) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

/**
 * 两个块之间的间距（**额外留白**，不含行高本身）。
 *
 * ## ⚠️ 这张表曾经是**错的**，而且错在最关键的一档（用户 2026-10-04）
 *
 * 用户原话：
 *
 * > 「ds在由大标题分割的块之间给了**非常大**的间隙，
 * >   而 orenote 不同大标题分割的空隙高度甚至**低于大标题到下方第一行**的高度」
 *
 * 旧表把「标题之前」定成 **1.3**（最小档），理由是"标题前后要克制"——
 * 那是**我的直觉，不是量出来的**（旧注释却写着"从截图量出来的"，是假的）。
 *
 * 用户给了同宽（1011px）的双端截图后实测：
 *
 * | 位置 | DS | 我们（改前） |
 * |---|---|---|
 * | 标题**上方** | **160 px** | 177 px |
 * | 标题**下方** | **54 px** | 158 px |
 * | 上/下 比 | **2.94** | 1.12 |
 *
 * **DS 的标题上方留白是下方的近 3 倍**；我们几乎一样大（1.12），
 * 所以"大标题分割的块之间"看不出分隔。
 *
 * 换算成 dp（density ≈ 2.3）：标题上方 ≈ 70dp、下方 ≈ 23dp。
 *
 * ## 现在的取值（按实测反推）
 *
 * | 场景 | 倍数 | 16sp 下约 | 依据 |
 * |---|---|---|---|
 * | **标题之前** | **4.2** | **67dp** | 实测 DS 160px ≈ 70dp |
 * | 标题之后 | 1.4 | 22dp | 实测 DS 54px ≈ 23dp |
 * | 段落 ↔ 段落 | 1.9 | 30dp | 保持（上一轮量过，与 DS 接近） |
 * | 列表项 ↔ 列表项 | 0.8 | 13dp | 保持（清单要紧凑） |
 *
 * **标题之前必须是最大的一档** —— 那是"这里换了一个大章节"的视觉信号，
 * 也正是用户说的"非常大的间隙"。
 *
 * ⚠️ `internal` 而不是 `private`：单测要钉的正是**层级关系**
 *（"标题之前必须是最大档"），那是排版意图本身，见 `BlockSpacingOrderTest`。
 */
internal fun gapBefore(prev: Block, next: Block, unit: Dp): Dp = when {
    // 标题之前：最大档（实测 DS 是下方留白的近 3 倍）
    next is Block.Heading -> unit * 4.2f
    // 标题之后：收紧，让标题与它管的内容贴在一起
    prev is Block.Heading -> unit * 1.4f
    prev.isListItem && next.isListItem -> unit * 0.8f
    else -> unit * 1.9f
}

/**
 * 列表行的公共外形：缩进 + 标记（•／1.／空） + 内容。
 *
 * ## ⚠️ 标记的宽度是**固定**的，不随文字走
 *
 * `•` 在 16sp 下的**字形框**（advance）实测约 20dp，而它的**墨迹**只有约 3.6dp ——
 * 两侧各留了约 8dp 空白。于是 `spacedBy(8.dp)` 的实际视觉效果是：
 *
 * ```
 * 圆点墨迹 ──[8.4dp 字形空白]──[8dp 间隔]── 文字   = 实测真机 16.4dp
 * ```
 *
 * 那个 8.4dp 空白**由字体决定**：换字体、换字号都会变。用固定值去补偿它
 * 等于把字体度量写死进代码，字体一换就错。
 *
 * 所以给标记一个**固定宽度槽**：不管字形多宽都塞进同一个槽，
 * 槽右缘到文字的距离由 [BULLET_MARKER_GAP] 显式决定。
 * 三级缩进的标记墨迹大小不同，但**占位一致** —— 正文左缘对齐在同一条竖线上。
 *
 * ⚠️ **槽宽必须接近墨迹宽**，否则槽内会再留出一片空白。
 * 我一度把它设成 18dp，结果间距反而从 16.4dp 涨到 20.4dp（用户当场发现）。
 * 现在 [BULLET_MARKER_SLOT] 取 10dp —— 刚好容下 `•` 的墨迹（3.6dp）
 * 和两位序号（`10.`），又不额外制造空白。
 */
@Composable
private fun BulletRow(
    indent: Int,
    marker: String,
    style: TextStyle,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(BULLET_MARKER_GAP),
        modifier = Modifier
            .fillMaxWidth()
            // 每级缩进 16dp，最多三级（再深在手机上会挤成一条缝）
            .padding(start = (indent.coerceIn(0, 2) * 16).dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (marker.isNotEmpty()) {
            Box(
                modifier = Modifier.width(BULLET_MARKER_SLOT),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 标记用**正文样式**：写死 AiTypo.body 的话，调用方一改基准字号就会不搭
                VText(marker, style, color = VColors.ink3)
            }
        }
        content()
    }
}

/**
 * 列表标记占的**固定宽度槽**。
 *
 * ⚠️ 这个值**不能大**：槽内如果比墨迹宽出很多，那部分就成了新的空白。
 * 取 **10dp** —— 约等于 `•` 的墨迹（3.6dp）加一点余量，
 * 也刚好容得下 `1.` 这类短序号（`10.` 会被裁掉一点，但那是罕见情况）。
 *
 * 教训：我第一版取 18dp，把间距从 16.4dp 改到了 20.4dp —— **越改越糟**。
 */
private val BULLET_MARKER_SLOT = 10.dp

/**
 * 标记与正文之间的间距。
 *
 * 保持在 **8dp**：既然标记已经被收进 10dp 的窄槽里，
 * 槽右缘（≈墨迹右缘）到文字的距离就是这里写的值 —— 视觉上约 8dp，
 * 比改前的 16.4dp 明显收拢，也不会挤在一起。
 */
private val BULLET_MARKER_GAP = 8.dp

/** 任务清单的方框：对勾用字形，不引图标（列表里图标会显得很重）。 */
@Composable
private fun TaskCheckbox(checked: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .padding(top = 3.dp)
            .size(15.dp)
            .clip(shape)
            .background(if (checked) VColors.accent else Color.Transparent)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, VColors.ink3, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            VText("✓", AiTypo.body.copy(fontSize = 11.sp), color = Color.White)
        }
    }
}

/**
 * 表格。
 *
 * ## 宽度不够时**横向滚动**，不把每格压扁
 *
 * 按 `Modifier.weight()` 分配宽度在气泡里必然碎掉：气泡 350dp、4 列 →
 * 每格约 74dp，中文 16sp 每字约 16dp，**一行只放得下四五个字**，
 * 于是一个正常的对比表会变成一片换行，看起来就像"表格根本没渲染"。
 *
 * 现在：先按内容估一个**最小宽度**，放得下就按比例铺满，放不下就让整张表横向滚动 ——
 * 表格的可读性来自**列对齐**，压扁会同时毁掉对齐和内容。
 *
 * ## 对齐：认 `:---` / `:--:` / `---:`
 *
 * CommonMark 用分隔行里的冒号表达对齐。丢了的话数字列（对比表里最常见的）看起来会歪。
 *
 * 外观：**无外框、每行一条分割线**（用户口径：「外面的框显得多余」）。
 * 行间距归零，由每行自己的上下内边距撑开 —— 线的位置才落在两行正中间。
 */
@Composable
private fun MarkdownTable(block: Block.Table, baseStyle: TextStyle, color: Color) {
    val columns = maxOf(
        block.header.size,
        block.rows.maxOfOrNull { it.size } ?: 0,
    ).coerceAtLeast(1)

    val aligns = (0 until columns).map { column ->
        block.alignments.getOrNull(column) ?: TableAlign.START
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth

    /*
     * 每一列的宽度 = **该列所有行里最宽的那一格**，然后整列统一使用。
     *
     * 先把每一列的全部单元格摊平（表头 + 各数据行），
     * 交给 [tableColumnWidthsMeasured] 用真实排版引擎量。
     *
     * ⚠️ 必须按"整列"量 —— 每一行各量各的会让列边界对不上，整张表散架（见 [TableCell]）。
     */
    val perColumn: List<List<String>> = (0 until columns).map { col ->
        buildList {
            add(block.header.getOrNull(col).orEmpty())
            block.rows.forEach { add(it.getOrNull(col).orEmpty()) }
        }
    }
    val columnWidths = tableColumnWidthsMeasured(
        columns = perColumn,
        style = baseStyle,
        available = available,
    )
    val tableWidth = columnWidths.fold(0.dp) { acc, w -> acc + w }

    Column(
        modifier = Modifier
            .width(tableWidth)
            // 只有真的放不下时才滚动 —— 窄表不该多出一条能拖的空白
            .then(if (tableWidth > available) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
    ) {
        TableRow(
            cells = block.header,
            widths = columnWidths,
            aligns = aligns,
            style = baseStyle.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
        // 表头下面**总是**有线（哪怕没有数据行）—— 那是表头的下边界
        Divider(tableWidth)

        block.rows.forEachIndexed { index, row ->
            TableRow(
                cells = List(columns) { row.getOrNull(it).orEmpty() },
                widths = columnWidths,
                aligns = aligns,
                style = baseStyle,
                color = color,
            )
            // 每行下面都画线，但**最后一行不画** —— 末尾多一条悬空的线像"表格没结束"
            if (index != block.rows.lastIndex) Divider(tableWidth)
        }
    }
    }
}

/**
 * 一行单元格。抽出来是因为表头与数据行形状相同，只有字重不同。
 *
 * ⚠️ [widths] 是**整张表统一**的列宽（由 [MarkdownTable] 算好后传进来）——
 * 不要在行内重新测量，否则每行列宽不同、表格散架。
 */
@Composable
private fun TableRow(
    cells: List<String>,
    widths: List<Dp>,
    aligns: List<TableAlign>,
    style: TextStyle,
    color: Color,
) {
    Row(
        // 上下留白撑开行高 —— 分割线落在这里的正中间（见 [MarkdownTable] 的说明）
        modifier = Modifier.padding(vertical = TABLE_CELL_VERTICAL),
    ) {
        widths.forEachIndexed { index, width ->
            TableCell(
                text = cells.getOrNull(index).orEmpty(),
                width = width,
                style = style,
                color = color,
                align = aligns.getOrNull(index)?.toTextAlign() ?: TextAlign.Start,
            )
        }
    }
}

/**
 * 一行的分割线。
 *
 * 用 `$line`（和项目其它卡片的分割线同一套语言）—— 1dp、不透明。
 * 刻意**不做成虚线或淡色**：表格线的作用是让人扫一眼就对上行，太淡就失去意义。
 *
 * ## ⚠️ 宽度**显式传入**，不能用 `fillMaxWidth()`（用户 2026-10-04 图一）
 *
 * 用户报「列表没有水平分割线」，实测那张表截图里**一条线都没有**
 *（脚本扫"连续暗像素 > 300px 的行"：0 条）。而解析是好的
 *（`Table header=[条件, 得到的方程] rows=2`），所以问题在**渲染**。
 *
 * 根因：表格外层在放不下时会套 `Modifier.horizontalScroll`，
 * 而滚动容器给子节点的是**无限宽约束** ——
 * `fillMaxWidth()` 在这种约束下会解析成 0 或不可预测的值，
 * 线因此画不出来（或画在宽度 0 的区域）。
 *
 * 现在改成 `Modifier.width(tableWidth)`：宽度由调用方（[MarkdownTable]）
 * 用真实的列宽合计显式给出，**与约束无关**，滚动与不滚动都对。
 *
 * @param width 整张表的宽度（= 各列宽之和）
 */
@Composable
private fun Divider(width: Dp) {
    Box(
        Modifier
            .width(width)
            .height(1.dp)
            .background(VColors.line),
    )
}

/**
 * 单元格的**上下**留白。
 *
 * 每一行靠它撑开高度，分割线因此落在两行正中间。
 * 12dp（上下各 12）+ 一行文字 25.6dp ≈ 每行 50dp —— 足够宽松，又不会让五行表占掉整屏。
 */
private val TABLE_CELL_VERTICAL = 12.dp

/**
 * 表格的列间距。
 *
 * 中文没有词间空格，列边界只能靠间距来分。
 * 12dp 让「左右两列属于不同字段」一眼可见，又不会吃掉太多宽度。
 */
private val TABLE_COL_GAP = 12.dp

/**
 * 一列最少给多宽。
 *
 * 72dp 在 16sp 下能放下约 4 个中文字，**大幅减少无谓折行** ——
 * 折行之后每格两行，整张表立刻显得又挤又碎。
 */
private val MIN_COLUMN = 72.dp

/**
 * 一列最多给多宽。
 *
 * ⚠️ 这个上限**只在"宽度够、可以铺满"时才参与**，
 * 而且**不能**被当成"列宽就取这个值"（那是这次 bug 的根源，见 [tableColumnWidth]）。
 */
private val MAX_COLUMN = 220.dp

/**
 * **一列**单元格的固定宽度。
 *
 * ## ⚠️⚠️ 列宽必须**整列统一**，不能每一行各算各的（用户 2026-10-04）
 *
 * ### 我上一版错在哪
 *
 * 我用 `Modifier.weight(估宽, fill = false)` 让 Compose 按**每行各自的内容**测宽。
 * 结果：同一列在不同行里宽度不同 —— **列边界对不上，整张表散架、全往左堆**。
 *
 * 用户的批评一针见血：
 *
 * > 「按理说设计不应该是取同一列最宽的一行加上一小段作为列宽吗，
 * >   为什么现在所有元素都靠左堆积起来，同一列不对齐了」
 *
 * **这就是表格的定义**：列宽 = 该列**所有行里最宽的那一格**，
 * 然后**整列所有行都用这个宽度**。表格的可读性完全来自"同一列上下对齐"。
 *
 * ### 正确做法
 *
 * | 步骤 | 做法 |
 * |---|---|
 * | 1 | 对每一列，**逐个测量**该列所有单元格（表头 + 每行）的真实文字宽度 |
 * | 2 | 取最大值 = 这一列的自然宽度（+ [TABLE_COL_GAP] 间距） |
 * | 3 | 夹到 `[MIN_COLUMN, MAX_COLUMN]` |
 * | 4 | 若整表放得下，把富余宽度按比例摊满；放不下就保持自然宽（外层横向滚动） |
 * | 5 | **整列所有行都用同一个宽度** —— 这就是对齐的来源 |
 *
 * 第 1 步用 [measureWidthDp]（Compose 的真实文本测量），
 * **不再**用"按字符数猜"那种办法 —— 猜出来的值就是
 * "`∂u/∂t` 被当成 220dp、实际只要 35dp"的根源。
 *
 * @param width 整列统一的宽度（由 [MarkdownTable] 量好传进来）
 */
@Composable
private fun TableCell(
    text: String,
    width: Dp,
    style: TextStyle,
    color: Color,
    align: TextAlign?,
) {
    Box(
        modifier = Modifier
            .width(width)
            .padding(end = TABLE_COL_GAP),
    ) {
        InlineText(text, style, color, align = align)
    }
}

/**
 * 用**真实排版引擎**量一段文字多宽（单位 dp）。
 *
 * `TextMeasurer` 是 Compose 自己的文本测量器 —— 它知道字体、连字、字距、
 * 全角半角，量出来的就是最终画在屏幕上的宽度。
 *
 * ⚠️ 之前按字符数猜宽度（中日韩 1em、西文 0.55em），
 * 于是 `∂u/∂t` 被估成"很宽"、顶到上限 220dp，而它真实只要 35dp ——
 * **屏幕上就是那一大片 244dp 的空白**。有精确测量就不该用估算。
 */
@Composable
private fun measureWidthDp(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, measurer, density) {
        if (text.isEmpty()) 0.dp
        else {
            val result = measurer.measure(
                text = AnnotatedString(text),
                style = style,
            )
            with(density) { result.size.width.toDp() }
        }
    }
}

/**
 * 算出**每一列**的最终宽度（表头与所有数据行统一使用）。
 *
 * 见 [TableCell] 的说明：**列宽 = 该列最宽的那一格**，整列统一。
 *
 * @param cells 每列的**全部**单元格文字（表头 + 各数据行）
 */
@Composable
private fun tableColumnWidthsMeasured(
    columns: List<List<String>>,
    style: TextStyle,
    available: Dp,
): List<Dp> {
    // 每列取"最宽的那一格" —— 用真实排版引擎量，不是按字符数猜
    val natural = columns.map { cells ->
        cells.maxOfOrNull { measureWidthDp(it, style) } ?: 0.dp
    }
    return distributeColumns(natural, available)
}

/**
 * **列宽分配算法**（纯函数）。
 *
 * 从"每列的自然宽度"算出"每列最终用多宽"。抽成纯函数有两个理由：
 *
 * 1. 渲染路径（[tableColumnWidthsMeasured]）要用它；
 * 2. **单测能直接钉住它的行为** —— 真实测量发生在 Compose 的 `TextMeasurer` 里，
 *    纯 JVM 单测拿不到字体度量，硬写 dp 值只会得到"跑在 JVM 上、和真机无关的假精度"。
 *    把算法抽出来，就能用**构造的自然宽度**验算分配逻辑本身。
 *
 * ## 规则
 *
 * 1. 自然宽度 + [TABLE_COL_GAP]（列间距）
 * 2. 夹到 `[MIN_COLUMN, MAX_COLUMN]`
 * 3. 整表放得下 → 富余宽度**按比例**摊满（窄表不留空）
 * 4. 放不下 → 保持原样（外层横向滚动）
 *
 * ## 为什么按比例摊，而不是等分
 *
 * 等分会让窄列平白变宽（`∂u/∂t` 那列本可以只占一点点）。
 * 按比例摊是"按需分配" —— 宽列拿到更多空间。
 * 我一度改成等分，那是错的：**真正的问题从来不是分摊方式**
 *（见 [TableCell] 里那张三次弯路的表）。
 */
internal fun distributeColumns(natural: List<Dp>, available: Dp): List<Dp> {
    val clamped = natural.map { (it + TABLE_COL_GAP).coerceIn(MIN_COLUMN, MAX_COLUMN) }
    val total = clamped.fold(0.dp) { acc, w -> acc + w }
    if (total <= available && total.value > 0f) {
        val extra = available - total
        return clamped.map { it + extra * (it.value / total.value) }
    }
    return clamped
}

/**
 * 供单测使用的入口：给**自然宽度**（dp），返回分配后的列宽。
 *
 * ⚠️ 生产路径不走这里 —— 渲染时自然宽度由 [measureWidthDp] 真实测量得出。
 * 这个重载只为让算法本身可测（见 [distributeColumns]）。
 */
internal fun tableColumnWidths(
    header: List<String>,
    rows: List<List<String>>,
    columns: Int,
    available: Dp,
    fontSize: Float,
): List<Dp> {
    // 用"每列最长的文字长度"当自然宽度的**替身**，只为驱动分配算法。
    // ⚠️ 这不是生产用的测量 —— 生产用 TextMeasurer（见 [measureWidthDp]）。
    val natural = (0 until columns).map { column ->
        val longest = maxOf(
            header.getOrNull(column).orEmpty().length,
            rows.maxOfOrNull { it.getOrNull(column).orEmpty().length } ?: 0,
        )
        (longest * fontSize * 0.6f).dp
    }
    return distributeColumns(natural, available)
}

/**
 * 表格某一列的对齐方式（来自分隔行里的冒号）。 */
internal enum class TableAlign {
    START, CENTER, END;

    fun toTextAlign(): TextAlign = when (this) {
        START -> TextAlign.Start
        CENTER -> TextAlign.Center
        END -> TextAlign.End
    }
}

/**
 * 行内文本：把 Markdown 行内语法转成 SpanStyle / 链接。
 *
 * 这里直接用 `Text` 而不是项目的 `VText` ——
 * `VText` 只接受 `String`，而这里需要传 `AnnotatedString`（带粗体/代码样式）。
 * 为一个组件给 VText 加重载不划算（它是全项目共用的，改动面大）。
 */
@Composable
private fun InlineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    strike: Boolean = false,
    align: TextAlign? = null,
) {
    /*
     * ⚠️ 记忆化的 key 必须带上 `style.fontSize`：行内代码的字号是由它**推导**出来的
     *（见 [renderInline] 的 `codeFontSize`），漏掉的话字号一变就会出现
     *"正文改了、行内代码还是旧尺寸"。
     */
    val annotated = remember(text, color, strike, style.fontSize) {
        renderInline(text, color, style.fontSize * 0.87f, strike)
    }
    androidx.compose.material3.Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
        textAlign = align,
    )
}

/**
 * 强调 / 链接里还能再嵌几层。
 *
 * `**甲**` 里面可以再有 `` `代码` `` 或 `$公式$`，所以强调要**递归**往下解析。
 * 但递归必须有上限 —— 模型偶尔会输出一长串 `**`，不设上限就是栈溢出。
 */
private const val MAX_INLINE_DEPTH = 4

/**
 * 行内标注。
 *
 * ## 一条扫描线，**分隔符只有一张表**
 *
 * 每种标记的优先级由 `when` 的顺序决定：
 *
 * | 顺序 | 标记 | 为什么要排在前面 |
 * |---|---|---|
 * | 1 | `` ` `` | 代码里的任何字符都不是标记（`` `**a**` `` 不该变粗体） |
 * | 2 | `\(` `\[` `$$` `$` | 公式里的 `*` 不是斜体（`$a*b$`） |
 * | 3 | `![` | 必须早于 `[`，否则会多露出一个感叹号 |
 * | 4 | `[` | 链接 |
 * | 5 | `~~` | 删除线 |
 * | 6 | `**` | 粗体 |
 * | 7 | `*` | 斜体（前后不能紧邻另一个星号） |
 *
 * ⚠️ 老实现这里缺 `\[…\]`（模型很常用，会原样露出反斜杠），
 * 而且 `**` 内部**不递归**（`**$\mathbf{u}$**` 会露出 `$` 和 `\mathbf`）。
 * 两处都已修好。
 *
 * ⚠️ 刻意**不认** `__粗体__` / `_斜体_` ——
 * 模型在讲代码时大量写 `snake_case`，认下划线标记会把标识符吃掉一半。
 *
 * @param codeFontSize 行内代码的字号。由调用方按正文推导传入 ——
 *   写死 13sp 的话，正文改了之后行内代码会明显不搭。
 * @param baseColor 正文色。**当前未使用** —— 没有设色的 span 由外层
 *   `Text(color = …)` 决定，这里不必也插一层颜色。留着是为了不给调用方加负担。
 */
internal fun renderInline(
    text: String,
    baseColor: Color,
    codeFontSize: TextUnit,
    strike: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    // 先清掉 HTML 噪音（模型会写 `<br>`、`<b>` 这些东西）
    appendInlineMarkup(stripHtmlTags(text), codeFontSize, depth = 0)

    // 任务清单的已完成项：整行划线（在最外层加，免得和行内样式打架）
    if (strike) {
        addStyle(
            SpanStyle(textDecoration = TextDecoration.LineThrough, color = VColors.ink3),
            start = 0,
            end = length,
        )
    }
}

/** [renderInline] 的扫描主体。递归调用自己来处理强调 / 链接的内部。 */
private fun AnnotatedString.Builder.appendInlineMarkup(
    text: String,
    codeFontSize: TextUnit,
    depth: Int,
) {
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isNotEmpty()) {
            append(buffer.toString())
            buffer.clear()
        }
    }

    /** 强调 / 链接 / 删除线的**内部**再走一遍行内扫描（深度到顶就原样输出）。 */
    fun inner(part: String) {
        if (part.isEmpty()) return
        if (depth >= MAX_INLINE_DEPTH) append(part)
        else appendInlineMarkup(part, codeFontSize, depth + 1)
    }

    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            // ---- 行内代码：优先级最高，里面的任何标记都不解析
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = VColors.surface2,
                            fontSize = codeFontSize,
                        ),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    buffer.append(c); i++
                }
            }

            // ---- LaTeX 的三个行内写法：`\(…\)`、`\[…\]`、`$$…$$`
            //
            // ⚠️ `\[…\]` 是后补的：模型（至少 DeepSeek）大量用它写行内公式，
            // 老实现不认，用户看到的就是一串裸的 `\[` `\frac` `\]`。
            text.startsWith("\\(", i) || text.startsWith("\\[", i) -> {
                val close = if (text[i + 1] == '(') "\\)" else "\\]"
                val end = text.indexOf(close, i + 2)
                if (end > i + 1) {
                    flush()
                    // `followedBy` 传公式后面的那个字符：行尾不补空隙，见 [appendInlineMath]
                    appendInlineMath(this, text.substring(i + 2, end), text.getOrNull(end + 2))
                    i = end + 2
                } else {
                    // 没有收尾（流式输出中间态）→ 原样留着，等下几片
                    buffer.append(c); i++
                }
            }

            // ---- 块级公式标记写在**句子中间**时，按行内规则渲染
            //
            // 老实现的分支条件里写着 `!source.startsWith("$$", i)` ——
            // 于是它既不是块级（块级只看行首）也不是行内，**原样输出**。
            text.startsWith("$$", i) -> {
                val end = text.indexOf("$$", i + 2)
                if (end > i + 2) {
                    flush()
                    appendInlineMath(this, text.substring(i + 2, end), text.getOrNull(end + 2))
                    i = end + 2
                } else {
                    buffer.append(c); i++
                }
            }

            /*
             * ---- 行内公式 `$…$`
             *
             * `isInlineMathSpan` 挡住"钱数"（`这本书 $5，那本 $10`）。
             */
            c == '$' -> {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1 && isInlineMathSpan(text, i, end)) {
                    flush()
                    appendInlineMath(this, text.substring(i + 1, end), text.getOrNull(end + 1))
                    i = end + 1
                } else {
                    buffer.append(c); i++
                }
            }

            /*
             * ---- 图片 `![说明](地址)`
             *
             * 对话里不加载图片（要联网、要权限，而且模型给的地址经常只是示意图），
             * 但**绝不能让 `!` 和地址裸露出来**。这里显示说明文字，没有说明就显示占位。
             *
             * ⚠️ 必须排在链接分支**之前**。
             */
            text.startsWith("![", i) -> {
                val close = text.indexOf(']', i + 2)
                val open = if (close > i) text.indexOf('(', close + 1) else -1
                val end = if (open == close + 1) text.indexOf(')', open + 1) else -1
                if (close > i && end > open) {
                    flush()
                    append(text.substring(i + 2, close).ifBlank { "［图片］" })
                    i = end + 1
                } else {
                    buffer.append(c); i++
                }
            }

            // ---- 链接 `[文字](地址)`
            c == '[' -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close > i) text.indexOf('(', close + 1) else -1
                val end = if (open == close + 1) text.indexOf(')', open + 1) else -1
                if (close > i && end > open) {
                    flush()
                    val label = text.substring(i + 1, close)
                    val url = text.substring(open + 1, end)
                    /*
                     * ⚠️ `LinkAnnotation.Url` 对**空地址**会抛异常
                     *（`require(url.isNotEmpty())`），而流式输出时 `[文字]()`
                     * 这种中间态是会出现的 —— 那会直接崩掉整个界面。
                     * 地址无效时退化成普通文字，链接没了总比闪退好。
                     */
                    if (url.isNotBlank()) {
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = VColors.accent,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ),
                        ) {
                            inner(label)
                        }
                    } else {
                        inner(label)
                    }
                    i = end + 1
                } else {
                    buffer.append(c); i++
                }
            }

            // ---- 删除线 `~~文字~~`
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        inner(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    buffer.append(c); i++
                }
            }

            // ---- 粗体
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        // ⚠️ 内部要**递归**：`**$\mathbf{u}$**` 里的公式得照常解析
                        inner(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    buffer.append(c); i++
                }
            }

            // ---- 斜体（单星号；前后不能紧邻另一个星号）
            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text.startsWith("**", end)) {
                    flush()
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        inner(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    buffer.append(c); i++
                }
            }

            else -> {
                buffer.append(c); i++
            }
        }
    }
    flush()
}

/**
 * 清掉模型夹带的 HTML 噪音。
 *
 * 模型（尤其中文场景）很爱写 `<br>`、`<b>`、`<sub>` 这些 ——
 * 原样显示的话用户会看到一堆尖括号标签，非常破。
 *
 * 处理方式：
 *  · `<br>` / `<br/>` → **换行**（它本来就是这个意思）
 *  · 其余成对/自闭合标签 → **直接去掉**（保留里面的文字）
 *
 * ⚠️ 只删"看起来像标签"的东西：`<` 后面必须紧跟字母或 `/`。
 * 所以 `a < b`（后面是空格）和 `a<b`（没有 `>`）都不会被误伤。
 */
internal fun stripHtmlTags(text: String): String {
    if (!text.contains('<')) return text

    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c != '<') {
            out.append(c); i++
            continue
        }

        val next = text.getOrNull(i + 1)
        if (next == null || !(next.isLetter() || next == '/')) {
            // 不是标签，是普通的小于号
            out.append(c); i++
            continue
        }

        val close = text.indexOf('>', i + 1)
        if (close < 0) {
            // 没有闭合的 `>`，当普通文本
            out.append(c); i++
            continue
        }

        val name = text.substring(i + 1, close).trim('/').substringBefore(' ').lowercase()
        if (name == "br") out.append('\n')
        // 其余标签只丢标记、保留内容
        i = close + 1
    }
    return out.toString()
}

/**
 * `$…$` 到底是不是公式（而不是美元符号）。
 *
 * 规则取自 Pandoc：**开标记后面不能是空白、闭标记前面不能是空白、
 * 闭标记后面不能紧跟数字**。这三条能挡住绝大多数的钱数写法，
 * 而真正的公式（`$x^2$`、`$a+b$`）全部满足。
 *
 * `\(…\)` 不需要这条 —— 那个写法不可能有歧义。
 *
 * ## ⚠️ 先验下标合法性，再看内容
 *
 * 这个函数会读 `text[close - 1]`，所以**下标本身首先要合法**。
 * 调用方传进来的必须是"已经找到的收尾位置"，**不能是 `indexOf` 的 -1** ——
 * 漏了这一步的后果是 `charAt(-2)`，真机上的表现是
 * `StringIndexOutOfBoundsException: length=404; index=-2`（用户报过一次）。
 * 所以这里把前置条件写死，不指望每个调用方都记得。
 *
 * @return 下标不成立或内容不像公式时 false
 */
private fun isInlineMathSpan(text: String, open: Int, close: Int): Boolean {
    // 收尾必须真的在开标记之后，且两个下标都落在串内
    if (open < 0 || open + 1 >= close || close > text.length) return false
    if (text[open + 1].isWhitespace()) return false
    if (text[close - 1].isWhitespace()) return false
    val after = text.getOrNull(close + 1)
    return after == null || !after.isDigit()
}

/**
 * 把一段 LaTeX 以行内形式写进 [AnnotatedString.Builder]，并补上**右侧空隙**。
 *
 * 行内**只能压平**（见 [flatten]）—— 正文是一个 `AnnotatedString`，
 * 而它塞不进 composable（上下叠的分数需要真的嵌套布局）。
 * 解析不出来的话老实显示原文，不猜。
 *
 * ## ⚠️ 为什么末尾要补一个空隙（用户 2026-10-04：「：之间的间距小的离谱」）
 *
 * ### 实测（NS 方程那张列表，density 2.75）
 *
 * | 条目 | 公式 → `：` | `：` → 中文 |
 * |---|---|---|
 * | `ρ：密度` | **3.3dp** | 15.6dp |
 * | `u：速度场` | **4.7dp** | 15.3dp |
 * | `p：压强` | **2.9dp** | 17.1dp |
 *
 * 同一个冒号，**左边 3.3dp、右边 15.6dp —— 差 4.7 倍**，看起来就是"挤在一起"。
 *
 * ### 根因
 *
 * 两件事叠在一起：
 *
 * 1. **公式被包了 [FontStyle.Italic]**。斜体字形（尤其数学斜体 `𝜌`、`𝑝`）
 *    的墨迹会**探出**自己的字形框右缘 —— 排版上叫 italic correction，
 *    而 Compose 的 `AnnotatedString` 不会因为这个自动加宽间距。
 * 2. **中文全角冒号 `：` 自带左右各约 1/4 em 的空白**（16sp 下约 8dp）。
 *    所以冒号右边天然很宽。
 *
 * 左边没有对应补偿 → 公式墨迹直接顶到冒号的字形框左缘。
 *
 * ### 修法：在公式末尾补一个**细空隙**
 *
 * 补的是 [MATH_TRAILING_SPACE]（一个 U+2009 THIN SPACE），不是普通空格 ——
 * 普通空格在 16sp 下约 4dp 且会被中文排版规则吞掉，细空隙更可控。
 *
 * ⚠️ **只在后面确实紧跟内容时补**：公式在行尾（`… 其中 $\mu$`）时补了会多出尾随空白，
 * 让文字看起来没对齐右边。
 */
private fun appendInlineMath(
    builder: AnnotatedString.Builder,
    latex: String,
    followedBy: Char? = null,
) {
    val tree = MathParser.parse(latex)
    /*
     * ⚠️ **行内公式也要用数学字体**（用户 2026-10-04）。
     *
     * 用户原话：「ds在全文中所有的物理符号都使用了正确的优化字体，
     * 而 orenote 仅仅在公式内使用了优化字体」。
     *
     * 原来这里只加了 `fontStyle = Italic`，**没有指定 fontFamily** ——
     * 于是 `flatten()` 出来的 `∇`、`ρ`、`∂` 用的是**正文系统字体**的字形，
     * 和块级公式里 Latin Modern Math 的同一个符号**长得不一样**。
     * 一行文字里两种 `∇`，正是用户看到的"只有公式内是优化字体"。
     *
     * 这里补上 [MathFontFamily]。
     *
     * ⚠️ 仍然保留 `fontStyle = Italic`：它作用于**没有被 Unicode 数学字母映射覆盖**
     * 的那些字符（映射在 `toMathAlphanumeric` 里做，但行内走的是 `flatten()`，
     * 不经过映射）。两者叠加不冲突 —— 有码位的用码位，没有的靠倾斜。
     */
    builder.withStyle(
        SpanStyle(
            fontFamily = MathFontFamily,
            fontStyle = FontStyle.Italic,
        ),
    ) {
        append(if (tree != null) tree.flatten() else latex)
        // 后面还有内容才补空隙（行尾不补，避免尾随空白）
        if (followedBy != null) append(MATH_TRAILING_SPACE)
    }
}

/**
 * 行内公式后面补的细空隙（U+2009 THIN SPACE）。
 *
 * 用来抵消**斜体字形的右侧探出** + 中文标号自带左空白不足的问题。
 * 见 [appendInlineMath] 的实测数据。
 */
private const val MATH_TRAILING_SPACE = '\u2009'

// =====================================================================================
//  块级模型
// =====================================================================================

/** 一个块级元素。 */
internal sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val text: String, val indent: Int = 0) : Block
    data class Ordered(val marker: String, val text: String, val indent: Int = 0) : Block
    data class Task(val text: String, val checked: Boolean, val indent: Int = 0) : Block
    data class Code(val text: String) : Block
    data class Quote(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        /** 每列的对齐方式（来自分隔行里的冒号）；缺省左对齐。 */
        val alignments: List<TableAlign> = emptyList(),
    ) : Block

    /** 块级公式 `$$…$$` / `\[…\]` / `\begin{env}…\end{env}`。 */
    data class Math(val latex: String) : Block

    /** 分隔线。 */
    data object Rule : Block
}

/**
 * 按块解析一段 Markdown。
 *
 * 纯函数、无正则（见文件头的说明）—— 解析错了才是"看起来像坏了"的根源，
 * 所以它必须能被单测直接钉住。
 */
internal fun parseBlocks(markdown: String): List<Block> = MarkdownParser(markdown).parse()

/**
 * 块级扫描器。
 *
 * ## 核心不变量：游标**不保证在行首**
 *
 * 每一轮从 [i] 出发，先看这里能不能开一个新块（见 [scan]）；都不能，就按普通段落读下去。
 * 段落/列表项读到"下一个块从这里开始"为止 —— 而那个位置**可以在行中间**：
 *
 * ```
 * …至今未解##常见简化- **欧拉方程**：令 $\mu=0$
 *          ↑            ↑
 *         标题          条目
 * ```
 *
 * 老实现把"行首"当成块判定的前提，所以只能靠一串预处理补丁在解析前
 * 往行中间**插换行**（`splitGluedBlockMarkers` 等六个函数）。
 * 现在这些补丁全部删除：块判定本来就不该关心自己在不在行首。
 *
 * ## 为什么 `\r\n` 要先归一
 *
 * 后面全部逻辑都在比较下标，`\r` 留在串里会让"行尾"算错一位。
 */
private class MarkdownParser(source: String) {

    private val src = source.replace("\r\n", "\n").replace('\r', '\n')
    private val out = ArrayList<Block>()

    /** 当前游标。**不保证在行首** —— 粘连标记被切开后它会停在行中间。 */
    private var i = 0

    fun parse(): List<Block> {
        while (i < src.length) {
            val lineEnd = endOfLine(i)

            // 空行 / 只有空白：跳过（它只起分隔作用，不产生块）
            var head = i
            while (head < lineEnd && (src[head] == ' ' || src[head] == '\t')) head++
            if (head >= lineEnd) {
                i = nextLine(lineEnd)
                continue
            }

            i = head
            scan()

            // 兜底：任何分支都必须推进游标，否则死循环
            if (i <= head) i = nextLine(lineEnd)
        }
        return out
    }

    /**
     * 在 [i] 处开一个块。分支顺序 = 优先级（越"独占"的越先判）。
     *
     * ⚠️ **标题排在表格之前**，这一条是有意的。
     *
     * 模型会把小节标题与**表格头**写在同一行（用户第六张截图）：
     *
     * ```
     * ###每项背后的物理|项 |对应物理 |
     * ```
     *
     * 表格判定要向前看一行（下一行是 `|---|` 才算表），所以老实现把表格排在标题前面 ——
     * 于是整行被当成表头，`###每项背后的物理` 成了**第一格的内容**，
     * 用户看到的就是"标题变成表格的一列、`|` 全平铺"。
     * 它当时的补法是"把表头第一格里粘着的块标记摘出来"（`splitLeadingBlockMarker`）。
     *
     * 现在改成让标题**先认领**这一行：[readHeading] 的断点规则里
     * 本来就有"标题里不该出现 `|`"，于是标题只取到 `每项背后的物理`，
     * 剩下的 `|项 |对应物理 |` **从原位置**接着被表格分支认领 —— 不需要任何补丁。
     */
    private fun scan() {
        val lineEnd = endOfLine(i)

        fenceAt(i)?.let { readCode(it); return }
        mathOpenAt(i, lineEnd)?.let { readMathBlock(it, lineEnd); return }
        if (ruleAt(i)) { out += Block.Rule; i = nextLine(lineEnd); return }
        if (headingAt(i)) { readHeading(); return }
        if (tableAt(i)) { readTable(); return }
        taskAt(i)?.let { readTask(it); return }
        bulletAt(i)?.let { readBullet(it); return }
        orderedAt(i)?.let { readOrdered(it); return }
        if (quoteAt(i)) { readQuote(); return }
        readParagraph()
    }

    // ------------------------------------------------------------------ 位置工具

    /** [from] 所在行的**行尾**（`\n` 的下标，或字符串末尾）。 */
    private fun endOfLine(from: Int): Int {
        var k = from
        while (k < src.length && src[k] != '\n') k++
        return k
    }

    /** 下一行的起点（没有下一行时等于 `src.length`）。 */
    private fun nextLine(lineEnd: Int): Int =
        if (lineEnd >= src.length) src.length else lineEnd + 1

    /** [pos] 所在行的行首。 */
    private fun lineStartOf(pos: Int): Int {
        var k = pos
        while (k > 0 && src[k - 1] != '\n') k--
        return k
    }

    /** 这一行从 [pos] 起全是空白（或者是空行）。 */
    private fun blankLineAt(pos: Int): Boolean {
        val lineEnd = endOfLine(pos)
        for (k in pos until lineEnd) {
            if (src[k] != ' ' && src[k] != '\t') return false
        }
        return true
    }

    /** 标记所在的缩进级数：每 2 个空格 / 1 个 Tab 一级。 */
    private fun indentAt(pos: Int): Int {
        var k = pos - 1
        var spaces = 0
        while (k >= 0 && (src[k] == ' ' || src[k] == '\t')) {
            spaces += if (src[k] == '\t') 2 else 1
            k--
        }
        return spaces / 2
    }

    /** 从 [pos] 起连续多少个 [ch]。 */
    private fun runLength(pos: Int, ch: Char): Int {
        var n = 0
        while (pos + n < src.length && src[pos + n] == ch) n++
        return n
    }

    /** 续行判据：行首至少两个空格或一个 Tab。 */
    private fun isIndented(pos: Int): Boolean =
        src.startsWith("  ", pos) || src.getOrNull(pos) == '\t'

    // ------------------------------------------------------------------ 标记识别

    /**
     * 代码围栏的开标记。**只在行首认**（行中间的 ``` 不是围栏）。
     *
     * 允许行首缩进（模型会缩进着写代码块），所以判据是"这一行在 [pos] 之前全是空白"。
     */
    private fun fenceAt(pos: Int): String? {
        val lineStart = lineStartOf(pos)
        if (pos != lineStart && !src.substring(lineStart, pos).isBlank()) return null
        return when {
            src.startsWith("```", pos) -> "```"
            src.startsWith("~~~", pos) -> "~~~"
            else -> null
        }
    }

    /**
     * 这一行是不是块级公式的开头；是则返回（首行剩余内容, 结束标记）。
     *
     * 支持三种分隔符：`$$…$$`（最常见）、`\[…\]`（LaTeX 原生）、
     * `\begin{equation}…\end{equation}` 及 align / gather 等环境。
     * 分隔符属于**渲染器该兜住的差异**，不该指望提示词约束模型只写某一种。
     */
    private fun mathOpenAt(pos: Int, lineEnd: Int): Pair<String, String>? {
        if (src.startsWith("$$", pos)) return src.substring(pos + 2, lineEnd) to "$$"
        if (src.startsWith("\\[", pos)) return src.substring(pos + 2, lineEnd) to "\\]"
        return beginEnvAt(pos, lineEnd)
    }

    /**
     * `\begin{equation}` —— 纯字符串判断，刻意不用正则（见文件头的事故记录）。
     *
     * ⚠️ 两个下标都要**由判据本身保证**，不能靠"想一遍觉得不可能"：
     * `close + 1 <= lineEnd` 这条保证了下面那次 `substring(close + 1, lineEnd)`
     * 不会出现 begin > end（那同样是 `StringIndexOutOfBoundsException`）。
     */
    private fun beginEnvAt(pos: Int, lineEnd: Int): Pair<String, String>? {
        if (!src.startsWith(BEGIN_PREFIX, pos)) return null
        val close = src.indexOf('}', pos + BEGIN_PREFIX.length)
        // `close >= pos + 7` 由 `indexOf` 的起点保证；再要求它连同 `}` 都在这一行内
        if (close < 0 || close + 1 > lineEnd) return null

        val env = src.substring(pos + BEGIN_PREFIX.length, close)
        if (env !in MATH_ENVS) return null
        return src.substring(close + 1, lineEnd) to "\\end{$env}"
    }

    /** 当前行（从 [pos] 起）带表头分隔行 → 这是一张表。 */
    private fun tableAt(pos: Int): Boolean {
        val lineEnd = endOfLine(pos)
        if (!src.substring(pos, lineEnd).contains('|')) return false
        val next = nextLine(lineEnd)
        if (next >= src.length) return false
        return isTableSeparator(src.substring(next, endOfLine(next)).trim())
    }

    /** 整行（从 [pos] 起）是分隔线：`---` / `***` / `___`，三个以上、同一种字符。 */
    private fun ruleAt(pos: Int): Boolean {
        val line = src.substring(pos, endOfLine(pos)).trim()
        if (line.length < 3) return false
        val c = line[0]
        if (c != '-' && c != '*' && c != '_') return false
        return line.all { it == c }
    }

    private fun headingAt(pos: Int): Boolean =
        pos < src.length && src[pos] == '#' && (pos == 0 || src[pos - 1] != '#')

    private fun quoteAt(pos: Int): Boolean =
        src.substring(pos, endOfLine(pos)).trim().startsWith(">")

    /**
     * 任务清单 `- [ ] 文字` / `- [x] 文字`。
     *
     * 老实现用 `Regex("^[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")`，还在注释里记着
     * "组号写错会 IndexOutOfBounds 崩掉整个界面" —— 手写扫描没有这种下标风险，
     * 也不必再为 ICU 与 JVM 的正则差异买单。
     */
    private fun taskAt(pos: Int): Marker? {
        val bullet = src.getOrNull(pos) ?: return null
        if (bullet !in BULLET_CHARS) return null
        var p = pos + 1
        if (!isSpace(src.getOrNull(p))) return null
        while (isSpace(src.getOrNull(p))) p++
        if (src.getOrNull(p) != '[') return null
        val state = src.getOrNull(p + 1) ?: return null
        if (state != ' ' && state != 'x' && state != 'X') return null
        if (src.getOrNull(p + 2) != ']') return null

        var q = p + 3
        if (!isSpace(src.getOrNull(q))) return null
        while (isSpace(src.getOrNull(q))) q++
        return Marker(marker = "", contentStart = q, checked = state != ' ')
    }

    /**
     * 无序列表标记，返回条目正文的起点。
     *
     * ## ⚠️ **不要求标记后面有空格**（用户截图暴露的问题）
     *
     * CommonMark 要求 `- 内容`，但**模型经常写成 `-内容`**。
     * 要求空格的话那些行会退化成普通段落，于是几条清单被**合并成一大段**，
     * 横杠还留在文字里。
     *
     * ## 但要有底线
     *
     * · `*` 不加空格一律不认 —— `*强调*` 太容易误判成列表
     * · `--` 不认 —— 可能是 `---` 分隔线或破折号
     * · `-5` 不认 —— 行首的负数
     */
    private fun bulletAt(pos: Int): Marker? {
        val marker = src.getOrNull(pos) ?: return null
        if (marker !in BULLET_CHARS) return null

        val next = src.getOrNull(pos + 1) ?: return null
        var content = pos + 1
        if (isSpace(next)) {
            while (isSpace(src.getOrNull(content))) content++
        } else {
            if (marker == '*') return null
            if (next == '-' || next.isDigit()) return null
        }
        if (content >= endOfLine(pos)) return null
        return Marker(marker = marker.toString(), contentStart = content)
    }

    /**
     * 有序列表标记 `1. ` / `2) `，返回标记原文与正文起点。
     *
     * 序号长度不限（`10.` 要完整带出来）；分隔符后**必须有空格**，
     * 否则 `1.左边`、`2024.年` 这种正文会被误判成列表。
     */
    private fun orderedAt(pos: Int): Marker? {
        var n = 0
        while (src.getOrNull(pos + n)?.isDigit() == true) n++
        if (n == 0) return null
        val sep = src.getOrNull(pos + n) ?: return null
        if (sep != '.' && sep != ')') return null
        if (!isSpace(src.getOrNull(pos + n + 1))) return null

        var content = pos + n + 2
        while (isSpace(src.getOrNull(content))) content++
        return Marker(marker = src.substring(pos, pos + n + 1), contentStart = content)
    }

    // ------------------------------------------------------------------ 块读取

    private fun readCode(fence: String) {
        val body = ArrayList<String>()
        var p = nextLine(endOfLine(i))
        while (p < src.length) {
            val lineEnd = endOfLine(p)
            val line = src.substring(p, lineEnd)
            if (line.trim().startsWith(fence)) { p = nextLine(lineEnd); break }
            body += line
            p = nextLine(lineEnd)
        }
        // 未闭合的围栏也要把内容显示出来（不能吞掉）
        if (body.isNotEmpty()) out += Block.Code(body.joinToString("\n").trimEnd())
        i = p
    }

    private fun readMathBlock(open: Pair<String, String>, lineEnd: Int) {
        val (first, closer) = open

        // 单行写法：开闭标记在同一行
        val inlineEnd = first.indexOf(closer)
        if (inlineEnd >= 0) {
            val latex = first.substring(0, inlineEnd).trim()
            if (latex.isNotEmpty()) out += Block.Math(latex)
            i = nextLine(lineEnd)
            return
        }

        val body = StringBuilder().appendLine(first)
        var p = nextLine(lineEnd)
        while (p < src.length) {
            val eol = endOfLine(p)
            val line = src.substring(p, eol).trim()
            val end = line.indexOf(closer)
            if (end >= 0) {
                // 收尾标记**粘在内容后面**也要收进来（`\nabla \cdot \mathbf{u} =0$$`）
                body.appendLine(line.substring(0, end))
                p = nextLine(eol)
                break
            }
            body.appendLine(line)
            p = nextLine(eol)
        }
        val latex = body.toString().trim()
        if (latex.isNotEmpty()) out += Block.Math(latex)
        i = p
    }

    private fun readTable() {
        val headerEnd = endOfLine(i)
        val sepStart = nextLine(headerEnd)
        val separator = src.substring(sepStart, endOfLine(sepStart)).trim()
        val header = splitTableRow(src.substring(i, headerEnd).trim())

        val rows = ArrayList<List<String>>()
        var p = nextLine(endOfLine(sepStart))
        while (p < src.length) {
            val lineEnd = endOfLine(p)
            val line = src.substring(p, lineEnd).trim()
            if (line.isEmpty() || !line.contains('|')) break
            rows += splitTableRow(line)
            p = nextLine(lineEnd)
        }

        /*
         * 把每行补齐到列数一致。
         *
         * 模型漏写一格（`| 1 |` 而表头是三列）很常见。渲染层用 `getOrNull` 兜得住，
         * 但**数据模型不统一就是隐患** —— 在这里补齐，
         * `Block.Table` 就有了"每行都是 columns 格"的不变量。
         */
        val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
        fun pad(row: List<String>) = List(columns) { row.getOrNull(it).orEmpty() }
        out += Block.Table(pad(header), rows.map(::pad), parseTableAlignments(separator))
        i = p
    }

    /**
     * 标题。
     *
     * ## 两种写法分开处理
     *
     * | 写法 | 处理 |
     * |---|---|
     * | `## 标题`（井号后有空格） | 整行都是标题，**不必猜** |
     * | `##标题正文`（粘连，模型常见） | 用 [titleCut] 猜断点，剩下的正文**从原位置继续解析** |
     *
     * 第二种里"剩下的正文"可能还是列表、公式、甚至另一个粘连的标题 ——
     * 交给扫描器重新判定即可（老实现是把它塞回"待处理行列表"再跑一遍）。
     */
    private fun readHeading() {
        val level = runLength(i, '#').coerceIn(1, MAX_HEADING_LEVEL)
        val p = i + level
        val lineEnd = endOfLine(i)

        // `###` 后面什么都没有 → 丢掉（不留空标题）
        if (p >= lineEnd) { i = nextLine(lineEnd); return }

        if (isSpace(src[p])) {
            val title = src.substring(p, lineEnd).trim()
            if (title.isNotEmpty()) out += Block.Heading(level, title)
            i = nextLine(lineEnd)
            return
        }

        val cut = titleCut(p, lineEnd)
        val titleEnd = if (cut < 0) lineEnd else p + cut
        val title = src.substring(p, titleEnd).trim()
        if (title.isNotEmpty()) out += Block.Heading(level, title)

        var rest = titleEnd
        while (rest < lineEnd && isSpace(src[rest])) rest++
        i = if (rest < lineEnd) rest else nextLine(lineEnd)
    }

    /**
     * `##标题正文` 这种**没有分隔符**的写法里，猜"标题到哪里结束"。
     *
     * 这是**语义**问题，光看字符串无法 100% 确定 —— 所以只能取"足够好"的启发式：
     *
     * 1. **下一个块标记**（`- ` / `#` / `**` / `$` / `|` / 行尾分隔线）——
     *    那几乎必然是"标题到此结束"
     * 2. **句读 + 长度** —— 没有块标记时，按 [sentenceCut] 猜
     *
     * 判据一律偏保守（宁可不切）：切长了只是标题多带几个字，
     * 而**切碎**会让正文从中间断开，比前者难看。
     *
     * @return 相对 [from] 的偏移；`-1` 表示"整行都是标题、不切"
     */
    private fun titleCut(from: Int, lineEnd: Int): Int {
        val raw = src.substring(from, lineEnd)
        val text = raw.trim()
        if (text.isEmpty()) return -1
        val lead = raw.indexOfFirst { !isSpace(it) }

        var cut = -1
        var pos = from + lead + 1
        while (pos < lineEnd) {
            if (boundaryAt(pos, from + lead, lineEnd, TITLE_RULES)) {
                cut = pos - from - lead
                break
            }
            pos++
        }

        /*
         * ⚠️ **块标记太靠后时不算数**。
         *
         * `##它想解决什么问题给定一个流体的初始状态…**牛顿第二定律在流体上的应用**——…`
         * 那个 `**` 在第 66 个字。若以它当断点，标题就是前面 66 个字 ——
         * 相当于整段话都成了标题。真正的标题只在前 12 个字里，
         * 后面是**模型把两小段粘在了一行**。
         */
        if (cut > HARD_TITLE_LIMIT) cut = -1

        // 2. 没有块标记 → 按句读 + 长度兜底（`sentenceCut` 的偏移基于 trim 后的文字）
        if (cut < 0) {
            val guessed = sentenceCut(text)
            if (guessed < 0) return -1
            return lead + guessed
        }
        if (cut < MIN_TITLE) return -1
        return lead + cut
    }

    /**
     * 普通段落：一行行读下去，直到空行 / 下一个块 / 行内粘着的块标记。
     *
     * ## ⚠️ 删除了「整行粗体 = 小节标题」这条规则（用户 2026-10-04）
     *
     * ### 原来有的规则
     *
     * `**它长什么样**` 这种**整行粗体**被当成二级标题。注释里的理由是
     * "模型大量用这种写法当标题"。
     *
     * ### 为什么删掉
     *
     * 那只是**观察到的写法**，不等于**应该渲染成标题**：
     *
     * | | 行为 |
     * |---|---|
     * | CommonMark / GFM 规范 | `**体积力**` 单独成行 = **一个粗体段落**，不是标题 |
     * | DS（remark-gfm） | 按规范 —— 粗体段落，字号同正文 |
     * | 我们（删除前） | 当成 L2 标题，字号放大 1.35 倍 |
     *
     * 于是**同一个输入，两边长相不同** —— 用户图二报的
     * 「明明是列表内容却被误解析为小标题」（`f：**体积力**`）就是这么来的。
     *
     * ### 现在的行为
     *
     * · 标题**只认 `#` 系列**（`#` ~ `######`），与规范一致
     * · 整行粗体 -> 普通段落，粗体由 `renderInline` 正常处理
     *
     * 顺带删掉了两条为此服务的补丁式判据
     *（`boldHeadingAt`、`gluedBoldSectionAt` 的"引出语"判据）——
     * 它们都是在给一条**本不该存在**的规则打补丁。
     */
    private fun readParagraph() {
        val sb = StringBuilder()
        var p = i
        while (true) {
            val lineEnd = endOfLine(p)
            val cut = firstBoundary(p, lineEnd, PARAGRAPH_RULES)
            val end = if (cut < 0) lineEnd else cut
            appendChunk(sb, src.substring(p, end))

            if (cut >= 0) {
                i = cut
                break
            }
            val next = nextLine(lineEnd)
            if (next >= src.length || blankLineAt(next) || lineStartsBlock(next)) {
                i = next
                break
            }
            p = next
        }

        val text = sb.toString().trim()
        if (text.isNotEmpty()) out += Block.Paragraph(text)
    }

    /**
     * 列表项 / 任务项 / 有序项：正文读到下一个块标记或行尾，
     * 后面的**续行**并回同一条。
     *
     * ## 续行的两种信号
     *
     * | 信号 | 例子 | 判据 |
     * |---|---|---|
     * | **有缩进** | `- 第一句\n  第二句` | 模型表达"我还在这一条里" |
     * | **行首是接续符号** | `…(∂ρ)/(∂t)\n. + ∇·(ρu)=0` | 见 [looksLikeContinuation] |
     *
     * ⚠️ 第二种是后补的（用户 2026-10-04 图三）。模型把一条公式拆成两行时
     * **第二行常常没有缩进**，而行首是个 `.` 或 `+` ——
     * 只认缩进的话它会掉成普通段落，屏幕上就是一个**孤立的 `.` 单独占一行**
     *（用户截图里正是这个）。
     *
     * @return `(正文, 下一个游标位置)`
     */
    private fun itemText(from: Int, rules: BoundaryRules): Pair<String, Int> {
        val sb = StringBuilder()
        var p = from
        while (p < src.length) {
            val lineEnd = endOfLine(p)
            val cut = firstBoundary(p, lineEnd, rules)
            val end = if (cut < 0) lineEnd else cut
            appendChunk(sb, src.substring(p, end))

            if (cut >= 0) return sb.toString() to cut

            val next = nextLine(lineEnd)
            if (next >= src.length || blankLineAt(next)) return sb.toString() to next
            if (lineStartsBlock(next)) return sb.toString() to next

            // 有缩进 → 续行；没缩进但行首是接续符号 → 也算续行
            if (!isIndented(next) && !looksLikeContinuation(next)) {
                return sb.toString() to next
            }

            // 表格行不是续行 —— 从那里并进来会把表格切碎
            if (src.substring(next, endOfLine(next)).contains('|')) {
                return sb.toString() to next
            }
            p = next
        }
        return sb.toString() to p
    }

    /**
     * 这一行看起来是**上一行的接续**吗（而不是新的一段）。
     *
     * 用于模型"把一条公式拆成两行、第二行不缩进"的写法：
     *
     * ```
     * 1. 连续性方程（质量守恒）：(∂ρ)/(∂t)
     * . + ∇·(ρu)=0
     * ```
     *
     * ## 判据：行首是**运算符或半个表达式**
     *
     * 只认那些**不可能作为一句话开头**的字符：
     *
     * | 行首 | 为什么算接续 |
     * |---|---|
     * | `.` `+` `−` `=` `)` `]` `}` | 都是"承接上文"的符号，单独起句没有意义 |
     * | `,` `;` `·` `×` `÷` | 同上 |
     *
     * ⚠️ **刻意不认**字母和汉字开头的行 —— 那多半是新的一段，
     * 并进来会把两段不相干的话粘在一起（比"少并一行"糟得多）。
     */
    private fun looksLikeContinuation(pos: Int): Boolean {
        val lineEnd = endOfLine(pos)
        var k = pos
        while (k < lineEnd && isSpace(src[k])) k++
        val c = src.getOrNull(k) ?: return false
        return c in CONTINUATION_HEADS
    }

    private fun readTask(m: Marker) {
        val (text, next) = itemText(m.contentStart, ITEM_RULES)
        val indent = indentAt(i)
        if (text.isNotEmpty()) out += Block.Task(text, m.checked, indent)
        i = next
    }

    private fun readBullet(m: Marker) {
        val (text, next) = itemText(m.contentStart, ITEM_RULES)
        val indent = indentAt(i)
        if (text.isNotEmpty()) out += Block.Bullet(text, indent)
        i = next
    }

    private fun readOrdered(m: Marker) {
        val (text, next) = itemText(m.contentStart, ORDERED_ITEM_RULES)
        val indent = indentAt(i)
        if (text.isNotEmpty()) out += Block.Ordered(m.marker, text, indent)
        i = next
    }

    /**
     * 引用。**连续的引用行合并成一个块**，否则每行各画一条短竖线，
     * 看起来是好几段独立引用。
     *
     * ⚠️ 中间夹空行时不该合并 —— 循环条件里"这一行以 `>` 开头"天然挡住了它。
     */
    private fun readQuote() {
        val text = StringBuilder()
        while (i < src.length) {
            val lineEnd = endOfLine(i)
            val line = src.substring(i, lineEnd).trim()
            if (!line.startsWith(">")) break
            val quoted = line.removePrefix(">").trim()
            if (quoted.isNotEmpty()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(quoted)
            }
            i = nextLine(lineEnd)
        }
        if (text.isNotEmpty()) out += Block.Quote(text.toString())
    }

    // ------------------------------------------------------------------ 行内断点

    /**
     * 在 [from] 起的那一行里找**第一个块断点**。
     *
     * @return 断点下标；没有则 -1
     */
    private fun firstBoundary(from: Int, lineEnd: Int, rules: BoundaryRules): Int {
        var origin = from
        while (origin < lineEnd && isSpace(src[origin])) origin++

        var pos = origin + 1
        while (pos < lineEnd) {
            /*
             * 代码 / 公式内部不是 Markdown —— 不当作断点。
             *
             * 没有这一步的话 `压力梯度\[-\nabla p\]` 里的 `-` 会被当成列表标记，
             * 一条公式就断成"正文 + 圆点"两半。
             *（标题那套规则例外：标题里出现 `$` 恰恰是"标题写完了"的信号。）
             */
            if (!rules.math) {
                val skip = inlineSpanEnd(pos, lineEnd)
                if (skip > pos) { pos = skip; continue }
            }
            if (boundaryAt(pos, origin, lineEnd, rules)) return pos
            pos++
        }
        return -1
    }

    /**
     * 位置 [pos] 处是否是一个**粘在文句中间**的块标记。
     *
     * 判定分两类：
     *
     * | 前一个字符 | 含义 | 判据 |
     * |---|---|---|
     * | 空白 | 行内的普通文字边界 | 只有"序号"算断点（`甲 2. 乙`） |
     * | 非空白 | **模型漏了换行** | 按标记各自的规矩（见 [BoundaryRules]） |
     *
     * 前一个字符是空白就一律不当断点，是为了挡住 `- 甲 - 乙` 里的破折号
     * 和 `文字 # 井号` 这类正常标点。
     */
    private fun boundaryAt(
        pos: Int,
        origin: Int,
        lineEnd: Int,
        rules: BoundaryRules,
    ): Boolean {
        if (pos <= origin || pos >= lineEnd) return false

        val prev = src[pos - 1]
        if (prev.isWhitespace()) return rules.ordered && orderedBoundaryAt(pos)

        return when (src[pos]) {
            '#' -> rules.heading && gluedHeadingAt(pos, lineEnd)

            /*
             * 行内的 `**`（粗体）**不再是块断点**。
             *
             * 原来这里有一条 `rules.boldSection && gluedBoldSectionAt(...)`，
             * 用来把粘在句尾的粗体切成"小节标题"。整行粗体当标题这条规则
             * 已经删掉了（它没有规范依据，见 [readParagraph] 的说明），
             * 这条断点也就失去了存在理由 —— 而且它正是把
             * `- f：**体积力**` 切成两块的元凶。
             *
             * 现在 `**` 只在**标题内部**当断点（[TITLE_RULES] 的 `boldAnywhere`），
             * 用来把 `##标题**正文**` 这种粘连切开。
             */
            '*' -> rules.boldAnywhere &&
                src.getOrNull(pos + 1) == '*' && prev != '*'

            '-' -> (rules.bullet && gluedBulletAt(pos, origin, rules.bulletTight)) ||
                (rules.rule && trailingRuleAt(pos, lineEnd))

            '_' -> rules.rule && trailingRuleAt(pos, lineEnd)
            '|' -> rules.pipe
            '$' -> rules.math
            else -> false
        }
    }

    /**
     * 粘在正文里的标题标记（`…至今未解##常见简化`）。
     *
     * 判据刻意保守：井号**最多 6 个**，后面必须是空白或中日韩字符 ——
     * 于是 `C#` 不会在 `#` 处断开（`#` 后面是行尾或非中文时不算）。
     */
    private fun gluedHeadingAt(pos: Int, lineEnd: Int): Boolean {
        if (src.getOrNull(pos - 1) == '#') return false
        val hashes = runLength(pos, '#')
        if (hashes > MAX_HEADING_LEVEL) return false
        val next = src.getOrNull(pos + hashes) ?: return false
        if (pos + hashes >= lineEnd) return false
        return isSpace(next) || next.code >= 0x2E80
    }

    /**
     * 粘在文句中间的列表标记（`方程组- **注意**`、`报告-第一节`）。
     *
     * 这里问的是**反面问题**："它像不像词内连接" —— 这个集合是封闭的、
     * 不会越加越长：
     *
     * | 形状 | 例子 | 判定 |
     * |---|---|---|
     * | 连接符后面是空白 | `速度场- 密度` | **是**分隔 |
     * | 两侧都是拉丁字母/数字 | `COVID-19` / `3-4` | 词内连接 |
     * | 左手边 ≤ 2 个字符 | `纳维-斯托克斯` | 词内连接 |
     * | 其余 | `报告-第一节` | **是**分隔 |
     *
     * 正着问（"后面像不像新条目的开头"）是**开放式**问题 ——
     * 用户的真实输出里 `-` 后面可以是 `*`、`**`、任意汉字，白名单永远加不完
     *（这个 bug 用户报过三次，前两版就是栽在这里）。
     *
     * @param tight 两侧都紧贴时算不算分隔。段落里**不算** ——
     *   否则 `压力梯度\[-\nabla p\]`、`state-of-the-art` 这类正文会被切开。
     */
    private fun gluedBulletAt(pos: Int, origin: Int, tight: Boolean): Boolean {
        val before = src[pos - 1]
        if (before.isWhitespace()) return false

        val after = src.getOrNull(pos + 1) ?: return false
        if (isSpace(after)) return true
        if (!tight) return false

        // 两侧都紧贴：先排掉"词内连接"这个封闭集合
        if (isAsciiWord(before) && isAsciiWord(after)) return false
        return pos - origin > WORD_JOIN_MAX
    }

    
    
    /**
     * 位置 [pos] 上的数字是不是**挤在一行里的**序号（`1. 甲 2. 乙`）。
     *
     * 序号的前面必须是"一个空格 + 正文"，用来和缩进（`   2. 甲`）区分开。
     * 只认 1~2 位数字：三位以上在正文里太容易误判（`见图 100. 页`）。
     */
    private fun orderedBoundaryAt(pos: Int): Boolean {
        if (src.getOrNull(pos - 1) != ' ') return false
        val before = src.getOrNull(pos - 2) ?: return false
        if (before.isWhitespace()) return false

        var n = 0
        while (n < 2 && src.getOrNull(pos + n)?.isDigit() == true) n++
        if (n == 0) return false
        if (src.getOrNull(pos + n)?.isDigit() == true) return false

        val sep = src.getOrNull(pos + n) ?: return false
        if (sep != '.' && sep != ')' && sep != '、') return false
        val after = src.getOrNull(pos + n + 1)
        return after == null || isSpace(after)
    }

    /** 行尾**粘着的**分隔线（`工程计算---`）。 */
    private fun trailingRuleAt(pos: Int, lineEnd: Int): Boolean {
        val c = src[pos]
        if (c != '-' && c != '_') return false
        if (lineEnd - pos < 3) return false
        for (k in pos until lineEnd) {
            if (src[k] != c) return false
        }
        return true
    }

    
    /**
     * [pos] 这一行会不会开一个新块（供段落 / 条目的续行判定用）。
     *
     * 行首的缩进要先跳过 —— 模型会缩进着写子条目。
     */
    private fun lineStartsBlock(pos: Int): Boolean {
        val lineEnd = endOfLine(pos)
        var k = pos
        while (k < lineEnd && isSpace(src[k])) k++
        if (k >= lineEnd) return false

        return fenceAt(k) != null ||
            mathOpenAt(k, lineEnd) != null ||
            tableAt(k) ||
            ruleAt(k) ||
            headingAt(k) ||
            taskAt(k) != null ||
            bulletAt(k) != null ||
            orderedAt(k) != null ||
            quoteAt(k)
    }

    /**
     * [pos] 处若是行内代码 / 公式的**开标记**，返回它收尾之后的位置；否则 -1。
     *
     * 用来把"公式/代码内部"整段跳过 —— 里面的 `-`、`#`、`|` 都不是 Markdown。
     */
    private fun inlineSpanEnd(pos: Int, lineEnd: Int): Int {
        when (src[pos]) {
            '`' -> {
                val end = src.indexOf('`', pos + 1)
                return if (end in (pos + 1) until lineEnd) end + 1 else -1
            }
            '$' -> {
                // `$$…$$` 先判，再判单个 `$`（单个要过"不是钱数"那道闸）
                val open = if (src.startsWith("$$", pos)) 2 else 1
                val close = if (open == 2) "$$" else "$"
                val end = src.indexOf(close, pos + open)

                /*
                 * ⚠️⚠️ **`end <= pos` 必须先挡住**（这一行写掉过一次崩溃）。
                 *
                 * 这一行里没有收尾符号时 `indexOf` 返回 **-1**，而下面那句
                 * `end >= lineEnd` **挡不住它**（-1 当然小于 lineEnd）。
                 * 于是 -1 被当成下标传进 [isInlineMathSpan]，
                 * 它去读 `src[end - 1]` —— 就是 `charAt(-2)`：
                 *
                 * ```
                 * StringIndexOutOfBoundsException: length=404; index=-2
                 * ```
                 *
                 * 用户在真机上遇到的正是这一条（触发输入：`-甲\n$a-b$`）。
                 * 这类"负下标"没有症状轻重之分 —— 直接把整个界面崩掉，
                 * 所以守它的用例是 [MarkdownFuzzTest]。
                 */
                if (end <= pos || end >= lineEnd) return -1
                if (open == 1 && !isInlineMathSpan(src, pos, end)) return -1
                return end + open
            }
            '\\' -> {
                val close = when {
                    src.startsWith("\\(", pos) -> "\\)"
                    src.startsWith("\\[", pos) -> "\\]"
                    else -> return -1
                }
                val end = src.indexOf(close, pos + 2)
                return if (end in (pos + 2) until lineEnd) end + 2 else -1
            }
            else -> return -1
        }
    }

    /** 把一段行内原文接进段落缓冲（用空格连接软换行）。 */
    private fun appendChunk(sb: StringBuilder, raw: String) {
        val chunk = raw.trim()
        if (chunk.isEmpty()) return
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(chunk)
    }
}

/** 列表标记的解析结果。 */
private class Marker(
    val marker: String,
    val contentStart: Int,
    val checked: Boolean = false,
)

/**
 * 一行里允许哪几类标记把它切成两块。
 *
 * ## 为什么要分好几套
 *
 * **同一串字符在不同位置含义不同**：
 *
 * | 位置 | `-` 的含义 |
 * |---|---|
 * | 段落里的 `方程组- 注意` | 新的一条列表 |
 * | 标题里的 `各项在说什么- $x$` | 标题写完了，后面是条目 |
 * | `\nabla p\]` 里的 `-` | 公式的一部分，**什么都不是** |
 *
 * @param bullet `-` 后面跟空白 = 分隔（三处都要）
 * @param bulletTight `-` 两侧都紧贴也算分隔（只在列表项里 —— 段落里太危险）
 * @param heading 粘着的 `##`
 * @param boldSection 粘着的、**自成一行**的 `**标题**`
 * @param boldAnywhere 粘着的 `**`（标题专用：标题里不该有粗体，见到就收）
 * @param ordered 挤在一行的 `1. 甲 2. 乙`
 * @param math `$`（标题专用）
 * @param pipe `|`（标题专用：标题粘着表格头）
 * @param rule 行尾粘着的 `---` / `___`
 */
private class BoundaryRules(
    val bullet: Boolean = false,
    val bulletTight: Boolean = false,
    val heading: Boolean = false,
    val boldAnywhere: Boolean = false,
    val ordered: Boolean = false,
    val math: Boolean = false,
    val pipe: Boolean = false,
    val rule: Boolean = false,
)

private val PARAGRAPH_RULES = BoundaryRules(
    bullet = true,
    heading = true,
    rule = true,
)

private val ITEM_RULES = BoundaryRules(
    bullet = true,
    bulletTight = true,
    heading = true,
    rule = true,
)

private val ORDERED_ITEM_RULES = BoundaryRules(
    bullet = true,
    bulletTight = true,
    heading = true,
    ordered = true,
    rule = true,
)

private val TITLE_RULES = BoundaryRules(
    bullet = true,
    heading = true,
    boldAnywhere = true,
    math = true,
    pipe = true,
    rule = true,
)

/** 无序列表的标记字符。 */
private const val BULLET_CHARS = "-+*"

/**
 * 行首出现这些字符时，这一行是**上一行的接续**（见 [looksLikeContinuation]）。
 *
 * 都是"承接上文"的符号：单独起句没有意义，所以只会是被折行拆开的后半截。
 */
private const val CONTINUATION_HEADS = ".+,−-=)]},;·×÷"

/** 标题级数的上限（CommonMark 的 `######`）。 */
private const val MAX_HEADING_LEVEL = 6





/** 词内连接的左手边最长多少字符（超过就按"条目分隔"处理，见 `gluedBulletAt`）。 */
private const val WORD_JOIN_MAX = 2

/** 行内空白（不含换行 —— 换行是"另一行"，另有判据）。 */
private fun isSpace(c: Char?): Boolean = c == ' ' || c == '\t'

/** 拉丁字母或数字（用于区分 `COVID-19` 与 `报告-第一节`）。 */
private fun isAsciiWord(c: Char): Boolean =
    c.code < 0x2E80 && (c.isLetterOrDigit())

/** `\begin{` —— 7 个字符。 */
private const val BEGIN_PREFIX = "\\begin{"

/**
 * 会被当成**块级公式**处理的 LaTeX 环境。
 *
 * `align` / `gather` 的行分隔（`\\`）与对齐符（`&`）我们不排版，
 * 但内容仍然按一条公式渲染 —— 总比把整段源码亮出来好。
 */
private val MATH_ENVS = setOf(
    "equation", "equation*", "displaymath", "math",
    "align", "align*", "aligned", "gather", "gather*",
    "multline", "multline*", "eqnarray", "eqnarray*",
    "alignat", "alignat*", "split", "cases",
)

/**
 * 标题最少多少字。
 *
 * 原文里最短的小节标题是「它从哪来」= 4 字。取 3 留一点余量，
 * 但能挡住"在第一个字就断开"这种明显错误的切法。
 */
private const val MIN_TITLE = 3

/**
 * 标题最多多少字（**句中停顿**的切点上限）。
 *
 * 原文里最长的小节标题是「为什么它这么有名」= 8 字。取 **12** —— 比它长一截，
 * 是为了让"切错"更少见（切长了只是标题多带几个字，切短了正文会从中间断开）。
 *
 * ⚠️ 只约束 `，` `、` `；` 这类**句中停顿**。
 * 句末的 `。` `？` 另有更宽松的 [HARD_TITLE_LIMIT]。
 */
private const val MAX_TITLE = 12

/**
 * 标题的**硬上限**（句末标点的宽容上限）。
 *
 * `##它想解决什么问题给定一个流体的初始状态和边界条件，能不能算出…？`
 * 这一句里**第一个句末标点 `？` 在第 40 多个字** —— 远超 [MAX_TITLE]，
 * 但它确实是这一句的结尾。两个选择：
 *
 * | 做法 | 结果 |
 * |---|---|
 * | 不认它 | 标题退化成前 12 个字 `它想解决什么问题给定一个` ← **从词中间断开** |
 * | **认它** | 标题是整句问句，但**一个字没断** |
 *
 * 选后者（与「常见形式（…）」那条同一个取舍：宁可标题长，不要断在词中间）。
 */
private const val HARD_TITLE_LIMIT = 40

/**
 * 没有块标记时，为 `##标题正文` 找一个断点。
 *
 * ## 三类形状，三种切法
 *
 * | 原文 | 想要的标题 | 切法 |
 * |---|---|---|
 * | `常见形式（不可压缩、常密度）` | **整行**（本来就只有标题） | 括号闭合就整行 |
 * | `它从哪来流体里随便取一个小块，它受力包括：` | `它从哪来` | 只能猜 |
 * | `各项在说什么- $x$：速度…` | `各项在说什么` | 由 `-` 块标记切（不走这里） |
 *
 * ## 判据
 *
 * 1. **括号配对**：标题里开了 `（(` 且在同一行闭合 → **在闭合处收住**
 *    （`常见形式（不可压缩、常密度）` → 整行，一个字不切）。
 *    ⚠️ 这一条必须**排在最前**：放后面的话顿号那条会先在 `、` 处把标题切成
 *    `常见形式（不可压缩、` —— 把括号劈开，比"整行当标题"糟得多。
 *    ⚠️ 括号**没闭合**也整行不切（硬切同样会把括号劈开）。
 * 2. **句末标点**（`？` `。` `！`）：在**第一个**处收住 ——
 *    标题不会以它结尾后又紧跟正文。上限放宽到 [HARD_TITLE_LIMIT]。
 * 3. **句中停顿**（`，` `、` `；`）：只在长度落在 [MIN_TITLE]～[MAX_TITLE] 时收。
 * 4. **长度兜底**：都没有才硬切 —— 会从词中间断（`它想解决什么问题给定一个`），
 *    但那**远好于**把整段变成标题（后者把正文结构整个毁掉）。
 *
 * @return 断点下标（等于 `text.length` 表示整行都是标题）；`-1` 表示不切
 */
private fun sentenceCut(text: String): Int {
    // ---- 1. 括号优先
    val open = text.indexOfFirst { it == '（' || it == '(' }
    if (open >= 0) {
        val close = text.indexOfFirst { it == '）' || it == ')' }
        if (close > open) {
            val afterClose = close + 1
            val rest = text.substring(afterClose).trim()
            // 括号后没有内容（或只有标点）→ **整行就是标题**，不切
            if (rest.isEmpty() || rest.all { it in "。，、！？：;；" }) return -1
            // 括号后还有正文 → 在闭合处收住
            if (afterClose >= MIN_TITLE) return afterClose
        }
        return -1
    }

    // ---- 2/3. 标点处切
    var cutAt = -1
    for (i in text.indices) {
        val c = text[i]
        val end = i + 1
        // 太靠前（前两个字就有标点）→ 不是标题尾
        if (end < MIN_TITLE) continue

        // 句子结束符：一旦超过 MIN_TITLE 就收（哪怕超过 MAX_TITLE）
        if (c == '？' || c == '。' || c == '！' || c == '?' || c == '!') {
            if (end <= HARD_TITLE_LIMIT) { cutAt = end; break }
            continue
        }
        // 句中停顿：只在长度合理时收（超过 MAX_TITLE 说明它已经是正文里的逗号）
        if (c == '，' || c == '、' || c == '；' || c == ',' || c == ';') {
            if (end <= MAX_TITLE) cutAt = end
            continue
        }
        // 冒号：标题常以它收尾（`各项在说什么：`）
        if (c == '：' || c == ':') {
            if (end <= HARD_TITLE_LIMIT) cutAt = end
            continue
        }
    }
    if (cutAt > 0) return cutAt

    // ---- 4. 长度兜底（⚠️ 必须存在，不能返回 -1 —— 那会把整段变成标题）
    if (text.length > MAX_TITLE) return MAX_TITLE
    return -1
}

/** 表格分隔行：`|---|---|`、`|:--:|`、`|-|-|` 等。 */
private fun isTableSeparator(trimmed: String): Boolean {
    if (!trimmed.contains('-')) return false
    val cells = splitTableRow(trimmed)
    if (cells.isEmpty()) return false
    // 每个单元格只能是冒号、短横、空格
    return cells.all { cell -> cell.all { it == '-' || it == ':' || it.isWhitespace() } }
}

/**
 * 从分隔行里读每列的**对齐方式**（CommonMark 用冒号表达）。
 *
 * ```
 * |:---|    左对齐（默认，写不写冒号一样）
 * |:--:|    居中
 * |---:|    右对齐
 * ```
 */
private fun parseTableAlignments(separator: String): List<TableAlign> =
    splitTableRow(separator).map { cell ->
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        when {
            left && right -> TableAlign.CENTER
            right -> TableAlign.END
            else -> TableAlign.START
        }
    }

/**
 * 拆表格行：去掉首尾的 `|`，再按 `|` 切。
 *
 * ## 认 `\|` 转义
 *
 * 单元格里本来就可能含竖线（写「a \| b」表示"a 或 b"）。直接 `split('|')`
 * 会把那个格子劈成两半 —— 表格从此错位一整行。
 *
 * 拆的时候把 `\|` 还原成 `|`，因为渲染层拿到的是**单元格内容**，
 * 转义符在那个层面已经没有意义了。
 */
private fun splitTableRow(trimmed: String): List<String> {
    val body = trimmed.trim('|')
    val cells = ArrayList<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && body.getOrNull(i + 1) == '|' -> {
                cell.append('|')
                i += 2
            }

            c == '|' -> {
                cells += cell.toString().trim()
                cell.clear()
                i++
            }

            else -> {
                cell.append(c)
                i++
            }
        }
    }
    cells += cell.toString().trim()
    return cells
}
