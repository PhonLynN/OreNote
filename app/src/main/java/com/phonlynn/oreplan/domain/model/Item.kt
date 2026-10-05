package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.tree.TreePath
import java.time.Instant
import java.time.LocalDate

/** 条目的四种形态。它们不是四套子系统，而是同一模型在不同「时间确定度」上的表现。 */
enum class ItemKind {
    /** 日程：有明确时刻或时间段。 */
    EVENT,

    /** 规划：无固定时刻，只有可选的期望完成期。 */
    TASK,

    /** 长期目标：通常带子节点，进度由子节点汇总。 */
    GOAL,

    /**
     * 分步目标的子步骤。挂在 kind=GOAL 且 goalType=STEP 的目标下，
     * 完成状态就是一般条目的完成状态。它不出现在时间轴与待办清单里。
     */
    STEP,

    /**
     * 习惯目标的**提醒载体**：一条不可见的后台条目，带「每天/每周几 + 打卡时间」的规则，
     * 只为了让提醒调度器（只认 startAt + rrule 的条目）能按周期把提醒排出来。
     * 它挂在习惯目标下（parentId=goalId，删除目标时一并清掉），
     * 在时间轴 / 待办 / 规划列表里都不出现。
     */
    HABIT_ALARM,

    /**
     * 规划工作区：规划页里的最外层容器，自己是根条目，目标/待办挂在它下面。
     *
     * 为什么工作区也是一个条目而不是新开一张表：层级、同级排序、拖动改顺序、拖到别的
     * 工作区、重命名、笔记、备份 —— 这些能力条目全都已经有了；另开一张表则要在两个
     * 模型之间来回翻译，而且拖动跨区会变成两套排序键的同步问题。
     */
    WORKSPACE,

    /**
     * 待办组（文件夹）：待办页里的**可嵌套**容器，待办可以归到它下面。
     *
     * 与 [WORKSPACE] 同一个理由做成条目而不是新开一张表：层级、同级排序、
     * 跳组移动（reparent）、重命名、笔记、备份 —— 条目全都已经有了。
     * 它不进时间轴、不进待办清单里当条目（见 AgendaBuilder 的容器判断）。
     */
    TODO_GROUP,
    ;

    /**
     * 纲要节点（目标与工作区）—— 它们在这两件事上同形：
     *  - 可以当**容器**（挂子节点）；
     *  - 可以填**计划跨度**（甘特条）。
     *
     * 只留这一份谓词：以前编辑器界面把两者当同形、保存时却只认目标，
     * 于是工作区上填的跨度被静默丢掉；两处各写一次判断就迟早会再分叉。
     */
    val isOutline: Boolean get() = this == GOAL || this == WORKSPACE

    /**
     * 可以挂子节点的**容器**（目标 / 工作区 / 待办组）。
     *
     * 与 [isOutline] 分开：那个谓词还含「能填计划跨度（甘特条）」的意思，
     * 待办组没有跨度 —— 混进去会让编辑器给它多出一个时间字段。
     */
    val isContainer: Boolean get() = this == GOAL || this == WORKSPACE || this == TODO_GROUP
}

enum class ItemStatus {
    TODO,
    DOING,
    DONE,
    CANCELLED,
}

/** 规划页的三种目标形态。 */
enum class GoalType { STEP, QUANTITY, HABIT }

/** 分步目标：按顺序解锁 / 自由完成。 */
enum class StepOrderMode {
    SEQ,
    FREE,
    ;

    val isSequential: Boolean get() = this == SEQ
}

/**
 * 统一条目模型 —— 日程与规划共用一张表。
 *
 * 为什么不分三张表：它们互相转化。「把待办拖到日历上就变成日程」「把目标拆成子任务」
 * 这类操作，只有在共享一套底层模型时才自然；分成三套则每个转化都要写跨表迁移。
 * 而且统一之后，「今日」视图一次查询就能同时拿到日程和当天到期的规划。
 *
 * 时间字段分成两组，语义不混：
 *  - [startAt] / [endAt]：**精确时刻**，只有 [ItemKind.EVENT] 用；
 *  - [planStartDay] / [planEndDay]：**计划跨度，精度到日**，用于甘特条，不用时分。
 *
 * [treePath] 是物化路径（见 [TreePath]），层级操作靠它，UI 只需要 [depth] 做缩进。
 */
@Immutable
data class Item(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val note: String? = null,
    val status: ItemStatus = ItemStatus.TODO,
    /** 0 = 无，1 = 低，2 = 中，3 = 高。 */
    val priority: Int = 0,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val allDay: Boolean = false,
    /** iCal RRULE 子集，见 [RecurrenceRule]。为 null 表示不重复。 */
    val rrule: String? = null,
    val rruleUntil: Instant? = null,
    /** 规划条目的「期望完成期」，可空 —— 不填就是纯粹的「有空再做」。 */
    val softDueAt: Instant? = null,
    val planStartDay: LocalDate? = null,
    val planEndDay: LocalDate? = null,
    val parentId: String? = null,
    /**
     * 归属的待办组（仅 [ItemKind.TASK] 用）。
     *
     * 为什么不复用 [parentId]：那个字段对任务是**「关联事项」**语义
     * （可指向另一个任务或目标，见编辑器里候选项的过滤）。
     * 一个字段挂两种含义会互相污染 ——「关联了某个目标」与「归入某个待办组」
     * 是两件事，必须各占一个字段。
     */
    val groupId: String? = null,
    val treePath: String,
    val depth: Int,
    /**
     * 手动进度。**有子节点时应为 null** —— 那种情况由子节点完成率自动汇总，
     * 不允许手填，否则会出现「父目标显示 80%，但子任务全没做完」的自相矛盾状态。
     */
    val progress: Float? = null,
    val completedAt: Instant? = null,
    val colorTag: String? = null,
    /** 同级手动排序键。用 Double 便于在两项之间插入而不必重排全表。 */
    val orderIndex: Double = 0.0,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 目标类型（仅 GOAL 使用）。 */
    val goalType: GoalType? = null,
    val unit: String? = null,
    val targetValue: Long? = null,
    val stepOrderMode: StepOrderMode? = null,
    val autoAdvance: Boolean = false,
    val showOnToday: Boolean = false,
    val pinned: Boolean = false,
    val category: String? = null,
    val goalNote: String? = null,
    /** 地点（日程用）。 */
    val location: String? = null,
) {
    /** 是否已排到时间轴上。 */
    val isScheduled: Boolean get() = startAt != null

    /** 是否是无时限规划（没有具体时刻的待办）。 */
    val isFloating: Boolean get() = startAt == null && kind == ItemKind.TASK

    /** 工作区：只在规划页作为容器出现，不进时间轴、不进甘特条。 */
    val isWorkspace: Boolean get() = kind == ItemKind.WORKSPACE

    /** 能当容器的种类：目标 / 工作区 / 待办组可以有子节点，日程不适合当容器。 */
    val isContainer: Boolean get() = kind.isContainer

    /** 规划页的条目：目标、待办、工作区。日程只作为子节点出现。 */
    val isPlannable: Boolean get() = kind != ItemKind.EVENT

    val isDone: Boolean get() = status == ItemStatus.DONE

    val isRecurring: Boolean get() = !rrule.isNullOrBlank()

    companion object {
        /**
         * 建一个根条目。id、路径、时间戳都由这里统一生成，
         * 避免每个调用点各写一遍而漏掉某几个字段。
         */
        fun newRoot(
            kind: ItemKind,
            title: String,
            now: Instant,
            note: String? = null,
            priority: Int = 0,
            startAt: Instant? = null,
            endAt: Instant? = null,
            allDay: Boolean = false,
            rrule: String? = null,
            rruleUntil: Instant? = null,
            softDueAt: Instant? = null,
            planStartDay: LocalDate? = null,
            planEndDay: LocalDate? = null,
            status: ItemStatus = ItemStatus.TODO,
            orderIndex: Double = 0.0,
            id: String = Ids.newId(),
        ): Item = Item(
            id = id,
            kind = kind,
            title = title,
            note = note,
            status = status,
            priority = priority,
            startAt = startAt,
            endAt = endAt,
            allDay = allDay,
            rrule = rrule,
            rruleUntil = rruleUntil,
            softDueAt = softDueAt,
            planStartDay = planStartDay,
            planEndDay = planEndDay,
            parentId = null,
            treePath = TreePath.root(id),
            depth = 0,
            orderIndex = orderIndex,
            createdAt = now,
            updatedAt = now,
        )

        /** 在 [parent] 下建一个子条目，路径与层级自动接上。 */
        fun newChild(
            parent: Item,
            kind: ItemKind,
            title: String,
            now: Instant,
            note: String? = null,
            priority: Int = 0,
            startAt: Instant? = null,
            endAt: Instant? = null,
            allDay: Boolean = false,
            rrule: String? = null,
            rruleUntil: Instant? = null,
            softDueAt: Instant? = null,
            planStartDay: LocalDate? = null,
            planEndDay: LocalDate? = null,
            status: ItemStatus = ItemStatus.TODO,
            orderIndex: Double = 0.0,
            id: String = Ids.newId(),
        ): Item = Item(
            id = id,
            kind = kind,
            title = title,
            note = note,
            status = status,
            priority = priority,
            startAt = startAt,
            endAt = endAt,
            allDay = allDay,
            rrule = rrule,
            rruleUntil = rruleUntil,
            softDueAt = softDueAt,
            planStartDay = planStartDay,
            planEndDay = planEndDay,
            parentId = parent.id,
            treePath = TreePath.childOf(parent.treePath, id),
            depth = parent.depth + 1,
            orderIndex = orderIndex,
            createdAt = now,
            updatedAt = now,
        )
    }
}
