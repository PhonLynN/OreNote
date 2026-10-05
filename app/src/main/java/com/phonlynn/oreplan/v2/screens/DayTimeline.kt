package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.theme.NumFont
import com.phonlynn.oreplan.domain.model.DayWindow
import com.phonlynn.oreplan.v2.components.VEmptyState
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset

/**
 * 日程 · 日视图的**时间轴网格**（设计稿 BBxB7）。
 *
 * # 与设计稿的对应关系
 *
 * ```
 * Section Head        「时间轴」+ 「08:00 – 22:00」
 * ┌────┬──────────────────────────┐
 * │08:00│ ─────────────────────────│  ← 整点横线
 * │09:00│ ┌────────────────────┐   │  ← 事件块（高 = 时长 × 每分钟像素）
 * │     │ │▎英语精读           │   │     左侧 3dp 色条（课程色）
 * │     │ │ 08:00–09:35 · 外语楼│   │     标题 12sp/500，副信息 10sp
 * │     │ └────────────────────┘   │
 * │  ⋮  │        ⋮                 │
 * └────┴──────────────────────────┘
 * ```
 *
 * - **左侧 34dp** 是整点标签列（右对齐，10sp，ink3）；
 * - **右侧**是事件区，宽卡占满、两个重叠事件各占一半（设计稿 304 / 150）；
 * - 事件高度按**真实时长**换算（设计稿 95 分钟 ≈ 63.3px ⇒ 约 0.667px/分钟）；
 * - 「现在」用一条玫红线 + 左侧小三角表示（仅当所选日期是今天）。
 *
 * ## 与既有实现的关系
 *
 * 旧实现是「上午 / 下午 / 晚上」分组的议程列表，与设计稿不符（用户明确指出）。
 * 这里按设计重做成真正的**时间轴**：横向位置表达时间，纵向长度表达时长。
 */
@Composable
internal fun DayTimelineCard(
    state: CalendarV2UiState,
    /** 点击某个事件块 → 打开详情（传 row.key）。 */
    onOpen: (String) -> Unit,
    /** 「时间轴」标题左边的箭头：返回月视图（V3 设计稿）。 */
    onBackToMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DayTimelineHead(state = state, onBackToMonth = onBackToMonth)
        DayTimelineBody(state = state, onOpen = onOpen)
    }
}

/**
 * 「时间轴」标题行（返回箭头 + 标题 + 时间范围）。
 *
 * 日视图把它固定在滚动区上方 —— 上下滑动只滚下面的刻度展示部分（用户 2026-09-25）。
 */
@Composable
internal fun DayTimelineHead(
    state: CalendarV2UiState,
    onBackToMonth: () -> Unit,
) {
    val rows = remember(state.agendaGroups) { state.agendaGroups.flatMap { it.rows } }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // V3：返回月视图（用户 2026-09-25 确认这个箭头就是回月视图）。
            Box(
                Modifier
                    .size(26.dp)
                    .background(VColors.surface2, CircleShape)
                    .vPressable(scaleDown = 0.88f, onClick = onBackToMonth),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.ChevronLeft, null, Modifier.size(14.dp), tint = VColors.ink2)
            }
            VText("时间轴", VTypo.section, color = VColors.ink)
        }
        VText(
            timeRangeLabel(rows, state.selectedDate == state.today),
            VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
            color = VColors.ink3,
            maxLines = 1,
        )
    }
}

/** 时间轴展示部分（空状态或时间网格）—— 日视图里它是可滚动的那一半。 */
@Composable
internal fun DayTimelineBody(
    state: CalendarV2UiState,
    onOpen: (String) -> Unit,
) {
    val rows = remember(state.agendaGroups) { state.agendaGroups.flatMap { it.rows } }
    if (rows.isEmpty()) {
        // 与白板页空状态同一套（VEmptyState：图标块 + 标题 + 说明）。
        VEmptyState(
            icon = Lucide.Calendar,
            title = "这一天还没有安排",
            description = "点右下角的加号新建日程或待办。",
        )
    } else {
        TimeGrid(rows = rows, isToday = state.selectedDate == state.today, onOpen = onOpen)
    }
}

/** 网格里显示的时间范围文本，如 `08:00 – 22:00`。 */
private fun timeRangeLabel(rows: List<CalendarAgendaRowUi>, isToday: Boolean): String {
    val (from, to) = visibleRange(rows.filter { it.startMinute != null }, isToday)
    return "${hhmm(from)} – ${hhmm(to)}"
}

/**
 * 网格实际覆盖的时段（整点对齐）。
 *
 * 设计稿是固定的 08:00 – 22:00，但写死会把窗口外的事件（早八前、晚十后）以及
 * 「现在」挡在视野外 —— 挡掉「现在」就等于没有时刻线。
 * 所以取 **默认窗口 ∪ 事件跨度 ∪ 当前时刻**，向外扩到整点、夹在 0..24 内。
 */
private fun visibleRange(timed: List<CalendarAgendaRowUi>, isToday: Boolean): Pair<Int, Int> {
    val now = if (isToday) nowMinuteOfDay() else null
    val minStart = minOf(
        DEFAULT_FROM,
        timed.minOfOrNull { it.startMinute!! } ?: DEFAULT_FROM,
        now ?: DEFAULT_FROM,
    )
    val maxEnd = maxOf(
        DEFAULT_TO,
        timed.maxOfOrNull { it.endMinute ?: (it.startMinute!! + 60) } ?: DEFAULT_TO,
        now ?: DEFAULT_TO,
    )
    val from = ((minStart / 60) * 60).coerceIn(0, 23 * 60)
    val to = (((maxEnd + 59) / 60) * 60).coerceAtLeast(from + 60).coerceAtMost(24 * 60)
    return from to to
}

/**
 * 时间轴本体。
 *
 * 用 [BoxWithConstraints] 拿到实际宽度，再按「每分钟对应多少 dp」把所有元素
 * 绝对定位 —— 与设计稿的 lay=none + ABS 坐标是同一套做法。
 */
@Composable
private fun TimeGrid(
    rows: List<CalendarAgendaRowUi>,
    isToday: Boolean,
    onOpen: (String) -> Unit,
) {
    val timed = rows.filter { it.startMinute != null }
    val untimed = rows.filter { it.startMinute == null }

    // 时间范围见 [visibleRange]：默认 08:00 – 22:00 ∪ 事件跨度 ∪ 当前时刻。
    val fromMinute = visibleRange(timed, isToday).first
    val toMinute = visibleRange(timed, isToday).second

    val hourHeight = HOUR_HEIGHT
    val totalHours = (toMinute - fromMinute) / 60f
    val gridHeight = hourHeight * totalHours

    val density = LocalDensity.current

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(GRID_TOP + gridHeight + GRID_BOTTOM),
        ) {
            val hourHeightPx = with(density) { hourHeight.toPx() }

            // ---- 整点横线 + 整点标签（先画，事件块压在上面）----
            for (h in 0..(toMinute - fromMinute) / 60) {
                val minute = fromMinute + h * 60
                val y = GRID_TOP + hourHeight * h
                // 横线：设计稿 Hour Line w=312，起点 x=38，右端顶到容器边
                Box(
                    Modifier
                        .offset(x = LINE_START, y = y)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(VColors.line),
                )
                // 标签：设计稿 Hour Slot w=34 h=14，右对齐、垂直居中于横线
                Box(
                    Modifier
                        .offset(y = y - LABEL_HALF / 2)
                        .width(LABEL_COL - 6.dp)
                        .height(LABEL_HALF),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    VText(
                        hhmm(minute),
                        VTypo.numMini.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal),
                        color = VColors.ink3,
                        maxLines = 1,
                    )
                }
            }

            // ---- 事件块（含重叠分列）----
            val placed = placeEvents(timed)
            placed.forEach { p ->
                val top = GRID_TOP + hourHeight * ((p.row.startMinute!! - fromMinute) / 60f)
                val durationMin = max(20, (p.row.endMinute ?: (p.row.startMinute + 60)) - p.row.startMinute)
                val h = hourHeight * (durationMin / 60f)
                // 事件区 = 网格宽 − 46dp（设计稿：块从 x=46 到容器右边；两列时各 150 + 4 间隙）
                val areaWidth = maxWidth - BLOCK_START
                val colW = (areaWidth - COLUMN_GAP * (p.columns - 1)) / p.columns
                val x = BLOCK_START + (colW + COLUMN_GAP) * p.column

                EventBlock(
                    row = p.row,
                    width = colW,
                    height = h.coerceAtLeast(MIN_BLOCK_HEIGHT),
                    modifier = Modifier
                        .offset(x = x, y = top)
                        .width(colW)
                        .height(h.coerceAtLeast(MIN_BLOCK_HEIGHT))
                        .vPressableOpen({ onOpen(p.row.key) }, p.row.key),
                )
            }

            // ---- 无时间的待办：贴在网格顶部，用圆点 + 标题表示（设计稿的待办行）----
            var untimedY = GRID_TOP + gridHeight + 8.dp
            untimed.forEach { row ->
                UntimedRow(row = row, onClick = { onOpen(row.key) }, modifier = Modifier.offset(y = untimedY))
                untimedY += UNTIMED_ROW_HEIGHT + 6.dp
            }

            // ---- 现在时刻线（设计稿：玫瑰细线 + 线首一个右向三角）----
            if (isToday) {
                val nowMinute = nowMinuteOfDay()
                if (nowMinute in fromMinute..toMinute) {
                    val y = GRID_TOP + hourHeight * ((nowMinute - fromMinute) / 60f)
                    // 三角：设计稿 Now Triangle ABS x=34,y=82.5（8×8），
                    // 路径 M0 0 l8 4.5 -8 4.5 z = 右向三角。
                    //
                    // **中线必须与横线重合**：横线占 [y, y+1)，中线在 y+0.5；
                    // 三角高 8dp，所以顶端 = (y+0.5) − 4（曾经写成 y−4，就高了 0.5dp，看着偏上）。
                    //
                    // 水平位置是**实测校准**的：写 LINE_START−4 时三角左缘正好压在线的起点上
                    // （比设计稿整体偏右 4dp），改成 −8 后左缘才会退到线起点左侧、尖端搭在线上。
                    val lineCenterY = y + 0.5.dp
                    Canvas(
                        Modifier
                            .offset(x = LINE_START - 8.dp, y = lineCenterY - 4.dp)
                            .size(8.dp),
                    ) {
                        val tri = Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(tri, VColors.rose)
                    }
                    Box(
                        Modifier
                            .offset(x = LINE_START, y = y)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(VColors.rose),
                    )
                }
            }
            // 供未使用的密度变量引用，避免告警
            if (hourHeightPx < 0f) Box(Modifier.size(0.dp))
        }
    }
}

/**
 * 事件分列：重叠的事件横向分列（设计稿里两个重叠事件各占一半宽度）。
 *
 * 做法：按开始时间排序，逐个塞进「当前列里最后一个事件已结束」的列，
 * 否则新开一列。同一簇（互相重叠的连续事件）共用列数。
 */
private data class PlacedEvent(
    val row: CalendarAgendaRowUi,
    val column: Int,
    val columns: Int,
)

private fun placeEvents(rows: List<CalendarAgendaRowUi>): List<PlacedEvent> {
    // 并列同起点：按创建时刻升序 —— **后添加的排在后面**（占右侧列）。
    // 只按 startMinute 排时，并列项的相对顺序由输入序决定、不稳定（用户 2026-09-29）。
    val sorted = rows.sortedWith(
        compareBy({ it.startMinute ?: 0 }, { it.createdAtMillis }),
    )
    val out = ArrayList<PlacedEvent>(sorted.size)

    // 一簇 = 互相重叠的连续事件；簇内成员各自占一列。
    // 两个列表分别记「成员在 sorted 里的下标」与「它占的列号」——
    // 不能只存列号再拿去当下标用（那样第二簇会回填错成员）。
    var clusterIdx = ArrayList<Int>()
    var clusterCol = ArrayList<Int>()
    var columnEnds = ArrayList<Int>()
    var clusterMaxEnd = 0

    fun flushCluster() {
        if (clusterIdx.isEmpty()) return
        val columns = columnEnds.size.coerceAtLeast(1)
        clusterIdx.indices.forEach { k ->
            out.add(PlacedEvent(sorted[clusterIdx[k]], clusterCol[k], columns))
        }
        clusterIdx = ArrayList()
        clusterCol = ArrayList()
        columnEnds = ArrayList()
        clusterMaxEnd = 0
    }

    sorted.forEachIndexed { i, row ->
        val start = row.startMinute ?: 0
        val end = row.endMinute ?: (start + 60)
        // 与当前簇不重叠 → 收束上一簇
        if (clusterIdx.isNotEmpty() && start >= clusterMaxEnd) flushCluster()
        // 塞进第一个「已经空出来」的列，否则新开一列
        var col = columnEnds.indexOfFirst { it <= start }
        if (col < 0) {
            col = columnEnds.size
            columnEnds.add(end)
        } else {
            columnEnds[col] = end
        }
        clusterIdx.add(i)
        clusterCol.add(col)
        clusterMaxEnd = max(clusterMaxEnd, end)
    }
    flushCluster()
    return out
}

/** 单个事件块：左侧 3dp 色条 + 标题 + 副信息。 */
@Composable
private fun EventBlock(
    row: CalendarAgendaRowUi,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    // 待办块（V3 设计稿「交数据结构实验报告 18:00」）：没有侧色条，
    // 整块 accent-soft；块左侧的轨道上画一个圆点；内容横排「时间 + 标题」。
    if (row.isTask) {
        Box(
            modifier
                .drawBehind {
                    drawCircle(
                        color = VColors.accent,
                        radius = 3.dp.toPx(),
                        center = Offset(-3.dp.toPx(), size.height / 2f),
                    )
                }
                .clip(RoundedCornerShape(8.dp))
                .background(VColors.accentSoft),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VText(
                    row.startText,
                    VTypo.numMini.copy(fontSize = 10.sp),
                    color = VColors.ink2,
                    maxLines = 1,
                )
                VText(
                    row.title,
                    VTypo.caption12.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = VColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    // 设计稿 BBxB7：块底色 = 同色系的 soft 版，色条 = 同色系的实色版。
    // 两者由 EventTone 一起给出 —— 各判各的就会出现"底色绿、色条琥珀"。
    val (bar, soft) = EventTone.of(row.colorTag)
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(soft),
    ) {
        Row(Modifier.fillMaxSize()) {
            // 左侧色条（设计稿 Color Bar w=3 r=3，贴左边通高）
            Box(
                Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = if (height < 44.dp) 6.dp else 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                VText(
                    row.title,
                    VTypo.caption12.copy(fontWeight = FontWeight.Medium, fontSize = if (height < 44.dp) 11.sp else 12.sp),
                    color = VColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 副信息：时间 + 地点（设计稿 `08:00 – 09:35 · 外语楼 210 · 李文倩`）
                val meta = buildString {
                    if (row.endText != null) append("${row.startText} – ${row.endText}")
                    else append(row.startText)
                    row.metaText?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
                if (width >= 120.dp && height >= 44.dp) {
                    VText(
                        meta,
                        VTypo.caption.copy(fontSize = 10.sp, lineHeight = 13.sp),
                        color = VColors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 无时间的事件/待办：一行「圆点 + 标题」，贴在网格下方。 */
@Composable
private fun UntimedRow(
    row: CalendarAgendaRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = BLOCK_START)
            .height(UNTIMED_ROW_HEIGHT)
            .clip(RoundedCornerShape(8.dp))
            .background(VColors.accentSoft)
            .vPressableOpen(onClick, row.key)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(VColors.accent, RoundedCornerShape(4.dp)))
        VText(
            row.title,
            VTypo.caption12.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
            color = VColors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------- 常量与工具

/** 每小时的高度（设计稿：95 分钟 ≈ 63.3px ⇒ 约 40px/小时）。 */
private val HOUR_HEIGHT = 40.dp

/** 整点标签的半高（用于垂直居中于横线）。 */
private val LABEL_HALF = 14.dp

// ---- 设计稿 BBxB7 的绝对坐标（原样换算成 dp；屏幕更窄时只压缩事件区宽度）----

/** 标签列宽：设计稿 Hour Slot `w=34`。 */
private val LABEL_COL = 34.dp

/** 整点横线起点：设计稿 Hour Line `ABS x=38`（标签列右侧留 4）。 */
private val LINE_START = 38.dp

/**
 * 事件块起点：设计稿事件块 `ABS x=46`。
 * 比横线起点再右 8dp —— 所以**刻度线会向事件块左侧伸出一小截**，这是设计稿的样子。
 */
private val BLOCK_START = 46.dp

/** 网格顶部留白：设计稿第一根横线在网格 `y=20`（不是 0）。 */
private val GRID_TOP = 20.dp

/** 网格底部留白：设计稿最后一个标签槽 573..587，网格高 594。 */
private val GRID_BOTTOM = 14.dp

/**
 * 默认可见时段 08:00 – 22:00（设计稿 Section Head 的范围文案）。
 *
 * ⚠️ 值搬去了 [DayWindow] —— 空闲时间工具要用**同一个**窗口，
 * 各写一份迟早会漂移（AI 推荐界面上没有的空档，且不会报错）。
 */
private const val DEFAULT_FROM = DayWindow.DEFAULT_FROM
private const val DEFAULT_TO = DayWindow.DEFAULT_TO

/** 两个并排事件之间的间距。 */
private val COLUMN_GAP = 4.dp

/** 事件块最小高度（太短的课也要看得见）。 */
private val MIN_BLOCK_HEIGHT = 26.dp

private val UNTIMED_ROW_HEIGHT = 30.dp

private fun hhmm(minute: Int): String =
    "%02d:%02d".format(minute / 60, minute % 60)

private fun nowMinuteOfDay(): Int {
    val now = java.time.LocalTime.now()
    return now.hour * 60 + now.minute
}

/** 点击 → 打开详情。 */
private fun Modifier.vPressableOpen(onClick: () -> Unit, key: Any): Modifier =
    this.vPressable(scaleDown = 0.97f, onClick = onClick)
