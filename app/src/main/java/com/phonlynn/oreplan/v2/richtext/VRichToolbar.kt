@file:JvmName("RichTextToolbarKt")

package com.phonlynn.oreplan.v2.richtext

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.core.rt.RichAttr
import com.phonlynn.oreplan.core.rt.RichEditState
import com.phonlynn.oreplan.core.rt.RichLineKind
import com.phonlynn.oreplan.core.rt.setAttr
import com.phonlynn.oreplan.core.rt.setLineKind
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.vPressable

/** 工具栏高度（含边框与上下内边距）。三点菜单要靠它向上让位。 */
private val TOOLBAR_HEIGHT = 48.dp

/** 三点菜单与工具栏之间的间隙。 */
private val MENU_GAP = 6.dp

/**
 * 高亮底的内缩量（每侧）。
 *
 * 相邻按钮同时激活时，两块高亮底必须有可见的间隔，
 * 否则会看成「边框重叠成一大块」。
 */
private val TOOL_BUTTON_INSET = 3.dp

/**
 * 富文本编辑工具栏（2026-09-23，**UI 一字未改**，只换状态源）。
 *
 * 按钮**直接改文档属性**，不插入任何标记：
 *   撤销 / 重做 ｜ 无序 / 有序 / 复选框 ｜ 加重 / 下划线 / 删除线
 *
 * 行为：
 *  · **有选中（设置模式）**：把选中文字**设为**该属性的目标态——
 *    已全带就取消、否则一律设为带上。不再逐字翻转（那会让用户觉得「越点越乱」）。
 *    三种属性互不干扰；
 *  · **无选中**：进入「输入模式」，之后打的字自动带该属性并**持续生效**，
 *    直到再次点击取消；此时按钮保持高亮。
 *
 * 视觉与旧版完全一致（八个按钮按 weight 均分铺满、组间分隔线、激活态用强调色浅底）。
 */
@Composable
fun VRichToolbar(
    state: RichEditState,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    // 读 revision 让高亮随文档变化刷新。
    @Suppress("UNUSED_EXPRESSION")
    state.revision

    val doc = state.doc
    val sel = state.field.selection
    val hasSelection = sel.start != sel.end
    // 选区是**显示坐标**（每行带前缀：标记字符 + 缩进字符），
    // 而属性操作要的是**内容坐标**（段内列）。
    // 前缀长度不是固定值（由 `IndentEm` 决定），所以必须走状态的换算，
    // 不能自己减一个写死的常量。
    val anchorMin = state.contentAnchorIn(state.field.text, sel.min)
    val anchorMax = state.contentAnchorIn(state.field.text, sel.max)
    val lineIndex = anchorMax.first.coerceIn(0, doc.lines.lastIndex.coerceAtLeast(0))
    val line = doc.lines.getOrNull(lineIndex) ?: return
    val contentLen = line.text.length
    val rangeStart = if (anchorMin.first == lineIndex) anchorMin.second.coerceIn(0, contentLen) else 0
    val rangeEnd = anchorMax.second.coerceIn(rangeStart, contentLen)

    /** 高亮判定：选区全带才亮；无选中看输入模式。 */
    fun attrActive(attr: RichAttr): Boolean {
        if (!hasSelection) return attr in state.pendingAttrs
        if (rangeStart >= rangeEnd) return false
        for (i in rangeStart until rangeEnd) {
            if (!line.hasAttr(i, attr)) return false
        }
        return true
    }

    fun onAttr(attr: RichAttr) {
        if (hasSelection) {
            // 设置模式：已全带 → 再点取消；否则一律设为带上。
            // 不能用 toggleAttr：它在掺杂时会逐字翻转，用户会看到「越点越乱」。
            val allOn = rangeStart < rangeEnd &&
                (rangeStart until rangeEnd).all { line.hasAttr(it, attr) }
            state.applyDocKeepSelection(
                doc.setAttr(lineIndex, rangeStart, rangeEnd, attr, on = !allOn),
            )
            // 做了一次显式设置：终结此前的输入模式，避免两种模式互相污染。
            state.pendingAttrs = emptySet()
        } else {
            // 切换该属性（可多选：三种可同时生效）。
            state.pendingAttrs = if (attr in state.pendingAttrs) {
                state.pendingAttrs - attr
            } else {
                state.pendingAttrs + attr
            }
        }
    }

    fun onKind(kind: RichLineKind) {
        state.applyDocKeepSelection(doc.setLineKind(lineIndex, kind))
        // 段类型也是显式设置，同样终结输入模式。
        state.pendingAttrs = emptySet()
    }

    // 三点菜单的开关。
    //
    // 提升到 RichEditState 而不是本地 remember：编辑页与全屏浮层是两个
    // VRichToolbar 实例，本地状态会随实例重建（fsProgress / revision 变化）
    // 而被重置，表现为「菜单刚打开就没了」。
    val menuOpen = state.menuOpen
    // 菜单展开时系统返回键先收菜单，而不是直接退出页面。
    // 同窗口浮层不参与 back 分发，所以由这里显式接管，行为确定。
    androidx.activity.compose.BackHandler(enabled = menuOpen) { state.menuOpen = false }

    // 工具栏根节点。
    //
    // 菜单用**同窗口内的浮层**（不是 Popup），与项目里 TagMenuPresenter 同一套做法。
    //
    // 为什么放弃 Popup（2026-09-21 定）：
    //  · `focusable = true` 会抢走输入框焦点 → 键盘收起 →
    //    工具栏本身因为「键盘不在就隐藏」而消失，菜单跟着一起没；
    //  · `focusable = false` 时 Popup 收不到窗口外的点击，
    //    `onDismissRequest` 不会被调用，于是「再点一下三点按钮」收不回菜单。
    // 两者不可兼得，所以改用项目里已验证的做法：
    // **不加 Popup，用一层不盖住工具栏的遮罩承接外部点击**。
    Box(modifier.fillMaxWidth()) {
        // 展开/收起走淡入 + 上浮，不用 if 硬切（元素出现必须带过渡）。
        val menuProgress by animateFloatAsState(
            targetValue = if (menuOpen) 1f else 0f,
            animationSpec = VMotion.settle(),
            label = "richToolbarMenu",
        )
        // ---- 点菜单外关闭的遮罩 ----
        //
        // **不能盖住工具栏本身**（遮罩从工具栏顶边往上铺）：
        // 三点按钮必须保持可点，用户才能在「再点一下」时把菜单收回去。
        // 这与 TagMenuPresenter 的 scrimTopPx 是同一个理由。
        if (menuProgress > 0.001f) {
            Box(
                Modifier
                    .matchParentSize()
                    // 往上让开工具栏高度：遮罩只铺在工具栏**上方**的区域。
                    .offset(y = -TOOLBAR_HEIGHT)
                    .pointerInput(Unit) { detectTapGestures { state.menuOpen = false } },
            )
        }

        // ---- 上方展开的横向小菜单（三点菜单的内容）----
        //
        // 声明位置在遮罩**之后**：后声明的画在上面，菜单才不会被遮罩吃掉点击。
        // （这正是最初版本的教训：菜单先声明、被后声明的不透明内容盖住 → 点不开。）
        // 展开/收起走淡入 + 上浮，不用 if 硬切（元素出现必须带过渡）。
        if (menuProgress > 0.001f) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    // 向上让开工具栏自身高度，使菜单落在工具栏**上方**。
                    // 用 offset（布局位移）而不是只靠绘制位移：这里没有动画平移需求，
                    // 静态定位用 offset 更直观，也不会影响命中测试与绘制不一致。
                    .offset(y = -(TOOLBAR_HEIGHT + MENU_GAP))
                    .graphicsLayer {
                        alpha = menuProgress
                        // 从下方（工具栏方向）小幅上浮入位。
                        translationY = (1f - menuProgress) * with(density) { 10.dp.toPx() }
                    }
                    .background(VColors.surface, RoundedCornerShape(12.dp))
                    .border(1.dp, VColors.line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 三项格式：删除线 / 斜体 / 荧光笔加重。
                MenuButton(Lucide.Strikethrough, "删除线", attrActive(RichAttr.STRIKE)) {
                    onAttr(RichAttr.STRIKE)
                    state.menuOpen = false
                }
                MenuButton(Lucide.Italic, "斜体", attrActive(RichAttr.ITALIC)) {
                    onAttr(RichAttr.ITALIC)
                    state.menuOpen = false
                }
                MenuButton(Lucide.Highlighter, "荧光笔加重", attrActive(RichAttr.HIGHLIGHT)) {
                    onAttr(RichAttr.HIGHLIGHT)
                    state.menuOpen = false
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(TOOLBAR_HEIGHT)
                .background(VColors.surface)
                .border(1.dp, VColors.line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            ToolButton(Lucide.Undo2, "撤销", active = false) { state.undo() }
            ToolButton(Lucide.Redo2, "重做", active = false) { state.redo() }
            ToolDivider()
            ToolButton(Lucide.List, "无序列表", active = line.kind == RichLineKind.BULLET) {
                onKind(RichLineKind.BULLET)
            }
            ToolButton(Lucide.ListOrdered, "有序列表", active = line.kind == RichLineKind.ORDERED) {
                onKind(RichLineKind.ORDERED)
            }
            ToolButton(Lucide.SquareCheck, "复选框", active = line.kind == RichLineKind.CHECK) {
                onKind(RichLineKind.CHECK)
            }
            ToolDivider()
            // 顺序：加重 → 下划线 → 三点菜单（含删除线/斜体/荧光笔）。
            ToolButton(Lucide.Bold, "加重", active = attrActive(RichAttr.BOLD)) { onAttr(RichAttr.BOLD) }
            ToolButton(Lucide.Underline, "下划线", active = attrActive(RichAttr.UNDERLINE)) {
                onAttr(RichAttr.UNDERLINE)
            }
            // 三点按钮：展开上方菜单；展开时按钮保持高亮。
            ToolButton(Lucide.Ellipsis, "更多格式", active = state.menuOpen) {
                state.menuOpen = !state.menuOpen
            }
        }
    }
}

/**
 * 三点菜单里的一项：横向小胶囊按钮。
 *
 * 尺寸比主工具栏按钮小一号（菜单是次级入口），但图标尺寸与交互反馈一致。
 */
@Composable
private fun MenuButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .background(
                if (active) VColors.accentSoft else Color.Transparent,
                RoundedCornerShape(9.dp),
            )
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = if (active) VColors.accent else VColors.ink2,
        )
    }
}

/** 单个工具按钮：按 weight 均分宽度，正好铺满功能栏。 */
@Composable
private fun RowScope.ToolButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    // 高亮底必须**向内缩**，不能铺满整个 weight 槽位。
    //
    // 之前把 background 画在 weight(1f) 的整个盒子上，而按钮之间 horizontalArrangement
    // 是 spacedBy(0.dp)：两个相邻按钮同时激活时，两块圆角矩形边缘相贴，
    // 看起来就像「边框重叠成一个大框」（用户 2026-09-21 反馈）。
    //
    // 现在外层占满 weight（保证 8 个按钮宽度均分不变），内层用
    // TOOL_BUTTON_INSET 内缩出间隙，高亮底画在内层上。
    Box(
        Modifier
            .weight(1f)
            .height(34.dp)
            .padding(horizontal = TOOL_BUTTON_INSET),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(
                    if (active) VColors.accentSoft else Color.Transparent,
                    RoundedCornerShape(9.dp),
                )
                .vPressable(scaleDown = 0.9f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = if (active) VColors.accent else VColors.ink2,
            )
        }
    }
}

/** 组间分隔线：固定窄条（不占 weight，避免抢走按钮的宽度）。 */
@Composable
private fun ToolDivider() {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(width = 1.dp, height = 18.dp)
            .background(VColors.line),
    )
}


// ================================================================
// 键盘工具栏容器
//
// 与工具栏同放一处：两者总是一起用（钉在键盘上方的那条就是工具栏）。
// ================================================================

/**
 * 把内容**钉在键盘正上方**的容器（键盘弹出才出现、收起就隐藏）。
 *
 * 出现/消失带走位 + 淡入淡出动画，不用 `if` 硬切。
 *
 * ## 可见性判据
 *
 * 默认由本组件自己读 `WindowInsets.ime`。但如果调用方的父容器已经用了
 * `imePadding()`，该 inset 会被消费，子节点再读到可能是 0，导致 `shown`
 * 恒为 false、工具栏整个不显示（表现为「按钮全都没用」）。
 * 这种情况下请把 [gateOnImeInsets] 传 false，并由调用方用自己算好的
 * 「键盘是否弹出」布尔值直接控制 [visible]，避免两套口径不一致。
 */
@Composable
fun VKeyboardToolbar(
    visible: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 父级已处理 IME inset 时传 false，避免重复内边距把工具栏推高。
     */
    applyImePadding: Boolean = true,
    /**
     * 是否再用 `WindowInsets.ime` 二次判定键盘状态。
     * 父级已经消费过 IME inset（如用了 `imePadding()`）时传 false。
     */
    gateOnImeInsets: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // 用**未被消费**的 IME 高度判断键盘是否可见。
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val shown = visible && (!gateOnImeInsets || imeBottomPx > 0)
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = VMotion.settle(),
        label = "keyboardToolbar",
    )
    if (progress <= 0.001f) return
    val liftPx = if (applyImePadding) imeBottomPx else 0
    Box(
        modifier
            .fillMaxWidth()
            .offset { IntOffset(0, -liftPx) },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = progress
                    translationY = (1f - progress) * with(density) { 12.dp.toPx() }
                },
        ) {
            content()
        }
    }
}
