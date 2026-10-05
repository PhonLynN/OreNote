package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.focus.ActiveFocus
import com.phonlynn.oreplan.domain.focus.FocusMode
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.focus.FocusTarget
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import java.time.Instant
import com.phonlynn.oreplan.v2.components.VPageHorizontal

/**
 * 规划 · 专注中（设计稿 `aogr4 倒计时` / `lBnWn 正计时`）。
 *
 * 倒计时与番茄钟显示大环（剩余时间 + 已专注），正计时显示一个大数字。
 * 共用的控制条按模式给不同按钮 —— 这些差异是设计稿明写的：
 * - 倒计时：结束 / 暂停 / 跳过；
 * - 正计时：结束 / 暂停（没有「跳过」可跳）；
 * - 暂停中：中央按钮变成「继续」。
 *
 * 计时状态来自 [com.phonlynn.oreplan.domain.focus.FocusController]（单例），
 * 所以从这里返回规划页，计时照样在走（悬浮窗会继续显示）。
 */
@Composable
fun FocusRunningScreen(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    holder: FocusHolderViewModel = hiltViewModel(),
) {
    val focus = holder.controller
    val active by focus.active.collectAsStateWithLifecycle()
    // 心跳：真正的秒数永远现算，tick 只负责触发重绘（见 FocusController 的注释）。
    val tick by focus.tick.collectAsStateWithLifecycle(initialValue = 0L)

    val state = active
    if (state == null) {
        // 没有活动专注（比如进程被系统回收后重建）—— 明确说明并给出口，不留白屏。
        EmptyRunning(onBack = onBack)
        return
    }

    val now = if (tick > 0) Instant.ofEpochMilli(tick) else Instant.now()
    val elapsed = state.elapsedSeconds(now)
    val remaining = state.remainingSeconds(now)

    Column(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding()
            .padding(horizontal = VPageHorizontal),
        // ⚠️ **整个内容列水平居中**（用户 2026-10-02，已说过不止一次）。
        //
        // 设计稿 `Content`：`layout = vertical` + **`alignItems = center`** ——
        // 也就是说这一列里的**每一个**子项都居中：进度环、时钟、模式说明、
        // Target Chip、控制条。我只给内层几个 Column 加了居中，
        // 最外层这一列漏了，于是 Target Chip 靠着左边（而不是居中）。
        //
        // 教训：**居中属于容器，不属于某个子项**。逐个给子项加
        // `align(CenterHorizontally)` 是打补丁；容器写对一次就全对。
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(VColors.surface, CircleShape)
                    .vPressable(scaleDown = 0.9f, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.X, contentDescription = "收起（计时继续）", modifier = Modifier.size(18.dp), tint = VColors.ink)
            }
            Spacer(Modifier.width(12.dp))
            VText("专注中", VTypo.navTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(40.dp)
                    .background(VColors.surface, CircleShape)
                    .vPressable(scaleDown = 0.9f) { navigate(V2Routes.FOCUS_RECORDS) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.List, contentDescription = "专注记录", modifier = Modifier.size(18.dp), tint = VColors.ink2)
            }
        }

        Spacer(Modifier.weight(1f))

        when (state.mode) {
            FocusMode.COUNT_UP -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                VText(FocusStats.clockText(elapsed), VTypo.numDisplay, color = VColors.ink)
                VText("正计时 · 想停就按结束", VTypo.caption12, color = VColors.ink3)
            }
            else -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                FocusRing(progress = state.progress(now)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        VText(
                            FocusStats.clockText(remaining ?: elapsed),
                            VTypo.numRing,
                            color = VColors.ink,
                        )
                        VText("已专注 ${FocusStats.clockText(elapsed)}", VTypo.caption12, color = VColors.ink3)
                    }
                }
                Spacer(Modifier.height(16.dp))
                VText(
                    if (state.mode == FocusMode.POMODORO) {
                        "番茄钟 · 第 ${state.cycle} 段 · ${state.plannedMinutes ?: 25} 分钟"
                    } else {
                        "${state.plannedMinutes ?: 0} 分钟"
                    },
                    VTypo.caption12,
                    color = VColors.ink3,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        // 胶囊**居中**在页面里（设计稿 x130 于 390 宽页面 ⇒ 水平居中）。
        // 外层 Column 是 align(centerHorizontally) 的，所以这里不需要再撑宽度。
        TargetChip(state.target)

        Spacer(Modifier.weight(1f))

        Controls(
            state = state,
            onEnd = { focus.finish(); navigate(V2Routes.FOCUS_DONE) },
            onTogglePause = { focus.togglePause() },
            onSkip = {
                // 「跳过」= 提前结束并直接进完成页：不再等计划时长。
                focus.finish()
                navigate(V2Routes.FOCUS_DONE)
            },
            onAddMinute = { focus.addMinute() },
        )

        Spacer(Modifier.height(48.dp))
    }
}

/**
 * 专注对象胶囊（设计稿 `Target Chip`）。
 *
 * ⚠️ **按内容宽度收拢，不占满整行**（用户 2026-10-02）。
 * 设计稿实测：`width = undefined`（收内容）、130×37、`r 18`、`pad [9,14]`、
 * 内部 `gap 8`、`stroke $line 1px`、在页面里**居中**（x130 于 390 宽页面）。
 *
 * 我此前写成了 `Modifier.fillMaxWidth()` —— 于是胶囊横跨整行、
 * 变成一个"整行的条"，既不是设计稿的样子，也不像一颗胶囊。
 * 现在：外层 Column 用 `align(centerHorizontally)` 居中，胶囊本身只占内容宽度。
 */
@Composable
private fun TargetChip(target: FocusTarget) {
    val title = target.titleOrNull
    Row(
        Modifier
            .background(VColors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            when (target) {
                FocusTarget.None -> Lucide.Hash
                is FocusTarget.Goal -> Lucide.Target
                is FocusTarget.Event -> Lucide.Calendar
            },
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = VColors.accent,
        )
        VText(title ?: "自由专注", VTypo.body, color = VColors.ink, maxLines = 1)
    }
}

/** 控制条（设计稿 Controls 342x80）。 */
@Composable
private fun Controls(
    state: ActiveFocus,
    onEnd: () -> Unit,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
    onAddMinute: () -> Unit,
) {
    val showSkip = state.mode != FocusMode.COUNT_UP
    // 正计时给「+1 分钟」而不是「跳过」：它的痛点是「忘了按结束」，不是「想提前结束」。
    val sideAction: (@Composable () -> Unit)? = when {
        state.mode == FocusMode.COUNT_UP && !state.isPaused -> {
            { RoundControl(Lucide.Plus, "记 1 分钟", onAddMinute) }
        }
        showSkip -> {
            { RoundControl(Lucide.SkipForward, "跳过（直接结束）", onSkip) }
        }
        else -> null
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundControl(Lucide.Square, "结束专注", onEnd)
        Spacer(Modifier.width(28.dp))
        Box(
            Modifier
                .size(80.dp)
                .background(if (state.isPaused) VColors.amber else VColors.accent, CircleShape)
                .vPressable(scaleDown = 0.93f, onClick = onTogglePause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.isPaused) Lucide.Play else Lucide.Pause,
                contentDescription = if (state.isPaused) "继续" else "暂停",
                modifier = Modifier.size(32.dp),
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(28.dp))
        if (sideAction != null) {
            sideAction()
        } else {
            Spacer(Modifier.size(56.dp))
        }
    }
}

@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(56.dp)
            .background(VColors.surface, CircleShape)
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = VColors.ink2,
        )
    }
}

/** 没有活动专注时的兜底（进程被杀后重建会走到这里）。 */
@Composable
private fun EmptyRunning(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(VColors.bg).statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(64.dp).background(VColors.accentSoft, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            Icon(Lucide.Timer, contentDescription = null, modifier = Modifier.size(26.dp), tint = VColors.accent)
        }
        Spacer(Modifier.height(14.dp))
        VText("这次专注已经结束了", VTypo.bodyMed, color = VColors.ink)
        Spacer(Modifier.height(6.dp))
        VText("（计时不会在后台保留，重新开始一次就好。）", VTypo.caption12, color = VColors.ink3)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .height(44.dp)
                .background(VColors.accent, RoundedCornerShape(14.dp))
                .vPressable(scaleDown = 0.96f, onClick = onBack)
                .padding(horizontal = VPageHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            VText("返回规划", VTypo.buttonBold.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
    }
}
