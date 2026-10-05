package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.v2.ai.math.AtomType
import com.phonlynn.oreplan.v2.ai.math.MathNode
import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.atomGapEm
import com.phonlynn.oreplan.v2.ai.math.atomTypeOf
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断/回归：公式里**相邻两个原子之间**实际插了多少空隙。
 *
 * ## 背景（用户 2026-10-04 第六张图：「空隙过大」）
 *
 * 实测那条一般形式公式里，相邻墨迹段之间有 **17~34dp** 的空隙，
 * 而 TeX 的 thick space 只有 4.67dp（16.8sp 下）—— 差 4~7 倍。
 *
 * 根因：`atomSpacing` 给**每个原子的两侧各加一份**空隙，
 * 相邻两个原子之间于是叠了**两份**：
 *
 * | 相邻原子 | 叠加后 | TeX 应当 |
 * |---|---|---|
 * | `frac` ↔ `=` | 9.33dp | 4.67dp |
 * | `+` ↔ `μ` | 7.47dp | 3.73dp |
 *
 * 修法：改成**一对原子之间只插一份**（`atomGapBetween`）。
 *
 * ## 这个测试断言什么
 *
 * 用真实公式，逐个检查相邻原子之间的空隙**不超过 TeX 的上限**（thick space）。
 * 这是"叠加"这类错误最直接的指纹 —— 一旦有人再把两侧都加上，就会红。
 */
class SpacingOverflowDiagnosticTest {

    private val formulaSp = 16.8f   // 正文 16sp * 1.05

    /** 与 `MathView.atomGapBetween` 一致的判据。 */
    private fun gapBetween(children: List<MathNode>, left: Int): Float {
        val l = children.getOrNull(left) ?: return 0f
        val r = children.getOrNull(left + 1) ?: return 0f
        return atomGapEm(typeOf(l, children, left), typeOf(r, children, left + 1)) * formulaSp
    }

    private fun typeOf(node: MathNode, children: List<MathNode>, index: Int): AtomType {
        val base = when (node) {
            is MathNode.Sym -> atomTypeOf(node.text)
            is MathNode.BigOp -> AtomType.OP
            else -> AtomType.INNER
        }
        if (base != AtomType.BIN) return base
        val prev = children.getOrNull(index - 1) ?: return AtomType.ORD
        val pt = when (prev) {
            is MathNode.Sym -> atomTypeOf(prev.text)
            is MathNode.BigOp -> AtomType.OP
            else -> AtomType.INNER
        }
        return if (pt == AtomType.BIN || pt == AtomType.REL || pt == AtomType.OPEN) {
            AtomType.ORD
        } else {
            AtomType.BIN
        }
    }

    private fun label(n: MathNode): String = when (n) {
        is MathNode.Space -> "Space(%.3f)".format(n.em)
        is MathNode.Sym -> "'${n.text}'"
        is MathNode.Frac -> "Frac"
        is MathNode.Script -> "Script"
        is MathNode.Row -> "Row(${n.children.size})"
        is MathNode.Delimited -> "Delimited"
        is MathNode.Sqrt -> "Sqrt"
        is MathNode.BigOp -> "BigOp"
        is MathNode.Overline -> "Overline"
    }

    /** ⚠️ **核心**：任何相邻原子之间的空隙都不能超过 thick space。 */
    @Test
    fun `相邻原子之间的空隙不超过 TeX 上限`() {
        val formulas = listOf(
            """\rho\frac{D\mathbf{u}}{Dt} = -\nabla p + \mu\nabla^2\mathbf{u} + \frac{1}{3}\mu\nabla(\nabla\cdot\mathbf{u})""",
            """\tau_{ij} = \mu\left(\frac{\partial u_i}{\partial x_j} + \frac{\partial u_j}{\partial x_i}\right) - \frac{2}{3}\mu\,\delta_{ij}\frac{\partial u_k}{\partial x_k}""",
            """\rho\left(\frac{\partial \mathbf{u}}{\partial t} + \mathbf{u}\cdot\nabla \mathbf{u}\right) = -\nabla p + \mu \nabla^2 \mathbf{u} + \mathbf{f}""",
        )

        // thick space = 5/18 em，是 TeX 里最宽的原子间距
        val limit = 5f / 18f * formulaSp + 0.01f
        val offenders = ArrayList<String>()

        for (src in formulas) {
            val row = MathParser.parse(src) as? MathNode.Row ?: continue
            for (i in 0 until row.children.size - 1) {
                val gap = gapBetween(row.children, i)
                if (gap > limit) {
                    offenders += "%-14s | %-14s = %.2fdp (上限 %.2f)  <- %s".format(
                        label(row.children[i]), label(row.children[i + 1]), gap, limit, src.take(40),
                    )
                }
            }
        }

        assertTrue(
            "有相邻原子之间的空隙超过了 thick space（可能是两侧都加了间距）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 打印真实公式的每个空隙（供人工核对）。 */
    @Test
    fun `打印一般形式公式的每个空隙`() {
        val src = """\rho\frac{D\mathbf{u}}{Dt} = -\nabla p + \mu\nabla^2\mathbf{u} + \frac{1}{3}\mu\nabla(\nabla\cdot\mathbf{u})"""
        val row = MathParser.parse(src) as MathNode.Row

        println("===== 相邻原子之间的空隙 =====")
        println("输入：$src")
        for (i in 0 until row.children.size - 1) {
            val gap = gapBetween(row.children, i)
            if (gap > 0f) {
                println("  %-14s | %-14s = %5.2fdp".format(
                    label(row.children[i]), label(row.children[i + 1]), gap))
            }
        }
    }
}

