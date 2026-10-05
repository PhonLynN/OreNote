package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.theme.NumFont

/**
 * AI 三页的字体定义。
 *
 * ## ⚠️ 为什么不复用 `VTypo`
 *
 * 项目的 `VTypo.body` 是 **13sp** —— 那是给笔记类页面用的（信息密度优先）。
 * 而设计稿里 AI 对话页的正文是 **15sp**、副文本 13sp。
 *
 * 我第一版直接套了 `VTypo.body`，结果手机上**字明显偏小**（用户反馈）。
 * 这正是「语义相近但数值不同的组件不该硬套」的又一个例子 ——
 * 与顶栏那次（VNavBar 48 vs 56）是同一类错误。
 *
 * ## ⚠️ 正文只比设计稿大**一档**（用户第三次反馈才定下来）
 *
 * 按设计稿的 15 / 13 落地后用户觉得小，我一次加到了 17 —— **加过头了**
 *（用户：「字体又太大了，我就让你比原来增大一点点点」）。
 * 最终只各加 1sp：
 *
 * ```
 * 正文（用户与 AI 消息）  15 → 16
 * 推理内容（思考过程）    13 → 14
 * ```
 *
 * 行高也跟着松了两次（用户：「每一行文字之间的行距再增大一点点就好，就一点点」→
 * 又一次「行距还是太小了」）：
 *
 * ```
 * 正文 1.45 → 1.50 → 1.60
 * 气泡 1.35 → 1.40 → 1.50
 * 推理 1.40 → 1.45 → 1.55
 * ```
 *
 * 1.60 对中文正文是常见的舒适档（中文笔画密，比拉丁文需要更大行距）。
 * **再要松/紧就继续按 0.05–0.10 的步长挪。**
 *
 * ⚠️ **只动"对话窗口里的消息字符"**。其余一律保持设计稿原值 ——
 * 我上一版顺手把「已思考」标签和**输入框**也放大了，用户明确说
 *「输入框里的字体大小本来是合理的」。**不要再动它们。**
 *
 * ## 数值来源
 *
 * 除上面两条外，全部是设计稿的 `fontSize` 属性实测：
 *
 * | 用途 | 设计稿 |
 * | --- | --- |
 * | 顶栏标题 | 18 / 600 / lineHeight 1 |
 * | 正文（用户与 AI 消息） | 15 / normal / lineHeight 1.45 |
 * | 用户气泡内文字 | 15 / normal / **lineHeight 1.35** |
 * | 「已思考」标签 | 13 / normal |
 * | 推理内容 | 13 / normal / lineHeight 1.4 |
 * | 模式分段标签 | 12 / 600（选中）/ normal（未选） |
 * | 「深度思考」chip | 13 / normal |
 * | 输入框占位 | 15 / normal |
 *
 * 注意**用户气泡的行高（1.35）与 AI 正文（1.45）不同** ——
 * 设计稿确实这么定的，不是笔误。
 */
object AiTypo {

    /** 顶栏标题：18/600，行高 1（单行标题不需要额外行距）。 */
    val headerTitle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = BodyFont,
        lineHeight = 18.sp,
    )

    /** AI 正文：16/400，行高 **1.60**（设计稿 15 / 1.45）。 */
    val body = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
        lineHeight = 25.6.sp, // 16 * 1.60
    )

    /** 用户气泡内文字：16/400，行高 **1.50**（比 AI 正文紧一点；设计稿 15 / 1.35）。 */
    val bubbleBody = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
        lineHeight = 24.sp, // 16 * 1.50
    )

    /** 「已思考（用时 N 秒）」/「已生成」标签：13/400（**设计稿原值，未改**）。 */
    val thinkingLabel = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )

    /**
     * 轮次切换文字（设计稿 `Round Text`）：13/500，**数字字体**。
     *
     * 用 `NumFont` 是设计稿指定的（`$font-num`）—— 数字等宽，
     * 从 `1 / 2` 翻到 `2 / 2` 时文字宽度不跳。
     */
    val roundText = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = NumFont,
    )

    /** 推理内容：14/400，行高 **1.55**（设计稿 13 / 1.4）。 */
    val reasoning = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
        lineHeight = 21.7.sp, // 14 * 1.55
    )

    /** 分段标签选中态：12/600。 */
    val segSelected = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = BodyFont,
    )

    /** 分段标签未选中：12/400。 */
    val segNormal = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )

    /** 「深度思考」chip：13/400。 */
    val chip = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )

    /**
     * 输入框占位与正文：15/400，行高 **1.45**。
     *
     * ## 字号**保持设计稿原值**
     *
     * 用户说过「输入框里的字体大小本来是合理的」—— 所以 15sp 不动。
     * 我上一版顺手把它一起放大了，是越权。
     *
     * ## ⚠️ 但**行距**原来根本没设（用户反馈「悬浮输入框内行距过小」）
     *
     * 这个 token 原本只有 `fontSize` —— 没写 `lineHeight` 时 Compose 用
     * 默认值（约 **1.17 倍** ≈ 17.6dp）。而对话正文是 1.60 倍（24dp）：
     *
     * ```
     * 正文       24.0dp  ← 行与行
     * 输入框     17.6dp  ← 只有正文的七成，多行时挤成一片
     * ```
     *
     * 输入框里写的常常是**两三行的长要求**（"帮我把这周的复习排一下，
     * 优先数学，晚上别排"），行距太小读起来很累 —— 而且和旁边正文的
     * 疏密差得太远，视觉上像两个不同步的界面。
     *
     * ## 取 1.45 而不是正文的 1.60
     *
     * 输入框的高度是**有限**的（悬浮在键盘上方，最多几行就滚动）。
     * 完全对齐正文会白白吃掉可输入的行数。1.45 是折中：
     * 明显松开（17.6 → 21.75dp），但仍然比正文紧凑一档。
     */
    val input = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
        lineHeight = 21.75.sp, // 15 * 1.45
    )

    /**
     * 对话列表的**行标题**：15 / **500**。
     *
     * ⚠️ 这里踩过一次：`AiTypo` 里早就定义好了这个 token，
     * 但对话列表那一页写的时候套了全局的 `VTypo.body`（**13sp**），
     * 于是整页字都偏小两档 —— 用户反馈「对话列表内字体大小太小了」。
     * 与本文档开头记的顶栏那次是同一类错误：**语义相近但数值不同的组件不该硬套**。
     *
     * 字重 500 来自设计稿 `IbAWs` 的 `Item Title`（`fs15 w500`）。
     * 本文档早先写的是 400，那是旧稿的值。
     */
    val listItem = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = BodyFont,
    )

    /** 对话列表的**分组标题**：13 / **600**（设计稿 `IbAWs` 的 `Group Title`，`fs13 w600`）。 */
    val listGroup = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = BodyFont,
    )

    /** 对话列表的**搜索框**文字与占位：14 / 400（设计稿 `Search Placeholder`，`fs14`）。 */
    val searchPlaceholder = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )

    /** 设置页行标签：15/400。 */
    val settingLabel = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )

    /** 设置页行值：13/400。 */
    val settingValue = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )

    /** 设置页大标题：20/700。 */
    val screenTitle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = BodyFont,
    )

    /** 设置页 section 标题：15/500。 */
    val section = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = BodyFont,
    )

    /** 数值（参数值等）用等宽字体族，保证 0.7 / 2048 这类数字对齐。 */
    val number = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = NumFont,
    )

    /** 提示词组名：14/400（设计稿 Group Name）。 */
    val promptGroupName = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )

    /** 提示词正文编辑框：14/400，lineHeight 1.5（多行文本需要更松的行距）。 */
    val promptBody = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
        lineHeight = 21.sp,
    )

    /** 「添加提示词组」：13/400。 */
    val promptAddLabel = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = BodyFont,
    )
}
