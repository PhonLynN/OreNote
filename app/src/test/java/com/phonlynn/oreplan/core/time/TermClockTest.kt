package com.phonlynn.oreplan.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 周次计算的边界。这是整个课表功能的根 —— 这里错一天，整学期的课都会错位。
 */
class TermClockTest {

    /** 2026-09-07 是周一，作为「第 1 周周一」。 */
    private val termStart = LocalDate.of(2026, 9, 7)
    private val totalWeeks = 18

    @Test
    fun `开学当天就是第 1 周`() {
        assertEquals(1, TermClock.weekNumberOn(termStart, totalWeeks, termStart))
    }

    @Test
    fun `第 1 周的周日仍是第 1 周`() {
        assertEquals(1, TermClock.weekNumberOn(termStart, totalWeeks, termStart.plusDays(6)))
    }

    @Test
    fun `第 8 天进入第 2 周`() {
        assertEquals(2, TermClock.weekNumberOn(termStart, totalWeeks, termStart.plusDays(7)))
    }

    @Test
    fun `开学前一天返回 null 而不是 0 或 1`() {
        assertNull(TermClock.weekNumberOn(termStart, totalWeeks, termStart.minusDays(1)))
    }

    @Test
    fun `学期最后一天仍算最后一周`() {
        val lastDay = TermClock.termEndDate(termStart, totalWeeks)
        assertEquals(totalWeeks, TermClock.weekNumberOn(termStart, totalWeeks, lastDay))
    }

    @Test
    fun `学期结束后一天返回 null`() {
        val afterTerm = TermClock.termEndDate(termStart, totalWeeks).plusDays(1)
        assertNull(TermClock.weekNumberOn(termStart, totalWeeks, afterTerm))
    }

    @Test
    fun `dateOf 第 1 周周一是开学当天`() {
        assertEquals(termStart, TermClock.dateOf(termStart, weekNumber = 1, dayOfWeek = 1))
    }

    @Test
    fun `dateOf 第 1 周周日是开学后第 6 天`() {
        assertEquals(termStart.plusDays(6), TermClock.dateOf(termStart, weekNumber = 1, dayOfWeek = 7))
    }

    @Test
    fun `dateOf 第 3 周周三是第 1 周周三往后推两周`() {
        val expected = LocalDate.of(2026, 9, 23)
        assertEquals(DayOfWeek.WEDNESDAY, expected.dayOfWeek)
        assertEquals(expected, TermClock.dateOf(termStart, weekNumber = 3, dayOfWeek = 3))
    }

    @Test
    fun `weekStartOf 把周内任意一天折回本周周一`() {
        val wednesday = LocalDate.of(2026, 9, 23)
        assertEquals(LocalDate.of(2026, 9, 21), TermClock.weekStartOf(wednesday))
    }

    @Test
    fun `weekStartOf 对周一本身不变`() {
        assertEquals(termStart, TermClock.weekStartOf(termStart))
    }

    @Test
    fun `星期序号与 ISO 对齐 周一是 1`() {
        assertEquals(1, TermClock.dayOfWeekValue(termStart))
        assertEquals(7, TermClock.dayOfWeekValue(termStart.plusDays(6)))
    }

    @Test
    fun `weekStartDate 对非法周次直接抛错而不是静默返回错日期`() {
        val failure = runCatching { TermClock.weekStartDate(termStart, 0) }
        assertEquals(true, failure.isFailure)
    }
}
