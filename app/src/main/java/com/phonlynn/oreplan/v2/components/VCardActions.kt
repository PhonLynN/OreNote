package com.phonlynn.oreplan.v2.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import com.phonlynn.oreplan.v2.theme.VMotion
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.platform.export.CardLongImage
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 卡片三点菜单的四个动作（用户 2026-09-28 定稿，顺序固定）：
 * **复制 / 导出 / 分享 / 更多**。
 *
 * 从"点三点直接进编辑页"改成弹层菜单 —— 复制/导出/分享这三个动作
 * 不该逼用户先进一次编辑页再找按钮。
 */
data class CardMenuActions(
    /** 「复制」= 把卡片文本（标题+正文）复制到剪贴板，**不是**生成副本卡片。 */
    val onCopy: () -> Unit,
    val onExport: () -> Unit,
    val onShare: () -> Unit,
    /** 「更多」= 进「更多设置」页（标签 / 颜色 / 显示 / 操作都在那里）。 */
    val onMore: () -> Unit,
)

/**
 * 三点按钮 + 贴着它弹出的下拉卡片菜单。
 *
 * ## 为什么不用 M3 的 DropdownMenu（用户 2026-09-28 报了「巨大黑边」）
 *
 * `DropdownMenu` 自带一层**黑色** elevation 阴影（tonal/shadow elevation），
 * 叠在我们自己的圆角与底色上，就在菜单四周形成一圈粗黑边；而且它默认不给
 * 选项之间画分割线。这里改成自绘 `Popup`：
 *  - 阴影色与全项目一致（冷灰 `0x2E/0x52 101613`），不是纯黑；
 *  - 选项之间用 [VDivider] 画分割线（用户明确要求）；
 *  - 行高 46dp、图标 16dp，与设置页的行保持一致。
 *
 * 关闭方式：点外部、返回键（`PopupProperties.focusable = true` 负责）。
 */
@Composable
internal fun ThreeDotMenuButton(
    actions: CardMenuActions,
    modifier: Modifier = Modifier,
    tint: Color = VColors.ink2,
) {
    var open by remember { mutableStateOf(false) }
    // 弹层也要有入场/退场（项目铁律：任何元素出现/消失必须有过渡，不能硬切）。
    // 做法与全屏层一致：一个 progress 驱动，退场要**播完再卸下**节点。
    var mounted by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) mounted = true
        appear.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (open) 170 else 120,
                easing = VMotion.Emphasized,
            ),
        )
        if (!open) mounted = false
    }
    Box(modifier) {
        Box(
            Modifier.size(28.dp).vPressable(scaleDown = 0.88f) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Ellipsis, "更多", Modifier.size(18.dp), tint = tint)
        }
        if (mounted) {
            Popup(
                // 锚在按钮的右下角，再往下偏一个按钮高度 + 8dp 间隙。
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, with(LocalDensity.current) { 36.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true,
                ),
            ) {
                Column(
                    Modifier
                        // 淡入 + 轻微缩放/上移：从三点按钮那侧"长出来"。
                        .graphicsLayer {
                            val a = appear.value
                            alpha = a
                            scaleX = 0.94f + 0.06f * a
                            scaleY = 0.94f + 0.06f * a
                            translationY = -8.dp.toPx() * (1f - a)
                        }
                        // 收窄到「容纳 4 个字」：图标 16 + 间距 10 + 正文 4 字 + 左右内衬 28 ≈ 106，取 112 留余量。
                        // 原为 176dp —— 选项多为 2 字（复制/导出/分享/更多），176 太宽（用户 2026-09-29）。
                        .width(112.dp)
                        .shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(16.dp),
                            clip = false,
                            ambientColor = Color(0x2E101613),
                            spotColor = Color(0x52101613),
                        )
                        .background(VColors.surface, RoundedCornerShape(16.dp))
                        .padding(vertical = 4.dp),
                ) {
                    MenuItem(Lucide.Copy, "复制") { open = false; actions.onCopy() }
                    MenuItem(Lucide.Download, "导出") { open = false; actions.onExport() }
                    MenuItem(Lucide.Send, "分享") { open = false; actions.onShare() }
                    MenuItem(Lucide.Settings, "更多") { open = false; actions.onMore() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .vPressable(scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = VColors.ink2)
        Text(label, style = VTypo.body, color = VColors.ink)
    }
}

// ---------------------------------------------------------------- 导出 / 分享（N1）

/**
 * 导出长图：渲染 → 存进系统相册 → 提示。
 *
 * 放这里而不是各页面各写一份：导出与分享在「展示页」「更多设置页」都要用，
 * 两处各写一份必然分叉（这条教训在本项目里已经出现过多次）。
 */
internal fun exportCardLongImage(
    context: Context,
    scope: CoroutineScope,
    storage: AttachmentStorage,
    title: String?,
    body: String?,
    attachments: List<Attachment>,
    background: Color,
    ink: Color,
    ink3: Color,
    titleSp: Float,
    bodySp: Float,
) {
    scope.launch {
        val bitmap = withContext(Dispatchers.Default) {
            CardLongImage.render(
                storage = storage,
                spec = CardLongImage.Spec(
                    title = title,
                    body = body,
                    attachments = attachments,
                    background = background.toArgb(),
                    ink = ink.toArgb(),
                    ink3 = ink3.toArgb(),
                    titleSp = titleSp,
                    bodySp = bodySp,
                ),
            )
        }
        val name = CardLongImage.fileName(title)
        val ok = withContext(Dispatchers.IO) {
            CardLongImage.saveToPictures(context, bitmap, name)
        }
        Toast.makeText(
            context,
            if (ok) "长图已存入相册：Pictures/OreNote" else "导出失败，请重试",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/** 分享为**图片**：渲染长图 → 写进 FileProvider 白名单目录 → 拉起系统分享面板。 */
internal fun shareCardImage(
    context: Context,
    scope: CoroutineScope,
    storage: AttachmentStorage,
    title: String?,
    body: String?,
    attachments: List<Attachment>,
    background: Color,
    ink: Color,
    ink3: Color,
    titleSp: Float,
    bodySp: Float,
) {
    scope.launch {
        val bitmap = withContext(Dispatchers.Default) {
            CardLongImage.render(
                storage = storage,
                spec = CardLongImage.Spec(
                    title = title,
                    body = body,
                    attachments = attachments,
                    background = background.toArgb(),
                    ink = ink.toArgb(),
                    ink3 = ink3.toArgb(),
                    titleSp = titleSp,
                    bodySp = bodySp,
                ),
            )
        }
        val file = withContext(Dispatchers.IO) {
            CardLongImage.writeForShare(context, bitmap, CardLongImage.fileName(title))
        }
        if (file == null) {
            Toast.makeText(context, "生成图片失败，请重试", Toast.LENGTH_SHORT).show()
        } else {
            CardLongImage.shareImage(context, file, title)
        }
    }
}

/**
 * 分享方式选择（用户要求：可选**图片**或**文字**）。
 * 点外部/返回键关闭，不做任何事。
 */
@Composable
internal fun ShareChoiceDialog(
    onDismiss: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel {
            Text(
                "分享",
                style = VTypo.dialogTitle,
                color = VColors.ink,
                modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp),
            )
            VRow(
                title = "分享为图片",
                subtitle = "长图：图片完整高清、纵向排列",
                leading = { Icon(Lucide.Image, null, Modifier.size(18.dp), tint = VColors.ink2) },
                onClick = onShareImage,
            )
            VRow(
                title = "分享为文字",
                subtitle = "纯文本：标题 + 正文",
                leading = { Icon(Lucide.FileText, null, Modifier.size(18.dp), tint = VColors.ink2) },
                onClick = onShareText,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
