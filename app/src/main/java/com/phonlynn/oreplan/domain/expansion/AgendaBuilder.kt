package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Agenda
import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.ChecklistCount
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.Term
import java.time.LocalDate
import java.time.ZoneId

/**
 * 把「课表模板 + 条目」聚合成按天分组的议程 —— 今日页、周视图、月视图的密度标记
 * 全都吃这一个输出。
 *
 * 纯函数：不碰数据库，所有输入都由调用方给。这么做是因为聚合规则里有不少边界
 * （跨天、无结束时刻、到期与已排期重复、取消状态），这些边界必须能用普通单元测试钉住。
 */
object AgendaBuilder {

    /** 只填了开始时间、没填结束时间时的显示时长。不写死成 0，否则时间轴上会出现看不见的条。 */
    const val DEFAULT_DURATION_MINUTES = 30

    private const val MINUTES_PER_DAY = 24 * 60

    fun build(
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        items: List<Item>,
        exceptionsByItem: Map<String, List<RecurrenceException>>,
        term: Term?,
        sessions: List<CourseSession>,
        courses: List<Course>,
        today: LocalDate = from,
        checklistCounts: Map<String, ChecklistCount> = emptyMap(),
    ): Agenda {
        if (to < from) return Agenda.Empty

        /** 该条目的备忘清单进度。没建清单就是 null，界面不画徒标。 */
        fun countOf(itemId: String?): ChecklistCount? = itemId?.let { checklistCounts[it] }

        /**
         * 条目创建时刻的当地分钟数。
         *
         * 列表行用它显示「创建于 HH:mm」而不是具体时刻：时刻已经由时间轴上的位置表达了，
         * 行里再写一遍是同一件事说两遍。
         */
        fun createdMinuteOf(item: Item): Int =
            item.createdAt.atZone(zone).let { it.hour * 60 + it.minute }

        val days = mutableMapOf<LocalDate, MutableList<AgendaEntry>>()
        fun add(date: LocalDate, entry: AgendaEntry) {
            if (date < from || date > to) return
            days.getOrPut(date) { mutableListOf() } += entry
        }

        // ---- 课程：来自课表模板层，实时展开而不是查一堆日程 ----
        if (term != null && sessions.isNotEmpty()) {
            val courseById = courses.associateBy { it.id }
            TimetableExpander
                .expand(term.startDate, term.totalWeeks, sessions, from, to)
                .forEach { instance ->
                    val course = courseById[instance.courseId]
                    add(
                        instance.date,
                        AgendaEntry(
                            key = "course:${instance.sessionId}:${instance.date}",
                            source = AgendaSource.COURSE,
                            date = instance.date,
                            title = course?.name ?: "（课程已删除）",
                            location = instance.location ?: course?.defaultLocation,
                            startMinute = instance.startMinuteOfDay,
                            endMinute = instance.endMinuteOfDay,
                            // 课程色优先用课程自带的，没有就用课程 id 作稳定取色种子
                            colorToken = course?.colorHex ?: course?.id,
                            sessionId = instance.sessionId,
                            courseId = instance.courseId,
                            weekNumber = instance.weekNumber,
                        ),
                    )
                }
        }

        // ---- 条目：日程展开成具体发生；无时限待办按期望完成期浮出 ----
        items.forEach { item ->
            if (item.status == ItemStatus.CANCELLED) return@forEach
            // 容器（目标 / 工作区 / 待办组）自己从不占时间轴；子节点各自按自己的时间规则浮出。
            // 习惯提醒载体是不可见的后台条目，只在提醒调度器里起作用。
            if (item.kind.isContainer) return@forEach
            if (item.kind == ItemKind.HABIT_ALARM) return@forEach

            if (item.startAt != null) {
                OccurrenceExpander
                    .expand(item, exceptionsByItem[item.id].orEmpty(), from, to, zone)
                    .forEach { occurrence ->
                        val startLocal = occurrence.startAt.atZone(zone)
                        val startMinute = startLocal.hour * 60 + startLocal.minute
                        val rawEnd = occurrence.endAt?.atZone(zone)?.let { it.hour * 60 + it.minute }
                        add(
                            occurrence.originalDate,
                            AgendaEntry(
                                key = "item:${item.id}:${occurrence.originalDate}",
                                source = AgendaSource.ITEM,
                                date = occurrence.originalDate,
                                title = occurrence.title,
                                note = item.note,
                                location = item.location,
                                startMinute = startMinute,
                                // 没有结束时刻 = **点日程**（「12:00 交表」这类）。保持为 null，
                                // 时间轴会把它画成一根横线；以前这里会补上 30 分钟默认时长，
                                // 于是点日程被画成一段半小时的块，与真实语义不符。
                                endMinute = rawEnd?.let { normaliseEnd(startMinute, it) },
                                allDay = item.allDay,
                                colorToken = item.colorTag ?: item.id,
                                itemId = item.id,
                                occurrenceDate = if (item.isRecurring) occurrence.originalDate else null,
                                isOverride = occurrence.isOverride,
                                itemStatus = item.status,
                                itemKind = item.kind,
                                checklistCount = countOf(item.id),
                                createdMinute = createdMinuteOf(item),
                                orderIndex = item.orderIndex,
                                createdAtMillis = item.createdAt.toEpochMilli(),
                                priority = item.priority,
                            ),
                        )
                    }
            } else if (item.kind == ItemKind.TASK && item.softDueAt != null) {
                // 只有「没有具体时刻」的待办才按期望完成期浮出。
                // 已经排到时间轴上的条目再浮一次会在同一天重复出现两条。
                val dueDate = item.softDueAt.atZone(zone).toLocalDate()
                add(
                    dueDate,
                    AgendaEntry(
                        key = "due:${item.id}",
                        source = AgendaSource.ITEM,
                        date = dueDate,
                        title = item.title,
                        note = item.note,
                        startMinute = null,
                        endMinute = null,
                        colorToken = item.colorTag ?: item.id,
                        itemId = item.id,
                        itemStatus = item.status,
                        itemKind = item.kind,
                        checklistCount = countOf(item.id),
                        createdMinute = createdMinuteOf(item),
                        orderIndex = item.orderIndex,
                        createdAtMillis = item.createdAt.toEpochMilli(),
                        dueAtMillis = item.softDueAt?.toEpochMilli(),
                        priority = item.priority,

                    ),
                )
            }
        }

        // ---- 待办：全部没做完的，不分日期 ----
        //
        // 不过滤到期日：待办是不限时、随想随做的东西，如果只在自己到期那天出现，
        // 那么「没填期望完成期」的待办永远不会出现在任何一个视图里（用户刚添加完
        // 看不到任何变化，只能以为没存上）。待办统一在今日页呈现，日期信息用行内小字表达。
        val openTasks = items
            .asSequence()
            .filter { it.kind == ItemKind.TASK && it.startAt == null }
            .filter { it.status != ItemStatus.CANCELLED }
            // 已完成的只在完成当天留着：刚勾完就从列表里消失会让人怀疑没勾上
            .filter { item ->
                item.status != ItemStatus.DONE ||
                    item.completedAt?.atZone(zone)?.toLocalDate() == today
            }
            .sortedWith(compareBy({ it.orderIndex }, { it.createdAt }, { it.title }))
            .map { item ->
                val due = item.softDueAt?.atZone(zone)?.toLocalDate()
                AgendaEntry(
                    key = "task:${item.id}",
                    source = AgendaSource.ITEM,
                    date = due ?: today,
                    title = item.title,
                    note = item.note,
                    startMinute = null,
                    endMinute = null,
                    colorToken = item.colorTag ?: item.id,
                    itemId = item.id,
                    itemStatus = item.status,
                    itemKind = item.kind,
                    groupId = item.groupId,
                    checklistCount = countOf(item.id),
                    createdMinute = createdMinuteOf(item),
                    createdAtMillis = item.createdAt.toEpochMilli(),
                    dueDate = due,
                    dueAtMillis = item.softDueAt?.toEpochMilli(),
                    priority = item.priority,

                )
            }
            .toList()

        // 有具体时刻的按时间排，没有时刻的排在最后 —— 眼睛先扫时间轴，再看零散待办。
        // 无时刻的那一段按**手动排序键**排（它们没有时间可排，顺序只能来自用户拖动）；
        // 以前这一段是按标题排的，于是「拖动排序」在日历的当天清单里根本无从体现。
        val sorted = days.mapValues { (_, entries) ->
            entries.sortedWith(
                compareBy(
                    { it.startMinute ?: Int.MAX_VALUE },
                    { it.endMinute ?: Int.MAX_VALUE },
                    { if (it.startMinute == null) it.orderIndex else 0.0 },
                    { it.title },
                ),
            )
        }
        return Agenda(
            sorted,
            hasActiveTerm = term != null,
            openTasks = openTasks,
            // 待办组：待办页分组展示用。按每层自己的 orderIndex 无法在这里排（树还没建），
            // 所以这里只保证「确定性顺序」；真正的同级顺序在 TodoTree 里按 orderIndex 排。
            todoGroups = items
                .filter { it.kind == ItemKind.TODO_GROUP && it.status != ItemStatus.CANCELLED }
                .sortedWith(compareBy({ it.orderIndex }, { it.createdAt }, { it.title })),
        )
    }

    /**
     * 结束分钟的兜底与裁剪。
     *  - 结束不晚于开始（脏数据）→ 给一个默认时长，否则时间轴上会出现零高度、看不见的条；
     *  - 跨过午夜 → 裁到当天 24:00，多出来的部分属于第二天，不该在这一天里画出去。
     */
    private fun normaliseEnd(startMinute: Int, rawEnd: Int?): Int {
        if (rawEnd == null || rawEnd <= startMinute) {
            return (startMinute + DEFAULT_DURATION_MINUTES).coerceAtMost(MINUTES_PER_DAY)
        }
        return rawEnd.coerceAtMost(MINUTES_PER_DAY)
    }
}
