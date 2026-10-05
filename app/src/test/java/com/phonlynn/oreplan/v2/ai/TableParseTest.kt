package com.phonlynn.oreplan.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格**解析**的鲁棒性。
 *
 * ## 为什么要专门测这些形状
 *
 * 表格渲染不出来，最常见的原因不是渲染，而是**解析没认出这是表格** ——
 * 于是整段退化成普通段落，用户看到的是一堆 `|` 和 `---`。
 *
 * 模型不会老老实实按 CommonMark 写：它经常省掉首尾竖线、留空单元格、
 * 少写几个短横。这些形状都得认。
 *
 * （列宽计算见 [TableColumnWidthTest] —— 那是"认出来了但画得不能看"的另一半。）
 */
class TableParseTest {

    private fun table(markdown: String): Block.Table =
        parseBlocks(markdown).filterIsInstance<Block.Table>().single()

    // ---------------------------------------------------------------- 常见形状

    /** 省掉首尾竖线 —— 模型很常这么写。 */
    @Test
    fun `没有首尾竖线也认`() {
        val t = table(
            """
            项目 | 分数
            --- | ---
            数学 | 90
            """.trimIndent(),
        )

        assertEquals(listOf("项目", "分数"), t.header)
        assertEquals(listOf(listOf("数学", "90")), t.rows)
    }

    /** 空单元格要保留位置，不能把后面的列往前挤。 */
    @Test
    fun `空单元格保留位置`() {
        val t = table("| a |  | c |\n| --- | --- | --- |\n| 1 |  | 3 |")

        assertEquals(listOf("a", "", "c"), t.header)
        assertEquals(listOf(listOf("1", "", "3")), t.rows)
    }

    /** 单元格里的**竖线**用 `\|` 转义时不能拆列。 */
    @Test
    fun `转义的竖线不拆列`() {
        val t = table("""| 表达式 | 说明 |""" + "\n" + """| --- | --- |""" + "\n" + """| a \| b | 或 |""")

        assertEquals(2, t.header.size)
        assertEquals("转义竖线应当留在同一格里：${t.rows}", 2, t.rows.single().size)
        assertTrue(
            "格子里应当含竖线：${t.rows}",
            t.rows.single()[0].contains('|'),
        )
    }

    /** 只有表头与分隔行、没有数据行 —— 也要认（渲染成只有表头）。 */
    @Test
    fun `没有数据行也认`() {
        val t = table("| a | b |\n| --- | --- |")

        assertEquals(listOf("a", "b"), t.header)
        assertTrue("不该凭空造出数据行", t.rows.isEmpty())
    }

    /** 分隔行只写一个短横也认（CommonMark 允许，模型也常偷懒）。 */
    @Test
    fun `一个短横的分隔行也认`() {
        val t = table("| a | b |\n|-|-|\n| 1 | 2 |")

        assertEquals(listOf(listOf("1", "2")), t.rows)
    }

    /** 单元格两侧的空格要去掉，但内容里的空格要留着。 */
    @Test
    fun `去两侧空格保留内容空格`() {
        val t = table("|  名称  | 备注 |\n| --- | --- |\n|  数学 分析  |  ok  |")

        assertEquals(listOf("名称", "备注"), t.header)
        assertEquals(listOf(listOf("数学 分析", "ok")), t.rows)
    }

    /** 列数不齐的行（模型漏写一格）不能崩，缺的当空串。 */
    @Test
    fun `列数不齐不崩`() {
        val t = table("| a | b | c |\n| --- | --- | --- |\n| 1 |\n| 1 | 2 | 3 |")

        assertEquals(3, t.header.size)
        assertEquals(2, t.rows.size)
        assertEquals(3, t.rows[0].size)
    }

    /** 表格**紧跟在正文后面**（没有空行）也要能认出来。 */
    @Test
    fun `紧跟在正文后也认`() {
        val blocks = parseBlocks("对比如下：\n| a | b |\n| --- | --- |\n| 1 | 2 |")

        assertEquals("正文应当单独成段", 1, blocks.filterIsInstance<Block.Paragraph>().size)
        assertEquals(1, blocks.filterIsInstance<Block.Table>().size)
    }

    /** 表格后面接正文时，正文不能被当成数据行吃掉。 */
    @Test
    fun `表格后的正文不被吃掉`() {
        val blocks = parseBlocks("| a | b |\n| --- | --- |\n| 1 | 2 |\n\n以上是对比结果。")

        val t = blocks.filterIsInstance<Block.Table>().single()
        assertEquals("只该有一行数据", 1, t.rows.size)
        assertTrue(
            "表格后面的正文丢了：$blocks",
            blocks.filterIsInstance<Block.Paragraph>().any { it.text.contains("以上是对比结果") },
        )
    }

    /**
     * ⚠️ 已知取舍：表格后面**紧接着**（无空行）一行含 `|` 的正文，会被当成数据行。
     *
     * 这条**不是** bug —— 单看那一行无法区分"数据行"和"含竖线的句子"。
     * 钉在这里是为了：哪天有人想改，先看到这个取舍是**知情**的。
     */
    @Test
    fun `已知取舍 含竖线的正文会被当数据行`() {
        val blocks = parseBlocks("| a | b |\n| --- | --- |\n| 1 | 2 |\na | b 这种写法也能用")

        val t = blocks.filterIsInstance<Block.Table>().single()
        assertEquals("无空行时会多吃一行 —— 这是知情取舍", 2, t.rows.size)
    }

    /** 对齐标记在读列数不齐的行时也不能崩。 */
    @Test
    fun `对齐数量少于列数时不崩`() {
        val t = table("| a | b | c |\n| :-: |\n| 1 | 2 | 3 |")

        assertEquals(3, t.header.size)
        assertEquals(TableAlign.CENTER, t.alignments.first())
    }
}
