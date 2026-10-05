package com.phonlynn.oreplan.core.todo

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待办拖动的**重排**契约（不是换组）。
 *
 * 背景：2026-10-01 用户实测「拖动只能换组、无法真正换位」。当时先怀疑落点解析，
 * 但把「落点 → planTodoDrop → 写库 → 重新装配」整条链路钉成测试后发现：
 * **这一层逻辑是对的**，问题在界面层（松手时落点没传到写库）。
 *
 * 所以这个测试的作用是：把「重排」这件事锁在这里 —— 以后若有人改排序口径或落点换算，
 * 这里会先红，而不是等到真机上「拖了没反应」才发现。
 *
 * 数据形状照抄用户真实的那份：一个待办集 12345 里有 4 条（123/234/345/456），
 * 外面还有两条顶层待办（1234/235）。
 */
class TodoReorderTest {

    private val today = LocalDate.of(2026, 10, 1)

    private fun group(id: String, title: String, order: Double = 1.0) = Item(
        id = id, kind = ItemKind.TODO_GROUP, title = title,
        treePath = "/$id/", depth = 0, orderIndex = order,
        createdAt = Instant.ofEpochSecond(1_000), updatedAt = Instant.ofEpochSecond(1_000),
    )

    private fun task(id: String, groupId: String?, order: Double, priority: Int = 0) = AgendaEntry(
        key = "task:$id", source = AgendaSource.ITEM, date = today, title = id,
        itemId = id, groupId = groupId, priority = priority, orderIndex = order,
        createdAtMillis = order.toLong() * 1000,
    )

    /** 把 planTodoDrop 的产出写回数据，再按界面口径重新装配 —— 得到「用户会看到的顺序」。 */
    private fun applyAndRerender(
        groups: List<Item>,
        tasks: List<AgendaEntry>,
        changes: List<com.phonlynn.oreplan.domain.repository.TodoOrderChange>,
    ): List<String> {
        val after = tasks.map { t ->
            val c = changes.firstOrNull { it.itemId == t.itemId } ?: return@map t
            t.copy(
                orderIndex = c.orderIndex,
                groupId = if (c.setGroup) c.groupId else t.groupId,
            )
        }
        return buildTodoRows(groups, after, emptySet())
            .mapNotNull { (it as? TodoRow.TaskRow)?.task?.title }
    }

    @Test
    fun `组内重排：拖到同组另一条之后，顺序真的变`() {
        val groups = listOf(group("g1", "12345"))
        val tasks = listOf(
            task("123", "g1", 1.0),
            task("234", "g1", 2.0),
            task("345", "g1", 3.0),
            task("456", "g1", 4.0),
            task("1234", null, 5.0),
            task("235", null, 6.0),
        )
        val changes = planTodoDrop(groups, tasks, "123", TodoDropTarget.After("345"))
        assertEquals(
            listOf("234", "345", "123", "456", "1234", "235"),
            applyAndRerender(groups, tasks, changes),
        )
        // 同层重排不该动归属
        assertTrue(changes.none { it.setGroup || it.setParent })
    }

    @Test
    fun `顶层重排：拖到另一条顶层待办之后`() {
        val groups = listOf(group("g1", "12345"))
        val tasks = listOf(
            task("123", "g1", 1.0),
            task("1234", null, 5.0),
            task("235", null, 6.0),
        )
        val changes = planTodoDrop(groups, tasks, "1234", TodoDropTarget.After("235"))
        assertEquals(
            listOf("123", "235", "1234"),
            applyAndRerender(groups, tasks, changes),
        )
    }

    @Test
    fun `移出待办组：拖到顶层待办之后，归属被清掉`() {
        val groups = listOf(group("g1", "12345"))
        val tasks = listOf(
            task("123", "g1", 1.0),
            task("234", "g1", 2.0),
            task("1234", null, 5.0),
            task("235", null, 6.0),
        )
        val changes = planTodoDrop(groups, tasks, "123", TodoDropTarget.After("1234"))
        val moved = changes.first { it.itemId == "123" }
        assertTrue("移出组必须带 setGroup", moved.setGroup)
        assertEquals("移出组后 groupId 应为 null", null, moved.groupId)
    }

    @Test
    fun `移入待办组：落点 Into 之后成为该组第一个子项`() {
        val groups = listOf(group("g1", "12345"), group("g2", "学习"))
        val tasks = listOf(
            task("a1", "g1", 1.0),
            task("t1", null, 5.0),
        )
        val changes = planTodoDrop(groups, tasks, "t1", TodoDropTarget.Into("g2"))
        val moved = changes.first { it.itemId == "t1" }
        assertTrue(moved.setGroup)
        assertEquals("g2", moved.groupId)
    }

    /**
     * 落点解析：**待办行**必须能给出 Before/After（这是「重排」能成立的前提）。
     * 若这里退化成只能给出组的 Into，用户就会觉得「只能换组、不能换位」。
     */
    @Test
    fun `落点解析：待办行上半给 Before、下半给 After、组行中部给 Into`() {
        val ids = listOf("g1", "123", "234", "345", "456", "1234", "235")
        val slots = ids.mapIndexed { i, id ->
            TodoDragSlot(
                id = id,
                top = i * 131f,
                height = 131f,
                depth = if (id == "g1" || id == "1234" || id == "235") 0 else 1,
                isGroup = id == "g1",
            )
        }
        val top345 = slots.first { it.id == "345" }.top
        assertEquals(
            TodoDropTarget.Before("345"),
            resolveTodoDrop(slots, top345 + 30f, "123", false, emptySet())?.target,
        )
        assertEquals(
            TodoDropTarget.After("345"),
            resolveTodoDrop(slots, top345 + 100f, "123", false, emptySet())?.target,
        )
        assertEquals(
            TodoDropTarget.Into("g1"),
            resolveTodoDrop(slots, slots[0].top + 65f, "345", false, emptySet())?.target,
        )
    }
}
