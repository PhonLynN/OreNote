package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.focus.FocusMode
import com.phonlynn.oreplan.domain.focus.FocusResult
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.focus.FocusTarget
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VPrimaryButton
import com.phonlynn.oreplan.v2.components.VPageHorizontal
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import kotlinx.coroutines.launch

/**
 * 规划 · 专注完成（设计稿 `UmxQp`）。
 *
 * 四块：
 * 1. **结果 hero**：计时方式 / 实际时长 / 关联对象；
 * 2. **对照卡**：预计 vs 实际（超出/提前多少）——只有带计划时长的模式才有；
 * 3. **反馈卡**：问「这次专注推进了目标吗」，两个按钮都真的做事
 *    （「更新进度」就地记一笔，「去目标填写」跳到目标详情弹层）；
 * 4. **保存记录**：写入 `focus_sessions`。
 *
 * 不保存直接退出也允许（`discardResult`）——强留一份记录反而会让人不敢开始。
 */
@Composable
fun FocusDoneScreen(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    holder: FocusHolderViewModel = hiltViewModel(),
) {
    val focus = holder.controller
    val result by focus.result.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updatedHint by remember { mutableStateOf<String?>(null) }

    val state = result
    if (state == null) {
        // 已经保存过 / 没有待保存的结果：给一个明确的落点。
        Column(
            Modifier.fillMaxSize().background(VColors.bg).statusBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(64.dp).background(VColors.accentSoft, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Icon(Lucide.CircleCheck, contentDescription = null, modifier = Modifier.size(26.dp), tint = VColors.accent)
            }
            Spacer(Modifier.height(14.dp))
            VText("记录已经保存了", VTypo.bodyMed, color = VColors.ink)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .height(44.dp)
                    .background(VColors.accent, RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.96f, onClick = { navigate(V2Routes.PLAN) })
                    .padding(horizontal = VPageHorizontal),
                contentAlignment = Alignment.Center,
            ) {
                VText("回到规划", VTypo.button, color = Color.White)
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding()
            .padding(horizontal = VPageHorizontal),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(VColors.surface, CircleShape)
                    .vPressable(scaleDown = 0.9f) {
                        focus.discardResult()
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.X, contentDescription = "不保存，返回", modifier = Modifier.size(18.dp), tint = VColors.ink)
            }
            Spacer(Modifier.width(12.dp))
            VText("专注完成", VTypo.pageTitle, color = VColors.ink, maxLines = 1)
        }

        Spacer(Modifier.height(18.dp))

        ResultHero(state)

        state.overMinutes?.let { over ->
            Spacer(Modifier.height(14.dp))
            CompareCard(state, over)
        }

        if (state.target is FocusTarget.Goal) {
            Spacer(Modifier.height(14.dp))
            FeedbackCard(
                goalTitle = (state.target as FocusTarget.Goal).title,
                updatedHint = updatedHint,
                onUpdate = { showUpdateDialog = true },
                onOpenGoal = {
                    val goalId = (state.target as FocusTarget.Goal).id
                    focus.discardResult()
                    GoalDetailBus.open(goalId)
                    navigate(V2Routes.PLAN)
                },
            )
        }

        Spacer(Modifier.weight(1f))

        VPrimaryButton(
            text = "保存记录",
            onClick = {
                scope.launch {
                    focus.saveResult()
                    navigate(V2Routes.PLAN)
                }
            },
        )
        Spacer(Modifier.height(28.dp))
    }

    if (showUpdateDialog) {
        val goalId = (state.target as? FocusTarget.Goal)?.id
        GoalProgressDialog(
            goalId = goalId,
            onDismiss = { showUpdateDialog = false },
            onDone = { text ->
                updatedHint = text
                showUpdateDialog = false
            },
        )
    }
}

// ---------------------------------------------------------------- 结果

@Composable
private fun ResultHero(state: FocusResult) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(20.dp))
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VText(state.mode.label, VTypo.caption, color = VColors.ink3)
        VText(FocusStats.clockText(state.elapsedSeconds), VTypo.numResult, color = VColors.ink)
        if (state.target != FocusTarget.None) {
            Box(
                Modifier
                    .background(VColors.accentSoft, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(
                        when (state.target) {
                            is FocusTarget.Goal -> Lucide.Target
                            is FocusTarget.Event -> Lucide.Calendar
                            FocusTarget.None -> Lucide.Hash
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = VColors.accent,
                    )
                    VText(
                        state.target.titleOrNull ?: "自由专注",
                        VTypo.caption12.copy(fontWeight = FontWeight.Medium),
                        color = VColors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 对照卡：预计 / 实际 / 差多少。
 *
 * 三种结论的语气不同，用不同颜色：
 * - 超出 → amber（不是错误，但值得注意）；
 * - 提前 → accent（好事）；
 * - 符合（±10% 内）→ accent。
 */
@Composable
private fun CompareCard(state: FocusResult, overMinutes: Int) {
    val planned = state.plannedMinutes ?: return
    val ratio = if (planned > 0) state.minutes.toFloat() / planned else 1f
    val onTime = kotlin.math.abs(overMinutes) <= (planned * 0.1f).coerceAtLeast(1f)
    val label = when {
        onTime -> "符合预期"
        overMinutes > 0 -> "超出 ${(ratio * 100).toInt() - 100}%"
        else -> "提前 ${-overMinutes} 分钟"
    }
    val tone = if (!onTime && overMinutes > 0) VColors.amber else VColors.accent
    val toneSoft = if (!onTime && overMinutes > 0) VColors.amberSoft else VColors.accentSoft

    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VText("与预计用时对照", VTypo.bodyMed, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                Modifier.background(toneSoft, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                VText(label, VTypo.micro.copy(fontWeight = FontWeight.Medium), color = tone, maxLines = 1)
            }
        }
        // 预计的实心段 + 实际超出/不足的浅色段，用同一个坐标系画出来。
        val plannedFraction = (planned.toFloat() / maxOf(planned, state.minutes)).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(VColors.surface2, RoundedCornerShape(4.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(plannedFraction)
                    .height(8.dp)
                    .background(VColors.lilac, RoundedCornerShape(4.dp)),
            )
        }
        VText(
            "预计 ${FocusStats.clockText(planned * 60L)} · 实际 ${FocusStats.clockText(state.elapsedSeconds)} · " +
                if (overMinutes >= 0) "超出 ${overMinutes} 分钟" else "提前 ${-overMinutes} 分钟",
            VTypo.caption,
            color = VColors.ink3,
        )
    }
}

/** 反馈卡：这次专注推进了目标吗？两个按钮都真的做事。 */
@Composable
private fun FeedbackCard(
    goalTitle: String,
    updatedHint: String?,
    onUpdate: () -> Unit,
    onOpenGoal: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VText("这次专注推进「$goalTitle」了吗？", VTypo.bodyMed, color = VColors.ink, maxLines = 2)
        if (updatedHint != null) {
            VText(updatedHint, VTypo.caption12, color = VColors.accent)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(VColors.accentSoft, RoundedCornerShape(12.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onUpdate),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(15.dp), tint = VColors.accent)
                    VText("更新进度", VTypo.caption12.copy(fontWeight = FontWeight.SemiBold), color = VColors.accent)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(VColors.surface2, RoundedCornerShape(12.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onOpenGoal),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.ArrowUpRight, contentDescription = null, modifier = Modifier.size(15.dp), tint = VColors.ink2)
                    VText("去目标填写", VTypo.caption12, color = VColors.ink2)
                }
            }
        }
    }
}
