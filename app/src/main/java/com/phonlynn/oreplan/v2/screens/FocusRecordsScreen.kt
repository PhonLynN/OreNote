package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VPageHorizontal
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * 规划 · 专注记录（设计稿 `DnhqZ`）。
 *
 * 三块：
 * 1. **热力图卡**：一整年（52 周）横向可拖；点某一格 = 选中那一天，
 *    右上角「回到今日」把视图与选中一起拉回今天；
 * 2. **当日头**：`5月12日 · 周三` + `1h 35m · 4 次专注`；
 * 3. **当日列表**：每行左侧类型色竖条 + 标题/方式胶囊 + 时间 · 标签 + 时长，
 *    点行开操作面板（去目标 / 删除这条记录）。
 *
 * 头部的日历按钮打开日期选择：**记录页的筛选入口就是它**，不做一个点了没反应的图标。
 */
@Composable
fun FocusRecordsScreen(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: FocusRecordsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var actionRow by remember { mutableStateOf<FocusRecordRow?>(null) }

    // 热力图横向滚动的状态。52 周 ≈ 1000dp 宽，一屏放不下，所以它天然可拖。
    val heatScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // 热力图可视宽度：算「视口里是哪几列」要用它，测量前为 0。
    var heatViewportWidth by remember { mutableStateOf(0.dp) }
    // 进页面时把视图拖到**最右（今天）**。只做一次：
    // 用户自己拖到几个月前之后，不该被任何重组抢回位置。
    var didScrollToToday by remember { mutableStateOf(false) }
    val goToday: () -> Unit = {
        viewModel.selectDate(LocalDate.now())
        scope.launch {
            withFrameNanos { }
            heatScroll.scrollTo(heatScroll.maxValue)
        }
    }
    LaunchedEffect(state.heatmap) {
        if (didScrollToToday || state.heatmap.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        if (heatScroll.maxValue > 0) {
            heatScroll.scrollTo(heatScroll.maxValue)
            didScrollToToday = true
        }
    }

    /*
     * 热力图右上角的**年份气泡** —— 按滑到的**视口范围**算（用户 2026-10-02 要求）。
     *
     * 换算在 [FocusStats.yearsInViewport]（纯函数 + 单测）：这里只负责把
     * **px 单位的滚动偏移 / 视口宽度 / 列间距**交给它。
     * 由 `heatScroll.value` 与测量出来的 [heatViewportWidth] 触发重组 ——
     * 拖动时前者是帧级变化的，所以气泡跟着手指走。
     */
    val density = LocalDensity.current
    val visibleYears = remember(state.heatmap, heatScroll.value, heatViewportWidth, density) {
        val pitch = with(density) { HeatmapMetrics.ColumnPitch.toPx() }
        // 两端各 EdgeRoom 的内衬属于内容内衬，不是"能看见列的地方"：
        //   · 视口宽度要扣掉**两侧**（×2）；
        //   · 滚动偏移要扣掉**左端**一个（由 yearsInViewport 的 contentOverhang 处理）。
        val edge = with(density) { HeatmapMetrics.EdgeRoom.toPx() }
        val viewport = with(density) { heatViewportWidth.toPx() } - edge * 2
        FocusStats.yearsInViewport(
            columns = state.heatmap,
            scrollX = heatScroll.value.toFloat(),
            viewportWidth = viewport,
            columnPitch = pitch,
            contentOverhang = edge,
        ).ifEmpty { listOf(state.selectedDate.year) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        // 页头
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = VPageHorizontal).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(VColors.surface, CircleShape)
                    .vPressable(scaleDown = 0.9f, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.ArrowLeft, contentDescription = "返回", modifier = Modifier.size(18.dp), tint = VColors.ink)
            }
            Spacer(Modifier.width(12.dp))
            VText("专注记录", VTypo.pageTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(40.dp)
                    .background(VColors.surface, CircleShape)
                    .vPressable(scaleDown = 0.9f) { showPicker = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.CalendarDays, contentDescription = "按日期筛选", modifier = Modifier.size(18.dp), tint = VColors.ink2)
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            // 设计稿 `OZUMA` Content：pad = [10, 20]（上下 10、左右 20）、gap = 18。
            // ⚠️ 左右**按用户口径取 12**（VPageHorizontal）—— 这是全局唯一的例外，
            // 设计稿那侧还没同步过来。其余一律照设计稿。
            contentPadding = PaddingValues(
                start = VPageHorizontal,
                end = VPageHorizontal,
                top = 10.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "heatmap") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(VColors.surface, RoundedCornerShape(16.dp))
                        // 设计稿 `NLmCP` Heatmap Card：pad 8、gap 12、r16
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        // 设计稿 `XPkso` Title Row：gap 8、pad [4,4,0,4]
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VText("专注热力图", VTypo.section, color = VColors.ink, maxLines = 1)
                        // 年份气泡：按**滑到的视口范围**算，跨年时同时显示两个。
                        // 设计稿 Year Chip：pad [3,8]、r8、文字 11/500 ink-3。
                        visibleYears.forEach { year ->
                            Box(
                                Modifier
                                    .background(VColors.bg, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                VText(
                                    "$year 年",
                                    VTypo.caption.copy(fontWeight = FontWeight.Medium),
                                    color = VColors.ink3,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 「回到今日」：拖到几个月前之后，一键把视图与选中都拉回今天。
                        Row(
                            Modifier
                                .background(VColors.accentSoft, RoundedCornerShape(9.dp))
                                .vPressable(scaleDown = 0.93f, onClick = goToday)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Lucide.CalendarCheck, contentDescription = null, modifier = Modifier.size(12.dp), tint = VColors.accent)
                            VText("回到今日", VTypo.micro.copy(fontWeight = FontWeight.Medium), color = VColors.accent, maxLines = 1)
                        }
                    }
                    // 两端余量由 FocusHeatmap 自己留（HeatmapMetrics.EdgeRoom）——
                    // 这里不再传 padding(end)，否则详情页那种新调用点又会漏。
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // 视口宽度只有这里能拿到（父容器给的约束宽度），
                        // 交给外层算年份气泡用；用 SideEffect 写回，避免组合期改状态。
                        val measured = maxWidth
                        SideEffect { if (heatViewportWidth != measured) heatViewportWidth = measured }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(heatScroll),
                        ) {
                            FocusHeatmap(
                                columns = state.heatmap,
                                monthLabels = state.heatmapLabels,
                                today = state.today,
                                selected = state.selectedDate,
                                onSelect = { date -> viewModel.selectDate(date) },
                            )
                        }
                    }
                    // 色阶图例（少 ▢▢▢▢▢ 多）**有意不画**（用户 2026-10-02）：
                    // 热力格本身已经用深浅表达了强弱，再加一排色块只是重复信息、
                    // 把卡片压得更高。要恢复的话，`heatColor(level, accent)` 就是那五档。
                }
            }

            item(key = "day-head") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VText(FocusStats.dayTitle(state.selectedDate), VTypo.section, color = VColors.ink, maxLines = 1)
                    VText(
                        "${FocusStats.durationText(state.dayMinutes)} · ${state.dayCount} 次专注",
                        VTypo.caption,
                        color = VColors.ink3,
                        maxLines = 1,
                    )
                }
            }

            if (!state.loaded) {
                // ⚠️ 数据未到就不画——否则会闪一帧「这一天还没有专注记录」，
                // 而实际上是有记录的（`initialValue` 里 rows 为空）。
                // 与规划首页、以及历史 commit 7566639 同一口径。
                item(key = "loading") { Box(Modifier.fillMaxWidth().height(1.dp)) }
            } else if (state.rows.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.surface, RoundedCornerShape(16.dp))
                            .vPressable(scaleDown = 0.99f) { navigate(V2Routes.FOCUS_START) }
                            .padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(50.dp).background(VColors.accentSoft, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.Timer, contentDescription = null, modifier = Modifier.size(22.dp), tint = VColors.accent)
                        }
                        VText("这一天还没有专注记录", VTypo.bodyMed, color = VColors.ink)
                        VText("点这里开始一次专注", VTypo.caption12, color = VColors.ink3)
                    }
                }
            } else {
                item(key = "list") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.surface, RoundedCornerShape(16.dp)),
                    ) {
                        state.rows.forEachIndexed { index, row ->
                            RecordRow(row) { actionRow = row }
                            if (index != state.rows.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(VColors.line))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        DayPickerDialog(
            initial = state.selectedDate,
            month = state.pickerMonth,
            onShiftMonth = viewModel::shiftMonth,
            onDismiss = { showPicker = false },
            onConfirm = { viewModel.selectDate(it); showPicker = false },
        )
    }

    actionRow?.let { row ->
        RecordActionDialog(
            row = row,
            onDismiss = { actionRow = null },
            onOpenGoal = {
                actionRow = null
                row.goalId?.let { GoalDetailBus.open(it) }
                navigate(V2Routes.PLAN)
            },
            onDelete = {
                viewModel.delete(row.id)
                actionRow = null
            },
        )
    }
}

// ---------------------------------------------------------------- 行

@Composable
private fun RecordRow(row: FocusRecordRow, onClick: () -> Unit) {
    val color = row.colorToken?.let { PlanUi.typeColor(it) } ?: VColors.accent
    Row(
        Modifier
            .fillMaxWidth()
            .vPressable(scaleDown = 0.99f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(4.dp).height(40.dp).background(color, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VText(row.title, VTypo.bodyMed, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Box(
                    Modifier
                        .background(VColors.surface2, RoundedCornerShape(7.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    VText(row.modeLabel, VTypo.micro, color = VColors.ink2, maxLines = 1)
                }
            }
            VText(row.meta, VTypo.caption, color = VColors.ink3, maxLines = 1)
            row.compare?.let { VText(it, VTypo.caption, color = VColors.accent, maxLines = 1) }
        }
        VText(row.duration, VTypo.numPercent, color = VColors.ink, maxLines = 1)
    }
}

/** 点某条记录后的操作面板：去目标 / 删除。两项都真的做事。 */
@Composable
private fun RecordActionDialog(
    row: FocusRecordRow,
    onDismiss: () -> Unit,
    onOpenGoal: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VText(row.title, VTypo.dialogTitle, color = VColors.ink, maxLines = 2)
            VText("${row.meta} · ${row.duration}", VTypo.caption, color = VColors.ink3)
            Spacer(Modifier.height(2.dp))
            if (row.goalId != null) {
                ActionRow(Lucide.Target, "查看关联目标", VColors.ink, onOpenGoal)
            }
            ActionRow(Lucide.Trash2, "删除这条记录", VColors.rose) { confirmDelete = true }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(VColors.surface2, RoundedCornerShape(13.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                VText("取消", VTypo.button, color = VColors.ink2)
            }
        }
    }

    if (confirmDelete) {
        VConfirmDeleteDialog(
            title = "删除这条专注记录？",
            message = "删除后「${row.title}」这次 ${row.duration} 的专注不再计入统计。",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(VColors.bg, RoundedCornerShape(13.dp))
            .vPressable(scaleDown = 0.97f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        VText(text, VTypo.bodyMed, color = tint)
    }
}

// ---------------------------------------------------------------- 日期选择

/** 记录页的日期筛选：月历网格，点一天就跳到那天的记录。 */
@Composable
private fun DayPickerDialog(
    initial: LocalDate,
    month: LocalDate,
    onShiftMonth: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    val ym = YearMonth.from(month)
    val firstDay = ym.atDay(1)
    // 周一开头，与全应用一致
    val lead = firstDay.dayOfWeek.value - 1
    val cells = lead + ym.lengthOfMonth()

    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).background(VColors.surface2, CircleShape)
                        .vPressable(scaleDown = 0.88f) { onShiftMonth(-1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.ChevronLeft, contentDescription = "上个月", modifier = Modifier.size(16.dp), tint = VColors.ink2)
                }
                VText("${ym.year} 年 ${ym.monthValue} 月", VTypo.bodyMed, color = VColors.ink)
                Box(
                    Modifier.size(32.dp).background(VColors.surface2, CircleShape)
                        .vPressable(scaleDown = 0.88f) { onShiftMonth(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.ChevronRight, contentDescription = "下个月", modifier = Modifier.size(16.dp), tint = VColors.ink2)
                }
            }

            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        VText(label, VTypo.micro, color = VColors.ink3)
                    }
                }
            }

            val rows = (cells + 6) / 7
            repeat(rows) { rowIndex ->
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { col ->
                        val index = rowIndex * 7 + col
                        val dayNumber = index - lead + 1
                        val valid = dayNumber in 1..ym.lengthOfMonth()
                        val date = if (valid) ym.atDay(dayNumber) else null
                        val isSelected = date == selected
                        Box(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                                .padding(2.dp)
                                .background(
                                    if (isSelected) VColors.accent else Color.Transparent,
                                    RoundedCornerShape(11.dp),
                                )
                                .then(
                                    if (valid) Modifier.vPressable(scaleDown = 0.88f) { selected = date!! } else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (valid) {
                                VText(
                                    "$dayNumber",
                                    VTypo.caption12.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (isSelected) Color.White else VColors.ink,
                                )
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("取消", VTypo.button, color = VColors.ink2)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(VColors.accent, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) { onConfirm(selected) },
                    contentAlignment = Alignment.Center,
                ) {
                    VText("查看", VTypo.buttonBold, color = Color.White)
                }
            }
        }
    }
}

/** 供归档页复用：某天的中文短标题。 */
internal fun dayShortTitle(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日 ${TermClock.weekdayLabel(date.dayOfWeek)}"
