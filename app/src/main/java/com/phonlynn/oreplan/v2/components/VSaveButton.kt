package com.phonlynn.oreplan.v2.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * **全应用唯一的「保存」按钮**（问题 #25：保存按钮规范统一）。
 *
 * ## 为什么要有它
 *
 * 之前至少有 **三份各写一遍**的保存按钮：
 *  - `BoardCardScreenV2`（卡片：50dp / 15dp 圆角 / accent / ✓+文字）
 *  - `RoutineScreenV2`（作息：50dp / 15dp 圆角 / accent / 只有文字）
 *  - 日程与课程则**内联**写在各自的底部操作条里（同样 50dp / 15dp / accent）
 *
 * 更要紧的是**禁用规则不统一**，而且有个隐蔽的坑：
 * **`vPressable(enabled = false)` 只是不让点，外观一点不变** ——
 * 于是"内容为空"时按钮仍是**满绿的**，点下去毫无反应。
 * 用户看不出为什么点不动（问题 #25 的原话："保存按钮禁用规则不统一……禁用态样式手写硬编码"）。
 *
 * ## 口径（用户 2026-09-30 拍板）
 *
 * > **空内容不能保存，但是空标题可以。**
 *
 * ⚠️ **"空标题可以"只对卡片成立**，别当成通则：
 *  - **卡片**的"内容"是**正文**（卡片可以只有正文、没有标题）；
 *  - **日程/待办**的字段**全部可选**（时间、优先级都有默认值），
 *    它唯一的"内容"就是**标题** ⇒ 日程用「标题非空」是**对的**，两者不冲突。
 *
 * 本组件只负责"长什么样 + 能不能点"，**"什么算空"由各页自己算好传 [enabled]**。
 *
 * @param showCheckIcon 左侧是否带 ✓。卡片页有、作息页原先没有 —— 保留各自原样，
 *   避免顺手改掉没被点名的页面（护栏 A10）。
 */
@Composable
fun VSaveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCheckIcon: Boolean = true,
) {
    // 用户 2026-09-30：**变色必须有过渡动画**，不能硬切。
    // 底/字/图标三处一起走同一条动画，颜色才同步渐变（否则会出现"底先变、字后变"的割裂感）。
    val tint by animateColorAsState(
        targetValue = if (enabled) VColors.accent else VColors.surface2,
        animationSpec = VMotion.color(),
        label = "saveButtonBg",
    )
    val fg by animateColorAsState(
        targetValue = if (enabled) Color.White else VColors.ink3,
        animationSpec = VMotion.color(),
        label = "saveButtonFg",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(tint, RoundedCornerShape(15.dp))
            .vPressable(scaleDown = 0.97f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showCheckIcon) {
                Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = fg)
            }
            VText(text, VTypo.button, color = fg, maxLines = 1)
        }
    }
}
