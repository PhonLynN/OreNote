package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 归档的状态筛选（设计稿 Filter Button 弹出的面板）。 */
enum class ArchiveStatusFilter(val label: String) {
    ALL("全部"),
    ACHIEVED("已完成"),
    ABANDONED("已放弃"),
}

/** 归档的类型筛选。 */
enum class ArchiveTypeFilter(val label: String) {
    ALL("全部类型"),
    STEP("分步"),
    QUANTITY("数量"),
    HABIT("习惯"),
}

data class ArchivedGoalRow(
    val id: String,
    val title: String,
    val type: GoalType,
    val archivedAt: LocalDate,
    val achieved: Boolean,
)

data class ArchiveSection(val monthKey: String, val monthLabel: String, val rows: List<ArchivedGoalRow>)

data class PlanArchiveUiState(
    val loaded: Boolean = false,
    val total: Int = 0,
    val achieved: Int = 0,
    val abandoned: Int = 0,
    val statusFilter: ArchiveStatusFilter = ArchiveStatusFilter.ALL,
    val typeFilter: ArchiveTypeFilter = ArchiveTypeFilter.ALL,
    val sections: List<ArchiveSection> = emptyList(),
)

/**
 * 「规划 · 已归档」（设计稿 `Mc5BR`）。
 *
 * 设计稿给出的三段统计（已归档 / 已完成 / 已放弃）与月份分组都由这里算。
 * 恢复目标时按**当前进度**决定回「已完成」还是「待办」——
 * 归档动作本身不记录「当时算不算达成」，只能用当前数据反推。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanArchiveViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val goalLogRepository: GoalLogRepository,
    private val reminderRepository: ReminderRepository,
    private val extRepository: EntityExtRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val items = itemRepository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val status = MutableStateFlow(ArchiveStatusFilter.ALL)
    private val type = MutableStateFlow(ArchiveTypeFilter.ALL)

    /** 只有数量目标需要逐目标订阅记录（进度要靠它算）。 */
    private val quantityLogs = items.flatMapLatest { list ->
        val ids = list.filter { it.kind == ItemKind.GOAL && it.goalType == GoalType.QUANTITY }.map { it.id }
        if (ids.isEmpty()) {
            flowOf(emptyMap<String, List<QuantityLog>>())
        } else {
            combine(ids.map { id -> goalLogRepository.observeQuantityLogs(id) }) { arrays ->
                ids.zip(arrays).associate { (id, logs) -> id to logs }
            }
        }
    }

    val uiState: StateFlow<PlanArchiveUiState> = combine(
        items,
        quantityLogs,
        status,
        type,
    ) { list, quantities, statusFilter, typeFilter ->
        build(list, quantities, statusFilter, typeFilter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlanArchiveUiState(),
    )

    fun setStatusFilter(value: ArchiveStatusFilter) {
        status.value = value
    }

    fun setTypeFilter(value: ArchiveTypeFilter) {
        type.value = value
    }

    /** 恢复：回到进行中。已达成过的直接进「已完成」，否则回「待办」。 */
    fun restore(goalId: String) {
        viewModelScope.launch {
            val goal = itemRepository.getById(goalId) ?: return@launch
            val achieved = isAchieved(goal)
            itemRepository.update(
                goal.copy(
                    status = if (achieved) ItemStatus.DONE else ItemStatus.TODO,
                    completedAt = if (achieved) Instant.now() else null,
                    updatedAt = Instant.now(),
                ),
            )
        }
    }

    /** 彻底删除：条目 + 提醒 + 抽屉一并清掉，不留孤儿行。 */
    fun delete(goalId: String) {
        viewModelScope.launch {
            itemRepository.deleteSubtree(goalId)
            reminderRepository.deleteOf(goalId)
            extRepository.remove(ExtOwner.ITEM, goalId)
        }
    }

    private suspend fun isAchieved(goal: Item): Boolean = when (goal.goalType ?: GoalType.STEP) {
        GoalType.STEP -> {
            val steps = itemRepository.getChildren(goal.id).filter { it.kind == ItemKind.STEP }
            val (done, total) = PlanUi.stepDoneTotal(steps)
            total > 0 && done == total
        }
        GoalType.QUANTITY -> {
            val target = goal.targetValue ?: 0L
            target > 0 && PlanUi.quantityTotal(goalLogRepository.observeQuantityLogs(goal.id).first()) >= target
        }
        GoalType.HABIT -> goalLogRepository.observeHabitLogs(goal.id).first().isNotEmpty()
    }

    private fun build(
        list: List<Item>,
        quantities: Map<String, List<QuantityLog>>,
        statusFilter: ArchiveStatusFilter,
        typeFilter: ArchiveTypeFilter,
    ): PlanArchiveUiState {
        val archived = list.filter { it.kind == ItemKind.GOAL && it.status == ItemStatus.CANCELLED }
        val stepsByGoal = list.filter { it.kind == ItemKind.STEP }.groupBy { it.parentId }

        val rows = archived.map { goal ->
            val goalType = goal.goalType ?: GoalType.STEP
            ArchivedGoalRow(
                id = goal.id,
                title = goal.title,
                type = goalType,
                archivedAt = PlanUi.toLocalDate(goal.updatedAt, zone) ?: LocalDate.now(zone),
                achieved = when (goalType) {
                    GoalType.STEP -> {
                        val (done, total) = PlanUi.stepDoneTotal(stepsByGoal[goal.id].orEmpty())
                        total > 0 && done == total
                    }
                    GoalType.QUANTITY -> {
                        val target = goal.targetValue ?: 0L
                        target > 0 && PlanUi.quantityTotal(quantities[goal.id].orEmpty()) >= target
                    }
                    // 习惯没有「完成」终态：归档时只要打过卡就算坚持过。
                    GoalType.HABIT -> true
                },
            )
        }

        val filtered = rows
            .filter {
                when (statusFilter) {
                    ArchiveStatusFilter.ALL -> true
                    ArchiveStatusFilter.ACHIEVED -> it.achieved
                    ArchiveStatusFilter.ABANDONED -> !it.achieved
                }
            }
            .filter {
                when (typeFilter) {
                    ArchiveTypeFilter.ALL -> true
                    ArchiveTypeFilter.STEP -> it.type == GoalType.STEP
                    ArchiveTypeFilter.QUANTITY -> it.type == GoalType.QUANTITY
                    ArchiveTypeFilter.HABIT -> it.type == GoalType.HABIT
                }
            }
            .sortedByDescending { it.archivedAt }

        val sections = filtered
            .groupBy { it.archivedAt.year * 100 + it.archivedAt.monthValue }
            .entries
            .sortedByDescending { it.key }
            .map { (key, group) ->
                val first = group.first().archivedAt
                ArchiveSection(
                    monthKey = key.toString(),
                    monthLabel = "${first.year}年${first.monthValue}月",
                    rows = group,
                )
            }

        return PlanArchiveUiState(
            loaded = true,
            total = rows.size,
            achieved = rows.count { it.achieved },
            abandoned = rows.count { !it.achieved },
            statusFilter = statusFilter,
            typeFilter = typeFilter,
            sections = sections,
        )
    }
}
