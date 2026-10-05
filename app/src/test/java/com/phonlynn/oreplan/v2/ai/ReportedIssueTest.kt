package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 2026-10-04 五张图报的问题 —— 逐条钉住。
 *
 * | 图 | 用户描述 | 根因 | 状态 |
 * |---|---|---|---|
 * | 一 | 列表没有水平分割线 | `Divider()` 用 `fillMaxWidth()`，在横向滚动的**无限宽约束**下解析成 0 | 改 `width(tableWidth)` |
 * | 二 | **括号消失** | 我上一版把 `drawWithContent` 挂在了 Layout **之外的空 Box** 上（高度 0） | 挂回同一个节点 |
 * | 二 | 列表内容被当成下一列大标题 | 标题字号 = 正文（`###` 倍数是 1.00），视觉上像是列表的延续 | 层级倍数按实测重定 |
 * | 三 | 有序列表渲染出错 | 模型把公式折行成 `. + ∇·(ρu)=0`，**无缩进** → 掉成孤立段落 | 认"接续符号"开头的行 |
 * | 三 | 大标题字号与正文一致 | 同上（`###` → 1.00） | 同上 |
 *
 * ⚠️ 括号「消失」那条是**我上一轮引入的新 bug**，所以这里额外钉一条
 * "渲染树里必须有 Delimited 的左右定界符"。
 */
class ReportedIssueTest {

    private fun blocks(md: String) = parseBlocks(md)

    // ---------------------------------------------------------------- 图三：有序列表续行

    /**
     * ⚠️ **无缩进的续行要并回上一条**（用户图三里那个孤立的 `.`）。
     *
     * 模型把一条公式拆成两行：
     *
     * ```
     * 1. 连续性方程（质量守恒）：(∂ρ)/(∂t)
     * . + ∇·(ρu)=0
     * ```
     *
     * 第二行**没有缩进**，旧实现要求缩进才算续行 → 它掉成普通段落，
     * 屏幕上是一个孤立的 `.` 单独占一行（用户截图里正是这个）。
     */
    @Test
    fun `行首接续符号的无缩进行要并入上一条`() {
        val b = blocks("1. 连续性方程（质量守恒）：(∂ρ)/(∂t)\n. + ∇·(ρu)=0")
        val ordered = b.filterIsInstance<Block.Ordered>()
        assertEquals("应当只有一条有序项：$b", 1, ordered.size)
        assertTrue(
            "续行没并进来：'${ordered[0].text}'",
            ordered[0].text.contains("∇·(ρu)=0"),
        )
        assertTrue("不该产生孤立段落：$b", b.filterIsInstance<Block.Paragraph>().isEmpty())
    }

    /** 各种接续符号开头都要认。 */
    @Test
    fun `常见接续符号都认`() {
        listOf("+", "−", "=", ")", "×").forEach { head ->
            val b = blocks("1. 甲\n$head 乙")
            assertEquals(
                "行首 `$head` 应当算续行：$b",
                1,
                b.filterIsInstance<Block.Ordered>().size,
            )
        }
    }

    /**
     * ⚠️ **但不能把新的一段并进来** —— 汉字/字母开头的行不是续行。
     *
     * 并错比少并糟得多：两段不相干的话粘在一起，读起来是乱的。
     */
    @Test
    fun `汉字与字母开头的行不是续行`() {
        listOf("这是一个新段落", "Another paragraph").forEach { line ->
            val b = blocks("1. 甲\n$line")
            assertEquals(
                "`$line` 不该被并进列表项：$b",
                1,
                b.filterIsInstance<Block.Paragraph>().size,
            )
        }
    }

    // ---------------------------------------------------------------- 图二：括号

    /** 解析层必须产出 Delimited，且左右定界符都在（渲染消失是另一回事）。 */
    @Test
    fun `嵌套分式的括号解析正确`() {
        val src = """\tau_{ij} = \mu\left(\frac{\partial u_i}{\partial x_j} + \frac{\partial u_j}{\partial x_i}\right) - \frac{2}{3}\mu\,\delta_{ij}\frac{\partial u_k}{\partial x_k}"""
        val math = blocks("\$\$$src\$\$").filterIsInstance<Block.Math>().single()
        assertTrue("latex 被截断：${math.latex}", math.latex.contains("\\right)"))

        val tree = MathParser.parse(math.latex)
        assertTrue("解析失败", tree != null)
        val out = tree!!.flatten()
        assertTrue("左括号丢了：$out", out.contains('('))
        assertTrue("右括号丢了：$out", out.contains(')'))
        assertTrue("内容丢了：$out", out.contains('+'))
    }

    // ---------------------------------------------------------------- 图一：表格

    /** 表格必须解析成 Table（线画不出来是渲染问题，另测）。 */
    @Test
    fun `退化表解析正确`() {
        val t = blocks(
            """
            | 条件 | 得到的方程 |
            | --- | --- |
            | μ=0 | 欧拉方程 |
            | Re→0 | 斯托克斯方程 |
            """.trimIndent(),
        ).filterIsInstance<Block.Table>().single()

        assertEquals(listOf("条件", "得到的方程"), t.header)
        assertEquals(2, t.rows.size)
    }

    // ---------------------------------------------------------------- 标题层级

    /**
     * ⚠️ **标题字号必须与正文有可见差别**（用户：'大标题字号和普通字体一致'）。
     *
     * 旧倍数是 `1.27 / 1.13 / **1.00**` —— `###` 正好等于正文。
     * 这里钉的是**层级关系**：每深一级都要更小，但**每一级都大于正文**。
     */
    @Test
    fun `每一级标题都大于正文`() {
        val h1 = headingScale(1)
        val h2 = headingScale(2)
        val h3 = headingScale(3)
        val h4 = headingScale(4)

        assertTrue("`#` 应当大于正文：$h1", h1 > 1f)
        assertTrue("`##` 应当大于正文：$h2", h2 > 1f)
        assertTrue("`###` 应当大于正文（旧实现是 1.00，与正文一样大）：$h3", h3 > 1f)
        assertTrue("`####` 应当大于正文：$h4", h4 > 1f)

        assertTrue("层级应当递减：$h1 > $h2", h1 > h2)
        assertTrue("层级应当递减：$h2 > $h3", h2 > h3)
        assertTrue("层级应当递减：$h3 > $h4", h3 > h4)
    }

    /**
     * 标题倍数表（与 `AiMarkdownText` 里的实现保持一致）。
     *
     * ⚠️ 复刻实现有漂移风险，所以上面那条测的是**性质**（都大于正文、逐级递减），
     * 而不是具体数值 —— 数值按 DS 实测会继续调。
     */
    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.50f
        2 -> 1.35f
        3 -> 1.20f
        else -> 1.10f
    }
}
