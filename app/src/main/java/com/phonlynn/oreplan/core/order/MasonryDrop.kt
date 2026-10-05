package com.phonlynn.oreplan.core.order

import kotlin.math.abs

/** 一张卡片在瀑布流里的**静态槽位**（容器内坐标，左上角 + 尺寸）。 */
class BoardSlot {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
}

/** 瀑布流布局的输入项：尺寸 + 是否整行（宽）卡。 */
class FlowItem(val id: String, val w: Float, val h: Float, val full: Boolean)

/**
 * 双列瀑布流的**布局算法**。纯函数，**布局与拖放落点模拟共用这一份实现**。
 *
 * 为什么必须共用（2026-09-18 用户反馈「窄卡往宽卡缝隙拖几乎拖不进去」）：
 * 落点判定用的是「枚举插入下标 → 模拟重排 → 取落点离指针最近的那个」。
 * 早先模拟用的是简化版（只按「较矮列」放），而真实布局还有**填洞**逻辑
 * （宽卡横跨两列会在较矮列上方留下空洞，后续窄卡优先填洞）。
 * 两者不一致时，模拟出的落点与实际落点不同 ——
 * 表现为「看着有一条缝，却怎么也放不进去」，尤其是宽窄交界处（洞最多）。
 *
 * 因此把算法抽到这里，`MasonryBoard` 的真实布局与 `MasonryDrop` 的模拟都调它。
 */
object MasonryLayout {

    /**
     * 按 [items] 的顺序做双列瀑布流布局，依次写出每项的槽位。
     *
     * 规则（与界面实际布局一致）：
     *  - **宽卡**（`full`）：从两列较高处开始、横跨两列；
     *    较矮列在它上方留下的空白记为「洞」；
     *  - **窄卡**：优先填入能容纳它的最靠上的洞；否则放入当前较矮的一列。
     *
     * @param out 结果写入回调（id → 槽位）
     */
    fun flow(
        items: List<FlowItem>,
        columnWidth: Float,
        gap: Float,
        rowGap: Float,
        out: (id: String, x: Float, y: Float) -> Unit,
    ) {
        class Hole(val column: Int, val top: Float, val bottom: Float)
        val holes = ArrayList<Hole>()
        val bottoms = floatArrayOf(0f, 0f)

        items.forEach { item ->
            var left: Float
            var top: Float
            if (item.full) {
                top = maxOf(bottoms[0], bottoms[1])
                for (c in 0..1) {
                    if (bottoms[c] < top) holes += Hole(c, bottoms[c], top)
                }
                left = 0f
                val bottom = top + item.h + rowGap
                bottoms[0] = bottom
                bottoms[1] = bottom
            } else {
                val fitting = holes
                    .filter { it.bottom - it.top >= item.h + rowGap }
                    .minByOrNull { it.top }
                if (fitting != null) {
                    left = if (fitting.column == 0) 0f else columnWidth + gap
                    top = fitting.top
                    val newTop = fitting.top + item.h + rowGap
                    holes.remove(fitting)
                    if (newTop < fitting.bottom) holes += Hole(fitting.column, newTop, fitting.bottom)
                } else {
                    val c = if (bottoms[0] <= bottoms[1]) 0 else 1
                    left = if (c == 0) 0f else columnWidth + gap
                    top = bottoms[c]
                    bottoms[c] = top + item.h + rowGap
                }
            }
            out(item.id, left, top)
        }
    }
}

/**
 * 双列瀑布流拖动时的「插入位置」判定。纯函数，便于把行为钉在单测里。
 *
 * ## 判定方式
 *
 * 瀑布流是**顺序相关**的：给定顺序与插入下标，被拖卡片的落点是确定的。
 * 所以直接以「落点」选插入位置：
 * **枚举所有候选下标 → 用 [MasonryLayout.flow] 模拟重排 → 取落点离指针最近的**。
 * 这样「手指在哪、卡片就落哪」，任意列、任意高度、任意缝隙都能钻。
 *
 * 两条防抖约束：
 *  - **滞回**：只有新候选比当前候选好出 [hysteresis] 才换，避免边界反复横跳；
 *  - 距离用 2D（纵向为主、横向降权，列只有两列）。
 */
object MasonryDrop {

    /** 横向距离权重：列只有两列，横向差异本身离散，不该压过纵向的精细选择。 */
    private const val X_WEIGHT = 0.35f

    /**
     * 选出被拖卡片的插入位置。
     *
     * @param orderIds 当前顺序（含被拖动的卡片）
     * @param draggedId 被拖动卡片的 id
     * @param draggedW 被拖卡片宽（窄卡=列宽，宽卡=整宽）
     * @param draggedH 被拖卡片高
     * @param isFullWidthOf 判断某张卡是否为整行（宽）卡
     * @param slotOf 取某张卡的静态槽位（提供宽高用于模拟）
     * @param pointerX 指针 X（容器内坐标）
     * @param pointerY 指针 Y（容器内坐标）
     * @param columnWidth 单列宽
     * @param gap 列间距
     * @param rowGap 行间距
     * @param currentTarget 上一轮的插入位置（滞回用）；-1 表示未初始化
     * @param hysteresis 换位所需的最小改进量（px）
     * @return 插入位置（0..orderIds.size 的「插入到该下标之前」语义）
     */
    fun targetIndex(
        orderIds: List<String>,
        draggedId: String,
        draggedW: Float,
        draggedH: Float,
        isFullWidthOf: (String) -> Boolean,
        pointerX: Float,
        pointerY: Float,
        columnWidth: Float,
        gap: Float,
        rowGap: Float,
        slotOf: (String) -> BoardSlot?,
        currentTarget: Int,
        hysteresis: Float,
    ): Int {
        val without = orderIds.filterNot { it == draggedId }
        val n = without.size
        if (n == 0) return 0

        val draggedFull = draggedW >= columnWidth * 1.5f

        /** 模拟：把被拖卡片插到第 [index] 位，返回它的落点（x,y,w,h）；失败返回 null。 */
        fun simulate(index: Int): FloatArray? {
            val items = ArrayList<FlowItem>(n + 1)
            without.forEachIndexed { i, id ->
                if (i == index) {
                    items += FlowItem(draggedId, draggedW, draggedH, draggedFull)
                }
                val s = slotOf(id) ?: return null
                items += FlowItem(id, s.w, s.h, isFullWidthOf(id))
            }
            if (index >= n) items += FlowItem(draggedId, draggedW, draggedH, draggedFull)

            var rx = 0f
            var ry = 0f
            var found = false
            MasonryLayout.flow(items, columnWidth, gap, rowGap) { id, x, y ->
                if (id == draggedId) {
                    rx = x
                    ry = y
                    found = true
                }
            }
            return if (found) floatArrayOf(rx, ry, draggedW, draggedH) else null
        }

        fun scoreAt(index: Int): Float? {
            val r = simulate(index) ?: return null
            val cx = r[0] + r[2] / 2f
            val cy = r[1] + r[3] / 2f
            return abs(pointerY - cy) + abs((pointerX - cx) * X_WEIGHT)
        }

        var best = currentTarget.coerceIn(0, n)
        var bestScore = scoreAt(best) ?: Float.MAX_VALUE
        for (i in 0..n) {
            if (i == best) continue
            val sc = scoreAt(i) ?: continue
            // 滞回：必须明显更优才换，否则保持当前，避免边界处反复横跳。
            if (sc < bestScore - hysteresis) {
                bestScore = sc
                best = i
            }
        }
        return best
    }
}
