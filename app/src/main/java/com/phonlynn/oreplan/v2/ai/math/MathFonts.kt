package com.phonlynn.oreplan.v2.ai.math

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.phonlynn.oreplan.R

/**
 * 公式专用字体：**Latin Modern Math**。
 *
 * ## 为什么要打包字体（用户：「字体感觉完全不对，你肯定没有用对公式字体」）
 *
 * 数学排版有一整套专门的字形 —— 系统衬线体（`FontFamily.Serif`）的
 * `ρ`、`∂`、`μ`、斜体字母全都不是数学形状：
 *
 * · 数学里的**变量是斜体**，而且那种斜体是单独设计的（TeX 的 `cmmi`），
 *   不是把正体倾斜一下
 * · `∂`（偏导）、`∑`、`∫`、`∇` 这些符号有一致的笔画粗细与对齐基线
 * · 分数、根号、上下标要和字身的尺寸阶梯匹配
 *
 * Latin Modern Math 就是 TeX 的 Computer Modern 数学字体（OpenType 版），
 * 是 LaTeX 默认渲染效果的本体。
 *
 * ## 代价与许可
 *
 * · 体积 **+716KB**（APK 从 4.8MB 到约 5.5MB）—— 用户明确要求，已确认
 * · 许可是 **GUST Font License**（自由分发，允许打包进应用），
 *   全文见 `docs/字体-LatinModernMath-许可.txt`
 * · 来源：CTAN `fonts/lm-math/opentype/latinmodern-math.otf`
 *
 * ## ⚠️ 不靠"斜体样式"，靠"码位"
 *
 * 用 `FontStyle.Italic` 让这个字体倾斜是**错的** —— 它是单字重单姿态字体，
 * Android 只能合成倾斜（假斜体），字形会明显变形。
 *
 * 正确的做法是 Unicode 的**数学字母区**（U+1D400 起）：
 * 数学斜体 `𝑎` 是 U+1D44E 这个**独立码位**，数学粗体 `𝐚` 是 U+1D41A。
 * 本字体包含整个区段（已用 fontTools 逐个核对过），
 * 所以只要把字符映射到对应码位，拿到的就是**真正的数学字形**。
 * 映射见 [toMathAlphanumeric]。
 */
val MathFontFamily: FontFamily = FontFamily(Font(R.font.latinmodern_math))

/**
 * 把普通字符映射到 **Unicode 数学字母**（U+1D400 区）。
 *
 * ## 为什么必须这样做
 *
 * TeX 里 `x`（变量，斜体 `𝑥`）和 `\mathrm{x}`（正体 `x`）是**不同字形**；
 * `\mathbf{u}`（粗体 `𝐮`）也是独立字形。这些在 Unicode 里都有码位，
 * 数学字体也都包含 —— 用码位选中它们，比靠 fontStyle/fontWeight 让系统
 * 去"合成"要准确得多。
 *
 * 映射规则（偏移量都是固定的，字母区是连续的）：
 *
 * | 目标 | 码位 |
 * |---|---|
 * | 数学斜体 a–z / A–Z | U+1D44E / U+1D434 |
 * | 数学粗体 a–z / A–Z | U+1D41A / U+1D400 |
 * | 数学粗斜体 a–z / A–Z | U+1D482 / U+1D468 |
 * | 数学斜体 α–ω | U+1D6FC |
 * | 数学粗体 α–ω | U+1D6C2 |
 *
 * 映射不到的一律**原样保留**（数字、运算符、`∇`、`∂`、`∑` 本来就是正体）。
 */
internal fun toMathAlphanumeric(text: String, italic: Boolean, bold: Boolean): String {
    if (!italic && !bold) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        out.appendCodePoint(mapCodePoint(cp, italic, bold))
    }
    return out.toString()
}

private fun mapCodePoint(cp: Int, italic: Boolean, bold: Boolean): Int = when {
    cp in 'A'.code..'Z'.code -> when {
        bold && italic -> 0x1D468 + (cp - 'A'.code)
        bold -> 0x1D400 + (cp - 'A'.code)
        italic -> 0x1D434 + (cp - 'A'.code)
        else -> cp
    }

    cp in 'a'.code..'z'.code -> when {
        bold && italic -> 0x1D482 + (cp - 'a'.code)
        bold -> 0x1D41A + (cp - 'a'.code)
        italic -> 0x1D44E + (cp - 'a'.code)
        else -> cp
    }

    // 小写希腊字母（α–ω）：数学里是变量，斜体
    cp in 0x03B1..0x03C9 -> when {
        bold && italic -> 0x1D736 + (cp - 0x03B1)
        bold -> 0x1D6C2 + (cp - 0x03B1)
        italic -> 0x1D6FC + (cp - 0x03B1)
        else -> cp
    }

    // 大写希腊字母（Α–Ω）：TeX 里保持正体，只在 \mathbf 时变粗
    cp in 0x0391..0x03A9 && bold -> 0x1D6A8 + (cp - 0x0391)

    else -> cp
}
