package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 规划投影模型。
 *
 * 这里最要紧的一条是 [可见行序必须是库内同级序的子序列]：拖动落点的下标换算就建立在
 * 这个前提上，一旦不成立，落位就会整体错位。
 */
class PlanOutlineTest {

    private val now: Instant = Instant.parse("2026-09-12T04:00:00Z")

    private fun root(
        id: String,
        kind: ItemKind = ItemKind.GOAL,
        order: Double = 0.0,
    ): Item = Item.newRoot(kind = kind, title = id, now = now, id = id).copy(orderIndex = order)

    private fun child(
        parent: Item,
        id: String,
        kind: ItemKind = ItemKind.GOAL,
        order: Double = 0.0,
    ): Item = Item.newChild(parent = parent, kind = kind, title = id, now = now, id = id)
        .copy(orderIndex = order)

    private fun ids(vararg items: Item): Set<String> = items.map { it.id }.toSet()

    @Test
    fun `工作区分区在前 未分组在后`() {
        val w = root("w", ItemKind.WORKSPACE)
        val loose = root("loose")
        val outline = PlanOutline.build(listOf(loose, w), expanded = emptySet())

        assertEquals(listOf("w", PlanOutline.UNGROUPED), outline.blocks.map { it.id })
        assertEquals(listOf(false, true), outline.blocks.map { it.virtual })
    }

    @Test
    fun `工作区自己不在它的行里 否则标题下会多一条同名行`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g = child(w, "g")
        val outline = PlanOutline.build(listOf(w, g), expanded = emptySet())

        assertEquals(listOf("g"), outline.blocks.first().rows.map { it.item.id })
        assertEquals(listOf(0), outline.blocks.first().rows.map { it.depth })
    }

    @Test
    fun `工作区的顶层目标落在工作区容器里`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g = child(w, "g")
        val outline = PlanOutline.build(listOf(w, g), expanded = emptySet())

        assertEquals("w", outline.blocks.first().rows.single().dropContainer)
        assertEquals(listOf("g"), outline.dropOrderOf("w"))
    }

    @Test
    fun `未分组的根目标落在未分组容器里`() {
        val loose = root("loose")
        val outline = PlanOutline.build(listOf(loose), expanded = emptySet())

        assertEquals(PlanOutline.UNGROUPED, outline.blocks.single().rows.single().dropContainer)
        assertEquals(listOf("loose"), outline.dropOrderOf(PlanOutline.UNGROUPED))
    }

    @Test
    fun `嵌套行的容器是它的父目标`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g = child(w, "g")
        val a = child(g, "a")
        val b = child(g, "b")
        val outline = PlanOutline.build(listOf(w, g, a, b), expanded = ids(g))

        assertEquals(listOf("g", "a", "b"), outline.blocks.first().rows.map { it.item.id })
        assertEquals(listOf(0, 1, 1), outline.blocks.first().rows.map { it.depth })
        assertEquals(listOf("a", "b"), outline.dropOrderOf("g"))
        // 工作区容器里只有顶层目标，不含嵌套子项
        assertEquals(listOf("g"), outline.dropOrderOf("w"))
    }

    @Test
    fun `工作区之间排序的下标空间就是工作区顺序`() {
        val w2 = root("w2", ItemKind.WORKSPACE, order = 2.0)
        val w1 = root("w1", ItemKind.WORKSPACE, order = 1.0)
        val loose = root("loose", order = 0.0)
        val outline = PlanOutline.build(listOf(w2, w1, loose), expanded = emptySet())

        assertEquals(listOf("w1", "w2"), outline.dropOrderOf(PlanOutline.ROOT))
    }

    @Test
    fun `折叠的节点不出现在行里 也不在下标空间里`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g = child(w, "g")
        val a = child(g, "a")
        val outline = PlanOutline.build(listOf(w, g, a), expanded = emptySet())

        assertEquals(listOf("g"), outline.blocks.first().rows.map { it.item.id })
        assertEquals(emptyList<String>(), outline.dropOrderOf("g"))
    }

    @Test
    fun `待办不进投影`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g = child(w, "g")
        val task = child(w, "t", kind = ItemKind.TASK)
        val nested = child(g, "gt", kind = ItemKind.TASK)
        val outline = PlanOutline.build(listOf(w, g, task, nested), expanded = ids(g))

        assertEquals(listOf("g"), outline.blocks.first().rows.map { it.item.id })
        // 总条目只数树里真有的节点：工作区 + g（两个待办都不进树）
        assertEquals(2, outline.counts.totalNodes)
    }

    @Test
    fun `日程可以作为子节点出现`() {
        val g = root("g")
        val event = child(g, "e", kind = ItemKind.EVENT)
        val outline = PlanOutline.build(listOf(g, event), expanded = ids(g))

        assertEquals(listOf("g", "e"), outline.blocks.single().rows.map { it.item.id })
    }

    @Test
    fun `计数三个口径互不混用`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g1 = child(w, "g1")
        val g2 = child(w, "g2")
        val nested = child(g1, "n")
        val loose = root("loose")
        val outline = PlanOutline.build(listOf(w, g1, g2, nested, loose), expanded = ids(g1))

        // 顶层目标：g1、g2、loose（工作区是分区标题，不算目标）
        assertEquals(3, outline.counts.topLevelGoals)
        // 总条目：工作区 1 + g1/g2/n/loose 4
        assertEquals(5, outline.counts.totalNodes)
        // 当前渲染：g1、n、g2、loose
        assertEquals(4, outline.counts.visibleRows)
    }

    @Test
    fun `分区条目数与展开状态无关`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g = child(w, "g")
        val a = child(g, "a")
        val b = child(g, "b")

        val collapsed = PlanOutline.build(listOf(w, g, a, b), expanded = emptySet())
        val expanded = PlanOutline.build(listOf(w, g, a, b), expanded = ids(g))

        assertEquals(3, collapsed.blocks.first().nodeCount)
        assertEquals(3, expanded.blocks.first().nodeCount)
        assertEquals(1, collapsed.counts.visibleRows)
        assertEquals(3, expanded.counts.visibleRows)
    }

    @Test
    fun `孤儿条目提升为未分组里的行`() {
        val orphan = Item(
            id = "orphan",
            kind = ItemKind.GOAL,
            title = "孤儿",
            parentId = "missing",
            treePath = "/missing/orphan/",
            depth = 1,
            createdAt = now,
            updatedAt = now,
        )
        val outline = PlanOutline.build(listOf(orphan), expanded = emptySet())

        assertEquals(listOf("orphan"), outline.dropOrderOf(PlanOutline.UNGROUPED))
    }

    @Test
    fun `全部展开的 id 集合就是有子项的纲要节点`() {
        val w = root("w", ItemKind.WORKSPACE)
        val g = child(w, "g")
        val leaf = child(g, "leaf")
        val taskOnly = child(w, "t", kind = ItemKind.TASK)

        assertEquals(setOf("g"), PlanOutline.allExpandableIds(listOf(w, g, leaf, taskOnly)))
    }

    @Test
    fun `空输入返回空投影`() {
        val outline = PlanOutline.build(emptyList(), emptySet())
        assertTrue(outline.isEmpty)
        assertEquals(0, outline.counts.totalNodes)
    }

    /**
     * 核心不变量：**每个容器的可见行序，必须是库内同级序（[PlanOrder] 排序后）的子序列**。
     *
     * 拖动落点只知道「落在可见的第几行」，落库要的是「同级第几项」，这个换算成立的前提
     * 就是这条。破坏它的典型症状是「拖了没反应」或者「旁边那一项莫名跳位」。
     */
    @Test
    fun `可见行序必须是库内同级序的子序列`() {
        val w2 = root("w2", ItemKind.WORKSPACE, order = 5.0)
        val w1 = root("w1", ItemKind.WORKSPACE, order = 1.0)
        val looseB = root("looseB", order = 9.0)
        val looseA = root("looseA", order = 3.0)
        val looseTask = root("looseTask", kind = ItemKind.TASK, order = 4.0)
        val g = child(w1, "g", order = 1.0)
        val gTask = child(w1, "gTask", kind = ItemKind.TASK, order = 0.0)
        val a = child(g, "a", order = 1.0)

        val items = listOf(w2, w1, looseB, looseA, looseTask, g, gTask, a)
        val outline = PlanOutline.build(items, expanded = ids(g))

        outline.dropOrder.forEach { (container, visible) ->
            val dbParent = when (container) {
                PlanOutline.ROOT, PlanOutline.UNGROUPED -> null
                else -> container
            }
            val siblings = items
                .filter { it.parentId == dbParent }
                .sortedWith(PlanOrder.sibling)
                .map { it.id }

            val expectedVisible = siblings.filter { it in visible.toSet() }
            assertEquals("容器 $container 的可见行序必须与库内同级序一致", expectedVisible, visible)
        }
    }
}
