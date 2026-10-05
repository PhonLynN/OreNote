package com.phonlynn.oreplan.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 学期周次计算。所有「第几周」的显示与课表展开都从这里出。
 *
 * **约定：学期的 startDate 就是第 1 周的周一。** 这个约定必须固定死，
 * 否则「第 3 周周三」到底指哪一天就有二义，而且这种二义会在整个项目里扩散。
 *
 * 这里不存「第几周」这个结果 —— 它永远是从学期开始日期算出来的。
 * 存出来的话，改一次学期设置就要批量改数据，而且跨假期必然算错。
 */
object TermClock {

    /**
     * [date] 落在学期的第几周（1 起）。不在学期范围内返回 null。
     *
     * 注意「不在范围内」和「第 0 周」是两件事：开学前一天必须返回 null 而不是 1 或 0。
     */
    fun weekNumberOn(startDate: LocalDate, totalWeeks: Int, date: LocalDate): Int? {
        val daysFromStart = ChronoUnit.DAYS.between(startDate, date)
        if (daysFromStart < 0) return null
        val week = (daysFromStart / 7).toInt() + 1
        return if (week in 1..totalWeeks) week else null
    }

    /** 第 [weekNumber] 周的周一。[weekNumber] 从 1 起。 */
    fun weekStartDate(startDate: LocalDate, weekNumber: Int): LocalDate {
        require(weekNumber >= 1) { "weekNumber 从 1 起，实际 $weekNumber" }
        return startDate.plusWeeks((weekNumber - 1).toLong())
    }

    /** 第 [weekNumber] 周的周日。 */
    fun weekEndDate(startDate: LocalDate, weekNumber: Int): LocalDate =
        weekStartDate(startDate, weekNumber).plusDays(6)

    /**
     * 第 [weekNumber] 周里某个星期几的日期。
     * [dayOfWeek] 用 ISO 约定：1=周一 … 7=周日，与 `CourseSession.dayOfWeek` 一致。
     */
    fun dateOf(startDate: LocalDate, weekNumber: Int, dayOfWeek: Int): LocalDate {
        require(dayOfWeek in 1..7) { "dayOfWeek 必须在 1..7，实际 $dayOfWeek" }
        return weekStartDate(startDate, weekNumber).plusDays((dayOfWeek - 1).toLong())
    }

    /** 学期最后一天（第 [totalWeeks] 周的周日）。 */
    fun termEndDate(startDate: LocalDate, totalWeeks: Int): LocalDate =
        weekEndDate(startDate, totalWeeks)

    /** [date] 所在周的周一。 */
    fun weekStartOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    /** 与 `CourseSession.dayOfWeek` 对齐的星期序号（1=周一 … 7=周日）。 */
    fun dayOfWeekValue(date: LocalDate): Int = date.dayOfWeek.value

    /** 便于测试与显示：星期几的中文单字。 */
    fun weekdayLabel(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "一"
        DayOfWeek.TUESDAY -> "二"
        DayOfWeek.WEDNESDAY -> "三"
        DayOfWeek.THURSDAY -> "四"
        DayOfWeek.FRIDAY -> "五"
        DayOfWeek.SATURDAY -> "六"
        DayOfWeek.SUNDAY -> "日"
    }
}
