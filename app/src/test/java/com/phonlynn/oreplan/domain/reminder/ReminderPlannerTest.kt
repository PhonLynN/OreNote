package com.phonlynn.oreplan.domain.reminder

import com.phonlynn.oreplan.domain.model.ExceptionAction
import com.phonlynn.oreplan.domain.model.Frequency
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import com.phonlynn.oreplan.domain.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderPlannerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-09-11T04:00:00Z")   // 北京时间 9/11 12:00

    private fun event(
        id: String,
        startAt: Instant,
        rrule: String? = null,
        status: ItemStatus = ItemStatus.TODO,
    ): Item = Item.newRoot(
        kind = ItemKind.EVENT,
        title = "事件 $id",
        now = now,
        startAt = startAt,
        endAt = startAt.plusSeconds(3600),
        rrule = rrule,
        status = status,
        id = id,
    )

    private fun reminder(id: String, itemId: String, offset: Int?, enabled: Boolean = true) =
        Reminder(id = id, itemId = itemId, triggerAt = now, offsetMinutes = offset, enabled = enabled)

    @Test
    fun `单次日程算出提前 N 分钟的触发时刻`() {
        val start = Instant.parse("2026-09-11T06:00:00Z")   // 北京时间 14:00
        val planned = ReminderPlanner.plan(
            items = listOf(event("e1", start)),
            reminders = listOf(reminder("r1", "e1", 15)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertEquals(1, planned.size)
        assertEquals(start.minusSeconds(15 * 60), planned.single().triggerAt)
    }

    @Test
    fun `已经过去的时间点不再排程`() {
        val start = Instant.parse("2026-09-11T01:00:00Z")   // 北京时间 09:00，已经过了
        val planned = ReminderPlanner.plan(
            items = listOf(event("e1", start)),
            reminders = listOf(reminder("r1", "e1", 15)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `已完成或已取消的条目不排程`() {
        val start = Instant.parse("2026-09-11T06:00:00Z")
        val planned = ReminderPlanner.plan(
            items = listOf(
                event("done", start, status = ItemStatus.DONE),
                event("cancelled", start, status = ItemStatus.CANCELLED),
            ),
            reminders = listOf(reminder("r1", "done", 15), reminder("r2", "cancelled", 15)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `关掉的提醒不排程`() {
        val start = Instant.parse("2026-09-11T06:00:00Z")
        val planned = ReminderPlanner.plan(
            items = listOf(event("e1", start)),
            reminders = listOf(reminder("r1", "e1", 15, enabled = false)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `没有开始时刻的待办不排程`() {
        val floating = Item.newRoot(kind = ItemKind.TASK, title = "待办", now = now, id = "t1")
        val planned = ReminderPlanner.plan(
            items = listOf(floating),
            reminders = listOf(reminder("r1", "t1", 15)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `条目不存在时跳过而不是崩`() {
        val planned = ReminderPlanner.plan(
            items = emptyList(),
            reminders = listOf(reminder("r1", "missing", 15)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `重复日程只排下一次未来的发生`() {
        // 每周三 20:00，锚点 9/9（周三）
        val anchor = Instant.parse("2026-09-09T12:00:00Z")   // 北京时间 20:00
        val item = event("e1", anchor, rrule = RecurrenceRule(frequency = Frequency.WEEKLY).toRRule())

        val planned = ReminderPlanner.plan(
            items = listOf(item),
            reminders = listOf(reminder("r1", "e1", 30)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        // 9/9 已过，下一次是 9/16 20:00，提前 30 分钟 => 19:30 北京时间
        val expected = Instant.parse("2026-09-16T11:30:00Z")
        assertEquals(expected, planned.single().triggerAt)
    }

    @Test
    fun `被删除的单个发生不参与排程`() {
        val anchor = Instant.parse("2026-09-09T12:00:00Z")
        val item = event("e1", anchor, rrule = RecurrenceRule(frequency = Frequency.WEEKLY).toRRule())
        val exception = RecurrenceException(
            id = "x1",
            itemId = "e1",
            date = LocalDate.of(2026, 9, 16),
            action = ExceptionAction.DELETED,
        )

        val planned = ReminderPlanner.plan(
            items = listOf(item),
            reminders = listOf(reminder("r1", "e1", 30)),
            exceptionsByItem = mapOf("e1" to listOf(exception)),
            now = now,
            zone = zone,
        )
        // 9/16 被删掉，下一次是 9/23
        assertEquals(Instant.parse("2026-09-23T11:30:00Z"), planned.single().triggerAt)
    }

    @Test
    fun `多条提醒按触发时间升序排列`() {
        val start = Instant.parse("2026-09-11T06:00:00Z")
        val planned = ReminderPlanner.plan(
            items = listOf(event("e1", start), event("e2", start.plusSeconds(7200))),
            reminders = listOf(reminder("r2", "e2", 10), reminder("r1", "e1", 15)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertEquals(listOf("r1", "r2"), planned.map { it.reminderId })
    }

    @Test
    fun `没有偏移量时按准点触发`() {
        val start = Instant.parse("2026-09-11T06:00:00Z")
        val planned = ReminderPlanner.plan(
            items = listOf(event("e1", start)),
            reminders = listOf(reminder("r1", "e1", null)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertEquals(start, planned.single().triggerAt)
    }

    @Test
    fun `带回标题与备注供通知使用`() {
        val start = Instant.parse("2026-09-11T06:00:00Z")
        val item = event("e1", start).copy(note = "记得带材料")
        val planned = ReminderPlanner.plan(
            items = listOf(item),
            reminders = listOf(reminder("r1", "e1", 5)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
        )
        assertEquals("事件 e1", planned.single().title)
        assertEquals("记得带材料", planned.single().note)
    }

    @Test
    fun `窗口之外的重复发生不会排程`() {
        val anchor = Instant.parse("2026-09-09T12:00:00Z")
        val item = event("e1", anchor, rrule = RecurrenceRule(frequency = Frequency.YEARLY).toRRule())
        val planned = ReminderPlanner.plan(
            items = listOf(item),
            reminders = listOf(reminder("r1", "e1", 30)),
            exceptionsByItem = emptyMap(),
            now = now,
            zone = zone,
            horizonDays = 60,
        )
        assertTrue(planned.isEmpty())
    }
}
