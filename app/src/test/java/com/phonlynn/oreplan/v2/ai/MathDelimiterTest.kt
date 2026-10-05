package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.v2.ai.math.MathNode
import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `\left( … \right)` —— **可自动定高的括号**（用户 2026-10-04）。
 *
 * ## 用户原话
 *
 * > 「说到括号就引出了另一个问题，ds的括号适配了公式高度，
 * >   而不是一直使用一行高的小括号」
 *
 * ## 原来的行为
 *
 * `\left` / `\right` 被当成**噪音直接丢掉**，只把括号字符本身当普通符号吐出来 ——
 * 于是无论里面装多高的东西（分式、根号），括号永远是**一行高**，
 * 看起来像"括号被内容撑破了"。
 *
 * ## 现在的行为
 *
 * 解析成 [MathNode.Delimited]（左定界符 / 内容 / 右定界符），
 * 渲染层据此画**按内容高度伸缩**的弧线。
 */
class MathDelimiterTest {

    private fun parse(src: String) = MathParser.parse(src)

    /** `\left(…\right)` 要产出 Delimited 节点，左右定界符都记下来。 */
    @Test
    fun `left right 解析成 Delimited`() {
        val row = parse("""\left( a + b \right)""") as MathNode.Row
        val d = row.children.filterIsInstance<MathNode.Delimited>().single()
        assertEquals("左定界符不对", "(", d.left)
        assertEquals("右定界符不对", ")", d.right)
    }

    /** 各种定界符都要认。 */
    @Test
    fun `各种定界符都能配对`() {
        listOf(
            """\left[ x \right]""" to ("[" to "]"),
            """\left\{ x \right\}""" to ("{" to "}"),
            """\left| x \right|""" to ("|" to "|"),
        ).forEach { (src, expected) ->
            val row = parse(src) as MathNode.Row
            val d = row.children.filterIsInstance<MathNode.Delimited>().single()
            assertEquals("$src 的左定界符", expected.first, d.left)
            assertEquals("$src 的右定界符", expected.second, d.right)
        }
    }

    /**
     * ⚠️ **括号里的内容不能丢** —— 这是"能定高"的前提。
     *
     * 用户截图里的主方程就是 `\rho\left(\frac{…}{…} + …\right) = …`，
     * 括号里装着分式和运算符。
     */
    @Test
    fun `括号里的内容完整保留`() {
        val src = """\rho\left(\frac{\partial \mathbf{u}}{\partial t} + \mathbf{u}\cdot\nabla \mathbf{u}\right) = -\nabla p"""
        val out = parse(src)?.flatten()
        assertTrue("解析失败", out != null)
        assertTrue("括号丢了：$out", out!!.contains('(') && out.contains(')'))
        assertTrue("分式丢了：$out", out.contains('/'))
        assertTrue("等号丢了：$out", out.contains('='))
        assertTrue("内容被截断：$out", out.contains('∇'))
    }

    /** `.` 是不可见定界符（`\left.` / `\right.`），要记成 null。 */
    @Test
    fun `不可见定界符解析成 null`() {
        val row = parse("""\left. \frac{a}{b} \right|""") as MathNode.Row
        val d = row.children.filterIsInstance<MathNode.Delimited>().single()
        assertEquals("`\\left.` 应当是不可见", null, d.left)
        assertEquals("`\\right|` 应当是竖线", "|", d.right)
    }

    /** 嵌套的 `\left…\right` 要各自配对，不能只认最外层。 */
    @Test
    fun `嵌套括号各自配对`() {
        val row = parse("""\left( a + \left[ b \right] \right)""") as MathNode.Row
        val outer = row.children.filterIsInstance<MathNode.Delimited>().single()
        assertEquals("(", outer.left)
        assertEquals(")", outer.right)
        // 内层应当在 outer.body 里（body 是 Row，内层 Delimited 是它的子节点之一）
        val body = outer.body
        assertTrue("outer.body 应当是 Row：$body", body is MathNode.Row)
        val inner = (body as MathNode.Row).children
            .filterIsInstance<MathNode.Delimited>()
            .firstOrNull()
        assertTrue("内层 `\\left[` 没被解析出来：$body", inner != null)
        assertEquals("[", inner!!.left)
        assertEquals("]", inner.right)
    }

    /** ⚠️ 渲染结果里**不能残留** `\left` / `\right` 的字面文本。 */
    @Test
    fun `渲染结果不残留 left 与 right 关键字`() {
        val out = parse("""\left( \frac{a}{b} \right)""")?.flatten()
        assertTrue("解析失败", out != null)
        assertFalse("残留了 `\\left`：$out", out!!.contains("left"))
        assertFalse("残留了 `\\right`：$out", out.contains("right"))
    }

    /** 没有配对 `\right` 时不能崩，也不能把后面的内容吃掉。 */
    @Test
    fun `缺失 right 时不崩且不吞内容`() {
        val out = parse("""\left( a + b""")?.flatten()
        assertTrue("解析失败", out != null)
        assertTrue("内容被吞了：$out", out!!.contains('a'))
    }
}
