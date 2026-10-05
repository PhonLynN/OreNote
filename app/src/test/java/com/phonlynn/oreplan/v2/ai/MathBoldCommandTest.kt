package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.v2.ai.math.MathNode
import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **加粗类命令的花括号里要按数学解析**（用户 2026-10-04 报的 `\tau` 缺陷）。
 *
 * ## 症状
 *
 * 真机截图里这一行：
 *
 * ```
 * ∂(ρu)/∂t + ∇·(ρu ⊗ u) = −∇p + ∇·\tau + ρf      ← `\tau` 带反斜杠露出
 * ```
 *
 * 用户原话：「很奇怪，我不清楚为什么出现 `\tau`」。
 * 关键线索：**同一行里 `\nabla` `\rho` `\otimes` 全都正常**，只有 `\tau` 露出。
 *
 * ## 根因
 *
 * 模型原文用的是 `\boldsymbol{\tau}` —— **不是** `\tau`。
 * 而解析器里这两类命令共用了同一个取内容函数：
 *
 * | 命令 | 花括号内容是 | 应该用 |
 * |---|---|---|
 * | `\text{...}` | **文本** | `readGroupRawText()` 原样取 |
 * | `\boldsymbol{...}` / `\mathbf{...}` | **数学内容** | `parseRequiredGroup()` 按数学解析 |
 *
 * 共用之后，`\boldsymbol{\tau}` 的 `\tau` 被当**字面文本**输出，
 * 于是反斜杠原样画到屏幕上。
 *
 * ## 判据
 *
 * 加粗命令的**渲染结果里不能出现反斜杠** —— 那是"命令没被解析"的直接指纹。
 */
class MathBoldCommandTest {

    private fun math(src: String) = MathParser.parse(src)?.flatten()

    // ---------------------------------------------------------------- 核心

    /**
     * ⚠️ **`\boldsymbol{\tau}` 必须解析成 `τ`**，不能是 `\tau`。
     *
     * 这是用户报的那个缺陷的直接用例。
     */
    @Test
    fun `boldsymbol 里的命令要解析`() {
        assertEquals("τ", math("""\boldsymbol{\tau}"""))
        assertEquals("σ", math("""\boldsymbol{\sigma}"""))
        assertEquals("α", math("""\boldsymbol{\alpha}"""))
    }

    /** `\mathbf{...}` 同理（它和 `\boldsymbol` 是一类）。 */
    @Test
    fun `mathbf 里的命令要解析`() {
        assertEquals("τ", math("""\mathbf{\tau}"""))
        assertEquals("∇", math("""\mathbf{\nabla}"""))
    }

    /** 花括号里是普通字母时行为不变（最基础的护栏）。 */
    @Test
    fun `加粗普通字母不受影响`() {
        assertEquals("u", math("""\mathbf{u}"""))
        assertEquals("uf", math("""\mathbf{uf}"""))
    }

    /** 用户原文那两行整句 —— 渲染结果里**一个反斜杠都不能有**。 */
    @Test
    fun `用户原文的整行公式不残留反斜杠`() {
        val momentum = """\frac{\partial (\rho\mathbf{u})}{\partial t} + \nabla\cdot(\rho\mathbf{u}\otimes\mathbf{u}) = -\nabla p + \nabla\cdot\boldsymbol{\tau} + \rho\mathbf{f}"""
        val energy = """\frac{\partial (\rho E)}{\partial t} + \nabla\cdot[(\rho E + p)\mathbf{u}] = \nabla\cdot(\boldsymbol{\tau}\cdot\mathbf{u}) + \nabla\cdot(k\nabla T) + \rho\mathbf{f}\cdot\mathbf{u}"""

        listOf("动量方程" to momentum, "能量方程" to energy).forEach { (name, src) ->
            val out = math(src)
            assertTrue("$name 解析失败", out != null)
            assertFalse(
                "$name 残留反斜杠（说明有命令没被解析）：$out",
                out!!.contains('\\'),
            )
            // 关键字形要在（内容不能丢）
            assertTrue("$name 丢了 τ：$out", out.contains('τ'))
            assertTrue("$name 丢了 ∇：$out", out.contains('∇'))
        }
    }

    // ---------------------------------------------------------------- 加粗标记

    /** ⚠️ 加粗要**保留**：解析出来必须是 bold，否则 `\boldsymbol{\tau}` 和 `\tau` 没区别。 */
    @Test
    fun `boldsymbol 的结果带粗体标记`() {
        val tree = MathParser.parse("""\boldsymbol{\tau}""")
        val sym = firstSymbol(tree)
        assertTrue("`\\boldsymbol{\\tau}` 应当是粗体", sym?.bold == true)
    }

    /**
     * ⚠️ 希腊字母的**斜体标记要保留**。
     *
     * `\tau` 在数学里是变量（斜体），`\boldsymbol` 只是让它变粗，
     * **不该把斜体冲掉** —— 否则 τ 会变成直立的，和 TeX 的 `\boldsymbol{\tau}` 不一致。
     */
    @Test
    fun `boldsymbol 不冲掉斜体`() {
        val sym = firstSymbol(MathParser.parse("""\boldsymbol{\tau}"""))
        assertTrue("τ 在数学里是变量，应当保持斜体", sym?.italic == true)
        assertTrue("应当是粗体", sym?.bold == true)
    }

    /** `\text{}` 仍然按**原样文本**取 —— 不能把这条一起改坏。 */
    @Test
    fun `text 里的内容仍然原样保留`() {
        // `\text{N-S方程}` 里的连字符是文本，不该被当成减号
        assertEquals("N-S方程", math("""\text{N-S方程}"""))
        // `\text{}` 里出现反斜杠时按字面（它是文本，不是命令）
        assertEquals("""\alpha""", math("""\text{\alpha}"""))
    }

    /** 逐字符找出树里第一个 Sym（用于断言 bold/italic 标志）。 */
    private fun firstSymbol(node: MathNode?): MathNode.Sym? = when (node) {
        is MathNode.Sym -> node
        is MathNode.Row -> node.children.firstNotNullOfOrNull { firstSymbol(it) }
        else -> null
    }
}
