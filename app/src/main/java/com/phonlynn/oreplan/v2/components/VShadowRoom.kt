package com.phonlynn.oreplan.v2.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 让「绘制区域」比「布局尺寸」大 [slack]（四边各留），以便 `shadow` 的羽化外阴影
 * 在 `graphicsLayer` 的离屏缓冲内不被裁掉；同时父容器看到的尺寸/位置完全不变。
 *
 * ## 参数
 * @param contentSize 内容（圆按钮）的真实尺寸，例如 56.dp。**必须显式给出** ——
 *   本 modifier 需要强制按 `contentSize + slack*2` 测量，再对外报告 `contentSize`。
 * @param slack 四边留白，应大于阴影扩散半径（12dp elevation → 18dp 足够）。
 *
 * ## 用法（顺序自外向内）
 * ```kotlin
 * Box(Modifier.vShadowRoom(56.dp, 18.dp)) {          // 对外 56dp
 *     Box(
 *         Modifier
 *             .graphicsLayer { alpha = ... }          // 缓冲 = 92dp（因此不裁阴影）
 *             .size(56.dp)                            // 圆本体
 *             .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
 *             .background(...)
 *     ) { ... }
 * }
 * ```
 * 要点：`vShadowRoom` 在最外层，负责「测量 92dp、报告 56dp、内容居中偏移 −slack」。
 */
fun Modifier.vShadowRoom(contentSize: Dp, slack: Dp = 18.dp): Modifier = composed {
    val density = LocalDensity.current
    val contentPx = with(density) { contentSize.roundToPx() }
    val slackPx = with(density) { slack.roundToPx() }
    val bigPx = contentPx + slackPx * 2
    this.layout { measurable, _ ->
        // 强制按大尺寸测量内容（内容自身是 contentSize，居中由外层摆放完成）。
        val placeable = measurable.measure(Constraints.fixed(bigPx, bigPx))
        // 对外只报告 contentSize：父容器的间距/对齐不受影响。
        layout(contentPx, contentPx) {
            placeable.place(-slackPx, -slackPx)
        }
    }
}
