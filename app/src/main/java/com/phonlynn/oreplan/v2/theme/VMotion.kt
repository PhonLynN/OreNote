package com.phonlynn.oreplan.v2.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * V2 动效规范：全部非线性；位移与淡入用快起慢收贝塞尔，连续状态用带阻尼弹簧。
 * 禁止 LinearEasing（项目硬规则）。
 */
object VMotion {
    /** 快起慢收（进入、展开）。 */
    val Expressive: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 缓起快收（退出、收起）。 */
    val Accelerate: Easing = CubicBezierEasing(0.5f, 0f, 0.75f, 0.4f)

    /** 标准强调（选择、切换）。 */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 慢-快-慢（两端缓、尾端着陆更缓），接近线性；适合卡片飞入/位移。 */
    val Glide: Easing = CubicBezierEasing(0.45f, 0f, 0.35f, 1f)

    const val EnterMillis = 300
    const val ExitMillis = 220
    const val RevealMillis = 260

    /** 连续状态变化：轻阻尼弹簧（按钮抬起、尺寸落位）。 */
    fun <T> settle() = spring<T>(dampingRatio = 0.85f, stiffness = 420f)

    /** 交互反馈：更快、更明确的阻尼。 */
    fun <T> snappy() = spring<T>(dampingRatio = 0.8f, stiffness = 700f)

    /** 选中断言（tab 指示器、chip）。 */
    fun <T> select() = spring<T>(dampingRatio = 0.86f, stiffness = 520f)

    /** 列表/内容出现。 */
    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** 轻微弹性：小幅过冲后回稳（卡片飞入、落位）。 */
    fun <T> springy() = spring<T>(dampingRatio = 0.7f, stiffness = 800f)

    /**
     * **颜色过渡**（2026-09-30 用户要求："变色过程必须有过渡动画"）。
     *
     * 为什么用 tween 而不是弹簧：颜色没有"惯性"这回事。
     * 用弹簧会让中间色停留过久（看不准最终颜色），
     * 而这里的变色是**状态指示**（能不能保存），必须让用户尽快确认结果。
     *
     * [ColorChangeMillis] 取 180ms：比内容进场（[EnterMillis] 300）快，
     * 因为它是"就地反馈"而不是"页面切换"；与 `vPressable` 的按压反馈同一量级，
     * 手感上是"立刻就知道变了"，但仍看得出是渐变而非硬切。
     */
    fun <T> color() = tween<T>(durationMillis = ColorChangeMillis, easing = Emphasized)

    const val ColorChangeMillis = 180
}

/**
 * 统一的可按压反馈：按下缩到 [scaleDown]，松开用阻尼弹簧回弹。
 * 所有可点击元素都走这个 modifier，保证按压手感一致。
 *
 * [onLongPress] 非空时改用 combinedClickable：框架保证「长按」与「单击」互斥，
 * 长按后松手不会又触发一次单击（用户 2026-09-28 的对勾按钮就需要这个语义）。
 */
fun Modifier.vPressable(
    enabled: Boolean = true,
    scaleDown: Float = 0.97f,
    /**
     * 长按回调，**必须排在 onClick 之前**：
     * onClick 是最后一个参数，各调用点才能继续用尾随 lambda 写法
     *（`vPressable { ... }`）。参数加在 onClick 之后会把尾随 lambda 抢走。
     */
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "vPress",
    )
    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .then(
            if (onLongPress == null) {
                Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
            } else {
                Modifier.combinedClickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongPress,
                )
            },
        )
}
