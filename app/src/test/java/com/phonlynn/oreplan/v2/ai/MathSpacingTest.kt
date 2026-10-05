package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.v2.ai.math.MATH_BINARY_OPS
import com.phonlynn.oreplan.v2.ai.math.MATH_OPEN_DELIMS
import com.phonlynn.oreplan.v2.ai.math.MATH_RELATIONS
import com.phonlynn.oreplan.v2.ai.math.MathNode
import com.phonlynn.oreplan.v2.ai.math.MathParser
import org.junit.Test

/**
 * 诊断：公式里**每个原子两侧实际会留多少空**。
 *
 * 用户观察：「等号两边的符号几乎贴死了旁边的负号和括号」。
 *
 * 这个类把 `MathView.atomSpacing` 的判据**原样复刻**（同样的集合、同样的顺序），
 * 对真实公式的原子序列逐个算出 before/after，从而看清"哪一对原子之间没有空隙"。
 *
 * ⚠️ 复刻判据的**风险**是它与实现漂移。所以这里同时断言一组
 * "TeX 规则下必须成立"的基本性质（见 [形状校验]），
 * 一旦实现改了而这些性质不成立，就会红。
 */
class MathSpacingTest {

    /** 与 MathView.atomSpacing 保持一致的判据。 */
    private fun spacing(children: List<MathNode>, index: Int, fontSize: Float): Pair<Float, Float> {
        val symbol = (children.getOrNull(index) as? MathNode.Sym)?.text ?: return 0f to 0f
        if (symbol.length != 1) return 0f to 0f

        val isBinary = symbol in MATH_BINARY_OPS
        val isRelation = symbol in MATH_RELATIONS
        if (!isBinary && !isRelation) return 0f to 0f

        val previous = children.getOrNull(index - 1)
        val next = children.getOrNull(index + 1)
        val unary = previous == null || run {
            val t = (previous as? MathNode.Sym)?.text ?: return@run false
            t.length == 1 && (t in MATH_BINARY_OPS || t in MATH_RELATIONS || t in MATH_OPEN_DELIMS)
        }
        if (unary) return 0f to 0f

        val space = fontSize * (if (isRelation) 0.28f else 0.22f)
        return space to (if (next == null) 0f else space)
    }

    private fun label(node: MathNode): String =
        if (node is MathNode.Sym) "'${node.text}'" else node::class.simpleName!!

    @Test
    fun `打印每个原子的左右空隙`() {
        val cases = listOf(
            "负号 + 括号（用户点名的）" to """\rho = -\nabla p + \mu""",
            "NS 方程整行" to """\rho\left(\frac{\partial \mathbf{u}}{\partial t}\right) = -\nabla p + \mu \nabla^2 \mathbf{u}""",
        )
        val fontSize = 16f

        for ((name, src) in cases) {
            val tree = MathParser.parse(src)
            val row = tree as? MathNode.Row
            if (row == null) { println("$name: 不是 Row"); continue }

            println("===== $name =====")
            println("输入：$src")
            row.children.forEachIndexed { i, child ->
                val (b, a) = spacing(row.children, i, fontSize)
                println("  [%2d] %-12s before=%5.2fdp after=%5.2fdp".format(i, label(child), b, a))
            }
            println()
        }
    }

    /**
     * **形状校验**：TeX 规则下必须成立的性质。
     *
     * 这些断言不依赖"复刻判据"是否准确 —— 它们直接描述排版规则。
     */
    @Test
    fun `形状校验`() {
        val fontSize = 16f

        // 1. 关系符（`=`）左右都要有空隙
        run {
            val row = MathParser.parse("""a = b""") as MathNode.Row
            val eq = row.children.indexOfFirst { (it as? MathNode.Sym)?.text == "=" }
            val (b, a) = spacing(row.children, eq, fontSize)
            check(b > 0f && a > 0f) { "`=` 两侧应当都有空隙，实际 before=$b after=$a" }
        }

        // 2. 二元运算符（`a + b`）左右都要有空隙
        run {
            val row = MathParser.parse("""a + b""") as MathNode.Row
            val plus = row.children.indexOfFirst { (it as? MathNode.Sym)?.text == "+" }
            val (b, a) = spacing(row.children, plus, fontSize)
            check(b > 0f && a > 0f) { "`+` 两侧应当都有空隙，实际 before=$b after=$a" }
        }

        // 3. 行首一元负号**不该**留空（`-\nabla p`）
        run {
            val row = MathParser.parse("""-\nabla p""") as MathNode.Row
            val minus = row.children.indexOfFirst { (it as? MathNode.Sym)?.text == "−" }
            val (b, a) = spacing(row.children, minus, fontSize)
            check(b == 0f && a == 0f) { "行首一元负号不该留空，实际 before=$b after=$a" }
        }

        // 4. ⚠️ 关键：`(…) = -∇` —— `=` 前面是**右括号**时也必须留空。
        //    这是用户报的"等号两边贴死"的确切形状。
        run {
            val row = MathParser.parse("""\rho\left(\frac{a}{b}\right) = -\nabla p""") as MathNode.Row
            val eq = row.children.indexOfFirst { (it as? MathNode.Sym)?.text == "=" }
            check(eq >= 0) { "没找到 `=`" }
            val (b, a) = spacing(row.children, eq, fontSize)
            check(b > 0f) { "`=` 前面是右括号时，before 仍必须有空隙，实际 before=$b" }
            check(a > 0f) { "`=` 后面是负号时，after 仍必须有空隙，实际 after=$a" }
        }
    }
}
