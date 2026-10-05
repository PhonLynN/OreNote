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
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VPageHorizontal
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VChip

/**
 * 规划 · 已归档（设计稿 `Mc5BR`）。
 *
 * 三段统计（已归档 / 已完成 / 已放弃）+ 月份分组卡片 + 筛选。
 * 行内点击开操作面板：恢复 / 查看目标 / 彻底删除 —— 三个都是真动作。
 */
@Composable
fun PlanArchiveScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: PlanArchiveViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilter by remember { mutableStateOf(false) }
    var actionRow by remember { mutableStateOf<ArchivedGoalRow?>(null) }
    var deleteRow by remember { mutableStateOf<ArchivedGoalRow?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
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
            VText("已归档", VTypo.pageTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (state.statusFilter != ArchiveStatusFilter.ALL || state.typeFilter != ArchiveTypeFilter.ALL) {
                            VColors.accentSoft
                        } else {
                            VColors.surface
                        },
                        CircleShape,
                    )
                    .vPressable(scaleDown = 0.9f) { showFilter = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.SlidersHorizontal,
                    contentDescription = "筛选",
                    modifier = Modifier.size(18.dp),
                    tint = if (state.statusFilter != ArchiveStatusFilter.ALL || state.typeFilter != ArchiveTypeFilter.ALL) {
                        VColors.accent
                    } else {
                        VColors.ink2
                    },
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = VPageHorizontal, end = VPageHorizontal, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "stats") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("${state.total}", "已归档", Modifier.weight(1f))
                    StatTile("${state.achieved}", "已完成", Modifier.weight(1f))
                    StatTile("${state.abandoned}", "已放弃", Modifier.weight(1f))
                }
            }

            if (!state.loaded) {
                // ⚠️ 数据未到就不画——否则会闪一帧「还没有归档的目标」，
                // 而三格统计也会先显示 0/0/0。与规划首页同一口径（commit 7566639）。
                item(key = "loading") { Box(Modifier.fillMaxWidth().height(1.dp)) }
            } else if (state.sections.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.surface, RoundedCornerShape(16.dp))
                            .padding(vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(50.dp).background(VColors.bg, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Lucide.Archive, contentDescription = null, modifier = Modifier.size(22.dp), tint = VColors.ink3)
                        }
                        VText(
                            if (state.total == 0) "还没有归档的目标" else "这个筛选下没有目标",
                            VTypo.bodyMed,
                            color = VColors.ink,
                        )
                        VText(
                            if (state.total == 0) "在目标设置里点「归档目标」就会出现在这里" else "换个筛选条件看看",
                            VTypo.caption12,
                            color = VColors.ink3,
                        )
                    }
                }
            }

            state.sections.forEach { section ->
                item(key = "head-${section.monthKey}") {
                    VSectionHead(title = section.monthLabel, note = "${section.rows.size} 个")
                }
                item(key = "card-${section.monthKey}") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.surface, RoundedCornerShape(16.dp)),
                    ) {
                        section.rows.forEachIndexed { index, row ->
                            ArchivedRow(row) { actionRow = row }
                            if (index != section.rows.lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(VColors.line))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilter) {
        ArchiveFilterDialog(
            statusFilter = state.statusFilter,
            typeFilter = state.typeFilter,
            onStatus = viewModel::setStatusFilter,
            onType = viewModel::setTypeFilter,
            onDismiss = { showFilter = false },
        )
    }

    actionRow?.let { row ->
        ArchiveActionDialog(
            row = row,
            onDismiss = { actionRow = null },
            onRestore = {
                viewModel.restore(row.id)
                actionRow = null
            },
            onOpen = {
                actionRow = null
                GoalDetailBus.open(row.id)
                navigate(V2Routes.PLAN)
            },
            onDelete = {
                actionRow = null
                deleteRow = row
            },
        )
    }

    deleteRow?.let { row ->
        VConfirmDeleteDialog(
            title = "彻底删除这个目标？",
            message = "「${row.title}」及其子项、打卡、数量记录都会一并删除，无法恢复。",
            onDismiss = { deleteRow = null },
            onConfirm = {
                viewModel.delete(row.id)
                deleteRow = null
            },
        )
    }
}

// ---------------------------------------------------------------- 行与小件

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .height(66.dp)
            .background(VColors.surface, RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VText(value, VTypo.numStat, color = VColors.ink, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        VText(label, VTypo.micro, color = VColors.ink3, maxLines = 1)
    }
}

@Composable
private fun ArchivedRow(row: ArchivedGoalRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(59.dp)
            .vPressable(scaleDown = 0.99f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(PlanUi.typeSoft(row.type), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PlanUi.typeIcon(row.type), contentDescription = null, modifier = Modifier.size(15.dp), tint = PlanUi.typeColor(row.type))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(row.title, VTypo.body, color = VColors.ink, maxLines = 1)
            VText(
                "${PlanUi.typeLabel(row.type)} · ${PlanUi.monthDay(row.archivedAt)}归档",
                VTypo.caption,
                color = VColors.ink3,
                maxLines = 1,
            )
        }
        // 状态胶囊走公共的 VChip（原先自写的 StatusPill 已删）。
        VChip(
            text = if (row.achieved) "完成" else "放弃",
            background = if (row.achieved) VColors.accentSoft else VColors.surface2,
            foreground = if (row.achieved) VColors.accent else VColors.ink3,
        )
        Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(14.dp), tint = VColors.ink3)
    }
}

/** 筛选面板：状态三选 + 类型四选。选完立即生效。 */
@Composable
private fun ArchiveFilterDialog(
    statusFilter: ArchiveStatusFilter,
    typeFilter: ArchiveTypeFilter,
    onStatus: (ArchiveStatusFilter) -> Unit,
    onType: (ArchiveTypeFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("筛选", VTypo.dialogTitle, color = VColors.ink)

            VText("状态", VTypo.caption, color = VColors.ink3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArchiveStatusFilter.entries.forEach { option ->
                    FilterPill(option.label, option == statusFilter) { onStatus(option) }
                }
            }

            VText("类型", VTypo.caption, color = VColors.ink3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArchiveTypeFilter.entries.forEach { option ->
                    FilterPill(option.label, option == typeFilter) { onType(option) }
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

@Composable
private fun androidx.compose.foundation.layout.RowScope.FilterPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .height(36.dp)
            .background(if (active) VColors.accent else VColors.surface2, RoundedCornerShape(12.dp))
            .vPressable(scaleDown = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            label,
            VTypo.caption12.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
            color = if (active) Color.White else VColors.ink2,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArchiveActionDialog(
    row: ArchivedGoalRow,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VText(row.title, VTypo.dialogTitle, color = VColors.ink, maxLines = 2)
            VText(
                "${PlanUi.typeLabel(row.type)} · ${PlanUi.monthDay(row.archivedAt)}归档 · " +
                    if (row.achieved) "完成" else "放弃",
                VTypo.caption,
                color = VColors.ink3,
            )
            Spacer(Modifier.height(2.dp))
            ArchiveAction(Lucide.RotateCcw, "恢复到进行中", VColors.accent, onRestore)
            ArchiveAction(Lucide.ArrowUpRight, "查看目标详情", VColors.ink, onOpen)
            ArchiveAction(Lucide.Trash2, "彻底删除", VColors.rose, onDelete)
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
}

@Composable
private fun ArchiveAction(
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
