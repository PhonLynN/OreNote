package com.phonlynn.oreplan.core.todo

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动排序的落点解析与落库换算。
 *
 * 盯四件事：
 *  1. **分区**：行的上/下半 → 前/后；组行的中间 → 放进组；
 *  2. **滞回**：边界附近微抖不换落点（没有它就会反复改向 → 让位抽搐）；
 *  3. **拒绝**：组不能插到自己后代的旁边（会成环）；
 *  4. **换算**：落点 → 新序号 / 换组标志，写出来的顺序与界面呈现一致。
 */
class TodoDragDropTest {

    private val date = LocalDate.of(2026, 9, 26)

    /** 行高统一 50，从 y=0 起顺序排列 —— 与真实列表同构，便于手算边界。 */
    private fun slot(id: String, top: Float, isGroup: Boolean = false, collapsed: Boolean = false) =
        TodoDragSlot(
            id = id,
            top = top,
            height = 50f,
            depth = if (isGroup) 0 else 1,
            isGroup = isGroup,
            collapsed = collapsed,
        )

    private fun resolve(
        slots: List<TodoDragSlot>,
        pointerY: Float,
        draggedId: String = "dragged",
        draggedIsGroup: Boolean = false,
        subtree: Set<String> = emptySet(),
        previous: TodoDropResolution? = null,
    ) = resolveTodoDrop(slots, pointerY, draggedId, draggedIsGroup, subtree, previous)

    @Test
    fun `普通行上半插到之前、下半插到之后`() {
        val slots = listOf(slot("a", 0f), slot("b", 50f))
        assertEquals(TodoDropTarget.Before("b"), resolve(slots, 60f)?.target)
        assertEquals(TodoDropTarget.After("b"), resolve(slots, 90f)?.target)
    }

    @Test
    fun `组行中间放进组、上下仍插到前后`() {
        val slots = listOf(slot("g", 0f, isGroup = true), slot("t", 50f))
        // 0..50 是组行：上 30% = 0..15 → 之前；中 40% = 15..35 → 放进组；下 30% = 35..50 → 之后
        assertEquals(TodoDropTarget.Before("g"), resolve(slots, 10f)?.target)
        assertEquals(TodoDropTarget.Into("g"), resolve(slots, 25f)?.target)
        assertEquals(TodoDropTarget.After("g"), resolve(slots, 45f)?.target)
    }

    @Test
    fun `首行之上插到最前、末行之下插到最后`() {
        val slots = listOf(slot("a", 100f), slot("b", 150f))
        assertEquals(TodoDropTarget.Before("a"), resolve(slots, 20f)?.target)
        assertEquals(TodoDropTarget.After("b"), resolve(slots, 400f)?.target)
    }

    @Test
    fun `滞回：边界附近微抖保持原落点`() {
        val slots = listOf(slot("a", 0f), slot("b", 50f))
        // 第一次落在 b 的上半（插到 b 之前）
        val first = resolve(slots, 70f)
        assertEquals(TodoDropTarget.Before("b"), first?.target)
        // 指针往下挪 5px（进入 b 的下半区）：滞回 14px 内 → 不换
        val second = resolve(slots, 75f, previous = first)
        assertEquals(TodoDropTarget.Before("b"), second?.target)
        // 挪到 90px（越过边界 + 14px 以外）→ 才换成「b 之后」
        val third = resolve(slots, 96f, previous = first)
        assertEquals(TodoDropTarget.After("b"), third?.target)
    }

    @Test
    fun `组不能插到自己后代旁边`() {
        val slots = listOf(slot("g", 0f, isGroup = true), slot("child", 50f), slot("other", 100f))
        val previous = resolve(slots, 300f)  // 先有一个合法落点
        // 拖的是 g，指针落在自己的后代 child 上 → 落点不成立，保留上一个
        val out = resolve(slots, 70f, draggedId = "g", draggedIsGroup = true, subtree = setOf("g", "child"), previous = previous)
        assertEquals(previous?.target, out?.target)
    }

    @Test
    fun `换算：同层重排写出连续序号，顺序与界面一致`() {
        val shifts = planTodoDrop(
            groups = emptyList(),
            tasks = listOf(task("t1", order = 1.0), task("t2", order = 2.0), task("t3", order = 3.0)),
            draggedId = "t3",
            target = TodoDropTarget.Before("t1"),
        )
        val order = shifts.sortedBy { it.orderIndex }.map { it.itemId }
        assertEquals(listOf("t3", "t1", "t2"), order)
        // 序号重编号成 1,2,3 的整数倍
        assertEquals(listOf(1.0, 2.0, 3.0), shifts.sortedBy { it.orderIndex }.map { it.orderIndex })
        // 同层移动不该带换组标志
        assertTrue(shifts.none { it.setGroup || it.setParent })
    }

    @Test
    fun `换算：待办拖进组带 setGroup，组拖进组带 setParent`() {
        val groups = listOf(group("g1", "学习"), group("g2", "生活"))
        val tasks = listOf(task("t1", groupId = null))
        val taskInto = planTodoDrop(groups, tasks, "t1", TodoDropTarget.Into("g2"))
        val moved = taskInto.first { it.itemId == "t1" }
        assertTrue(moved.setGroup)
        assertEquals("g2", moved.groupId)

        val groupInto = planTodoDrop(groups, emptyList(), "g1", TodoDropTarget.Into("g2"))
        val movedGroup = groupInto.first { it.itemId == "g1" }
        assertTrue(movedGroup.setParent)
        assertEquals("g2", movedGroup.parentId)
    }

    @Test
    fun `换算：空数据或找不到参考行时不做任何改动`() {
        assertTrue(planTodoDrop(emptyList(), emptyList(), "x", TodoDropTarget.Before("y")).isEmpty())
        assertTrue(planTodoDrop(emptyList(), listOf(task("t1")), "t1", TodoDropTarget.Before("nope")).isEmpty())
        assertNull(resolve(emptyList(), 10f))
    }


    @Test
    fun `换算：往下拖一位是相邻交换，不是掉到末尾`() {
        val shifts = planTodoDrop(
            groups = emptyList(),
            tasks = listOf(task("t1", order = 1.0), task("t2", order = 2.0), task("t3", order = 3.0)),
            draggedId = "t1",
            target = TodoDropTarget.After("t2"),
        )
        assertEquals(listOf("t2", "t1", "t3"), shifts.sortedBy { it.orderIndex }.map { it.itemId })
    }

    @Test
    fun `换算：往下拖多位的落点也准`() {
        val shifts = planTodoDrop(
            groups = emptyList(),
            tasks = listOf(task("t1", order = 1.0), task("t2", order = 2.0), task("t3", order = 3.0)),
            draggedId = "t1",
            target = TodoDropTarget.Before("t3"),
        )
        assertEquals(listOf("t2", "t1", "t3"), shifts.sortedBy { it.orderIndex }.map { it.itemId })
    }

    @Test
    fun `换算：落点等于原位时写回的次序必须不变`() {
        val shifts = planTodoDrop(
            groups = emptyList(),
            tasks = listOf(task("t1", order = 1.0), task("t2", order = 2.0), task("t3", order = 3.0)),
            draggedId = "t1",
            target = TodoDropTarget.Before("t2"), // t1 本来就在 t2 之前 → 不该动
        )
        assertEquals(listOf("t1", "t2", "t3"), shifts.sortedBy { it.orderIndex }.map { it.itemId })
    }

    // ---------------------------------------------------------------- 造数据

    private fun task(id: String, groupId: String? = null, order: Double = 0.0) = AgendaEntry(
        key = "task:$id",
        source = AgendaSource.ITEM,
        date = date,
        title = id,
        itemId = id,
        groupId = groupId,
        orderIndex = order,
    )

    private fun group(
        id: String,
        title: String,
        parent: String? = null,
        order: Double = 0.0,
    ) = Item(
        id = id,
        kind = ItemKind.TODO_GROUP,
        title = title,
        parentId = parent,
        treePath = "/$id/",
        depth = 0,
        orderIndex = order,
        createdAt = Instant.ofEpochSecond(1_000),
        updatedAt = Instant.ofEpochSecond(1_000),
    )
}
