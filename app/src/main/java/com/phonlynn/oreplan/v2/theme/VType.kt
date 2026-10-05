package com.phonlynn.oreplan.v2.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.R

/** Inter（数字专用，随包携带，保证与设计稿一致）。 */
val NumFont = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

/** 正文字体：跟随系统（Android 系统中文即 Noto Sans CJK，与设计稿 Noto Sans SC 一致）。 */
val BodyFont = FontFamily.Default

/**
 * 断词用的 locale —— **必须显式给出**（2026-09-28）。
 *
 * ## 为什么必须
 *
 * Compose 的「选词」不是它自己实现的：长按选词 / 双击选词 / 拖手柄调整选区，
 * 走的都是 `SelectionAdjustment.Word` → `TextLayoutResult.getWordBoundary(offset)`
 * → `TextLayout.getWordIterator()` → `WordIterator(text, 0, len, TextPaint.getTextLocale())`
 *（这条链是直接反编译 ui-text 1.12.1 / foundation 1.12.1 确认的）。
 *
 * 而 `TextPaint.getTextLocale()` 会不会被覆盖，取决于 `style.localeList`：
 * `setTextPaintMeasureState()` 里只有 `localeList != LocaleList.current` 才写 paint。
 * **`TextStyle` 默认的 localeList 是空列表** → 于是 paint 的 textLocale 被写成空
 * → ICU 的 BreakIterator 落到 root locale → **中文没有 CJ 词典分词**：
 * 一整段中文会被当成一个「词」，长按一下就把整段选掉，拖手柄时每一步都在跳。
 *
 * 系统自带记事本没有这个问题：它是 View 体系（EditText），用的是系统 locale。
 *
 * ## 为什么写 zh-CN 而不是 LocaleList.current
 *
 * 给 `LocaleList.current` 只是「不覆盖」，paint 就取设备 locale —— 在英文系统上
 * （包括我们自己的模拟器）中文分词照样失效。本项目中文优先，直接给 zh-CN：
 * 中文按词典分词；拉丁字母与数字的词边界与 locale 无关，不受影响。
 */
val VCjkLocaleList: LocaleList = LocaleList(Locale("zh-CN"))

/** 字阶样式的统一构造：所有 [VTypo] 都带上 [VCjkLocaleList]。 */
private fun vTypo(
    fontSize: TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily,
): TextStyle = TextStyle(
    fontSize = fontSize,
    fontWeight = fontWeight,
    fontFamily = fontFamily,
    localeList = VCjkLocaleList,
)

/**
 * V2 字阶 — 取自设计稿的 text style 统计。命名按用途。
 */
object VTypo {
    /** 页面大标题：25/700。 */
    val pageTitle = vTypo(fontSize = 25.sp, fontWeight = FontWeight.Bold, fontFamily = BodyFont)
    /** hero/详情标题：20/700。 */
    val hero = vTypo(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = BodyFont)
    /** 弹窗标题：17/600。 */
    val dialogTitle = vTypo(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFont)
    /** 导航栏标题：16/600。 */
    val navTitle = vTypo(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFont)
    /** 卡片标题（列表卡）：15/700。 */
    val cardTitle = vTypo(fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = BodyFont)
    /** section 标题：15/500。 */
    val section = vTypo(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = BodyFont)
    /** 正文：13/400。 */
    val body = vTypo(fontSize = 13.sp, fontWeight = FontWeight.Normal, fontFamily = BodyFont)
    /** 正文强调：13/500。 */
    val bodyMed = vTypo(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = BodyFont)
    /** 正文弱：12/400。 */
    val caption12 = vTypo(fontSize = 12.sp, fontWeight = FontWeight.Normal, fontFamily = BodyFont)
    /** 说明：11/400。 */
    val caption = vTypo(fontSize = 11.sp, fontWeight = FontWeight.Normal, fontFamily = BodyFont)
    /** 徽标：10/400。 */
    val micro = vTypo(fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = BodyFont)
    /** 按钮：14/500。 */
    val button = vTypo(fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = BodyFont)
    /** 按钮（重）：14/600。 */
    val buttonBold = vTypo(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFont)

    // 数字（Inter）
    val numBig = vTypo(fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)
    val numHero = vTypo(fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)
    val numValue = vTypo(fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)
    val numStat = vTypo(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)

    /**
     * 专注中页环形里的剩余时间：52/700（设计稿 `Remain` fs52）。
     *
     * 为什么单独一档而不是复用 [numRing]：正计时那个「42:15」是 64/700
     * （设计稿 `Elapsed` fs64），两者差一档 —— 环里有 244 的直径托着，
     * 没有环的大数字要更大才撑得住画面。
     */
    val numRing = vTypo(fontSize = 52.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)

    /** 正计时的大数字：64/700。 */
    val numDisplay = vTypo(fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)

    /** 专注完成页的结果数字：44/700。 */
    val numResult = vTypo(fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)

    val numPercent = vTypo(fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = NumFont)
    val numTime = vTypo(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = NumFont)
    val numChip = vTypo(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = NumFont)
    val numMini = vTypo(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = NumFont)
}

/**
 * 便捷文本：统一提供 maxLines / overflow / align 参数，减少样板。
 */
@Composable
fun VText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = VColors.ink,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
        textAlign = align,
    )
}
