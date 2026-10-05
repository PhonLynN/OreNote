package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.domain.expansion.AgendaBuilder
import com.phonlynn.oreplan.domain.expansion.ChecklistSummary
import com.phonlynn.oreplan.domain.model.Agenda
import com.phonlynn.oreplan.domain.repository.ChecklistRepository
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 取一段时间内的议程（课程 + 条目合一）。
 *
 * 所有输入都用 Flow 组合，所以改课表、改条目、改学期、改重复例外、改备忘清单
 * 都会自动重算 —— 界面不需要知道「哪一步操作需要刷新什么」。
 *
 * 聚合逻辑在 [AgendaBuilder]（纯函数）。这里只负责把数据接起来。
 *
 * 「今天是哪天」在这里取（而不是让纯函数自己 `LocalDate.now()`）：逾期判定依赖今天，
 * 而纯函数里读系统时钟就没法用测试钉住跨天行为。
 */
class BuildAgendaUseCase @Inject constructor(
    private val itemRepository: ItemRepository,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val exceptionRepository: RecurrenceExceptionRepository,
    private val checklistRepository: ChecklistRepository,
) {

    fun observe(from: LocalDate, to: LocalDate, zone: ZoneId): Flow<Agenda> = combine(
        itemRepository.observeAll(),
        termRepository.observeActiveTerm(),
        courseRepository.observeSessions(),
        courseRepository.observeCourses(),
        // combine 的带类型重载最多五路，所以把「重复例外 + 备忘清单」先合成一路。
        // 直接凑六路就只能走 Array 版重载，现场全是强制类型转换。
        combine(
            exceptionRepository.observeAll(),
            checklistRepository.observeAll(),
        ) { exceptions, checklist -> exceptions to checklist },
    ) { items, term, sessions, courses, extras ->
        val (exceptions, checklist) = extras
        AgendaBuilder.build(
            from = from,
            to = to,
            zone = zone,
            items = items,
            exceptionsByItem = exceptions.groupBy { it.itemId },
            term = term,
            sessions = sessions,
            courses = courses,
            today = LocalDate.now(zone),
            checklistCounts = ChecklistSummary.counts(checklist),
        )
    }

    /** 一次性取数，给小组件、导出这类不需要持续观察的场景用。 */
    suspend fun build(from: LocalDate, to: LocalDate, zone: ZoneId): Agenda = AgendaBuilder.build(
        from = from,
        to = to,
        zone = zone,
        items = itemRepository.getAll(),
        exceptionsByItem = exceptionRepository.getAll().groupBy { it.itemId },
        term = termRepository.getActiveTerm(),
        sessions = courseRepository.getSessions(),
        courses = courseRepository.getCourses(),
        today = LocalDate.now(zone),
        checklistCounts = ChecklistSummary.counts(checklistRepository.getAll()),
    )
}
