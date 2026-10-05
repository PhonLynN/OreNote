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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.v2.components.VBadge
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VNavBar
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.components.VPrimaryButton
import com.phonlynn.oreplan.v2.components.VSwitch
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 单项提醒设置页（41 规格）。 */
@Composable
fun RemindScreenV2(onBack: () -> Unit) {
    val viewModel: RemindV2ViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickTime by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
        VNavBar(title = "提醒设置", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 主卡
            VCard(radius = 18.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(VColors.accentSoft, RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.BellRing, null, Modifier.size(20.dp), tint = VColors.accent)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        VText("开启提醒", VTypo.section, color = VColors.ink)
                        VText(
                            "事件开始前按设定时间推送提醒通知",
                            VTypo.caption.copy(lineHeight = 15.sp),
                            color = VColors.ink3,
                        )
                    }
                    VSwitch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                }
            }

            FieldLabel("提醒时间")
            VCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 两行预设 chips
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LeadChip("提前 5 分钟", state.offsetMinutes == 5 && !state.customExpanded, Modifier.weight(1f)) {
                            viewModel.selectPreset(5)
                        }
                        LeadChip("提前 15 分钟", state.offsetMinutes == 15 && !state.customExpanded, Modifier.weight(1f)) {
                            viewModel.selectPreset(15)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LeadChip("提前 1 小时", state.offsetMinutes == 60 && !state.customExpanded, Modifier.weight(1f)) {
                            viewModel.selectPreset(60)
                        }
                        LeadChip("提前 1 天", state.offsetMinutes == 1440 && !state.customExpanded, Modifier.weight(1f)) {
                            viewModel.selectPreset(1440)
                        }
                    }
                    // 自定义
                    LeadChip(
                        "自定义",
                        state.customExpanded,
                        Modifier.fillMaxWidth(),
                        icon = Lucide.SlidersHorizontal,
                        onClick = { viewModel.expandCustom() },
                    )

                    if (state.customExpanded) {
                        CustomStepper(
                            value = state.customValue,
                            unit = state.customUnit,
                            onDecrement = { viewModel.setCustomValue(state.customValue - 1) },
                            onIncrement = { viewModel.setCustomValue(state.customValue + 1) },
                            onUnitChange = viewModel::setCustomUnit,
                        )
                    }

                    // 提示
                    val reference = state.referenceStartAt
                    if (reference != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Lucide.AlarmClock, null, Modifier.size(15.dp), tint = VColors.accent)
                            VText(
                                triggerHint(reference, state.offsetMinutes),
                                VTypo.caption.copy(lineHeight = 15.sp),
                                color = VColors.accent,
                            )
                        }
                    }
                }
            }

            // 全天事件卡（仅全天日程有意义）
            if (state.item?.allDay == true) {
                FieldLabel("全天事件")
                VCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        VBadge(icon = Lucide.CalendarDays, iconSize = 15.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            VText("全天事件提醒", VTypo.caption, color = VColors.ink3)
                            VText(if (state.allDayEnabled) "默认开启" else "已关闭", VTypo.bodyMed, color = VColors.ink)
                        }
                        VSwitch(checked = state.allDayEnabled, onCheckedChange = viewModel::setAllDayEnabled)
                    }
                    VDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .vPressable(scaleDown = 0.985f) { pickTime = true }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        VBadge(icon = Lucide.Clock4, iconSize = 15.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            VText("提醒时间", VTypo.caption, color = VColors.ink3)
                            VText(state.allDayTime, VTypo.bodyMed, color = VColors.ink)
                        }
                        VChevron()
                    }
                }
            }

            VText(
                "提醒由系统通知推送，请在系统设置中允许通知。",
                VTypo.micro.copy(lineHeight = 13.sp),
                color = VColors.ink3,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        }
        // 底部固定操作条（与 Tab 栏同款：渐隐遮罩 + 实底）。
        VBottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            VPrimaryButton(
                text = "保存提醒设置",
                icon = Lucide.Check,
                onClick = { viewModel.save(onBack) },
            )
        }
    }

    if (pickTime) {
        VTimePickerDialog(
            initialMinute = remindParseHm(state.allDayTime),
            onConfirm = { value ->
                viewModel.setAllDayTime(remindFormatHm(value))
                pickTime = false
            },
            onDismiss = { pickTime = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    VText(text, VTypo.caption, color = VColors.ink3, modifier = Modifier.fillMaxWidth())
}

/** 提前时间 chip：选中 = accent 填充白字，未选中 = surface 描边。 */
@Composable
private fun LeadChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val bg = if (selected) VColors.accent else VColors.surface
    val fg = if (selected) Color.White else VColors.ink2
    val borderColor = if (selected) VColors.accent else VColors.line
    Row(
        modifier
            .height(32.dp)
            .background(bg, RoundedCornerShape(11.dp))
            .border(1.dp, borderColor, RoundedCornerShape(11.dp))
            .vPressable(scaleDown = 0.95f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = fg)
            Spacer(Modifier.size(6.dp))
        }
        VText(
            text,
            VTypo.caption,
            color = fg,
            maxLines = 1,
            align = TextAlign.Center,
        )
    }
}

/** 自定义 stepper：− 数值 单位 + 与 分钟/小时/天 分段。 */
@Composable
private fun CustomStepper(
    value: Int,
    unit: RemindUnit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onUnitChange: (RemindUnit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(VColors.bg, RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(VColors.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, VColors.line, RoundedCornerShape(10.dp))
                    .vPressable(scaleDown = 0.9f, onClick = onDecrement),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Minus, null, Modifier.size(16.dp), tint = VColors.ink2)
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VText("$value", VTypo.numValue.copy(fontSize = 20.sp), color = VColors.ink)
                Spacer(Modifier.size(4.dp))
                VText(unit.label, VTypo.caption12, color = VColors.ink3)
            }
            Box(
                Modifier
                    .size(34.dp)
                    .background(VColors.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, VColors.line, RoundedCornerShape(10.dp))
                    .vPressable(scaleDown = 0.9f, onClick = onIncrement),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Plus, null, Modifier.size(16.dp), tint = VColors.accent)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(VColors.surface2, RoundedCornerShape(12.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            RemindUnit.entries.forEach { u ->
                val active = u == unit
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .then(
                            if (active) {
                                Modifier
                                    .shadow(4.dp, RoundedCornerShape(9.dp), spotColor = Color(0x1F101613), ambientColor = Color(0x1F101613))
                                    .background(VColors.surface, RoundedCornerShape(9.dp))
                            } else {
                                Modifier
                            },
                        )
                        .vPressable(scaleDown = 0.96f) { onUnitChange(u) },
                    contentAlignment = Alignment.Center,
                ) {
                    VText(
                        u.label,
                        VTypo.caption12,
                        color = if (active) VColors.ink else VColors.ink3,
                    )
                }
            }
        }
    }
}

/** 「将于 X 提醒你」文案。 */
private fun triggerHint(referenceStartAt: Instant, offsetMinutes: Int): String {
    val zone = ZoneId.systemDefault()
    val trigger = referenceStartAt.minus(Duration.ofMinutes(offsetMinutes.toLong())).atZone(zone)
    val today = LocalDate.now(zone)
    val hm = "%02d:%02d".format(trigger.hour, trigger.minute)
    return when (trigger.toLocalDate()) {
        today -> "将于今天 $hm 提醒你"
        today.plusDays(1) -> "将于明天 $hm 提醒你"
        else -> "将于${trigger.monthValue}月${trigger.dayOfMonth}日 $hm 提醒你"
    }
}
