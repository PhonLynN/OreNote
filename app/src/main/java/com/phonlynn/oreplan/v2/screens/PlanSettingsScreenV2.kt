package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VPageScaffold
import com.phonlynn.oreplan.v2.components.VNoteBox
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import com.phonlynn.oreplan.v2.theme.vPressable

/** 规划设置 —— 归档入口与目标类型说明。 */
@Composable
fun PlanSettingsScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
) {
    VPageScaffold(title = "规划设置", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VSectionHead("归档")
            VCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .vPressable(scaleDown = 0.985f) { navigate(V2Routes.PLAN_ARCHIVE) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        VText("已归档目标", VTypo.body, color = VColors.ink)
                        VText("查看与恢复归档的目标", VTypo.caption, color = VColors.ink3)
                    }
                    VChevron()
                }
            }

            VSectionHead("目标类型")
            VCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TypeLine("分步目标", "把大目标拆成步骤，逐个完成，进度按步骤统计。")
                    VDivider(0.dp)
                    TypeLine("数量目标", "设定一个总量（如 12 本、10 份），每次完成记一笔。")
                    VDivider(0.dp)
                    TypeLine("习惯目标", "按天打卡，追踪连续天数与本周完成情况。")
                }
            }

            VNoteBox("目标提醒：数量与分步目标可在「目标设置」里开启提醒；习惯目标会在当天任意时间提醒打卡。")
        }
    }
}

@Composable
private fun TypeLine(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        VText(title, VTypo.bodyMed, color = VColors.ink)
        VText(description, VTypo.caption, color = VColors.ink3)
    }
}
