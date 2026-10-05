package com.phonlynn.oreplan.v2.components

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.delay

/** 弹窗退场时长（与退场动画对齐）。 */
private const val VDialogExitMillis = 130L

/**
 * 供弹窗内容里的按钮走「先播退场动画、再执行动作」的关闭路径。
 * 用法：`LocalVDialogClose.current { ...关闭与后续动作... }`；传入的 after 会在退场动画播完后执行，
 * 不传 after 则走 [VDialog] 的 onDismissRequest。
 */
val LocalVDialogClose = staticCompositionLocalOf<((() -> Unit)?) -> Unit> { { _ -> } }

/**
 * V2 弹窗唯一入口。整屏窗口 + 点面板外关闭 + 背景模糊（设备支持时）。
 * 面板宽度上限 320（与设计一致），个别弹窗（悬浮输入 310）单独传。
 * 出场：点面板外 / 返回键 / 内容里通过 [LocalVDialogClose] 请求的关闭，都先播退场动画再真正关闭。
 */
@Composable
fun VDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 320.dp,
    content: @Composable () -> Unit,
) {
    val closingState = remember { mutableStateOf(false) }
    val pendingAfterState = remember { mutableStateOf<(() -> Unit)?>(null) }
    val requestClose = remember {
        { after: (() -> Unit)? ->
            if (!closingState.value) {
                closingState.value = true
                pendingAfterState.value = after
            }
        }
    }
    LaunchedEffect(closingState.value) {
        if (closingState.value) {
            delay(VDialogExitMillis)
            val after = pendingAfterState.value
            pendingAfterState.value = null
            if (after != null) after() else onDismissRequest()
        }
    }

    Dialog(
        onDismissRequest = { requestClose(null) },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val blurRadius = with(LocalDensity.current) { 26.dp.roundToPx() }
        SideEffect {
            view.dialogWindow()?.applyBackdropBlur(blurRadius)
        }

        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        val shown = visible && !closingState.value
        val scale by animateFloatAsState(
            targetValue = if (shown) 1f else 0.94f,
            animationSpec = if (shown) {
                androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 480f)
            } else {
                tween((VDialogExitMillis - 10).toInt(), easing = VMotion.Accelerate)
            },
            label = "dialogScale",
        )
        val alpha by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (shown) 200 else (VDialogExitMillis - 10).toInt(),
                easing = if (shown) VMotion.Expressive else VMotion.Accelerate,
            ),
            label = "dialogAlpha",
        )

        var panelBounds by remember { mutableStateOf<Rect?>(null) }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(VColors.dialogScrim.copy(alpha = VColors.dialogScrim.alpha * alpha))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val bounds = panelBounds
                        if (bounds == null || !bounds.contains(offset)) requestClose(null)
                    }
                }
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .widthIn(max = maxWidth)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .onGloballyPositioned { panelBounds = it.boundsInRoot() },
            ) {
                CompositionLocalProvider(LocalVDialogClose provides requestClose) {
                    content()
                }
            }
        }
    }
}

/**
 * 白色面板容器：r24 + 大投影 + **统一内边距**。
 *
 * 内边距在这里统一给，调用方不要再各自传 `Modifier.padding(...)`——
 * 之前每个弹窗各写各的（16 / 20 / 22 混用），导致同一套设计语言下面板疏密不一，
 * 且普遍偏紧：内容与按钮直接顶到圆角边缘，看起来像「撑着面板涨大」。
 *
 * [contentPadding] 默认 [VDialogPanelPadding]；只有聚焦浮层这类需要通栏列表的
 * 特殊面板才传更小的值，并把内边距交给自己逐行控制。
 */
val VDialogPanelPadding: Dp = 24.dp

@Composable
fun VDialogPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = VDialogPanelPadding,
    horizontalPadding: Dp = contentPadding,
    verticalPadding: Dp = contentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color(0x66101613),
                spotColor = Color(0x66101613),
            )
            .background(VColors.surface, RoundedCornerShape(24.dp))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * 确认删除弹窗（设计组件 b1ZCg）。
 * 危险图标 48 + 标题 + 说明 + 对象胶囊 + 取消/确认删除。
 */
@Composable
fun VConfirmDeleteDialog(
    title: String = "确认删除？",
    message: String = "删除后无法撤销，相关内容将被永久移除。",
    objectName: String? = null,
    confirmText: String = "确认删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            Box(
                Modifier.size(48.dp).background(VColors.roseSoft, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Trash2, contentDescription = null, modifier = Modifier.size(22.dp), tint = VColors.rose)
            }
            Spacer(Modifier.height(16.dp))
            VText(title, VTypo.dialogTitle, color = VColors.ink, align = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            VText(
                message,
                VTypo.body.copy(lineHeight = 20.sp),
                color = VColors.ink2,
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (objectName != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .background(VColors.surface2, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(width = 3.dp, height = 16.dp).background(VColors.rose, RoundedCornerShape(2.dp)))
                    VText(objectName, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(VColors.surface2, RoundedCornerShape(14.dp))
                        .vPressable(scaleDown = 0.96f, onClick = { close(onDismiss) }),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("取消", VTypo.buttonBold, color = VColors.ink)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(VColors.roseDeep, RoundedCornerShape(14.dp))
                        .vPressable(scaleDown = 0.96f, onClick = { close(onConfirm) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Lucide.Trash2, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        VText(confirmText, VTypo.buttonBold, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * **弹窗底部「取消 / 确定」按钮行** —— 全站弹窗的统一收尾。
 *
 * 形态（多页曾各写一遍，逐字相同）：`Row(spacedBy(10)) { 取消(surface2) 确定(accent) }`，
 * 两枚 `weight(1f)` + `height(48)` + `r14`，按下缩放 0.96。
 *
 * 收敛到这里的原因见 `.context/extensibility-audit.md` P-6：此前这套按钮行在
 * TtSettings / Routine / CourseEdit / GoalDetail 等**十余处**各抄一遍，
 * 改一次按钮样式要改十几处。
 *
 * @param confirmText 主按钮文案（默认「确定」）。
 * @param confirmEnabled 主按钮是否可用；false 时置灰且不可点（部分弹窗需要）。
 * @param onCancel 取消（同时也是「点主按钮前的落点」之外唯一的退出口）。
 * @param onConfirm 确定。
 */
@Composable
fun VDialogButtons(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "确定",
    cancelText: String = "取消",
    confirmEnabled: Boolean = true,
    /**
     * 按钮高度 / 圆角。默认 48dp / r14 —— 设计稿「确认删除弹窗」的按钮几何。
     *
     * ⚠️ 之所以做成参数：本项目弹窗按钮**存在三种既有几何**
     * （48/14、46/13、44/13，见 .context/extensibility-audit.md P-6）。
     * 收敛结构时**必须保住各页既有几何**，否则会改变视觉；故不在此处强制统一。
     */
    height: Dp = 48.dp,
    radius: Dp = 14.dp,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .weight(1f)
                .height(height)
                .background(VColors.surface2, RoundedCornerShape(radius))
                .vPressable(scaleDown = 0.96f, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            VText(cancelText, VTypo.buttonBold, color = VColors.ink)
        }
        // 颜色过渡（用户 2026-09-30：变色必须有过渡动画，不能硬切）。
        // 弹窗的确认按钮同样有"能不能点"的状态（如「保存暗号」输入为空时）。
        val confirmBg by androidx.compose.animation.animateColorAsState(
            if (confirmEnabled) VColors.accent else VColors.surface2,
            com.phonlynn.oreplan.v2.theme.VMotion.color(),
            label = "confirmBtnBg",
        )
        val confirmFg by androidx.compose.animation.animateColorAsState(
            if (confirmEnabled) Color.White else VColors.ink3,
            com.phonlynn.oreplan.v2.theme.VMotion.color(),
            label = "confirmBtnFg",
        )
        Box(
            Modifier
                .weight(1f)
                .height(height)
                .background(confirmBg, RoundedCornerShape(radius))
                .then(if (confirmEnabled) Modifier.vPressable(scaleDown = 0.96f, onClick = onConfirm) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            VText(
                confirmText,
                VTypo.buttonBold,
                color = confirmFg,
            )
        }
    }
}

/**
 * 悬浮输入弹窗（设计组件 k4jxaD）：310 宽、r22，内部一个带焦点描边的输入框。
 */
@Composable
fun VFloatingInputDialog(
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
    confirmText: String = "确定",
) {
    VDialog(onDismissRequest = onDismiss, maxWidth = 310.dp) {
        val close = LocalVDialogClose.current
        // 自动唤起键盘：这类悬浮输入弹窗（添加地点、保密暗号等）
        // 的目的就是让用户输入，弹出来就应直接可打字。
        val inputFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            inputFocus.requestFocus()
            keyboard?.show()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x66101613), spotColor = Color(0x66101613))
                .background(VColors.surface, RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(VColors.bg, RoundedCornerShape(12.dp))
                    .border(1.5.dp, VColors.accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = VColors.ink3)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        VText(placeholder, VTypo.button, color = VColors.ink3, maxLines = 1)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                        textStyle = VTypo.button.copy(color = VColors.ink, fontFamily = com.phonlynn.oreplan.v2.theme.BodyFont),
                        singleLine = true,
                        cursorBrush = SolidColor(VColors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { close(onConfirm) }),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f, onClick = { close(onDismiss) }),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("取消", VTypo.button, color = VColors.ink)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(VColors.accent, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f, onClick = { close(onConfirm) }),
                    contentAlignment = Alignment.Center,
                ) {
                    VText(confirmText, VTypo.button, color = Color.White)
                }
            }
        }
    }
}

private fun View.dialogWindow(): Window? =
    (this as? DialogWindowProvider)?.window
        ?: (parent as? DialogWindowProvider)?.window

private fun Window.applyBackdropBlur(radiusPx: Int) {
    val supported = context
        .getSystemService(WindowManager::class.java)
        ?.isCrossWindowBlurEnabled == true
    val radius = if (supported) radiusPx else 0
    setBackgroundBlurRadius(radius)
    if (radius > 0) {
        addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    }
}

