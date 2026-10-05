package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GoalProgressTest {

    private val now: Instant = Instant.parse("2026-09-11T04:00:00Z")

    private fun goal(
        id: String,
        status: ItemStatus = ItemStatus.TODO,
        progress: Float? = null,
        parent: Item? = null,
    ): Item {
        val base = if (parent == null) {
            Item.newRoot(kind = ItemKind.GOAL, title = id, now = now, status = status, id = id)
        } else {
            Item.newChild(
                parent = parent,
                kind = ItemKind.GOAL,
                title = id,
                now = now,
                status = status,
                id = id,
            )
        }
        return base.copy(progress = progress)
    }

    @Test
    fun `没有子节点的目标完成就是 1`() {
        val leaf = goal("a", status = ItemStatus.DONE)
        assertEquals(1f, GoalProgress.rollup(listOf(leaf)).getValue("a"))
    }

    @Test
    fun `没有子节点且未完成就是 0`() {
        val leaf = goal("a")
        assertEquals(0f, GoalProgress.rollup(listOf(leaf)).getValue("a"))
    }

    @Test
    fun `叶子节点的手填进度优先于完成状态`() {
        val leaf = goal("a", progress = 0.4f)
        assertEquals(0.4f, GoalProgress.rollup(listOf(leaf)).getValue("a"))
    }

    @Test
    fun `父目标进度由子节点均值汇总 而不是手填`() {
        val parent = goal("p", progress = 0.9f)
        val done = goal("c1", status = ItemStatus.DONE, parent = parent)
        val todo = goal("c2", parent = parent)

        val result = GoalProgress.rollup(listOf(parent, done, todo))

        // 手填的 0.9 被忽略，实际是 (1 + 0) / 2
        assertEquals(0.5f, result.getValue("p"))
    }

    @Test
    fun `多层嵌套逐级向上汇总`() {
        val root = goal("root")
        val mid = goal("mid", parent = root)
        val leafDone = goal("l1", status = ItemStatus.DONE, parent = mid)
        val leafTodo = goal("l2", parent = mid)

        val result = GoalProgress.rollup(listOf(root, mid, leafDone, leafTodo))

        assertEquals(1f, result.getValue("l1"))
        assertEquals(0f, result.getValue("l2"))
        assertEquals(0.5f, result.getValue("mid"))
        assertEquals(0.5f, result.getValue("root"))
    }

    @Test
    fun `已取消的子节点不计入分母`() {
        val parent = goal("p")
        val done = goal("c1", status = ItemStatus.DONE, parent = parent)
        val cancelled = goal("c2", status = ItemStatus.CANCELLED, parent = parent)

        val result = GoalProgress.rollup(listOf(parent, done, cancelled))

        // 分母只有 1 个有效子节点，所以是 1 而不是 0.5
        assertEquals(1f, result.getValue("p"))
    }

    @Test
    fun `全部子节点被取消时父目标不除零`() {
        val parent = goal("p")
        val cancelled = goal("c1", status = ItemStatus.CANCELLED, parent = parent)
        val result = GoalProgress.rollup(listOf(parent, cancelled))
        assertEquals(0f, result.getValue("p"))
    }

    @Test
    fun `空输入返回空表`() {
        assertEquals(emptyMap<String, Float>(), GoalProgress.rollup(emptyList()))
    }

    @Test
    fun `父节点不在集合里时子节点仍被计算`() {
        val orphan = Item(
            id = "orphan",
            kind = ItemKind.GOAL,
            title = "orphan",
            status = ItemStatus.DONE,
            parentId = "missing",
            treePath = "/missing/orphan/",
            depth = 1,
            createdAt = now,
            updatedAt = now,
        )
        assertEquals(1f, GoalProgress.rollup(listOf(orphan)).getValue("orphan"))
    }

    @Test
    fun `结果被夹在 0 到 1 之间`() {
        val leaf = goal("a", progress = 3f)
        assertEquals(1f, GoalProgress.rollup(listOf(leaf)).getValue("a"))
    }
}
