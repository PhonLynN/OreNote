package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlanTreeBuilderTest {

    private val now: Instant = Instant.parse("2026-09-11T04:00:00Z")

    private fun root(
        id: String,
        kind: ItemKind = ItemKind.GOAL,
        status: ItemStatus = ItemStatus.TODO,
        order: Double = 0.0,
    ): Item = Item.newRoot(kind = kind, title = id, now = now, status = status, id = id)
        .copy(orderIndex = order)

    private fun child(
        parent: Item,
        id: String,
        // 默认子节点是纲要节点（目标）：待办不进规划树，用它当默认值会让多数用例失去意义
        kind: ItemKind = ItemKind.GOAL,
        status: ItemStatus = ItemStatus.TODO,
        order: Double = 0.0,
    ): Item = Item.newChild(
        parent = parent,
        kind = kind,
        title = id,
        now = now,
        status = status,
        id = id,
    ).copy(orderIndex = order)

    @Test
    fun `空输入返回空树`() {
        val tree = PlanTreeBuilder.build(emptyList())
        assertTrue(tree.roots.isEmpty())
        assertEquals(0, tree.totalCount)
    }

    @Test
    fun `只有目标与工作区作为根节点 待办与日程都不进规划树`() {
        // 待办是不限时清单，只在今日页出现（用户明确要求）；规划页放的是纲要。
        val items = listOf(
            root("g1", ItemKind.GOAL),
            root("w1", ItemKind.WORKSPACE),
            root("t1", ItemKind.TASK),
            root("e1", ItemKind.EVENT),
        )
        val tree = PlanTreeBuilder.build(items)
        // 工作区排在最前（见 PlanOrder）：规划页把它们渲染成分区卡片，其余根条目进「未分组」，
        // 库内顺序必须与界面顺序一致，拖动落点的下标换算才不会整体错位。
        assertEquals(listOf("w1", "g1"), tree.roots.map { it.item.id })
    }

    @Test
    fun `待办作为子节点也不进规划树`() {
        val goal = root("g1")
        val task = child(goal, "t1", ItemKind.TASK)
        val tree = PlanTreeBuilder.build(listOf(goal, task))

        assertEquals(listOf("g1"), tree.roots.map { it.item.id })
        assertTrue("待办不该作为子节点出现在规划树里", tree.roots.single().children.isEmpty())
        assertEquals(1, tree.totalCount)
    }

    @Test
    fun `日程可以作为子节点出现 并带在树里`() {
        val goal = root("g1")
        val event = child(goal, "e1", ItemKind.EVENT)
        val tree = PlanTreeBuilder.build(listOf(goal, event))
        assertEquals(1, tree.roots.size)
        assertEquals(listOf("e1"), tree.roots.single().children.map { it.item.id })
    }

    @Test
    fun `按 orderIndex 排序同级`() {
        val items = listOf(
            root("b", order = 2.0),
            root("a", order = 1.0),
            root("c", order = 3.0),
        )
        assertEquals(listOf("a", "b", "c"), PlanTreeBuilder.build(items).roots.map { it.item.id })
    }

    @Test
    fun `父节点不存在的条目提升为根节点而不是被丢掉`() {
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
        val tree = PlanTreeBuilder.build(listOf(orphan))
        assertEquals(listOf("orphan"), tree.roots.map { it.item.id })
    }




    @Test
    fun `父子进度向上汇总`() {
        val goal = root("g")
        val done = child(goal, "c1", status = ItemStatus.DONE)
        val todo = child(goal, "c2")
        val tree = PlanTreeBuilder.build(listOf(goal, done, todo))
        assertEquals(0.5f, tree.roots.single().progress)
    }

    @Test
    fun `成环时不会栈溢出`() {
        // 人为构造 a -> b -> a 的环，模拟数据被写坏
        val a = Item(
            id = "a",
            kind = ItemKind.GOAL,
            title = "a",
            parentId = "b",
            treePath = "/b/a/",
            depth = 1,
            createdAt = now,
            updatedAt = now,
        )
        val b = Item(
            id = "b",
            kind = ItemKind.GOAL,
            title = "b",
            parentId = "a",
            treePath = "/a/b/",
            depth = 1,
            createdAt = now,
            updatedAt = now,
        )
        val tree = PlanTreeBuilder.build(listOf(a, b))
        // 两个都不是有效根（父节点存在），所以根为空；关键是这里必须正常返回而不是崩。
        // 计数只数树里的节点，这两个都进不了树，所以是 0 —— 与实际看到的内容一致。
        assertEquals(0, tree.roots.size)
        assertEquals(0, tree.totalCount)
    }

    @Test
    fun `自我引用的节点不会无限递归`() {
        val self = Item(
            id = "self",
            kind = ItemKind.GOAL,
            title = "self",
            parentId = "self",
            treePath = "/self/",
            depth = 0,
            createdAt = now,
            updatedAt = now,
        )
        val tree = PlanTreeBuilder.build(listOf(self))
        // 自我引用视为孤儿 -> 提升为根，且不把自己当子节点
        assertEquals(listOf("self"), tree.roots.map { it.item.id })
        assertTrue(tree.roots.single().children.isEmpty())
    }

}
