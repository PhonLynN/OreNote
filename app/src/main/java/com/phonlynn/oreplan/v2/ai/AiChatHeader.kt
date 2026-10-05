package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * AI 对话页的顶栏。
 *
 * ## ⚠️ 为什么不复用 `VNavBar`
 *
 * 项目既有的 `VNavBar` 是**二级页导航条**（48dp 高、水平边距 20dp、
 * 图标带白色圆形外框）。AI 对话页的设计与它完全不同：
 *
 * | | VNavBar | AI 对话页（设计稿） |
 * | --- | --- | --- |
 * | 高度 | 48dp | **56dp** |
 * | 水平边距 | 20dp | **14dp** |
 * | 右侧图标 | 36dp 圆圈 + 18dp 图标 | **23dp 图标，无外框** |
 * | 右侧间距 | 无固定 | **14dp** |
 *
 * 我第一版图省事复用了 `VNavBar`，结果截图里出现三个突兀的白圆圈，
 * 整套比例也不对。**语义不同的组件不该硬套** —— 这里单独实现。
 *
 * 所有数值来自设计稿实测（见 `verification/plan030/ai_chat_spec.py`）。
 */
@Composable
fun AiChatHeader(
    title: String,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onList: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            /*
             * ⚠️ **必须显式要求高度，且不能被压缩。**
             *
             * 「顶栏不是真顶栏、会被挤没」的根因：`Column` 的子项总高超出可用高度时，
             * `weight(1f)` 的消息区先被压到 0，继续超出就挤固定高度的子项 ——
             * 顶栏被顶出可视区。
             *
             * `height()` 只声明期望高度，父容器在空间不足时**仍可能压缩它**。
             * 因此这里同时限定 `heightIn(min = 56.dp)`：
             * 高度不会低于 56，`Column` 只能从别处让出空间（或裁剪），
             * 而不会把顶栏压扁。
             */
            .height(56.dp)
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /*
         * 左侧：返回按钮 + 标题。
         *
         * ⚠️ **标题必须 `weight(1f)`，否则长标题会把右边的按钮挤出去**
         *（用户报的：「在标题过长时，右上角的三个按钮会被挤走消失，无法点击」）。
         *
         * ## 原因
         *
         * `Row` 里各子项按**固有宽度**分配空间。标题原来没写 `weight`，
         * 于是它按内容撑到任意宽：
         *
         * ```
         * 想看到的：[←] [今天的复习计划…]        …  [+] [☰] [⚙]
         * 实际变成：[←] [今天的复习计划真的很长很长很长很长…  ←+ ☰ ⚙ 被推出屏幕]
         *                                                    ↑ 点不到了
         * ```
         *
         * 更糟的是右边那三个是**固定宽度**（3 × 36dp 热区 + 间距），
         * 它们不会"让位"，只会被挤出可视区 —— 而 `Spacer(weight(1f))`
         * 先被压到 0，然后继续挤它们。
         *
         * ## 修法
         *
         * 把左半边整体 `weight(1f)`（**注意是给包住返回键和标题的 Row，
         * 不是只给标题**）：占满剩余空间，内部再让标题自己收缩。
         *
         * 标题上再单独 `weight(1f)`，这样：
         *  · 返回键保持 36dp 不被压
         *  · 标题吃掉剩下的宽度，**超长时省略号截断**（`maxLines = 1` 已有）
         *  · 右边三个按钮永远拿得到它们需要的宽度
         *
         * 于是标题再长也只会截断，不会把按钮顶走。
         */
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 返回按钮：36dp 热区，图标 22dp
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .vPressable(scaleDown = 0.9f, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.ChevronLeft, contentDescription = "返回", tint = VColors.ink, modifier = Modifier.size(22.dp))
            }
            VText(
                title,
                VTypo.navTitle,
                color = VColors.ink,
                maxLines = 1,
                // 超长时截断 —— 配合上面的 weight，标题再长也挤不走右边的按钮
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.width(8.dp))

        // 右侧：三个图标，**无外框**，间距 14dp，图标 23dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BareIcon(Lucide.Plus, "新对话", onNew)
            BareIcon(Lucide.Menu, "对话列表", onList)
            BareIcon(Lucide.Settings, "设置", onSettings)
        }
    }
}

/**
 * 顶栏图标：**只有图标，没有外框**。
 *
 * 设计稿的 Header Right 是三个 23×23 的裸图标。
 * 点击热区做大到 36dp —— 那只是**溢出热区**，不参与排版
 *（与侧栏按钮 `VSidebarButton` 同一手法：热区不把图标推开）。
 */
@Composable
private fun BareIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .vPressable(scaleDown = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = VColors.ink, modifier = Modifier.size(23.dp))
    }
}
