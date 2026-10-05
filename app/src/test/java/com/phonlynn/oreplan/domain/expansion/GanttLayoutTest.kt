package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.PlanTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class GanttLayoutTest {

    private val now: Instant = Instant.parse("2026-09-11T04:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 9, 11)

    private fun root(
        id: String,
        start: LocalDate? = null,
        end: LocalDate? = null,
        kind: ItemKind = ItemKind.GOAL,
    ): Item = Item.newRoot(kind = kind, title = id, now = now, id = id)
        .copy(planStartDay = start, planEndDay = end)

    private fun child(
        parent: Item,
        id: String,
        start: LocalDate? = null,
        end: LocalDate? = null,
        status: ItemStatus = ItemStatus.TODO,
    ): Item = Item.newChild(parent = parent, kind = ItemKind.GOAL, title = id, now = now, id = id)
        .copy(planStartDay = start, planEndDay = end, status = status)

    private fun tree(items: List<Item>): PlanTree = PlanTreeBuilder.build(items)

    @Test
    fun `父条自动包络子条`() {
        val parent = root("p")                                  // 父自己没有跨度
        val c1 = child(parent, "c1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10))
        val c2 = child(parent, "c2", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 30))

        val model = GanttLayout.build(tree(listOf(parent, c1, c2)), expanded = setOf("p"), today = today)
        val parentBar = model.bars.single { it.item.id == "p" }

        assertEquals(LocalDate.of(2026, 9, 1), parentBar.start)
        assertEquals(LocalDate.of(2026, 9, 30), parentBar.end)
    }

    @Test
    fun `父自己填了跨度时与子条取并集`() {
        val parent = root("p", LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 18))
        val c1 = child(parent, "c1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5))

        val model = GanttLayout.build(tree(listOf(parent, c1)), expanded = setOf("p"), today = today)
        val parentBar = model.bars.single { it.item.id == "p" }

        // 父自己的 9/15-9/18 与子的 9/1-9/5 并集 => 9/1-9/18
        assertEquals(LocalDate.of(2026, 9, 1), parentBar.start)
        assertEquals(LocalDate.of(2026, 9, 18), parentBar.end)
    }

    @Test
    fun `只填起点时当作单日`() {
        val item = root("a", start = LocalDate.of(2026, 9, 12), end = null)
        val model = GanttLayout.build(tree(listOf(item)), expanded = emptySet(), today = today)
        val bar = model.bars.single()
        assertEquals(LocalDate.of(2026, 9, 12), bar.start)
        assertEquals(LocalDate.of(2026, 9, 12), bar.end)
    }

    @Test
    fun `只填终点时也当作单日`() {
        val item = root("a", start = null, end = LocalDate.of(2026, 9, 12))
        val model = GanttLayout.build(tree(listOf(item)), expanded = emptySet(), today = today)
        val bar = model.bars.single()
        assertEquals(LocalDate.of(2026, 9, 12), bar.start)
        assertEquals(LocalDate.of(2026, 9, 12), bar.end)
    }

    @Test
    fun `起止填反了会自动纠正`() {
        val item = root("a", start = LocalDate.of(2026, 9, 20), end = LocalDate.of(2026, 9, 10))
        val model = GanttLayout.build(tree(listOf(item)), expanded = emptySet(), today = today)
        val bar = model.bars.single()
        assertEquals(LocalDate.of(2026, 9, 10), bar.start)
        assertEquals(LocalDate.of(2026, 9, 20), bar.end)
    }

    @Test
    fun `完全没填跨度的节点不画条 但仍然出现在行里`() {
        val item = root("a")
        val model = GanttLayout.build(tree(listOf(item)), expanded = emptySet(), today = today)
        val bar = model.bars.single()
        assertFalse(bar.hasSpan)
        assertNull(bar.start)
        assertNull(bar.end)
    }

    @Test
    fun `视窗把数据首尾包在里面并留出宽裕边缘`() {
        val item = root("a", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20))
        val model = GanttLayout.build(tree(listOf(item)), expanded = emptySet(), today = today)
        // 边缘不再是贴边的 3 天，而是前后各数年 —— 这样随时可以左右拖去看更远的时间，
        // 同时留有边界（无限滑动的画布会让人失去「现在在哪」的参照）。
        assertTrue(model.rangeStart < LocalDate.of(2026, 9, 10))
        assertTrue(model.rangeEnd > LocalDate.of(2026, 9, 20))
        assertTrue(model.rangeStart < today.minusYears(1))
        assertTrue(model.rangeEnd > today.plusYears(1))
    }

    @Test
    fun `没有任何跨度时视窗以今天为中心`() {
        val item = root("a")
        val model = GanttLayout.build(tree(listOf(item)), expanded = emptySet(), today = today)
        assertTrue(model.rangeStart < today)
        assertTrue(model.rangeEnd > today)
    }

    @Test
    fun `折叠的节点不出现在甘特里`() {
        val parent = root("p", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
        val c1 = child(parent, "c1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10))

        val collapsed = GanttLayout.build(tree(listOf(parent, c1)), expanded = emptySet(), today = today)
        assertEquals(listOf("p"), collapsed.bars.map { it.item.id })

        val expandedModel = GanttLayout.build(tree(listOf(parent, c1)), expanded = setOf("p"), today = today)
        assertEquals(listOf("p", "c1"), expandedModel.bars.map { it.item.id })
    }

    @Test
    fun `fractionOf 在视窗两端是 0 和 1`() {
        val item = root("a", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 20))
        val model = GanttLayout.build(tree(listOf(item)), expanded = emptySet(), today = today)

        assertEquals(0f, GanttLayout.fractionOf(model.rangeStart, model))
        assertEquals(1f, GanttLayout.fractionOf(model.rangeEnd, model))
        // 超出视窗的值被夹住，避免画出界
        assertEquals(0f, GanttLayout.fractionOf(model.rangeStart.minusDays(5), model))
        assertEquals(1f, GanttLayout.fractionOf(model.rangeEnd.plusDays(5), model))
    }

    @Test
    fun `进度透传到条上`() {
        val parent = root("p", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        val done = child(parent, "c1", status = ItemStatus.DONE)
        val todo = child(parent, "c2")

        val model = GanttLayout.build(tree(listOf(parent, done, todo)), expanded = setOf("p"), today = today)
        assertEquals(0.5f, model.bars.single { it.item.id == "p" }.progress)
    }

    @Test
    fun `空树返回空模型且视窗合法`() {
        val model = GanttLayout.build(PlanTree.Empty, expanded = emptySet(), today = today)
        assertTrue(model.bars.isEmpty())
        assertTrue(model.totalDays >= 1)
    }
}
