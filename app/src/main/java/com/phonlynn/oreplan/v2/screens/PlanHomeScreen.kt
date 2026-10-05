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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VFAB
import com.phonlynn.oreplan.v2.components.VPageHorizontal
import com.phonlynn.oreplan.v2.components.VScreenTitle
import com.phonlynn.oreplan.v2.components.VTab
import com.phonlynn.oreplan.v2.components.VTabBottomPadding
import com.phonlynn.oreplan.v2.components.VTabScaffold
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VRadius
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VChipSmall
import com.phonlynn.oreplan.v2.components.VChevron

/**
 * 规划主页（0.3.0 全新）—— 设计稿 `wBNwV 规划 V2`。
 *
 * 结构（自上而下）：
 * 1. 页头：`第 N 周 · 3月10日 – 3月16日` + 大标题「规划」+ 设置钮；
 * 2. **今日专注卡**：大数字 / 今日目标、连续天数、今日次数、开始专注按钮、
 *    分隔线、本周柱状图 + 本周合计与环比；
 * 3. 「进行中的目标」小节 + 右侧「已归档」入口；
 * 4. 目标栈（三种类型的卡片）；
 * 5. FAB（新建目标）。
 *
 * 与旧版（0.2.x）的区别：旧版是「筛选胶囊 + 按类型分组的两列网格」，
 * 新版是**单列大卡 + 日程式专注统计头**。旧代码已整批删除，不做兼容。
 */
@Composable
fun PlanScreenV2(
    navigate: (String) -> Unit,
    onSelectTab: (VTab) -> Unit,
    onAi: () -> Unit,
    viewModel: PlanHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 回到本页时刷新「今天」：Tab 页会长期驻留，跨天后不刷新就成了昨天的数据。
    LaunchedEffect(Unit) { viewModel.refreshToday() }

    VTabScaffold(
        active = VTab.Plan,
        onSelect = onSelectTab,
        fab = { VFAB(onClick = { navigate(V2Routes.goalEditor()) }) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = VPageHorizontal, end = VPageHorizontal, top = 10.dp, bottom = VTabBottomPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                VScreenTitle(
                    title = "规划",
                    overline = state.overline,
                    onSidebar = { navigate(V2Routes.SETTINGS) },
                    onAi = onAi,
                )
            }

            item(key = "focus") {
                FocusCard(
                    state = state,
                    onStart = { navigate(V2Routes.FOCUS_START) },
                    onRecords = { navigate(V2Routes.FOCUS_RECORDS) },
                )
            }

            item(key = "goals-head") {
                VSectionHead(
                    title = "进行中的目标",
                    trailing = {
                        // 行内入口 = 公共件组合（VChipSmall + VChevron），
                        // 不再自写 PlanLinkRow。
                        Row(
                            Modifier.vPressable(scaleDown = 0.94f) { navigate(V2Routes.PLAN_ARCHIVE) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            VChipSmall("已归档" + if (state.archivedCount > 0) " ${state.archivedCount}" else "")
                            VChevron(tint = VColors.accent, size = 13.dp)
                        }
                    },
                )
            }

            if (!state.loaded) {
                // ⚠️ **数据还没到就什么都不画**（历史 bug，本轮又踩了一次）。
                //
                // 现象：进规划页时，空状态「还没有目标 / 点这里或右下角 + 新建第一个目标」
                // 会闪一帧。
                //
                // 根因：`uiState` 的 `initialValue = PlanHomeUiState()`（其中 goals 为空），
                // 而下面那个 `combine` 要等 Room 与扩展抽屉**各自首次发射**才算得出真值 ——
                // 于是第一帧拿到的就是「0 个目标」，空状态被当成「确实没有目标」画了出来。
                //
                // 口径与项目历史一致（commit 7566639「卡片编辑页：消除进入时的空状态闪现」）：
                // **未加载完不渲染内容**，只铺同色背景；数据到位后一次性画出完整列表。
                // 这里连骨架都不铺 —— 首帧通常只有几十毫秒，骨架自己闪一下反而更显眼。
                item(key = "loading") { Box(Modifier.fillMaxWidth().height(1.dp)) }
            } else if (state.goals.isEmpty()) {
                item(key = "empty") { EmptyGoals(onCreate = { navigate(V2Routes.goalEditor()) }) }
            } else {
                items(state.goals.size, key = { state.goals[it].id }) { index ->
                    val goal = state.goals[index]
                    GoalCard(data = goal, onClick = { GoalDetailBus.open(goal.id) })
                }
            }
        }

        // 目标详情弹层：挂在内容之上、底栏之下 —— 设计稿里底栏是被压暗的，
        // 说明这一层属于本页而不是新开的一页。
        GoalDetailSheetHost(navigate = navigate)
    }
}

/**
 * 今日专注卡（设计稿 Focus Card 350x194）。
 *
 * 三层：`今日专注 / 查看记录` → 大数字 + 次数 + 连续 → 分隔线 + 本周柱状。
 */
@Composable
private fun FocusCard(
    state: PlanHomeUiState,
    onStart: () -> Unit,
    onRecords: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(VRadius.hero))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText("今日专注", VTypo.caption, color = VColors.ink3, maxLines = 1)
            Row(
                Modifier.vPressable(scaleDown = 0.94f, onClick = onRecords),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                VChipSmall("查看记录")
                VChevron(tint = VColors.accent, size = 13.dp)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    VText(FocusStats.durationText(state.todayFocusMinutes), VTypo.numBig, color = VColors.ink)
                    Spacer(Modifier.width(4.dp))
                    VText("/ ${FocusStats.durationText(state.dailyGoalMinutes)}", VTypo.caption, color = VColors.ink3)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.streakDays > 0) {
                        Row(
                            Modifier
                                .background(VColors.accentSoft, RoundedCornerShape(9.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(Lucide.Flame, contentDescription = null, modifier = Modifier.size(12.dp), tint = VColors.accent)
                            VText("连续 ${state.streakDays} 天", VTypo.micro.copy(fontWeight = FontWeight.SemiBold), color = VColors.accent)
                        }
                    }
                    VText("今日 ${state.todayFocusCount} 次", VTypo.caption, color = VColors.ink3, maxLines = 1)
                }
            }
            Box(
                Modifier
                    .height(41.dp)
                    .background(VColors.accent, RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.95f, onClick = onStart)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                    VText("开始专注", VTypo.bodyMed.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.line))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            FocusWeekBars(
                dailyMinutes = state.weekDailyMinutes,
                weekStart = state.weekStart,
                today = state.today,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText("本周", VTypo.micro, color = VColors.ink3, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VText(FocusStats.durationText(state.weekTotalMinutes), VTypo.numPercent, color = VColors.ink)
                    state.weekDeltaPercent?.let { delta ->
                        VText(
                            text = if (delta >= 0) "+$delta%" else "$delta%",
                            style = VTypo.numMini,
                            color = if (delta >= 0) VColors.accent else VColors.rose,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 空态：还没建过目标。点一下直接进新建页 —— 空态里的行动入口不能是死的。 */
@Composable
private fun EmptyGoals(onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(VRadius.cardBig))
            .vPressable(scaleDown = 0.99f, onClick = onCreate)
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(56.dp).background(VColors.accentSoft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(Lucide.Target, contentDescription = null, modifier = Modifier.size(24.dp), tint = VColors.accent)
        }
        VText("还没有目标", VTypo.bodyMed, color = VColors.ink)
        VText("点这里或右下角 + 新建第一个目标", VTypo.caption12, color = VColors.ink3)
    }
}
