package com.phonlynn.oreplan.v2.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.platform.attachment.AttachmentOpener
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/**
 * 从 filesDir 里按采样加载一张附件图片。长图/相机原图直接解码会吃掉几十 MB 内存，
 * 这里按目标尺寸的 2 倍采样。
 */
suspend fun decodeAttachmentBitmap(storage: AttachmentStorage, relativePath: String, targetPx: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = storage.fileOf(relativePath)
            if (!file.isFile) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > targetPx * 2 && bounds.outHeight / sample > targetPx * 2) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }.getOrNull()
    }

/** 附件缩略图（本地文件）。 */
@Composable
fun VAttachmentThumb(
    storage: AttachmentStorage,
    relativePath: String,
    modifier: Modifier = Modifier,
    /** 缩放模式：默认 Crop（填满裁切）；Fit 用于「维持原图比例」的场景。 */
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, relativePath) {
        value = decodeAttachmentBitmap(storage, relativePath, 220)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VColors.surface2),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

/**
 * 读一张图片的原始宽高比（宽/高）。读不到时返回 null（调用方回退为 1:1）。
 * 只读 bounds，不解码像素，开销极小。
 */
suspend fun attachmentAspectRatio(storage: AttachmentStorage, relativePath: String): Float? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = storage.fileOf(relativePath)
            if (!file.isFile) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            bounds.outWidth.toFloat() / bounds.outHeight.toFloat()
        }.getOrNull()
    }

/**
 * 打开一个附件。图片回传给调用方做应用内预览，其余交给系统查看器。
 * 返回 false 表示文件丢失且已提示。
 */
fun openAttachmentV2(
    context: android.content.Context,
    storage: AttachmentStorage,
    attachment: Attachment,
    onImage: (Attachment) -> Unit,
): Boolean {
    val file = storage.fileOf(attachment)
    if (!file.isFile) {
        android.widget.Toast.makeText(context, "文件已丢失（备份不含附件本体），请重新添加", android.widget.Toast.LENGTH_LONG).show()
        return false
    }
    return if (attachment.isImage) {
        onImage(attachment)
        true
    } else {
        AttachmentOpener.openExternally(context, file, attachment.mimeType)
    }
}

/** 图片大图预览。 */
@Composable
fun VImagePreviewDialog(
    attachment: Attachment?,
    storage: AttachmentStorage,
    onDismiss: () -> Unit,
) {
    if (attachment == null) return
    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(22.dp))
                .padding(12.dp),
        ) {
            VAttachmentThumb(
                storage = storage,
                relativePath = attachment.storedPath,
                modifier = Modifier.fillMaxWidth().height(320.dp),
            )
        }
    }
}

/**
 * 全屏图片查看：黑底铺满，可左右滑动切换，双指缩放/拖动画。
 *
 * [paths] 是全部待查看图片的相对路径，[startIndex] 是打开时定位到第几张。
 * 点空白/返回键关闭。
 */
@Composable
fun VFullscreenImageViewer(
    paths: List<String>,
    startIndex: Int,
    storage: AttachmentStorage,
    onDismiss: () -> Unit,
) {
    if (paths.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, paths.lastIndex)) {
        paths.size
    }
    // 点按关闭 + 返回键关闭。
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(
                storage = storage,
                path = paths[page],
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 关闭按钮（右上角）。
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .size(34.dp)
                .background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape)
                .vPressable(scaleDown = 0.9f) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = Color.White)
        }
        // 页码。
        if (paths.size > 1) {
            VText(
                "${pager.currentPage + 1} / ${paths.size}",
                VTypo.caption12,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
            )
        }
    }
}

/** 单张可缩放图片：双指缩放 + 拖动平移，缩放超出边界时回弹到 1x。 */
@Composable
private fun ZoomableImage(
    storage: AttachmentStorage,
    path: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = decodeAttachmentBitmap(storage, path, 1080)
    }
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier.pointerInput(path) {
            awaitEachGesture {
                // 只在**已放大**（scale > 1）时接管手势（拖动平移 + 继续缩放）；
                // 未放大时**不消费**事件，让外层 HorizontalPager 接管左右滑动切换。
                // 之前无条件下 detectTransformGestures → 所有手势被吃，无法翻页。
                var zoomed = false
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.size >= 2) zoomed = true
                    if (scale > 1f || zoomed) {
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f || panChange != androidx.compose.ui.geometry.Offset.Zero) {
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            offset = if (scale <= 1f) {
                                androidx.compose.ui.geometry.Offset.Zero
                            } else {
                                offset + panChange
                            }
                            // 缩回 1x 后不再消费，交回给 pager。
                            if (scale > 1f) event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}
