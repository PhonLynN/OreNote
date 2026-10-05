package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 「当天清单」的显示顺序。
 *
 * 这份清单是可以拖动排序的，所以它的顺序不是「怎么好看怎么来」，而是**契约**：
 * 界面按这个顺序渲染、拖动落点也按这个顺序换算下标，两处必须一致（见 `ReorderItemsUseCase`）。
 */
class DayListOrderTest {

    private val date: LocalDate = LocalDate.of(2026, 9, 12)

    private fun course(id: String, start: Int, end: Int = start + 90): AgendaEntry = AgendaEntry(
        key = "course:$id",
        source = AgendaSource.COURSE,
        date = date,
        title = id,
        startMinute = start,
        endMinute = end,
    )

    private fun event(id: String, start: Int, order: Double = 0.0, created: Long = 0): AgendaEntry =
        AgendaEntry(
            key = "item:$id",
            source = AgendaSource.ITEM,
            date = date,
            title = id,
            startMinute = start,
            endMinute = start + 60,
            itemId = id,
            itemKind = ItemKind.EVENT,
            orderIndex = order,
            createdAtMillis = created,
        )

    private fun task(id: String, order: Double = 0.0, created: Long = 0): AgendaEntry = AgendaEntry(
        key = "due:$id",
        source = AgendaSource.ITEM,
        date = date,
        title = id,
        itemId = id,
        itemKind = ItemKind.TASK,
        orderIndex = order,
        createdAtMillis = created,
    )

    @Test
    fun `课程在前按时间 条目在后按手动顺序`() {
        val before = listOf(
            event("日程B", start = 600, order = 2048.0),
            course("军理课", start = 480),
            event("日程A", start = 540, order = 1024.0),
            course("军训", start = 300),
        )

        assertEquals(
            listOf("军训", "军理课", "日程A", "日程B"),
            dayListOrder(before).map { it.title },
        )
    }

    @Test
    fun `有序时刻的日程与无时刻待办同一套手动顺序`() {
        val entries = listOf(
            task("待办", order = 3072.0),
            event("日程", start = 540, order = 1024.0),
        )
        assertEquals(listOf("日程", "待办"), dayListOrder(entries).map { it.title })
    }

    @Test
    fun `排序键相同时用创建时间再按标题 保证与库里的顺序一致`() {
        // 老数据：orderIndex 全是 0，此时必须退到创建时间，否则界面顺序与库里顺序不一致
        val entries = listOf(
            event("晚创建的", start = 540, order = 0.0, created = 2000),
            event("早创建的", start = 540, order = 0.0, created = 1000),
        )
        assertEquals(listOf("早创建的", "晚创建的"), dayListOrder(entries).map { it.title })
    }

    @Test
    fun `只有课程时按开始时间排`() {
        val entries = listOf(course("第三节", 600), course("第一节", 480))
        assertEquals(listOf("第一节", "第三节"), dayListOrder(entries).map { it.title })
    }

    @Test
    fun `空输入返回空`() {
        assertEquals(emptyList<AgendaEntry>(), dayListOrder(emptyList()))
    }

    @Test
    fun `不丢项也不重复`() {
        val entries = listOf(
            course("课", 480),
            event("日程", 540, order = 1024.0),
            task("待办", order = 2048.0),
        )
        val ordered = dayListOrder(entries)
        assertEquals(entries.size, ordered.size)
        assertEquals(entries.map { it.key }.toSet(), ordered.map { it.key }.toSet())
    }
}
