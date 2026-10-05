package com.phonlynn.oreplan.v2.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **全屏卡片页的布局参数唯一来源** —— 全屏展示页与全屏编辑页共用。
 *
 * ## 为什么需要它
 *
 * 用户 2026-09-28 明确（原话）：
 * > 「全屏的展示与编辑页面不能只改一个，要改就要一起改，绝对不能漏下某一个。」
 *
 * 而实际情况是两页各写了一份数值，于是同一排元素在两页上不一致：
 *
 * | 项目 | 展示页（原） | 编辑页（原） |
 * | --- | --- | --- |
 * | 信息栏左右内衬 | 24dp | 12dp |
 * | 信息栏上缘偏移 | `-PullOut` | `-PullOut − 状态栏高` |
 * | 内容层顶部内衬 | 4dp | 0 |
 * | 非图片附件行高 | 36dp | 40dp |
 * | 非图片附件行左右内衬 | 0 | 14dp |
 *
 * 根因是「值写在两处」而不是「值调得不对」—— 只要有两份，
 * 下一次改动依然会只改一边。所以这里把它们收敛成一份常量：
 * **两页都读这里，改一个数字两页同时生效，物理上不可能再分叉。**
 *
 * ## 口径：以**展示页**为准
 *
 * 用户 2026-09-28 明确「完全依据展示页的 ui 布局」。这里是展示页那一套值。
 * 注意 `InfoBarHorizontalPadding` 也因此把展示页的 24dp **原样保留**过来
 *（编辑页从 12dp 提到 24dp，展示页不变）。
 *
 * 与它配套的 [CardInfoBarPullOut]（信息栏上缘拉出屏幕的量）在 `VCardInfoBar.kt`，
 * 同为两页共用；本文件只放「内衬 / 间距」这类**页面骨架**参数。
 */
internal object FullscreenCardLayout {

    /**
     * 全屏页的左右基础边距（容器级别）。
     *
     * 两页相同：正文、图片区、滚动内容都以它作左右内衬。
     * 用户 2026-09-19 定为 8dp（原 24dp 太宽、空间利用率低）。
     */
    val HorizontalPadding: Dp = 8.dp

    /**
     * 悬浮信息栏的**额外**左右内衬（叠加在 [HorizontalPadding] 之上）。
     *
     * 展示页原值 16dp → 信息栏左右合计 **24dp**，即信息栏边缘与
     * 卡片内容区边缘对齐（用户 2026-09-28：「不要放大」）。
     * 编辑页原先各自写成 12dp（合计 20dp），现取展示页值，两页同一竖线。
     */
    val InfoBarExtraHorizontalPadding: Dp = 16.dp

    /** 信息栏左右合计内衬（= [HorizontalPadding] + [InfoBarExtraHorizontalPadding]）。 */
    val InfoBarHorizontalPadding: Dp = HorizontalPadding + InfoBarExtraHorizontalPadding

    /**
     * 信息栏底边与内容之间的呼吸间距。
     *
     * ⚠️ 这是**信息栏自身**的下外衬，与「内容顶部内衬」是两件事：
     * 内容内衬由实测栏高算出（见两页的 `top = 栏高 − 拉出量 − 状态栏高`），
     * 它保证第一行文字正好落在栏下方；本值只是视觉呼吸。
     */
    val InfoBarBottomPadding: Dp = 4.dp

    /**
     * 内容层（滚动区）的左右内衬 —— 两页正文宽度必须一致，否则双击定位会错行。
     *
     * ⚠️ **这是内层那一档，不是文本的最终位置**。文本距屏幕边缘的距离 = 
     * [HorizontalPadding]（容器层）+ [ContentHorizontalPadding]（本层），
     * 即 [TextHorizontalPadding]。
     */
    val ContentHorizontalPadding: Dp = HorizontalPadding

    /**
     * **文本（标题 / 正文 / 图片区）距屏幕左右边缘的最终内衬。**
     *
     * = 容器层 [HorizontalPadding] + 内容层 [ContentHorizontalPadding]。
     *
     * 2026-09-28 晚的教训（用户报「看起来可完全不一样」）：
     * 当时两页都写着 `ContentHorizontalPadding`，看代码"一样"，
     * 但展示页的**容器**另有一层 8dp，编辑页没有 → 展示页实际 16dp、编辑页 8dp。
     * **光看常量名相同不足以判定一致，必须把整条 padding 链加起来。**
     * 现在两页都用这个常量校核：编辑页的内容层补上了容器层那一档。
     */
    val TextHorizontalPadding: Dp = HorizontalPadding + ContentHorizontalPadding

    /**
     * 内容层顶部内衬（**额外**补的一块）。
     *
     * 展示页原值 4dp；编辑页原先没有这一块（0）。取展示页值后两页一致。
     * 真正的顶部大内衬是「栏高 − 拉出量 − 状态栏高」，见各自的滚动区。
     */
    val ContentTopPadding: Dp = 4.dp

    /** 内容层底部内衬（容器级别）。两页同值，只是原先写在不同的节点上。 */
    val ContentBottomPadding: Dp = 8.dp

    /** 滚动区内各内容块之间的纵向间距（标题 / 正文 / 图片 / 附件）。 */
    val ContentSpacing: Dp = 12.dp

    // ---------------------------------------------------------------- 底部空白（展示页 / 编辑页共用）
    //
    // 用户 2026-09-30（#18 的定稿口径）：
    // > 给详情页也加入编辑页那么大的底部空白，不用只加一行。

    /** 编辑页右下那颗 ✓ 按钮的直径。 */
    val DoneButtonSize: Dp = 52.dp

    /** ✓ 按钮与内容末尾之间留的间隙。 */
    val DoneButtonGap: Dp = 12.dp

    /** ✓ 按钮在**键盘收起**时距屏幕底部的位置。键盘弹出时会动画到更大值。 */
    val DoneButtonFollowBottomIdle: Dp = 12.dp

    /**
     * **滚动内容末尾的呼吸空白** —— 两页共用，值取编辑页的那一套。
     *
     * ## 为什么是「编辑页那么大」
     *
     * 编辑页底部有一颗悬浮的 ✓ 按钮，所以它的滚动内容必须留出一块尾部余量
     * （`followBottom + 按钮直径 + 间隙`）—— 否则最后一行会被按钮压住。
     * 展示页没有按钮，于是它原先只留 [ContentBottomPadding]（8dp），
     * 长文滚到底时最后一行几乎贴着屏幕下缘，读起来很挤（用户 #18 的原话是"阅读舒适"）。
     *
     * 用户定稿：**展示页也用编辑页那么大的底部空白**，两页观感一致。
     *
     * 这里用**同一组常量推导**而不是直接写 76dp：编辑页的 `doneButtonArea`
     * 也改成读这三个值，于是"两页一样多"是**算出来的**，不会因为谁改了按钮尺寸而悄悄分叉。
     */
    val ContentBottomBlank: Dp = DoneButtonFollowBottomIdle + DoneButtonSize + DoneButtonGap

    /**
     * 非图片附件行（图标 + 文件名 …）。
     *
     * 展示页原值：行高 36dp、**无**左右内衬、文件名用 ink2。
     * 编辑页原先 40dp / 左右 14dp / ink（深一档），已全部取展示页值。
     *
     * 为什么不给左右内衬：附件行与正文、图片区同处滚动区内，
     * 内衬由滚动区统一给（[ContentHorizontalPadding]），行自己再加会缩进一格。
     */
    // 用户 2026-09-29：文件附件行「间隔过大」-> 行高 36dp 收窄约 20% 到 29dp。
    // （行与行之间只有分割线、无额外 gap，所以「间隔」= 行高本身。）
    // 两页共用，改此值展示页与编辑页同时生效。
    val AttachmentRowHeight: Dp = 29.dp

    /** 滚动区与 [ContentHorizontalPadding] 之外，附件行**不再**额外加左右内衬。 */
    val AttachmentRowExtraHorizontalPadding: Dp = 0.dp
}
