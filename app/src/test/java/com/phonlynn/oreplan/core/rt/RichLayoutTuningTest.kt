package com.phonlynn.oreplan.core.rt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **排版参数自检**（2026-09-23）。
 *
 * 改 `RichLayout.kt` 里的参数后，这个测试会立刻告诉你有没有改错。
 * 比如把 `SymbolCenterOffsetEm` 改成正数（符号会压到文字上）、
 * 或把圆点直径改得比缩进区还大（会顶到正文），都会在这里失败。
 *
 * > 这不是「效果验收」（效果由用户看真机），而是**参数合法性检查**。
 */
class RichLayoutTuningTest {

    @Test
    fun `参数自检必须无告警`() {
        val problems = RichLayout.validate()
        assertTrue(
            "RichLayout 参数有问题：\n" + problems.joinToString("\n") { "  · $it" },
            problems.isEmpty(),
        )
    }

    @Test
    fun `缩进量是 0_5 的整数倍（缩进字符能拼出来）`() {
        // 缩进由「全角空格(1em) + 可选 EN SPACE(0.5em)」拼成，
        // 所以只支持 0.5 的整数倍；其它值会拼不出期望宽度。
        val em = RichLayout.IndentEm
        val frac = em - kotlin.math.floor(em)
        assertTrue(
            "IndentEm 应为 0.5 的整数倍（当前 $em），否则缩进宽度会与期望不符",
            kotlin.math.abs(frac) < 0.05f || kotlin.math.abs(frac - 0.5f) < 0.05f,
        )
    }

    @Test
    fun `缩进字符能拼出目标宽度`() {
        val chars = RichTextMark.indentChars()
        // 每个全角空格 1em，EN SPACE 0.5em
        val width = chars.sumOf {
            if (it == RichTextMark.IDEOGRAPHIC_SPACE) 1.0 else 0.5
        }
        assertEquals(
            "缩进字符总宽应等于 IndentEm（$chars）",
            RichLayout.IndentEm.toDouble(),
            width,
            0.001,
        )
    }

    @Test
    fun `圆点直径明显小于旧版字形尺寸`() {
        // 旧版用字形 ● 约 0.9em，用户反馈「太大」。
        // 这条防止有人把参数改回去。
        assertTrue(
            "圆点直径 ${RichLayout.BulletDiameterEm} 不应接近旧字形的 0.9em",
            RichLayout.BulletDiameterEm < 0.5f,
        )
    }
}
