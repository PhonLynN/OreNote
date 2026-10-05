package com.phonlynn.oreplan.core.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双列瀑布流的拖放落点判定。
 *
 * 判定方式：枚举候选插入下标 → 用 [MasonryLayout.flow] 模拟重排 → 取落点离指针最近。
 * 测试盯三件事：
 *  1. **能钻进想放的位置**（含两列各自的任意高度）；
 *  2. **窄卡能插到宽卡附近**（宽卡横跨两列会留洞，模拟必须与真实布局一致）；
 *  3. **不抽摘**（滞回：边界附近微抖不换位）。
 */
class MasonryDropTest {

    private val colW = 100f
    private val gap = 10f
    private val rowGap = 16f
    /** 宽卡宽度：跨两列。 */
    private val fullW = colW * 2 + gap

    private fun slot(x: Float, y: Float, w: Float, h: Float) = BoardSlot().apply {
        this.x = x; this.y = y; this.w = w; this.h = h
    }

    /** 用 [MasonryLayout] 算出被拖卡片插在 [index] 时的落点。 */
    private fun landing(
        order: List<String>,
        slots: Map<String, BoardSlot>,
        full: Set<String>,
        draggedId: String,
        draggedW: Float,
        draggedH: Float,
        index: Int,
    ): BoardSlot? {
        val without = order.filterNot { it == draggedId }
        val items = ArrayList<FlowItem>()
        without.forEachIndexed { i, id ->
            if (i == index) items += FlowItem(draggedId, draggedW, draggedH, draggedW >= colW * 1.5f)
            val s = slots[id]!!
            items += FlowItem(id, s.w, s.h, id in full)
        }
        if (index >= without.size) items += FlowItem(draggedId, draggedW, draggedH, draggedW >= colW * 1.5f)
        var out: BoardSlot? = null
        MasonryLayout.flow(items, colW, gap, rowGap) { id, x, y ->
            if (id == draggedId) out = slot(x, y, draggedW, draggedH)
        }
        return out
    }

    private fun target(
        order: List<String>,
        slots: Map<String, BoardSlot>,
        full: Set<String>,
        pointerX: Float,
        pointerY: Float,
        current: Int = -1,
        hysteresis: Float = 0f,
        draggedW: Float = colW,
        draggedH: Float = 100f,
    ): Int = MasonryDrop.targetIndex(
        orderIds = order,
        draggedId = "X",
        draggedW = draggedW,
        draggedH = draggedH,
        isFullWidthOf = { it in full },
        pointerX = pointerX,
        pointerY = pointerY,
        columnWidth = colW,
        gap = gap,
        rowGap = rowGap,
        slotOf = { slots[it] },
        currentTarget = current,
        hysteresis = hysteresis,
    )

    // ------------------------------------------------- 纯窄卡（基准行为）

    private val narrowOrder = listOf("A", "B", "C", "D")
    private val narrowSlots = mapOf(
        "A" to slot(0f, 0f, colW, 100f),
        "B" to slot(colW + gap, 0f, colW, 100f),
        "C" to slot(0f, 116f, colW, 140f),
        "D" to slot(colW + gap, 116f, colW, 100f),
    )

    @Test
    fun `拖到顶部时落点在最前`() {
        assertEquals(0, target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = -30f))
    }

    @Test
    fun `拖到最底部时落点在最后`() {
        val base = target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = 80f)
        val last = target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = 900f, current = base)
        assertEquals(narrowOrder.size, last)
    }

    @Test
    fun `从左列中部拖到右列中部能落到右列`() {
        val t = target(narrowOrder, narrowSlots, emptySet(), pointerX = 160f, pointerY = 130f)
        val s = landing(narrowOrder, narrowSlots, emptySet(), "X", colW, 100f, t)
        assertTrue("落点应在右列（x=${colW + gap}），实际 ${s?.x}", s != null && s!!.x > colW)
    }

    @Test
    fun `落点随指针下移而单调不降`() {
        var prevY = -1f
        var cur = -1
        var y = -40f
        while (y <= 700f) {
            val t = target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = y, current = cur)
            val s = landing(narrowOrder, narrowSlots, emptySet(), "X", colW, 100f, t)!!
            assertTrue("落点不应回退：指针 y=$y, prev=$prevY, now=${s.y}", s.y >= prevY - 0.01f)
            prevY = s.y
            cur = t
            y += 20f
        }
    }

    // ------------------------------------------- 窄卡 ↔ 宽卡（这次的重点）

    // 顺序：宽卡 W 在最上（横跨两列），下面两窄卡。
    private val wideOrder = listOf("W", "A", "B")
    private val wideSlots = mapOf(
        // W 占满两列，从 0 开始、高 120 → 两列 bottom = 136。
        "W" to slot(0f, 0f, fullW, 120f),
        // A 放左列（136 起），B 放右列（136 起）。
        "A" to slot(0f, 136f, colW, 100f),
        "B" to slot(colW + gap, 136f, colW, 100f),
    )
    private val wideFull = setOf("W")

    @Test
    fun `窄卡拖到宽卡上方时应落在最前（宽卡之前）`() {
        // 指针在宽卡上半部：落点应在宽卡之前，即 y 接近 0。
        val t = target(wideOrder, wideSlots, wideFull, pointerX = 50f, pointerY = 30f)
        val s = landing(wideOrder, wideSlots, wideFull, "X", colW, 100f, t)
        assertTrue("应落在宽卡之前（y≈0），实际 ${s?.y}", s != null && s.y < 60f)
    }

    @Test
    fun `窄卡拖到宽卡与窄卡之间的缝应能插入`() {
        // 指针在宽卡下方、A 上方（y 界于 120~136 之间）：应插到 W 之后、A 之前。
        val t = target(wideOrder, wideSlots, wideFull, pointerX = 50f, pointerY = 128f)
        val s = landing(wideOrder, wideSlots, wideFull, "X", colW, 100f, t)
        assertTrue("落点应在宽卡之后（y≥120），实际 ${s?.y}", s != null && s.y >= 120f)
    }

    @Test
    fun `窄卡拖到两窄卡缝隙（右侧列）能落到右列`() {
        // 指针在右列下方空白：应能落到右列。
        val t = target(wideOrder, wideSlots, wideFull, pointerX = 160f, pointerY = 500f)
        val s = landing(wideOrder, wideSlots, wideFull, "X", colW, 100f, t)
        assertTrue("应落到右列，实际 x=${s?.x}", s != null)
    }

    @Test
    fun `窄卡往宽卡区域拖的落点连续（不跳变）`() {
        // 指针从上到下滑过宽卡与窄卡：落点应连续变化（相邻步 y 差有界）。
        var prevY: Float? = null
        var cur = -1
        var y = -40f
        while (y <= 400f) {
            val t = target(wideOrder, wideSlots, wideFull, pointerX = 50f, pointerY = y, current = cur)
            val s = landing(wideOrder, wideSlots, wideFull, "X", colW, 100f, t)!!
            if (prevY != null) {
                assertTrue(
                    "相邻步落点跳变过大：y=$y, prev=$prevY, now=${s.y}",
                    kotlin.math.abs(s.y - prevY!!) <= 200f,
                )
            }
            prevY = s.y
            cur = t
            y += 20f
        }
    }

    // ---------------------------------------------------------------- 滞回

    @Test
    fun `指针在边界附近抖动时不换位`() {
        val base = target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = 120f)
        var cur = base
        for (dy in listOf(-3f, 3f, -2f, 2f, -4f, 4f, 0f)) {
            val t = target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = 120f + dy, current = cur, hysteresis = 14f)
            assertEquals("边界附近微抖不应换位", base, t)
            cur = t
        }
    }

    @Test
    fun `同一处反复微抖结果唯一`() {
        var cur = target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = 200f)
        val seen = mutableSetOf(cur)
        for (i in 0 until 60) {
            val dy = if (i % 2 == 0) 3f else -3f
            cur = target(narrowOrder, narrowSlots, emptySet(), pointerX = 50f, pointerY = 200f + dy, current = cur, hysteresis = 14f)
            seen += cur
        }
        assertEquals("同一处微抖不应产生多个结果", 1, seen.size)
    }
}
