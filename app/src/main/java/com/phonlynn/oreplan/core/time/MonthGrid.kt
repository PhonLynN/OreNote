package com.phonlynn.oreplan.core.time

import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历月视图的网格与它所需的议程区间。纯函数，便于把边界钉在单测里。
 *
 * 为什么要把这两件事放在一起算：网格是「月初所在周的周一起，铺满整周」，所以**末行会落到
 * 下个月**。议程区间如果只取到当月最后一天，网格末尾那几天就查不到任何数据 ——
 * 症状是月视图最后一行永远没有密度点，看起来像「那几天没安排」。
 *
 * 之前这两段计算散在 ViewModel 里、且区间先算、网格后算，顺序上就不可能互相参照，
 * 于是这个遗漏一直没有被任何测试拦住（实测 2026 年前 9 个月里有 7 个月中招）。
 */
object MonthGrid {

    data class Grid(
        /** 网格第一格（月初所在周的周一）。 */
        val start: LocalDate,
        /** 网格全部格子，长度是 7 的倍数。 */
        val days: List<LocalDate>,
        /** 渲染这个网格所需的议程区间（含端点）。 */
        val rangeStart: LocalDate,
        val rangeEnd: LocalDate,
    )

    /**
     * @param month 要显示的月份
     * @param selected 当前选中日（周视图那一行要显示它所在的一整周，所以区间也要把它算进来）
     */
    fun of(month: YearMonth, selected: LocalDate): Grid {
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()

        // 网格：从月初所在周的周一铺到覆盖整月所需的整周（末行可能落到下个月）
        val gridStart = TermClock.weekStartOf(monthStart)
        val offset = monthStart.dayOfWeek.value - 1
        val rows = (offset + month.lengthOfMonth() + 6) / 7
        val days = (0 until (rows * 7)).map { gridStart.plusDays(it.toLong()) }

        // 周视图那一行
        val weekStart = TermClock.weekStartOf(selected)
        val weekEnd = weekStart.plusDays(6)

        return Grid(
            start = gridStart,
            days = days,
            // 三个下界里取最小、三个上界里取最大：少算任何一边都会有「界面画了但没有数据」的格子
            rangeStart = minOf(monthStart, weekStart, gridStart),
            rangeEnd = maxOf(monthEnd, weekEnd, days.last()),
        )
    }
}
