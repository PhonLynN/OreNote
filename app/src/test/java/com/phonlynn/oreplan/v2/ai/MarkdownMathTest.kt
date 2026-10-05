package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.v2.ai.math.MathNode
import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.flatten
import com.phonlynn.oreplan.v2.ai.math.toMathAlphanumeric
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX 解析 + Markdown 分块。
 *
 * 只钉**结构**，不碰排版 —— 排版在真机上看，解析错了才是"看起来像坏了"的根源。
 * 这两块是纯函数，测起来便宜，且出错的后果（公式显示成乱码、表格变成一堆竖线）
 * 光看代码很难发现。
 */
class MarkdownMathTest {

    // ---------------------------------------------------------------- LaTeX

    private fun math(src: String) = MathParser.parse(src)

    @Test
    fun `分数解析成上下两层`() {
        val node = math("""\frac{a}{b}""")
        assertTrue(node is MathNode.Row)
        val frac = (node as MathNode.Row).children.single()
        assertTrue("应当是 Frac，实际是 $frac", frac is MathNode.Frac)
        assertEquals("a", ((frac as MathNode.Frac).numerator as MathNode.Row).flatten())
        assertEquals("b", (frac.denominator as MathNode.Row).flatten())
    }

    @Test
    fun `上下标附着在前一个原子上`() {
        val row = (math("x^2") as MathNode.Row).children.single()
        assertTrue("应当是 Script，实际是 $row", row is MathNode.Script)
        row as MathNode.Script
        // 单个字符的上下标是 Sym（不是 Row），所以用 flatten 取值而不是强制转型
        assertEquals("2", row.sup!!.flatten())

        // 上下标同时存在，且书写顺序相反也要认
        val both = (math("x_i^2") as MathNode.Row).children.single() as MathNode.Script
        assertEquals("2", both.sup!!.flatten())
        assertEquals("i", both.sub!!.flatten())
    }

    @Test
    fun `大型算符带上限下标`() {
        val row = (math("""\sum_{i=1}^{n} i""") as MathNode.Row).children
        val op = row.first()
        assertTrue(op is MathNode.BigOp)
        op as MathNode.BigOp
        assertEquals("∑", op.text)
        assertTrue("∑ 的上下限应当排在正上正下", op.limitsAbove)
        assertNotNull(op.sub)
        assertNotNull(op.sup)
    }

    @Test
    fun `积分上下限排在右侧`() {
        val op = (math("""\int_0^1 f""") as MathNode.Row).children.first() as MathNode.BigOp
        assertEquals("∫", op.text)
        assertTrue("积分的上下限在右侧", !op.limitsAbove)
    }

    @Test
    fun `希腊字母与关系符映射到 Unicode`() {
        assertEquals("α≤β", math("""\alpha \le \beta""")!!.flatten())
    }

    /** 不认识的命令退化成**名字**，而不是原样吐反斜杠（后者在对话里很刺眼）。 */
    @Test
    fun `未知命令退化成名字`() {
        assertEquals("foobar", math("""\foobar""")!!.flatten())
    }

    /** 行内公式压平：能映射到上下标字符就用字符。 */
    @Test
    fun `行内压平用 Unicode 上下标`() {
        assertEquals("x²", math("x^2")!!.flatten())
        assertEquals("H₂O", math("H_2O")!!.flatten())
        assertEquals("1/2", math("""\frac{1}{2}""")!!.flatten())
        assertEquals("√(x)", math("""\sqrt{x}""")!!.flatten())
    }

    /**
     * 残缺的公式要**尽量给出结果**，而不是整段放弃。
     *
     * 理由很实际：流式输出时公式是**一个字一个字到达**的，
     * `\frac{a` 这种中间态每秒都会出现。此时若判定"解析失败"并退回原文，
     * 用户会看到屏幕上闪一串原始 LaTeX，然后才变成公式。
     * 容错解析则表现为"分数线先出来、分母随后填上"，观感好得多。
     */
    @Test
    fun `残缺公式尽量给出结构`() {
        val node = math("""\frac{a""")
        assertNotNull("残缺也应给出结构，而不是 null", node)
        assertTrue((node as MathNode.Row).children.single() is MathNode.Frac)
    }

    /** 但真的什么都没有时就是 null —— 调用方据此不画任何东西。 */
    @Test
    fun `空公式返回 null`() {
        assertNull(math(""))
        assertNull(math("   "))
    }

    // ---------------------------------------------------------------- Markdown

    @Test
    fun `表格要认出表头与数据行`() {
        val blocks = parseBlocks(
            """
            | 项目 | 分数 |
            | --- | --- |
            | 数学 | 90 |
            | 英语 | 85 |
            """.trimIndent(),
        )
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(listOf("项目", "分数"), table.header)
        assertEquals(listOf(listOf("数学", "90"), listOf("英语", "85")), table.rows)
    }

    @Test
    fun `任务清单带勾选状态与缩进`() {
        val blocks = parseBlocks(
            """
            - [ ] 没做
            - [x] 做完了
              - [ ] 子项
            """.trimIndent(),
        )
        val tasks = blocks.filterIsInstance<Block.Task>()
        assertEquals(3, tasks.size)
        assertEquals(false, tasks[0].checked)
        assertEquals(true, tasks[1].checked)
        assertEquals(0, tasks[1].indent)
        assertEquals("缩进一级", 1, tasks[2].indent)
    }

    @Test
    fun `分隔线与块级公式各自成块`() {
        val blocks = parseBlocks(
            """
            上面

            ---

            $$
            \frac{a}{b}
            $$
            """.trimIndent(),
        )
        assertTrue(blocks.any { it is Block.Rule })
        val formula = blocks.filterIsInstance<Block.Math>().single()
        assertEquals("""\frac{a}{b}""", formula.latex)
    }

    /**
     * `\[…\]` 与 `$$…$$` 必须**一视同仁**。
     *
     * 用户真机截图里的"非常多渲染失败"就是这里来的：模型（DeepSeek）
     * 大量使用 LaTeX 原生的 `\[…\]` / `\(…\)`，而上一版只认 `$$` / `$`，
     * 于是同一段回复里 `$$` 的渲染成功、`\[` 的原样露出。
     */
    @Test
    fun `LaTeX 原生分隔符也要认`() {
        val block = parseBlocks(
            """
            \[
            \rho \left( \frac{\partial u}{\partial t} \right)
            \]
            """.trimIndent(),
        )
        assertEquals(
            """\rho \left( \frac{\partial u}{\partial t} \right)""",
            block.filterIsInstance<Block.Math>().single().latex,
        )

        // 同一行的紧凑写法
        val oneLine = parseBlocks("""\[x^2\]""").filterIsInstance<Block.Math>().single()
        assertEquals("x^2", oneLine.latex)

        // 行内 `\(…\)`
        val inline = renderInline(
            "速度场 \\(u\\) 结束",
            androidx.compose.ui.graphics.Color.Black,
            12.sp,
        )
        assertTrue("行内公式应当被解析：${inline.text}", inline.text.contains("u"))
        assertTrue("不该留下反斜杠：${inline.text}", !inline.text.contains("\\("))
    }

    /**
     * `$` 也可能是**美元符号**，不能一律当公式。
     *
     * 「这本书 $5，那本 $10」若被当成公式，中间那段文字会被塞进公式渲染里。
     */
    @Test
    fun `钱数不会被当成行内公式`() {
        val text = renderInline(
            "这本书 \$5，那本 \$10 元",
            androidx.compose.ui.graphics.Color.Black,
            12.sp,
        )
        assertEquals("这本书 \$5，那本 \$10 元", text.text)
    }

    /** 代码围栏里的东西一个都不能被当成语法。 */
    @Test
    fun `代码块里的井号竖线星号都不解析`() {
        val blocks = parseBlocks(
            """
            ```
            # 这不是标题
            | 这不是 | 表格 |
            ---
            ```
            """.trimIndent(),
        )
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertTrue(code.text.contains("# 这不是标题"))
        assertTrue(blocks.none { it is Block.Heading })
        assertTrue(blocks.none { it is Block.Table })
        assertTrue(blocks.none { it is Block.Rule })
    }

    /** 行内公式优先于斜体：`$a*b$` 里的星号不该被当成斜体标记。 */
    @Test
    fun `行内公式里的星号不被当成斜体`() {
        val text = renderInline(
            "公式 ${'$'}a*b${'$'} 结束",
            androidx.compose.ui.graphics.Color.Black,
            12.sp,
        )
        assertTrue("应当保留原样的 a*b，实际是「${text.text}」", text.text.contains("a*b"))
    }

    /**
     * 变量要映射到 **Unicode 数学字母**，不是靠 fontStyle 倾斜。
     *
     * 这是"公式字体完全不对"那件事的核心：数学斜体 `𝑎` 是 U+1D44E 这个
     * **独立码位**，数学粗体 `𝐮` 是 U+1D42E。用码位选中的才是真正的数学字形；
     * 把正体倾斜一下只是合成假斜体。
     *
     * 码位写死在这里，是为了防止以后有人"顺手"改错映射表 ——
     * 错了不会报错，只会显示成豆腐块、或者在屏幕上悄悄变成别的字母。
     */
    @Test
    fun `变量映射到数学字母码位`() {
        fun cp(s: String) = s.codePointAt(0)

        // 斜体：数学斜体 a / A / x
        assertEquals(0x1D44E, cp(toMathAlphanumeric("a", italic = true, bold = false)))
        assertEquals(0x1D434, cp(toMathAlphanumeric("A", italic = true, bold = false)))
        assertEquals(0x1D465, cp(toMathAlphanumeric("x", italic = true, bold = false)))

        // 粗体（\mathbf）：数学粗体 u / f
        assertEquals(0x1D42E, cp(toMathAlphanumeric("u", italic = false, bold = true)))
        assertEquals(0x1D41F, cp(toMathAlphanumeric("f", italic = false, bold = true)))

        // 粗斜体
        assertEquals(0x1D482, cp(toMathAlphanumeric("a", italic = true, bold = true)))

        // 小写希腊字母：数学斜体 α / μ / ρ
        assertEquals(0x1D6FC, cp(toMathAlphanumeric("α", italic = true, bold = false)))
        assertEquals(0x1D707, cp(toMathAlphanumeric("μ", italic = true, bold = false)))
        assertEquals(0x1D70C, cp(toMathAlphanumeric("ρ", italic = true, bold = false)))

        // 大写希腊字母：TeX 里保持正体，只有 \mathbf 才变粗
        assertEquals(0x0393, cp(toMathAlphanumeric("Γ", italic = true, bold = false)))
        // Γ 是第 3 个希腊字母 → 粗体 Γ = U+1D6A8 + 2 = U+1D6AA（𝚪），不是 𝚨
        assertEquals(0x1D6AA, cp(toMathAlphanumeric("Γ", italic = false, bold = true)))
        assertEquals(0x1D6A8, cp(toMathAlphanumeric("Α", italic = false, bold = true)))

        // 运算符、数字、∇/∂/∑ 本来就是正体，一个都不该被改
        for (s in listOf("+", "=", "2", "∇", "∂", "∑", "≤", "·")) {
            assertEquals("「$s」不该被映射", s, toMathAlphanumeric(s, italic = true, bold = true))
        }

        // 多字符：逐字符映射（每个数学字母占两个 char，所以长度翻倍）
        val mapped = toMathAlphanumeric("ab", italic = true, bold = false)
        assertEquals(2, mapped.codePointCount(0, mapped.length))
        assertEquals(4, mapped.length)
    }

    /**
     * 小写希腊字母在数学里是**变量**，解析出来要带斜体标记。
     *
     * 之前一律按正体渲染，所以 `ρ`、`μ` 是直立的 —— 和参考图明显不同。
     */
    @Test
    fun `小写希腊字母是斜体而大写不是`() {
        fun firstSym(src: String): MathNode.Sym =
            (math(src) as MathNode.Row).children.first() as MathNode.Sym

        assertTrue("alpha 应当是斜体变量", firstSym("""\alpha""").italic)
        assertTrue("rho 应当是斜体变量", firstSym("""\rho""").italic)
        assertFalse("Gamma 大写希腊字母保持正体", firstSym("""\Gamma""").italic)
        assertFalse("nabla 是算符不是变量", firstSym("""\nabla""").italic)
        assertFalse("partial 是算符", firstSym("""\partial""").italic)
    }

    /**
     * ASCII 的 `-` 在公式里要变成 **U+2212 真减号**。
     *
     * 用户报的："减号字体仍然不是优化字体，看起来很违和"——
     * 根因不是字体，是**用错了字符**：`-` 是连字符（短、偏高），
     * 数学字体渲染出来和旁边 `+`、`=` 不是一套。
     */
    @Test
    fun `公式里的连字符变成真减号`() {
        val minus = '\u2212'

        // 二元减号
        assertEquals("a${minus}b", math("a-b")!!.flatten())
        // 一元负号（-∇p）
        assertEquals("${minus}∇p", math("""-\nabla p""")!!.flatten())
        // 展开后的 NS 方程：一个 ASCII 连字符都不该剩
        val ns = math("""\rho(\frac{\partial \mathbf{u}}{\partial t}+(\mathbf{u}\cdot\nabla)\mathbf{u})=-\nabla p+\mu\nabla^2\mathbf{u}+\mathbf{f}""")
        assertNotNull(ns)
        assertFalse("仍残留 ASCII 连字符：${ns!!.flatten()}", ns.flatten().contains('-'))
        assertTrue("应当含真减号：${ns.flatten()}", ns.flatten().contains(minus))
    }

    /**
     * ⚠️ 但 `\text{}` 里的连字符**不能**被改 —— 那是文本，不是数学。
     *
     * 比如 `\text{N-S方程}`，改成减号就成了 `N−S方程`。
     * 这条能成立，正是因为换字符是在**解析阶段**做的：
     * `\text{}` 走 `readGroupRawText()`，根本不经过 `plainChar`。
     */
    @Test
    fun `文本模式里的连字符保持原样`() {
        assertEquals("N-S方程", math("""\text{N-S方程}""")!!.flatten())
        assertEquals("N-S方程", math("""\mathrm{N-S方程}""")!!.flatten())
        assertEquals("e-mail", math("""\text{e-mail}""")!!.flatten())
    }
}
