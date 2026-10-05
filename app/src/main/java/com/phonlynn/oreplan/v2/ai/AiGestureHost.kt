package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 四指下滑唤起的**手势层**。
 *
 * ## ⚠️ 这个组件曾经是个**空壳**（2026-10-04 补上实现）
 *
 * 上一版的函数体只有一行 `Box { content() }` —— 上面那一大段 KDoc
 * 描述的判据（四指 / 位移阈值）**一行都没实现**。
 * 于是"四指下滑唤起"这个功能**从来没有生效过**，而代码看起来是"做了的"。
 *
 * 这类"注释写得很足、实现是空的"比明显的 TODO 更危险：
 * 读代码的人会以为功能在，只有真机试才会发现没反应。
 *
 * ## 为什么是四指
 *
 * 用户原话：「四指的误触概率低得可怕」。这个判断是对的 ——
 * 四指同时按下并下滑，在正常持机姿势下几乎不可能意外发生。
 * 所以这里**不加额外保护**（不判断速度、不要求二次确认、不显示提示）：
 * 加了只会让真正想用的人觉得迟钝。
 *
 * ## 为什么包在最外层
 *
 * 用户要求「无论在哪里都能唤起」，包括二级页面、编辑弹窗、
 * 甚至白板卡片拖到一半时。所以侦测必须放在**整棵界面树之外**，
 * 而不是各页面自己实现。
 *
 * ## 为什么不与会滚动的列表冲突
 *
 * Compose 的手势在 pointer 数量上天然区分：列表滚动是**单指**驱动的，
 * 而这里要求**同时 ≥4 个 pointer**。四指按下时，`awaitEachGesture` 会看到
 * 4 个 pointer 落下，此时直接接管 —— 列表不会认为那是滚动。
 *
 * ⚠️ 唯一需要留意的是**白板卡片的"长按抓取"**（它自己实现了手势循环、
 * 会阻止列表滚动）。但那是单指长按，四指场景下不会成立。
 *
 * ## 触发条件
 *
 * · 同时按下 ≥ [REQUIRED_POINTERS] 个手指
 * · 整体向下位移超过 [THRESHOLD_DP]
 *
 * 不判断速度：慢速稳稳下拉与快速甩下都应该生效，
 * 加速度判断只会让"慢慢滑"的用户困惑。
 */
@Composable
fun AiGestureHost(
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { THRESHOLD_DP.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled, thresholdPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    /*
                     * ⚠️ 用 `PointerEventPass.Initial` 拿**第一手**事件。
                     *
                     * 默认的 `Main` pass 是"从子节点往上冒泡"—— 子节点
                     *（列表、卡片手势）先看到事件，可能已经把它消费掉了。
                     * `Initial` 是"从父往下传"，我们作为最外层能先看到，
                     * 从而**在子节点之前**判断"这是不是四指"。
                     *
                     * 但我们**不能**把事件吞掉（那会让正常情况下所有点击失灵）——
                     * 只在确认"≥4 指"之后才接管，否则顺着往下传。
                     */
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    val startY = down.position.y

                    /*
                     * 等这一组手势结束，同时记录：
                     *  · 是否曾经同时按到过 ≥4 指
                     *  · 最终的整体下移量
                     *
                     * 用**起点**（第一次按下的那只手指）的位移作判据，
                     * 而不是"所有手指的平均" —— 四指按下时手指间距很大，
                     * 平均位移会被按得晚的手指稀释。
                     */
                    var maxPointers = 0
                    var dragged = 0f
                    var triggered = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > maxPointers) maxPointers = pressed

                        if (maxPointers >= REQUIRED_POINTERS) {
                            // 以第一只手指的位移为准
                            val currentY = event.changes.firstOrNull { it.id == down.id }
                                ?.position?.y
                            if (currentY != null) {
                                dragged = currentY - startY
                            }
                            if (!triggered && dragged >= thresholdPx) {
                                triggered = true
                                onTrigger()
                                // 触发后把这一组事件消费掉，避免底下的页面
                                // 同时收到一次莫名其妙的滑动
                                event.changes.forEach { it.consume() }
                            }
                        }

                        // 所有手指都抬起 → 这一组结束
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        content()
    }
}

/** 需要同时按下的手指数。 */
private const val REQUIRED_POINTERS = 4

/** 下滑多远算触发。60dp 既不易误触，也不至于要拉很久。 */
private const val THRESHOLD_DP = 60f
