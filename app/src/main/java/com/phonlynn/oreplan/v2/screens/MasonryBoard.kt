package com.phonlynn.oreplan.v2.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.core.order.BoardPush
import com.phonlynn.oreplan.domain.model.BoardCard
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp

/**
 * 拖动状态。**刻意分成两类字段**（2026-09-18 血的教训）：
 *
 *  - [draggedId] 是 Compose 状态：需要在**组合期**决定卡片是否「浮起来」
 *    （放大/投影/倾斜归零）。它一次拖动只变两次，不会造成高频重组。
 *
 *  - [pointerX] / [pointerY] 是「被拖卡片左上角应处的容器内坐标」，
 *    **只在动画循环里读取**（`withFrameNanos` 内，不在组合期）。
 *    因此指针每次移动写它们**不会触发任何重组**。
 *
 * 上一版把指针位置做成组合期读取的值（`dragOffset` 在组合期算），
 * 与布局后的 `tileBounds` 互相驱动，形成了每帧震荡的反馈环——
 * 表现就是「疯狂抽搐」。这就是为什么位置必须走这条「非组合读取」路径。
 */
class BoardDragState {
    var draggedId by mutableStateOf<String?>(null)

    // 以下四个都是普通字段（非 Compose 状态）：只在事件处理与动画循环里读写，
    // 不进组合期，因此指针高频移动不会引发任何重组。

    /**
     * 被拖卡片的容器内坐标**不再单独存储**（曾经有 `pointerX/pointerY`，
     * 但它们在改成 root 坐标后没被更新，导致「长按后卡片跳位」与「让位区停住不动」）。
     * 现在是每帧由「pointerRoot − 抓取偏移 − 容器原点」现算。
     */

    /** 指针在 **root** 坐标里的位置（跟手、落点判定与边缘滚动都用它）。 */
    var pointerRootX = 0f
    var pointerRootY = 0f

    /** 抓取点：手指相对卡片左上角的偏移（卡片跟手时要减去它，否则会跳）。 */
    var grabDx = 0f
    var grabDy = 0f

    /**
     * 当前插入位置（供插入判定的**滞回**使用）。-1 表示未初始化。
     */
    var insertIndex: Int = -1

    /** 开始拖动：[containerX]/[containerY] 是被拖卡片当前的容器内左上角。 */
    fun start(
        id: String,
        rootX: Float,
        rootY: Float,
        grabDx: Float,
        grabDy: Float,
        insertIndex: Int,
    ) {
        pointerRootX = rootX
        pointerRootY = rootY
        this.grabDx = grabDx
        this.grabDy = grabDy
        this.insertIndex = insertIndex
        draggedId = id
    }

    fun moveRoot(rootX: Float, rootY: Float) {
        pointerRootX = rootX
        pointerRootY = rootY
    }

    fun end() {
        draggedId = null
        insertIndex = -1
    }
}

/**
 * 双列瀑布流（小红书那种）＋ 拖动让位。
 *
 * ============================================================================
 * 架构（2026-09-18 第三次重做，这一版彻底切断反馈环）
 * ============================================================================
 *
 * 三条铁律，违反任何一条都会出现「卡片集体抽搐」：
 *
 *  1. **布局阶段不写 Compose 状态**。每张卡的目标位置写进 [SlotTable]
 *     （普通对象）。写状态会在同一帧触发再次组合 + 重测。
 *
 *  2. **位置动画在独立的长生命周期循环里，且目标变化不重启循环**。
 *     靠 `LaunchedEffect(目标) { animateTo() }` 会被每帧取消重启，动画永远跑不完。
 *
 *  3. **每帧变化的值绝不能在组合期被读取**（这是第三次重做新增的铁律）。
 *     指针位置、推挤位移都只在动画循环内计算与读取。
 *     一旦让它们进入组合期（例如算出 `dragOffset` 传给子卡片），
 *     就会和「布局完成后的实测 bounds」形成互相驱动的环 → 每帧震荡。
 *
 * 数据流：
 *  - 布局：算**静态槽位**（x, y, w, h）→ [SlotTable]（仅在顺序/高度变化时重测）。
 *  - 动画循环（每卡一个、不重启）：目标 = 静态槽位 +（非拖动卡）连续推挤位移；
 *    被拖卡片的目标 = 指针位置（跟手，不插值）。
 *  - 渲染：`translation = 视觉位置 − 布局位置`。
 */
@Composable
fun MasonryBoard(
    cards: List<BoardCard>,
    isFullWidth: (BoardCard) -> Boolean,
    drag: BoardDragState,
    /**
     * 静态槽位表。由调用方创建并传入：调用方需要它做**拖动插入位置判定**。
     * 必须用静态槽位而不是卡片的视觉位置（含让位位移）——
     * 视觉位置每帧都在变，用它做命中判定会让插入位置在边界处反复翻转。
     */
    slots: SlotTable,
    columnGap: Dp = 10.dp,
    rowGap: Dp = 16.dp,
    /**
     * 本容器左上角在 root 坐标里的位置。**故意不给默认值**：
     * 漏传会让被拖卡片的跟手少减一个容器原点（卡片被摆到 root 坐标处、
     * 视觉上偏下一大截），而且不会有任何编译或运行时报错——
     * 这类「静默用错默认值」的 bug 极难从现象反推，所以宁可强制调用方传。
     */
    originInRoot: () -> Offset,
    /**
     * 这张卡此刻是否正在「离场」（已不在数据里、但还要原位淡出一下）。
     *
     * 为真的卡片会**冻结当前视觉位置**，不再跟随静态槽位收敛——
     * 这是「原地消失」的关键：离场卡被插回渲染列表原位，但瀑布流重排后
     * 它的槽位往往已经变了，若照常收敛就会被平滑地拖向新槽位，
     * 表现成卡片「快速飞离」（用户 2026-09-22 反馈）。
     *
     * 默认 `{ false }` 是安全的：没有离场卡时行为与不传完全一致。
     */
    isLeaving: (cardId: String) -> Boolean = { false },
    modifier: Modifier = Modifier,
    item: @Composable (card: BoardCard) -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val gap = columnGap.roundToPx().toFloat()
        val rowGapPx = rowGap.roundToPx().toFloat()
        val totalWidth = constraints.maxWidth
        val columnWidth = ((totalWidth - gap) / 2f).coerceAtLeast(1f)

        data class Measured(
            val cardId: String,
            val w: Int,
            val placeable: androidx.compose.ui.layout.Placeable,
        )

        // 先测量（尺寸必须实测），再交给**与落点模拟共用**的布局算法定位。
        val measured = ArrayList<Measured>(cards.size)
        slots.beginFrame()
        cards.forEach { card ->
            val full = isFullWidth(card)
            val w = if (full) totalWidth else columnWidth.toInt()
            if (w <= 0) return@forEach

            val measurable = subcompose(card.id) {
                SlidingSlot(
                    cardId = card.id,
                    slots = slots,
                    drag = drag,
                    maxPushPx = rowGapPx * 2f,
                    originInRoot = originInRoot,
                    isLeaving = { isLeaving(card.id) },
                ) { item(card) }
            }.firstOrNull() ?: return@forEach

            val placeable = measurable.measure(
                Constraints.fixedWidth(w).copy(minHeight = 0),
            )
            measured += Measured(card.id, w, placeable)
        }

        // 布局：与拖放落点模拟用**同一份实现**（core/order/MasonryLayout）。
        // 不共用就会出现「模拟落点与实际布局不一致」——
        // 表现为「看着有一条缝，却怎么也放不进去」（宽窄交界处洞最多，最明显）。
        val byId = measured.associateBy { it.cardId }
        val items = measured.map { m ->
            val card = cards.first { it.id == m.cardId }
            com.phonlynn.oreplan.core.order.FlowItem(
                id = m.cardId,
                w = m.w.toFloat(),
                h = m.placeable.height.toFloat(),
                full = isFullWidth(card),
            )
        }
        val positions = HashMap<String, Pair<Float, Float>>(items.size)
        var contentHeightPx = 0f
        com.phonlynn.oreplan.core.order.MasonryLayout.flow(
            items = items,
            columnWidth = columnWidth,
            gap = gap,
            rowGap = rowGapPx,
        ) { id, x, y ->
            positions[id] = x to y
            // 记录内容高度（最后一项的底边），用于给出正确的 layout 高度。
            val m = byId[id]
            if (m != null) {
                contentHeightPx = maxOf(contentHeightPx, y + m.placeable.height + rowGapPx)
            }
        }
        measured.forEach { m ->
            val pos = positions[m.cardId] ?: return@forEach
            // 只写静态槽位（普通对象）：不触发组合、不启动动画、不读任何每帧变化的值。
            slots.put(m.cardId, pos.first, pos.second, m.w.toFloat(), m.placeable.height.toFloat())
        }
        slots.endFrame()

        val contentHeight = contentHeightPx.toInt().coerceAtLeast(0)

        layout(totalWidth, contentHeight) {
            // 所有卡片都摆在 (0,0)，**位置完全由动画层的绝对位移决定**。
            // 为什么不摆在槽位上再用「视觉位置 − 槽位」补偿：
            // 槽位一变，子节点会被重新摆放，而动画层的位移是上一帧的值，
            // 两者叠加会让卡片在重排那一帧跳一下（位移是**写死**的，
            // graphicsLayer 不会因普通对象变化而重跑）。
            // 摆 (0,0) + 绝对位移后，槽位变化对渲染没有任何影响。
            // 被拖的卡片最后摆 → 画在最上层（比 zIndex 更确定）。
            measured.forEach { r -> if (r.cardId != drag.draggedId) r.placeable.placeRelative(0, 0) }
            measured.forEach { r -> if (r.cardId == drag.draggedId) r.placeable.placeRelative(0, 0) }
        }
    }
}

/**
 * 静态槽位表（容器内坐标）。普通对象：布局写、动画循环读，全程不碰 Compose 状态。
 */
/**
 * 静态槽位表（容器内坐标）。普通对象：布局写、动画循环与插入判定读，
 * 全程不碰 Compose 状态。
 */
class SlotTable {
    private class Entry {
        val slot = com.phonlynn.oreplan.core.order.BoardSlot()
        var frame = -1
    }

    private val map = HashMap<String, Entry>()
    private var frame = 0

    fun beginFrame() {
        frame++
    }

    fun put(id: String, x: Float, y: Float, w: Float, h: Float) {
        val e = map.getOrPut(id) { Entry() }
        e.slot.x = x
        e.slot.y = y
        e.slot.w = w
        e.slot.h = h
        e.frame = frame
    }

    fun get(id: String): com.phonlynn.oreplan.core.order.BoardSlot? = map[id]?.slot

    fun endFrame() {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.frame != frame) it.remove()
        }
    }
}

/**
 * 位置动画的唯一驱动点。整张卡跑一个**不重启**的长生命周期协程，每帧：
 *
 *  1. 读静态槽位（[SlotTable]，普通对象）；
 *  2. 算出这一帧的目标位置：
 *     - 被拖卡片 → 指针位置（跟手，不插值）；
 *     - 其余卡片 → 静态槽位 + 与「被拖卡片的实时矩形」的连续推挤位移；
 *  3. 用帧率无关的指数收敛把视觉位置推向目标。
 *
 * 目标变化**不重启协程**，只改变读到的值——这是与早期实现最本质的区别。
 * 收敛系数 `k = 1 - exp(-dt / tau)`：帧率无关、单调收敛、不过冲。
 */
@Composable
private fun SlidingSlot(
    cardId: String,
    slots: SlotTable,
    drag: BoardDragState,
    /**
     * 让位位移上限（px）。不宜大：推得越远，松手时同时回弹的幅度越大
     * （会被看成「集体抖动」）。取两个行距，足够有「被挤开」的可感反馈。
     */
    maxPushPx: Float,
    /** 容器左上角在 root 里的位置（跟手用；容器会随列表滚动）。 */
    originInRoot: () -> Offset,
    /**
     * 是否正在离场。为真时**冻结视觉位置**：既不收敛到静态槽位，
     * 也不响应让位推挤——卡片就停在它开始淡出的地方原地消失。
     */
    isLeaving: () -> Boolean,
    content: @Composable () -> Unit,
) {
    // 视觉位置（容器内坐标）。每帧由循环写入；只在 graphicsLayer 的绘制 lambda 里读，
    // 所以只导致重绘、不导致重组。
    var visX by remember(cardId) { mutableFloatStateOf(Float.NaN) }
    var visY by remember(cardId) { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(cardId) {
        var lastFrame = 0L
        while (isActive) {
            withFrameNanos { now ->
                // 离场冻结：位置完全不动（保持开始离场那一刻的视觉值）。
                //
                // 这里**刻意什么都不做**：离场卡的槽位在瀑布流重排后会变，
                // 若继续走下面的收敛分支，它会在淡出的同时被平滑地拖走——
                // 就是「卡位快速飞离」。冻结后与卡片自身的 presence 淡出配合，
                // 得到「原位淡出」。
                //
                // 首次出现（visX 为 NaN）时不冻结：退场卡必然已绘制过一帧，
                // visX 已有值；真的从 NaN 进来也只可能是组合重建，
                // 那时按下面的兜底直接落到当前槽位更安全。
                if (isLeaving() && !visX.isNaN()) {
                    lastFrame = now
                    return@withFrameNanos
                }

                val s = slots.get(cardId) ?: return@withFrameNanos

                // 被拖的卡片：直接跟指针（不插值），保证紧贴手指。
                // 目标 = 指针 root 坐标 − 抓取偏移 − 容器在 root 里的原点
                //（容器会随列表滚动而移动，所以原点每帧取）。
                val draggedId = drag.draggedId
                val isDragged = draggedId == cardId
                val origin = originInRoot()
                // 被拖卡片的容器内左上角（推挤与跟手共用同一个值，避免两处口径不一致）。
                val draggedContainerX = drag.pointerRootX - drag.grabDx - origin.x
                val draggedContainerY = drag.pointerRootY - drag.grabDy - origin.y
                val tx: Float
                val ty: Float
                if (isDragged) {
                    tx = draggedContainerX
                    ty = draggedContainerY
                } else {
                    // 让位：用被拖卡片的**实时矩形**（上面的容器内坐标 + 它自己的尺寸）
                    // 与本卡静态槽位做交叠计算。只有垂直位移（恒向下），
                    // 保证卡片永远在两列之内、不会滑出屏幕。
                    // 全部是普通值，不牵动组合。
                    var py = 0f
                    if (draggedId != null) {
                        val ds = slots.get(draggedId)
                        if (ds != null) {
                            py = BoardPush.pushDown(
                                left = s.x,
                                top = s.y,
                                width = s.w,
                                height = s.h,
                                draggedLeft = draggedContainerX,
                                draggedTop = draggedContainerY,
                                draggedRight = draggedContainerX + ds.w,
                                draggedBottom = draggedContainerY + ds.h,
                                maxPush = maxPushPx,
                            )
                        }
                    }
                    tx = s.x
                    ty = s.y + py
                }

                if (visX.isNaN()) {
                    // 首次出现：直接就位，不播入场位移（入场由卡片自身的淡入负责）。
                    visX = tx
                    visY = ty
                } else if (isDragged) {
                    // 跟手：直接赋值，不插值。
                    if (visX != tx) visX = tx
                    if (visY != ty) visY = ty
                } else {
                    val dtMs = if (lastFrame == 0L) 16f else (now - lastFrame) / 1_000_000f
                    val dt = dtMs.coerceIn(0f, 64f) / 1000f
                    val k = (1f - exp(-dt / TAU_SECONDS)).coerceIn(0f, 1f)
                    val nx = visX + (tx - visX) * k
                    val ny = visY + (ty - visY) * k
                    // 收敛后直接吸附（阈值取 0.01px，视觉无差别）。
                    // 阈值不能大：越大就越会在「吸附到位」与「同目标插值」之间反复切换，
                    // 造成速度不连续 = 肉眼可见的抖动（0.5px 时就很明显）。
                    if (abs(tx - nx) < 0.01f && abs(ty - ny) < 0.01f) {
                        if (visX != tx) visX = tx
                        if (visY != ty) visY = ty
                    } else {
                        visX = nx
                        visY = ny
                    }
                }
                lastFrame = now
            }
        }
    }

    androidx.compose.foundation.layout.Box(
        Modifier.graphicsLayer {
            // 卡片在布局上被摆在 (0,0)，这里直接给**绝对**视觉位置。
            // visX 未就绪（首个绘制帧早于动画循环的第一帧）时给出兜底值：
            //  - 被拖卡片 → 由指针实时换算的容器内坐标；
            //  - 其余卡片 → 静态槽位（否则会从原点飞入）。
            if (visX.isNaN()) {
                if (drag.draggedId == cardId) {
                    val o = originInRoot()
                    translationX = drag.pointerRootX - drag.grabDx - o.x
                    translationY = drag.pointerRootY - drag.grabDy - o.y
                } else {
                    val s = slots.get(cardId)
                    translationX = s?.x ?: 0f
                    translationY = s?.y ?: 0f
                }
            } else {
                translationX = visX
                translationY = visY
            }
        },
    ) {
        content()
    }
}

/** 时间常数：越大越「重」、追得越慢。0.09s 接近轻阻尼弹簧的手感。 */
private const val TAU_SECONDS = 0.09f
