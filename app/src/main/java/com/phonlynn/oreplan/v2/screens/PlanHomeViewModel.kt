package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.HabitLog
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.domain.model.Term
import com.phonlynn.oreplan.domain.plan.PlanMeta
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.FocusRepository
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 规划首页的数据。
 *
 * 设计稿对应 `wBNwV 规划 V2`：周标题 → 今日专注卡（含本周柱状图）→
 * 「进行中的目标 ⋯ 已归档」→ 目标栈 → FAB。
 *
 * ## 一次组合、一次算完
 *
 * 首页要同时用到六路数据（条目 / 学期 / 打卡 / 数量记录 / 专注记录 / 扩展抽屉）。
 * 每一路都单独 `collectAsState` 再在 Compose 里合并，会造成「专注记录到了、
 * 条目还没到」这种中间态被渲染出来（页面会闪一下「今日专注 0m」）。
 * 这里全部在 `combine` 里合并成**一个** [PlanHomeUiState]，界面只读它。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanHomeViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val termRepository: TermRepository,
    private val goalLogRepository: GoalLogRepository,
    private val focusRepository: FocusRepository,
    extRepository: EntityExtRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val items = itemRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val term = termRepository.observeActiveTerm().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    private val habitLogs = goalLogRepository.observeAllHabitLogs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val focusSessions = focusRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** 规划相关的抽屉内容（只取 ITEM 这一张表）。 */
    private val itemMeta = extRepository.observeAll()
        .map { records -> records.filter { it.owner == ExtOwner.ITEM }.associateBy { it.ownerId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    /** 每个数量目标各自的记录。目标 id 变了要重新订阅。 */
    private val quantityLogs = items.flatMapLatest { list ->
        val goalIds = list.filter { it.kind == ItemKind.GOAL && it.goalType == GoalType.QUANTITY }.map { it.id }
        if (goalIds.isEmpty()) {
            flowOf(emptyMap<String, List<QuantityLog>>())
        } else {
            combine(goalIds.map { id -> goalLogRepository.observeQuantityLogs(id) }) { arrays ->
                goalIds.zip(arrays).associate { (id, logs) -> id to logs }
            }
        }
    }

    private val today = MutableStateFlow(LocalDate.now(zone))

    val uiState: StateFlow<PlanHomeUiState> = combine(
        items,
        term,
        habitLogs,
        focusSessions,
        itemMeta,
        quantityLogs,
        today,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        buildState(
            list = values[0] as List<Item>,
            activeTerm = values[1] as Term?,
            habits = values[2] as List<HabitLog>,
            sessions = values[3] as List<FocusSession>,
            meta = values[4] as Map<String, ExtRecord>,
            quantities = values[5] as Map<String, List<QuantityLog>>,
            day = values[6] as LocalDate,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlanHomeUiState(),
    )

    /** 跨天时刷新「今天」。Tab 页长期驻留，不刷新的话第二天进来还是昨天的数据。 */
    fun refreshToday() {
        today.value = LocalDate.now(zone)
    }

    private fun buildState(
        list: List<Item>,
        activeTerm: Term?,
        habits: List<HabitLog>,
        sessions: List<FocusSession>,
        meta: Map<String, ExtRecord>,
        quantities: Map<String, List<QuantityLog>>,
        day: LocalDate,
    ): PlanHomeUiState {
        val todayEpoch = day.toEpochDay().toInt()
        val stepsByGoal = list.filter { it.kind == ItemKind.STEP }.groupBy { it.parentId }
        val habitByGoal = habits.groupBy { it.itemId }.mapValues { (_, v) -> v.map { it.epochDay }.toSet() }

        val goals = list.filter { it.kind == ItemKind.GOAL }
        val archived = goals.filter { it.status == ItemStatus.CANCELLED }
        val active = goals.filter { it.status != ItemStatus.CANCELLED }
            .sortedWith(compareByDescending<Item> { it.pinned }.thenBy { it.orderIndex }.thenBy { it.createdAt })

        val week = FocusStats.weekSummary(sessions, day, zone)
        val dayInfo = FocusStats.daySummary(sessions, day, zone)

        return PlanHomeUiState(
            loaded = true,
            today = day,
            weekStart = week.weekStart,
            overline = PlanUi.weekOverline(activeTerm, day),
            todayFocusMinutes = dayInfo.minutes,
            todayFocusCount = dayInfo.sessions,
            dailyGoalMinutes = DAILY_GOAL_MINUTES,
            streakDays = FocusStats.focusStreak(sessions, day, zone),
            weekDailyMinutes = week.dailyMinutes,
            weekTotalMinutes = week.totalMinutes,
            weekDeltaPercent = week.deltaPercent,
            goals = active.map { goal ->
                toCard(
                    goal = goal,
                    meta = meta[goal.id],
                    steps = stepsByGoal[goal.id].orEmpty().sortedBy { it.orderIndex },
                    habitDays = habitByGoal[goal.id].orEmpty(),
                    quantities = quantities[goal.id].orEmpty(),
                    sessions = sessions,
                    day = day,
                    todayEpoch = todayEpoch,
                )
            },
            archivedCount = archived.size,
        )
    }

    private fun toCard(
        goal: Item,
        meta: ExtRecord?,
        steps: List<Item>,
        habitDays: Set<Int>,
        quantities: List<QuantityLog>,
        sessions: List<FocusSession>,
        day: LocalDate,
        todayEpoch: Int,
    ): GoalCardData {
        val type = goal.goalType ?: GoalType.STEP
        val ext = meta?.map
        val due = PlanUi.toLocalDate(goal.softDueAt, zone)
        val dailyMinutes = ext?.let { PlanMeta.dailyMinutes(it) }
        val focusMinutes = FocusStats.minutesForGoal(sessions, goal.id)

        return when (type) {
            GoalType.STEP -> {
                val (done, total) = PlanUi.stepDoneTotal(steps)
                GoalCardData(
                    id = goal.id,
                    title = goal.title,
                    type = type,
                    // 完成了就写「已完成」，否则写剩余时间
                    rightText = if (total > 0 && done == total) "已完成" else PlanUi.dueText(due, day),
                    fraction = if (total > 0) done.toFloat() / total else 0f,
                    percent = PlanUi.stepPercent(done, total),
                    foot = PlanUi.goalFootText(type, done, total, null, null),
                )
            }
            GoalType.QUANTITY -> {
                val target = goal.targetValue ?: 0L
                val current = PlanUi.quantityTotal(quantities)
                val unit = goal.unit.orEmpty()
                GoalCardData(
                    id = goal.id,
                    title = goal.title,
                    type = type,
                    rightText = if (target > 0 && current >= target) "已达成" else PlanUi.dueText(due, day),
                    fraction = if (target > 0) (current.toDouble() / target).toFloat().coerceIn(0f, 1f) else 0f,
                    percent = PlanUi.quantityPercent(goal, quantities),
                    foot = PlanUi.quantityFoot(goal, quantities) +
                        if (focusMinutes > 0) " · 专注 ${focusMinutes}m" else "",
                )
            }
            GoalType.HABIT -> {
                val freq = HabitFrequencyCodec.decode(goal.rrule)
                val weeklyTarget = habitWeeklyTarget(freq)
                val weekDone = PlanUi.weekDone(habitDays, day)
                val streak = PlanUi.streak(habitDays, todayEpoch)
                val monday = TermClock.weekStartOf(day)
                val strip = (0..6).map { offset ->
                    monday.plusDays(offset.toLong()).toEpochDay().toInt() in habitDays
                }
                GoalCardData(
                    id = goal.id,
                    title = goal.title,
                    type = type,
                    rightText = if (streak > 0) "连续 $streak 天" else "${PlanUi.habitPercent(weekDone, weeklyTarget)}%",
                    fraction = if (weeklyTarget > 0) weekDone.toFloat() / weeklyTarget else 0f,
                    percent = null,
                    foot = PlanUi.habitFoot(weeklyTarget, weekDone) +
                        if (dailyMinutes != null) " · 每天 $dailyMinutes 分钟" else "",
                    weekStrip = strip,
                    todayIndex = (day.dayOfWeek.value - 1),
                    rightEmphasis = streak > 0,
                )
            }
        }
    }

    private companion object {
        /**
         * 今日专注目标（设计稿里写死的 `/ 2h`）。
         *
         * 有意不做成设置项：这是「今日专注」卡片上的一句参照，不是一个用户承诺。
         * 真要做成可配的，得先问用户想不想，而不是顺手塞一个设置项进去。
         */
        const val DAILY_GOAL_MINUTES = 120
    }
}

/** 规划首页状态。 */
data class PlanHomeUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val weekStart: LocalDate = LocalDate.now(),
    val overline: String = "",
    /** 今日专注分钟数与次数。 */
    val todayFocusMinutes: Int = 0,
    val todayFocusCount: Int = 0,
    val dailyGoalMinutes: Int = 120,
    val streakDays: Int = 0,
    /** 本周七天（周一..周日）各自的分钟数。 */
    val weekDailyMinutes: List<Int> = List(7) { 0 },
    val weekTotalMinutes: Int = 0,
    /** 与上周同进度对比；null 表示上周没有数据（不显示「+∞%」）。 */
    val weekDeltaPercent: Int? = null,
    val goals: List<GoalCardData> = emptyList(),
    val archivedCount: Int = 0,
)
