package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/** 议程里一条时间块来自哪里。两种来源在时间轴上必须能一眼区分。 */
enum class AgendaSource {
    /** 来自课表模板层展开的课程。 */
    COURSE,

    /** 来自条目（日程 / 待办到期）。 */
    ITEM,
}

/**
 * 议程里的一条。**课程与日程在这里合流** —— 时间轴不需要知道它来自课表还是条目。
 *
 * [startMinute] / [endMinute] 是相对当天的分钟数。为 null 表示没有具体时刻
 * （无时限待办、或只有期望完成期），这类条目在界面上不进时间轴，单独列在下面。
 */
@Immutable
data class AgendaEntry(
    /** 稳定且唯一，用于列表 key 与动画。 */
    val key: String,
    val source: AgendaSource,
    val date: LocalDate,
    val title: String,
    val location: String? = null,
    /**
     * 条目备注。列表行要展示它，所以跟着议程一起传到界面，
     * 而不是让每一行回头去查一次库。
     */
    val note: String? = null,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
    val allDay: Boolean = false,
    /** 取色用的稳定标识：课程用课程色或课程 id，条目用条目 id。 */
    val colorToken: String? = null,
    val itemId: String? = null,
    val sessionId: String? = null,
    val courseId: String? = null,
    /** 重复日程「原规则会在哪一天发生」，编辑例外时用它定位；非重复日程为 null。 */
    val occurrenceDate: LocalDate? = null,
    val isOverride: Boolean = false,
    val weekNumber: Int? = null,
    val itemStatus: ItemStatus? = null,
    val itemKind: ItemKind? = null,
    /** 待办归属的待办组 id（仅 kind=TASK 且有归属时非空）。 */
    val groupId: String? = null,
    /**
     * 条目自己填的当地分钟数（0:00 起算），课程展开的条为空。
     *
     * 列表行展示它而不是具体时刻：时刻已经由时间轴上的位置表达了，
     * 再写一排「09:00-10:00」是同一件事说两遍。
     */
    val createdMinute: Int? = null,
    /**
     * 条目自己的手动排序键。课程没有这个概念，固定为 0。
     *
     * 界面靠它把**无时刻的条目**按用户拖出来的顺序排（见 [AgendaEntry.isTimed] 的区分）：
     * 有时刻的条目按时间排，无时刻的条目没有时间可排，只能用手动顺序。
     */
    val orderIndex: Double = 0.0,
    /**
     * 条目创建时刻（毫秒）。列表排序的次序比较键，与 `ReorderItemsUseCase` 里的全局顺序一致。
     *
     * 不能只靠 [orderIndex]：老数据里所有条目的排序键都是默认值 0，还必须有个稳定且两处
     * 一致的第二键，否则「界面看到的顺序」与「库里的顺序」会不一致，拖动落点就会错位。
     */
    val createdAtMillis: Long = 0,
    /**
     * 待办的期望完成期。null = 无期限（有空再做）。
     *
     * 与 [date] 分开：[date] 是「这条归在哪一天（用于分组与排序）」，
     * 而这里是用户真的填了的日期 —— 界面靠它显示「今天到期 / 明天到期 / 逾期 3 天」。
     */
    val dueDate: java.time.LocalDate? = null,
    /**
     * 该条目的备忘清单进度（已勾选 / 总数）。为 null 表示没建清单。
     * 跟着议程一起传下来，列表行就不必为每一行再查一次库。
     */
    val checklistCount: ChecklistCount? = null,
    /**
     * 到期时刻（毫秒）。与 [dueDate] 并存：[dueDate] 用来分组，
     * 这个用来显示「今天 22:00 前」「剩 6 小时」这类精确倒计时。
     */
    val dueAtMillis: Long? = null,
    /**
     * 条目优先级（0 无 / 1 低 / 2 中 / 3 高）。列表行用它决定「紧急 / 重要 / 普通」徽标。
     */
    val priority: Int = 0,
) {
    val isTimed: Boolean get() = startMinute != null

    val isCourse: Boolean get() = source == AgendaSource.COURSE
}

/** 一段日期范围内的议程，按天分组。 */
@Immutable
data class Agenda(
    val days: Map<LocalDate, List<AgendaEntry>>,
    /** 是否配置了活动学期。课表页与今日页的引导文案靠它区分「没课」和「没设置学期」。 */
    val hasActiveTerm: Boolean = false,
    /**
     * 全部没做完的待办，按手动排序键排列。
     *
     * **不受「今天」限制**：待办是不限时、随想随做的东西，只在「它到期那天」才出现的话，
     * 没填期望完成期的待办就永远不会出现（添加完看不到任何变化）。所以待办只在今日页
     * 统一呈现，与日期无关；日期相关的信息用行内小字（今天到期 / 逾期 3 天）表达。
     *
     * 已完成的条目只在完成当天留在表里 —— 否则刚勾完就消失，会让人怀疑是不是没勾上。
     */
    val openTasks: List<AgendaEntry> = emptyList(),
    /**
     * 全部待办组（kind = TODO_GROUP）的**扁平**列表。
     *
     * 只把原始数据一次带出来，免得界面为了画分组再回库查一遍；
     * 树的形状（父子、嵌套深度、递归统计）在 core/todo/TodoTree.kt 里装配 ——
     * 那部分是纯函数，能单测。
     */
    val todoGroups: List<Item> = emptyList(),
) {
    fun on(date: LocalDate): List<AgendaEntry> = days[date].orEmpty()

    /** 有具体时刻的条目，已按开始时间排好。 */
    fun timedOn(date: LocalDate): List<AgendaEntry> = on(date).filter { it.isTimed }

    /**
     * 时间轴上要画的条目：有具体时刻、且不是「全天」。
     *
     * 全天条目在时间轴上只能被画成午夜的一小段（它没有真实起止时刻），
     * 读起来像一条凌晨 30 分钟的日程 —— 所以它们不进时间轴，归到 [tasksOn]。
     */
    fun scheduledOn(date: LocalDate): List<AgendaEntry> = timedOn(date).filterNot { it.allDay }

    /**
     * 待办卡里要列的条目：不限时的待办，加上老数据里遗留的全天日程。
     *
     * 全天形态已取消（全天类的事情用待办），所以这两类现在是同一类东西，
     * 放在同一个清单里才不会让老数据凭空消失。
     */
    fun tasksOn(date: LocalDate): List<AgendaEntry> =
        on(date).filter { it.allDay } + untimedOn(date)

    /** 没有具体时刻的条目（无时限待办、到期提醒）。 */
    fun untimedOn(date: LocalDate): List<AgendaEntry> = on(date).filterNot { it.isTimed }

    /** 当天课程数，给月视图的密度标记用。 */
    fun courseCountOn(date: LocalDate): Int = on(date).count { it.isCourse }

    /**
     * 待办卡里要列的条目 ——  **今日页与日历日视图共用的同一条规则**，只此一份。
     *
     * - **今天**：列全部没做完的待办（[openTasks]）。待办是「有空就做」的事，
     *   从日期维度过滤必然会让一部分消失；加上当天遗留的全天日程。
     * - **其他日期**：只列当天浮出的条目（[tasksOn]），也就是「期望完成期就在这一天」的待办
     *   加上当天遗留的全天日程。
     *
     * 提成函数是因为它以前在 `DayAgendaCard` 与 `TodayViewModel` 里各写了一遍：
     * 两者一字不差时只是冗余，一旦有人只改了一边就会出现「同一屏上下两块列出的待办不一样」。
     */
    fun taskCardEntries(date: LocalDate, isToday: Boolean): List<AgendaEntry> =
        if (isToday) on(date).filter { it.allDay } + openTasks else tasksOn(date)

    companion object {
        val Empty = Agenda(emptyMap())
    }
}