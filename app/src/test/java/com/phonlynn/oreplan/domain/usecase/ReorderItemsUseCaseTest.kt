package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.repository.ItemRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * 条目清单的拖动排序。
 *
 * 三件事必须钉住：**顺序真的落库**（老数据 orderIndex 全是 0，中点法算出来也是 0，
 * 表现就是「拖了没反应」）、**只改这一屏看得见那一段的相对关系**（日历某一天只列当天几条，
 * 不能让不在这一屏的条目顺序乱掉）、以及**容器不参与**（目标/工作区的顺序归 MoveItemUseCase）。
 */
class ReorderItemsUseCaseTest {

    private val now: Instant = Instant.parse("2026-09-12T04:00:00Z")

    private fun item(
        id: String,
        kind: ItemKind = ItemKind.TASK,
        order: Double = 0.0,
        createdOffset: Long = 0,
        startAt: Instant? = null,
    ): Item = Item.newRoot(
        kind = kind,
        title = id,
        now = now.plus(Duration.ofMinutes(createdOffset)),
        id = id,
    ).copy(orderIndex = order, startAt = startAt)

    private class FakeItems(items: List<Item>) : ItemRepository {
        val stored = items.associateByTo(mutableMapOf()) { it.id }

        override fun observeAll(): Flow<List<Item>> = flowOf(stored.values.toList())
        override suspend fun getAll(): List<Item> = stored.values.toList()

        /** 排序用例不关心墓碑，两者同义。 */
        override suspend fun getAllIncludingDeleted(): List<Item> = stored.values.toList()

        override fun observeChildren(parentId: String?): Flow<List<Item>> = flowOf(emptyList())
        override suspend fun getChildren(parentId: String?): List<Item> = emptyList()
        override fun observeSubtree(rootId: String): Flow<List<Item>> = flowOf(emptyList())
        override fun observeScheduledBetween(since: Instant, until: Instant): Flow<List<Item>> =
            flowOf(emptyList())

        override fun observeDueBetween(since: Instant, until: Instant): Flow<List<Item>> =
            flowOf(emptyList())

        override suspend fun getById(id: String): Item? = stored[id]
        override suspend fun create(item: Item): String = error("用不到")
        override suspend fun update(item: Item) {
            stored[item.id] = item
        }

        override suspend fun deleteSubtree(rootId: String): Unit = error("用不到")
        override suspend fun reparent(itemId: String, newParentId: String?, orderIndex: Double?) =
            error("条目排序不该改父节点")

        override suspend fun renormalizeSiblings(parentId: String?): List<Item> = error("用不到")
        override suspend fun applyTodoOrder(changes: List<com.phonlynn.oreplan.domain.repository.TodoOrderChange>) = error("用不到")

        /** 按现有顺序给出标题（含所有条目，便于看清谁被重编号了）。 */
        fun order(): List<String> =
            stored.values.sortedWith(compareBy({ it.orderIndex }, { it.createdAt }, { it.title }))
                .map { it.title }
    }

    private fun itemsOf(vararg ids: String): FakeItems =
        FakeItems(ids.map { item(it) })

    @Test
    fun `全部为默认排序键时拖动依然生效`() = runTest {
        // 老数据：orderIndex 全是 0，按创建时间排
        val items = FakeItems(
            listOf(
                item("a", createdOffset = 0),
                item("b", createdOffset = 10),
                item("c", createdOffset = 20),
            ),
        )
        assertEquals(listOf("a", "b", "c"), items.order())

        // 把 c 拖到最前
        ReorderItemsUseCase(items)("c", listOf("a", "b"), 0)

        assertEquals(listOf("c", "a", "b"), items.order())
    }

    @Test
    fun `追加到末尾`() = runTest {
        val items = itemsOf("a", "b", "c")
        ReorderItemsUseCase(items)("a", listOf("b", "c"), 2)
        assertEquals(listOf("b", "c", "a"), items.order())
    }

    @Test
    fun `拖到中间`() = runTest {
        val items = itemsOf("a", "b", "c")
        ReorderItemsUseCase(items)("c", listOf("a", "b"), 1)
        assertEquals(listOf("a", "c", "b"), items.order())
    }

    @Test
    fun `可见的只是完整清单的一部分时 不在这一屏的项顺序不变`() = runTest {
        // 完整清单 a b c d e；日历某一天只列 b 与 d
        val items = itemsOf("a", "b", "c", "d", "e")
        ReorderItemsUseCase(items)("d", listOf("b"), 0)

        assertEquals(listOf("a", "d", "b", "c", "e"), items.order())
    }

    /** 用户 2026-09-12 选定「整份我自己排」：有具体时刻的日程也要能拖。 */
    @Test
    fun `有时刻的日程也参与排序`() = runTest {
        val scheduled = item("scheduled", kind = ItemKind.EVENT, createdOffset = 5, startAt = now)
        val items = FakeItems(listOf(item("a"), scheduled, item("b")))

        // 日程排在 b 之前
        ReorderItemsUseCase(items)("scheduled", listOf("a", "b"), 1)

        assertEquals(listOf("a", "scheduled", "b"), items.order())
    }

    @Test
    fun `容器不参与条目排序`() = runTest {
        val goal = item("goal", kind = ItemKind.GOAL, createdOffset = 5)
        val workspace = item("ws", kind = ItemKind.WORKSPACE, createdOffset = 6)
        val items = FakeItems(listOf(item("a"), goal, workspace, item("b")))

        ReorderItemsUseCase(items)("b", listOf("a"), 0)

        // 目标与工作区的排序键不动：它们的顺序归 MoveItemUseCase 管
        assertEquals(0.0, items.stored.getValue("goal").orderIndex, 0.0)
        assertEquals(0.0, items.stored.getValue("ws").orderIndex, 0.0)
        assertEquals(listOf("goal", "ws", "b", "a"), items.order())
    }

    @Test
    fun `已取消的条目不参与排序`() = runTest {
        val cancelled = item("cancelled", createdOffset = 5).copy(status = ItemStatus.CANCELLED)
        val items = FakeItems(listOf(item("a"), cancelled, item("b")))

        ReorderItemsUseCase(items)("b", listOf("a"), 0)

        assertEquals(0.0, items.stored.getValue("cancelled").orderIndex, 0.0)
    }

    @Test
    fun `拖动之后排序键是均匀分布的 不会挤成同一个值`() = runTest {
        val items = itemsOf("a", "b", "c")
        ReorderItemsUseCase(items)("c", listOf("a", "b"), 0)

        val keys = items.order().map { title -> items.stored.getValue(title).orderIndex }
        assertEquals(3, keys.distinct().size)
        assertEquals(keys.sorted(), keys)
    }

    @Test
    fun `找不到被拖动的条目时什么都不做`() = runTest {
        val items = itemsOf("a", "b")
        ReorderItemsUseCase(items)("missing", listOf("a", "b"), 0)
        assertEquals(listOf("a", "b"), items.order())
    }
}
