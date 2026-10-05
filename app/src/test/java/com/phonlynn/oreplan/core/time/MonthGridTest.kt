package com.phonlynn.oreplan.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月视图网格与议程区间。
 *
 * 这里最要紧的一条是 [议程区间必须覆盖网格的每一天]：区间少算一天，那一格就永远没有密度点，
 * 而界面上看不出是「没数据」还是「没安排」。重构前这条不成立 —— 实测 2026 年前 9 个月里
 * 有 7 个月末行落在区间之外。
 */
class MonthGridTest {

    @Test
    fun `网格从周一开始 长度是整周 且包住整个月`() {
        val month = YearMonth.of(2026, 9)
        val grid = MonthGrid.of(month, month.atDay(15))

        assertEquals(DayOfWeek.MONDAY, grid.days.first().dayOfWeek)
        assertEquals(0, grid.days.size % 7)
        assertTrue("网格要包住月初", grid.days.contains(month.atDay(1)))
        assertTrue("网格要包住月末", grid.days.contains(month.atEndOfMonth()))
        assertEquals(grid.days.first(), grid.start)
    }

    @Test
    fun `网格天数随月份长短与起始星期变化`() {
        // 2026-02 是 28 天、从周日开始 → 需要 5 行（前 6 天属于 1 月）
        assertEquals(5 * 7, MonthGrid.of(YearMonth.of(2026, 2), LocalDate.of(2026, 2, 10)).days.size)
        // 2026-08 是 31 天、从周六开始 → 需要 6 行
        assertEquals(6 * 7, MonthGrid.of(YearMonth.of(2026, 8), LocalDate.of(2026, 8, 10)).days.size)
    }

    /**
     * 核心不变量：渲染出来的每一格都必须落在议程区间内。
     *
     * 穷举多年 × 每月 × 多个选中日，因为「区间差一天」只在特定月份才会出现，
     * 手工挑几个用例是挑不出来的。
     */
    @Test
    fun `议程区间必须覆盖网格的每一天`() {
        for (year in 2024..2028) {
            for (monthValue in 1..12) {
                val month = YearMonth.of(year, monthValue)
                val selectedDays = listOf(month.atDay(1), month.atDay(15), month.atEndOfMonth())

                selectedDays.forEach { selected ->
                    val grid = MonthGrid.of(month, selected)
                    val where = "$month / 选中 $selected"

                    assertTrue(
                        "$where：网格首格 ${grid.days.first()} 在区间起点 ${grid.rangeStart} 之前",
                        !grid.days.first().isBefore(grid.rangeStart),
                    )
                    assertTrue(
                        "$where：网格末格 ${grid.days.last()} 在区间终点 ${grid.rangeEnd} 之后",
                        !grid.days.last().isAfter(grid.rangeEnd),
                    )
                }
            }
        }
    }

    @Test
    fun `区间也要覆盖周视图那一行`() {
        for (year in 2024..2028) {
            for (monthValue in 1..12) {
                val month = YearMonth.of(year, monthValue)
                val selected = month.atDay(15)
                val grid = MonthGrid.of(month, selected)

                val weekStart = TermClock.weekStartOf(selected)
                assertTrue("周视图首日被挡在区间外", !weekStart.isBefore(grid.rangeStart))
                assertTrue("周视图末日被挡在区间外", !weekStart.plusDays(6).isAfter(grid.rangeEnd))
            }
        }
    }
}
