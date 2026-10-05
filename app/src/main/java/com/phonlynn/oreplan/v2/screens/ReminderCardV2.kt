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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.components.VBadge
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VSwitch
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/** 提醒卡片里可选的提前档位（分钟）。 */
val ReminderPresetMinutes: List<Int> = listOf(5, 15, 60, 1440)

/** 提前分钟 → 文案。 */
fun reminderLeadText(minutes: Int): String = when {
    minutes <= 0 -> "准点提醒"
    minutes % (24 * 60) == 0 -> "提前 ${minutes / (24 * 60)} 天"
    minutes % 60 == 0 -> "提前 ${minutes / 60} 小时"
    else -> "提前 $minutes 分钟"
}

/**
 * Thk37 提醒卡片（编辑器内嵌）。
 *
 * 结构照规格 48：开启提醒行 + 提前时间 chips + 提示 + 更多提醒设置行。
 * [enabled] / [offsetMinutes] 由编辑器持有，改动通过回调上抛。
 * 「更多提醒设置」跳转 RemindScreenV2 由 [onMoreSettings] 触发。
 */
@Composable
fun ReminderCardV2(
    enabled: Boolean,
    offsetMinutes: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectOffset: (Int) -> Unit,
    onMoreSettings: () -> Unit,
) {
    VCard {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            VBadge(icon = Lucide.Bell, iconSize = 15.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText("开启提醒", VTypo.body, color = VColors.ink)
                VText("到期前提醒我", VTypo.caption, color = VColors.ink3)
            }
            VSwitch(checked = enabled, onCheckedChange = onToggleEnabled)
        }

        if (enabled) {
            VDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReminderPresetMinutes.forEach { minutes ->
                        val active = minutes == offsetMinutes
                        Box(
                            Modifier
                                .height(32.dp)
                                .background(if (active) VColors.accentSoft else VColors.surface2, RoundedCornerShape(11.dp))
                                .padding(horizontal = 10.dp)
                                .vPressable(scaleDown = 0.95f) { onSelectOffset(minutes) },
                            contentAlignment = Alignment.Center,
                        ) {
                            VText(
                                reminderLeadText(minutes),
                                VTypo.caption12,
                                color = if (active) VColors.accent else VColors.ink2,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.Info, null, Modifier.size(14.dp), tint = VColors.ink3)
                    VText(
                        "每个目标最多 3 次提醒；两次提醒至少间隔 30 分钟",
                        VTypo.micro.copy(lineHeight = 13.sp),
                        color = VColors.ink3,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        VDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .vPressable(scaleDown = 0.985f, onClick = onMoreSettings)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            VBadge(icon = Lucide.AlarmClock, iconSize = 15.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText("更多提醒设置", VTypo.body, color = VColors.ink)
                VText("重复、渠道、稍后提醒", VTypo.caption, color = VColors.ink3)
            }
            VChevron()
        }
    }
}
