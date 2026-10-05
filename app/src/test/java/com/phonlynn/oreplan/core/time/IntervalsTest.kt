package com.phonlynn.oreplan.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class IntervalsTest {

    @Test
    fun `首尾相接不算重叠`() {
        val first = Interval(600, 660)   // 10:00 - 11:00
        val second = Interval(660, 720)  // 11:00 - 12:00
        assertFalse(first.overlaps(second))
        assertFalse(second.overlaps(first))
    }

    @Test
    fun `真正相交算重叠且对称`() {
        val first = Interval(600, 660)
        val second = Interval(630, 700)
        assertTrue(first.overlaps(second))
        assertTrue(second.overlaps(first))
    }

    @Test
    fun `包含关系算重叠`() {
        val outer = Interval(540, 720)
        val inner = Interval(600, 660)
        assertTrue(outer.overlaps(inner))
        assertTrue(inner.overlaps(outer))
    }

    @Test
    fun `完全错开不重叠`() {
        assertFalse(Interval(540, 600).overlaps(Interval(660, 720)))
    }

    @Test
    fun `零长度区间不与其他区间重叠`() {
        // 零长度出现在「日程只填了开始时间」的情况下，它不应该挤占别人的横向空间。
        assertFalse(Interval(600, 600).overlaps(Interval(590, 610)))
    }

    @Test
    fun `分组把连通的重叠串成一组`() {
        val a = Interval(600, 660)
        val b = Interval(650, 700)   // 与 a 重叠
        val c = Interval(690, 720)   // 与 b 重叠、与 a 不重叠，但通过 b 连通
        val d = Interval(780, 840)   // 完全独立
        val groups = Intervals.groupOverlapping(listOf(a, b, c, d))
        assertEquals(2, groups.size)
        assertEquals(listOf(a, b, c), groups[0])
        assertEquals(listOf(d), groups[1])
    }

    @Test
    fun `分组输入乱序也会按开始时间排好`() {
        val a = Interval(600, 660)
        val b = Interval(620, 700)
        val groups = Intervals.groupOverlapping(listOf(b, a))
        assertEquals(listOf(a, b), groups.single())
    }

    @Test
    fun `空输入返回空分组`() {
        assertEquals(emptyList<List<Interval>>(), Intervals.groupOverlapping(emptyList()))
    }

    @Test
    fun `并排摆放时重叠的区间分到不同列`() {
        val a = Interval(600, 660)
        val b = Interval(620, 700)
        val columns = Intervals.assignColumns(Intervals.groupOverlapping(listOf(a, b)).single())
        assertEquals(2, columns.getValue(a).second)
        assertEquals(2, columns.getValue(b).second)
        assertFalse(columns.getValue(a).first == columns.getValue(b).first)
    }

    @Test
    fun `不重叠的区间复用同一列`() {
        val a = Interval(600, 660)
        val b = Interval(660, 700)
        val columns = Intervals.assignColumns(listOf(a, b))
        assertEquals(columns.getValue(a).first, columns.getValue(b).first)
        assertEquals(1, columns.getValue(a).second)
    }

    @Test
    fun `分钟与 LocalTime 可以互相换算`() {
        assertEquals(600, Intervals.minuteOfDay(LocalTime.of(10, 0)))
        assertEquals(LocalTime.of(10, 0), Intervals.toLocalTime(600))
    }

    @Test
    fun `布局按索引返回 时间完全相同的两个区间各占一列`() {
        val items = listOf(
            Interval(600, 660),
            Interval(600, 660),
            Interval(660, 700),
        )
        val spans = Intervals.layout(items)
        assertEquals(listOf(0, 1, 2), spans.map { it.index })
        assertEquals(2, spans[0].columns)
        assertEquals(2, spans[1].columns)
        // 两个同时间的不允许在同一列
        assertEquals(false, spans[0].column == spans[1].column)
        // 不重叠的第三个可以复用第一列，单独一组
        assertEquals(1, spans[2].columns)
    }

    @Test
    fun `布局对空输入返回空`() {
        assertEquals(emptyList<Intervals.ColumnSpan>(), Intervals.layout(emptyList()))
    }

    @Test
    fun `布局不会因为输入顺序而改变结果`() {
        val a = Interval(600, 660)
        val b = Interval(620, 700)
        val c = Interval(690, 730)
        val forward = Intervals.layout(listOf(a, b, c))
        val backward = Intervals.layout(listOf(c, b, a))
        assertEquals(forward[0], backward[0])
        assertEquals(forward[1], backward[1])
        assertEquals(forward[2], backward[2])
    }

    @Test
    fun `布局把三个连通区间分到两列 因为是链式重叠而不是两两重叠`() {
        // a(600-660) 与 b(650-700) 重叠，b 与 c(690-740) 重叠，a 与 c 不重叠
        val spans = Intervals.layout(
            listOf(Interval(600, 660), Interval(650, 700), Interval(690, 740)),
        )
        assertTrue(spans.all { it.columns == 2 })
        assertEquals(spans[0].column, spans[2].column)
        assertEquals(false, spans[0].column == spans[1].column)
    }

    @Test
    fun `结束早于开始的区间构造时就报错`() {
        assertEquals(true, runCatching { Interval(660, 600) }.isFailure)
    }
}
