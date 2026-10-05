package com.phonlynn.oreplan.v2.screens

import androidx.compose.animation.core.Animatable
import com.phonlynn.oreplan.v2.components.VFullscreenImageViewer
import com.phonlynn.oreplan.v2.components.attachmentMetaText
import com.phonlynn.oreplan.v2.richtext.VRichText
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.ItemKind
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import com.phonlynn.oreplan.core.todo.TodoDragSlot
import com.phonlynn.oreplan.core.todo.TodoDropResolution
import com.phonlynn.oreplan.core.todo.TodoDropTarget
import com.phonlynn.oreplan.core.todo.resolveTodoDrop
import com.phonlynn.oreplan.core.todo.planTodoDragTargets
import com.phonlynn.oreplan.core.todo.TodoDragRowInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VAttachmentThumb
import com.phonlynn.oreplan.v2.components.VCheckStyle
import com.phonlynn.oreplan.v2.components.VCheckbox
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VDividerFull
import com.phonlynn.oreplan.v2.components.VEmptyState
import com.phonlynn.oreplan.v2.components.VFAB
import com.phonlynn.oreplan.v2.components.VImagePreviewDialog
import com.phonlynn.oreplan.v2.components.VScreenTitle
import com.phonlynn.oreplan.v2.components.VSegmented
import com.phonlynn.oreplan.v2.components.VTab
import com.phonlynn.oreplan.v2.components.VFabClearance
import com.phonlynn.oreplan.v2.components.VTabBottomPadding
import com.phonlynn.oreplan.v2.components.VTabScaffold
import com.phonlynn.oreplan.v2.components.openAttachmentV2
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.NumFont
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Instant
import androidx.compose.foundation.layout.fillMaxHeight
import com.phonlynn.oreplan.v2.components.VCard
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState

/**
 * 日程（V2）—— 设计稿 DaAgT / chN2d / u0QP0i / PpQVV。
 * 月 / 周 / 日 三种视图；日视图下分「今日日程 / 待办任务」；
 * 点击事件弹出悬浮详情卡（照 u0QP0i）。
 */
@Composable
fun CalendarScreenV2(
    navigate: (String) -> Unit,
    onSelectTab: (VTab) -> Unit,
    onAi: () -> Unit,
    viewModel: CalendarV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 首帧门控：uiState 初值是 CalendarV2UiState()（月网格为空、日期栏=今天），直接渲染会
    // 先闪一帧「空月网格 + 今天」再跳到真实状态（用户 2026-09-30：切到日程画面闪动 /
    // 日期栏样式跳变）。加载完成前只铺页面底色 —— 用 VM 已有的 loaded 字段门控。
    // 放在函数体最前、用 early return：只加 4 行，不动下面任何缩进。
    if (!state.loaded) {
        Box(Modifier.fillMaxSize().background(VColors.bg))
        return
    }
    val storage = viewModel.attachmentStorage
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<EventDetailUi?>(null) }
    var pendingDelete by remember { mutableStateOf<EventDetailUi?>(null) }
    // 日期导航条点中间 → 悬浮日历（复用编辑页那个 VDatePickerDialog）。
    var showDatePicker by remember { mutableStateOf(false) }
    // 「从格子放大到全屏」的起点：点月格时记下该格在屏幕上的矩形。
    var dayEnterFrom by remember { mutableStateOf<Rect?>(null) }
    // 展示区可视区的 root 边界（拖动排序的边缘自动滚屏用）。
    val todoViewport = remember { TodoViewport() }
    // 日视图转场进度：0 = 缩在格子里（背景清晰），1 = 全屏（背景模糊）。
    // 两个方向都靠这一个进度：进入时内容放大 + 背景**逐渐变模糊**；
    // 返回时内容缩回 + 背景**从模糊到清晰**，播完才真正卸载（用户 2026-09-25）。
    // 模糊半径固定不变，只调「模糊层的透明度」（白板定下的原则）。
    val dayVisible = state.view == CalendarView.Day && state.mode == CalendarMode.Schedule
    // 「日视图挂在树上」= 目标就是日视图（**与被点的状态同一帧就挂载**，不等 effect）
    //                    ∨ 退场动画还没播完（dayHold，播完才卸载）。
    //
    // 为什么挂载必须与状态同帧：早先写成「LaunchedEffect(dayVisible){ dayMounted = true }」，
    // 挂载要等**下一帧**，而那一帧正好是「组合日视图」的重帧；动画的起始时间又被记在
    // 同一帧上，于是再下一帧到达时动画已经吃掉了这一整帧的时间 —— 展开看起来是
    // 「先卡住、然后直接跳到位」，日期栏「n日」的淡入也被整段吞掉
    // （用户 2026-09-25 报的两个症状，根因都是它）。
    // 与 DetailLayer 的「组合期同步挂载」是同一套做法。
    var dayHold by remember { mutableStateOf(false) }
    val dayMounted = dayVisible || dayHold
    // ⚠️⚠️ **初值必须按当前视图给，不能写死 0**（用户报的「重放入场动画」根因）。
    //
    // 症状：从别的页面切回日程页时，日期栏的「n日」与副标题「共 N 项安排」
    // 会重新播一遍入场；停在日视图时整个日视图还会先半透明一下再实起来。
    //
    // 为什么：Tab 切换会把日程页**整个卸载重建**（见 V2Root.switchTab 的说明
    // ——「要保留页面，就不可能只有一个页面」）。于是 `remember` 全部归零：
    //   · 这个 Animatable 回到 0；
    //   · `dayHold` 也回到 false ⇒ `dayMounted = dayVisible`，
    //     下面 effect 里 `else if (dayHold)` 那个分支根本不进 ⇒ **连 snapTo(1) 都不执行**，
    //     它就永远停在 0：副标题整块 alpha = 0、日视图 alpha = 0（看起来像刚进场）。
    // 冷启动恢复进日视图走的是同一个坏路径。
    //
    // 修法：以「挂载那一刻视图是不是日视图」为初值 —— 本来就是日视图就直接是**已到位**，
    // 只有真正「从月视图切到日视图」时才需要播动画。
    val dayEnterAnim = remember { Animatable(if (dayVisible) 1f else 0f) }
    LaunchedEffect(dayVisible) {
        if (dayVisible) {
            dayHold = true
            if (dayEnterFrom == null) {
                // **不是「点格子」进来的**（从白板/其他同级页切进日程、或冷启动恢复日视图）：
                // 直接到位，不播放大动画 —— 跳页时那一下放大很莫名（用户 2026-09-25）。
                dayEnterAnim.snapTo(1f)
            } else if (dayEnterAnim.value < 1f) {
                // 用户在本页点了格子：从那个格子放大到全屏（白板聚焦同款弹性）。
                // 值已经到位时不再播 —— 那正是「重建后重放」的来源。
                dayEnterAnim.animateTo(1f, VMotion.springy())
            }
        } else if (dayHold) {
            // 退出：**原地淡出**（几何在 DayViewOverlay 里被冻结在满屏，见 `returning`），
            // 所以这里只需要一个干净的收尾曲线：Accelerate（缓起快收）比弹簧更适合淡出
            // —— 弹簧会在末尾多滑一小段，淡出时看起来就是「明明该没了又多挂了一会儿」。
            dayEnterAnim.animateTo(0f, tween(durationMillis = 160, easing = VMotion.Accelerate))
            dayHold = false
            // 起点用完即清：下次从其他页面切进来时没有起点 → 不会播放大动画。
            // 放在退场**播完之后**：中途又点回日视图时起点仍在，放大动画不会丢。
            dayEnterFrom = null
        }
    }

    fun openDetail(key: String) {
        scope.launch { detail = viewModel.loadDetail(key) }
    }

    // 页面内容整体交给 DetailLayer：浮层显示期间（含退场动画）由它给内容加背景模糊，
    // 并保证退场动画播完之前数据不被清空。
    if (showDatePicker) {
        VDatePickerDialog(
            initial = state.selectedDate,
            onConfirm = {
                viewModel.selectDate(it)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }

    DetailLayer(
        target = detail,
        storage = storage,
        onRequestClose = { detail = null },
        onEdit = { d ->
            if (d.isCourse) {
                d.courseId?.let { navigate(V2Routes.courseEditor(it)) }
            } else {
                d.itemId?.let { navigate(V2Routes.editor(itemId = it)) }
            }
        },
        onDelete = { pendingDelete = it },
        onArchive = { it.itemId?.let(viewModel::archiveItem) },
    ) {
        Box(Modifier.fillMaxSize()) {
            VTabScaffold(
                active = VTab.Calendar,
                onSelect = onSelectTab,
                fab = {
                    VFAB(
                        onClick = {
                            navigate(
                                V2Routes.editor(
                                    day = state.selectedDate,
                                    kind = if (state.mode == CalendarMode.Todo) {
                                        ItemKind.TASK.name
                                    } else {
                                        ItemKind.EVENT.name
                                    },
                                ),
                            )
                        },
                    )
                },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    // ==================== 标题栏（固定） ====================
                    // 标题 / 分段 / 日期栏**不参与任何转场动画**：不滚动、不模糊、不缩放。
                    // 日期栏的日号淡入、副标题交叉，是它自己的文字过渡（用户 2026-09-25）。
                    Column(
                        Modifier
                            // 标题栏（含日期栏）**画在展示区之上**：
                            // 日视图进入/退出是弹性弹簧，过冲时它会放大到伸出展示区、
                            // 盖住日期栏的下缘 —— 抬高本栏的绘制层级，日期栏就始终在它上面
                            // （用户 2026-09-25）。只改绘制顺序，布局不变。
                            .zIndex(1f)
                            .fillMaxWidth()
                            // 底边**不留 padding**：展示区（= 日视图的底色/表面）从日期栏下边缘
                            // 开始 —— 日视图的上边缘要顶到那里（用户 2026-09-25）。
                            // 内容各自的 14dp 呼吸位改由月内容 / 日视图内容自己加。
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        VScreenTitle(
                            // V3：标题与 overline 跟随分段（待办段显示「待办」+「今天 · 9月25日」）。
                            title = if (state.mode == CalendarMode.Todo) "待办" else "日程",
                            overline = if (state.mode == CalendarMode.Todo) {
                                "今天 · ${state.selectedDate.monthValue}月${state.selectedDate.dayOfMonth}日"
                            } else {
                                "${state.selectedDate.year}年 · ${state.weekLabel}"
                            },
                            onSidebar = { navigate(V2Routes.SCHEDULE_SETTINGS) },
                            onAi = onAi,
                        )
                        VSegmented(
                            options = listOf("日程", "待办"),
                            selectedIndex = if (state.mode == CalendarMode.Todo) 1 else 0,
                            onSelect = { index ->
                                viewModel.setMode(if (index == 1) CalendarMode.Todo else CalendarMode.Schedule)
                            },
                        )
                        if (state.mode != CalendarMode.Todo) {
                            DateNavRow(
                                state = state,
                                onPrev = { viewModel.shift(false) },
                                onNext = { viewModel.shift(true) },
                                onPickDate = { showDatePicker = true },
                                onToday = { viewModel.goToday() },
                                onToggleHideCourses = { viewModel.toggleHideCoursesInMonth() },
                                dayMode = dayVisible,
                            )
                        }
                    }

                    // ==================== 展示区 ====================
                    // 月网格 / 待办 / 日视图都在这一块里 —— 转场动画也只发生在这里。
                    //
                    // ⚠️ **必须用 BoxWithConstraints 在这里取高度**（而不是在滚动容器里面）：
                    // 这一块就是「视口」本身（weight(1f) 吃掉标题栏之下的全部高度），
                    // 它的高度在**组合期**就已知 —— 不需要等任何子内容测量。
                    // 待办空状态要按视口高度垂直居中，只有在这里拿到的值才是**首帧就正确**的；
                    // 换到滚动容器里读 ScrollState.viewportSize 会晚一帧，切页时闪一下
                    //（用户 2026-10-01 实测报的正是这个）。这样也**天然适配各种屏幕高度**，
                    // 不用写死任何像素。
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInRoot()
                                todoViewport.top = b.top
                                todoViewport.bottom = b.bottom
                            },
                    ) {
                        // 展示区（= 视口）高度：传给待办空状态做垂直居中用。
                        val viewportHeight = maxHeight
                        // 背景内容一律按「月视图」渲染：日视图显示期间，
                        // 它要一直留在下面（直到被完全覆盖），所以不能跟着 view 变。
                        val bgState = if (dayVisible) state.copy(view = CalendarView.Month) else state

                        // ① 月内容：**只组合一次**；转场期间把这一份内容「记录进 GraphicsLayer、
                        //    同一份画两遍」（清晰一遍 + 固定半径模糊一遍），
                        //    模糊那一遍的透明度 = 转场进度 —— 就是「月视图逐渐模糊」。
                        //
                        //    为什么不再「另外组合一份模糊副本」：那份副本是 ~42 个格子、
                        //    一两百个文本节点的**整棵新子树**，全部压在与动画同一帧组合 + 测量，
                        //    单帧被拖到一两百毫秒；而动画的起始时间正好记在这一帧上，于是
                        //    下一帧到达时它已经跑完这一整帧 —— 展开「先卡住再跳到位」、
                        //    日期栏「n日」淡入被吞掉（用户 2026-09-25 报的两个症状，这是主因）。
                        //    白板页 2026-09-17 已定过同一结论：内容只组合一次，用 GraphicsLayer
                        //    记录再画两遍；而且**图层结构必须恒定**（按进度拆/建子树会重建内容）。
                        val monthScroll = rememberScrollState()

                        // ⚠️⚠️ **展开/收起待办集不得重置滚动位置**（用户报的原始问题之一）。
                        //
                        // 根因（字节码级确认）：`ScrollState.maxValue` 是**单向收缩**的 ——
                        //     setMaxValue$foundation(int) = if (value > newMax) value = newMax
                        // 而 `ScrollNode.measure` 会把它重算成 `childHeight − 内容高度`。
                        // 于是收起一个待办集、内容变短时：
                        //     maxValue 立刻变小 ⇒ **当前的 value 被夹掉** ⇒ 位置永久丢失，
                        //     即使随后展开、内容恢复原样，value 也**回不来**了。
                        // 这正是"展开/收起会重置滚动位置"的直接机制。
                        //
                        // 对策：把**用户真实的滚动位置**记在旁边，等下一次测量完成后
                        // 再把它写回去。`value` 被夹掉时它不受影响，所以位置能恢复。
                        var wantedScroll by remember { mutableIntStateOf(-1) }
                        // 用户**自己滚动**时同步记录，这样恢复用的是"用户最后想待的位置"，
                        // 而不是某个旧值。（`snapshotFlow` 只在值真的变化时才触发。）
                        LaunchedEffect(monthScroll) {
                            snapshotFlow { monthScroll.value }
                                .collect { v -> if (v > 0 || wantedScroll < 0) wantedScroll = v }
                        }
                        // 展开/收起导致内容变化之后，把记录的位置写回去。
                        LaunchedEffect(state.todoListRows, state.mode) {
                            withFrameNanos { }
                            if (wantedScroll >= 0) monthScroll.scrollTo(wantedScroll)
                        }
                        // 把 dp 几何换算成 px 用（月网格的纵向常量在文件底部）。
                        val density = LocalDensity.current
                        // 打开月视图时把**今天所在的那一周滚到视口中间**，而不是顶到头
                        // （用户 2026-09-26）。只在本月第一次显示时做一次：
                        // 用户自己滚动过之后不再抢走滚动位置。
                        var didCenterToday by remember { mutableStateOf(false) }
                        LaunchedEffect(state.monthWeeks, state.mode) {
                            if (didCenterToday || state.mode == CalendarMode.Todo) return@LaunchedEffect
                            val weeks = state.monthWeeks
                            if (weeks.isEmpty()) return@LaunchedEffect
                            val todayWeek = weeks.indexOfFirst { w -> w.any { it.isToday } }
                            // 今天不在本月网格内（看的是其他月份）：不强滚，保持顶部。
                            if (todayWeek < 0) {
                                didCenterToday = true
                                return@LaunchedEffect
                            }
                            // 等一帧，让内容先完成测量（否则 maxValue 还是 0，滚不动）。
                            withFrameNanos { }
                            val viewport = monthScroll.viewportSize
                            if (viewport > 0) {
                                // 今天那周的中心相对内容顶部的偏移（px）：
                                // 周头高 + 分割线 + 前面各周的（行高 + 周间分割线）+ 本行一半。
                                // 这些值与 MonthGridV3 里的 dp 常量一一对应。
                                val rowH = with(density) { MonthWeekRowHeight.toPx() }
                                val headerH = with(density) { MonthHeaderHeight.toPx() }
                                val dividerH = with(density) { 1.dp.toPx() }
                                val rowCenter = headerH + dividerH +
                                    todayWeek * (rowH + dividerH) + rowH / 2f
                                val target = (rowCenter - viewport / 2f).coerceAtLeast(0f)
                                monthScroll.scrollTo(target.toInt())
                            }
                            didCenterToday = true
                        }
                        val sharpLayer = rememberGraphicsLayer()
                        val blurLayer = rememberGraphicsLayer()
                        Box(
                            Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    val t = dayEnterAnim.value
                                    // **只在转场进行中才走「图层」这条路**，其余时候一律直接画。
                                    //
                                    // 为什么必须判 isRunning，而不能只看进度值：弹簧停下时的最终值
                                    // 可能是 0.999x 而不是精确的 1，`t >= 0.999f` 有可能永远不成立 ——
                                    // 那就等于**停在日视图期间每帧都记两遍图层 + 画一遍全屏模糊**，
                                    // 而那份月内容早被不透明的日视图完全盖住，纯属白烧：每帧多几十
                                    // 毫秒，整页连同底栏点按都会顿（用户 2026-09-25 反馈的「切主页面
                                    // 有明显延迟」就是这个）。判 isRunning 则「静止 = 直接画」，
                                    // 与最终值无关。
                                    if (!dayMounted || !dayEnterAnim.isRunning) {
                                        this@drawWithContent.drawContent()
                                    } else {
                                        // 清晰层：转场中它一直在下面（模糊层是半透明的），必须画。
                                        sharpLayer.record { this@drawWithContent.drawContent() }
                                        sharpLayer.alpha = 1f
                                        sharpLayer.renderEffect = null
                                        drawLayer(sharpLayer)
                                        // 模糊层：半径**固定**（逐帧改半径要重建 RenderEffect，会明显卡）。
                                        blurLayer.record { this@drawWithContent.drawContent() }
                                        blurLayer.alpha = t
                                        blurLayer.renderEffect = BlurEffect(
                                            DetailBackdropBlur.toPx(),
                                            DetailBackdropBlur.toPx(),
                                            TileMode.Clamp,
                                        )
                                        drawLayer(blurLayer)
                                    }
                                },
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    // 顶部 14dp 放在**滚动容器之外**：展示区从日期栏下边缘开始
                                    // （日视图表面要顶到那里），月内容仍从这里往下 14dp 起，
                                    // 且这 14dp 不参与滚动。
                                    .padding(top = 14.dp)
                                    .verticalScroll(monthScroll, enabled = !dayMounted)
                                    .padding(start = 12.dp, end = 12.dp, bottom = VTabBottomPadding + VFabClearance),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                CalendarDisplayContent(
                                    state = bgState,
                                    onSelectDate = { date, rect ->
                                        dayEnterFrom = rect
                                        // 一次写完日期+视图：状态只更新一次，动画立即起步。
                                        viewModel.openDay(date)
                                    },
                                    onOpenDetail = { openDetail(it) },
                                    onToggleTodo = { viewModel.toggleTodo(it) },
                                    onToggleGroup = { viewModel.toggleTodoGroup(it) },
                                    // 组名区 → 组编辑页（改名/删除都在那里）。
                                    onOpenGroup = { groupId -> navigate(V2Routes.editor(itemId = groupId)) },
                                    // 组行右侧加号 → 添加页，预选这个组。
                                    onAddInGroup = { groupId ->
                                        navigate(V2Routes.editor(kind = ItemKind.TASK.name, groupId = groupId))
                                    },
                                    onMoveTodo = { id, target -> viewModel.moveTodo(id, target) },
                                    scrollState = monthScroll,
                                    viewportTopRoot = { todoViewport.top },
                                    viewportBottomRoot = { todoViewport.bottom },
                                    // 转场期间（日视图已挂载）月内容不再响应：
                                    // 否则日视图还在放大时点到它下面的格子会中途换一天。
                                    interactive = !dayMounted,
                                    // 展示区（视口）高度：待办空状态按它垂直居中。
                                    viewportHeight = viewportHeight,
                                )
                            }
                        }

                        // ② 日视图内容（仅转场期间）：从被点的格子处完全透明淡入 + 放大；
                        //    退出时缩回格子并同步淡出（不会缩到最小突然消失）。
                        if (dayMounted) {
                            DayViewOverlay(
                                state = state,
                                from = dayEnterFrom,
                                progress = dayEnterAnim,
                                // 正在退场（日视图已不是目标，但挂载还没摘）：
                                // 退场时**不再缩回格子**，原地淡出（用户 2026-09-26）。
                                returning = !dayVisible,
                                viewModel = viewModel,
                                onOpen = { openDetail(it) },
                            )
                        }
                    }
                }
            }
        }
    }


    pendingDelete?.let { d ->
        VConfirmDeleteDialog(
            objectName = d.title,
            onConfirm = {
                d.itemId?.let { viewModel.deleteItem(it) }
                pendingDelete = null
                detail = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ---------------------------------------------------------------- 日视图浮层

/**
 * 日视图内容（展示区里的浮层）。
 *
 * - **只包含展示区域**（时间轴整块），标题栏不属于它；
 * - **进入**：从被点的格子处完全透明淡入 + 放大（[from] 提供格子矩形）；
 * - **退出**：**原地淡出**，不再缩回格子（用户 2026-09-26）——
 *   退场时几何（缩放/位移）冻结在满屏状态，只有透明度归零。
 * 进度只在 graphicsLayer 的 lambda 里读，动画期间零重组。
 */
@Composable
private fun DayViewOverlay(
    state: CalendarV2UiState,
    from: Rect?,
    progress: Animatable<Float, AnimationVector1D>,
    /** 是否处于退场中：是则几何冻结在满屏（只淡出，不缩回）。 */
    returning: Boolean,
    viewModel: CalendarV2ViewModel,
    onOpen: (String) -> Unit,
) {
    var containerRect by remember { mutableStateOf<Rect?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { containerRect = it.boundsInRoot() }
            // 缩放 / 位移 / 透明度：
            //  · **进入**（returning = false）：从格子淡入放大，三者共用进度；
            //  · **退出**（returning = true）：几何冻结在满屏（scale = 1、位移 = 0），
            //    只有 alpha 随进度归零 —— 就是「原地消失」，不再缩回格子（用户 2026-09-26）。
            .graphicsLayer {
                val t = progress.value
                alpha = t
                val c = containerRect
                if (returning) {
                    // 原地淡出：不缩放、不位移。
                    scaleX = 1f
                    scaleY = 1f
                    translationX = 0f
                    translationY = 0f
                } else if (from != null && c != null && c.width > 0f) {
                    // 起始比例取宽/高两个方向的较大者 —— 起点更接近格子真实大小。
                    val s0 = maxOf(from.width / c.width, from.height / c.height)
                        .coerceIn(0.05f, 1f)
                    val s = s0 + (1f - s0) * t
                    scaleX = s
                    scaleY = s
                    translationX = (from.center.x - c.center.x) * (1f - t)
                    translationY = (from.center.y - c.center.y) * (1f - t)
                } else {
                    // 没有起点（冷启动直接恢复进日视图）：轻微放大兜底。
                    val s = 0.94f + 0.06f * t
                    scaleX = s
                    scaleY = s
                }
            }
            .background(VColors.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // 表面（底色）从日期栏下边缘开始；内容仍留 14dp 顶部呼吸位，
                // 位置与改版前一致（用户 2026-09-25）。
                // **底部不留 padding**：滚动视口要一直铺到页面底边，时间轴才能滚到
                // 底栏遮罩**下面**去（否则内容在遮罩上方就被截断，遮罩区变成一整块
                // 什么都没有的实色矩形 —— 用户 2026-09-25 报的就是这个）。
                // 让最后一条能滚出遮罩的余量，放在滚动**内容**里（与月视图同一做法）。
                .padding(start = 12.dp, end = 12.dp, top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DayTimelineHead(
                state = state,
                // 返回：只切视图 —— 缩回动画由页面级状态机统一播放。
                onBackToMonth = { viewModel.setView(CalendarView.Month) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                DayTimelineBody(state = state, onOpen = onOpen)
                // 底栏高度 + 渐隐带 + FAB 让位：最后一条能滚到遮罩上方可见，
                // 且不会被右下角悬浮添加按钮压住（用户 2026-09-27）。
                Spacer(Modifier.height(VTabBottomPadding + VFabClearance))
            }
        }
    }
}

// ---------------------------------------------------------------- 展示区内容

/**
 * 展示区的内容（月网格 / 待办 + 关键日期）。
 *
 * 同一份实现渲染两次：① 真实可交互的一份、② 转场期间的模糊副本
 * （[interactive] = false 时所有回调为空，副本不参与交互）。
 */
@Composable
private fun CalendarDisplayContent(
    state: CalendarV2UiState,
    onSelectDate: (LocalDate, Rect) -> Unit,
    onOpenDetail: (String) -> Unit,
    onToggleTodo: (String) -> Unit,
    /** 待办页分组行：折叠/展开一个组。 */
    onToggleGroup: (String) -> Unit,
    /** 待办页分组行：打开该组的编辑页（改名 / 删除入口）。 */
    onOpenGroup: (String) -> Unit,
    /** 待办页分组行右侧加号：进添加页并预选这个组。 */
    onAddInGroup: (String) -> Unit,
    /** 待办页拖动排序落库（落点已在拖动层解析好）。 */
    onMoveTodo: (String, TodoDropTarget) -> Unit,
    /** 拖动时的边缘自动滚屏要滚的那个滚动条（外层展示区）。 */
    scrollState: ScrollState,
    /** 可视区在 root 里的上下边界（边缘自动滚屏判定用；由页面层提供）。 */
    viewportTopRoot: () -> Float,
    viewportBottomRoot: () -> Float,
    interactive: Boolean,
    /**
     * 展示区（视口）高度 —— 由页面层的 `BoxWithConstraints` 在**组合期**给出。
     * 待办空状态按它垂直居中，所以天然适配各种屏幕高度（不写死像素）。
     */
    viewportHeight: Dp,
) {
    when {
        state.mode == CalendarMode.Todo -> {
            AgendaHead("待办任务", state.todoDoneText)
            val rows = state.todoListRows
            if (rows.isEmpty()) {
                // 空状态**垂直居中**（与白板空状态同一观感；用户 2026-10-01：
                // 「待办的空状态图标偏高了，参考白板部分的」）。
                //
                // 高度 = 视口 − 头部，**在组合期就算出来**，所以首帧即正确、不闪；
                // 同时因为用的是真实视口，天然适配任意屏幕高度（不写死像素）。
                //
                // 三个坑（都踩过，别再走回去）：
                //  1. 白板那两句照抄不了 —— 它的空状态是 LazyColumn 的一项，
                //     `fillParentMaxHeight()` 只在 Lazy 作用域里有「撑满视口」的语义；
                //  2. 这里的外层是 `verticalScroll` 的 Column，给子项的约束是**无限高**：
                //     `fillMaxHeight` / 滚动容器内的 `BoxWithConstraints.maxHeight`
                //     都会拿到 Infinity —— 所以视口高度必须由**滚动容器之外**传进来；
                //  3. 别用 `ScrollState.viewportSize`：那个值要等测量，首帧只能走兜底，
                //     第二帧才跳到位 ⇒ 从别的页切到待办会闪一帧（用户 2026-10-01 实测）。
                val emptyBlockHeight = (viewportHeight - TodoEmptyHeaderBlock).coerceAtLeast(0.dp)
                Box(
                    Modifier.fillMaxWidth().height(emptyBlockHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    VEmptyState(
                        icon = Lucide.ListTodo,
                        title = "还没有待办",
                        description = "点右下角新建，把要做的事记下来。",
                    )
                }
            } else {
                TodoDragList(
                    rows = rows,
                    interactive = interactive,
                    onToggle = { if (interactive) onToggleTodo(it) },
                    onOpen = { if (interactive) onOpenDetail(it) },
                    onToggleGroup = { if (interactive) onToggleGroup(it) },
                    onOpenGroup = { if (interactive) onOpenGroup(it) },
                    onAddInGroup = { if (interactive) onAddInGroup(it) },
                    onDrop = { id, target -> if (interactive) onMoveTodo(id, target) },
                    scrollState = scrollState,
                    viewportTopRoot = viewportTopRoot,
                    viewportBottomRoot = viewportBottomRoot,
                )
            }
        }
        else -> {
            MonthGridV3(
                state = state,
                onSelect = { date, rect -> if (interactive) onSelectDate(date, rect) },
            )
            KeyDatesSection(state = state, onOpen = { if (interactive) onOpenDetail(it) })
        }
    }
}

// ---------------------------------------------------------------- 日期导航

@Composable
private fun DateNavRow(
    state: CalendarV2UiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPickDate: () -> Unit,
    onToday: () -> Unit,
    onToggleHideCourses: () -> Unit,
    /** 当前是否处于日视图 —— 日期栏文字（日号 / 副标题）用它驱动**自己的**过渡动画。 */
    dayMode: Boolean = false,
) {
    // 日期栏文字用**两条**独立时间线（都不跟主导场进度绑定：主导场是白板那种快弹簧，
    // 会把文字过渡挤没）。它们都由 [dayMode] 触发，各自只负责一个视觉角色：
    //
    //  · 日号「n日」—— 角色是「立刻看到日期」。
    //    进入：快起曲线（Expressive），即使某一帧抖动，它也已经亮起来了，
    //    不会被读成「没有动画」。
    //    退出：**必须比主转场（缩回弹簧约 150ms）更快结束**，而且用快起收尾 ——
    //    否则缩回已经播完、月视图已经露面，它还在慢慢淡，看起来「回到月视图之后
    //    才拖一下才消失」（用户 2026-09-25）。快起曲线让它前段就降得差不多，
    //    回收尾时干净收完。
    //  · 副标题 —— 角色是「月版先淡出、日版再淡入」（各占一半进度）。
    //    用户 2026-09-25 要求它也换成弹性弹簧并加快，所以现在走 springy / snappy；
    //    两个 alpha 都有 coerceIn(0,1) 兵守，弹簧过冲不会让字“闪”一下。
    // ⚠️ 初值同样必须按当前视图给（理由与上面的 dayEnterAnim 完全一致）：
    // 写死 0 的话，切回日程页时这两条会各自从 0 播一遍 ——
    // 副标题「共 N 项安排」表现为**先淡出、再淡入**的重放，正是用户报的那个现象。
    val dayNumAnim = remember { Animatable(if (dayMode) 1f else 0f) }
    val daySubtitleAnim = remember { Animatable(if (dayMode) 1f else 0f) }
    LaunchedEffect(dayMode) {
        // 已经到位就不再播：重建（切 Tab 回来）时值本就是对的，重播没有意义。
        if (dayNumAnim.value >= 1f && dayMode) return@LaunchedEffect
        if (dayNumAnim.value <= 0f && !dayMode) return@LaunchedEffect
        launch {
            dayNumAnim.animateTo(
                targetValue = if (dayMode) 1f else 0f,
                animationSpec = tween(
                    // 退出 110ms：短于主转场，确保在缩回过程中就消失干净。
                    durationMillis = if (dayMode) 260 else 110,
                    easing = if (dayMode) VMotion.Expressive else VMotion.Accelerate,
                ),
            )
        }
        // 副标题：退出同样压到主转场之内（弹簧约 100ms）。
        daySubtitleAnim.animateTo(
            targetValue = if (dayMode) 1f else 0f,
            animationSpec = if (dayMode) VMotion.springy() else VMotion.snappy(),
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 左侧标题两行：整块可点，弹出悬浮日历直接跳日期（用户 2026-09-24）。
        Column(
            Modifier
                .weight(1f)
                .vPressable(scaleDown = 0.98f, onClick = onPickDate),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 标题 = 月版（「2026年9月」）+ 日号（「26日」）。
            // 日号在进入日视图时淡入、退出时淡出，而不是直接闪现（用户 2026-09-25）。
            val daySuffix = state.navTitleDay.removePrefix(state.navTitleMonth)
            Row(verticalAlignment = Alignment.CenterVertically) {
                VText(
                    state.navTitleMonth,
                    VTypo.bodyMed.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = VColors.ink,
                    maxLines = 1,
                )
                if (daySuffix.isNotEmpty()) {
                    Box(Modifier.graphicsLayer { alpha = dayNumAnim.value }) {
                        VText(
                            daySuffix,
                            VTypo.bodyMed.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            color = VColors.ink,
                            maxLines = 1,
                        )
                    }
                }
            }
            // 副标题：月版淡出 → 日版淡入（先后各占一半进度，用户 2026-09-25）。
            Box {
                Box(
                    Modifier.graphicsLayer {
                        alpha = (1f - daySubtitleAnim.value * 2f).coerceIn(0f, 1f)
                    },
                ) {
                    VText(
                        state.navSubtitleMonth,
                        VTypo.micro.copy(fontSize = 10.sp),
                        color = VColors.ink3,
                        maxLines = 1,
                    )
                }
                Box(
                    Modifier.graphicsLayer {
                        alpha = ((daySubtitleAnim.value - 0.5f) * 2f).coerceIn(0f, 1f)
                    },
                ) {
                    VText(
                        state.navSubtitleDay,
                        VTypo.micro.copy(fontSize = 10.sp),
                        color = VColors.ink3,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            Modifier
                .background(VColors.accentSoft, RoundedCornerShape(11.dp))
                .vPressable(scaleDown = 0.94f, onClick = onToday)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            VText(
                "今天",
                VTypo.caption12.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = VColors.accent,
            )
        }
        // 「显示/隐藏课程」：月视图与日视图都始终显示（用户 2026-09-25）。
        Box(
            Modifier
                .background(
                    if (state.hideCoursesInMonth) VColors.accentSoft else VColors.surface2,
                    RoundedCornerShape(11.dp),
                )
                .vPressable(scaleDown = 0.94f, onClick = onToggleHideCourses)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            VText(
                if (state.hideCoursesInMonth) "显示课程" else "隐藏课程",
                VTypo.caption12.copy(
                    fontSize = 11.sp,
                    fontWeight = if (state.hideCoursesInMonth) FontWeight.Medium else FontWeight.Normal,
                ),
                color = if (state.hideCoursesInMonth) VColors.accent else VColors.ink2,
            )
        }
        NavArrow(Lucide.ChevronLeft, onPrev)
        NavArrow(Lucide.ChevronRight, onNext)
    }
}

@Composable
private fun NavArrow(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .background(VColors.surface2, CircleShape)
            .vPressable(scaleDown = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = VColors.ink2)
    }
}

// ---------------------------------------------------------------- 周条

@Composable
private fun WeekStripCard(state: CalendarV2UiState, onSelect: (LocalDate) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        state.weekStrip.forEach { day ->
            Column(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.94f) { onSelect(day.date) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                VText(day.weekdayLabel.removePrefix("周"), VTypo.caption, color = VColors.ink3, maxLines = 1)
                Box(
                    Modifier
                        .size(32.dp)
                        .background(if (day.isSelected) VColors.accent else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    VText(
                        day.dayOfMonth.toString(),
                        VTypo.caption12.copy(
                            fontSize = 14.sp,
                            fontWeight = if (day.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = NumFont,
                        ),
                        color = if (day.isSelected) Color.White else VColors.ink,
                    )
                }
                Box(
                    Modifier
                        .size(4.dp)
                        .background(if (day.hasEvents) VColors.accent else VColors.ink3.copy(alpha = 0.5f), CircleShape),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 日视图模式胶囊

@Composable
private fun ModePills(state: CalendarV2UiState, onMode: (CalendarMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val scheduleActive = state.mode == CalendarMode.Schedule
        ModePill(
            count = state.agendaGroups.sumOf { it.rows.size },
            label = "今日日程",
            active = scheduleActive,
            modifier = Modifier.weight(1f),
            onClick = { onMode(CalendarMode.Schedule) },
        )
        ModePill(
            count = state.todoRows.size,
            label = "待办任务",
            active = !scheduleActive,
            modifier = Modifier.weight(1f),
            onClick = { onMode(CalendarMode.Todo) },
        )
    }
}

@Composable
private fun ModePill(
    count: Int,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(46.dp)
            .background(if (active) VColors.accent else VColors.surface, RoundedCornerShape(14.dp))
            .then(if (active) Modifier else Modifier.border(1.dp, VColors.line, RoundedCornerShape(14.dp)))
            .vPressable(scaleDown = 0.97f, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        VText(count.toString(), VTypo.numStat, color = if (active) Color.White else VColors.ink)
        Spacer(Modifier.width(8.dp))
        VText(label, VTypo.bodyMed, color = if (active) Color.White else VColors.ink2)
    }
}

// ---------------------------------------------------------------- 月卡

// ---------------------------------------------------------------- 月视图（V3）

/**
 * 月视图网格（V3 设计稿）：周头 + 6 周格 + 周分隔线（无外层卡片）。
 *
 * 每格最多 6 条事件条（课程 + 日程），超出部分在右上角以「+N」提示；
 * 选中/今天用 accent 圆突出。整格可点：选中该日（下方 Selected Day 条随之更新）。
 */
@Composable
private fun MonthGridV3(
    state: CalendarV2UiState,
    onSelect: (LocalDate, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, label ->
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    VText(
                        label,
                        VTypo.numMini.copy(fontSize = 11.sp),
                        color = if (i >= 5) VColors.ink3 else VColors.ink2,
                    )
                }
            }
        }
        // 周头下面也有一条分割线（用户 2026-09-25）。
        Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.line))
        state.monthWeeks.forEachIndexed { wi, week ->
            if (wi > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.line))
            Row(
                Modifier.fillMaxWidth().height(118.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                week.forEach { day ->
                    MonthCellV3(
                        day = day,
                        onSelect = { rect -> onSelect(day.date, rect) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCellV3(day: CalendarDayCell, onSelect: (Rect) -> Unit, modifier: Modifier = Modifier) {
    // 记下格子在 root 里的矩形：点进日视图时用它做「从格子放大到全屏」的起点。
    var cellBounds by remember { mutableStateOf<Rect?>(null) }
    Column(
        modifier
            .onGloballyPositioned { cellBounds = it.boundsInRoot() }
            .fillMaxHeight()
            .vPressable(scaleDown = 0.94f, onClick = { cellBounds?.let(onSelect) })
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 只保留「今天」的圆底；选中的圆框已按用户要求取消（2026-09-25）。
            Box(
                Modifier.size(14.dp).background(
                    if (day.isToday) VColors.accent else Color.Transparent,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                VText(
                    day.dayOfMonth.toString(),
                    VTypo.numMini.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                    color = when {
                        day.isToday -> Color.White
                        day.inMonth -> VColors.ink
                        else -> VColors.ink3
                    },
                )
            }
            Box(Modifier.weight(1f))
            if (day.moreCount > 0) {
                VText("+${day.moreCount}", VTypo.numMini.copy(fontSize = 8.sp), color = VColors.accent)
            }
        }
        day.events.forEach { MonthEventBar(it) }
    }
}

/** 月格事件条：soft 底 + 对应深色字，8sp 一行放完。 */
@Composable
private fun MonthEventBar(e: MonthCellEvent) {
    val (deep, soft) = EventTone.of(e.colorToken)
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(soft, RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        VText(e.label, VTypo.numMini.copy(fontSize = 8.sp), color = deep, maxLines = 1)
    }
}

/**
 * 选中日信息条（V3 的 Selected Day）：上方细线 + 一行摘要 + 「查看当天」。
 * 点它进入该日的日视图。
 */
@Composable
private fun KeyDatesSection(state: CalendarV2UiState, onOpen: (String) -> Unit) {
    if (state.keyDates.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            VText("关键日期", VTypo.section, color = VColors.ink)
            VText("${state.keyDates.size} 项", VTypo.numMini.copy(fontSize = 11.sp), color = VColors.ink3)
        }
        VCard {
            state.keyDates.forEachIndexed { i, row ->
                if (i > 0) VDivider(14.dp)
                KeyDateRowView(row = row, onClick = { onOpen(row.key) })
            }
        }
    }
}

@Composable
private fun KeyDateRowView(row: KeyDateRow, onClick: () -> Unit) {
    val (deep, soft) = EventTone.of(row.colorToken)
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .vPressable(scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(46.dp)
                .height(30.dp)
                .background(soft, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            VText(
                row.badge,
                VTypo.numMini.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = deep,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(
                row.title,
                VTypo.caption12.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = VColors.ink,
                maxLines = 1,
            )
            VText(row.meta, VTypo.numMini.copy(fontSize = 10.sp), color = VColors.ink3, maxLines = 1)
        }
        Box(
            Modifier.background(soft, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            VText(row.tag, VTypo.numMini.copy(fontSize = 10.sp), color = deep)
        }
        Icon(Lucide.ChevronRight, null, Modifier.size(13.dp), tint = VColors.ink3)
    }
}

// ---------------------------------------------------------------- 议程

@Composable
private fun AgendaHead(title: String, count: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        VText(title, VTypo.section, color = VColors.ink)
        VText(count, VTypo.caption12, color = VColors.ink3)
    }
}

@Composable
private fun AgendaGroupHead(label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VText(label, VTypo.caption, color = VColors.ink3)
        Box(Modifier.weight(1f).height(1.dp).background(VColors.line))
    }
}

/**
 * 事件的「色条实色」+「色块底色」。
 *
 * 设计稿（日程 · 日视图 BBxB7）里事件块是 `fill=$amber-soft` 配色条 `fill=$amber` ——
 * 即**同色系的 soft 版与实色版**，不是同一个颜色。
 *
 * 两者必须由**同一个判定**派生：曾经拆成两处（底色那边另走了一套 token 归一，
 * 把不认识的颜色全塞成 accent），于是出现"底色是绿的、色条是琥珀的"。
 * 现在只有这一个入口，错不开。
 *
 * 自定义 `#hex` 没有预设 soft 版，用同色 14% 透明度铺白底近似（同样是"同色系更浅"）。
 */
object EventTone {
    private val TONES: List<Pair<Color, Color>> = listOf(
        VColors.accent to VColors.accentSoft,
        VColors.amber to VColors.amberSoft,
        VColors.lilac to VColors.lilacSoft,
        VColors.rose to VColors.roseSoft,
    )

    /**
     * 设计稿色板里「实色 → soft」的对应表。
     *
     * 课程/日程把颜色存成 `#hex`（如 `#9C6516`），这些值就是设计稿的实色。
     * 曾经对 hex 一律用「同色 14% 透明」当底色 —— 铺在页面上比设计稿的 soft 明显更暗更脏
     * （实测 amber 得到 (232,225,213)，设计稿 amber-soft 是 (247,235,215)）。
     */
    private val HEX_SOFT: Map<String, Color> = mapOf(
        "#1C6B58" to VColors.accentSoft, // accent
        "#2E8C72" to VColors.accentSoft, // accent-2（同为绿系，共用 soft）
        "#9C6516" to VColors.amberSoft,  // amber
        "#BC5F63" to VColors.roseSoft,   // rose
        "#6A61BE" to VColors.lilacSoft,  // lilac
        "#A5484E" to VColors.roseSoft,   // rose-deep
    )

    /** 返回 (色条实色, 色块底色)。 */
    fun of(token: String?): Pair<Color, Color> {
        customColor(token)?.let { hex ->
            val soft = HEX_SOFT[token!!.uppercase()] ?: pastelize(hex)
            return hex to soft
        }
        return when (token) {
            null, "accent" -> VColors.accent to VColors.accentSoft
            "amber" -> VColors.amber to VColors.amberSoft
            "lilac" -> VColors.lilac to VColors.lilacSoft
            "rose" -> VColors.rose to VColors.roseSoft
            "grey", "white" -> VColors.ink3 to VColors.surface2
            else -> TONES[(token.hashCode() and 0x7FFFFFFF) % TONES.size]
        }
    }

    private fun customColor(token: String?): Color? =
        token?.takeIf { it.startsWith("#") }
            ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

    /** 不在设计稿色板里的自定义色：往白里调 85%，得到与设计稿 soft 相近的粉彩明度。 */
    private fun pastelize(c: Color, white: Float = 0.85f): Color = Color(
        red = c.red + (1f - c.red) * white,
        green = c.green + (1f - c.green) * white,
        blue = c.blue + (1f - c.blue) * white,
    )
}

/** 事件色条颜色（实色版）= [EventTone.of] 的第一个分量。 */
fun vColorOf(token: String?): Color = EventTone.of(token).first

@Composable
private fun AgendaRowCard(
    row: CalendarAgendaRowUi,
    showEnd: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (showEnd) 58.dp else 56.dp)
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(14.dp))
            .vPressable(scaleDown = 0.985f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showEnd) {
            Column(Modifier.width(44.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                VText(row.startText, VTypo.numTime, color = VColors.ink, maxLines = 1)
                row.endText?.let {
                    VText(it, VTypo.numMini.copy(fontWeight = FontWeight.Normal), color = VColors.ink3, maxLines = 1)
                }
            }
        } else {
            VText(row.startText, VTypo.numTime, color = VColors.ink2, maxLines = 1)
        }
        Box(
            Modifier
                .width(3.dp)
                .height(if (showEnd) 28.dp else 26.dp)
                .background(vColorOf(row.colorTag), RoundedCornerShape(2.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(row.title, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
            row.metaText?.let { VText(it, VTypo.caption, color = VColors.ink2, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------- 待办拖动排序

/**
 * 拖动几何：**静态槽位**，在布局阶段由 [TodoDragList] 的自定义 Layout 一次算好。
 *
 * 普通对象（不是 Compose 状态）→ 不触发重组。两条来自白板 0.1.7 的铁律：
 *  1. 布局/测量阶段绝不写 Compose 状态；
 *  2. **槽位必须由布局算出来**，不能靠 onGloballyPositioned 逐个上报 ——
 *     子节点先定位、父容器原点还没算出来，上报出来的坐标会整体错一截
 *     （用户 2026-09-26：拖动无法真正换位 + 抽动，根因就是这个）。
 */
private class TodoDragGeometry {
    var slots: List<TodoDragSlot> = emptyList()

    /** 容器在 root 里的 y（边缘自动滚屏要把容器坐标换回 root）。 */
    var originY: Float = 0f

    fun topOf(id: String): Float = slots.firstOrNull { it.id == id }?.top ?: 0f

    /** 槽位顶边；找不到（行刚被移除）返回 null —— 调用方据此退回「无位移」。 */
    fun topOrNull(id: String): Float? = slots.firstOrNull { it.id == id }?.top
}

/**
 * 拖动状态。
 *
 * 只有 [draggingId] 进组合期（它决定抬起视觉：缩放/投影/最上层）；
 * 其余每帧变化的值**只在帧循环里读写**，绝不进组合期。
 */
private class TodoDragState {
    var draggingId by mutableStateOf<String?>(null)
    var pointerY: Float = 0f
    var grabDy: Float = 0f
    var target: TodoDropResolution? = null
    var anyMoving: Boolean = false
}

/**
 * 展示区（拖动时的可视区）在 root 里的上下边界。
 *
 * **普通对象**：只在布局时写、只在帧循环里读，进组合期只会白白重组。
 */
private class TodoViewport {
    var top: Float = 0f
    var bottom: Float = 0f
}

/** 帧循环的收敛时间常数（与白板同值：快而不抖、可中断）。 */
private const val TODO_DRAG_TAU_SECONDS = 0.09f

/**
 * 松手后「落位」的时间常数：位移按指数衰减回 0。
 *
 * 与拖动收敛同值（0.09s）—— 用户 2026-10-01 反馈 0.16s 太慢。
 * 再快就会接近硬切（任何元素都不得突变），所以不再往下调。
 */
private const val TODO_SETTLE_TAU_SECONDS = 0.09f

/**
 * 拖动中「让位」的缓动时间常数。
 *
 * 让位不是把公式结果硬贴到屏幕上，而是**缓动逼近**目标值：
 *  · 指针采样率与帧率不一致时不会顿；
 *  · 目标值跨过边界时会平滑过渡，而不是瞬移。
 * 取小值（0.045s）—— 要的是"顺"，不是"慢"。
 */
private const val TODO_SQUEEZE_TAU_SECONDS = 0.045f

// ---------------------------------------------------------------- 展开 / 收起

/**
 * 折叠展开的动画口径（用户 2026-10-01 定稿）：
 *
 *  - **展开**：下方其他项被**挤开**；子项**从上到下依次出现**；
 *  - **收起**：子项**从下到上依次消失**；下方其他项向上收拢补位；
 *  - 子项是**原地淡入/展开**，不从别处滑进来（不要纵向位移）；
 *  - 全程**非线性**，且**任何元素都不得有位置/状态突变**。
 *
 * ## 实现口径（关键）
 *
 * 唯一随时间变化的量是**每个子项的「出现高度」**（0 → 真实高度）。
 * 下方行的位置**由布局从这些高度累加推导**（Layout 里的 `y += h`），
 * 不是各行自己跑位移 —— 所以：
 *  · 空间撑开/收拢是连续的（不可能突变）；
 *  · 行之间不会各自错位（没有独立位移动画可跑偏）。
 *
 * ## 为什么用 VMotion 而不是自造常量
 *
 * 项目硬规则（README「视觉系统」第 1 条 + VMotion 文件头）：
 * **不使用 LinearEasing；位移与淡入走贝塞尔，连续状态走阻尼弹簧。**
 * 展开用 [VMotion.Expressive]（快起慢收），收起用 [VMotion.Accelerate]（缓起快收）。
 */
private const val TODO_REVEAL_DURATION_MS = VMotion.RevealMillis
private const val TODO_REVEAL_EXIT_MS = VMotion.ExitMillis

/**
 * 撑开 / 收拢空间的时长（毫秒）。
 *
 * 与「内容淡入淡出」分开：空间先动、内容随后 —— 这样内容出现时位置已经让好，
 * 不会被正在移动的相邻行压到（那是"被压扁"观感的来源）。
 */
private const val TODO_SPACE_MS = 220

/** 内容原地淡入的时长。 */
private const val TODO_FADE_IN_MS = 180

/** 内容原地淡出的时长（比淡入略快：收起干脆一些）。 */
private const val TODO_FADE_OUT_MS = 140

/**
 * 「依次出现 / 依次消失」的错开步长与上限（毫秒）。
 *
 * 步长管「依次感」：太小就看不出先后，太大就显得拖沓。
 * 上限管「子项很多时别等太久」：错开总量封顶，超出的行与最后一行同时开始。
 */
private const val TodoRevealStaggerMs = 45
private const val TodoRevealMaxDelayMs = 220

/**
 * **临时调试开关**：把展开/收起放慢 N 倍，便于逐帧抓图核对中间过程。
 * 平时必须是 1。验证完立刻改回 1（不要提交非 1 的值）。
 */
private const val TODO_REVEAL_DEBUG_SLOWDOWN = 10

/** 边缘自动滚屏的触发区高度与最大速度（px/s，按 420dpi 的经验值近似）。 */
private val TodoDragEdgeZone = 96.dp
private const val TODO_DRAG_MAX_SPEED = 1400f

/**
 * 展开 / 收起的**组合期状态**（普通对象，不是 Compose 状态）。
 *
 * 为什么不用 Compose 状态：进入 / 离开的判定必须与「渲染哪一帧」在**同一次组合**内完成。
 * 用状态 + LaunchedEffect 会晚一帧 —— 直接造成「展开没动画」「收起跳一下」两个现象。
 */
private class TodoRevealState {
    /** 上一轮的行（用来判定谁新来、谁离开）。 */
    var prevRows: List<TodoListRow> = emptyList()
    /** 正在消失、还没收完的行。 */
    var dying: List<TodoDyingRow> = emptyList()
    /** 是否已完成首次组合（首次整列视为已就位，不播入场动画）。 */
    var initialized: Boolean = false
}

/**
 * 一个「正在消失」的行（折叠时被移除的那些）。
 *
 * 为什么要单独记：`rows` 是**折叠后的结果** —— 子行一被折叠就从里面没了、
 * composable 也随之卸载，没有东西能替它播「收起」动画。
 *
 * [anchorId] 是它**后面最近的那个仍在场的行**：渲染时插到那个行之前 ⇒ 回到原位。
 * 若不锚定（早先一律追加到列表末尾），子项原处的空间没人收，收起后留下大片空白。
 */
private data class TodoDyingRow(
    val row: TodoListRow,
    val anchorId: String?,
)

/** 一行的稳定 id：组用 groupId，待办用 itemId。 */
private fun todoRowKey(row: TodoListRow): String = when (row) {
    is TodoListRow.Group -> row.groupId
    is TodoListRow.Todo -> row.row.itemId
}

/**
 * 待办列表的拖动排序区。
 *
 * 架构照搬白板（已被验证）：行**保持原始顺序布局**，让位/落位只改 `graphicsLayer`
 * 的位移 → 不触发重排；一个长生命周期协程 + 每帧读目标做指数收敛；
 * 落点用静态槽位 + 滞回；松手不写单独的落位动画（同一循环继续收敛）。
 */
@Composable
private fun TodoDragList(
    rows: List<TodoListRow>,
    interactive: Boolean,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onAddInGroup: (String) -> Unit,
    onDrop: (String, TodoDropTarget) -> Unit,
    scrollState: ScrollState,
    /** 可视区在 root 里的上下边界（边缘自动滚屏判定用）。 */
    viewportTopRoot: () -> Float,
    viewportBottomRoot: () -> Float,
) {
    val drag = remember { TodoDragState() }
    val geometry = remember { TodoDragGeometry() }
    val density = LocalDensity.current
    /** 行高（px）。组行与待办行都是 50dp —— 下面的连续让位公式依赖「等高」。 */
    val rowH = with(density) { TodoRowHeight.toPx() }
    /**
     * 每行**期望停留的绝对 y**（容器坐标）。null = 没有期望，直接用布局槽位。
     *
     * ⚠️ 为什么存「绝对 y」而不是「相对槽位的差值」（这是消除**坐标突变**的关键）：
     * 落库是**异步**的 —— 数据一回来，行的布局基准就跳到了新位置。
     * 若存的是差值，差值仍是旧值，与新的基准叠加 ⇒ 视觉上坐标突变。
     * 存绝对期望位置后，绘制时用 `期望 − 当前槽位` 现算位移：
     * 基准一变，位移自动跟着变，**突变在结构上不可能发生**。
     */
    val desiredTops = remember { mutableStateMapOf<String, MutableFloatState>() }
    /** 目标期望位置（普通对象）：由指针算出；帧循环把它缓动到 [desiredTops]。 */
    val targetTops = remember { HashMap<String, Float>() }
    /**
     * 松手后是否在**等落库后的新顺序到达**。
     *
     * ⚠️ 为什么必须等：落库前，行的槽位仍是**旧位置**。此刻若开始落位缓动，
     * 会先往旧位置走、数据到了再往新位置追 —— 看起来就是「调换再换回来」。
     * 等新顺序到达后再动，槽位已经是最终位置，一次到位。
     */
    var settleAwaitingData by remember { mutableStateOf(false) }
    var loopOn by remember { mutableStateOf(false) }
    /** 松手后的「落位」动画是否在跑：把各行位移指数衰减回 0（交还给布局槽位）。 */
    var settleOn by remember { mutableStateOf(false) }
    val edgePx = with(density) { TodoDragEdgeZone.toPx() }
    val maxSpeedPx = with(density) { TODO_DRAG_MAX_SPEED / 2.5f }
    // 拖动中隐藏块分割线。只改透明度，不动布局。
    var landed by remember { mutableStateOf(true) }
    val dividersVisible = landed

    // ---------------------------------------------------------------- 展开 / 收起
    //
    // 目标观感（用户 2026-10-01 定稿）：
    //   · 展开：下方其他项被挤开；子项从上到下依次出现；
    //   · 收起：子项从下到上依次消失；下方其他项向上收拢补位；
    //   · 子项**原地**淡入/展开（不从别处滑入）；
    //   · 全程非线性；任何元素都不得有位置/状态突变。
    //
    // ## 做法：高度参与布局，位置由布局推导
    //
    // 唯一随时间变化的量是每一行的**高度因子**(0..1)，由**行自己**用 Animatable 驱动，
    // 作用在 `Modifier.height(行高 × 因子)` 上。于是：
    //   · Layout 读到的 placeables[i].height 就是当前真实高度；
    //   · 下方行位置由 y += h 推导 ⇒ 空间撑开/收拢是连续变化的**结果**，不可能突变；
    //   · 不存在「槽位记账高度」与「实际画出来的高度」两份值
    //     （旧写法正是两份值对不上 ⇒ 内容溢出自己的框 ⇒ 文字重叠）。
    //
    // ## 消失的行：锚定插回原位（不是追加到末尾）
    //
    // rows 是折叠后的结果 —— 子行一被折叠就没了、composable 也卸载了，
    // 所以需要一份「正在消失」的名单，让它们继续渲染、把高度收到 0 再移除。
    // ⚠️ 必须插回**原来的位置**：早先一律追加到列表末尾，导致子项原处的空间没人收，
    // 收起后留下一大片空白（用户实测）。
    // 锚点取「它后面最近的那个仍在场的行」，插到那个行之前 —— 折叠掉的是一段连续块，
    // 于是整段会原样插回组头之后。
    // ⚠️⚠️ 进入 / 离开的判定必须在**组合期同步**算出来，绝不能放进 LaunchedEffect。
    //
    // 放 effect 里会晚一整帧，正好踩两个坑（都实测到了）：
    //  1. **展开没有动画**：新行的首次组合就发生在这一帧，那时 entering 还没算出来（仍是 false）
    //     ⇒ 它直接从满高度起步，等于没有入场动画；
    //  2. **收起会跳一下**：这一帧 rows 里已经没有子行了，而「正在消失」名单还没登记
    //     ⇒ 先按「已经收好」的布局渲染一帧，下一帧才把消失的行插回来播动画。
    //
    // 所以这里用一个**普通对象**（不是 Compose 状态）记住上一轮的行：组合期就地比对、就地更新。
    // 写它不触发重组，也就不存在「组合期写状态 → 再重组」的回环。
    val revealState = remember { TodoRevealState() }
    /** 某一行收完时 +1：唯一需要触发重组的时刻（把那行从「正在消失」名单里摘掉）。 */
    var dyingVersion by remember { mutableIntStateOf(0) }

    val currentIds = rows.mapTo(HashSet(), ::todoRowKey)
    val prevIds = revealState.prevRows.mapTo(HashSet(), ::todoRowKey)
    // 首次组合：整列视为已就位（那是「进页面」，不播入场动画）。
    val enteringNow: Set<String> =
        if (!revealState.initialized) emptySet() else currentIds - prevIds
    if (revealState.initialized) {
        // 刚离开的行：记下锚点（它后面最近的仍在场的行），稍后原样插回。
        val prev = revealState.prevRows
        val gone = prev.withIndex()
            .filter { todoRowKey(it.value) !in currentIds }
            .map { (idx, r) ->
                val anchor = prev.drop(idx + 1).firstOrNull { todoRowKey(it) in currentIds }
                TodoDyingRow(r, anchor?.let(::todoRowKey))
            }
        if (gone.isNotEmpty()) {
            revealState.dying = (revealState.dying + gone).distinctBy { todoRowKey(it.row) }
        }
    }
    revealState.prevRows = rows
    revealState.initialized = true
    // 读一次版本号：收完一行时它变化 ⇒ 触发重组 ⇒ 该行从渲染列表里消失。
    dyingVersion

    val dyingIds = revealState.dying.mapTo(HashSet()) { todoRowKey(it.row) }
    // 参与渲染的行 = 当前行 + 正在消失的行（按锚点插回原位）。
    val renderRows: List<TodoListRow> = if (revealState.dying.isEmpty()) rows else {
        val byAnchor = revealState.dying.groupBy { it.anchorId }
        buildList(rows.size + revealState.dying.size) {
            rows.forEach { r ->
                byAnchor[todoRowKey(r)]?.forEach { add(it.row) }
                add(r)
            }
            byAnchor[null]?.forEach { add(it.row) }
        }
    }

    // 错开的顺序**只看这一组内**的先后，不能用整个列表的下标：
    // 用全局下标时，靠前的组会算出很大的延迟（实测：收起要等两秒才开始动），
    // 而靠后的组又几乎没有错开 —— 「依次」的效果完全不稳定。
    /** 正在做出现/消失动画的行：它们要**钉在固有位置**并**画在最上层**（见下面的 Layout）。 */
    val transitioningIds: Set<String> = dyingIds + enteringNow
    /**
     * 每一行在「撑开状态」下的真实高度（实测得来，含顶层行上方那条分割线）。
     * 普通对象：只在布局期读写，不进组合期依赖。
     */
    val fullHeights = remember { HashMap<String, Int>() }
    val renderIds = renderRows.map(::todoRowKey)
    val dyingOrder = renderIds.filter { it in dyingIds }
    val enteringOrder = renderIds.filter { it in enteringNow }

    /**
     * 拖动让位：把「被拖行相对自身槽位的位移」映射成**每一行**的绘制偏移。
     *
     * 纯连续函数 —— 这是本次重写的第二个核心修正：
     *   · 拖动期间**顺序一个字都不改**（不重排、不重组、不重布局）；
     *   · 被拖行跟指针；其余行的偏移由公式直接算出，只写 graphicsLayer；
     *   · 没有「落点」这个离散中间量 ⇒ 挤压是**连续**的，不是跨过边界才跳一下。
     *
     * 公式（所有行等高 h，共 n 行，被拖行下标 d，dragDy = 相对自身槽位的位移）：
     *   p = clamp(d + dragDy/h, 0, n-1)              连续插入位置
     *   对 i ≠ d：
     *     rest   = i<d ? i : i-1                     「去掉被拖行」后的下标
     *     target = rest*h + h*clamp(rest-p+1, 0, 1)  rest>=p 时整行下移一格（跨界处连续过渡）
     *     offset = target - i*h
     *
     * ⚠️ 只写**位移量**（不是绝对 y）：行由 Layout 摆在自己的槽位上，
     * 位移是叠加的临时偏离，静止时恒为 0 ⇒ 位置只有一个真值源。
     */
    /**
     * 算「每一行期望停在哪个绝对 y」（只写目标，不碰显示值）。
     *
     * ⚠️ 用 **smoothstep** 而不是线性 clamp：
     * 线性 clamp 在两端有**折角**（位置连续、速度不连续），行在「开始让位 / 让位结束」
     * 的瞬间加速度会跳变 —— 手感就是硬、木（用户 2026-10-01：「挤压好僵硬」）。
     * smoothstep（3t²−2t³）两端导数为零，起停都是渐入渐出。
     */
    fun computeDragTargets() {
        targetTops.clear()
        val n = renderRows.size
        val draggedId = drag.draggingId ?: return
        val d = renderRows.indexOfFirst { todoRowKey(it) == draggedId }
        if (d < 0) return
        // 用**真实槽位 top**（不是 d*rowH）：顶层行上方还有一条分割线，
        // 用下标乘行高会随行数累积误差，表现为「拖起来的位置和手指差一点」。
        val homeTop = geometry.topOrNull(draggedId) ?: (d * rowH)
        val dragDy = drag.pointerY - drag.grabDy - homeTop
        val p = (d + dragDy / rowH).coerceIn(0f, (n - 1).toFloat())
        renderRows.forEachIndexed { i, r ->
            val id = todoRowKey(r)
            val slotTop = geometry.topOrNull(id) ?: (i * rowH)
            val off = if (i == d) dragDy else {
                val rest = if (i < d) i else i - 1
                val t = (rest - p + 1f).coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)          // smoothstep
                rest * rowH + rowH * eased - i * rowH
            }
            targetTops[id] = slotTop + off
        }
    }

    /** 把目标期望位置**直接**贴上去（只在起手那一帧用：要立刻跟手，不能有滞后）。 */
    fun snapTopsToTargets() {
        targetTops.forEach { (id, want) ->
            val st = desiredTops.getOrPut(id) { mutableFloatStateOf(want) }
            if (st.floatValue != want) st.floatValue = want
        }
    }

    // 落库后的新顺序到达 ⇒ 解除等待，开始落位。
    // keyed on rows：等价时不重启（例如落点没有产生实际改动），那种情况由下面的兜底计时器解除。
    LaunchedEffect(rows) {
        if (settleAwaitingData) settleAwaitingData = false
    }
    // 兜底：落点没有产生任何数据改动时 rows 不会变，别让位置永远冻着。
    LaunchedEffect(settleAwaitingData) {
        if (settleAwaitingData) {
            delay(220)
            settleAwaitingData = false
        }
    }

    LaunchedEffect(loopOn) {
        if (!loopOn) return@LaunchedEffect
        var lastFrame = 0L
        while (isActive) {
            val scrollDelta = withFrameNanos { now ->
                val dtMs = if (lastFrame == 0L) 16f else (now - lastFrame) / 1_000_000f
                lastFrame = now
                val dt = dtMs.coerceIn(0f, 64f) / 1000f
                // 拖动中：解析落点（静态槽位 + 滞回）。
                // 落点只用于**松手后写库**，不参与每行的视觉位置（视觉位置见 applyDragOffsets）。
                val draggedId = drag.draggingId
                if (draggedId != null) {
                    val slots = geometry.slots
                    val slot = slots.firstOrNull { it.id == draggedId }
                    if (slot != null) {
                        drag.target = resolveTodoDrop(
                            slots = slots,
                            pointerY = drag.pointerY,
                            draggedId = draggedId,
                            draggedIsGroup = slot.isGroup,
                            draggedSubtreeIds = slot.subtreeIds,
                            previous = drag.target,
                        )
                    }
                }
                // 拖动中：每帧重算目标位移，并**缓动逼近**（不在指针事件里硬贴）。
                // 被拖行例外：它必须**跟手**（缓动会有滞后感）。
                if (draggedId != null) {
                    computeDragTargets()
                    val k = (1f - exp(-dt / TODO_SQUEEZE_TAU_SECONDS)).coerceIn(0f, 1f)
                    targetTops.forEach { (id, want) ->
                        val st = desiredTops.getOrPut(id) { mutableFloatStateOf(want) }
                        if (id == draggedId) {
                            // 被拖行**跟手**：直接写，不缓动（缓动会有滞后感）
                            if (st.floatValue != want) st.floatValue = want
                        } else {
                            val cur = st.floatValue
                            val diff = want - cur
                            if (abs(diff) < 0.5f) {
                                if (cur != want) st.floatValue = want
                            } else {
                                st.floatValue = cur + diff * k
                            }
                        }
                    }
                }

                // 落位：松手后把各行位移**指数衰减回 0**（行交还给布局槽位）。
                // 不直接 offsets.clear() —— 那是硬切（用户明确不要突变）。
                var stillMoving = false
                if (draggedId == null && settleOn) {
                    if (settleAwaitingData) {
                        // 位置**冻结**：等落库后的新顺序到达。
                        // 此刻槽位还是旧位置，现在动就会「先往回、再往前」。
                        // 保持 stillMoving 让帧循环继续跑，别让它挂起。
                        stillMoving = true
                    } else {
                        // 落位：把「期望位置」缓动逼近**实时槽位**。
                        // 走到这里时新顺序已经到位（或这次落点没有产生任何改动），
                        // 所以槽位就是最终位置 —— 一次到位，不会来回。
                        val k = (1f - exp(-dt / TODO_SETTLE_TAU_SECONDS)).coerceIn(0f, 1f)
                        val done = ArrayList<String>(desiredTops.size)
                        desiredTops.forEach { (id, st) ->
                            val slot = geometry.topOrNull(id)
                            if (slot == null) { done.add(id); return@forEach }
                            val cur = st.floatValue
                            val diff = slot - cur
                            if (abs(diff) < 0.5f) {
                                done.add(id)
                            } else {
                                st.floatValue = cur + diff * k
                                stillMoving = true
                            }
                        }
                        done.forEach { desiredTops.remove(it) }
                    }
                    if (!settleAwaitingData && !stillMoving) {
                        settleOn = false
                        // ⚠️ 分割线**只在落位静止这一刻**才回来（用户 2026-09-26 定的时机）。
                        // 提前放出来会让线跟着还在移动的行一起走。
                        landed = true
                    }
                }
                // 边缘自动滚屏 —— 仅拖动中。
                var delta = 0f
                if (draggedId != null) {
                    val top = viewportTopRoot()
                    val bottom = viewportBottomRoot()
                    if (bottom > top) {
                        val y = drag.pointerY + geometry.originY
                        delta = when {
                            y < top + edgePx -> -maxSpeedPx * ((top + edgePx - y) / edgePx).coerceIn(0f, 1f) * dt
                            y > bottom - edgePx -> maxSpeedPx * ((y - (bottom - edgePx)) / edgePx).coerceIn(0f, 1f) * dt
                            else -> 0f
                        }
                    }
                }
                delta
            }
            if (scrollDelta != 0f) {
                scrollState.scrollBy(scrollDelta)
                computeDragTargets()   // 滚动改变了槽位 ⇒ 重算（下一帧缓动逼近）
            }
            // 松手后挂起。拖动期间不需要收敛（让位是纯函数直接算的）；
            // 只有松手后的「落位」需要把位移衰减回 0。
            if (drag.draggingId == null && !settleOn) loopOn = false
        }
    }

    // 手势挂在**容器**上（不挂在每一行上）：行被 graphicsLayer 平移后，行自己的局部坐标系
    // 会跟着动，行内手势读到的 position 会被钉在按下那一刻 —— 反锁不住手指、落点失真。
    // 容器的坐标空间是稳定的，而且**就是槽位用的那个空间**。
    val currentDrop by rememberUpdatedState(onDrop)
    val currentInteractive by rememberUpdatedState(interactive)
    // 待办「浮起」时的触觉反馈（问题 #12）。与白板卡片用同一套反馈。
    val haptic = LocalHapticFeedback.current
    val startDrag: (String, Float, Float) -> Unit = { id, pointerY, grabDy ->
        if (currentInteractive) {
            drag.draggingId = id
            drag.pointerY = pointerY
            drag.grabDy = grabDy
            drag.target = null
            landed = false
            computeDragTargets()
            snapTopsToTargets()   // 起手立刻跟手（这一帧不缓动）
            loopOn = true
        }
    }
    // 只更新指针位置：偏移由**帧循环**每帧重算并缓动（指针事件里硬贴会顿）。
    val moveDrag: (Float) -> Unit = { pointerY ->
        drag.pointerY = pointerY
        if (!loopOn) loopOn = true
    }
    val endDrag: () -> Unit = {
        val id = drag.draggingId
        // ⚠️ **松手时现场重新解析一次落点**，不只看帧循环里存下的 `drag.target`。
        // 只依赖存值时，一旦循环没跑到/被跳过（存值仍是 null），
        // 松手就什么都不写 —— 表现正是「能拖能挤，但完全无法真正换位」。
        // 现场解析用 previous = null（不要滞回）：松手要的是**精确**落点，不是拖动中的稳定落点。
        val liveTarget = if (id == null) null else {
            val slot = geometry.slots.firstOrNull { it.id == id }
            if (slot == null) null else resolveTodoDrop(
                slots = geometry.slots,
                pointerY = drag.pointerY,
                draggedId = id,
                draggedIsGroup = slot.isGroup,
                draggedSubtreeIds = slot.subtreeIds,
                previous = null,
            )?.target
        }
        val target = liveTarget ?: drag.target?.target
        if (id != null && target != null) {
            // 只提交；**位置冻结**等新顺序到达（见 settleAwaitingData 的说明）。
            settleAwaitingData = true
            currentDrop(id, target)
        }
        drag.draggingId = null
        drag.target = null
        // ⚠️ 这里**不要**置 landed = true：分割线必须等落位动画停稳再回来（见上面的衰减分支）。
        // 落位：位移衰减回 0（动画），而不是清空（硬切）。
        settleOn = desiredTops.isNotEmpty()
        if (!settleOn) landed = true   // 没有位移可收敛（没真正挪动）⇒ 立刻恢复分割线
        loopOn = true
    }

    Layout(
        content = {
            renderRows.forEachIndexed { index, row ->
                val id = todoRowKey(row)
                val dyingRow = id in dyingIds
                // 错开：展开正序（从上到下依次出现）；收起倒序（从下到上依次消失）。
                // 下标只取**本组内**的顺序。
                val staggerIndex = when {
                    dyingRow -> (dyingOrder.size - 1 - dyingOrder.indexOf(id)).coerceAtLeast(0)
                    id in enteringNow -> enteringOrder.indexOf(id).coerceAtLeast(0)
                    else -> 0
                }
                // ⚠️ **必须用 key 绑定行的身份**：不用 key 时 Compose 按位置匹配子项，
                // 列表内容一变（收起 / 展开），各行的 Animatable 状态就会串到相邻槽位上 ——
                // 表现就是「待办集外部的行也被带着缩小/淡出」以及状态错配的闪帧。
                key(id) {
                    TodoDragRow(
                    row = row,
                    // 块分割线：只在**顶层行**之间画（组内的待办与子组不打线）。
                    showDivider = index > 0 && row.depth == 0,
                    dividersVisible = dividersVisible,
                    drag = drag,
                    desiredTops = desiredTops,
                    geometry = geometry,
                    dying = dyingRow,
                    entering = id in enteringNow,
                    revealDelayMs = (staggerIndex * TodoRevealStaggerMs).coerceAtMost(TodoRevealMaxDelayMs),
                    onDyingFinished = {
                        revealState.dying = revealState.dying.filterNot { todoRowKey(it.row) == id }
                        dyingVersion++
                    },
                    onToggle = onToggle,
                    onOpen = onOpen,
                    onToggleGroup = onToggleGroup,
                    onOpenGroup = onOpenGroup,
                    onAddInGroup = onAddInGroup,
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { geometry.originY = it.positionInRoot().y }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 按下的点落在哪一行的槽位里
                        val slot = geometry.slots.firstOrNull {
                            down.position.y >= it.top && down.position.y < it.top + it.height
                        }
                        val held = awaitLongPressOrCancellation(down.id)
                        if (held == null || slot == null || !currentInteractive) continue
                        // 长按成立：先给触觉反馈再抓起这一行（问题 #12）。
                        // ⚠️ 时机是「**进入长按状态**」这一下，不是松手时（用户 2026-09-30 澄清）。
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        // grabDy = 按点在本行内的偏移，所以起手时位移为 0（不跳）
                        startDrag(slot.id, down.position.y, down.position.y - slot.top)
                        var active = true
                        while (active) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) {
                                active = false
                            } else {
                                ch.consume()
                                moveDrag(ch.position.y)
                            }
                        }
                        endDrag()
                    }
                }
            },
    ) { measurables, constraints ->
        // 每行的高度由**行自己**按高度因子给出（见 TodoDragRow 的 height(...)），
        // 这里直接读测量结果 —— 记账值与实际高度同源，不可能对不上。
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val slots = ArrayList<TodoDragSlot>(placeables.size)
        val contentTops = FloatArray(placeables.size)
        val isTransitioning = BooleanArray(placeables.size)
        // 两个纵坐标口径（这是消除「挤压感」的关键）：
        //   · flowY  —— **动画高度**累加 ⇒ 决定后续行的位置（挤开 / 收拢空间）；
        //   · finalY —— **满高**累加   ⇒ 过渡中的行钉在这个位置（固有位置，不随动画移动）。
        //
        // 若只用 flowY 摆放：过渡中的行会随自己的槽位高度一起移动（纵向移动），
        // 而它又画在正在移动的行的下层 ⇒ 被从下往上盖住，看起来就是「被液压机压扁」。
        var flowY = 0
        var finalY = 0
        renderRows.forEachIndexed { i, row ->
            val id = todoRowKey(row)
            val h = placeables[i].height
            val transitioning = id in transitioningIds
            // 满高：优先用静止态实测值（含分割线）；新出现的行（组内子项，无分割线）回退标准行高。
            val full = fullHeights[id] ?: rowH.toInt()
            if (!transitioning) fullHeights[id] = h
            contentTops[i] = (if (transitioning) finalY else flowY).toFloat()
            isTransitioning[i] = transitioning
            slots += TodoDragSlot(
                id = id,
                top = flowY.toFloat(),
                height = h.toFloat(),
                depth = row.depth,
                isGroup = row is TodoListRow.Group,
                collapsed = (row as? TodoListRow.Group)?.collapsed ?: false,
                // 后代集合由 VM 从完整数据算好（折叠在组里的后代不在可见行里）。
                subtreeIds = (row as? TodoListRow.Group)?.subtreeIds ?: emptySet(),
            )
            flowY += h
            finalY += full
        }
        // 布局阶段只写**普通对象**（不写 Compose 状态）→ 不会触发重组/重排循环。
        geometry.slots = slots
        layout(constraints.maxWidth, flowY) {
            // **真实摆在自己的位置上**（不再全部摆 (0,0) 靠绝对位移假装位置）。
            // 位置只有一个真值源 = 布局；拖动只是叠加一个临时位移（offsets），静止时为 0。
            //
            // ⚠️ 摆放顺序 = 绘制顺序：**先摆普通的行，最后摆过渡中的行** ⇒
            // 过渡中的行画在最上层，不会被正在挤开/收拢的行盖住（盖住就是「压扁」的观感）。
            placeables.forEachIndexed { i, p ->
                if (!isTransitioning[i]) p.placeRelative(0, contentTops[i].toInt())
            }
            placeables.forEachIndexed { i, p ->
                if (isTransitioning[i]) p.placeRelative(0, contentTops[i].toInt())
            }
        }
    }
}

/**
 * 一行待办/分组：包上拖动所需的绘制层。
 *
 * 手势不在这一层（挂在容器的 pointerInput 上）—— 行被 graphicsLayer 平移后，
 * 行自己的局部坐标系会跟着动，行内手势读到的 position 会被钉在按下那一刻（反锁不住手指）。
 */
@Composable
private fun TodoDragRow(
    row: TodoListRow,
    /** 这一行上方是否要画块分割线（顶层行之间才有）。 */
    showDivider: Boolean,
    /** 分割线是否可见（拖动中隐藏）。只改透明度，不动布局。 */
    dividersVisible: Boolean,
    drag: TodoDragState,
    /** 每行**期望停留的绝对 y**；绘制时减去当前槽位得到位移。 */
    desiredTops: MutableMap<String, MutableFloatState>,
    geometry: TodoDragGeometry,
    /** 是否正在消失（收起）：高度动画的终点是 0。 */
    dying: Boolean,
    /** 是否新出现（展开）：高度从 0 长起来。 */
    entering: Boolean,
    /** 出现 / 消失的错开延迟（毫秒）。 */
    revealDelayMs: Int,
    /** 高度收到 0 之后的回调（父级据此把这行从「正在消失」名单里摘掉）。 */
    onDyingFinished: () -> Unit,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onAddInGroup: (String) -> Unit,
) {
    val rowId = todoRowKey(row)
    val dragging = drag.draggingId == rowId
    // 这一行的自然高度：组行与待办行都是 50dp（设计稿 PpQVV）。
    val rowHeight = if (row is TodoListRow.Group) TodoGroupRowHeight else TodoRowHeight

    /**
     * 高度因子：1 = 完全展开（占满行高），0 = 完全收起（不占高度）。
     *
     * ⚠️ 它是**这一行自己的**动画，直接作用在 `Modifier.height` 上 —— 于是：
     *   · 父级 Layout 测到的高度就是当前真实高度；
     *   · 下方行的位置由父级 `y += h` 推导 ⇒ 空间撑开/收拢是连续变化的**结果**，不会突变；
     *   · 不存在「记账高度」与「实际画出来的高度」两份值
     *     （旧写法两份值对不上 ⇒ 内容溢出自己的框 ⇒ 用户看到的文字重叠）。
     *
     * 用 Animatable 而不是 animateFloatAsState：新出现的行要**从 0 起步**，
     * 而 animateFloatAsState 首次组合就直接取目标值（等于没有入场动画）。
     */
    // 空间因子：0 = 不占高度（收拢），1 = 占满行高（撑开）。
    // 它**只**决定这一行占据多少纵向空间，从而把下方项目挤开/收拢。
    val space = remember { Animatable(if (entering) 0f else 1f) }
    // 可见度因子：0 = 完全透明，1 = 完全显示。
    // 内容始终是满高（见下面的 requiredHeight），所以它只影响透明度。
    val vis = remember { Animatable(if (entering) 0f else 1f) }

    LaunchedEffect(dying) {
        // ⚠️ **空间开合 与 内容淡入淡出必须同步开始**（用户 2026-10-01 明确要求）。
        // 曾经写成串行（展开=先让位再出现、收起=先消失再收位），
        // 那是「先展开再出现 / 先消失再折叠」，不是用户要的。
        // 这里用 coroutineScope 让两条动画**同时起跑**，并等两者都跑完再收尾
        // （space 时长更长，所以收尾自然以它为准）。
        if (dying) {
            coroutineScope {
                launch {
                    vis.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = TODO_FADE_OUT_MS,
                            delayMillis = revealDelayMs,
                            // 收起也是**先快后慢**：展开与收起不是互为逆动画。
                            // （与 VMotion 既有约定「退出用 Accelerate」不同，是按用户口径特意改的。）
                            easing = VMotion.Expressive,
                        ),
                    )
                }
                launch {
                    space.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = TODO_SPACE_MS,
                            delayMillis = revealDelayMs,
                            easing = VMotion.Expressive,
                        ),
                    )
                }
            }
            onDyingFinished()
        } else if (space.value < 1f || vis.value < 1f) {
            coroutineScope {
                launch {
                    vis.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = TODO_FADE_IN_MS,
                            delayMillis = revealDelayMs,
                            easing = VMotion.Expressive,
                        ),
                    )
                }
                launch {
                    space.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = TODO_SPACE_MS,
                            delayMillis = revealDelayMs,
                            easing = VMotion.Expressive,
                        ),
                    )
                }
            }
        }
    }
    val dividerAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dividersVisible) 1f else 0f,
        animationSpec = tween(140),
        label = "todoDivider",
    )

    Box(
        Modifier
            .fillMaxWidth()
            // **真实高度随高度因子变化** —— 这才是「撑开 / 收拢空间」。
            // 内容被约束在这个高度内，不会溢出到相邻行上。
            .height(rowHeight * space.value)
            // 位移只在这里叠加：拖动时偏移，静止时恒为 0（位置回落到布局槽位）。
            .graphicsLayer {
                // 位移 = **期望位置 − 当前槽位位置**（每次绘制现算）。
                // 于是布局基准一变（落库后的新顺序），位移自动跟着变 —— 不会坐标突变。
                val want = desiredTops[rowId]?.floatValue
                val slot = geometry.topOrNull(rowId)
                translationY = if (want != null && slot != null) want - slot else 0f
                // 只改透明度：高度已经由上面的 space 承担，内容本身不参与形变。
                if (vis.value < 1f) alpha = vis.value
            }
            .then(
                if (dragging) {
                    // 拖动中的表达：**只高亮底色**（用户 2026-10-01 定稿）+ 图层置顶。
                    //  · 不用 shadow：高亮由底色承担，阴影是多余的层；
                    //  · 不用 scale：行是满宽且优先级标签顶到右边缘，scale > 1 必然把它挤出屏幕；
                    //  · zIndex 保留：被拖事项必须在所有元素之上。
                    Modifier
                        .zIndex(1f)
                        .background(VColors.surface, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 分割线画在**行内部**：它得跟着行一起让位，否则拖动时线留在原地。
            // 显隐只改透明度（graphicsLayer）—— 改布局会让整列每帧重排。
            if (showDivider) {
                Box(Modifier.graphicsLayer { alpha = dividerAlpha }) { VDividerFull() }
            }
            // 拖动期间挡掉行内容的点击入口：长按抓起后松手时，子节点在 Main 阶段
            // 会**先于**容器的拖动循环收到 up，会顺带打开条目。
            val clickable = drag.draggingId == null
            when (row) {
                is TodoListRow.Group -> CalendarTodoGroupRowView(
                    row = row,
                    onToggleFold = { if (clickable) onToggleGroup(row.groupId) },
                    onOpenGroup = { if (clickable) onOpenGroup(row.groupId) },
                    onAddInside = { if (clickable) onAddInGroup(row.groupId) },
                )

                is TodoListRow.Todo -> CalendarTodoRowView(
                    row = row.row,
                    depth = row.depth,
                    onToggle = { if (clickable) onToggle(row.row.itemId) },
                    onOpen = { if (clickable) onOpen(row.row.itemId) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 待办分组

/** 分组行高（设计稿 PpQVV：组行 50）。 */
private val TodoGroupRowHeight = 50.dp

/** 待办行高（设计稿 PpQVV：待办行也是 50；原来写 54 是旧稿）。 */
private val TodoRowHeight = 50.dp

/** 嵌套缩进步长：每深一级 +18dp（子组与组内待办都用它）。 */
private val TodoIndentStep = 18.dp

/**
 * 待办空状态：从**视口高度**里扣掉多少，剩下的区域用来垂直居中。
 *
 * 这个数 = 「头部占掉的高度」+ 「白板与本页坐标系起点之差」。
 * 白板的空状态撑满的是整屏、再上移 chrome/2；本页的滚动视口从头部**下方**才开始，
 * 两者起点不同，所以扣掉的不只是头部行高。
 *
 * 实测（1080x2400 / 420dpi，2026-10-01）：viewport=751.7dp，扣 169dp 时图标中心
 * 落在 y≈1236px，与白板空状态**逐像素相同**。
 *
 * ⚠️ 它与具体屏幕无关（屏幕变化由 `viewportHeight` 承担），但改 `AgendaHead` 行高、
 * 外层 `spacedBy(18.dp)`、或 `VEmptyState` 的 padding 之后需要重测。
 */
private val TodoEmptyHeaderBlock = 169.dp

/**
 * 待办分组行（设计稿 PpQVV）：折叠箭头 ｜ 组名 + 「x 项 · y 未完成」 ｜ 组内加号。
 *
 * 三个热区各管一件事（用户 2026-09-26）：
 *  · **箭头** = 折叠 / 展开（空组不画箭头）；
 *  · **组名区** = 打开该组编辑页 —— 改名与删除都在那里，
 *    与「点待办行进编辑」同一套心智，不在行上加第二个入口；
 *  · **加号** = 进添加页并预选这个组。
 */
@Composable
private fun CalendarTodoGroupRowView(
    row: TodoListRow.Group,
    onToggleFold: () -> Unit,
    onOpenGroup: () -> Unit,
    onAddInside: () -> Unit,
) {
    // 用户 2026-09-30 两次校准的最终值：
    //   · 箭头：第一次"偏左" ⇒ 我把 7dp 加在行首内衬上；第二次"还是不对，再往左移 4dp"
    //     ⇒ 7 - 4 = **3dp**。（墨迹 3+3 = 6dp，介于原来的 15dp 与复选框的 22dp 之间。）
    //   · 加号：用户"位置可以了" ⇒ 行尾内衬**保持 7dp 不动**。
    //
    // ⚠️ 两个内衬**必须分开**：它们现在各自被用户单独校准过，合并成一个数就会一改两动。
    val arrowInset = 3.dp
    val plusInset = 7.dp
    Row(
        Modifier
            .fillMaxWidth()
            // requiredHeight：忽略父级（正在收缩的）高度约束，内容始终保持满高。
            // 于是出现/消失时**内容不移动、也不被擦除**，只有透明度在变 —— 与白板卡片一致。
            .requiredHeight(TodoGroupRowHeight)
            .padding(start = TodoIndentStep * row.depth + arrowInset, end = plusInset),
        verticalAlignment = Alignment.CenterVertically,
        // 间距随箭头等量收窄 ⇒ **标题仍在 34dp 分毫不动**：3 + 22 + 9 = 0 + 22 + 12 = 34。
        // （护栏 B7：移动元素必须重算它的全部补偿量；只改内衬会让标题跟着右移。）
        horizontalArrangement = Arrangement.spacedBy(12.dp - arrowInset),
    ) {
        Box(
            Modifier.size(22.dp).vPressable(scaleDown = 0.9f, onClick = onToggleFold),
            contentAlignment = Alignment.Center,
        ) {
            if (row.hasChildren) {
                Icon(
                    if (row.collapsed) Lucide.ChevronRight else Lucide.ChevronDown,
                    contentDescription = if (row.collapsed) "展开" else "折叠",
                    modifier = Modifier.size(16.dp),
                    tint = VColors.ink2,
                )
            }
        }
        Column(
            Modifier.weight(1f).vPressable(scaleDown = 0.99f, onClick = onOpenGroup),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            VText(row.title, VTypo.body.copy(fontSize = 13.sp), color = VColors.ink, maxLines = 1)
            VText(row.meta, VTypo.caption.copy(fontSize = 11.sp), color = VColors.ink3, maxLines = 1)
        }
        Box(
            Modifier.size(22.dp).vPressable(scaleDown = 0.9f, onClick = onAddInside),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Plus, "在此组内新建", Modifier.size(16.dp), tint = VColors.ink3)
        }
    }
}

@Composable
private fun CalendarTodoRowView(
    row: CalendarTodoRow,
    /** 嵌套深度（在待办组里的层级）；每级缩进一个步长。 */
    depth: Int = 0,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    // 设计稿 PpQVV：行高 50、左内边距 10、复选框 22dp 方框（r=7）。
    Row(
        Modifier
            .fillMaxWidth()
            // requiredHeight：同上 —— 内容保持满高，不做「擦除」式出现/消失。
            .requiredHeight(TodoRowHeight)
            .padding(start = TodoIndentStep * depth + 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VCheckbox(
            checked = row.done,
            onToggle = onToggle,
            style = VCheckStyle.Outline,
            size = 22.dp,
        )
        Row(
            Modifier.weight(1f).vPressable(scaleDown = 0.99f, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText(row.title, VTypo.body.copy(fontSize = 13.sp), color = if (row.done) VColors.ink3 else VColors.ink, maxLines = 1)
                row.metaText?.let { VText(it, VTypo.caption.copy(fontSize = 11.sp), color = VColors.ink3, maxLines = 1) }
            }
        }
        row.priorityLabel?.let { label ->
            val (bg, fg) = when (row.priorityKind) {
                TodayChipKind.Rose -> VColors.roseSoft to VColors.rose
                TodayChipKind.Amber -> VColors.amberSoft to VColors.amber
                TodayChipKind.Accent -> VColors.accentSoft to VColors.accent
                TodayChipKind.Grey -> VColors.surface2 to VColors.ink3
            }
            Box(
                Modifier
                    .background(bg, RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                VText(label, VTypo.micro, color = fg, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(14.dp))
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        VText(text, VTypo.body, color = VColors.ink3)
    }
}

// ---------------------------------------------------------------- 详情浮层（设计稿 u0QP0i）
//
// ============================ 可调参数（改这里） ============================

/**
 * 月网格的纵向几何 —— **唯一来源**。
 *
 * 月视图打开时要把「今天那周」滚到视口中间，得先算出它的偏移；
 * 算偏移用的值必须与 [MonthGridV3] 里真正使用的一致，
 * 所以两处都引用这两个常量，不要再写字面量（写死两份必然会漂）。
 */
private val MonthHeaderHeight = 18.dp
private val MonthWeekRowHeight = 118.dp

/** 卡片宽度：设计稿 Floating Card w=310。 */
private val DetailCardWidth = 310.dp

/** 卡片圆角：设计稿 r=22。 */
private val DetailCardRadius = 22.dp

/**
 * 背景模糊半径。**取自 pen.dev 设计稿**：
 * `Blur Backdrop  fx={"type":"background_blur","radius":2}`。
 * 想更朦胧就调大（2 → 6 / 9），数值只在「显示 → 隐藏」时变化一次，
 * 不做逐帧插值（模糊半径每变一次都要重建 RenderEffect）。
 *
 * 两处都用它，语义相同（都是「背景变模糊」）：详情浮层的页面背景、
 * 日视图转场期间的月内容。
 */
private val DetailBackdropBlur = 2.dp

/** 蒙层：设计稿 `#ffffff80`（白 50%）。 */
private val DetailScrim = Color.White.copy(alpha = 0.5f)

/** 进入起始缩放：0.9 = 从 90% 弹到 100%（回弹时会略微过冲）。 */
private const val DetailEnterScale = 0.88f

/** 进入时从下方上浮的距离。 */
private val DetailRise = 26.dp

/**
 * **进入**动画：快速弹性。
 * dampingRatio 越大 → 回弹（弹出后回缩）越少：0.72 ≈ 回缩 4%，1.0 = 完全不回弹。
 * 想更快 → 加大 stiffness（1300 → 1800）；想回缩更明显 → 减小 dampingRatio（0.72 → 0.55）。
 */
private fun <T> detailEnterSpec() = spring<T>(dampingRatio = 0.72f, stiffness = 1300f)

/**
 * **退出**动画：比进入更快、不回弹（缓起快收）。想更慢 → 加大 millis。
 */
private fun <T> detailExitSpec() = tween<T>(durationMillis = 130, easing = VMotion.Accelerate)

// ==========================================================================

/**
 * 详情浮层外壳。除了画浮层，它还负责两件"不能拆开"的事：
 *
 * 1. **背景模糊**：`Modifier.blur` 模糊的是节点自己的绘制内容，蒙层是纯色，
 *    给它加模糊没有意义 —— 所以模糊必须由这里施加到 [content] 上。
 * 2. **退场时序**：调用方把 [target] 置 null 只表示"开始退场"，
 *    内部仍保留数据把退场动画播完（[mounted] 非空），播完才真正卸载。
 *    否则退出动画会因为数据先没了而什么都画不出来。
 *
 * 位置：**垂直居中**。设计稿是 998 高画布上的绝对定位 `ABS y=208`，
 * 居中才能适配各种屏幕高度、且卡片内容多寡都不会让它跑偏。
 */
@Composable
internal fun DetailLayer(
    target: EventDetailUi?,
    storage: com.phonlynn.oreplan.platform.attachment.AttachmentStorage,
    onRequestClose: () -> Unit,
    onEdit: (EventDetailUi) -> Unit,
    onDelete: (EventDetailUi) -> Unit,
    onArchive: (EventDetailUi) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 图片放大与白板**同一套**（VFullscreenImageViewer：多图可左右翻、可缩放）。
    var fullscreenImages by remember { mutableStateOf<List<String>?>(null) }
    var fullscreenStart by remember { mutableStateOf(0) }

    // 正在渲染的数据；退场动画播完前一直保持非空
    var mounted by remember { mutableStateOf<EventDetailUi?>(null) }
    // 退场播完后要执行的动作（编辑 / 删除）；null = 单纯关闭
    var afterExit by remember { mutableStateOf<((EventDetailUi) -> Unit)?>(null) }
    // 组合期同步挂载：晚一帧的话首帧没有卡片内容，会闪一下
    target?.let { if (mounted !== it) mounted = it }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target != null) {
            progress.snapTo(0f)
            progress.animateTo(1f, detailEnterSpec())
        } else if (mounted != null) {
            progress.animateTo(0f, detailExitSpec())
            val data = mounted
            val action = afterExit
            afterExit = null
            mounted = null
            if (data != null) action?.invoke(data)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 页面内容的背景模糊：**固定半径的双层交叉淡化**（照白板聚焦浮层原样搬）。
        //
        // 早先这里写的是 `.then(if (mounted != null) Modifier.blur(...) else Modifier)`：
        // 模糊跟 `mounted` 这个**布尔**走，而卡片自己的 alpha 跟 `progress` 走 —— 两个源
        // 不同步；而且 `Modifier.blur` 会插入/移除一个 graphicsLayer 节点，节点被移除时
        // 上面的 renderEffect 没有可插值的目标，模糊是在那一刻直接没的。于是进入时
        // 「背景一声不响就糊上」、退出时「卡片还在、背景已经清晰了」（用户 2026-09-26）。
        //
        // 现在：内容只组合一次，用 GraphicsLayer 记一遍、画两遍：
        //   清晰层 alpha = 1 − t；模糊层恒定 2dp、alpha = t
        // t 直接用浮层已有的 `progress`，所以背景模糊与卡片入场/退场**天然逐帧同步**。
        // 半径固定 → RenderEffect 只建一次（逐帧改半径要重建，实测很卡）。
        val sharpLayer = rememberGraphicsLayer()
        val blurLayer = rememberGraphicsLayer()
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    // **在绘制阶段读 progress**，不进组合期：动画期间一次重组都不会发生。
                    // 只要浮层还在画（含退场期间）就由它驱动：progress 自然跑到 0，模糊随之淡尽，
                    // 不需要另一个布尔开关（那正是当初不同步的根源）。
                    val t = if (mounted != null) progress.value.coerceIn(0f, 1f) else 0f
                    // 静态（无浮层、t = 0）：直接画内容，不进图层。
                    //
                    // 这一条不能省：本组件是**常驻的**（它包着整页内容），空闲时也会走
                    // drawWithContent；若仍去 record + drawLayer，就等于每帧多一次全屏离屏
                    // 绘制（日程页曾因同类问题“停页期间每帧白烧”，用户 2026-09-25）。
                    if (t <= 0.001f) {
                        this@drawWithContent.drawContent()
                    } else {
                        // 清晰层：随 t 增大而淡出。t 已到 1 时它完全透明、已被盖住，不必再画。
                        if (t < 0.999f) {
                            sharpLayer.record { this@drawWithContent.drawContent() }
                            sharpLayer.alpha = 1f - t
                            sharpLayer.renderEffect = null
                            drawLayer(sharpLayer)
                        }
                        // 模糊层：半径固定 2dp（逐帧改半径要重建 RenderEffect，实测很卡）。
                        blurLayer.record { this@drawWithContent.drawContent() }
                        blurLayer.alpha = t
                        blurLayer.renderEffect = BlurEffect(
                            DetailBackdropBlur.toPx(),
                            DetailBackdropBlur.toPx(),
                            TileMode.Clamp,
                        )
                        drawLayer(blurLayer)
                    }
                },
        ) {
            content()
        }

        val detail = mounted ?: return@Box

        fun dismiss(then: ((EventDetailUi) -> Unit)? = null) {
            afterExit = then
            onRequestClose()
        }

        Box(
            Modifier
                .fillMaxSize()
                // 透明度和蒙层都在绘制阶段取值（graphicsLayer 的 lambda 不进组合），
                // 于是动画期间一次重组都不会发生。
                .graphicsLayer { alpha = progress.value }
                .background(DetailScrim)
                .vPressable(scaleDown = 1f, onClick = { dismiss() }),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(DetailCardWidth)
                    .graphicsLayer {
                        val v = progress.value
                        val s = DetailEnterScale + (1f - DetailEnterScale) * v
                        scaleX = s
                        scaleY = s
                        alpha = v.coerceIn(0f, 1f)
                        translationY = (1f - v) * DetailRise.toPx()
                    }
                    .shadow(24.dp, RoundedCornerShape(DetailCardRadius), spotColor = Color(0x66101613), ambientColor = Color(0x66101613))
                    .background(VColors.surface, RoundedCornerShape(DetailCardRadius))
                    .vPressable(scaleDown = 1f) { /* 消费点击，避免点到卡片时关闭 */ }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(VColors.accentSoft, RoundedCornerShape(9.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    VText(detail.categoryLabel, VTypo.micro, color = VColors.accent)
                }
                // 右上角统一是「关闭 ✕」—— 待办也走底部按钮进设置，和课程/日程一致
                // （用户 2026-09-23 明确不要三点菜单）。
                Box(
                    Modifier
                        .size(26.dp)
                        .background(VColors.surface2, CircleShape)
                        .vPressable(scaleDown = 0.88f, onClick = { dismiss() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, "关闭", Modifier.size(14.dp), tint = VColors.ink2)
                }
            }
            VText(detail.title, VTypo.dialogTitle.copy(fontSize = 18.sp), color = VColors.ink, maxLines = 2)
            // 待办的创建时间：**浅灰小字**，不跟截止时间抢主次（用户 2026-09-23）。
            detail.createdText?.let {
                VText(it, VTypo.caption12.copy(fontSize = 11.sp), color = VColors.ink3)
            }
            // 时间行 + 它下面那条分割线是**一组**：待办没有开始/结束时间，
            // 两者都不该出现（否则要么留个孤立的计时器图标，要么标题下多一条没有意义的分割线）。
            if (detail.timeText != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.Timer, null, Modifier.size(15.dp), tint = VColors.ink2)
                    VText(detail.timeText, VTypo.bodyMed.copy(fontFamily = NumFont, fontSize = 13.sp), color = VColors.ink)
                    detail.durationText?.let { VText(it, VTypo.caption, color = VColors.ink3) }
                }
                DetailDivider()
            }
            detail.dateText?.let { DetailInfoRow(Lucide.Calendar, "日期", it) }
            detail.locationText?.let { DetailInfoRow(Lucide.MapPin, "地点", it) }
            detail.reminderText?.let { DetailInfoRow(Lucide.AlarmClock, "提醒", it) }
            // 备注**按小节**渲染（用户 2026-09-28）：
            //  · 小节之间画分割线 —— 多组备注由此分开（以前只显示第一个非空小节，分不开）；
            //  · 小节内部（文字 / 图片 / 附件）**不画线**，间距靠 top padding 控制。
            detail.notes.forEach { section ->
                DetailDivider()
                if (section.body.isNotBlank()) {
                    // 备注是富文本（与编辑端同一套编码）：必须用 VRichText 渲染，
                    // 否则结构字符会原样显示出来。
                    VRichText(
                        text = section.body,
                        style = VTypo.caption12.copy(lineHeight = 12.sp * 1.5f),
                        color = VColors.ink2,
                    )
                }
                if (section.photos.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = if (section.body.isNotBlank()) 8.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        section.photos.take(3).forEachIndexed { index, path ->
                            VAttachmentThumb(
                                storage = storage,
                                relativePath = path,
                                modifier = Modifier.size(84.dp).vPressable(scaleDown = 0.95f) {
                                    // 放大看的是本节全部图片（缩略图只摆前 3 张）。
                                    fullscreenImages = section.photos
                                    fullscreenStart = index
                                },
                            )
                        }
                    }
                }
                section.files.forEach { file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                top = if (section.body.isNotBlank() || section.photos.isNotEmpty()) 8.dp else 0.dp,
                            )
                            .vPressable(scaleDown = 0.99f) {
                                openAttachmentV2(context, storage, file) { }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Lucide.FileText, null, Modifier.size(15.dp), tint = VColors.ink2)
                        VText(
                            file.displayName,
                            VTypo.caption12.copy(fontWeight = FontWeight.Medium),
                            color = VColors.ink,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        VText(attachmentMetaText(file.sizeBytes, file.mimeType), VTypo.caption, color = VColors.ink3)
                        VChevron()
                    }
                }
            }
            DetailDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .background(VColors.accent, RoundedCornerShape(14.dp))
                        .vPressable(scaleDown = 0.97f, onClick = { dismiss(onEdit) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Lucide.Pencil, null, Modifier.size(15.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    VText(
                        when {
                            detail.isCourse -> "编辑课程"
                            detail.isTask -> "编辑待办"
                            else -> "编辑日程"
                        },
                        VTypo.bodyMed,
                        color = Color.White,
                    )
                }
                if (!detail.isCourse) {
                    // 已完成的待办：删除换成**归档** —— 完成的成果不该被直接删掉，
                    // 归档后能在「设置 → 已归档的待办」里找回来（用户 2026-09-23）。
                    val archivable = detail.isTask && detail.isDone
                    Box(
                        Modifier
                            .size(46.dp)
                            .background(VColors.surface2, RoundedCornerShape(14.dp))
                            .vPressable(scaleDown = 0.94f, onClick = { dismiss(if (archivable) onArchive else onDelete) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (archivable) {
                            Icon(Lucide.Archive, "归档", Modifier.size(18.dp), tint = VColors.accent)
                        } else {
                            Icon(Lucide.Trash2, "删除", Modifier.size(18.dp), tint = VColors.rose)
                        }
                    }
                }
            }
        }
        }

        fullscreenImages?.let { paths ->
            VFullscreenImageViewer(
                paths = paths,
                startIndex = fullscreenStart,
                storage = storage,
                onDismiss = { fullscreenImages = null },
            )
        }
    }
}

@Composable
private fun DetailDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.line))
}

@Composable
private fun DetailInfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = VColors.ink3)
        VText(label, VTypo.caption, color = VColors.ink3)
        VText(value, VTypo.caption12.copy(fontWeight = FontWeight.Medium), color = VColors.ink, maxLines = 1)
    }
}

// sizeText 已提升为公用组件 attachmentMetaText（v2/components/AttachmentMeta.kt）——
// 白板的三处附件行与这里必须用同一份实现。
