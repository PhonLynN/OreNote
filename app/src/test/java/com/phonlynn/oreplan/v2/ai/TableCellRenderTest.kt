package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格里**单元格内容**的渲染（不是外观）。
 *
 * ## 为什么单独立一条
 *
 * 2026-10-04 用户给了同一段 NS 方程回复在 DS App 与 OreNote 的双图对比
 *（`verification/dsref/ds_ns_2.jpg` / `mine_ns_2.jpg`）。
 * 逐条对比后发现的**真缺陷**是：
 *
 * | 项 | DS | OreNote（改前） |
 * |---|---|---|
 * | 表格左列 | `ρ∂u/∂t`、`ρu·∇u`、`−∇p`、`μ∇²u`、`f` 全部显示 | **整列为空** |
 *
 * 左列那几格的内容都是 `$\rho\,\partial\mathbf{u}/\partial t$` 这类**行内公式**，
 * 所以"整列为空"只可能是单元格里的行内公式没被渲染出来。
 *
 * ⚠️ 表格**外观**（无外框 / 无背景 / 只有水平分割线）是用户明确要的，
 * 与 DS 的带框卡片样式**刻意不一致** —— 那条不要"修"。
 * 这里测的是内容，不是外观。
 */
class TableCellRenderTest {

    private val nsTable = listOf(
        "##各项的物理含义|项 |含义 |",
        "|---|---|",
        "| \$\\rho\\,\\partial\\mathbf{u}/\\partial t\$ |非定常项，速度随时间变化 |",
        "| \$\\rho\\,\\mathbf{u}\\cdot\\nabla\\mathbf{u}\$ |对流项，流体被自身流动带走而改变速度 |",
        "| \$-\\nabla p\$ |压强梯度力，流体从高压流向低压 |",
        "| \$\\mu\\nabla^2\\mathbf{u}\$ |粘性力，流体内部摩擦 |",
        "| \$\\mathbf{f}\$ |外力 |",
    ).joinToString("\n")

    private fun table(): Block.Table =
        parseBlocks(nsTable).filterIsInstance<Block.Table>().single()

    /** 表格必须被识别出来，且列数是 2。 */
    @Test
    fun `粘着标题的表格要解析出来`() {
        val t = table()
        assertEquals("列数不对：${t.header}", 2, t.header.size)
        assertEquals("数据行数不对", 5, t.rows.size)
    }

    /** 「项」列第一格的内容要**原样保留**（原始 Markdown，渲染时才消费标记）。 */
    @Test
    fun `公式单元格的内容不能为空`() {
        val t = table()
        val firstCol = t.rows.map { it[0] }
        firstCol.forEachIndexed { i, cell ->
            assertTrue(
                "第 $i 行第一格是空的 —— 公式单元格内容丢了。整行=${t.rows[i]}",
                cell.isNotBlank(),
            )
        }
    }

    /**
     * ⚠️ **核心用例**：单元格里的行内公式要渲染成内容，不能渲染成空白。
     *
     * 渲染路径与正文一致（`TableCell` → `InlineText` → `renderInline`）。
     * 这里断言的是"渲染后有东西、且没有裸标记"。
     */
    @Test
    fun `单元格里的行内公式要渲染出内容`() {
        val t = table()
        val failures = ArrayList<String>()

        for (row in t.rows) {
            for (cell in row) {
                val rendered = renderInline(cell, Color.Black, 12.sp).text

                if (cell.isNotBlank() && rendered.isBlank()) {
                    failures += "单元格「$cell」渲染后是空的"
                }
                if (rendered.contains('$')) {
                    failures += "单元格「$cell」残留美元号 → 「$rendered」"
                }
                if (rendered.contains('\\')) {
                    failures += "单元格「$cell」残留反斜杠命令 → 「$rendered」"
                }
            }
        }

        assertTrue("表格单元格渲染有问题：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** 左列渲染后应当是数学字形（`ρ`、`∇`、`μ` 这些），不是原样 LaTeX。 */
    @Test
    fun `左列渲染成数学字形`() {
        val t = table()
        val firstCol = t.rows.map { renderInline(it[0], Color.Black, 12.sp).text }

        assertTrue("第一格应当含 ρ：$firstCol", firstCol[0].contains('ρ'))
        assertTrue("第二格应当含 ∇：$firstCol", firstCol[1].contains('∇'))
        assertTrue("第三格应当含 ∇：$firstCol", firstCol[2].contains('∇'))
        assertTrue("第四格应当含 ∇²：$firstCol", firstCol[3].contains('∇'))
        assertTrue("第五格应当含 f：$firstCol", firstCol[4].contains('f'))
    }

    /** 右列是纯文字，不能被公式解析误伤。 */
    @Test
    fun `右列文字原样保留`() {
        val t = table()
        assertEquals("非定常项，速度随时间变化", t.rows[0][1])
        assertEquals("外力", t.rows[4][1])
    }
}
