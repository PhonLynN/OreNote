package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.order.OrderKeys
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
import com.phonlynn.oreplan.domain.model.StepOrderMode
import com.phonlynn.oreplan.domain.plan.PlanMeta
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.FocusRepository
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 目标详情**底部弹层**的数据源（设计稿 `svsrX / CAMRJ / oTvuc`）。
 *
 * ## 为什么是弹层而不是独立页面
 *
 * 设计稿三张「目标详情」都是**背景 + 半透明蒙层 + 底部圆角面板**，而且面板高度
 * 只占屏幕 60%~85%：目标详情是从规划列表「就地展开」的一层，不是离开这个 Tab。
 * 做成独立路由会让返回栈多一层，还会把底栏藏掉（设计稿里底栏是被蒙层压暗的，
 * 说明它知道自己在主页之上）。
 *
 * 因此它没有导航参数：打开时由 [open] 传入目标 id。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GoalDetailSheetViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    /** 删除条目（连提醒/重复例外/清单一并清）。 */
    private val deleteItemUseCase: com.phonlynn.oreplan.domain.usecase.DeleteItemUseCase,
    private val goalLogRepository: GoalLogRepository,
    private val focusRepository: FocusRepository,
    extRepository: EntityExtRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val goalId = MutableStateFlow<String?>(null)

    private val items = itemRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val habitLogs = goalId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else goalLogRepository.observeHabitLogs(id)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val quantityLogs = goalId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else goalLogRepository.observeQuantityLogs(id)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val sessions = focusRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val meta = extRepository.observeAll()
        .map { records -> records.filter { it.owner == ExtOwner.ITEM }.associateBy { it.ownerId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    /** combine 的带类型重载最多 5 路：先把「条目 + 打卡 + 数量记录」并成一路。 */
    private val logSource = combine(items, habitLogs, quantityLogs) { list, habits, quantities ->
        Triple(list, habits, quantities)
    }

    val uiState: StateFlow<GoalDetailSheetUiState> = combine(
        goalId,
        logSource,
        sessions,
        meta,
    ) { id, logs, focuses, ext ->
        build(id, logs.first, logs.second, logs.third, focuses, ext)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GoalDetailSheetUiState(),
    )

    /** 打开某个目标的详情弹层。 */
    fun open(id: String) {
        goalId.value = id
    }

    fun close() {
        goalId.value = null
    }

    // ---------------------------------------------------------------- 动作

    /** 推进/取消一个里程碑。顺序解锁时只能推进当前那一步。 */
    fun toggleStep(stepId: String) {
        val id = goalId.value ?: return
        viewModelScope.launch {
            val goal = itemRepository.getById(id) ?: return@launch
            val steps = itemRepository.getChildren(id)
                .filter { it.kind == ItemKind.STEP }
                .sortedBy { it.orderIndex }
            val target = steps.firstOrNull { it.id == stepId } ?: return@launch
            val now = Instant.now()
            if (target.status == ItemStatus.DONE) {
                itemRepository.update(target.copy(status = ItemStatus.TODO, completedAt = null, updatedAt = now))
                return@launch
            }
            val mode = goal.stepOrderMode ?: StepOrderMode.SEQ
            val current = steps.firstOrNull { it.status != ItemStatus.DONE }?.id
            if (mode == StepOrderMode.FREE || current == stepId) {
                itemRepository.update(target.copy(status = ItemStatus.DONE, completedAt = now, updatedAt = now))
                // 目标设了「自动推进」且这是最后一步 → 目标本身置为完成
                if (goal.autoAdvance && steps.all { it.id == stepId || it.status == ItemStatus.DONE }) {
                    itemRepository.update(goal.copy(status = ItemStatus.DONE, completedAt = now, updatedAt = now))
                }
            }
        }
    }

    /** 推进下一步（弹层主按钮「推进下一步」）。 */
    fun advanceNextStep() {
        val id = goalId.value ?: return
        viewModelScope.launch {
            val steps = itemRepository.getChildren(id)
                .filter { it.kind == ItemKind.STEP }
                .sortedBy { it.orderIndex }
            val next = steps.firstOrNull { it.status != ItemStatus.DONE } ?: return@launch
            toggleStep(next.id)
        }
    }

    /** 数量目标记一笔。 */
    fun addQuantity(amount: Long, label: String) {
        val id = goalId.value ?: return
        if (amount <= 0) return
        viewModelScope.launch {
            goalLogRepository.addQuantityLog(
                QuantityLog(
                    id = Ids.newId(),
                    itemId = id,
                    at = Instant.now(),
                    amount = amount,
                    label = label.trim().ifBlank { null },
                ),
            )
        }
    }

    fun deleteQuantity(logId: String) {
        viewModelScope.launch { goalLogRepository.deleteQuantityLog(logId) }
    }

    /** 习惯打卡（今天）。已打卡再点就是取消。 */
    fun toggleToday() {
        val id = goalId.value ?: return
        val epoch = LocalDate.now(zone).toEpochDay().toInt()
        viewModelScope.launch { goalLogRepository.toggleHabit(id, epoch) }
    }

    /** 从弹层里加一个子项（分步目标的「添加子项」）。 */
    fun addStep(title: String) {
        val id = goalId.value ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val parent = itemRepository.getById(id) ?: return@launch
            val existing = itemRepository.getChildren(id).filter { it.kind == ItemKind.STEP }
            val orderIndex = OrderKeys.forAppend(existing.maxByOrNull { it.orderIndex }?.orderIndex)
            itemRepository.create(Item.newChild(parent, ItemKind.STEP, trimmed, Instant.now(), orderIndex = orderIndex))
        }
    }

    /**
     * 删除一个子项。
     *
     * 走用例而不是直接调仓储 —— 子项也可能挂提醒（用户在子项上设过），
     * 直接删会留下孤儿（详见 `DeleteItemUseCase`）。
     */
    fun removeStep(stepId: String) {
        viewModelScope.launch { deleteItemUseCase(stepId) }
    }

    /** 归档（= 放弃）。设计稿里归档后进「已归档」列表。 */
    fun archive() {
        val id = goalId.value ?: return
        viewModelScope.launch {
            itemRepository.getById(id)?.let {
                itemRepository.update(it.copy(status = ItemStatus.CANCELLED, updatedAt = Instant.now()))
            }
            close()
        }
    }

    /** 标记为已完成（习惯/数量达成时用）。 */
    fun markDone() {
        val id = goalId.value ?: return
        viewModelScope.launch {
            itemRepository.getById(id)?.let {
                itemRepository.update(
                    it.copy(status = ItemStatus.DONE, completedAt = Instant.now(), updatedAt = Instant.now()),
                )
            }
        }
    }

    private fun build(
        id: String?,
        list: List<Item>,
        habits: List<HabitLog>,
        quantities: List<QuantityLog>,
        sessions: List<FocusSession>,
        meta: Map<String, ExtRecord>,
    ): GoalDetailSheetUiState {
        if (id == null) return GoalDetailSheetUiState()
        val goal = list.firstOrNull { it.id == id }
            ?: return GoalDetailSheetUiState(loaded = true, missing = true)
        val type = goal.goalType ?: GoalType.STEP
        val day = LocalDate.now(zone)
        val todayEpoch = day.toEpochDay().toInt()
        val steps = list.filter { it.parentId == id && it.kind == ItemKind.STEP }.sortedBy { it.orderIndex }
        val days = habits.map { it.epochDay }.toSet()
        val ext = meta[id]?.map
        val due = PlanUi.toLocalDate(goal.softDueAt, zone)
        val focusMinutes = FocusStats.minutesForGoal(sessions, id)

        val (done, total) = PlanUi.stepDoneTotal(steps)
        val currentStepId = PlanUi.nextStep(steps)?.id

        val weeklyTarget = habitWeeklyTarget(HabitFrequencyCodec.decode(goal.rrule))
        val weekDone = PlanUi.weekDone(days, day)

        val trend = FocusStats.quantityTrend(quantities, day, zone)

        return GoalDetailSheetUiState(
            loaded = true,
            goal = goal,
            type = type,
            title = goal.title,
            percent = when (type) {
                GoalType.STEP -> PlanUi.stepPercent(done, total)
                GoalType.QUANTITY -> PlanUi.quantityPercent(goal, quantities)
                GoalType.HABIT -> PlanUi.habitPercent(weekDone, weeklyTarget)
            },
            progressText = when (type) {
                GoalType.STEP -> "已完成 $done / $total 步"
                GoalType.QUANTITY -> "已完成 ${PlanUi.quantityTotal(quantities)} / ${goal.targetValue ?: 0}"
                GoalType.HABIT -> "本周 $weekDone / $weeklyTarget 天"
            },
            remainingDays = PlanUi.remainingDays(due, day),
            milestones = "${PlanUi.milestoneDone(done)} / ${PlanUi.milestoneTotal(total)}",
            focusMinutes = focusMinutes,
            nodes = steps.map { step ->
                MilestoneNode(
                    id = step.id,
                    title = step.title,
                    meta = stepMeta(step, due, step.id == currentStepId),
                    done = step.status == ItemStatus.DONE,
                    current = step.id == currentStepId,
                )
            },
            stepDone = done,
            stepTotal = total,
            quantityLogs = quantities.sortedByDescending { it.at },
            quantityTotal = PlanUi.quantityTotal(quantities),
            quantityTarget = goal.targetValue ?: 0L,
            unit = goal.unit.orEmpty(),
            quantityTrend = trend,
            habitDays = days,
            habitTodayChecked = todayEpoch in days,
            habitStreak = PlanUi.streak(days, todayEpoch),
            habitMaxStreak = PlanUi.maxStreak(days),
            habitMonthDone = PlanUi.monthDone(days, day),
            habitWeeklyTarget = weeklyTarget,
            habitWeekDone = weekDone,
            dailyMinutes = ext?.let { PlanMeta.dailyMinutes(it) },
            today = day,
            // 习惯详情的热力图是**打卡**热力图（只有做/没做两态），不是专注强度。
            // 列数与专注记录页一致（HeatmapMetrics.Weeks = 一整年）——
            // 两边不一致时，「左右拖看历史」会一处有一处没有（用户 2026-10-02 指出）。
            habitHeatmap = FocusStats.checkinGrid(days, day, weeks = HeatmapMetrics.Weeks),
        )
    }

    private fun stepMeta(step: Item, due: LocalDate?, current: Boolean): String? {
        val completed = step.completedAt?.atZone(zone)?.toLocalDate()
        return when {
            completed != null -> "${PlanUi.monthDay(completed)}完成"
            current -> due?.let { "进行中 · 截至 ${PlanUi.monthDay(it)}" } ?: "进行中"
            due != null -> "${PlanUi.monthDay(due)} · 未开始"
            else -> "未开始"
        }
    }
}

/** 目标详情弹层的状态。[goal] 为 null 表示弹层没打开。 */
data class GoalDetailSheetUiState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val goal: Item? = null,
    val type: GoalType = GoalType.STEP,
    val title: String = "",
    val percent: Int = 0,
    val progressText: String = "",
    /** 「剩余天数」格子的值。 */
    val remainingDays: String? = null,
    /** 「里程碑」格子的值 `4 / 6`。 */
    val milestones: String = "0 / 0",
    val focusMinutes: Int = 0,
    // 分步
    val nodes: List<MilestoneNode> = emptyList(),
    val stepDone: Int = 0,
    val stepTotal: Int = 0,
    // 数量
    val quantityLogs: List<QuantityLog> = emptyList(),
    val quantityTotal: Long = 0,
    val quantityTarget: Long = 0,
    val unit: String = "",
    val quantityTrend: FocusStats.QuantityTrend? = null,
    // 习惯
    val habitDays: Set<Int> = emptySet(),
    val habitTodayChecked: Boolean = false,
    val habitStreak: Int = 0,
    val habitMaxStreak: Int = 0,
    val habitMonthDone: Int = 0,
    val habitWeeklyTarget: Int = 7,
    val habitWeekDone: Int = 0,
    /** 习惯的「每天目标」分钟数（设计稿「每日晨跑 30 分钟」的那个 30）。 */
    val dailyMinutes: Int? = null,
    val today: LocalDate = LocalDate.now(),
    val habitHeatmap: List<List<com.phonlynn.oreplan.domain.focus.HeatCell>> = emptyList(),
) {
    val open: Boolean get() = goal != null || missing

    /** 今天该不该打卡（按频率）。 */
    fun isHabitDay(): Boolean = today.dayOfWeek.value in freqActiveDays(
        HabitFrequencyCodec.decode(goal?.rrule),
    )

    /** 习惯热力图的月份标签。 */
    fun heatmapLabels(): Map<Int, String> = FocusStats.heatmapMonthLabels(habitHeatmap)

    /**
     * 热力图右上角的年份 —— **按滑到的位置算**，与专注记录页同一口径。
     *
     * 这里只给得出「左端那一列是哪一年」（详情页没有把视口宽度算进来，
     * 因为它是面板内的一小段；跨年时以左端为准即可）。
     */
    fun heatmapYearAt(columnIndex: Int): String {
        val column = habitHeatmap.getOrNull(columnIndex) ?: return "${today.year}"
        return "${column.firstOrNull()?.date?.year ?: today.year}"
    }

    /** 热力图有多少列（给界面算滚动位置用）。 */
    val heatmapColumns: Int get() = habitHeatmap.size

    /** 今天在第几列 —— 进面板时把视图贴到它，也就是贴到最右端。 */
    val todayColumn: Int
        get() = habitHeatmap.indexOfFirst { column -> column.any { it.date == today } }.coerceAtLeast(0)

    /** 数量趋势的柱值与标签，供 TrendBars 用。 */
    fun trendValues(): List<Long> = quantityTrend?.cumulative.orEmpty()

    fun trendLabels(): List<String> = quantityTrend?.labels.orEmpty()

    /** 数量趋势的右标题：`近 8 周 +7`。 */
    fun trendNote(): String {
        val trend = quantityTrend ?: return ""
        return "近 8 周 " + if (trend.lastDelta >= 0) "+${trend.lastDelta}" else "${trend.lastDelta}"
    }
}

/**
 * 规划首页与详情弹层之间的**最小桥**。
 *
 * 为什么不用共享 ViewModel：弹层与首页是两个作用域（首页是 Tab 级、
 * 弹层随选中项开关），强行共享会让「关掉弹层后数据还在」这种中间态外泄。
 * 这里只传一个 id，弹层自己去取数据 —— 单向、无状态残留。
 */
object GoalDetailBus {
    private val _requested = MutableStateFlow<String?>(null)
    val requested: StateFlow<String?> = _requested

    fun open(goalId: String) {
        _requested.value = goalId
    }

    fun consume() {
        _requested.value = null
    }
}
