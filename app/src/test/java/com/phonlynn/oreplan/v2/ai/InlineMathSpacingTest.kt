package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **行内公式与后续标点之间的空隙**（用户 2026-10-04：「：之间的间距小的离谱」）。
 *
 * ## 实测症状（NS 方程那张列表，density 2.75）
 *
 * | 条目 | 公式 → `：` | `：` → 中文 |
 * |---|---|---|
 * | `ρ：密度` | **3.3dp** | 15.6dp |
 * | `u：速度场` | **4.7dp** | 15.3dp |
 * | `p：压强` | **2.9dp** | 17.1dp |
 *
 * 同一个冒号**左边 3.3dp、右边 15.6dp —— 差 4.7 倍**，看起来就是"挤在一起"。
 *
 * ## 根因
 *
 * 1. 公式被包了 `FontStyle.Italic`，斜体墨迹会探出字形框右缘（italic correction），
 *    而 `AnnotatedString` 不会自动补偿。
 * 2. 中文全角 `：` 自带左右各约 1/4 em 空白，所以右边天然宽。
 *
 * ## 修法与它的边界
 *
 * 在公式末尾补一个细空隙（U+2009），**只在后面确实还有内容时补**。
 *
 * ⚠️ 这一条测的是"公式后面补了空隙"，**不是**精确的 dp 值 ——
 * 真实的视觉间距只有真机能量准。断言放在"有没有补、补在哪"上，
 * 避免造出一个跑在 JVM 上、和真机无关的假精度。
 */
class InlineMathSpacingTest {

    private fun render(s: String) = renderInline(s, Color.Black, 12.sp).text

    /** ⚠️ 核心：`$\rho$：密度` 渲染后，公式与冒号之间要有空隙。 */
    @Test
    fun `公式与紧随的冒号之间要有空隙`() {
        val out = render("""- ${'$'}\rho${'$'}：密度""")

        val rhoIndex = out.indexOf('ρ')
        val colonIndex = out.indexOf('：')
        assertTrue("公式没渲染出来：$out", rhoIndex >= 0)
        assertTrue("冒号丢了：$out", colonIndex > rhoIndex)

        assertTrue(
            "公式与冒号之间没有空隙（就是用户报的「小的离谱」）：" +
                "渲染结果里 ρ 与 ： 相邻 —— <${out.replace("\u2009", "·")}>",
            colonIndex > rhoIndex + 1,
        )
    }

    /** 多种标点都要补：中文冒号、英文冒号、逗号、句号。 */
    @Test
    fun `公式后接各种标点都补空隙`() {
        listOf(
            """${'$'}\rho${'$'}：密度""" to '：',
            """${'$'}p${'$'}：压强""" to '：',
            """${'$'}\mu${'$'}：动力粘度""" to '：',
            """${'$'}x${'$'}, 然后是""" to ',',
            """${'$'}x${'$'}. 结束""" to '.',
        ).forEach { (src, punct) ->
            val out = render(src)
            val pi = out.indexOf(punct)
            assertTrue("标点「$punct」丢了：$out", pi > 0)
            assertTrue(
                "公式后接「$punct」时没补空隙：<${out.replace("\u2009", "·")}>",
                out[pi - 1] == '\u2009',
            )
        }
    }

    /**
     * ⚠️ **行尾的公式不补空隙**。
     *
     * 补了会多出一个尾随空白 —— 右对齐的表格单元格会因此对不齐。
     */
    @Test
    fun `行尾公式不补尾随空隙`() {
        val out = render("""其中 ${'$'}\mu${'$'}""")
        assertFalse(
            "行尾公式补了空隙，会产生尾随空白：<${out.replace("\u2009", "·")}>",
            out.endsWith("\u2009"),
        )
        assertTrue("内容丢了：$out", out.endsWith("μ"))
    }

    /** 多余的空白不能让标记漏出来。 */
    @Test
    fun `补空隙不会让标记漏出来`() {
        val out = render("""- ${'$'}\mathbf{u}${'$'}：速度场""")
        assertFalse("残留美元号：$out", out.contains('$'))
        assertFalse("残留反斜杠：$out", out.contains('\\'))
        assertTrue("内容丢了：$out", out.contains("速度场"))
    }

    /** 相邻两个公式之间也要有间隙，不能粘成一个。 */
    @Test
    fun `相邻公式之间有间隙`() {
        val out = render("""${'$'}\rho${'$'}${'$'}\mu${'$'}""")
        assertTrue(
            "两个公式粘在一起了：<${out.replace("\u2009", "·")}>",
            out.contains("\u2009"),
        )
    }
}
