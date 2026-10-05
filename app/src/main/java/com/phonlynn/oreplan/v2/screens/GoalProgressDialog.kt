package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.domain.model.StepOrderMode
import com.phonlynn.oreplan.domain.plan.PlanMeta
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogButtons
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.components.VBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 「更新进度」弹窗（专注完成页的反馈卡）。
 *
 * 目标类型不同，「推进」的动作也不同，这里按类型给**能真的落库**的那一种：
 * - 分步：勾掉下一步（顺序解锁时只有当前那一步可点）；
 * - 数量：记一笔并写进 `quantity_logs`；
 * - 习惯：今天打卡。
 *
 * 返回一段结果文案，由调用方显示在卡片上 —— 用户点完必须看得到反馈。
 */
@Composable
fun GoalProgressDialog(
    goalId: String?,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
    viewModel: GoalProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var amount by remember { mutableStateOf("1") }
    var label by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(goalId) { viewModel.load(goalId) }

    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("更新进度", VTypo.dialogTitle, color = VColors.ink)
            if (state.goal == null) {
                VText("目标已经不存在了。", VTypo.caption12, color = VColors.ink3)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("关闭", VTypo.button, color = VColors.ink2)
                }
                return@Column
            }

            val color = PlanUi.typeColor(state.type)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 类型徽章走公共的 VBadge（自写的 TypeBadge 已删）；
                // 色与图标仍取自 PlanUi 的类型口径。
                VBadge(
                    background = PlanUi.typeSoft(state.type),
                    icon = PlanUi.typeIcon(state.type),
                    iconTint = PlanUi.typeColor(state.type),
                )
                Column(Modifier.weight(1f)) {
                    VText(state.goal!!.title, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                    VText(state.progressText, VTypo.caption, color = VColors.ink3, maxLines = 1)
                }
            }

            when (state.type) {
                GoalType.STEP -> {
                    if (state.nextStep == null) {
                        VText("所有子项都已经完成了。", VTypo.caption12, color = VColors.ink3)
                        PrimaryAction("好", color) { onDone("已全部完成") }
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(VColors.bg, RoundedCornerShape(13.dp))
                                .padding(14.dp),
                        ) {
                            VText("下一步", VTypo.micro, color = VColors.ink3)
                            Spacer(Modifier.height(3.dp))
                            VText(state.nextStep!!.title, VTypo.bodyMed, color = VColors.ink, maxLines = 2)
                        }
                        PrimaryAction("完成这一步", color) {
                            viewModel.completeNextStep { message -> onDone(message) }
                        }
                    }
                }

                GoalType.QUANTITY -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1L, 2L, 5L, 10L).forEach { preset ->
                            val active = amount == preset.toString()
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .background(if (active) color else VColors.surface2, RoundedCornerShape(12.dp))
                                    .vPressable(scaleDown = 0.94f) { amount = preset.toString() },
                                contentAlignment = Alignment.Center,
                            ) {
                                VText("+$preset", VTypo.caption12, color = if (active) Color.White else VColors.ink2)
                            }
                        }
                    }
                    PlanTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                        placeholder = "数量",
                        textStyle = VTypo.numValue,
                    )
                    PlanTextField(
                        value = label,
                        onValueChange = { label = it },
                        placeholder = "备注（可选）",
                    )
                    PrimaryAction("记一笔", color) {
                        val value = amount.toLongOrNull() ?: 1L
                        viewModel.addQuantity(value, label) { message -> onDone(message) }
                    }
                }

                GoalType.HABIT -> {
                    VText(
                        if (state.habitTodayChecked) "今天已经打过卡了。" else "今天还没打卡。",
                        VTypo.caption12,
                        color = VColors.ink2,
                    )
                    PrimaryAction(
                        if (state.habitTodayChecked) "取消今日打卡" else "今日打卡",
                        color,
                    ) {
                        viewModel.toggleToday { message -> onDone(message) }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(VColors.surface2, RoundedCornerShape(13.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                VText("稍后再说", VTypo.button, color = VColors.ink2)
            }
        }
    }
}

@Composable
private fun PrimaryAction(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(color, RoundedCornerShape(13.dp))
            .vPressable(scaleDown = 0.96f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
            VText(text, VTypo.buttonBold.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
    }
}

// ---------------------------------------------------------------- ViewModel

data class GoalProgressUiState(
    val goal: Item? = null,
    val type: GoalType = GoalType.STEP,
    val progressText: String = "",
    val nextStep: Item? = null,
    val habitTodayChecked: Boolean = false,
)

@HiltViewModel
class GoalProgressViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val goalLogRepository: GoalLogRepository,
    extRepository: EntityExtRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val _uiState = MutableStateFlow(GoalProgressUiState())
    val uiState: StateFlow<GoalProgressUiState> = _uiState.asStateFlow()

    fun load(goalId: String?) {
        if (goalId.isNullOrBlank()) return
        viewModelScope.launch {
            val goal = itemRepository.getById(goalId) ?: return@launch
            val type = goal.goalType ?: GoalType.STEP
            val steps = itemRepository.getChildren(goalId)
                .filter { it.kind == ItemKind.STEP }
                .sortedBy { it.orderIndex }
            val quantities = goalLogRepository.observeQuantityLogs(goalId).first()
            val habits = goalLogRepository.observeHabitLogs(goalId).first()
            val today = LocalDate.now(zone)
            val (done, total) = PlanUi.stepDoneTotal(steps)
            _uiState.value = GoalProgressUiState(
                goal = goal,
                type = type,
                progressText = when (type) {
                    GoalType.STEP -> "已完成 $done / $total 步"
                    GoalType.QUANTITY -> "已记录 ${PlanUi.quantityTotal(quantities)} / ${goal.targetValue ?: 0} ${goal.unit.orEmpty()}"
                    GoalType.HABIT -> "本周 ${PlanUi.weekDone(habits.map { it.epochDay }.toSet(), today)} 天"
                },
                nextStep = if (type == GoalType.STEP) PlanUi.nextStep(steps) else null,
                habitTodayChecked = habits.any { it.epochDay == today.toEpochDay().toInt() },
            )
        }
    }

    fun completeNextStep(onDone: (String) -> Unit) {
        val goal = _uiState.value.goal ?: return
        viewModelScope.launch {
            val steps = itemRepository.getChildren(goal.id)
                .filter { it.kind == ItemKind.STEP }
                .sortedBy { it.orderIndex }
            val next = PlanUi.nextStep(steps) ?: return@launch
            val mode = goal.stepOrderMode ?: StepOrderMode.SEQ
            val current = steps.firstOrNull { it.status != ItemStatus.DONE }?.id
            if (mode != StepOrderMode.FREE && current != next.id) return@launch
            val now = Instant.now()
            itemRepository.update(next.copy(status = ItemStatus.DONE, completedAt = now, updatedAt = now))
            onDone("已推进：${next.title}")
            load(goal.id)
        }
    }

    fun addQuantity(amount: Long, label: String, onDone: (String) -> Unit) {
        val goal = _uiState.value.goal ?: return
        if (amount <= 0) return
        viewModelScope.launch {
            goalLogRepository.addQuantityLog(
                QuantityLog(
                    id = Ids.newId(),
                    itemId = goal.id,
                    at = Instant.now(),
                    amount = amount,
                    label = label.trim().ifBlank { null },
                ),
            )
            onDone("已记录 +$amount ${goal.unit.orEmpty()}".trim())
            load(goal.id)
        }
    }

    fun toggleToday(onDone: (String) -> Unit) {
        val goal = _uiState.value.goal ?: return
        val epoch = LocalDate.now(zone).toEpochDay().toInt()
        viewModelScope.launch {
            goalLogRepository.toggleHabit(goal.id, epoch)
            onDone(if (_uiState.value.habitTodayChecked) "已取消今天的打卡" else "今天已打卡")
            load(goal.id)
        }
    }
}
