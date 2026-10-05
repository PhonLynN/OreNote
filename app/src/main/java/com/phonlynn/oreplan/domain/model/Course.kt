package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import com.phonlynn.oreplan.core.time.WeekParity
import java.time.LocalDate

/** 课程本身（名称、教师、颜色）。上课时间不在这里，在 [CourseSession]。 */
@Immutable
data class Course(
    val id: String,
    val name: String,
    val teacher: String? = null,
    val defaultLocation: String? = null,
    val colorHex: String? = null,
    val note: String? = null,
    /** 学分。0 = 未填。 */
    val credit: Double = 0.0,
)

/**
 * 一次上课安排 —— **课表的模板层，不是日程**。
 *
 * 关键设计：课表**不展开成一堆日程存进数据库**。存的是「星期几 + 起止时间 +
 * 起止周 + 单双周」，渲染时按查询范围实时展开。否则每次改课表都要批量改历史数据，
 * 而且「第几周」一旦被存下来，跨学期、跨假期必然算错。
 */
@Immutable
data class CourseSession(
    val id: String,
    val courseId: String,
    /** 1=周一 … 7=周日，与 ISO 一致。 */
    val dayOfWeek: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val startWeek: Int = 1,
    /** 默认到无穷：展开时会被学期总周数裁掉，所以不需要调用方知道学期长度。 */
    val endWeek: Int = Int.MAX_VALUE,
    val parity: WeekParity = WeekParity.ALL,
    val location: String? = null,
    val note: String? = null,
) {
    init {
        require(dayOfWeek in 1..7) { "dayOfWeek 必须在 1..7，实际 $dayOfWeek" }
        require(startMinuteOfDay in 0..24 * 60) { "startMinuteOfDay 越界：$startMinuteOfDay" }
        require(endMinuteOfDay in 0..24 * 60) { "endMinuteOfDay 越界：$endMinuteOfDay" }
        require(endMinuteOfDay > startMinuteOfDay) { "上课结束时间必须晚于开始时间" }
        require(startWeek >= 1) { "startWeek 从 1 起，实际 $startWeek" }
        require(endWeek >= startWeek) { "endWeek 不能早于 startWeek：$startWeek..$endWeek" }
    }

    val durationMinutes: Int get() = endMinuteOfDay - startMinuteOfDay
}

/**
 * 把模板展开到具体某一天之后得到的一次实际上课。
 * 这是**算出来的**，不落库。
 */
@Immutable
data class CourseInstance(
    val sessionId: String,
    val courseId: String,
    val date: LocalDate,
    val weekNumber: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val location: String?,
    /**
     * 课程自选颜色。展开器是纯函数、拿不到 [Course]，所以由调用方补上（见 `TimetableViewModel`）。
     * 为 null 时界面回退到用 [courseId] 哈希取色。
     */
    val colorHex: String? = null,
) {
    val durationMinutes: Int get() = endMinuteOfDay - startMinuteOfDay
}
