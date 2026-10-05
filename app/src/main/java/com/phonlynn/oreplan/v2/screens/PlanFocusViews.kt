package com.phonlynn.oreplan.v2.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.focus.HeatCell
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.v2.components.VProgress
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import java.time.LocalDate

/**
 * 规划模块的专属可视化件 —— 都是「设计稿里有、但别处用不到」的东西：
 * 周柱状图、专注热力图、环形进度、目标卡、里程碑路线。
 *
 * 不放进公用 `components/`：它们全都认识 `GoalType` / `FocusStats` 这些领域概念，
 * 而公用层应当与业务无关。
 */

// ---------------------------------------------------------------- 周柱状图

/**
 * 本周专注柱状图（设计稿 Focus Card 下半部分）。
 *
 * 造型取自设计稿：7 根 r3 圆角条，高度按本周最大值归一，
 * 今天那根用 accent 实色、其余半透明；日期标签在下方，今天加粗。
 */
@Composable
fun FocusWeekBars(
    dailyMinutes: List<Int>,
    weekStart: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
    barColor: Color = VColors.accent,
) {
    val max = dailyMinutes.maxOrNull()?.coerceAtLeast(1) ?: 1
    val minHeight = 7.dp
    val maxHeight = 28.dp
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        dailyMinutes.forEachIndexed { index, minutes ->
            val date = weekStart.plusDays(index.toLong())
            val isToday = date == today
            val fraction = (minutes.toFloat() / max).coerceIn(0f, 1f)
            val height = minHeight + (maxHeight - minHeight) * fraction
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .background(
                            when {
                                minutes <= 0 -> VColors.surface2
                                isToday -> barColor
                                else -> barColor.copy(alpha = 0.45f)
                            },
                            RoundedCornerShape(3.dp),
                        ),
                )
                VText(
                    TermClock.weekdayLabel(date.dayOfWeek),
                    VTypo.micro.copy(fontWeight = if (isToday) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal),
                    color = if (isToday) VColors.ink else VColors.ink3,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 热力图

/**
 * 热力图的格子尺寸与间距 —— **唯一来源**。
 *
 * 为什么必须是常量而不是各处的默认参数：专注记录页要按滚动偏移反推
 * 「现在看到的是哪一年」，那需要**格宽 + 列间距**这两个数。
 * 散着写两份，改了热力图却忘了改换算，年份标签就会悄悄算错
 * （显示一个错的年份，比不显示更糟）。
 */
object HeatmapMetrics {
    /** 格子边长。设计稿 `Cell` = 18×18，圆角 6。 */
    val Cell: Dp = 18.dp

    /**
     * 格子间距：**横向与纵向都是 7**（设计稿实测）。
     *
     * 列起点是 0/25/50/…（=18+7），行起点是 0/25/50/…（同样 =18+7）。
     * 我此前把它当成"标签行高"叠在顶部、又单独调过列宽，都是没量设计稿的后果。
     */
    val Gap: Dp = 7.dp

    /** 相邻两列起点之间的距离。 */
    val ColumnPitch: Dp = Cell + Gap

    /**
     * 月份标签行的高度。设计稿 `4月` 那个文字盒 = 16×14。
     *
     * 标签在**网格下方**（设计稿 y174；网格末行 150 + 18 = 168），不是上方。
     */
    val MonthLabelHeight: Dp = 14.dp

    /**
     * 网格末行底边 → 月份标签顶边的距离。设计稿实测 **6**
     * （末行 y150..168，标签 y174）。它与格子之间的 [Gap] = 7 **不是同一个数**——
     * 设计稿是绝对定位，两个数各是各的，别互相推导。
     */
    val LabelGap: Dp = 6.dp

    /**
     * 整个热力图的高度：7 行格子 + 6 条格间距 + 标签前那条缝 + 标签行高。
     * 设计稿滚动区 `IxT3A` = 334×192，与这个算式一致（175+6+14 = 195 是容器给的高度，
     * 内容 192 —— 差的 3 是滚动区自身的内衬，不算在内容里）。
     */
    val TotalHeight: Dp get() = Cell * 7 + Gap * 6 + LabelGap + MonthLabelHeight

    /**
     * 热力图列数（一周一列）：**一整年**。
     *
     * 两个调用方（专注记录页、目标详情的习惯热力图）都用它 ——
     * 一边 52 一边 17 会让「左右拖看历史」这个交互一处有一处没有
     * （用户 2026-10-02 指出详情页没跟上）。
     * 设计稿只画了 17 列（滚动区裁掉了后面），真实数据要放一整年。
     */
    const val Weeks: Int = 52

    /**
     * 热力图左右两端必须留的余量。
     *
     * 为什么需要：选中框/今日框是 `drawBehind` 画的 stroke，**以格子边界为中心**、
     * 两侧各溢出一半线宽；而横向滚动容器会裁剪越界内容 ——
     * 于是最右那列的框右边被切掉（用户 2026-10-02 报的「外边框右侧被截断」）。
     *
     * 取 4dp：既容纳线宽，也让首尾两列不贴着卡片边缘。
     * 这是**动态部分**（滚动 + 选中框）的必需补偿，不受设计稿的静态间距约束。
     *
     * ⚠️ 这个余量由 [FocusHeatmap] **自己**在两端加上，**不要挪到调用方** ——
     * 挪出去之后，每新增一个调用点就会重新出现截断（详情页那次就是这么漏的）。
     */
    val EdgeRoom: Dp = 4.dp

    /**
     * 选中框：一条**细的**边框，**悬浮在格子外侧**（用户 2026-10-02）。
     *
     * 三个数一起定，改一个要回头看另外两个：
     *
     * | 量 | 值 | 作用 |
     * | --- | --- | --- |
     * | [SelectionStroke] | 2dp | 线宽。3dp 用户反馈「太粗」，1dp 在 18dp 的格子上又几乎看不见 |
     * | [SelectionExpand] | 1dp | 路径相对格子边界**再外扩**一点，让框与格子之间留出空隙 |
     * | [Gap] | 7dp | 列间隙上限：外扩 + 半个线宽必须明显小于它 |
     *
     * 外沿到格子边界 = 外扩 + 线宽/2 = 1 + 1 = **2dp**；
     * 相邻两格都选中时，两个框之间还剩 7 − 2×2 = **3dp** —— 不会碰到一起。
     */
    val SelectionStroke: Dp = 2.dp

    /** 选中框路径相对格子边界的外扩量。见 [SelectionStroke] 的取值表。 */
    val SelectionExpand: Dp = 1.dp
}

/**
 * 专注热力图（设计稿「专注热力图」卡）。
 *
 * **按周分列**：一列一周、七行星期，横向可滚（设计稿里最后一列被裁掉了，
 * 说明本来就打算横向滚动，见 [FocusRecordsScreen] 的 `horizontalScroll`）。
 *
 * 两端的 [HeatmapMetrics.EdgeRoom] **由本组件自己加**（`Modifier.padding`）——
 * 这样任何调用方都不会再出现「最右一列的外边框被滚动容器裁掉」。
 *
 * ## 交互（用户 2026-10-02 要求）
 *
 * 1. **左右拖动**看更早的日子 —— 由调用方的 `horizontalScroll` 提供；
 * 2. **点某一格 = 选中那一天**（实色方框），下方列表跟着换；
 * 3. 未来的日子（今天之后）**不响应点击**：那里没有记录可看，
 *    点上去只把选中框挪到一个永远空白的日子，是误导。
 *
 * 「今天」与「选中」是两回事，所以框也分两种：
 * 选中 = 实色粗框（当前在看哪天）；今天 = 细框（时间轴上的锚点）。
 * 两者重合时只画选中框。
 */
@Composable
fun FocusHeatmap(
    columns: List<List<HeatCell>>,
    monthLabels: Map<Int, String>,
    modifier: Modifier = Modifier,
    cell: Dp = HeatmapMetrics.Cell,
    gap: Dp = HeatmapMetrics.Gap,
    today: LocalDate? = null,
    /** 当前选中的日期（画实色框）。 */
    selected: LocalDate? = null,
    /** 点某一格。传 null 表示纯展示（如目标详情里的打卡热力图）。 */
    onSelect: ((LocalDate) -> Unit)? = null,
    activeColor: Color = VColors.accent,
) {
    // 结构严格按设计稿 `IxT3A`（滚动区 334×192）：
    //
    //   Row(spacedBy(gap))                    ← 列间距 7
    //     Column(spacedBy(gap))               ← **行间距也是 7**（设计稿实测 0/25/50/…）
    //       7 × Cell(18×18)
    //     ...
    //   Row(spacedBy(gap))                    ← 月份标签行，在**网格下方**
    //     Text 18 宽、字号 10、居中
    //
    //   · 标签行与网格之间是 `LabelGap` = 6（设计稿末行 168 → 标签 174），
    //     与格间距 7 不是同一个数 —— 设计稿是绝对定位，两个数各是各的；
    //   · 标签列宽 = 格宽（18）。设计稿 `4月` 的文字盒是 16 宽、位于列起点 −1，
    //     即"在列上居中"；宽度锁成格宽是同一效果的排版写法，
    //     且**不会**再把列撑宽（两个约束互不干扰）。
    Row(
        modifier = modifier.padding(horizontal = HeatmapMetrics.EdgeRoom),
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(HeatmapMetrics.LabelGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                columns.forEach { column ->
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        column.forEach { entry ->
                            HeatCellBox(
                                entry = entry,
                                cell = cell,
                                isToday = today != null && entry.date == today,
                                isSelected = selected != null && entry.date == selected,
                                selectable = onSelect != null && (today == null || !entry.date.isAfter(today)),
                                activeColor = activeColor,
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                columns.forEachIndexed { index, _ ->
                    Box(
                        Modifier
                            .width(cell)
                            .height(HeatmapMetrics.MonthLabelHeight),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        monthLabels[index]?.let {
                            VText(
                                it,
                                // letterSpacing 归零：三字标签（10/11/12 月）本来就只有
                                // 一点余量，默认字距会把它顶到邻列上。
                                VTypo.micro.copy(letterSpacing = 0.sp),
                                color = VColors.ink3,
                                maxLines = 1,
                                align = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 热力图的一格。
 *
 * 抽成独立 composable 而不是写在 `forEach` 里：`Modifier.clickable` 是 composed 的，
 * 在循环里内联会让每格各建一次交互源、而且阅读起来分不清「这格是哪天」。
 * 这里 [entry] 是参数，「哪一天」在函数签名上就看得见。
 */
@Composable
private fun HeatCellBox(
    entry: HeatCell,
    cell: Dp,
    isToday: Boolean,
    isSelected: Boolean,
    selectable: Boolean,
    activeColor: Color,
    onSelect: ((LocalDate) -> Unit)?,
) {
    val date = entry.date
    /*
     * 底色**不因选中而改变**（用户 2026-10-02）。
     *
     * 之前的做法是「选中时把最低档抬到 accentSoft」，那是为了让框里的格子看起来
     * 不是空心的。用户明确否掉了：**选中只体现为外框，格子本身仍按当天专注强度上色**
     * —— 否则「选中」和「那天有专注」两件事会混在一起，读不出强度。
     */
    val fill = heatColor(entry.level, activeColor)
    Box(
        Modifier
            .size(cell)
            .background(fill, RoundedCornerShape(6.dp))
            .then(
                when {
                    // 选中框：细边框**悬浮在格子外侧**。
                    // 路径先外扩 SelectionExpand，线宽再以路径为中心铺开 ——
                    // 于是框整体落在格子外面一圈，且与格子之间留出空隙（不是紧贴）。
                    isSelected -> Modifier.drawBehind {
                        val expand = HeatmapMetrics.SelectionExpand.toPx()
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset(-expand, -expand),
                            size = Size(size.width + expand * 2f, size.height + expand * 2f),
                            cornerRadius = CornerRadius(13f, 13f),
                            style = Stroke(width = HeatmapMetrics.SelectionStroke.toPx()),
                        )
                    }
                    // 今天：更细更淡，且**不外扩**（贴着格子边界）——
                    // 与选中框形成「今天 = 内圈细线 / 选中 = 外圈稍亮」的层次，
                    // 两个状态同时出现时也认得出来。
                    isToday -> Modifier.drawBehind {
                        drawRoundRect(
                            color = activeColor.copy(alpha = 0.5f),
                            cornerRadius = CornerRadius(13f, 13f),
                            style = Stroke(width = 1.25f),
                        )
                    }
                    else -> Modifier
                },
            )
            .then(
                if (selectable && onSelect != null) {
                    Modifier.vPressable(scaleDown = 0.8f) { onSelect(date) }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = buildString {
                    append(date.monthValue).append("月").append(date.dayOfMonth).append("日")
                    append("，专注 ").append(entry.minutes).append(" 分钟")
                    if (isSelected) append("，已选中")
                }
            },
    )
}

/** 五档热力色。档 0 与档 1 必须能看出区别（「没专注」与「专注了一点」）。 */
fun heatColor(level: Int, activeColor: Color): Color = when (level) {
    0 -> VColors.surface2
    1 -> activeColor.copy(alpha = 0.25f)
    2 -> activeColor.copy(alpha = 0.45f)
    3 -> activeColor.copy(alpha = 0.70f)
    else -> activeColor
}

// ---------------------------------------------------------------- 环形进度

/**
 * 专注中页的大环（设计稿 Ring 244x244）。
 *
 * 用 `drawBehind` 画两段弧而不是叠两个椭圆：椭圆的 `sweepAngle` 是静态属性，
 * 而这里需要**每秒都在动的进度**，只能走 draw 阶段。
 */
@Composable
fun FocusRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 244.dp,
    strokeWidth: Dp = 10.dp,
    trackColor: Color = VColors.surface2,
    progressColor: Color = VColors.accent,
    content: @Composable () -> Unit,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), VMotion.settle(), label = "focusRing")
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(diameter)
                .drawBehind {
                    val stroke = strokeWidth.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (animated > 0f) {
                        drawArc(
                            color = progressColor,
                            startAngle = -90f,
                            sweepAngle = 360f * animated,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                },
        )
        content()
    }
}

/** 悬浮窗上的迷你环（设计稿 Float Window 里的 56x56）。 */
@Composable
fun FocusMiniRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 56.dp,
    strokeWidth: Dp = 6.dp,
    progressColor: Color = VColors.accent,
    trackColor: Color = VColors.surface2,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), VMotion.settle(), label = "miniRing")
    Box(
        modifier
            .size(diameter)
            .drawBehind {
                val stroke = strokeWidth.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            },
    )
}

// ---------------------------------------------------------------- 目标卡

/**
 * 目标卡片上要画的一切 —— **在 ViewModel 里算好**，界面只负责画。
 *
 * 为什么不在 Composable 里现算：三种类型的进度口径不同（步骤数 / 数量日志 / 打卡日集合），
 * 现算就意味着要把这些集合一起传进 UI 层。算好之后卡片组件不认识 `Item`，
 * 预览与复用都简单。
 */
data class GoalCardData(
    val id: String,
    val title: String,
    val type: GoalType,
    /** 右上角：分步/数量是剩余时间，习惯是连续天数。 */
    val rightText: String?,
    /** 进度条比例（0..1）。习惯卡不用它。 */
    val fraction: Float,
    /** 百分比数字（分步/数量显示；习惯卡为 null）。 */
    val percent: Int?,
    /** 脚注：`分步 · 已完成 8 / 12 步`。 */
    val foot: String,
    /** 习惯卡的七格周条。 */
    val weekStrip: List<Boolean>? = null,
    /** 习惯卡今天那一格的位置（0..6）。 */
    val todayIndex: Int = -1,
    /** 右上角文案是否用 accent 色（习惯的「连续 N 天」带火焰感）。 */
    val rightEmphasis: Boolean = false,
    /** 归档页复用同一张卡时用得到。 */
    val archived: Boolean = false,
)

/**
 * 规划首页的目标卡（设计稿 `Goal xxx` 350x110）。
 *
 * 三种类型的差异只在中间那条进度表达上：
 * - 分步/数量：一条细进度条（r3，h6）；
 * - 习惯：7 格周条（done=amber / 今天未完成=amber-soft / 其余=surface2）。
 */
@Composable
fun GoalCard(
    data: GoalCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = PlanUi.typeColor(data.type)
    val soft = PlanUi.typeSoft(data.type)
    Column(
        modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(18.dp))
            .vPressable(scaleDown = 0.99f, onClick = onClick)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(soft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(PlanUi.typeIcon(data.type), contentDescription = null, modifier = Modifier.size(15.dp), tint = color)
            }
            Spacer(Modifier.width(10.dp))
            VText(data.title, VTypo.cardTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            data.rightText?.let {
                VText(
                    it,
                    if (data.rightEmphasis) VTypo.numPercent else VTypo.caption,
                    color = if (data.rightEmphasis) VColors.accent else color,
                    maxLines = 1,
                )
            }
        }

        if (data.weekStrip != null) {
            HabitWeekStrip(data.weekStrip, data.todayIndex, color)
        } else {
            // 走公用进度条（`animated = false`）：这里表达的是一个**静态比例**，
            // 从左往右长出来会像在加载。见 VProgress 的注释。
            VProgress(fraction = data.fraction, height = 6.dp, color = color, animated = false)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText(data.foot, VTypo.caption, color = VColors.ink3, maxLines = 1, modifier = Modifier.weight(1f))
            data.percent?.let {
                Spacer(Modifier.width(8.dp))
                VText("$it%", VTypo.numPercent, color = color)
            }
        }
    }
}

/** 习惯卡的七格周条：7 段等宽，gap 6。 */
@Composable
private fun HabitWeekStrip(week: List<Boolean>, todayIndex: Int, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        week.forEachIndexed { index, done ->
            val bg = when {
                done -> color
                index == todayIndex -> color.copy(alpha = 0.28f)
                else -> VColors.surface2
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(bg, RoundedCornerShape(3.dp)),
            )
        }
    }
}

// ---------------------------------------------------------------- 里程碑路线

/** 路线里一行的高度：24 节点 + 上下留白。 */
private val MilestoneRowHeight = 58.dp

data class MilestoneNode(
    val id: String,
    val title: String,
    /** 完成时刻 / 未开始的截止日 / 进行中提示。 */
    val meta: String?,
    val done: Boolean,
    val current: Boolean,
)

/**
 * 里程碑路线（设计稿「里程碑路线」326x232）。
 *
 * 造型：左侧一列节点（24x24 圆），节点之间用 2px 竖线连起来；
 * 右侧是标题 + 元信息。节点三态：已完成（accent 实底 + 对勾）、
 * 进行中（accent 淡底 + 中心点）、未开始（surface2 实底）。
 */
@Composable
fun MilestoneRoadmap(
    nodes: List<MilestoneNode>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = VColors.accent,
) {
    Column(modifier.fillMaxWidth()) {
        nodes.forEachIndexed { index, node ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(
                                when {
                                    node.done -> color
                                    node.current -> color.copy(alpha = 0.18f)
                                    else -> VColors.surface2
                                },
                                CircleShape,
                            )
                            .vPressable(scaleDown = 0.88f) { onToggle(node.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            node.done -> Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                            node.current -> Box(Modifier.size(8.dp).background(color, CircleShape))
                            else -> Unit
                        }
                    }
                    if (index != nodes.lastIndex) {
                        Box(Modifier.width(2.dp).height(MilestoneRowHeight - 24.dp).background(VColors.line))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .height(MilestoneRowHeight)
                        .vPressable(scaleDown = 0.99f) { onToggle(node.id) },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Spacer(Modifier.height(1.dp))
                    VText(
                        node.title,
                        if (node.current) VTypo.bodyMed else VTypo.body,
                        color = if (node.done || node.current) VColors.ink else VColors.ink2,
                        maxLines = 1,
                    )
                    node.meta?.let {
                        VText(it, VTypo.caption, color = if (node.current) color else VColors.ink3, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 柱状图（数量目标趋势）

/**
 * 数量目标的「近 8 周」柱状图（设计稿「进度趋势」卡）。
 *
 * 与 [FocusWeekBars] 分开写：那个是 7 根、按周内最大值归一；这个是 8 根、
 * 按**累计值**归一，并且带数值标签。合并成一个会立刻长出一堆布尔开关。
 */
@Composable
fun TrendBars(
    values: List<Long>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = VColors.lilac,
    highlightLast: Boolean = true,
) {
    val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier.fillMaxWidth().height(100.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, value ->
            val isLast = highlightLast && index == values.lastIndex
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                VText(
                    "$value",
                    VTypo.caption,
                    color = if (value > 0 && isLast) color else VColors.ink3,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                val fraction = (value.toFloat() / max).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((5 + 45 * fraction).dp)
                        .background(
                            when {
                                value <= 0L -> VColors.surface2
                                isLast -> color
                                else -> color.copy(alpha = 0.35f)
                            },
                            RoundedCornerShape(5.dp),
                        ),
                )
                Spacer(Modifier.height(6.dp))
                VText(
                    labels.getOrElse(index) { "" },
                    VTypo.micro.copy(fontWeight = if (isLast) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal),
                    color = if (isLast) color else VColors.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 说明
//
// 本文件只保留**设计稿里独有、公共层确实没有对应件**的可视化：
//   周柱状图（7 根，按周内最大值归一）、专注热力图、环形进度（大/迷你）、
//   目标卡（三种类型的进度表达）、里程碑路线、近 8 周趋势柱。
//
// 原先这里还有三个「小件」——`TypeBadge` / `StatusPill` / `SoftIcon`，
// 它们与公共的 `VBadge` / `VChip` 是同一件事的第二份实现，已删除（用户 2026-10-02）：
//   · 类型徽章  → `VBadge(size, radius, background, icon, iconTint)`（色/图标仍取自 PlanUi）
//   · 状态胶囊  → `VChip(text, background, foreground)`
//   · 圆角图标块 → `VBadge`

