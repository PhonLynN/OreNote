package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.focus.FocusStats
import com.phonlynn.oreplan.domain.focus.HeatCell
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.repository.FocusRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 记录列表里的一行。 */
data class FocusRecordRow(
    val id: String,
    val title: String,
    /** `14:05 – 14:30 · 学习`。 */
    val meta: String,
    /** `25:00`。 */
    val duration: String,
    val modeLabel: String,
    /** 时间轴竖条颜色（按关联目标类型；自由专注用 accent）。 */
    val colorToken: GoalType?,
    /** 与预计用时的对照（只有带计划时长时才有）。 */
    val compare: String?,
    val goalId: String?,
)

data class FocusRecordsUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    /** 热力图（52 周）。 */
    val heatmap: List<List<HeatCell>> = emptyList(),
    val heatmapLabels: Map<Int, String> = emptyMap(),
    /** 选中那天的汇总。 */
    val selectedDate: LocalDate = LocalDate.now(),
    val dayMinutes: Int = 0,
    val dayCount: Int = 0,
    val rows: List<FocusRecordRow> = emptyList(),
    /** 日历筛选：当前显示的月份。 */
    val pickerMonth: LocalDate = LocalDate.now().withDayOfMonth(1),
)

/**
 * 「规划 · 专注记录」的数据（设计稿 `DnhqZ`）。
 *
 * 热力图 + 按天列表 + 日历筛选，三者都从同一份专注记录算出来。
 */
@HiltViewModel
class FocusRecordsViewModel @Inject constructor(
    private val focusRepository: FocusRepository,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val selectedDate = MutableStateFlow(LocalDate.now(zone))
    private val pickerMonth = MutableStateFlow(LocalDate.now(zone).withDayOfMonth(1))

    private val sessions = focusRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val goals = itemRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val uiState: StateFlow<FocusRecordsUiState> = combine(
        sessions,
        goals,
        selectedDate,
        pickerMonth,
    ) { all, items, date, month ->
        build(all, items, date, month)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FocusRecordsUiState(),
    )

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
        pickerMonth.value = date.withDayOfMonth(1)
    }

    fun shiftMonth(delta: Long) {
        pickerMonth.value = pickerMonth.value.plusMonths(delta)
    }

    fun delete(sessionId: String) {
        viewModelScope.launch { focusRepository.delete(sessionId) }
    }

    private fun build(
        all: List<FocusSession>,
        items: List<Item>,
        date: LocalDate,
        month: LocalDate,
    ): FocusRecordsUiState {
        val byId = items.associateBy { it.id }
        // 一整年（52 周）：热力图要能左右拖到更早的日子（用户 2026-10-02）。
        // 17 周只有 4 个月，「拖动看历史」就没什么可看的了。
        val grid = FocusStats.heatmap(all, LocalDate.now(zone), zone, weeks = HEATMAP_WEEKS)
        val ofDay = all.filter { it.startedAt.atZone(zone).toLocalDate() == date }
            .sortedByDescending { it.startedAt }

        val rows = ofDay.map { session ->
            val goal = session.itemId?.let { byId[it] }
            val unit = goal?.unit.orEmpty()
            FocusRecordRow(
                id = session.id,
                title = session.label ?: goal?.title ?: "自由专注",
                meta = FocusStats.rangeText(session, zone) + focusTag(session, goal),
                duration = FocusStats.clockText(session.minutes * 60L),
                modeLabel = session.kind.label,
                colorToken = goal?.goalType,
                compare = session.plannedMinutes?.let { planned ->
                    val over = session.minutes - planned
                    when {
                        session.minutes >= planned -> "预计 ${FocusStats.clockText(planned * 60L)} · 符合预期"
                        else -> "预计 ${FocusStats.clockText(planned * 60L)} · 提前 ${FocusStats.clockText(-over * 60L)}"
                    }
                },
                goalId = goal?.id,
            )
        }

        return FocusRecordsUiState(
            loaded = true,
            today = LocalDate.now(zone),
            heatmap = grid,
            heatmapLabels = FocusStats.heatmapMonthLabels(grid),
            selectedDate = date,
            dayMinutes = ofDay.sumOf { it.minutes },
            dayCount = ofDay.size,
            rows = rows,
            pickerMonth = month,
        )
    }

    /**
     * 行尾的「· 学习 / 阅读 / 专注」标签。
     *
     * 设计稿三行分别写「学习 / 阅读 / 专注」——那是**用户自己填的**标签。
     * 我们没有这个字段，于是用目标类型翻译一个稳定的说法（不编造用户数据）。
     */
    private fun focusTag(session: FocusSession, goal: Item?): String {
        if (goal == null) return ""
        val tag = when (goal.goalType) {
            GoalType.STEP -> "学习"
            GoalType.QUANTITY -> "累计"
            GoalType.HABIT -> "习惯"
            null -> return ""
        }
        return " · $tag"
    }

    companion object {
        /** 热力图列数（一周一列）。52 周 = 一整年，够左右拖出「有历史」的感觉。 */
        const val HEATMAP_WEEKS = 52
    }
}
