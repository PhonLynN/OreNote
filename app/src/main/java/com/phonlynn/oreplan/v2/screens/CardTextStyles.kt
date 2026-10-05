package com.phonlynn.oreplan.v2.screens

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.theme.VTypo

/**
 * **卡片文本样式的唯一来源**（2026-09-22 新建）。
 *
 * ## 为什么需要它
 *
 * 排版要完全一致，前提是**两处传入的 [TextStyle] 逐字段相同**
 *（字号、行高、字体族、字重都要一样）。这里的字段任何一个不同，
 * 排版引擎就会算出不同的行高与断行位置——这正是「编辑端与展示端不一样」的直接原因。
 *
 * 而改动前，同一张卡的正文行高在四个地方各写一个值：
 *
 *  | 位置 | 旧值 |
 *  | --- | --- |
 *  | 主页卡片（`BoardUi`） | `bodySize * 1.4f` |
 *  | 全屏展示（`BoardFocusOverlay`） | `bodySize * 1.5f` |
 *  | 全屏文本编辑（`BoardCardScreenV2`） | `bodySize * 1.5f` |
 *  | 卡片内文编辑（`BoardCardScreenV2`） | 硬编码 `21.sp` |
 *
 * 四处四个值，无论怎么调都凑不出「两端一致」。
 * 现在收敛到本文件，**所有渲染点只能从这里取样式**。
 *
 * ## 用法
 *
 * ```kotlin
 * val s = CardTextStyles.of(cardFontSizes)
 * VRichText(body, s.body, ...)          // 展示端
 * VRichTextField(..., textStyle = s.body) // 编辑端
 * ```
 *
 * 两端传同一个 `s.body`（同一个对象、同一份字段）→ 排版结果必然相同。
 */
object CardTextStyles {

    /**
     * 正文行高倍数 —— **全项目唯一定义**。
     *
     * 取 1.5：中文正文在 15sp 左右时，1.5 倍行高的可读性最好，
     * 也与项目里其余长文本区域保持一致。
     *
     * 注意：这个倍数同时用于编辑端与展示端，不要再在调用处乘别的系数。
     *
     * 🎛 **觉得列表行距太松 / 太紧就改这个值**。
     *（列表符号的尺寸与位置不在本文件，在 `core/rt/RichLayout.kt`。）
     */
    const val BODY_LINE_HEIGHT_MULTIPLIER = 1.5f

    /** 标题行高倍数。标题只有一两行，不需要正文那么松。 */
    const val TITLE_LINE_HEIGHT_MULTIPLIER = 1.3f

    /**
     * **全屏页**（全屏展示 + 全屏编辑/新建）的正文行高倍数。
     *
     * 用户 2026-09-28：全屏阅读场景下行距偏挤，单独放大一档。
     * 卡片形态仍是 [BODY_LINE_HEIGHT_MULTIPLIER]（卡片上空间小，行距跟着放大反而更挤）。
     *
     * 全屏的展示页与编辑页**必须都取这个值** —— 两页行高不一致会让同一段文字
     * 行数与折行位置不同，双击定位、阅读位置都会错位。
     */
    const val FULLSCREEN_BODY_LINE_HEIGHT_MULTIPLIER = 1.7f

    /** 一档卡片字号下，标题与正文的完整样式。 */
    data class Pair(
        val title: TextStyle,
        val body: TextStyle,
    )

    /**
     * 由字号档得出标题/正文样式。
     *
     * 这是**唯一的派生入口**：编辑端、展示端、主页卡片、归档页全部走它。
     */
    fun of(fontSizes: CardFontSizes): Pair = Pair(
        title = titleStyle(fontSizes.title),
        body = bodyStyle(fontSizes.body),
    )

    /** 标题样式。 */
    fun titleStyle(size: TextUnit): TextStyle = VTypo.section.copy(
        fontSize = size,
        lineHeight = size * TITLE_LINE_HEIGHT_MULTIPLIER,
        color = com.phonlynn.oreplan.v2.theme.VColors.ink,
        fontFamily = BodyFont,
    )

    /**
     * 正文字体样式 —— 编辑端与展示端**必须**都传它。
     *
     * 不在这里指定颜色：正文颜色在编辑态与展示态确实需要不同
     *（编辑态用 `ink`、卡片展示用 `cardBody`），而**颜色不影响排版**
     *（不影响行高与断行），所以允许调用方覆盖。字号/行高/字体族则必须锁死。
     */
    fun bodyStyle(
        size: TextUnit,
        lineHeightMultiplier: Float = BODY_LINE_HEIGHT_MULTIPLIER,
    ): TextStyle = VTypo.body.copy(
        fontSize = size,
        lineHeight = size * lineHeightMultiplier,
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
    )

    /** 全屏页（展示 + 编辑）正文样式：与 [bodyStyle] 同源，只换行距倍数。 */
    fun fullscreenBodyStyle(size: TextUnit): TextStyle =
        bodyStyle(size, FULLSCREEN_BODY_LINE_HEIGHT_MULTIPLIER)
}
