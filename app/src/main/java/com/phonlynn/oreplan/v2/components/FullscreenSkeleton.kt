package com.phonlynn.oreplan.v2.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * 全屏两页（**展示页** `BoardFocusOverlay` 的 fullscreen 形态 ↔ **编辑页**
 * `BoardCardFullScreenTextEdit`）共用的骨架几何。
 *
 * ## 为什么需要这个文件
 *
 * 这两页历史上因**外观分叉**返工三次。根因不是谁改错了数字，而是
 * **同一件事在两个文件里各写了一遍**：只要改一处、忘另一处，两页就悄悄不一致，
 * 而"看代码觉得一样"根本靠不住 —— 项目自己在 `BoardCardScreenV2.kt` 里留过一条教训：
 *
 * > 两页都写着 `ContentHorizontalPadding`，看代码"一样"，但展示页容器另有一层 8dp、
 * > 本页没有 → 展示页实际 16dp、本页只有 8dp。
 * > 教训：**光看常量名相同不足以判定一致，必须把整条 padding 链加起来。**
 *
 * 所以把**重复的表达式**搬到这里：改一处就是改两页，物理上不可能只改一页。
 *
 * ## 使用纪律
 *
 * 1. 往这里加东西的前提是**两页当前真的用同一个表达式**（先逐层加总验证，别靠印象）。
 * 2. **两页有意不同的部分不要搬进来** —— 例如编辑页特有的 ✓ 按钮、键盘工具栏、
 *    可编辑字段的最小高度。硬凑成共用件会逼出"带默认值的特殊参数"，反而更难懂。
 * 3. 改这里等于同时改两页 ⇒ 必须跑 `scripts/uicheck.py` 的像素比对
 *    （见 `.context/fullscreen-two-page-contract.md`）。
 */

/**
 * 全屏页**滚动内容**的顶部内衬：让滚到顶时标题正好落在悬浮信息栏下沿。
 *
 * 推导（记 `S` = 状态栏高，`H` = 信息栏实测高）：
 * ```
 * 可见栏底（相对内容层原点） = H − CardInfoBarPullOut − S
 * 标题顶边（屏幕坐标）       = S + 上面这一项 + CardInfoBarToTitleGap = H + 4dp
 * ```
 * ⇒ 两页都等于「栏高 + 4dp」，且随栏的实测高度自适应。
 *
 * ⚠️ 必须加在 `verticalScroll` **之后**（= 属于滚动内容）：
 * 滚到顶时标题正好在栏下方，继续滚则文字从**栏下面穿过**（没有截断线）。
 * 加在滚动之前会变成固定留白，滚动时文字会被栏切断。
 *
 * @param infoBarHeightPx 信息栏**实测**高度（由 `onGloballyPositioned` 写入的像素值）。
 *   首次测量前是调用方的估算种子值。
 */
@Composable
fun fullscreenScrollTopPadding(infoBarHeightPx: Float): Dp {
    val density = LocalDensity.current
    return with(density) {
        // 内容层的原点在状态栏**下沿**，而信息栏的度量是屏幕坐标 —— 所以要减掉 S 才能对齐。
        val statusBarTopPx = WindowInsets.statusBars.getTop(this).toFloat()
        (infoBarHeightPx - CardInfoBarPullOut.toPx() - statusBarTopPx)
            .coerceAtLeast(0f)
            .toDp() + CardInfoBarToTitleGap
    }
}
