package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.components.VAttachmentThumb
import com.phonlynn.oreplan.v2.components.attachmentAspectRatio
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 卡片图片区（**展示页与编辑页共用同一套画法**）。
 *
 * 为什么要共用：这两处曾经各写一套 —— 展示页（聚焦浮层）按行宽三等分**铺满**，
 * 全屏编辑页固定 84dp 方块、整排靠左，于是同一张卡片「展示时铺满、进编辑缩到左边」
 *（用户 2026-09-28 报的现象，且容易误以为是"给确认按钮让位"）。现在只剩这一个实现。
 *
 * 口径与展示页完全一致：
 *  - [imageLayout] = "grid" → 三栏正方形网格，显示全部图片，不足三张补空位保持格宽一致；
 *  - [imageLayout] = "fill" → 每张都占满宽度、保留原比例（极端比例裁到 9:16 ~ 16:9）；
 *  - 其它（null = 自动）→ 单图填充、多图网格。
 *
 * @param onOpen 点图片的回调（展示页进聚焦、编辑页进全屏查看）。
 * @param overlay 单元格右上角的附加内容（编辑页的「移除」按钮用）。
 *                展示页不传；编辑页传了就仍然保留它原有的移除入口 —— 共用画法不等于砍掉功能。
 */
@Composable
internal fun CardImageBlock(
    images: List<Attachment>,
    storage: AttachmentStorage,
    imageLayout: String?,
    onOpen: (Attachment) -> Unit,
    gap: Dp = 6.dp,
    overlay: (@Composable BoxScope.(Attachment) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    val useGrid = when (imageLayout) {
        "grid" -> true
        "fill" -> false
        else -> images.size > 1 // 自动：多图网格、单图填充
    }
    if (useGrid) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            images.chunked(3).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { att ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            VAttachmentThumb(storage, att.storedPath, Modifier.fillMaxSize())
                            Box(Modifier.matchParentSize().vPressable(scaleDown = 0.97f) { onOpen(att) })
                            overlay?.invoke(this, att)
                        }
                    }
                    // 不足 3 张时补空位，保持每格宽度一致。
                    repeat(3 - rowItems.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            images.forEach { att ->
                val rawRatio by produceState<Float?>(initialValue = null, att.storedPath) {
                    value = attachmentAspectRatio(storage, att.storedPath)
                }
                // 极端比例（比 16:9 更宽 / 比 9:16 更高）裁到边界，避免图过高/过扁。
                val ratio = (rawRatio ?: 1f).coerceIn(9f / 16f, 16f / 9f)
                Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                    VAttachmentThumb(storage, att.storedPath, Modifier.fillMaxSize(), ContentScale.Crop)
                    Box(Modifier.matchParentSize().vPressable(scaleDown = 0.99f) { onOpen(att) })
                    overlay?.invoke(this, att)
                }
            }
        }
    }
}
