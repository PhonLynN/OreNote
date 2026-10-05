package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.v2.components.VBadge
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VNavBar
import com.phonlynn.oreplan.v2.components.VPrimaryButton
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VSwitch
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.phonlynn.oreplan.v2.components.VRow

/** 日程设置 —— 提醒与默认时长（全局默认值）。 */
@Composable
fun ScheduleSettingsScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
) {
    val viewModel: SettingsV2ViewModel = hiltViewModel()
    val settings by viewModel.settingsStore.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
        VNavBar(title = "日程设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VSectionHead("提醒")
            VCard {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    VBadge(icon = Lucide.Bell, iconSize = 15.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        VText("开启提醒", VTypo.body, color = VColors.ink)
                        VText("新建条目默认开启提醒", VTypo.caption, color = VColors.ink3)
                    }
                    VSwitch(
                        checked = settings.remindersEnabled,
                        onCheckedChange = { value ->
                            scope.launch { viewModel.settingsStore.update { it.copy(remindersEnabled = value) } }
                        },
                    )
                }
                VDivider()
                VRow(
                    title = "默认提前提醒",
                    subtitle = leadText(settings.reminderLeadMinutes),
                    leading = { VBadge(icon = Lucide.AlarmClock, iconSize = 15.dp) },
                    trailing = { VChevron() },
                    onClick = { dialog = "lead" },
                )
                VDivider()
                VRow(
                    title = "全天事件提醒时间",
                    subtitle = settings.allDayReminderTime,
                    leading = { VBadge(icon = Lucide.Sun, iconSize = 15.dp) },
                    trailing = { VChevron() },
                    onClick = { dialog = "allday" },
                )
            }

            VSectionHead("默认值")
            VCard {
                VRow(
                    title = "新建日程默认时长",
                    subtitle = "${settings.defaultEventMinutes} 分钟",
                    leading = { VBadge(icon = Lucide.Timer, iconSize = 15.dp) },
                    trailing = { VChevron() },
                    onClick = { dialog = "duration" },
                )
                VDivider()
                // 归档待办的入口放这里：待办属于日程模块（日程页的「待办任务」段）。
                VRow(
                    title = "已归档的待办",
                    subtitle = "查看与恢复归档的待办",
                    leading = { VBadge(icon = Lucide.Archive, iconSize = 15.dp) },
                    trailing = { VChevron() },
                    onClick = { navigate(V2Routes.TASK_ARCHIVE) },
                )
            }

        }

        }
        VBottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            VPrimaryButton(text = "完成", icon = Lucide.Check, onClick = onBack)
        }
    }

    when (dialog) {
        "lead" -> OptionDialog(
            title = "默认提前提醒",
            options = listOf(0 to "不提醒", 5 to "提前 5 分钟", 15 to "提前 15 分钟", 30 to "提前 30 分钟", 60 to "提前 1 小时", 1440 to "提前 1 天"),
            selectedKey = settings.reminderLeadMinutes,
            onSelect = { value ->
                scope.launch { viewModel.settingsStore.update { it.copy(reminderLeadMinutes = value) } }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "allday" -> OptionDialog(
            title = "全天事件提醒时间",
            options = listOf(360 to "06:00", 480 to "08:00", 540 to "09:00", 720 to "12:00", 1080 to "18:00", 1200 to "20:00"),
            selectedKey = parseHmValue(settings.allDayReminderTime),
            onSelect = { value ->
                scope.launch { viewModel.settingsStore.update { it.copy(allDayReminderTime = formatHmValue(value)) } }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "duration" -> OptionDialog(
            title = "新建日程默认时长",
            options = listOf(30 to "30 分钟", 60 to "1 小时", 90 to "1.5 小时", 120 to "2 小时"),
            selectedKey = settings.defaultEventMinutes,
            onSelect = { value ->
                scope.launch { viewModel.settingsStore.update { it.copy(defaultEventMinutes = value) } }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun OptionDialog(
    title: String,
    options: List<Pair<Int, String>>,
    selectedKey: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText(title, VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (key, label) ->
                    val active = key == selectedKey
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(if (active) VColors.accentSoft else VColors.surface2, RoundedCornerShape(12.dp))
                            .vPressable(scaleDown = 0.97f) { onSelect(key) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        VText(label, VTypo.bodyMed, color = if (active) VColors.accent else VColors.ink)
                        if (active) Icon(Lucide.Check, null, Modifier.size(16.dp), tint = VColors.accent)
                    }
                }
            }
        }
    }
}

private fun leadText(minutes: Int): String = when {
    minutes <= 0 -> "不提醒"
    minutes % (24 * 60) == 0 -> "提前 ${minutes / (24 * 60)} 天"
    minutes % 60 == 0 -> "提前 ${minutes / 60} 小时"
    else -> "提前 $minutes 分钟"
}

private fun parseHmValue(text: String): Int {
    val parts = text.split(":")
    if (parts.size != 2) return 540
    return (parts[0].toIntOrNull() ?: 9) * 60 + (parts[1].toIntOrNull() ?: 0)
}

private fun formatHmValue(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
