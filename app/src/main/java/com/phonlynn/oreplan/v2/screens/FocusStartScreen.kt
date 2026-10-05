package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
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
import com.phonlynn.oreplan.domain.focus.FocusMode
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VPrimaryButton
import com.phonlynn.oreplan.v2.components.VPageHorizontal
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VChipSmall
import com.phonlynn.oreplan.v2.components.VChevron

/**
 * 规划 · 专注开始（设计稿 `E1Vcu3`）。
 *
 * 自上而下：计时方式（三选一）→ 专注时长（15/25/45/60/自定义）→
 * 专注对象（自由 / 目标 / 日程）→ 选中的对象列表或目标详情卡 → 开始专注。
 *
 * 「开始专注」在这里**真的启动计时器**（[FocusController.start]），然后进专注中页。
 * 时长 0 或没选对象时按钮置灰 —— 不留任何点了没反应的控件。
 */
@Composable
fun FocusStartScreen(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: FocusStartViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focus = hiltViewModel<FocusHolderViewModel>().controller
    val active by focus.active.collectAsStateWithLifecycle()
    var showCustom by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(
            Modifier.weight(1f).padding(horizontal = VPageHorizontal).padding(top = 10.dp),
        ) {
            // 页头：关闭 + 标题
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(VColors.surface, CircleShape)
                        .vPressable(scaleDown = 0.9f, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = VColors.ink)
                }
                Spacer(Modifier.width(12.dp))
                VText("开始专注", VTypo.pageTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
                // 已经在专注中时给一个明确入口，避免用户以为可以「再开一个」
                if (active != null) {
                    // 行内入口 = 公共件组合：VChipSmall（胶囊）+ VChevron。
                    // 原先这里是个自写的 `PlanLinkRow`，与公共层是同一件事的第二份实现。
                    Row(
                        Modifier.vPressable(scaleDown = 0.94f) { navigate(V2Routes.FOCUS_RUNNING) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        VChipSmall("回到专注中")
                        VChevron(tint = VColors.accent, size = 13.dp)
                    }
                }
            }

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "mode-head") { VSectionHead(title = "计时方式") }
                item(key = "mode") { ModeRow(state.mode, viewModel::setMode) }

                item(key = "duration-head") {
                    Box(Modifier.padding(top = 8.dp)) { VSectionHead(title = "专注时长") }
                }
                item(key = "duration") {
                    DurationRow(
                        presets = state.presets,
                        minutes = state.minutes,
                        custom = state.customMinutes,
                        disabled = !state.mode.hasPlan,
                        onPick = viewModel::setMinutes,
                        onCustom = { showCustom = true },
                    )
                }
                if (!state.mode.hasPlan) {
                    item(key = "duration-hint") {
                        VText(
                            "正计时不需要预设时长 —— 想停的时候按结束。",
                            VTypo.caption,
                            color = VColors.ink3,
                        )
                    }
                }

                item(key = "object-head") {
                    Box(Modifier.padding(top = 8.dp)) { VSectionHead(title = "专注对象") }
                }
                item(key = "object") {
                    ObjectRow(
                        selected = state.targetKind,
                        goalCount = state.goals.size,
                        eventCount = state.events.size,
                        onSelect = viewModel::setTargetKind,
                    )
                }

                if (state.pickRows.isNotEmpty()) {
                    item(key = "pick") {
                        PickCard(
                            rows = state.pickRows,
                            selectedId = state.selectedId(),
                            emptyHint = when (state.targetKind) {
                                FocusTargetKind.GOAL -> "还没有目标 —— 回规划页点 + 建一个。"
                                FocusTargetKind.EVENT -> "今天没有日程。先去日程页加一条，或者选「自由专注」。"
                                FocusTargetKind.FREE -> ""
                            },
                            onSelect = { id ->
                                when (state.targetKind) {
                                    FocusTargetKind.GOAL -> viewModel.selectGoal(id)
                                    FocusTargetKind.EVENT -> viewModel.selectEvent(id)
                                    FocusTargetKind.FREE -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }

        // 底部主按钮（设计稿 Primary Button 350x52）
        Column(Modifier.padding(horizontal = VPageHorizontal).padding(bottom = 24.dp)) {
            VPrimaryButton(
                text = state.startLabel,
                icon = Lucide.Play,
                enabled = state.canStart,
                onClick = {
                    val resolved = viewModel.resolve(state) ?: return@VPrimaryButton
                    focus.start(
                        target = resolved.first,
                        mode = resolved.second,
                        plannedMinutes = viewModel.plannedMinutes(state),
                    )
                    navigate(V2Routes.FOCUS_RUNNING)
                },
            )
            if (!state.canStart) {
                Spacer(Modifier.height(8.dp))
                VText(
                    "先在下面选一个要专注的对象",
                    VTypo.caption,
                    color = VColors.ink3,
                    align = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showCustom) {
        PlanDurationDialog(
            initialMinutes = state.minutes,
            onDismiss = { showCustom = false },
            onConfirm = { viewModel.setCustomMinutes(it); showCustom = false },
        )
    }
}

// ---------------------------------------------------------------- 分块

/** 计时方式三张卡（设计稿 Mode Row 350x96）。 */
@Composable
private fun ModeRow(selected: FocusMode, onSelect: (FocusMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusMode.entries.forEach { mode ->
            val active = mode == selected
            val icon = when (mode) {
                FocusMode.COUNT_DOWN -> Lucide.Hourglass
                FocusMode.COUNT_UP -> Lucide.Timer
                FocusMode.POMODORO -> Lucide.AlarmClock
            }
            Column(
                Modifier
                    .weight(1f)
                    .background(if (active) VColors.accentSoft else VColors.surface, RoundedCornerShape(16.dp))
                    .vPressable(scaleDown = 0.97f) { onSelect(mode) }
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (active) VColors.surface else VColors.bg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (active) VColors.accent else VColors.ink2)
                }
                VText(
                    mode.label,
                    VTypo.bodyMed.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (active) VColors.accent else VColors.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 专注时长五格（设计稿 `E1Vcu3` Duration Row：350 宽、gap 8、五格各 64×40）。 */
@Composable
private fun DurationRow(
    presets: List<Int>,
    minutes: Int,
    custom: Boolean,
    disabled: Boolean,
    onPick: (Int) -> Unit,
    onCustom: () -> Unit,
) {
    // 设计稿五格**等宽 64**（不是"自定义"那格更宽）—— 我此前给它 weight(1.3f)，
    // 是不对的：那一格看起来会比别的胖一圈。全部 weight(1f)。
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { preset ->
            val active = !custom && preset == minutes && !disabled
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(if (active) VColors.accent else VColors.surface, RoundedCornerShape(12.dp))
                    .vPressable(enabled = !disabled, scaleDown = 0.94f) { onPick(preset) },
                contentAlignment = Alignment.Center,
            ) {
                VText(
                    "$preset 分",
                    VTypo.caption12.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (active) Color.White else if (disabled) VColors.ink3 else VColors.ink2,
                    maxLines = 1,
                )
            }
        }
        val customActive = custom && !disabled
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .background(if (customActive) VColors.accent else VColors.surface, RoundedCornerShape(12.dp))
                .vPressable(enabled = !disabled, scaleDown = 0.94f, onClick = onCustom),
            contentAlignment = Alignment.Center,
        ) {
            VText(
                if (custom) "$minutes 分" else "自定义",
                VTypo.caption12.copy(fontWeight = if (customActive) FontWeight.SemiBold else FontWeight.Normal),
                color = if (customActive) Color.White else if (disabled) VColors.ink3 else VColors.ink2,
                maxLines = 1,
            )
        }
    }
}

/**
 * 专注对象三格（设计稿 `E1Vcu3` Object Row：350 宽、gap 8、三格各 111×38、内部 gap 6）。
 *
 * ⚠️ **标签气泡不能吃掉整行**（用户 2026-10-02）。
 *
 * 三格是等分整行的（设计稿就是 `fill_container`：实测 x=0/119/239、宽各 111，
 * 间距 8/9 不齐正是"等分后四舍五入"的痕迹）—— 等分本身没问题。
 * 有问题的是**格子里面的内容被拉满**：我此前给里面的 `Row` 没加宽度约束，
 * 它虽然是内容宽度，但外层 `Box(contentAlignment=Center)` 在
 * `Row(Modifier.fillMaxWidth())` 里量出来的格子被撑到满宽，
 * 于是气泡看起来"占满了整行"，而不是一个居中收拢的小胶囊。
 */
@Composable
private fun ObjectRow(
    selected: FocusTargetKind,
    goalCount: Int,
    eventCount: Int,
    onSelect: (FocusTargetKind) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusTargetKind.entries.forEach { kind ->
            val active = kind == selected
            val icon = when (kind) {
                FocusTargetKind.FREE -> Lucide.Hash
                FocusTargetKind.GOAL -> Lucide.Target
                FocusTargetKind.EVENT -> Lucide.Calendar
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .background(if (active) VColors.accentSoft else VColors.surface, RoundedCornerShape(12.dp))
                    .vPressable(scaleDown = 0.95f) { onSelect(kind) },
                contentAlignment = Alignment.Center,
            ) {
                // 内容按**自身宽度**收拢居中（gap 6 来自设计稿），
                // 不 fillMaxWidth —— 否则图标、文字、计数会被推到格子两端。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (active) VColors.accent else VColors.ink2)
                    VText(
                        kind.label,
                        VTypo.caption12.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                        color = if (active) VColors.accent else VColors.ink2,
                        maxLines = 1,
                    )
                    val count = when (kind) {
                        FocusTargetKind.FREE -> null
                        FocusTargetKind.GOAL -> goalCount.takeIf { it > 0 }
                        FocusTargetKind.EVENT -> eventCount.takeIf { it > 0 }
                    }
                    count?.let {
                        VText("$it", VTypo.numMini, color = if (active) VColors.accent else VColors.ink3, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 对象选择卡（设计稿 Pick Card 350x191）：单选行 + 分隔线。 */
@Composable
private fun PickCard(
    rows: List<FocusPickRow>,
    selectedId: String?,
    emptyHint: String,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(16.dp)),
    ) {
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                VText(emptyHint, VTypo.caption12, color = VColors.ink3, align = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@Column
        }
        rows.forEachIndexed { index, row ->
            val active = row.id == selectedId
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(63.dp)
                    .vPressable(scaleDown = 0.99f) { onSelect(row.id) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(if (active) VColors.accent else VColors.surface2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) Box(Modifier.size(8.dp).background(Color.White, CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VText(row.title, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                    VText(row.meta, VTypo.caption, color = VColors.ink3, maxLines = 1)
                }
                if (row.now) {
                    // 「进行中」胶囊：**按内容宽度**（设计稿 Now Pill 46×20、pad [3,8]、r9）。
                    // 加 weight/spaceBetween 会把它推到行尾、拉扯整行 —— 见下方 spacer。
                    Box(
                        Modifier
                            .background(VColors.accentSoft, RoundedCornerShape(9.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        VText("进行中", VTypo.micro, color = VColors.accent, maxLines = 1)
                    }
                } else {
                    // 没有胶囊时补一个**零宽**占位，让左侧 Column(weight(1f)) 的
                    // 宽度分配与"有胶囊"的行完全一致 —— 否则有胶囊的行文字会被压窄，
                    // 同一张卡里几行标题的左边界看起来不齐。
                    Spacer(Modifier.width(0.dp))
                }
            }
            if (index != rows.lastIndex) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(VColors.line))
            }
        }
    }
}
