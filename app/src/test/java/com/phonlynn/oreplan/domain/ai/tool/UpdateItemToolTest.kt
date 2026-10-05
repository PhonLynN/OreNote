package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.TodoOrderChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 修改条目。
 *
 * ## 为什么这个工具值得单测
 *
 * 它最危险的失败方式是**静默的**：模型只说"改标题"，工具却把备注/地点/时刻
 * 一起清空了 —— 界面上看不出错，数据已经没了。而且模型很快会学会
 * "反正没提到就等于要清空"。
 *
 * 所以这里钉的全是**边界**：没传的字段必须原样、没变的字段不进确认列表、
 * 用户取消勾选就不许写库。
 */
class UpdateItemToolTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    // ---------------------------------------------------------------- 假件

    private class FakeItems(var item: Item?) : ItemRepository {
        var updates = 0
        override fun observeAll(): Flow<List<Item>> = flowOf(listOfNotNull(item))
        override suspend fun getAll(): List<Item> = listOfNotNull(item)
        override suspend fun getAllIncludingDeleted(): List<Item> = listOfNotNull(item)
        override fun observeChildren(parentId: String?): Flow<List<Item>> = flowOf(emptyList())
        override suspend fun getChildren(parentId: String?): List<Item> = emptyList()
        override fun observeSubtree(rootId: String): Flow<List<Item>> = flowOf(emptyList())
        override fun observeScheduledBetween(since: Instant, until: Instant): Flow<List<Item>> =
            flowOf(emptyList())

        override fun observeDueBetween(since: Instant, until: Instant): Flow<List<Item>> =
            flowOf(emptyList())

        override suspend fun getById(id: String): Item? = item?.takeIf { it.id == id }
        override suspend fun create(item: Item): String = error("用不到")
        override suspend fun update(item: Item) {
            updates++
            this.item = item
        }

        override suspend fun deleteSubtree(rootId: String) = error("用不到")
        override suspend fun reparent(itemId: String, newParentId: String?, orderIndex: Double?) =
            error("用不到")

        override suspend fun renormalizeSiblings(parentId: String?): List<Item> = error("用不到")
        override suspend fun applyTodoOrder(changes: List<TodoOrderChange>) = error("用不到")
    }

    /** 一条"什么都有"的日程 —— 字段越多，越容易看出哪个被误清空了。 */
    private fun fullEvent(): Item {
        val start = Instant.parse("2026-10-04T06:00:00Z") // 设备时区下的某个整点
        return Item.newRoot(
            kind = ItemKind.EVENT,
            title = "概率论复习",
            now = Instant.parse("2026-10-01T00:00:00Z"),
            note = "第 5 章例题",
            priority = 2,
            startAt = start,
            endAt = start.plusSeconds(90 * 60),
        ).copy(location = "图书馆 3 楼")
    }

    private fun tool(items: Item) = UpdateItemTool(FakeItems(items))

    // ---------------------------------------------------------------- 用例

    /**
     * ⚠️ **只改传了的字段，其余原样。**
     *
     * 这条错了是静默的数据丢失：界面上看不出，用户过几天才发现备注没了。
     */
    @Test
    fun `没传的字段保持原样`() = runBlocking {
        val before = fullEvent()
        val items = FakeItems(before)
        val tool = UpdateItemTool(items)

        tool.apply(JSONObject().put("id", before.id).put("title", "概率论复习 · 第 7 章"), setOf(before.id))

        val after = items.item!!
        assertEquals("概率论复习 · 第 7 章", after.title)
        assertEquals("备注被清掉了", before.note, after.note)
        assertEquals("地点被清掉了", before.location, after.location)
        assertEquals("优先级被清掉了", before.priority, after.priority)
        assertEquals("开始时刻被改动了", before.startAt, after.startAt)
        assertEquals("结束时刻被改动了", before.endAt, after.endAt)
        assertEquals("状态被改动了", before.status, after.status)
    }

    /** 只给 `start` 时要顺延原时长，而不是留一个"结束早于开始"的条目。 */
    @Test
    fun `只改开始时刻会顺延原时长`() = runBlocking {
        val before = fullEvent()
        val items = FakeItems(before)
        val tool = UpdateItemTool(items)
        val duration = before.endAt!!.epochSecond - before.startAt!!.epochSecond

        val newStart = "2026-10-04T15:00"
        tool.apply(JSONObject().put("id", before.id).put("start", newStart), setOf(before.id))

        val after = items.item!!
        assertEquals(ItemArgs.parseInstant(newStart), after.startAt)
        assertEquals(
            "结束时刻没有跟着顺延，条目会变成负时长",
            duration,
            after.endAt!!.epochSecond - after.startAt!!.epochSecond,
        )
    }

    /** `clear_time` 把条目变成无时限待办，其余字段不动。 */
    @Test
    fun `clear_time 清掉时刻但不动别的`() = runBlocking {
        val before = fullEvent()
        val items = FakeItems(before)
        val tool = UpdateItemTool(items)

        tool.apply(JSONObject().put("id", before.id).put("clear_time", true), setOf(before.id))

        val after = items.item!!
        assertNull("时刻没被清掉", after.startAt)
        assertNull(after.endAt)
        assertEquals(before.title, after.title)
        assertEquals(before.note, after.note)
        assertEquals(before.location, after.location)
    }

    /**
     * **值没变的字段不进确认列表。**
     *
     * 把没变的也列出来会让确认卡变成一堵墙，用户开始无脑点"应用" ——
     * 那正好是确认机制失效的方式。
     */
    @Test
    fun `值没变的字段不进变更列表`() = runBlocking {
        val before = fullEvent()
        val tool = tool(before)

        val changes = tool.preview(
            JSONObject()
                .put("id", before.id)
                .put("title", before.title) // 和原来一样
                .put("note", "第 5、7 章例题"), // 真的变了
        )

        val fields = changes.single().fields
        assertEquals("只该列出真正变化的字段：$fields", 1, fields.size)
        assertEquals("备注", fields[0].label)
        assertEquals(before.note, fields[0].old)
        assertEquals("第 5、7 章例题", fields[0].new)
    }

    /** 一个字段都没改时返回空 —— 上层会把它当成普通读取，不弹确认卡。 */
    @Test
    fun `没有任何改动时不产生变更记录`() = runBlocking {
        val before = fullEvent()
        val tool = tool(before)
        assertTrue(tool.preview(JSONObject().put("id", before.id)).isEmpty())
    }

    /** 用户取消勾选 → **一个字都不许写库**。 */
    @Test
    fun `用户没勾选就不写库`() = runBlocking {
        val before = fullEvent()
        val items = FakeItems(before)
        val tool = UpdateItemTool(items)

        val outcome = tool.apply(
            JSONObject().put("id", before.id).put("title", "不该生效"),
            accepted = emptySet(),
        )

        assertEquals("不该调用 update", 0, items.updates)
        assertEquals(before.title, items.item!!.title)
        assertTrue("要如实告诉模型用户取消了：${outcome.forModel}", outcome.forModel.contains("取消"))
    }

    /** 值没变的字段：`old == new`，界面靠它画「这个字段没变」。 */
    @Test
    fun `状态可以改且只列出变化`() = runBlocking {
        val before = fullEvent()
        val tool = tool(before)

        val changes = tool.preview(JSONObject().put("id", before.id).put("status", "done"))
        val field = changes.single().fields.single()

        assertEquals("状态", field.label)
        assertEquals("待办", field.old)
        assertEquals("已完成", field.new)
        assertEquals(ItemStatus.DONE, ItemArgs.parseStatus("done"))
    }

    /** id 找不到时**不报错**，返回空变更（上层当成一次失败的读取回给模型）。 */
    @Test
    fun `id 不存在时不抛异常`() = runBlocking {
        val tool = tool(fullEvent())
        assertTrue(tool.preview(JSONObject().put("id", "不存在")).isEmpty())
    }

    /** 墙上时间按**设备时区**解释 —— 用户说的"下午两点"是他手表上的两点。 */
    @Test
    fun `时间按设备时区解析`() {
        val parsed = ItemArgs.parseInstant("2026-10-04T14:00")!!
        val local = parsed.atZone(zone)
        assertEquals(14, local.hour)
        assertEquals(0, local.minute)
    }
}
