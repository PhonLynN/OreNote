package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.phonlynn.oreplan.domain.focus.FocusMode
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import java.time.Instant
import kotlin.math.roundToInt

/**
 * 专注计时 · 悬浮窗（设计稿 `gkA8D`）。
 *
 * 设计稿给的是「规划页背后压着内容、右下角一个 84x84 的圆角浮窗」——
 * 也就是**应用内的浮层**，不是系统级悬浮窗（系统级要 `SYSTEM_ALERT_WINDOW`
 * 权限，会在设置里留一个「显示在其他应用上层」的开关，那是另一种产品决策）。
 *
 * 行为：
 * - 只在**有活动专注**且**不在专注页**时显示（专注页自己就是全屏计时）；
 * - 可拖动（记住位置），拖动用 `detectDragGestures`，松手不会误触发点击；
 * - 点它回专注中页；右下角绿点 = 正在计时（暂停时变琥珀）。
 */
@Composable
fun FocusFloatingWindow(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    holder: FocusHolderViewModel = hiltViewModel(),
) {
    if (!visible) return
    val focus = holder.controller
    val active by focus.active.collectAsStateWithLifecycle()
    val tick by focus.tick.collectAsStateWithLifecycle(initialValue = 0L)
    val state = active ?: return

    val density = LocalDensity.current
    val windowSize = 84.dp
    val windowSizePx = with(density) { windowSize.toPx() }

    // 位置用「距右下角的偏移」记：换屏幕/换方向时浮窗不会跑到屏幕外。
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val now = if (tick > 0) Instant.ofEpochMilli(tick) else Instant.now()
    val elapsed = state.elapsedSeconds(now)
    val remaining = state.remainingSeconds(now)
    val label = when (state.mode) {
        FocusMode.COUNT_UP -> FocusStats.clockText(elapsed)
        else -> FocusStats.clockText(remaining ?: elapsed)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxX = with(density) { (maxWidth - windowSize).toPx() }
        val maxY = with(density) { (maxHeight - windowSize).toPx() }
        Box(
            Modifier
                .offset { IntOffset(x = (maxX + offsetX).roundToInt(), y = (maxY + offsetY).roundToInt()) }
                .size(windowSize)
                .shadow(18.dp, RoundedCornerShape(24.dp), spotColor = Color(0x4D101613), ambientColor = Color(0x33101613))
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .pointerInput(windowSizePx) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDrag = { change, drag ->
                            change.consume()
                            // 夹在可视区内：拖到屏幕外就再也抓不回来了。
                            offsetX = (offsetX + drag.x).coerceIn(-maxX, 0f)
                            offsetY = (offsetY + drag.y).coerceIn(-maxY, 0f)
                        },
                    )
                }
                .vPressable(scaleDown = 0.94f) {
                    // 刚拖完的那一下不当作点击（否则拖完就跳页）。
                    if (!dragging) onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                FocusMiniRing(
                    progress = state.progress(now),
                    diameter = 56.dp,
                    progressColor = if (state.isPaused) VColors.amber else VColors.accent,
                )
                Spacer(Modifier.height(4.dp))
                VText(label, VTypo.numTime, color = VColors.ink, maxLines = 1)
            }
            // 右上角状态点：在跑 = accent，暂停 = amber
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(10.dp)
                    .background(if (state.isPaused) VColors.amber else VColors.accent, CircleShape),
            )
            // 中心叠一个透明层放图标的位置说明（图标放中心会压到时间文字，所以放左上）
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.isPaused) Lucide.Play else Lucide.Timer,
                    contentDescription = if (state.isPaused) "已暂停" else "计时中",
                    modifier = Modifier.size(12.dp),
                    tint = VColors.ink3,
                )
            }
        }
    }
}
