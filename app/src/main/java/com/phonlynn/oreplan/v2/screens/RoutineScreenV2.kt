package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.routine.BigBreak
import com.phonlynn.oreplan.domain.routine.RoutineConfig
import com.phonlynn.oreplan.domain.routine.RoutineSchedule
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VIconButton
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoutineV2ViewModel @Inject constructor(
    private val appSettings: AppSettingsStore,
) : ViewModel() {

    private val _draft = MutableStateFlow(RoutineConfig.Default)
    val draft: StateFlow<RoutineConfig> = _draft.asStateFlow()

    /** 用户一旦动过任何控件，就不再跟随存储里的最新值，避免编辑中途被覆盖。 */
    private var userEdited = false

    init {
        viewModelScope.launch {
            appSettings.settings.collect { s -> if (!userEdited) _draft.value = s.routine }
        }
    }

    private fun edit(transform: (RoutineConfig) -> RoutineConfig) {
        userEdited = true
        _draft.value = transform(_draft.value)
    }

    fun setPeriodMinutes(v: Int) = edit { it.copy(periodMinutes = v.coerceIn(10, 120)) }
    fun setLunchMinutes(v: Int) = edit { it.copy(lunchBreakMinutes = v.coerceIn(0, 180)) }
    fun setDinnerMinutes(v: Int) = edit { it.copy(dinnerBreakMinutes = v.coerceIn(0, 180)) }
    fun setSmallBreakMinutes(v: Int) = edit { it.copy(smallBreakMinutes = v.coerceIn(0, 60)) }
    fun setMorningCount(v: Int) = edit { it.copy(morningCount = v.coerceIn(0, 12)) }
    fun setAfternoonCount(v: Int) = edit { it.copy(afternoonCount = v.coerceIn(0, 12)) }
    fun setEveningCount(v: Int) = edit { it.copy(eveningCount = v.coerceIn(0, 12)) }

    fun setBigBreakMinutes(index: Int, minutes: Int) = edit { cfg ->
        val list = cfg.bigBreaks.toMutableList()
        if (index in list.indices) list[index] = list[index].copy(minutes = minutes)
        cfg.copy(bigBreaks = list)
    }

    fun removeBigBreak(index: Int) = edit { cfg ->
        cfg.copy(bigBreaks = cfg.bigBreaks.filterIndexed { i, _ -> i != index })
    }

    fun addBigBreak(afterPeriod: Int, minutes: Int) = edit { cfg ->
        val list = cfg.bigBreaks.toMutableList()
        list += BigBreak(afterPeriod = afterPeriod, minutes = minutes)
        cfg.copy(bigBreaks = list.sortedBy { it.afterPeriod })
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            appSettings.update { it.copy(routine = _draft.value) }
            onSaved()
        }
    }
}

@Composable
fun RoutineScreenV2(
    onBack: () -> Unit,
    viewModel: RoutineV2ViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    var editingBreakIndex by remember { mutableStateOf<Int?>(null) }
    var addingBreak by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        // 设计稿这里的标题是 25/700，与 VNavBar 的 16/600 不同，按规格照抄。
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIconButton(Lucide.ChevronLeft, onBack, tint = VColors.ink)
            Spacer(Modifier.width(12.dp))
            VText("作息时间", VTypo.pageTitle, color = VColors.ink, maxLines = 1)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 时间设置
            SectionBlock("时间设置") {
                StepperRow("每节时长", "${draft.periodMinutes} 分钟", draft.periodMinutes, 5, 10, 120) { viewModel.setPeriodMinutes(it) }
                VDivider(16.dp)
                StepperRow("午休时长", "${draft.lunchBreakMinutes} 分钟", draft.lunchBreakMinutes, 5, 0, 180) { viewModel.setLunchMinutes(it) }
                VDivider(16.dp)
                StepperRow("晚休时长", "${draft.dinnerBreakMinutes} 分钟", draft.dinnerBreakMinutes, 5, 0, 180) { viewModel.setDinnerMinutes(it) }
            }

            // 节次安排
            SectionBlock("节次安排") {
                StepperRow("上午节数", "${draft.morningCount} 节", draft.morningCount, 1, 0, 12) { viewModel.setMorningCount(it) }
                VDivider(16.dp)
                StepperRow("下午节数", "${draft.afternoonCount} 节", draft.afternoonCount, 1, 0, 12) { viewModel.setAfternoonCount(it) }
                VDivider(16.dp)
                StepperRow("晚课节数", "${draft.eveningCount} 节", draft.eveningCount, 1, 0, 12) { viewModel.setEveningCount(it) }
            }

            // 课间设置
            SectionBlock("课间设置") {
                StepperRow("小课间时长", "${draft.smallBreakMinutes} 分钟", draft.smallBreakMinutes, 5, 0, 60) { viewModel.setSmallBreakMinutes(it) }
            }

            // 大课间
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VSectionHead(title = "大课间", note = "共 ${draft.bigBreaks.size} 个")
                VCard {
                    draft.bigBreaks.forEachIndexed { index, br ->
                        BigBreakRow(br) { editingBreakIndex = index }
                        if (index != draft.bigBreaks.lastIndex) VDivider(16.dp)
                    }
                    if (draft.bigBreaks.isNotEmpty()) VDivider(16.dp)
                    AddBreakRow { addingBreak = true }
                }
            }

            VText(
                RoutineSchedule.summary(draft),
                VTypo.caption.copy(lineHeight = 15.sp),
                color = VColors.ink3,
            )
        }

        // 底部保存
        Box(
            Modifier
                .fillMaxWidth()
                .background(VColors.scrimTop)
                .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 16.dp),
        ) {
            SaveButton("保存作息时间") { viewModel.save(onSaved = onBack) }
        }
    }

    editingBreakIndex?.let { index ->
        val br = draft.bigBreaks.getOrNull(index) ?: return@let
        BigBreakDialog(
            afterPeriod = br.afterPeriod,
            minutes = br.minutes,
            allowRemove = true,
            onConfirm = { _, minutes -> viewModel.setBigBreakMinutes(index, minutes) },
            onRemove = { viewModel.removeBigBreak(index) },
            onDismiss = { editingBreakIndex = null },
        )
    }

    if (addingBreak) {
        BigBreakDialog(
            afterPeriod = (draft.bigBreaks.lastOrNull()?.afterPeriod ?: 1) + 1,
            minutes = 15,
            allowRemove = false,
            onConfirm = { afterPeriod, minutes -> viewModel.addBigBreak(afterPeriod, minutes) },
            onRemove = null,
            onDismiss = { addingBreak = false },
        )
    }
}

@Composable
private fun SectionBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VSectionHead(title = title)
        VCard { content() }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    value: Int,
    step: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        VText(label, VTypo.body, color = VColors.ink, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StepperButton(Lucide.Minus, value > min) { onChange(value - step) }
            VText(valueText, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
            StepperButton(Lucide.Plus, value < max) { onChange(value + step) }
        }
    }
}

@Composable
private fun StepperButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .background(VColors.bg, RoundedCornerShape(9.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(9.dp))
            .vPressable(scaleDown = 0.9f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = if (enabled) VColors.ink2 else VColors.ink3)
    }
}

@Composable
private fun BigBreakRow(breakItem: BigBreak, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        VText("第 ${breakItem.afterPeriod} 节后", VTypo.body, color = VColors.ink, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            VText("${breakItem.minutes} 分钟", VTypo.bodyMed, color = VColors.ink, maxLines = 1)
            Icon(Lucide.ChevronRight, contentDescription = null, Modifier.size(16.dp), tint = VColors.ink3)
        }
    }
}

@Composable
private fun AddBreakRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Plus, contentDescription = null, Modifier.size(16.dp), tint = VColors.accent)
        Spacer(Modifier.width(6.dp))
        VText("添加大课间", VTypo.bodyMed, color = VColors.accent, maxLines = 1)
    }
}

@Composable
private fun BigBreakDialog(
    afterPeriod: Int,
    minutes: Int,
    allowRemove: Boolean,
    onConfirm: (afterPeriod: Int, minutes: Int) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var after by remember { mutableStateOf(afterPeriod) }
    var mins by remember { mutableStateOf(minutes) }

    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText(if (allowRemove) "编辑大课间" else "添加大课间", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                VText("第", VTypo.body, color = VColors.ink)
                StepperButton(Lucide.Minus, after > 1) { after-- }
                VText("$after 节", VTypo.bodyMed, color = VColors.ink)
                StepperButton(Lucide.Plus, after < 20) { after++ }
                Spacer(Modifier.weight(1f))
                VText("后", VTypo.body, color = VColors.ink)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                VText("时长", VTypo.body, color = VColors.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepperButton(Lucide.Minus, mins > 5) { mins -= 5 }
                    VText("$mins 分钟", VTypo.bodyMed, color = VColors.ink)
                    StepperButton(Lucide.Plus, mins < 120) { mins += 5 }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (allowRemove && onRemove != null) {
                    Box(
                        Modifier
                            .height(48.dp)
                            .background(VColors.roseSoft, RoundedCornerShape(14.dp))
                            .vPressable(scaleDown = 0.96f, onClick = onRemove),
                        contentAlignment = Alignment.Center,
                    ) {
                        VText("移除", VTypo.buttonBold, color = VColors.roseDeep)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Box(
                    Modifier.weight(1f).height(48.dp).background(VColors.surface2, RoundedCornerShape(14.dp))
                        .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("取消", VTypo.buttonBold, color = VColors.ink)
                }
                Box(
                    Modifier.weight(1f).height(48.dp).background(VColors.accent, RoundedCornerShape(14.dp))
                        .vPressable(scaleDown = 0.96f, onClick = { onConfirm(after, mins); onDismiss() }),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("确定", VTypo.buttonBold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun SaveButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(VColors.accent, RoundedCornerShape(15.dp))
            .vPressable(scaleDown = 0.97f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(text, VTypo.button, color = Color.White, maxLines = 1)
    }
}
