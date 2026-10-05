package com.phonlynn.oreplan.v2.richtext

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.core.rt.RichEditState
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText

/**
 * 富文本**编辑器**组件（2026-09-23 第二次重写定稿）。
 *
 * # 文本流里只有正文与换行
 *
 * 显示文字 = 正文各段以 `\n` 连接，**没有符号、没有不可见字符**。
 * 因此 `field.selection` 的下标**就是内容坐标**，
 * 编辑器与 [RichEditState] 之间不需要任何换算层
 * —— 上一版的三个编辑症状（回车不换行 / 退格退不掉列表 / 连续退格卡住）
 * 全部源于那一层在处理「没有内容含义的分隔字符」。
 *
 * # 符号由独立层绘制
 *
 * 缩进由段落级 `TextIndent` 提供（见 `RichTextBuild`），
 * 符号由 [RichSymbolLayer] 依据 `onTextLayout` 拿到的真实字形盒自绘。
 * 两者都不参与编辑，所以编辑行为就是原生 `BasicTextField` 的行为。
 */
@Composable
fun VRichTextField(
    value: String,
    state: RichEditState,
    onValueChange: (String) -> Unit,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    focusRequester: FocusRequester? = null,
    minHeight: Int = 48,
    /** 正文颜色（编辑态通常比卡片展示更深）。 */
    color: Color = VColors.ink,
    /** 符号颜色。 */
    symbolColor: Color = VColors.ink2,
    /** 已勾选复选框项的正文颜色。 */
    checkedColor: Color = VColors.ink3,
    /**
     * 光标所在行的上下边（**root 窗口坐标**，px）变化时回调。
     *
     * 为什么是 root 坐标而不是「相对输入框」：输入框上面往往还有标题等元素，
     * 父级拿「相对输入框」的数值去和滚动容器比，会少算这段高度 ——
     * 表现就是光标多降了一行左右（用户 2026-09-28 实测）。
     * 用 root 坐标后，父子两边说的是同一个坐标系，直接可比。
     *
     * 用途：父级据此在「光标行低于确认按钮上界」时主动上滚 ——
     * `BasicTextField` 自带的 bringIntoView 只保证光标进入**整个可视区**，
     * 而可视区底部还浮着工具栏/确认按钮，那些它看不见。
     */
    onCursorRect: ((top: Float, bottom: Float) -> Unit)? = null,
    /**
     * 进页时把光标放到「正文文字坐标」里的某一点（y 以正文首行顶边为 0）——
     * 双击进编辑时"双击哪里，光标就放哪里"用。
     */
    placeCursorAt: androidx.compose.ui.geometry.Offset? = null,
    /** 定位完成回调一次（调用方据此清掉请求，避免后续布局重复落位）。 */
    onPlacedCursor: (() -> Unit)? = null,
) {
    val emit by rememberUpdatedState(onValueChange)
    state.bindEmitter { s -> emit(s) }

    // 样式 / 颜色在组合期写入状态：显示文字与绘制都依赖它们。
    // 放组合期而不是 LaunchedEffect，是为了同一帧内就用到新值。
    state.baseStyle = textStyle
    state.symbolColor = symbolColor
    state.checkedColor = checkedColor

    // 宿主字符串变化 → 同步（内部忽略自己的回声）。
    LaunchedEffect(value) { state.syncFromHost(value) }

    val fieldState = state.field
    val drawStyle = remember(textStyle, color) { textStyle.copy(color = color) }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 输入框自身在 root 中的顶边。只被 onCursorRect 读取（不进组合期），
    // 所以用状态容器存也不会引起重组。
    var fieldOriginInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val items = rememberSymbolItems(
        doc = state.doc,
        layout = layout,
        style = textStyle,
        symbolColor = symbolColor,
    )

    // 落位请求：布局就绪后把「正文文字坐标」的一个点换算成字符偏移，设进光标。
    // 只在请求变化时算一次（算完回调让调用方清掉），避免每帧重复落位。
    LaunchedEffect(layout, placeCursorAt) {
        val l = layout ?: return@LaunchedEffect
        val p2 = placeCursorAt ?: return@LaunchedEffect
        val y = p2.y.coerceIn(0f, l.size.height.toFloat())
        val off = runCatching {
            l.getOffsetForPosition(androidx.compose.ui.geometry.Offset(p2.x, y))
        }.getOrDefault(0)
        state.placeCursorAt(off)
        // 落位后立刻按**新光标**再上报一次：父级的滚动逻辑拿的是这份值，
        // 不重报的话它仍按落位前的位置（通常是文末）去滚，表现就是「一进去就到底」。
        val newLine = runCatching { l.getLineForOffset(off) }.getOrDefault(0)
        onCursorRect?.invoke(
            fieldOriginInRoot.y + l.getLineTop(newLine),
            fieldOriginInRoot.y + l.getLineBottom(newLine),
        )
        onPlacedCursor?.invoke()
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight.dp)
                // 复选框点击拦截挂在**容器**上，只在真的点中复选框时消费事件；
                // 其余情况一律放行 —— 否则点文字区的点击会被吃掉，
                // 光标无法落到正文（会一直停在标题上）。
                .richCheckBoxTaps(
                    items = items,
                    style = textStyle,
                    onToggleCheck = { index -> state.toggleCheckAt(index) },
                ),
        ) {
            // 用 doc.isBlank 而不是 plainText.isEmpty()：
            // 列表符号属于显示层，一行设为列表后正文仍为空，
            // 但它已经不是空白段落，提示必须让位。
            if (state.doc.isBlank && placeholder != null) {
                VText(placeholder, textStyle, color = VColors.ink3)
            }
            BasicTextField(
                value = fieldState,
                onValueChange = { next -> state.onFieldChange(next) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { fieldOriginInRoot = it.positionInRoot() }
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .onFocusChanged { state.focused = it.isFocused },
                textStyle = drawStyle,
                cursorBrush = SolidColor(VColors.accent),
                // 不使用 VisualTransformation：文本流里没有符号，
                // 不需要显示层伪装，也就不需要文档↔显示的双坐标换算。
                onTextLayout = { result ->
                    layout = result
                    // **只在没有选区时上报光标行**（用户 2026-09-28）：
                    // 旧写法把「选区末端」当光标上报，父级据此滚屏 ——
                    // 于是拖动选区手柄时，父级一边跟着选区末端滚、框架自己也在
                    // bringIntoView，两边抢同一个 ScrollState，表现就是手柄抽动、范围乱跳。
                    if (onCursorRect != null && fieldState.selection.collapsed) {
                        // 用光标偏移反查所在行：显示文字里含列表前缀，
                        // 直接用字符偏移会落在符号上。
                        val off = fieldState.selection.end
                            .coerceIn(0, result.layoutInput.text.length)
                        val line = runCatching { result.getLineForOffset(off) }.getOrDefault(0)
                        // 输出 root 坐标：输入框在 root 里的顶边 + 行内相对位置。
                        onCursorRect(
                            fieldOriginInRoot.y + result.getLineTop(line),
                            fieldOriginInRoot.y + result.getLineBottom(line),
                        )
                    }
                },
            )

            // 符号层：**叠在输入框之上**、与它共享同一原点。
            //
            // 必须用 matchParentSize 而不是排在 Column 里 ——
            // 排在下面会占额外高度（把输入框顶上去），
            // 且原点与输入框不同，符号会错位。
            //
            // 它**不挂任何 pointerInput**：叠在文字之上但不能拦点击，
            // 否则文字区就点不动了（复选框的拦截在容器的 richCheckBoxTaps 里）。
            RichSymbolLayer(
                items = items,
                style = textStyle,
                symbolColor = symbolColor,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
