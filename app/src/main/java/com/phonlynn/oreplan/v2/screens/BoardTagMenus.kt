package com.phonlynn.oreplan.v2.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 标签栏根胶囊（「全部」）的固定 key。 */
const val TAG_PILL_ROOT_KEY = "tag_root"

/** 卡片编辑页「选择标签」占位胶囊的 key。 */
const val TAG_PILL_PLACEHOLDER_KEY = "tag_placeholder"

/** 胶囊右侧的状态箭头：无 / 收起 › / 展开 ⌄（设计稿 JRggW 与 iwhAU 的两种状态）。 */
enum class TagPillChevron { None, Right, Down }

/** 一枚面包屑胶囊。 */
data class TagPillSpec(
    val key: String,
    val label: String,
    val chevron: TagPillChevron = TagPillChevron.None,
    /** 是否正在退场（由调用方标记）。退场胶囊原地淡出，不响应点击。 */
    val isExiting: Boolean = false,
)

/**
 * 标签面包屑胶囊行（设计稿：h30 r11 accent-soft，[全部] [学习] [课程]…）。
 * 胶囊的出现/消失都有过渡：新增胶囊淡入 + 从 0.88 轻微放大；
 * 被移除的胶囊保持挂载到退场动画播完再移除。
 * 通过 [onPillLeft] / [onBottom] 回报每枚胶囊左缘与整行底边的屏幕坐标，用于锚定下拉菜单。
 */
/**
 * 把「当前胶囊」补全为「当前胶囊 + 正在退场的胶囊」，供 [TagPillRow] 渲染。
 *
 * 退场状态由**副作用**维护（组合期只读取结果）。这样做的原因：
 * 早先把「检测消失 → 保留挂载 → 延时移除」写在组合期，依赖列表引用比较判断变化，
 * 而列表每帧都是新实例、重组又可能被跳过，推导结果会在一帧内反复变化，
 * 表现为末级子标签的箭头「消失 → 又出现」。
 *
 * 多个页面（白板主页、卡片编辑页）共用这一份，避免各写一套导致表现不一致。
 */
@Composable
fun rememberPillsWithExiting(pills: List<TagPillSpec>): List<TagPillSpec> {
    var rendered by remember { mutableStateOf(pills) }
    var exiting by remember { mutableStateOf<List<TagPillSpec>>(emptyList()) }
    LaunchedEffect(pills) {
        val aliveKeys = pills.map { it.key }.toSet()
        val newlyGone = rendered.filterNot { it.key in aliveKeys }
        exiting = (exiting + newlyGone).distinctBy { it.key }.filterNot { it.key in aliveKeys }
        rendered = pills + exiting.map { it.copy(isExiting = true) }
        if (newlyGone.isNotEmpty()) {
            // 保持挂载到退场动画播完（退场 220ms，留余量）。
            delay(270L)
            val goneKeys = newlyGone.map { it.key }.toSet()
            exiting = exiting.filterNot { it.key in goneKeys }
            rendered = pills + exiting.map { it.copy(isExiting = true) }
        }
    }
    return rendered
}

@Composable
fun TagPillRow(
    pills: List<TagPillSpec>,
    onPillClick: (Int) -> Unit,
    onPillLeft: (String, Float) -> Unit,
    onBottom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 纯渲染组件：只按传入顺序渲染胶囊，不做任何退场推导。
    //
    // 退场状态（哪些胶囊正在消失、消失到什么时候）由调用方的副作用维护，
    // 这里拿到的 [pills] 已经包含「正在退场的胶囊」。
    //
    // 这样做的原因：早先把「检测消失 → 保留挂载 → 延时移除」写在组合期，
    // 依赖引用比较判断列表是否变化——而列表每帧都是新实例、重组又可能被跳过，
    // 推导结果会在一帧内反复变化，导致末级子标签的箭头出现「消失 → 又出现」的抖动。
    // 组合期只读状态、副作用里改状态，这类问题从根上消失。

    Row(
        modifier = modifier.onGloballyPositioned { coords ->
            onBottom(coords.positionInRoot().y + coords.size.height)
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pills.forEachIndexed { index, pill ->
            key(pill.key) {
                AnimatedPillChip(
                    spec = pill,
                    // 退场胶囊：原地淡出、不响应点击。
                    visible = !pill.isExiting,
                    onPillLeft = onPillLeft,
                    onClick = if (pill.isExiting) null else { { onPillClick(index) } },
                )
            }
        }
    }
}

/**
 * 胶囊的进出场包装。
 *
 * 关键：`animateFloatAsState` 在首次组合时会把初始值设为目标值，所以如果一个新胶囊
 * 一上来就传 `visible = true`，它不会播入场动画（直接就是终态）。
 * 因此这里用 `Animatable` + 首次组合时从 0 开始，显式拉出入场过程。
 */
@Composable
private fun AnimatedPillChip(
    spec: TagPillSpec,
    visible: Boolean,
    onPillLeft: (String, Float) -> Unit,
    onClick: (() -> Unit)?,
) {
    // 入场：首次组合时从 0 开始动画到目标值，保证新胶囊有入场过程。
    // 退场：`visible` 变 false 时从当前值动画到 0。
    val progress = remember {
        Animatable(if (visible) 0f else 1f)
    }
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (visible) 240 else 220,
                easing = if (visible) VMotion.Glide else VMotion.Accelerate,
            ),
        )
    }
    val p = progress.value.coerceIn(0f, 1f)
    // 宽度：入场时从 0 长到原宽（新胶囊平滑挤出空间）；退场时保持原宽不动。
    val widthFactor = if (visible) p else 1f
    // 缩放：只在入场时做轻微的「放大落位」；退场**完全不缩放**。
    //
    // 退场只有一种情况：点了左侧的父标签，它右侧的整串标签一起消失。
    // 这些标签应当**维持原样原地淡出**——宽度、位置、形态都不变，只有透明度下降。
    // 之前退场也走缩放（1.0 → 0.88），胶囊在淡出过程中持续变小，
    // 箭头看起来就在动，这正是「右箭头闪动」的来源。
    val scaleFactor = if (visible) 0.88f + 0.12f * p else 1f
    Box(
        Modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = p
                scaleX = scaleFactor
                scaleY = scaleFactor
            }
            .layout { measurable, constraints ->
                // 先按原约束测量，再在 layout 里按 widthFactor 收窄宽度。
                //
                // 注意：**不能**把 maxWidth 乘系数后生成新约束——maxWidth 可能是
                // Constraints.Infinity（一个超大整数），相乘后超出 Constraints 的合法
                // 表示范围会直接抛 IllegalArgumentException（曾造成进入白板页闪退）。
                val placeable = measurable.measure(constraints)
                layout((placeable.width * widthFactor).roundToInt(), placeable.height) {
                    placeable.place(0, 0)
                }
            }
            .onGloballyPositioned { coords -> onPillLeft(spec.key, coords.positionInRoot().x) },
    ) {
        TagPillChip(
            label = spec.label,
            // 直接用它自己的形态。退场胶囊保持离开时的样子（宽度、箭头都不变），
            // 整个退场过程只有透明度在变。
            chevron = spec.chevron,
            onClick = onClick ?: {},
        )
    }
}

@Composable
private fun TagPillChip(
    label: String,
    chevron: TagPillChevron,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 箭头区宽度走动画：从 0 平滑展开到「4dp 间距 + 13dp 图标」。
    // 这样箭头出现时胶囊是「右扩」而不是瞬间变宽——两侧都有过渡，不会一格一跳。
    val hasChevron = chevron != TagPillChevron.None
    val targetWidth = if (hasChevron) 17.dp else 0.dp
    // 胶囊实例在整个生命周期里不重建，所以这里的动画天然连续，
    // 不需要「冻结 / 强制」之类的特殊处理。
    val chevronWidth = androidx.compose.animation.core.animateDpAsState(
        targetValue = targetWidth,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 280,
            easing = VMotion.Emphasized,
        ),
        label = "pillChevronWidth",
    ).value

    Row(
        modifier = modifier
            .height(30.dp)
            .background(VColors.accentSoft, RoundedCornerShape(11.dp))
            .vPressable(scaleDown = 0.94f, onClick = onClick)
            .padding(horizontal = 10.dp),
        // 间距交给箭头自己的动画宽度控制，这里不再用 spacedBy，
        // 否则箭头宽度归零时间距仍在，右扩动画会不连续。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(
            label,
            VTypo.caption12.copy(fontWeight = FontWeight.Medium),
            color = VColors.accent,
            maxLines = 1,
        )
        Box(
            Modifier
                .width(chevronWidth)
                .height(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 内容随宽度一起淡入淡出，避免图标在极窄宽度里被挤变形。
            val iconAlpha = androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (hasChevron) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 280,
                    easing = VMotion.Emphasized,
                ),
                label = "pillChevronAlpha",
            ).value
            if (chevronWidth > 0.dp) {
                Icon(
                    if (chevron == TagPillChevron.Down) Lucide.ChevronDown else Lucide.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(13.dp)
                        .alpha(iconAlpha),
                    tint = VColors.accent,
                )
            }
        }
    }
}

/**
 * 逐级标签菜单（设计稿 iwhAU / n8fV5）：只列当前级的子标签，每行带 ›；
 * 分隔线下方是「+ 新建标签」（在二级菜单里新建即当前标签的子标签）。
 */
@Composable
fun TagDrillMenu(
    children: List<BoardTag>,
    onPick: (BoardTag) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(210.dp)
            .menuSurface(20.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .padding(6.dp),
    ) {
        children.forEach { tag ->
            TagMenuRow(tag.name) { onPick(tag) }
        }
        if (children.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .height(1.dp)
                    .background(VColors.line),
            )
        }
        TagMenuCreateRow(height = 42.dp, onCreate = onCreate)
    }
}

/**
 * 全部标签树面板（设计稿 skbca）：父级 + 缩进子级 + 左侧连接线，
 * 每行右侧 ⋯ 进标签设置，底部「+ 新建标签」。
 */
@Composable
fun TagTreePanel(
    tags: List<BoardTag>,
    onPick: (BoardTag) -> Unit,
    onSettings: (BoardTag) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topLevel = tags.filter { it.parentId == null }.sortedBy { it.sortIndex }
    val children = tagChildrenMap(tags)
    Column(
        modifier = modifier
            .menuSurface(24.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .padding(6.dp),
    ) {
        // 递归渲染任意层级：原先只渲染两层，导致在末级标签下新建的三级标签在树面板里不可见。
        // 顶层行高 38dp、其余层级 34dp，缩进与左侧连接线沿用原有视觉。
        topLevel.forEach { parent ->
            TagTreeBranch(
                tag = parent,
                children = children,
                depth = 0,
                onPick = onPick,
                onSettings = onSettings,
            )
        }
        TagMenuCreateRow(height = 44.dp, onCreate = onCreate)
    }
}

/**
 * 树面板的一支：先画自身一行，再递归画它的子级。
 * [depth] = 层级深度（顶层 0，逐级 +1），用于**递归缩进**——
 * 原先用布尔 isParent 只区分「顶层/非顶层」，导致子与孙同一缩进。
 */
@Composable
private fun TagTreeBranch(
    tag: BoardTag,
    children: Map<String?, List<BoardTag>>,
    depth: Int,
    onPick: (BoardTag) -> Unit,
    onSettings: (BoardTag) -> Unit,
) {
    TagTreeRow(
        name = tag.name,
        isParent = depth == 0,
        depth = depth,
        onTap = { onPick(tag) },
        onSettings = { onSettings(tag) },
    )
    val kids = children[tag.id].orEmpty().sortedBy { it.sortIndex }
    if (kids.isEmpty()) return
    Box(Modifier.fillMaxWidth()) {
        Column {
            kids.forEach { child ->
                TagTreeBranch(
                    tag = child,
                    children = children,
                    depth = depth + 1,
                    onPick = onPick,
                    onSettings = onSettings,
                )
            }
        }
        // 连接线高度按整支的可见行数估算（34dp 一行）；
        // x 位置跟随当前层级的缩进（子级的线在子级缩进稍左侧）。
        Box(
            Modifier
                .padding(start = (12 + 23 * (depth + 1) - 14).dp)
                .width(1.dp)
                .height((34 * countBranchRows(kids, children)).dp)
                .background(VColors.line),
        )
    }
}

/** 统计一整支（含所有后代）的行数，用于画左侧连接线。 */
private fun countBranchRows(
    tags: List<BoardTag>,
    children: Map<String?, List<BoardTag>>,
): Int = tags.sumOf { tag ->
    1 + countBranchRows(children[tag.id].orEmpty(), children)
}

/**
 * 标签菜单/面板的完整呈现。
 *
 * 重写要点（针对「动画完全不播」）：
 * 1. 内容**永久挂载**，不存在任何条件渲染分支——`content()` 始终在组合树里。
 *    （旧写法用 `if (p > 0f)` 把内容包在条件里，导致 `p == 0` 时内容根本没被组合，
 *    打开的第一帧要用来「组合内容」而不是「驱动动画」，动画被吞掉。）
 * 2. 淡入淡出由 `animateFloatAsState` 纯声明式驱动，不依赖任何协程/effect 时序。
 * 3. 不可见时用 `pointerInput` 的 `enabled` 开关拦触摸，不靠卸载内容。
 */
@Composable
fun TagMenuPresenter(
    visible: Boolean,
    topPx: Float,
    leftPx: Float,
    onDismiss: () -> Unit,
    menuWidthPx: Float = 0f,
    /**
     * 「本次打开」的标识：调用方在每次打开时递增。
     * 位置动画状态与消费它的渲染代码会被包在 [key] 里同时重建——
     * 这样「打开时直接落位」与「打开期间平滑跟随」可以各自用对的机制实现：
     *   - 重建：让 animateFloatAsState 以当前锚点作为初始值（等价于 snapTo，直接落位）
     *   - 打开期间锚点变化：animateFloatAsState 从当前值平滑改道（不重启，跟得上）
     *
     * 注意 key 必须把「动画状态」和「读它的渲染」放在同一个作用域内。
     * 只在 key 块里创建动画、再在块外读它的值，会读到已离开组合的 State，直接崩溃。
     */
    resetKey: Int = 0,
    /**
     * 关闭遮罩的顶边（px）：应传「标签栏底边」。
     *
     * 遮罩从标签栏下方开始铺满，**不能盖住标签栏**——
     * 否则菜单打开期间标签栏点不动（用户点其他胶囊想切级，会被遮罩吃掉）。
     * 菜单本体仍由 [topPx] 定位，两者互不影响。
     */
    scrimTopPx: Float = 0f,
    content: @Composable () -> Unit,
) {
    // 右端边界钳制：菜单左缘贴近屏幕右侧时，把它往回拉到刚好贴边，
    // 保证菜单整体始终在屏幕内。menuWidthPx 为 0 时不做处理（如树面板自身已 fillMaxWidth）。
    val screenWidthPx = LocalConfiguration.current.screenWidthDp * LocalDensity.current.density
    val marginPx = with(LocalDensity.current) { 12.dp.toPx() }
    val clampedLeftPx = if (menuWidthPx > 0f && screenWidthPx > 0f) {
        leftPx.coerceIn(marginPx, (screenWidthPx - menuWidthPx - marginPx).coerceAtLeast(marginPx))
    } else {
        leftPx
    }
    val p by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 180 else 140,
            easing = if (visible) VMotion.Glide else VMotion.Accelerate,
        ),
        label = "tagMenuFade",
    )
    val progress = p.coerceIn(0f, 1f)

    // 位置处理，两条规则：
    //
    //  1) 从关闭到打开 —— **直接落位**在锚点上。若沿用上一处的位置再动画过去，
    //     会看到菜单从别处（例如右侧）飞进来。
    //  2) 已经打开、锚点又变了 —— **平滑跟随**。
    //
    // 跟随必须用 animateFloatAsState，不能用 Animatable + LaunchedEffect/collect：
    // 后者在锚点高频变化时每帧取消上一次动画再重启，而被取消时**速度信息一并丢失**，
    // 每次都从静止重新起步，结果是「一直追、追不上」。
    // animateFloatAsState 内部是持续运行的动画状态机，目标变化时保留当前速度平滑改道。
    //
    // 「打开时直接落位」靠 [resetKey] 实现：它只在打开瞬间变化，导致动画状态重建，
    // animateFloatAsState 便以当前锚点作为初始值——等价于 snapTo，且不会把跟随拖下水。
    //
    // 手感参数：dampingRatio = 1.0 是临界阻尼（全程无过冲，末端不弹）；
    // stiffness 控制跟随速度，越小越柔。
    val menuFollow = spring<Float>(dampingRatio = 1f, stiffness = 420f)

    // 淡出期间冻结位置：只在可见时跟随锚点，否则菜单会一边淡出一边平移。
    var frozenLeft by remember { mutableStateOf(clampedLeftPx) }
    var frozenTop by remember { mutableStateOf(topPx) }
    if (visible) {
        frozenLeft = clampedLeftPx
        frozenTop = topPx
    }

    key(resetKey) {
    // 动画状态与读它的渲染在同一个 key 作用域内，一起重建。
    val animLeft by animateFloatAsState(
        targetValue = frozenLeft,
        animationSpec = menuFollow,
        label = "tagMenuLeft",
    )
    val animTop by animateFloatAsState(
        targetValue = frozenTop,
        animationSpec = menuFollow,
        label = "tagMenuTop",
    )

    Box(Modifier.fillMaxSize()) {
        // 点菜单外关闭的遮罩：只在可见时接收触摸。
        //
        // 两个要点：
        //  1) **不位移**。曾写成 fillMaxSize() 后再 translation 跟到菜单锚点，
        //     遮罩被推离屏幕，部分区域点不动。
        //  2) **不盖住标签栏**。从标签栏底边（scrimTopPx）开始铺，让菜单打开期间
        //     标签栏仍然可点（用户可以直接点别的胶囊切级，不用先关菜单）。
        if (visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = with(LocalDensity.current) { scrimTopPx.toDp() })
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        // 菜单本体：只要还没淡到底就保持挂载，淡完自动不参与命中测试。
        // 动画状态（progress）在外层持续驱动，与本条件无关。
        // 用 graphicsLayer 的 translation 而非 offset 定位：graphicsLayer 只在绘制阶段应用，
        // 不会在动画期间触发重新布局（重新布局可能打断动画）。
        if (progress > 0.001f) {
            Box(
                Modifier.graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = animLeft
                    translationY = animTop
                    alpha = progress
                    val k = 0.94f + 0.06f * progress
                    scaleX = k
                    scaleY = k
                },
            ) {
                content()
            }
        }
    }
    }   // key(resetKey)
}

@Composable
private fun TagMenuRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VText(
            label,
            VTypo.body.copy(fontSize = 14.sp),
            color = VColors.ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(14.dp), tint = VColors.ink3)
    }
}

@Composable
private fun TagMenuCreateRow(height: Dp, onCreate: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .vPressable(scaleDown = 0.98f, onClick = onCreate),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Plus, contentDescription = null, modifier = Modifier.size(14.dp), tint = VColors.accent)
        Spacer(Modifier.size(6.dp))
        VText("新建标签", VTypo.caption12.copy(fontSize = 13.sp), color = VColors.accent, maxLines = 1)
    }
}

@Composable
private fun TagTreeRow(
    name: String,
    isParent: Boolean,
    depth: Int = 0,
    onTap: () -> Unit,
    onSettings: () -> Unit,
) {
    // 递归缩进：顶层 12dp，之后每深一级 +23dp（与原第二级 35dp 对齐）。
    val startPad = if (depth == 0) 12.dp else (12 + 23 * depth).dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (isParent) 38.dp else 34.dp)
            .vPressable(scaleDown = 0.99f, onClick = onTap)
            .padding(start = startPad, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(
            name,
            VTypo.body.copy(
                fontSize = 14.sp,
                fontWeight = if (isParent) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (isParent) VColors.ink else VColors.ink2,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.size(28.dp).vPressable(scaleDown = 0.85f, onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Ellipsis, contentDescription = "标签设置", modifier = Modifier.size(14.dp), tint = VColors.ink3)
        }
    }
}

/** 菜单/面板统一表面：surface 底 + 1px 描边 + 软投影（设计稿 0/8~10 模糊 20~24 的 15% 黑）。 */
private fun Modifier.menuSurface(elevation: Dp): Modifier = this
    .shadow(
        elevation = elevation,
        shape = RoundedCornerShape(16.dp),
        ambientColor = Color(0x26101613),
        spotColor = Color(0x26101613),
    )
    .background(VColors.surface, RoundedCornerShape(16.dp))
    .border(1.dp, VColors.line, RoundedCornerShape(16.dp))

