package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 标题行右侧的 AI 入口。
 *
 * ## 为什么和页内动作（如白板的「新建卡片」）并排而不是替换它
 *
 * 白板的 `trailing` 已经是「新建卡片」——那是**页内动作**，
 * 与 AI 入口不是一回事，替换掉会破坏白板的核心操作。
 * 所以两者并排：AI 入口在最右，页内动作在其左侧。
 *
 * 这个顺序是刻意的：AI 入口在**所有主 tab 的同一位置**（最右），
 * 用户换 tab 后肌肉记忆仍然有效；页内动作位置随页面变化，本来就不会形成记忆。
 */
@Composable
fun AiEntryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    icon: ImageVector = Lucide.Sparkles,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(VColors.surface)
            .border(1.dp, VColors.line, CircleShape)
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "AI",
            tint = VColors.accent,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}
