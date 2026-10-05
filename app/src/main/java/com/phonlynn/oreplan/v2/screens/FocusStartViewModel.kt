package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.focus.FocusMode
import com.phonlynn.oreplan.domain.focus.FocusTarget
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.plan.PlanMeta
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.usecase.BuildAgendaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** 「专注对象」三选一（设计稿 Object Row）。 */
enum class FocusTargetKind(val label: String) {
    FREE("自由专注"),
    GOAL("关联目标"),
    EVENT("关联日程"),
}

/** 可选的关联对象一行。 */
data class FocusPickRow(
    val id: String,
    val title: String,
    val meta: String,
    /** 「进行中」胶囊（设计稿 Now Pill）。 */
    val now: Boolean = false,
)

data class FocusStartUiState(
    val loaded: Boolean = false,
    val mode: FocusMode = FocusMode.COUNT_DOWN,
    val minutes: Int = 25,
    /** 用户是否选了「自定义」档（决定时长行里哪一格高亮）。 */
    val customMinutes: Boolean = false,
    val targetKind: FocusTargetKind = FocusTargetKind.FREE,
    val goals: List<FocusPickRow> = emptyList(),
    val events: List<FocusPickRow> = emptyList(),
    val selectedGoalId: String? = null,
    val selectedEventId: String? = null,
    /** 目标自己关联的日程（在「关联目标」下作为推荐出现在顶部）。 */
    val linkedEventIds: Set<String> = emptySet(),
) {
    /** 时长档位（设计稿 15 / 25 / 45 / 60 / 自定义）。 */
    val presets: List<Int> get() = listOf(15, 25, 45, 60)

    /** 主按钮可用性：关联目标/日程时必须真的选中了一条。 */
    val canStart: Boolean
        get() = when (targetKind) {
            FocusTargetKind.FREE -> true
            FocusTargetKind.GOAL -> !goals.isEmpty()
            FocusTargetKind.EVENT -> !events.isEmpty()
        }

    /** 当前要选的行（设计稿 Pick Card）。 */
    val pickRows: List<FocusPickRow>
        get() = when (targetKind) {
            FocusTargetKind.FREE -> emptyList()
            FocusTargetKind.GOAL -> goals
            FocusTargetKind.EVENT -> events
        }

    fun selectedId(): String? = when (targetKind) {
        FocusTargetKind.FREE -> null
        FocusTargetKind.GOAL -> selectedGoalId
        FocusTargetKind.EVENT -> selectedEventId
    }

    /** 「开始专注」按钮文案（设计稿统一是「开始专注」）。 */
    val startLabel: String get() = "开始专注"
}

/**
 * 「规划 · 专注开始」的数据（设计稿 `E1Vcu3`）。
 *
 * 三件事：计时方式、专注时长、专注对象（自由 / 目标 / 今天或关联的日程）。
 */
@HiltViewModel
class FocusStartViewModel @Inject constructor(
    buildAgenda: BuildAgendaUseCase,
    itemRepository: ItemRepository,
    extRepository: EntityExtRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = MutableStateFlow(LocalDate.now(zone))

    private val mode = MutableStateFlow(FocusMode.COUNT_DOWN)
    private val minutes = MutableStateFlow(25)
    private val custom = MutableStateFlow(false)
    private val targetKind = MutableStateFlow(FocusTargetKind.FREE)
    private val selectedGoalId = MutableStateFlow<String?>(null)
    private val selectedEventId = MutableStateFlow<String?>(null)

    /** 今天的议程（含课程与重复日程展开）。 */
    private val todayEntries = buildAgenda
        .observe(LocalDate.now(zone), LocalDate.now(zone), zone)
        .map { agenda -> agenda.on(LocalDate.now(zone)) }

    private val items = itemRepository.observeAll().stateIn(
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

    /** combine 的带类型重载最多 5 路，这里先把「条目 + 抽屉 + 选中的目标」并成一路。 */
    private val goalSource = combine(items, meta, selectedGoalId) { list, ext, goalId ->
        Triple(list, ext, goalId)
    }

    private val eventSource = combine(todayEntries, selectedEventId) { entries, eventId ->
        entries to eventId
    }

    val uiState: StateFlow<FocusStartUiState> = combine(
        mode,
        minutes,
        custom,
        targetKind,
        goalSource,
        eventSource,
    ) { values ->
        val focusMode = values[0] as FocusMode
        val mins = values[1] as Int
        val isCustom = values[2] as Boolean
        val kind = values[3] as FocusTargetKind
        @Suppress("UNCHECKED_CAST")
        val goalData = values[4] as Triple<List<Item>, Map<String, ExtRecord>, String?>
        @Suppress("UNCHECKED_CAST")
        val eventData = values[5] as Pair<List<com.phonlynn.oreplan.domain.model.AgendaEntry>, String?>
        build(focusMode, mins, isCustom, kind, goalData, eventData)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FocusStartUiState(),
    )

    private fun build(
        focusMode: FocusMode,
        mins: Int,
        isCustom: Boolean,
        kind: FocusTargetKind,
        goalData: Triple<List<Item>, Map<String, ExtRecord>, String?>,
        eventData: Pair<List<com.phonlynn.oreplan.domain.model.AgendaEntry>, String?>,
    ): FocusStartUiState {
        val (list, ext, goalId) = goalData
        val (todayAgenda, eventId) = eventData
        val day = LocalDate.now(zone)

        val goals = list.filter { it.kind == ItemKind.GOAL && it.status != ItemStatus.CANCELLED }
            .sortedWith(compareByDescending<Item> { it.pinned }.thenBy { it.orderIndex })
            .map { goal ->
                FocusPickRow(
                    id = goal.id,
                    title = goal.title,
                    meta = goalMeta(goal, day),
                )
            }

        val linked = goalId?.let { id -> ext[id]?.let { record -> PlanMeta.linkedEvents(record.map) } }.orEmpty().toSet()
        val nowMinute = LocalTime.now(zone).let { it.hour * 60 + it.minute }
        val eventRows = todayAgenda
            .sortedBy { it.startMinute ?: Int.MAX_VALUE }
            .map { entry ->
                val start = entry.startMinute
                val end = entry.endMinute
                val timeText = if (start != null && end != null) {
                    "%02d:%02d – %02d:%02d".format(start / 60, start % 60, end / 60, end % 60)
                } else {
                    "全天"
                }
                val location = entry.location?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                FocusPickRow(
                    id = entry.itemId ?: entry.key,
                    title = entry.title,
                    meta = "$timeText$location",
                    now = start != null && end != null && nowMinute in start until end,
                )
            }

        return FocusStartUiState(
            loaded = true,
            mode = focusMode,
            minutes = mins,
            customMinutes = isCustom,
            targetKind = kind,
            goals = goals,
            events = eventRows,
            selectedGoalId = goalId ?: goals.firstOrNull()?.id,
            selectedEventId = eventId ?: eventRows.firstOrNull()?.id,
            linkedEventIds = linked,
        )
    }

    private fun goalMeta(goal: Item, day: LocalDate): String = when (goal.goalType ?: GoalType.STEP) {
        GoalType.STEP -> "分步目标"
        GoalType.QUANTITY -> "数量目标 · ${goal.targetValue ?: 0} ${goal.unit.orEmpty()}".trim()
        GoalType.HABIT -> "习惯目标"
    } + PlanUi.dueText(PlanUi.toLocalDate(goal.softDueAt, zone), day)?.let { " · $it" }.orEmpty()

    fun setMode(value: FocusMode) {
        mode.value = value
        // 番茄钟的默认段长是 25 分钟；从正计时切回带计划的模式时给一个有意义的默认值。
        if (value == FocusMode.POMODORO && !custom.value) minutes.value = 25
    }

    fun setMinutes(value: Int) {
        minutes.value = value
        custom.value = false
    }

    fun setCustomMinutes(value: Int) {
        minutes.value = value
        custom.value = true
    }

    fun setTargetKind(value: FocusTargetKind) {
        targetKind.value = value
    }

    fun selectGoal(id: String) {
        selectedGoalId.value = id
    }

    fun selectEvent(id: String) {
        selectedEventId.value = id
    }

    /** 把界面选择翻译成一次专注的起点。 */
    fun resolve(state: FocusStartUiState): Pair<FocusTarget, FocusMode>? {
        val target = when (state.targetKind) {
            FocusTargetKind.FREE -> FocusTarget.None
            FocusTargetKind.GOAL -> state.selectedGoalId
                ?.let { id -> state.goals.firstOrNull { it.id == id } }
                ?.let { FocusTarget.Goal(it.id, it.title) }
                ?: state.goals.firstOrNull()?.let { FocusTarget.Goal(it.id, it.title) }
                ?: return null
            FocusTargetKind.EVENT -> state.selectedEventId
                ?.let { id -> state.events.firstOrNull { it.id == id } }
                ?.let { FocusTarget.Event(it.id, it.title) }
                ?: state.events.firstOrNull()?.let { FocusTarget.Event(it.id, it.title) }
                ?: return null
        }
        return target to state.mode
    }

    /** 实际采用的分钟数：正计时没有计划时长。 */
    fun plannedMinutes(state: FocusStartUiState): Int? =
        if (state.mode.hasPlan) state.minutes else null
}
