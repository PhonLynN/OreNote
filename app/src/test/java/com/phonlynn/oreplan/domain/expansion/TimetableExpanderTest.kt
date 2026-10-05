package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.core.time.WeekParity
import com.phonlynn.oreplan.domain.model.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 课表展开的边界。这里最容易错的是「周次含不含端点」和「单双周与起止周叠加」。
 */
class TimetableExpanderTest {

    /** 2026-09-07 是周一。第 1 周 = 9/7-9/13，第 2 周 = 9/14-9/20，第 3 周 = 9/21-9/27。 */
    private val termStart = LocalDate.of(2026, 9, 7)
    private val totalWeeks = 18

    private fun session(
        id: String = "s1",
        courseId: String = "c1",
        dayOfWeek: Int = 1,
        startMinute: Int = 8 * 60,
        endMinute: Int = 9 * 60 + 40,
        startWeek: Int = 1,
        endWeek: Int = Int.MAX_VALUE,
        parity: WeekParity = WeekParity.ALL,
    ) = CourseSession(
        id = id,
        courseId = courseId,
        dayOfWeek = dayOfWeek,
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
        startWeek = startWeek,
        endWeek = endWeek,
        parity = parity,
    )

    private fun expand(sessions: List<CourseSession>, from: LocalDate, to: LocalDate) =
        TimetableExpander.expand(termStart, totalWeeks, sessions, from, to)

    @Test
    fun `每周一节周一课 三周内出现三次`() {
        val result = expand(listOf(session()), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 27))
        assertEquals(3, result.size)
        assertEquals(
            listOf(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21)),
            result.map { it.date },
        )
    }

    @Test
    fun `单周课只在奇数周出现`() {
        val result = expand(
            listOf(session(parity = WeekParity.ODD)),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 27),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 21)),
            result.map { it.date },
        )
    }

    @Test
    fun `双周课只在偶数周出现`() {
        val result = expand(
            listOf(session(parity = WeekParity.EVEN)),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 27),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 14)), result.map { it.date })
    }

    @Test
    fun `起止周含端点`() {
        val onlyWeekTwo = expand(
            listOf(session(startWeek = 2, endWeek = 2)),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 27),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 14)), onlyWeekTwo.map { it.date })
    }

    @Test
    fun `单双周与起止周叠加 两者都要满足`() {
        // 第 2..6 周之间的单周 => 第 3 周(9/21) 与第 5 周(10/5)
        val result = expand(
            listOf(session(startWeek = 2, endWeek = 6, parity = WeekParity.ODD)),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 10, 11),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 5)),
            result.map { it.date },
        )
    }

    @Test
    fun `周日课落在本周最后一天`() {
        val result = expand(
            listOf(session(dayOfWeek = 7)),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 13),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 13)), result.map { it.date })
    }

    @Test
    fun `查询开学之前的区间返回空`() {
        val result = expand(listOf(session()), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 6))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `查询跨到学期之后 结果被裁到学期末`() {
        val result = expand(listOf(session()), LocalDate.of(2026, 9, 7), LocalDate.of(2027, 6, 1))
        // 18 周就是 18 个周一，最后一个周一是 2027-01-04（学期最后一天 01-10 是周日）
        assertEquals(totalWeeks, result.size)
        assertEquals(LocalDate.of(2027, 1, 4), result.last().date)
    }

    @Test
    fun `区间端点在周中间时 只返回落在区间内的那几天`() {
        val result = expand(
            listOf(session(dayOfWeek = 1), session(id = "s2", courseId = "c2", dayOfWeek = 5)),
            LocalDate.of(2026, 9, 9),
            LocalDate.of(2026, 9, 11),
        )
        // 9/9 是周三、9/11 是周五 => 只命中周五那节
        assertEquals(listOf(LocalDate.of(2026, 9, 11)), result.map { it.date })
    }

    @Test
    fun `同一天多节课按开始时间排序`() {
        val result = expand(
            listOf(
                session(id = "late", courseId = "c2", startMinute = 14 * 60, endMinute = 15 * 60),
                session(id = "early", courseId = "c1", startMinute = 8 * 60, endMinute = 9 * 60),
            ),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 7),
        )
        assertEquals(listOf("early", "late"), result.map { it.sessionId })
    }

    @Test
    fun `没有课程时返回空`() {
        assertTrue(expand(emptyList(), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 27)).isEmpty())
    }

    @Test
    fun `区间反向时返回空而不是抛错`() {
        assertTrue(
            expand(listOf(session()), LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 7)).isEmpty(),
        )
    }

    @Test
    fun `expandWeek 按周次取课`() {
        val second = TimetableExpander.expandWeek(
            termStart,
            totalWeeks,
            listOf(session()),
            weekNumber = 2,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 14)), second.map { it.date })
    }

    @Test
    fun `expandWeek 周次越界返回空`() {
        assertTrue(TimetableExpander.expandWeek(termStart, totalWeeks, listOf(session()), 0).isEmpty())
        assertTrue(TimetableExpander.expandWeek(termStart, totalWeeks, listOf(session()), 19).isEmpty())
    }

    @Test
    fun `展开结果带回周次与课程 id`() {
        val result = expand(
            listOf(session(courseId = "math")),
            LocalDate.of(2026, 9, 14),
            LocalDate.of(2026, 9, 14),
        )
        val instance = result.single()
        assertEquals("math", instance.courseId)
        assertEquals(2, instance.weekNumber)
        assertEquals(8 * 60, instance.startMinuteOfDay)
    }
}
