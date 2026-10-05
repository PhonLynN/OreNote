package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.model.CourseInstance
import com.phonlynn.oreplan.domain.model.CourseSession
import java.time.LocalDate

/**
 * 把课表模板展开成区间内的每一次实际上课。
 *
 * 这是纯函数：没有数据库、没有注入，输入输出都只有值。课表逻辑最容易出错的就是
 * 「周次边界」和「单双周」，把它做成纯函数是为了能用普通单元测试把边界钉死。
 *
 * 学期范围之外的请求会被自动裁掉，所以调用方（比如日历翻到寒假）不需要自己判断学期长度。
 */
object TimetableExpander {

    fun expand(
        termStart: LocalDate,
        totalWeeks: Int,
        sessions: List<CourseSession>,
        from: LocalDate,
        to: LocalDate,
    ): List<CourseInstance> {
        if (sessions.isEmpty() || to < from) return emptyList()

        val termEnd = TermClock.termEndDate(termStart, totalWeeks)
        val rangeStart = maxOf(from, termStart)
        val rangeEnd = minOf(to, termEnd)
        if (rangeStart > rangeEnd) return emptyList()

        val firstWeek = TermClock.weekNumberOn(termStart, totalWeeks, rangeStart) ?: return emptyList()

        val instances = mutableListOf<CourseInstance>()
        for (week in firstWeek..totalWeeks) {
            val weekStart = TermClock.weekStartDate(termStart, week)
            if (weekStart > rangeEnd) break

            for (session in sessions) {
                if (week < session.startWeek || week > session.endWeek) continue
                if (!session.parity.matches(week)) continue

                val date = TermClock.dateOf(termStart, week, session.dayOfWeek)
                if (date < rangeStart || date > rangeEnd) continue

                instances += CourseInstance(
                    sessionId = session.id,
                    courseId = session.courseId,
                    date = date,
                    weekNumber = week,
                    startMinuteOfDay = session.startMinuteOfDay,
                    endMinuteOfDay = session.endMinuteOfDay,
                    location = session.location,
                )
            }
        }

        // 同一天内按开始时间排，同一时间再按课程区分，保证顺序稳定可测。
        instances.sortWith(compareBy({ it.date }, { it.startMinuteOfDay }, { it.courseId }))
        return instances
    }

    /** 展开某一周的上课。周次越界返回空表。 */
    fun expandWeek(
        termStart: LocalDate,
        totalWeeks: Int,
        sessions: List<CourseSession>,
        weekNumber: Int,
    ): List<CourseInstance> {
        if (weekNumber !in 1..totalWeeks) return emptyList()
        return expand(
            termStart = termStart,
            totalWeeks = totalWeeks,
            sessions = sessions,
            from = TermClock.weekStartDate(termStart, weekNumber),
            to = TermClock.weekEndDate(termStart, weekNumber),
        )
    }

    /** 展开今天。 */
    fun expandDay(
        termStart: LocalDate,
        totalWeeks: Int,
        sessions: List<CourseSession>,
        date: LocalDate,
    ): List<CourseInstance> = expand(
        termStart = termStart,
        totalWeeks = totalWeeks,
        sessions = sessions,
        from = date,
        to = date,
    )
}
