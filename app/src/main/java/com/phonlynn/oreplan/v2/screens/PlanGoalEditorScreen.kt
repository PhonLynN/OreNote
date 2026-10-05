package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.plan.PlanMeta
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.components.VDangerCard
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogButtons
import com.phonlynn.oreplan.v2.components.VPageHorizontal
import com.phonlynn.oreplan.v2.components.VPrimaryButton
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VSettingRow
import com.phonlynn.oreplan.v2.components.VSettingRowHeight
import com.phonlynn.oreplan.v2.components.VSettingRowPadding
import com.phonlynn.oreplan.v2.components.VSettingSwitchRow
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import java.time.LocalDate

/**
 * 新建目标 / 目标设置（设计稿 `V62qjP` / `LWz7k` / `Dl2iy` / `g13Ial`）。
 *
 * 一屏两壳：
 * - `goalId == null` → **新建目标**：顶部「新建目标」+ 类型选择 + 子项区 + 底部「创建目标」；
 * - 否则 → **目标设置**：顶部返回 +「保存」，多一张概览卡与「关联 / 删除目标」。
 */
@Composable
fun PlanGoalEditorScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: PlanGoalEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val linkableEvents = state.linkableEvents
    var showDue by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showDuration by remember { mutableStateOf(false) }
    var showTargetEdit by remember { mutableStateOf(false) }
    var showUnitEdit by remember { mutableStateOf(false) }
    var showAddStep by remember { mutableStateOf(false) }
    var showWeekly by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var showLinkEvents by remember { mutableStateOf(false) }
    var showMoreReminder by remember { mutableStateOf(false) }

    // 能不能提交。**两个外壳共用一条规则**（空标题不能提交）：
    //  · 新建：标题本来就空 → 按钮从灰变绿，是「现在可以创建了」的正向反馈；
    //  · 设置：标题是已有目标的名字，只有用户把它删光才会变灰
    //    —— 那时也确实不该保存（等于把目标改成无名）。
    val canSave = form.loaded && form.title.isNotBlank()

    Box(Modifier.fillMaxSize().background(VColors.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            // 底部内衬要留出操作条的高度（渐隐带 36 + 按钮 50 + 14），
            // 否则最后一张卡会被底部按钮压住。
            contentPadding = PaddingValues(
                start = VPageHorizontal,
                end = VPageHorizontal,
                top = 10.dp,
                bottom = 160.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(key = "header") {
                EditorHeader(
                    title = if (form.isNew) "新建目标" else "目标设置",
                    isNew = form.isNew,
                    onClose = onBack,
                )
            }

            if (!form.isNew) {
                item(key = "overview") { OverviewCard(form) }
            }

            if (form.isNew) {
                item(key = "type-head") {
                    VSectionHead(title = "目标类型", note = "选择跟踪方式")
                }
                item(key = "type") {
                    TypeRow(selected = form.type, enabled = form.isNew, onSelect = viewModel::setType)
                }
            }

            item(key = "info-head") { VSectionHead(title = "基本信息") }
            item(key = "info") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(VColors.surface, RoundedCornerShape(16.dp)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(VSettingRowHeight).padding(horizontal = VSettingRowPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 「目标名称」是唯一**就地可编辑**的字段（其余都是打开弹窗），
                        // 所以它不是一个 VSettingRow —— 标签走公用的 VText 写法（与其他页一致）。
                        VText("目标名称", VTypo.body, color = VColors.ink2, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            PlanTextField(
                                value = form.title,
                                onValueChange = viewModel::setTitle,
                                placeholder = "如：期末复习计划",
                                singleLine = true,
                                height = 40.dp,
                                textStyle = VTypo.bodyMed,
                            )
                        }
                    }
                    InnerDivider()
                    when (form.type) {
                        GoalType.HABIT -> {
                            VSettingRow(
                                label = "周期时限",
                                value = form.weeklyTarget?.let { "每周 $it 天" } ?: freqLabel(form.frequency),
                                onClick = { showWeekly = true },
                            )
                            InnerDivider()
                            VSettingRow(
                                label = "每天目标",
                                value = form.dailyMinutes?.let { "$it 分钟" } ?: "不设置",
                                onClick = { showDuration = true },
                            )
                            InnerDivider()
                            VSettingRow(
                                label = "提醒时间",
                                value = "每天 ${form.checkInTime}",
                                onClick = { showTime = true },
                            )
                        }
                        else -> {
                            VSettingRow(
                                label = "截止日期",
                                value = form.due?.let { PlanUi.monthDay(it) } ?: "不设置",
                                onClick = { showDue = true },
                            )
                        }
                    }
                }
            }

            if (form.type == GoalType.QUANTITY) {
                item(key = "value-head") { VSectionHead(title = "数值设置") }
                item(key = "value") {
                    Column(
                        Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(16.dp)),
                    ) {
                        VSettingRow(
                            label = "目标值",
                            value = "${form.targetValue} ${form.unit}",
                            onClick = { showTargetEdit = true },
                        )
                        InnerDivider()
                        VSettingRow(label = "单位", value = form.unit, onClick = { showUnitEdit = true })
                        InnerDivider()
                        VSettingRow(label = "起始值", value = "0 ${form.unit}", onClick = { }, showChevron = false)
                    }
                }
            }

            if (form.type == GoalType.STEP) {
                item(key = "steps-head") {
                    VSectionHead(title = "子项", note = "${form.steps.size} 项")
                }
                item(key = "steps") {
                    Column(
                        Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(16.dp)),
                    ) {
                        form.steps.forEachIndexed { index, step ->
                            StepDraftRow(
                                index = index,
                                name = step.name,
                                canMoveUp = index > 0,
                                canMoveDown = index < form.steps.lastIndex,
                                onUp = { viewModel.moveStep(index, index - 1) },
                                onDown = { viewModel.moveStep(index, index + 1) },
                                onRemove = { viewModel.removeStep(index) },
                            )
                            InnerDivider()
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(45.dp)
                                .vPressable(scaleDown = 0.99f) { showAddStep = true }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(16.dp), tint = VColors.accent)
                            VText("添加子项", VTypo.bodyMed, color = VColors.accent)
                        }
                    }
                }
                item(key = "order") {
                    Column(
                        Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(16.dp)),
                    ) {
                        VSettingSwitchRow(
                            label = "按顺序解锁",
                            badgeIcon = Lucide.ListOrdered,
                            subtitle = if (form.sequential) "只能推进当前一步" else "任意一步都能勾",
                            checked = form.sequential,
                            onCheckedChange = viewModel::setSequential,
                        )
                    }
                }
            }

            item(key = "remind-head") { VSectionHead(title = "提醒") }
            item(key = "remind") {
                Column(
                    Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(16.dp)),
                ) {
                    VSettingSwitchRow(
                        label = "开启提醒",
                        badgeIcon = Lucide.Bell,
                        subtitle = when (form.type) {
                            GoalType.HABIT -> "到点提醒我坚持"
                            else -> "到期前提醒我"
                        },
                        checked = form.reminderEnabled,
                        onCheckedChange = viewModel::setReminderEnabled,
                    )
                    if (form.reminderEnabled) {
                        InnerDivider()
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                form.leads.forEach { lead ->
                                    ReminderChip(
                                        text = PlanMeta.leadLabel(lead),
                                        onRemove = { viewModel.removeLead(lead) },
                                    )
                                }
                                if (form.leads.size < PlanMeta.MAX_REMINDERS) {
                                    Row(
                                        Modifier
                                            .height(32.dp)
                                            .vPressable(scaleDown = 0.94f) { showMoreReminder = true }
                                            .padding(horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(12.dp), tint = VColors.accent)
                                        VText("添加时间", VTypo.caption12, color = VColors.accent)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Lucide.Info, contentDescription = null, modifier = Modifier.size(12.dp), tint = VColors.ink3)
                                VText(
                                    "每个目标最多 ${PlanMeta.MAX_REMINDERS} 次提醒；两次提醒至少间隔 ${PlanMeta.MIN_GAP_MINUTES} 分钟",
                                    VTypo.micro,
                                    color = VColors.ink3,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "extra-head") { VSectionHead(title = "关联") }
            item(key = "extra") {
                Column(
                    Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(16.dp)),
                ) {
                    VSettingRow(
                        label = "关联日程",
                        value = if (form.linkedEvents.isEmpty()) "—" else "${form.linkedEvents.size} 项",
                        onClick = { showLinkEvents = true },
                    )
                }
            }

            if (!form.isNew) {
                item(key = "actions") {
                    // 设计稿 `Dl2iy` / `g13Ial` / `LWz7k` 三张目标设置页的底部
                    // **只有一张 350×49 的「删除目标」卡**（`Delete Card`，
                    // pad [15,14]、r16、文字 13/500 $rose）。
                    //
                    // 我此前在这里另加了一张「归档目标」卡 —— 设计稿里没有，已删。
                    // 归档本身仍有入口：规划首页「进行中的目标」右侧的「已归档 N」
                    // （设计稿 `wBNwV` 就是这么放的），归档页里也能逐个恢复。
                    //
                    // 删除卡走公共件 [VDangerCard]，不再自制一套行样式。
                    VDangerCard(text = "删除目标", onClick = { showDelete = true })
                }
            }
        }

        // 底部固定操作条 —— **新建与设置共用同一条**（用户 2026-10-02）。
        //
        // 设计稿把提交入口放在顶栏（`Save Button` 55×35），那是**错的**：
        // 同一个应用里，日程编辑 / 课程编辑 / 白板卡片 / 白板设置 / 作息 / 提醒
        // 一律是「底部操作条 + 主按钮」，只有规划这两页是顶栏小按钮。
        // 用户口径：**顶栏保存全部删掉，统一走底部**。
        //
        // 走公共件：VBottomActionBar（36 渐隐带 + 实底 + 导航栏安全区）
        //          + VPrimaryButton（50 高、accent、禁用灰），与白板设置页同源。
        VBottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            VPrimaryButton(
                // 文案按全站设置类页面的口径：**「保存设置」**，不是「保存」。
                // 白板设置也是「保存设置」，提醒设置是「保存提醒设置」——
                // 「保存」太泛，用户不知道保存的是什么。
                text = if (form.isNew) "创建目标" else "保存设置",
                icon = Lucide.Check,
                enabled = canSave,
                onClick = { viewModel.save(onBack) },
            )
        }
    }

    // ------------------------------------------------------------ 弹窗
    //
    // 日期与时间**走公共件**（`VDatePickerDialog` / `VTimePickerDialog`，在 V2Pickers.kt）：
    // 日历页、日程编辑页、白板卡片页用的都是它们。两者都是「点即确认」——
    // 内部用 `close { onConfirm(...) }` 先播退场再回调，所以这里
    // **onConfirm 里不能再碰 showXxx**（那会让对话框卸载，退场动画来不及播），
    // 关掉弹窗的活儿交给 onDismiss。

    if (showDue) {
        VDatePickerDialog(
            initial = form.due ?: LocalDate.now(),
            onConfirm = viewModel::setDue,
            onDismiss = { showDue = false },
        )
    }
    if (showTime) {
        VTimePickerDialog(
            // 公共件用「一天中的第几分钟」，表单里存的是 "HH:mm"。
            initialMinute = parseClock(form.checkInTime),
            onConfirm = { viewModel.setCheckInTime(formatClock(it)) },
            onDismiss = { showTime = false },
        )
    }
    if (showDuration) {
        PlanDurationDialog(
            initialMinutes = form.dailyMinutes ?: 30,
            onDismiss = { showDuration = false },
            onConfirm = { viewModel.setDailyMinutes(it); showDuration = false },
        )
    }
    if (showTargetEdit) {
        NumberInputDialog(
            title = "目标值",
            initial = form.targetValue,
            suffix = form.unit,
            onDismiss = { showTargetEdit = false },
            onConfirm = { viewModel.setTargetValue(it); showTargetEdit = false },
        )
    }
    if (showUnitEdit) {
        UnitPickerDialog(
            current = form.unit,
            onDismiss = { showUnitEdit = false },
            onConfirm = { viewModel.setUnit(it); showUnitEdit = false },
        )
    }
    if (showAddStep) {
        NameInputDialog(
            title = "添加子项",
            placeholder = "如：概率论 · 第 1-3 章",
            onDismiss = { showAddStep = false },
            onConfirm = { viewModel.addStep(it); showAddStep = false },
        )
    }
    if (showWeekly) {
        WeeklyTargetDialog(
            current = form.weeklyTarget,
            frequency = form.frequency,
            onDismiss = { showWeekly = false },
            onConfirm = { target, freq ->
                viewModel.setFrequency(freq)
                viewModel.setWeeklyTarget(target)
                showWeekly = false
            },
        )
    }
    if (showMoreReminder) {
        ReminderLeadDialog(
            existing = form.leads,
            habit = form.type == GoalType.HABIT,
            onDismiss = { showMoreReminder = false },
            onAdd = { viewModel.addLead(it); showMoreReminder = false },
        )
    }
    if (showLinkEvents) {
        LinkEventsDialog(
            selected = form.linkedEvents,
            candidates = linkableEvents,
            onToggle = viewModel::toggleLinkedEvent,
            onDismiss = { showLinkEvents = false },
        )
    }
    if (showDelete) {
        VConfirmDeleteDialog(
            title = "删除目标",
            message = "「${form.title}」及其子项、打卡与提醒都会一并删除，不能恢复。",
            onDismiss = { showDelete = false },
            onConfirm = { showDelete = false; viewModel.delete(onBack) },
        )
    }
    if (showArchive) {
        // 归档入口已从本页移除（设计稿 `Dl2iy/g13Ial/LWz7k` 底部只有「删除目标」卡），
        // 归档现在从规划首页「已归档」进。这段弹窗暂时没人调用，保留是因为
        // `viewModel.archive()` 仍是**可达的能力**（归档页可恢复），
        // 删掉会让"归档"变成只有 UI 之外才能触发的状态。
        // 若确认不再需要，应连同 `PlanGoalEditorViewModel.archive()` 一起删。
        VDialog(onDismissRequest = { showArchive = false }, maxWidth = 320.dp) {
            Column(
                Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(24.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                VText("归档目标", VTypo.dialogTitle, color = VColors.ink)
                VText("归档后目标会移到「已归档」，提醒会停掉，随时可以恢复。", VTypo.caption12, color = VColors.ink3)
                VDialogButtons(
                    onCancel = { showArchive = false },
                    onConfirm = { showArchive = false; viewModel.archive(onBack) },
                    confirmText = "归档",
                    height = 46.dp,
                    radius = 13.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 头部

/**
 * 编辑页顶栏：圆形按钮（新建 = `×` 关闭；设置 = `←` 返回）+ 大标题。
 *
 * ## 顶栏**不放提交按钮**（用户 2026-10-02 定稿）
 *
 * 设计稿 `V62qjP` / `LWz7k` / `Dl2iy` / `g13Ial` 的 Header 右侧都有一个
 * 55×35 的 `Save`。用户判定这是**错误的页面设计**：
 *
 *  · 同一个应用里，日程编辑 / 课程编辑 / 白板卡片 / 白板设置 / 作息 / 提醒
 *    一律是「**底部操作条 + 主按钮**」——`VBottomActionBar` + `VSaveButton`/`VPrimaryButton`；
 *  · 只有规划这两页照设计稿做成了顶栏小按钮，于是提交入口的形状与位置全站不一致。
 *
 * 所以：**顶栏只留导航（关闭/返回 + 标题），提交一律在底部**。
 * 本组件不再接收 `canSave` / `onSave` —— 传进来就说明又有人想加回顶栏按钮。
 */
@Composable
private fun EditorHeader(
    title: String,
    isNew: Boolean,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .background(VColors.surface, CircleShape)
                .vPressable(scaleDown = 0.9f, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isNew) Lucide.X else Lucide.ArrowLeft,
                contentDescription = if (isNew) "关闭" else "返回",
                modifier = Modifier.size(18.dp),
                tint = VColors.ink,
            )
        }
        Spacer(Modifier.width(12.dp))
        VText(title, VTypo.pageTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- 类型选择

@Composable
private fun TypeRow(selected: GoalType, enabled: Boolean, onSelect: (GoalType) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GoalType.entries.forEach { type ->
            val active = type == selected
            val color = PlanUi.typeColor(type)
            Column(
                Modifier
                    .weight(1f)
                    .background(if (active) PlanUi.typeSoft(type) else VColors.surface, RoundedCornerShape(14.dp))
                    .vPressable(enabled = enabled, scaleDown = 0.97f) { onSelect(type) }
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (active) VColors.surface else VColors.bg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PlanUi.typeIcon(type), contentDescription = null, modifier = Modifier.size(18.dp), tint = if (active) color else VColors.ink2)
                }
                VText(PlanUi.typeLabel(type), VTypo.bodyMed, color = if (active) color else VColors.ink, maxLines = 1)
                VText(PlanUi.typeHint(type), VTypo.micro, color = if (active) color else VColors.ink3, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- 概览卡

@Composable
private fun OverviewCard(form: PlanGoalForm) {
    val color = PlanUi.typeColor(form.type)
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VText(form.title.ifBlank { "未命名目标" }, VTypo.cardTitle.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified).let { VTypo.cardTitle }, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(PlanUi.typeSoft(form.type), RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                VText(PlanUi.typeLabel(form.type), VTypo.micro, color = color)
            }
        }
        if (form.type == GoalType.HABIT) {
            VText(form.today.year.toString(), VTypo.caption, color = VColors.ink3)
            Box(Modifier.fillMaxWidth()) {
                HabitCheckinGrid(days = form.habitDays, today = form.today, color = color)
            }
        }
        SheetProgressBar(form.percent / 100f, color)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            VText(form.progressText, VTypo.caption, color = VColors.ink3, maxLines = 1)
            VText("${form.percent}%", VTypo.numPercent, color = color)
        }
        if (form.focusMinutes > 0) {
            InnerDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                VText("累计专注", VTypo.caption, color = VColors.ink3)
                VText(com.phonlynn.oreplan.domain.focus.FocusStats.durationText(form.focusMinutes), VTypo.numPercent, color = VColors.ink)
            }
        }
    }
}

/** 概览卡里的打卡网格：近 12 周（比详情弹层的 17 周窄，卡片放得下）。 */
/**
 * 新建 / 目标设置页概览卡里的**迷你**打卡网格（近 12 周）。
 *
 * 它不画月份标签、也不可交互，所以没有走 `FocusHeatmap`（那个是 18dp 格子、
 * 给整页用的）。列数固定 12：概览卡只有这么宽，塞一整年会把格子压成一条线。
 */
@Composable
private fun HabitCheckinGrid(
    days: Set<Int>,
    today: LocalDate,
    color: Color,
) {
    val grid = androidx.compose.runtime.remember(days, today) {
        com.phonlynn.oreplan.domain.focus.FocusStats.checkinGrid(days, today, weeks = MINI_GRID_WEEKS)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        grid.forEach { column ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                column.forEach { cell ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(9.dp)
                            .background(
                                if (cell.level > 0) color else VColors.surface2,
                                RoundedCornerShape(3.dp),
                            ),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 行内小件

@Composable
private fun InnerDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(VColors.line))
}

@Composable
private fun SheetProgressBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(VColors.surface2, RoundedCornerShape(4.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun StepDraftRow(
    index: Int,
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(24.dp).background(VColors.bg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            VText("${index + 1}", VTypo.numChip, color = VColors.ink2)
        }
        VText(name, VTypo.body, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoveButton(Lucide.ChevronUp, canMoveUp, onUp)
            MoveButton(Lucide.ChevronDown, canMoveDown, onDown)
            MoveButton(Lucide.X, true, onRemove, tint = VColors.rose)
        }
    }
}

@Composable
private fun MoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = VColors.ink3,
) {
    Box(
        Modifier.size(28.dp).vPressable(enabled = enabled, scaleDown = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (enabled) tint else VColors.line)
    }
}

@Composable
private fun ReminderChip(text: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .height(32.dp)
            .background(VColors.accentSoft, RoundedCornerShape(11.dp))
            .vPressable(scaleDown = 0.95f, onClick = onRemove)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.AlarmClock, contentDescription = null, modifier = Modifier.size(12.dp), tint = VColors.accent)
        VText(text, VTypo.caption12.copy(fontWeight = FontWeight.Medium), color = VColors.accent, maxLines = 1)
        Icon(Lucide.X, contentDescription = "移除这一档提醒", modifier = Modifier.size(11.dp), tint = VColors.accent)
    }
}

// ---------------------------------------------------------------- 弹窗集

@Composable
private fun NumberInputDialog(
    title: String,
    initial: Long,
    suffix: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VText(title, VTypo.dialogTitle, color = VColors.ink)
            PlanTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(7) },
                placeholder = "数值",
                textStyle = VTypo.numValue,
            )
            VText("单位：$suffix", VTypo.caption, color = VColors.ink3)
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = { text.toLongOrNull()?.let { onConfirm(it.coerceAtLeast(1)) } },
                confirmText = "确定",
                confirmEnabled = text.toLongOrNull() != null,
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}

@Composable
private fun UnitPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    val presets = listOf("本", "份", "次", "页", "章", "个", "题", "小时", "公里", "分钟")
    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        Column(
            Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("单位", VTypo.dialogTitle, color = VColors.ink)
            // 每行 5 个，两行放完预设档。
            presets.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { unit ->
                        val active = unit == current
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(if (active) VColors.accent else VColors.surface2, RoundedCornerShape(12.dp))
                                .vPressable(scaleDown = 0.94f) { onConfirm(unit) },
                            contentAlignment = Alignment.Center,
                        ) {
                            VText(unit, VTypo.caption12, color = if (active) Color.White else VColors.ink2, maxLines = 1)
                        }
                    }
                    // 补齐空位，保证每格等宽
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            PlanTextField(value = custom, onValueChange = { custom = it.take(6) }, placeholder = "自定义单位")
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = { if (custom.isNotBlank()) onConfirm(custom.trim()) },
                confirmText = "用这个",
                confirmEnabled = custom.isNotBlank(),
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VText(title, VTypo.dialogTitle, color = VColors.ink)
            PlanTextField(value = text, onValueChange = { text = it }, placeholder = placeholder)
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = { if (text.isNotBlank()) onConfirm(text) },
                confirmText = "添加",
                confirmEnabled = text.isNotBlank(),
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}

/** 周期时限：频率 + 每周天数。两者一起改，因为「每周 5 天」正是频率的含义。 */
@Composable
private fun WeeklyTargetDialog(
    current: Int?,
    frequency: HabitFrequency,
    onDismiss: () -> Unit,
    onConfirm: (Int?, HabitFrequency) -> Unit,
) {
    var freq by remember { mutableStateOf(frequency) }
    var custom by remember { mutableStateOf(current ?: habitWeeklyTarget(frequency)) }
    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        Column(
            Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("周期时限", VTypo.dialogTitle, color = VColors.ink)
            VText("哪几天要做这件事", VTypo.caption, color = VColors.ink3)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HabitFrequency.Daily,
                    HabitFrequency.Weekdays,
                    HabitFrequency.Weekend,
                ).forEach { option ->
                    val active = freq == option
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .background(if (active) VColors.accentSoft else VColors.surface2, RoundedCornerShape(12.dp))
                            .vPressable(scaleDown = 0.97f) { freq = option },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Spacer(Modifier.width(14.dp))
                        VText(freqLabel(option), VTypo.bodyMed, color = if (active) VColors.accent else VColors.ink)
                        Spacer(Modifier.weight(1f))
                        VText("每周 ${habitWeeklyTarget(option)} 天", VTypo.caption, color = if (active) VColors.accent else VColors.ink3)
                        Spacer(Modifier.width(14.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                VText("每周天数（自定义）", VTypo.body, color = VColors.ink2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(32.dp).background(VColors.surface2, RoundedCornerShape(10.dp))
                            .vPressable(scaleDown = 0.9f) { custom = (custom - 1).coerceAtLeast(1) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lucide.Minus, contentDescription = null, modifier = Modifier.size(15.dp), tint = VColors.ink) }
                    VText("$custom", VTypo.numValue, color = VColors.ink)
                    Box(
                        Modifier.size(32.dp).background(VColors.surface2, RoundedCornerShape(10.dp))
                            .vPressable(scaleDown = 0.9f) { custom = (custom + 1).coerceAtMost(7) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(15.dp), tint = VColors.ink) }
                }
            }
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = { onConfirm(custom, freq) },
                confirmText = "确定",
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}

/** 添加一档提前提醒。已满 3 档或间隔不足时给出提示而不是静默失败。 */
@Composable
private fun ReminderLeadDialog(
    existing: List<Int>,
    habit: Boolean,
    onDismiss: () -> Unit,
    onAdd: (Int) -> Unit,
) {
    val options = if (habit) PlanMeta.MINUTE_LEADS else PlanMeta.DAY_LEADS
    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        Column(
            Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("添加提醒时间", VTypo.dialogTitle, color = VColors.ink)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { minutes ->
                    val already = minutes in existing
                    val tooClose = !already && existing.any { kotlin.math.abs(it - minutes) < PlanMeta.MIN_GAP_MINUTES }
                    val usable = !already && !tooClose && existing.size < PlanMeta.MAX_REMINDERS
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(if (usable) VColors.surface2 else VColors.bg, RoundedCornerShape(12.dp))
                            .then(if (usable) Modifier.vPressable(scaleDown = 0.97f) { onAdd(minutes) } else Modifier)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        VText(PlanMeta.leadLabel(minutes), VTypo.bodyMed, color = if (usable) VColors.ink else VColors.ink3)
                        VText(
                            when {
                                already -> "已添加"
                                tooClose -> "与已有档位太近"
                                !usable -> "已满 ${PlanMeta.MAX_REMINDERS} 档"
                                else -> "添加"
                            },
                            VTypo.caption,
                            color = if (usable) VColors.accent else VColors.ink3,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 关联日程。
 *
 * 候选来自 [PlanGoalEditorViewModel.linkableEvents]（今天起、与目标标题有共同词的排前面）。
 * 一条候选都没有时给出**可执行的说明**而不是空面板 —— 空面板等于死路。
 */
@Composable
private fun LinkEventsDialog(
    selected: List<String>,
    candidates: List<LinkEventOption>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss, maxWidth = 360.dp) {
        Column(
            Modifier.fillMaxWidth().background(VColors.surface, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VText("关联日程", VTypo.dialogTitle, color = VColors.ink)
            VText("关联后，从这些日程可以直接开始专注。", VTypo.caption, color = VColors.ink3)
            if (candidates.isEmpty()) {
                VText("今天起还没有可关联的日程。先去日程页加一条，再回来关联。", VTypo.caption12, color = VColors.ink3)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { event ->
                        val active = event.id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(if (active) VColors.accentSoft else VColors.surface2, RoundedCornerShape(13.dp))
                                .vPressable(scaleDown = 0.97f) { onToggle(event.id) }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .background(if (active) VColors.accent else Color.Transparent, CircleShape)
                                    .border(1.5.dp, if (active) Color.Transparent else VColors.line, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (active) Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color.White)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                VText(event.title, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                                VText(event.meta, VTypo.caption, color = VColors.ink3, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(VColors.surface2, RoundedCornerShape(13.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                VText("完成", VTypo.button, color = VColors.ink2)
            }
        }
    }
}

// ---------------------------------------------------------------- 时间文案 <-> 分钟

/**
 * `VTimePickerDialog` 用「一天中的第几分钟」，而习惯的提醒时间在表单/抽屉里
 * 存的是 `"HH:mm"`。这两个换算以前散在我自造的那个步进盘弹窗里，
 * 现在公共件接管了界面，只留下这对纯函数。
 */
private fun parseClock(clock: String): Int {
    val parts = clock.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return (h.coerceIn(0, 23)) * 60 + m.coerceIn(0, 59)
}

private fun formatClock(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay.coerceIn(0, 1439) / 60, minuteOfDay.coerceIn(0, 1439) % 60)

/** 概览卡迷你网格的列数（近 12 周 = 约 3 个月）。 */
private const val MINI_GRID_WEEKS = 12

