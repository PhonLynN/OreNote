package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格**列宽**的行为。
 *
 * ## 这一条守的是什么（用户 2026-10-04 亲自纠正过我）
 *
 * 用户原话：
 *
 * > 「按理说设计不应该是**取同一列最宽的一行加上一小段**作为列宽吗，
 * >   为什么现在所有元素都靠左堆积起来，**同一列不对齐了**」
 *
 * **这就是表格的定义**：列宽 = 该列所有行里最宽的那一格，然后**整列统一**。
 * 表格的可读性完全来自"同一列上下对齐"。
 *
 * ## 我在这件事上走了三次弯路（都记下来，防止再犯）
 *
 * | 版本 | 做法 | 结果 |
 * |---|---|---|
 * | v1 | 按字符数**估算**每列宽，夹到 `[72, 220]dp` | `∂u/∂t`（真实 35dp）被估成 220dp → **两列之间 244dp 空白**，占屏宽 53% ❌ |
 * | v2 | 改成 `weight(估宽, fill = false)`，让 Compose 按**每行各自**测宽 | 同一列在不同行宽不同 → **列边界对不上、全往左堆** ❌ |
 * | **v3** | **按"整列"测量**：取该列最宽的一格 → 夹上下限 → 摊满 → **整列统一使用** ✅ |
 *
 * v1 的错是"用猜代替测"；v2 的错是"测了，但按行测而不是按列测"。
 *
 * ## 为什么这个测试文件里没有"精确 dp 断言"
 *
 * 真实测量发生在 Compose 的 `TextMeasurer` 里，纯 JVM 单测拿不到字体度量 ——
 * 硬写一个 dp 值只会得到一个**跑在 JVM 上、和真机无关的假精度**。
 *
 * 所以这里钉的是**算法结构**（用纯函数 [tableColumnWidths] 表达）：
 * 列宽只由"该列最宽的一格"决定、整列一致、放不下就溢出。
 * 真正的像素值靠真机截图核对（`verification/dsref/`）。
 */
class TableColumnWidthTest {

    private val bubble = 330.dp
    private val fontSize = 16f

    private fun widths(
        header: List<String>,
        rows: List<List<String>> = emptyList(),
        available: Dp = bubble,
        columns: Int = header.size,
    ) = tableColumnWidths(header, rows, columns, available, fontSize)

    private fun List<Dp>.total(): Dp = fold(0.dp) { acc, w -> acc + w }

    // ---------------------------------------------------------------- 核心行为

    /**
     * ⚠️ **宽表必须溢出**，不能压扁。
     *
     * 溢出 → 外层 `horizontalScroll` 生效 → 每列保持可读。
     */
    @Test
    fun `宽表会超过可用宽度以便滚动`() {
        val header = listOf("课程名称", "上课时间", "教室", "任课教师")
        val rows = listOf(
            listOf("工程制图与计算机绘图", "周三 09:50-11:30", "GXB-607", "刘令涛"),
            listOf("思想道德与法治", "周二 15:10-16:50", "XXA-101", "史敬文"),
        )

        val w = widths(header, rows)

        assertTrue(
            "四列中文内容在 330dp 里放不下，总宽必须超过它才会滚动；实际 ${w.total()}",
            w.total() > bubble,
        )
        w.forEach { assertTrue("列宽 $it 小于最小值，字会被挤成竖排", it >= 72.dp) }
    }

    /** 窄表要**铺满**可用宽度，右边不留一条空。 */
    @Test
    fun `窄表铺满可用宽度`() {
        val w = widths(listOf("项", "值"), listOf(listOf("A", "1")))

        assertEquals(
            "放得下时应当正好占满可用宽度",
            bubble.value,
            w.total().value,
            0.5f,
        )
    }

    /**
     * ⚠️ **列宽由该列最宽的一格决定** —— 短内容不能把整列压窄。
     *
     * 这正是用户说的"取同一列最宽的一行"。
     */
    @Test
    fun `列宽由该列最宽的一格决定`() {
        // 第 0 列：表头短、某一行很长 → 列宽必须照顾到那一行
        val w = widths(
            listOf("项", "含义"),
            listOf(
                listOf("∂u/∂t", "当地加速度"),
                listOf("这是一个相当长的公式说明文字", "外力"),
            ),
        )

        assertTrue(
            "第 0 列有一行内容很长，列宽必须照顾到它（不能被表头「项」压窄）：${w[0]}",
            w[0] > 72.dp,
        )
    }

    /** 内容相同 → 列宽相同（对称性，最基础的护栏）。 */
    @Test
    fun `内容相同的两列等宽`() {
        val w = widths(listOf("甲甲甲", "乙乙乙"), listOf(listOf("甲乙甲", "乙甲乙")))

        assertEquals("内容等长的两列应当等宽", w[0].value, w[1].value, 0.5f)
    }

    /** 放不下时没有一列超过上限（否则会把别的列挤没）。 */
    @Test
    fun `放不下时没有一列超过上限`() {
        val long = "这是一个非常非常非常非常非常非常长的单元格内容用来测试上限"
        val header = listOf(long, long, long, long)

        val w = widths(header)

        assertTrue("四列都超长，自然总宽必然放不下", w.total() > bubble)
        w.forEach { assertTrue("滚动时列宽 $it 不该超过 220dp", it <= 220.dp) }
    }

    /** 极短内容也有最小宽度，不会变成一条缝。 */
    @Test
    fun `极短内容也有最小宽度`() {
        val w = widths(listOf("a", "b", "c", "d"), listOf(listOf("1", "2", "3", "4")))

        w.forEach { assertTrue("列宽 $it 小于最小值", it >= 72.dp) }
    }

    /** 行数不一致（短行）不能崩，也不能少算列。 */
    @Test
    fun `参差不齐的行也能算`() {
        val w = widths(
            listOf("A", "B", "C"),
            listOf(listOf("1"), listOf("1", "2", "3")),
        )

        assertEquals(3, w.size)
    }

    /** 刚好放得下时不出现负宽度或异常值。 */
    @Test
    fun `刚好放得下时不出现负宽度`() {
        val tight = 72.dp + 72.dp + 0.5.dp
        val w = tableColumnWidths(listOf("a", "b"), emptyList(), 2, tight, fontSize)

        w.forEach { assertTrue("出现负宽度或异常值：$it", it.value > 0f) }
    }
}
