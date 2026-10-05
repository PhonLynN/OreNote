package com.phonlynn.oreplan.core.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白板拖动的让位位移。
 *
 * 这些断言盯的是用户真实反馈过的两个问题：
 *  - **不能出列**（只做垂直位移，水平恒为 0）；
 *  - **不能抖动**（位移在边界处连续归零，不会正负翻转）。
 */
class BoardPushTest {

    /** 静止卡：宽 100 高 100，左上角 (0, 0)。 */
    private fun push(
        draggedLeft: Float,
        draggedTop: Float,
        draggedRight: Float,
        draggedBottom: Float,
        maxPush: Float = 40f,
    ) = BoardPush.pushDown(
        left = 0f, top = 0f, width = 100f, height = 100f,
        draggedLeft = draggedLeft, draggedTop = draggedTop,
        draggedRight = draggedRight, draggedBottom = draggedBottom,
        maxPush = maxPush,
    )

    @Test
    fun `窄卡压在宽卡上时，位移不因宽度差异而失衡`() {
        // 静止卡宽 200 高 100；被拖窄卡宽 100。
        // 早先水平因子以**本卡**宽度作分母，窄/宽两种情形得到的因子不同，
        // 挤压看起来不对称（用户反馈“挤压实体中心不在卡片中心”）。
        val d = BoardPush.pushDown(
            left = 0f, top = 0f, width = 200f, height = 100f,
            draggedLeft = 50f, draggedTop = -30f, draggedRight = 150f, draggedBottom = 70f,
            maxPush = 40f,
        )
        assertTrue("窄卡压宽卡应产生向下让位，实际 $d", d > 0f)
    }

    @Test
    fun `水平压满时因子为 1（位移不因宽度放大）`() {
        // 被拖卡完全覆盖本卡宽度：无论宽窄，水平因子都应满 1，
        // 位移只由垂直交叠与上限决定。
        val a = BoardPush.pushDown(
            left = 0f, top = 0f, width = 100f, height = 100f,
            draggedLeft = 0f, draggedTop = -30f, draggedRight = 100f, draggedBottom = 70f,
            maxPush = 40f,
        )
        val b = BoardPush.pushDown(
            left = 0f, top = 0f, width = 100f, height = 100f,
            draggedLeft = -50f, draggedTop = -30f, draggedRight = 150f, draggedBottom = 70f,
            maxPush = 40f,
        )
        assertEquals("完全覆盖与超出覆盖应得到相同位移", a, b, 0.001f)
    }

    @Test
    fun `无交叠时不产生位移`() {
        assertEquals(0f, push(200f, 200f, 300f, 300f), 0.001f)
    }

    @Test
    fun `只有纵向相接但无交叠时不推`() {
        // 被拖卡正好在本卡上方、边贴边（不重叠）。
        assertEquals(0f, push(0f, -100f, 100f, 0f), 0.001f)
    }

    @Test
    fun `被拖卡在上方压住本卡时向下让位`() {
        // 被拖卡纵向与本卡交叠，但**中心在本卡中心之上**（20 < 50）→ 正位移。
        val d = push(0f, -30f, 100f, 70f)
        assertTrue("应向下让位，实际 $d", d > 0f)
    }

    @Test
    fun `被拖卡中心低于本卡中心时不再让位（不翻转方向）`() {
        // 被拖卡在本卡中心之下 → 必须为 0，绝不能变成负位移（那就是抖动源）。
        val d = push(0f, 60f, 100f, 160f)
        assertTrue("不得为负，实际 $d", d >= 0f)
    }

    @Test
    fun `位移恒不为负（水平已完全移除，卡片不会出列）`() {
        for (t in -150..150 step 10) {
            val d = push(0f, t.toFloat(), 100f, (t + 100).toFloat())
            assertTrue("位移出现负值：t=$t, d=$d", d >= 0f)
        }
    }

    @Test
    fun `位移不超过上限`() {
        val d = push(0f, -100f, 100f, 0f, maxPush = 30f)
        assertTrue("超过上限：$d", d <= 30f + 0.001f)
    }

    @Test
    fun `位移随被拖卡移动全程连续（相邻步变化有界）`() {
        // 被拖卡从远上方扫到下方：位移应是一条连续曲线（先升后降），
        // 任何相邻步的跳变都必须很小 —— 这就是「不抖动」的可测形式。
        var prev = push(0f, -260f, 100f, -160f)
        var t = -260f
        while (t <= 160f) {
            val d = push(0f, t, 100f, t + 100f)
            assertTrue("相邻步变化过大（跳变）：t=$t, prev=$prev, now=$d", kotlin.math.abs(d - prev) <= 12f)
            prev = d
            t += 5f
        }
    }

    @Test
    fun `远离时位移为 0（上方与下方两端）`() {
        assertEquals(0f, push(0f, -260f, 100f, -160f), 0.001f)
        assertEquals(0f, push(0f, 160f, 100f, 260f), 0.001f)
    }

    @Test
    fun `被拖卡越过本卡中心时位移连续归零（不跳变）`() {
        // 关键连续性断言：中心从上方越过到下方，位移必须连续地降到 0，不得跳变。
        var prev = push(0f, -40f, 100f, 60f)
        var t = -40f
        while (t <= 60f) {
            val d = push(0f, t, 100f, t + 100f)
            assertTrue("相邻步变化过大（跳变）：t=$t, prev=$prev, now=$d", kotlin.math.abs(d - prev) <= 12f)
            prev = d
            t += 5f
        }
    }

    @Test
    fun `水平方向离开本卡时位移连续归零（不跳变）`() {
        // 被拖卡从完全水平覆盖，逐渐向右移出（纵向仍交叠），位移应连续降到 0。
        var prev = -1f
        var l = -100f
        while (l <= 100f) {
            val d = push(l, -50f, l + 100f, 50f)
            if (prev >= 0f) {
                assertTrue("相邻步变化过大（跳变）：l=$l, prev=$prev, now=$d", kotlin.math.abs(d - prev) <= 12f)
            }
            prev = d
            l += 5f
        }
        // 完全移出后必须为 0。
        assertEquals(0f, push(101f, -50f, 201f, 50f), 0.001f)
    }
}
