package com.phonlynn.oreplan.v2.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.theme.VColors

/**
 * 转圈加载指示器（一段 280° 的圆弧匀速旋转）。
 *
 * ## ⚠️ 这是「禁止 LinearEasing」的一条**限定例外**，理由如下
 *
 * `VMotion` 顶部写的是：
 *
 * > 全部非线性；位移与淡入用快起慢收贝塞尔，连续状态用带阻尼弹簧。禁止 LinearEasing。
 *
 * 那条规则的**适用范围是过渡**（位移、淡入、状态变化）—— 它的目的是让界面动作
 * 看起来自然、不像机械。而本组件是**周期性进度指示**，不是过渡：
 *
 * · 匀速旋转是"还在进行"的通用视觉语言，用户对它的期待就是**恒定的角速度**
 * · 换成任何缓动（包括最接近线性的 `Glide`），圆弧都会在同一角度反复减速，
 *   看起来像**每圈卡一下** —— 那不是"精致"，那是像坏了
 *
 * 所以这里只对**不确定进度指示器**使用线性缓动。其余的动画一律走 `VMotion`，
 * 不要因为看到这一处就以为线性缓动在本项目里解禁了。
 *
 * @param size 组件边长（工具卡里用 14.dp，和旁边的状态图标同尺寸）
 * @param sweep 圆弧张角。360 会看起来像静止的圈，所以留缺口
 * @param periodMillis 转一圈的时长
 */
@Composable
fun VSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = VColors.ink3,
    strokeWidth: Dp = 1.6.dp,
    sweep: Float = 280f,
    periodMillis: Int = 900,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinner-angle",
    )

    Canvas(modifier.size(size).rotate(angle)) {
        val stroke = strokeWidth.toPx()
        // 描边是**居中**画在路径上的，所以要往里缩半个线宽，否则会被裁掉一半
        val inset = stroke / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
