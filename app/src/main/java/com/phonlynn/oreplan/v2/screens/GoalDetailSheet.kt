package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogButtons
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.components.VSectionHead
import androidx.compose.animation.core.animateFloatAsState

/**
 * 目标详情**底部弹层**（设计稿 `svsrX 目标详情 · 分步` /
 * `CAMRJ 目标详情 · 数量` / `oTvuc 目标详情 · 习惯`）。
 *
 * 三张设计稿共用同一套骨架：
 * ```
 * 蒙层（390x844 #101613 42%）
 * └ 面板（r24，左右各留 14）
 *   ├ 抓手
 *   ├ 类型胶囊 + 关闭钮
 *   ├ 标题
 *   ├ 进度行：`已完成 8 / 12 步`        67%
 *   ├ 进度条
 *   ├ 三格统计（剩余天数 / 里程碑 / 累计专注）
 *   ├ 类型专属区（里程碑路线 / 进度趋势 / 热力图+统计）
 *   └ 两个按钮（主操作 + 设置）
 * ```
 */
@Composable
fun GoalDetailSheetHost(
    navigate: (String) -> Unit,
    viewModel: GoalDetailSheetViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requested by GoalDetailBus.requested.collectAsStateWithLifecycle()

    LaunchedEffect(requested) {
        val id = requested ?: return@LaunchedEffect
        viewModel.open(id)
        GoalDetailBus.consume()
    }

    if (!state.open) return

    VDialog(onDismissRequest = { viewModel.close() }) {
        GoalDetailSheet(
            state = state,
            onClose = { viewModel.close() },
            onToggleStep = { viewModel.toggleStep(it) },
            onAdvance = { viewModel.advanceNextStep() },
            onAddQuantity = { amount, label -> viewModel.addQuantity(amount, label) },
            onDeleteQuantity = { viewModel.deleteQuantity(it) },
            onToggleHabit = { viewModel.toggleToday() },
            onAddStep = { viewModel.addStep(it) },
            onRemoveStep = { viewModel.removeStep(it) },
            onSettings = {
                val id = state.goal?.id ?: return@GoalDetailSheet
                viewModel.close()
                navigate(V2Routes.goalEditor(goalId = id))
            },
        )
    }
}

@Composable
private fun GoalDetailSheet(
    state: GoalDetailSheetUiState,
    onClose: () -> Unit,
    onToggleStep: (String) -> Unit,
    onAdvance: () -> Unit,
    onAddQuantity: (Long, String) -> Unit,
    onDeleteQuantity: (String) -> Unit,
    onToggleHabit: () -> Unit,
    onAddStep: (String) -> Unit,
    onRemoveStep: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var showQuantityDialog by remember { mutableStateOf(false) }
    var showAddStep by remember { mutableStateOf(false) }

    if (state.missing) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VText("目标不存在", VTypo.dialogTitle, color = VColors.ink)
            VText("它可能已经被删除了。", VTypo.caption12, color = VColors.ink3)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(VColors.surface2, RoundedCornerShape(13.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                VText("关闭", VTypo.button, color = VColors.ink2)
            }
        }
        return
    }

    val color = PlanUi.typeColor(state.type)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 660.dp)
            .background(VColors.surface, RoundedCornerShape(24.dp)),
    ) {
        // 抓手
        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(VColors.line, RoundedCornerShape(2.dp)))
        }

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                // 面板内衬 14：设计稿的面板是「屏幕 390 − 两侧 14」= 362 宽，
                // 内部内容再退 18 —— 那个 18 让卡片看着比页面里的窄，收敛到 14。
                .padding(horizontal = 14.dp)
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 类型胶囊 + 关闭
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .background(PlanUi.typeSoft(state.type), RoundedCornerShape(11.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    VText(PlanUi.typeLabel(state.type), VTypo.caption, color = color)
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .background(VColors.bg, CircleShape)
                        .vPressable(scaleDown = 0.9f, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = "关闭", modifier = Modifier.size(15.dp), tint = VColors.ink2)
                }
            }

            VText(state.title, VTypo.hero, color = VColors.ink, maxLines = 2)

            // 进度行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VText(state.progressText, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                VText("${state.percent}%", VTypo.numPercent, color = color)
            }
            SheetProgress(fraction = state.percent / 100f, color = color)

            // 三格统计
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    value = state.remainingDays ?: "—",
                    label = "剩余天数",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = when (state.type) {
                        GoalType.STEP -> state.milestones
                        GoalType.QUANTITY -> "${state.quantityTotal} / ${state.quantityTarget}"
                        GoalType.HABIT -> "${state.habitMonthDone} 天"
                    },
                    label = when (state.type) {
                        GoalType.STEP -> "里程碑"
                        GoalType.QUANTITY -> "累计"
                        GoalType.HABIT -> "本月"
                    },
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = FocusStats.durationText(state.focusMinutes),
                    label = "累计专注",
                    modifier = Modifier.weight(1f),
                )
            }

            when (state.type) {
                GoalType.STEP -> {
                    VSectionHead(
                        title = "里程碑路线",
                        note = "${PlanUi.milestoneDone(state.stepDone)} / ${PlanUi.milestoneTotal(state.stepTotal)} 完成",
                    )
                    if (state.nodes.isEmpty()) {
                        SheetHint("还没有子项 —— 加一个，把大目标拆成能推动的小步。")
                        TextAction("添加子项", Lucide.Plus) { showAddStep = true }
                    } else {
                        MilestoneRoadmap(nodes = state.nodes, onToggle = onToggleStep, color = color)
                    }
                }

                GoalType.QUANTITY -> {
                    VSectionHead(title = "进度趋势", note = state.trendNote())
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.bg, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            VText("${state.quantityTotal}", VTypo.numStat, color = color)
                            VText(state.unit, VTypo.caption, color = VColors.ink3)
                        }
                        TrendBars(
                            values = state.trendValues(),
                            labels = state.trendLabels(),
                            color = color,
                        )
                    }
                    VSectionHead(title = "最近记录")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.bg, RoundedCornerShape(14.dp))
                            .padding(vertical = 4.dp),
                    ) {
                        if (state.quantityLogs.isEmpty()) {
                            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                                VText("还没有记录", VTypo.caption12, color = VColors.ink3)
                            }
                        } else {
                            state.quantityLogs.take(5).forEachIndexed { index, log ->
                                QuantityLogRow(
                                    dateText = PlanUi.monthDay(log.at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
                                    name = log.label?.takeIf { it.isNotBlank() } ?: "记一笔",
                                    delta = "+${log.amount}",
                                    color = color,
                                    onDelete = { onDeleteQuantity(log.id) },
                                )
                                if (index != state.quantityLogs.take(5).lastIndex) {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(VColors.line))
                                }
                            }
                        }
                    }
                }

                GoalType.HABIT -> {
                    // 年份胶囊：与专注记录页同一口径 —— 跟着**滑到的位置**走，
                    // 不是写死今年（跨年那几周里写死今年就是错的）。
                    val heatScroll = rememberScrollState()
                    var didScrollToToday by remember(state.title) { mutableStateOf(false) }
                    LaunchedEffect(state.habitHeatmap) {
                        if (didScrollToToday || state.habitHeatmap.isEmpty()) return@LaunchedEffect
                        // 等一帧让内容先测量（否则 maxValue 还是 0，滚不动）。
                        withFrameNanos { }
                        if (heatScroll.maxValue > 0) {
                            // 初始位置贴**最右端 = 今天**：打开面板先看到的是「现在」，
                            // 而不是一年前那几列（用户 2026-10-02 指出）。
                            heatScroll.scrollTo(heatScroll.maxValue)
                            didScrollToToday = true
                        }
                    }
                    val density = LocalDensity.current
                    val yearAtViewport = remember(state.habitHeatmap, heatScroll.value, density) {
                        val pitch = with(density) { HeatmapMetrics.ColumnPitch.toPx() }
                        // 与记录页同一口径：滚动偏移要先减掉左端内衬，才对应"第一列"。
                        val edge = with(density) { HeatmapMetrics.EdgeRoom.toPx() }
                        val index = if (pitch > 0f) {
                            ((heatScroll.value - edge).coerceAtLeast(0f) / pitch).toInt()
                        } else {
                            0
                        }
                        state.heatmapYearAt(index)
                    }
                    VSectionHead(title = "打卡热力图", note = "$yearAtViewport 年")
                    Box(Modifier.fillMaxWidth().horizontalScroll(heatScroll)) {
                        FocusHeatmap(
                            columns = state.habitHeatmap,
                            monthLabels = state.heatmapLabels(),
                            today = state.today,
                            activeColor = color,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VText(
                            "本周 ${state.habitWeekDone} / ${state.habitWeeklyTarget} 天 · 连续 ${state.habitStreak} 天",
                            VTypo.bodyMed,
                            color = VColors.ink,
                            maxLines = 1,
                        )
                        VText("${state.percent}%", VTypo.numPercent, color = color)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("${state.habitMonthDone} / ${state.today.lengthOfMonth()}", "本月", Modifier.weight(1f))
                        StatTile("${state.habitDays.size}", "累计", Modifier.weight(1f))
                        StatTile("${state.habitMaxStreak}", "最长连续", Modifier.weight(1f))
                    }
                    state.dailyMinutes?.let { minutes ->
                        SheetHint("每天目标 $minutes 分钟 —— 打卡即算达标。")
                    }
                }
            }
        }

        // 底部两个按钮（设计稿 Actions：主操作 + 设置）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val (label, icon, action) = when (state.type) {
                GoalType.STEP -> Triple("推进下一步", Lucide.Check, onAdvance)
                GoalType.QUANTITY -> Triple("记录进度", Lucide.Plus, { showQuantityDialog = true })
                GoalType.HABIT -> Triple(
                    if (state.habitTodayChecked) "取消今日打卡" else "今日打卡",
                    if (state.habitTodayChecked) Lucide.X else Lucide.Check,
                    onToggleHabit,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(color, RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.96f, onClick = { action() }),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    VText(label, VTypo.buttonBold, color = Color.White, maxLines = 1)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(VColors.surface2, RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Lucide.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = VColors.ink2)
                    VText("设置", VTypo.button, color = VColors.ink2, maxLines = 1)
                }
            }
        }
    }

    if (showQuantityDialog) {
        QuantityAddDialog(
            unit = state.unit,
            initial = 1,
            onDismiss = { showQuantityDialog = false },
            onConfirm = { amount, label ->
                onAddQuantity(amount, label)
                showQuantityDialog = false
            },
        )
    }

    if (showAddStep) {
        AddStepDialog(
            onDismiss = { showAddStep = false },
            onConfirm = { name ->
                onAddStep(name)
                showAddStep = false
            },
        )
    }
}

// ---------------------------------------------------------------- 小件

@Composable
private fun SheetProgress(fraction: Float, color: Color) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), VMotion.settle(), label = "sheetProgress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(VColors.surface2, RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(8.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .height(66.dp)
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VText(value, VTypo.numStat, color = VColors.ink, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        VText(label, VTypo.micro, color = VColors.ink3, maxLines = 1)
    }
}

@Composable
private fun SheetHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(VColors.bg, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        VText(text, VTypo.caption12, color = VColors.ink2)
    }
}

@Composable
private fun TextAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .vPressable(scaleDown = 0.97f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = VColors.accent)
        Spacer(Modifier.width(6.dp))
        VText(text, VTypo.bodyMed, color = VColors.accent)
    }
}

@Composable
private fun QuantityLogRow(
    dateText: String,
    name: String,
    delta: String,
    color: Color,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(41.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        VText(dateText, VTypo.caption, color = VColors.ink3, maxLines = 1, modifier = Modifier.width(56.dp))
        VText(name, VTypo.body, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
        VText(delta, VTypo.numPercent, color = color, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.size(26.dp).vPressable(scaleDown = 0.85f, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Trash2, contentDescription = "删除这条记录", modifier = Modifier.size(14.dp), tint = VColors.ink3)
        }
    }
}

/** 记一笔（数量目标）。 */
@Composable
private fun QuantityAddDialog(
    unit: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    var amount by remember { mutableStateOf(initial.toString()) }
    var label by remember { mutableStateOf("") }
    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("记一笔", VTypo.dialogTitle, color = VColors.ink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1L, 2L, 5L, 10L).forEach { preset ->
                    val active = amount == preset.toString()
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(if (active) VColors.accent else VColors.surface2, RoundedCornerShape(12.dp))
                            .vPressable(scaleDown = 0.94f) { amount = preset.toString() },
                        contentAlignment = Alignment.Center,
                    ) {
                        VText("+$preset", VTypo.caption12, color = if (active) Color.White else VColors.ink2)
                    }
                }
            }
            PlanTextField(
                value = amount,
                onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                placeholder = if (unit.isBlank()) "数量" else "数量（$unit）",
                textStyle = VTypo.numValue,
            )
            PlanTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = "备注（可选，如《置身事内》）",
            )
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = { amount.toLongOrNull()?.let { onConfirm(it, label) } },
                confirmText = "记录",
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}

/** 添加子项。 */
@Composable
private fun AddStepDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("添加子项", VTypo.dialogTitle, color = VColors.ink)
            PlanTextField(value = name, onValueChange = { name = it }, placeholder = "如：概率论 · 第 1-3 章")
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = { if (name.isNotBlank()) onConfirm(name) },
                confirmText = "添加",
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}
