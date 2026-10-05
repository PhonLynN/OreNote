package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.order.OrderKeys
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.model.StepOrderMode
import com.phonlynn.oreplan.domain.plan.PlanMeta
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.FocusRepository
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.v2.V2Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt

/** 子项草稿：id 为 null 表示待新建。 */
data class PlanStepDraft(val id: String?, val name: String)

/**
 * 新建 / 目标设置的表单。
 *
 * 设计稿把「新建目标」（`V62qjP`）与三张「目标设置」（`LWz7k / Dl2iy / g13Ial`）
 * 分成了四个页面，但它们**字段完全重合**，差别只有：
 * - 新建：能切类型、带「子项」区、底部是「创建目标」；
 * - 设置：带顶部概览卡、带「关联 / 删除目标」、底部没有主按钮（右上「保存」）。
 *
 * 因此这里是一个表单、两个外壳 —— 复制两份必然分叉（改一处忘一处）。
 * 类型**只在新建时可改**：已有目标的类型一旦改了，历史打卡/记录的意义就变了。
 */
data class PlanGoalForm(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val goalId: String? = null,
    val type: GoalType = GoalType.STEP,
    val title: String = "",
    val due: LocalDate? = null,
    // 分步
    val steps: List<PlanStepDraft> = emptyList(),
    /** 顺序解锁（默认）还是自由完成。 */
    val sequential: Boolean = true,
    // 数量
    val targetValue: Long = 12,
    val unit: String = "本",
    val startValue: Long = 0,
    // 习惯
    val frequency: HabitFrequency = HabitFrequency.Daily,
    /** 「周期时限」的每周天数；null 表示不设限（= 按频率推导）。 */
    val weeklyTarget: Int? = null,
    /** 每天目标分钟数；null 表示不设。 */
    val dailyMinutes: Int? = null,
    val checkInTime: String = "07:00",
    // 提醒
    val reminderEnabled: Boolean = false,
    val leads: List<Int> = emptyList(),
    // 关联
    val linkedEvents: List<String> = emptyList(),
    // 概览（设置页顶部卡）
    val percent: Int = 0,
    val progressText: String = "",
    val streak: Int = 0,
    val weekDone: Int = 0,
    val habitDays: Set<Int> = emptySet(),
    val focusMinutes: Int = 0,
    val remainingDays: Int? = null,
    val today: LocalDate = LocalDate.now(),
)

@HiltViewModel
class PlanGoalEditorViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val goalLogRepository: GoalLogRepository,
    private val reminderRepository: ReminderRepository,
    private val focusRepository: FocusRepository,
    private val extRepository: EntityExtRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val goalId: String? = savedStateHandle.get<String>(V2Routes.ARG_GOAL_ID)?.takeIf { it.isNotBlank() }

    private val _form = MutableStateFlow(PlanGoalForm())
    val form: StateFlow<PlanGoalForm> = _form.asStateFlow()

    /** 保存是否已完成（界面据此返回上一页）。 */
    private val savedFlag = MutableStateFlow(false)

    private val allItems = itemRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /**
     * 界面要的一切：表单 + 可关联日程候选 + 是否已保存。
     *
     * 为什么合并成一个 StateFlow：`combine` 最多只接受 5 路（再多就要写数组强转，
     * 那种代码一改就崩）。合并之后界面只有一个 `collectAsState`，也不会出现
     * 「表单已经有值、候选还没到」这种半截状态。
     */
    val uiState: StateFlow<PlanGoalEditorUiState> = combine(_form, allItems, savedFlag) { form, items, saved ->
        PlanGoalEditorUiState(
            form = form,
            linkableEvents = buildLinkableEvents(items, form.title),
            saved = saved,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlanGoalEditorUiState(),
    )

    init {
        val typeArg = savedStateHandle.get<String>(V2Routes.ARG_TYPE).orEmpty()
        val initialType = GoalType.entries.firstOrNull { it.name == typeArg } ?: GoalType.STEP
        if (goalId == null) {
            _form.value = PlanGoalForm(
                loaded = true,
                isNew = true,
                type = initialType,
                // 新建时的默认值按类型给：习惯默认开提醒（设计稿「每天 20:00」的默认态）
                reminderEnabled = initialType == GoalType.HABIT,
                leads = if (initialType == GoalType.HABIT) emptyList() else listOf(1440),
            )
        } else {
            viewModelScope.launch { loadExisting(goalId) }
        }
    }

    private suspend fun loadExisting(id: String) {
        val goal = itemRepository.getById(id) ?: return
        val type = goal.goalType ?: GoalType.STEP
        val steps = itemRepository.getChildren(id)
            .filter { it.kind == ItemKind.STEP }
            .sortedBy { it.orderIndex }
        val ext = extRepository.get(ExtOwner.ITEM, id)
        val habits = goalLogRepository.observeHabitLogs(id).first().map { it.epochDay }.toSet()
        val quantities = goalLogRepository.observeQuantityLogs(id).first()
        val focusMinutes = FocusStats.minutesForGoal(focusRepository.getAll(), id)
        val today = LocalDate.now(zone)
        val todayEpoch = today.toEpochDay().toInt()
        val frequency = HabitFrequencyCodec.decode(goal.rrule)

        val (done, total) = PlanUi.stepDoneTotal(steps)
        val weekDone = PlanUi.weekDone(habits, today)
        val weeklyTarget = habitWeeklyTarget(frequency)

        val reminder = reminderRepository.observeOf(id).first()
        val leads = ext.let { PlanMeta.remindLeads(it) }.ifEmpty {
            // 兼容 0.3.0 之前的单一提醒：换算成「提前 N 分钟」一档。
            reminder.filter { it.enabled }.mapNotNull { it.offsetMinutes }.filter { it > 0 }
        }

        _form.value = PlanGoalForm(
            loaded = true,
            isNew = false,
            goalId = id,
            type = type,
            title = goal.title,
            due = PlanUi.toLocalDate(goal.softDueAt, zone),
            steps = steps.map { PlanStepDraft(it.id, it.title) },
            sequential = (goal.stepOrderMode ?: StepOrderMode.SEQ) == StepOrderMode.SEQ,
            targetValue = goal.targetValue ?: 12,
            unit = goal.unit ?: "本",
            startValue = 0,
            frequency = frequency,
            weeklyTarget = null,
            dailyMinutes = PlanMeta.dailyMinutes(ext),
            checkInTime = PlanMeta.checkInTime(ext) ?: "07:00",
            reminderEnabled = reminder.any { it.enabled },
            leads = leads,
            linkedEvents = PlanMeta.linkedEvents(ext),
            percent = when (type) {
                GoalType.STEP -> PlanUi.stepPercent(done, total)
                GoalType.QUANTITY -> PlanUi.quantityPercent(goal, quantities)
                GoalType.HABIT -> PlanUi.habitPercent(weekDone, weeklyTarget)
            },
            progressText = when (type) {
                GoalType.STEP -> "已完成 $done / $total 步"
                GoalType.QUANTITY -> "已记录 ${PlanUi.quantityTotal(quantities)} / ${goal.targetValue ?: 0}"
                GoalType.HABIT -> "本周 $weekDone / $weeklyTarget 天 · 连续 ${PlanUi.streak(habits, todayEpoch)} 天"
            },
            streak = PlanUi.streak(habits, todayEpoch),
            weekDone = weekDone,
            habitDays = habits,
            focusMinutes = focusMinutes,
            remainingDays = PlanUi.toLocalDate(goal.softDueAt, zone)
                ?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() },
            today = today,
        )
    }

    // ---------------------------------------------------------------- 字段

    private fun update(transform: (PlanGoalForm) -> PlanGoalForm) {
        _form.value = transform(_form.value)
    }

    fun setType(type: GoalType) = update { it.copy(type = type) }
    fun setTitle(value: String) = update { it.copy(title = value) }
    fun setDue(value: LocalDate?) = update { it.copy(due = value) }
    fun setSequential(value: Boolean) = update { it.copy(sequential = value) }
    fun setTargetValue(value: Long) = update { it.copy(targetValue = value.coerceAtLeast(1)) }
    fun setUnit(value: String) = update { it.copy(unit = value) }
    fun setFrequency(value: HabitFrequency) = update { it.copy(frequency = value) }
    fun setWeeklyTarget(value: Int?) = update { it.copy(weeklyTarget = value) }
    fun setDailyMinutes(value: Int?) = update { it.copy(dailyMinutes = value) }
    fun setCheckInTime(value: String) = update { it.copy(checkInTime = value) }
    fun setReminderEnabled(value: Boolean) = update {
        it.copy(
            reminderEnabled = value,
            // 打开提醒但一档都没有时给一个默认档：否则开关看起来生效了、实际什么都不会响。
            leads = if (value && it.leads.isEmpty()) defaultLeadsFor(it) else it.leads,
        )
    }

    private fun defaultLeadsFor(form: PlanGoalForm): List<Int> =
        if (form.type == GoalType.HABIT) listOf(15) else listOf(1440)

    /** 加一档提醒。满 3 档或与已有档位间隔不足 30 分钟时不加（界面会提示）。 */
    fun addLead(minutes: Int) = update { it.copy(leads = PlanMeta.addLead(it.leads, minutes)) }

    fun removeLead(minutes: Int) = update { it.copy(leads = it.leads - minutes) }

    fun toggleLinkedEvent(eventId: String) = update {
        it.copy(linkedEvents = if (eventId in it.linkedEvents) it.linkedEvents - eventId else it.linkedEvents + eventId)
    }

    // ---------------------------------------------------------------- 子项

    fun addStep(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        update { it.copy(steps = it.steps + PlanStepDraft(null, trimmed)) }
    }

    fun renameStep(index: Int, name: String) = update { form ->
        form.copy(steps = form.steps.mapIndexed { i, s -> if (i == index) s.copy(name = name) else s })
    }

    fun removeStep(index: Int) = update { form ->
        form.copy(steps = form.steps.filterIndexed { i, _ -> i != index })
    }

    fun moveStep(from: Int, to: Int) = update { form ->
        if (from !in form.steps.indices || to !in form.steps.indices || from == to) return@update form
        val list = form.steps.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        form.copy(steps = list)
    }

    // ---------------------------------------------------------------- 保存

    /** 标题非空才能保存 —— 空目标没有意义，界面上按钮会置灰。 */
    fun canSave(): Boolean = _form.value.title.isNotBlank()

    fun save(onDone: () -> Unit) {
        val f = _form.value
        if (f.title.isBlank()) return
        viewModelScope.launch {
            val id = if (f.isNew) createGoal(f) else updateGoal(f)
            syncReminder(id, f)
            syncHabitAlarm(id, f)
            saveMeta(id, f)
            savedFlag.value = true
            onDone()
        }
    }

    private suspend fun createGoal(f: PlanGoalForm): String {
        val now = Instant.now()
        val lastOrder = itemRepository.getAll().filter { it.kind == ItemKind.GOAL }
            .maxByOrNull { it.orderIndex }?.orderIndex
        val goal = Item.newRoot(
            kind = ItemKind.GOAL,
            title = f.title.trim(),
            now = now,
            softDueAt = f.due?.let { PlanUi.toDueInstant(it, zone) },
            orderIndex = OrderKeys.forAppend(lastOrder),
        ).copy(
            goalType = f.type,
            unit = f.unit.takeIf { f.type == GoalType.QUANTITY },
            targetValue = f.targetValue.takeIf { f.type == GoalType.QUANTITY },
            stepOrderMode = if (f.type == GoalType.STEP) {
                if (f.sequential) StepOrderMode.SEQ else StepOrderMode.FREE
            } else {
                null
            },
            rrule = if (f.type == GoalType.HABIT) HabitFrequencyCodec.encode(f.frequency) else null,
        )
        val id = itemRepository.create(goal)
        if (f.type == GoalType.STEP) {
            val parent = goal.copy(id = id)
            f.steps.forEachIndexed { index, draft ->
                itemRepository.create(
                    Item.newChild(parent, ItemKind.STEP, draft.name.trim(), now, orderIndex = (index + 1) * OrderKeys.GAP),
                )
            }
        }
        return id
    }

    private suspend fun updateGoal(f: PlanGoalForm): String {
        val id = f.goalId ?: return ""
        val now = Instant.now()
        val goal = itemRepository.getById(id) ?: return id
        itemRepository.update(
            goal.copy(
                title = f.title.trim(),
                softDueAt = f.due?.let { PlanUi.toDueInstant(it, zone) },
                unit = f.unit.takeIf { f.type == GoalType.QUANTITY } ?: goal.unit,
                targetValue = f.targetValue.takeIf { f.type == GoalType.QUANTITY } ?: goal.targetValue,
                stepOrderMode = if (f.type == GoalType.STEP) {
                    if (f.sequential) StepOrderMode.SEQ else StepOrderMode.FREE
                } else {
                    goal.stepOrderMode
                },
                rrule = if (f.type == GoalType.HABIT) HabitFrequencyCodec.encode(f.frequency) else goal.rrule,
                updatedAt = now,
            ),
        )
        if (f.type == GoalType.STEP) reconcileSteps(id, now)
        return id
    }

    /** 子项增删改：保留未变子项的完成状态，只改标题与顺序；删除被移除的；追加新建的。 */
    private suspend fun reconcileSteps(goalId: String, now: Instant) {
        val existing = itemRepository.getChildren(goalId).filter { it.kind == ItemKind.STEP }
        val drafts = _form.value.steps
        val keptIds = drafts.mapNotNull { it.id }.toSet()
        existing.filter { it.id !in keptIds }.forEach { itemRepository.deleteSubtree(it.id) }

        val parent = itemRepository.getById(goalId) ?: return
        drafts.forEachIndexed { index, draft ->
            val orderIndex = (index + 1) * OrderKeys.GAP
            val ex = draft.id?.let { existingId -> existing.firstOrNull { it.id == existingId } }
            when {
                ex != null -> if (ex.title != draft.name.trim() || ex.orderIndex != orderIndex) {
                    itemRepository.update(ex.copy(title = draft.name.trim(), orderIndex = orderIndex, updatedAt = now))
                }
                else -> itemRepository.create(
                    Item.newChild(parent, ItemKind.STEP, draft.name.trim(), now, orderIndex = orderIndex),
                )
            }
        }
    }

    /** 规划专属字段进扩展抽屉（不动老表，见 PlanMeta 的说明）。 */
    private suspend fun saveMeta(goalId: String, f: PlanGoalForm) {
        extRepository.update(ExtOwner.ITEM, goalId) { map ->
            var next = map
            next = if (f.type == GoalType.HABIT && f.dailyMinutes != null && f.dailyMinutes > 0) {
                next.putNum(PlanMeta.KEY_DAILY_MINUTES, f.dailyMinutes.toDouble())
            } else {
                next.remove(PlanMeta.KEY_DAILY_MINUTES)
            }
            next = if (f.type == GoalType.HABIT) {
                next.putText(PlanMeta.KEY_CHECK_IN_TIME, f.checkInTime)
            } else {
                next.remove(PlanMeta.KEY_CHECK_IN_TIME)
            }
            next = if (f.reminderEnabled && f.leads.isNotEmpty()) {
                next.putText(PlanMeta.KEY_REMIND_LEADS, PlanMeta.encodeRemindLeads(f.leads))
            } else {
                next.remove(PlanMeta.KEY_REMIND_LEADS)
            }
            next = if (f.linkedEvents.isEmpty()) {
                next.remove(PlanMeta.KEY_LINKED_EVENTS)
            } else {
                next.putText(PlanMeta.KEY_LINKED_EVENTS, PlanMeta.encodeLinkedEvents(f.linkedEvents))
            }
            next
        }
    }

    /**
     * 目标自己的提醒。
     *
     * 一档 = 一条 [Reminder]。分步/数量的档位是「截止前 N 天」，触发器 = 截止时刻 − N 天；
     * 习惯的档位是「打卡时间前 N 分钟」，真正的周期提醒由 [syncHabitAlarm] 的载体条目负责。
     */
    private suspend fun syncReminder(goalId: String, f: PlanGoalForm) {
        reminderRepository.deleteOf(goalId)
        if (!f.reminderEnabled) return
        val base = when (f.type) {
            GoalType.HABIT -> {
                val time = runCatching { LocalTime.parse(f.checkInTime) }.getOrElse { LocalTime.of(7, 0) }
                val today = LocalDate.now(zone)
                var at = today.atTime(time).atZone(zone).toInstant()
                if (at <= Instant.now()) at = at.plus(Duration.ofDays(1))
                at
            }
            else -> f.due?.let { PlanUi.toDueInstant(it, zone) } ?: return
        }
        f.leads.forEach { lead ->
            reminderRepository.upsert(
                Reminder(
                    id = Ids.newId(),
                    itemId = goalId,
                    triggerAt = base.minus(Duration.ofMinutes(lead.toLong())),
                    offsetMinutes = lead,
                    enabled = true,
                ),
            )
        }
    }

    private fun habitAlarmId(goalId: String): String = "habit_alarm_$goalId"

    /**
     * 习惯提醒的**载体条目**：提醒调度器只认「有 startAt + rrule 的条目」，
     * 所以习惯的周期提醒必须挂在一条不可见条目上（`ItemKind.HABIT_ALARM`）。
     * 关掉提醒或改成别的类型时把它删掉，否则通知还会响。
     */
    private suspend fun syncHabitAlarm(goalId: String, f: PlanGoalForm) {
        val alarmId = habitAlarmId(goalId)
        val shouldExist = f.type == GoalType.HABIT && f.reminderEnabled
        if (!shouldExist) {
            itemRepository.getById(alarmId)?.let { itemRepository.deleteSubtree(it.id) }
            reminderRepository.deleteOf(alarmId)
            return
        }
        val goal = itemRepository.getById(goalId) ?: return
        val now = Instant.now()
        val time = runCatching { LocalTime.parse(f.checkInTime) }.getOrElse { LocalTime.of(7, 0) }
        val today = LocalDate.now(zone)
        var start = today.atTime(time).atZone(zone).toInstant()
        if (start <= now) start = start.plus(Duration.ofDays(1))
        val rrule = habitRrule(f.frequency)
        val existing = itemRepository.getById(alarmId)
        if (existing == null) {
            val alarm = Item.newChild(goal, ItemKind.HABIT_ALARM, goal.title, now)
                .copy(startAt = start, rrule = rrule, id = alarmId, treePath = goal.treePath + alarmId + "/")
            itemRepository.create(alarm)
        } else {
            itemRepository.update(
                existing.copy(
                    title = goal.title,
                    startAt = start,
                    rrule = rrule,
                    status = ItemStatus.TODO,
                    completedAt = null,
                    updatedAt = now,
                ),
            )
        }
        reminderRepository.deleteOf(alarmId)
        reminderRepository.upsert(
            Reminder(id = Ids.newId(), itemId = alarmId, triggerAt = start, offsetMinutes = 0, enabled = true),
        )
    }

    private fun habitRrule(freq: HabitFrequency): String = when (freq) {
        HabitFrequency.Daily -> "FREQ=DAILY"
        HabitFrequency.Weekdays -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        HabitFrequency.Weekend -> "FREQ=WEEKLY;BYDAY=SA,SU"
        is HabitFrequency.Custom -> "FREQ=WEEKLY;BYDAY=" + freq.days.sorted().joinToString(",") { dayCodeOf(it) }
    }

    private fun dayCodeOf(isoDay: Int): String =
        listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").getOrElse(isoDay - 1) { "MO" }

    // ---------------------------------------------------------------- 危险操作

    /** 删除目标：连同子项、提醒、抽屉一起清掉。 */
    fun delete(onDone: () -> Unit) {
        val id = _form.value.goalId ?: return
        viewModelScope.launch {
            itemRepository.deleteSubtree(id)
            reminderRepository.deleteOf(id)
            reminderRepository.deleteOf(habitAlarmId(id))
            extRepository.remove(ExtOwner.ITEM, id)
            onDone()
        }
    }

    /** 归档（= 放弃）：进「已归档」列表，可恢复。 */
    fun archive(onDone: () -> Unit) {
        val id = _form.value.goalId ?: return
        viewModelScope.launch {
            itemRepository.getById(id)?.let {
                itemRepository.update(it.copy(status = ItemStatus.CANCELLED, updatedAt = Instant.now()))
            }
            // 归档后提醒必须停掉，否则目标已经进了归档、通知还在响。
            reminderRepository.deleteOf(id)
            itemRepository.getById(habitAlarmId(id))?.let { itemRepository.deleteSubtree(it.id) }
            reminderRepository.deleteOf(habitAlarmId(id))
            onDone()
        }
    }

    /** 标记完成（有子项时由子项汇总，这里只对习惯/数量开放）。 */
    fun markDone(onDone: () -> Unit) {
        val id = _form.value.goalId ?: return
        viewModelScope.launch {
            itemRepository.getById(id)?.let {
                itemRepository.update(
                    it.copy(status = ItemStatus.DONE, completedAt = Instant.now(), updatedAt = Instant.now()),
                )
            }
            reminderRepository.deleteOf(id)
            onDone()
        }
    }

    /** 重置进度（习惯清空打卡、数量清空记录、分步取消所有子项完成）。 */
    fun resetProgress() {
        val id = _form.value.goalId ?: return
        viewModelScope.launch {
            val goal = itemRepository.getById(id) ?: return@launch
            when (goal.goalType ?: GoalType.STEP) {
                GoalType.STEP -> itemRepository.getChildren(id)
                    .filter { it.kind == ItemKind.STEP && it.status != ItemStatus.TODO }
                    .forEach {
                        itemRepository.update(it.copy(status = ItemStatus.TODO, completedAt = null, updatedAt = Instant.now()))
                    }
                GoalType.QUANTITY -> goalLogRepository.observeQuantityLogs(id).first()
                    .forEach { goalLogRepository.deleteQuantityLog(it.id) }
                GoalType.HABIT -> goalLogRepository.observeHabitLogs(id).first()
                    .forEach { goalLogRepository.toggleHabit(id, it.epochDay) }
            }
        }
    }
}

/** 新建 / 目标设置页的完整界面状态。 */
data class PlanGoalEditorUiState(
    val form: PlanGoalForm = PlanGoalForm(),
    /** 可关联的日程候选（「关联日程」弹窗的数据）。 */
    val linkableEvents: List<LinkEventOption> = emptyList(),
    val saved: Boolean = false,
)

/** 目标百分比（表单概览卡用）。 */
internal fun percentOf(done: Int, total: Int): Int =
    if (total <= 0) 0 else (done * 100.0 / total).roundToInt().coerceIn(0, 100)

/** 「关联日程」的一个候选。 */
data class LinkEventOption(val id: String, val title: String, val meta: String)

/**
 * 挑出可关联的日程。
 *
 * 关键词匹配很朴素（按 2 字滑窗取词），但比「全部日程按时间排」有用得多：
 * 用户点「关联日程」时，脑子里想的是跟这个目标同名/同课的那条日程。
 */
internal fun buildLinkableEvents(
    list: List<Item>,
    goalTitle: String,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    limit: Int = 20,
): List<LinkEventOption> {
    val keywords = keywordsOf(goalTitle)
    val events = list
        .filter { it.kind == ItemKind.EVENT && it.status != ItemStatus.CANCELLED }
        .mapNotNull { event ->
            val day = event.startAt?.atZone(zone)?.toLocalDate() ?: return@mapNotNull null
            if (day.isBefore(today)) return@mapNotNull null
            val zoned = event.startAt.atZone(zone)
            val end = event.endAt?.atZone(zone)
            val time = if (end != null && end.toLocalDate() == day) {
                "%02d:%02d – %02d:%02d".format(zoned.hour, zoned.minute, end.hour, end.minute)
            } else {
                "%02d:%02d".format(zoned.hour, zoned.minute)
            }
            val location = event.location?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            Triple(
                LinkEventOption(event.id, event.title, "${PlanUi.monthDay(day)} $time$location"),
                day,
                keywords.any { it.isNotBlank() && event.title.contains(it) },
            )
        }
    return events
        .sortedWith(compareByDescending<Triple<LinkEventOption, LocalDate, Boolean>> { it.third }.thenBy { it.second })
        .take(limit)
        .map { it.first }
}

/** 取 2 字以上的中文词与长度 ≥3 的英文/数字串作为关键词。 */
private fun keywordsOf(title: String): Set<String> {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return emptySet()
    val out = mutableSetOf<String>()
    val cjk = Regex("[\\u4e00-\\u9fa5]{2,}")
    cjk.findAll(trimmed).forEach { match ->
        val word = match.value
        // 拆成 2 字滑窗：中文没有空格，「期末复习计划」要能匹配到「复习」
        for (i in 0..(word.length - 2)) out += word.substring(i, i + 2)
    }
    Regex("[A-Za-z0-9]{3,}").findAll(trimmed).forEach { out += it.value }
    return out
}
