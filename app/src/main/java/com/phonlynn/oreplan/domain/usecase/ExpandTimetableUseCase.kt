package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.expansion.TimetableExpander
import com.phonlynn.oreplan.domain.model.CourseInstance
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import java.time.LocalDate

/**
 * 取区间内的课程。
 *
 * 之所以要有这个用例、而不是让界面直接调两个仓储：**「第几周」和「单双周」的判断
 * 必须只有一处实现**。界面只关心「给我这段时间有哪些课」，学期长度、单双周、起止周
 * 全部在这里消化掉。
 */
class ExpandTimetableUseCase @Inject constructor(
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
) {

    /**
     * 观察区间内的课程。学期设置或课表一变就自动重算 ——
     * 改完课表不需要手动通知界面刷新。
     */
    fun observe(from: LocalDate, to: LocalDate): Flow<List<CourseInstance>> =
        combine(
            termRepository.observeActiveTerm(),
            courseRepository.observeSessions(),
        ) { term, sessions ->
            if (term == null) {
                emptyList()
            } else {
                TimetableExpander.expand(
                    termStart = term.startDate,
                    totalWeeks = term.totalWeeks,
                    sessions = sessions,
                    from = from,
                    to = to,
                )
            }
        }

    /** 一次性取数，给不需要持续观察的场景（导出、小组件的一次性刷新）用。 */
    suspend fun expand(from: LocalDate, to: LocalDate): List<CourseInstance> {
        val term = termRepository.getActiveTerm() ?: return emptyList()
        return TimetableExpander.expand(
            termStart = term.startDate,
            totalWeeks = term.totalWeeks,
            sessions = courseRepository.getSessions(),
            from = from,
            to = to,
        )
    }

    /** 取某一天所在周的周一与周日，界面用来定位「本周」区间。 */
    fun currentWeekRange(anchor: LocalDate): ClosedRange<LocalDate> =
        TermClock.weekStartOf(anchor)..TermClock.weekStartOf(anchor).plusDays(6)
}
