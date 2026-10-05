package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * 读取结果里的**提醒信息**。
 *
 * 用户口径：「询问某条日程有没有提醒的时候不要出 bug」。
 *
 * 在这之前，读工具**完全不输出提醒** —— 所以模型答不了这个问题，
 * 只能含糊地说"没看到提醒信息"。那不是 bug，但等于没答。
 */
class ReminderReadTest {

    private fun entry(itemId: String, title: String = "开会", startMinute: Int? = 14 * 60) =
        AgendaEntry(
            key = "k",
            source = AgendaSource.ITEM,
            date = LocalDate.of(2026, 10, 15),
            title = title,
            startMinute = startMinute,
            itemStatus = ItemStatus.TODO,
            itemKind = ItemKind.EVENT,
            itemId = itemId,
        )

    private val item = Item(
        id = "i1",
        kind = ItemKind.EVENT,
        title = "开会",
        status = ItemStatus.TODO,
        treePath = "i1",
        depth = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    // ---------------------------------------------------------------- 列表

    /** **有**提醒时列表要写出来。 */
    @Test
    fun `列表里有提醒就输出`() {
        val at = Instant.parse("2026-10-15T05:45:00Z")
        val text = describeAgendaEntry(entry("i1"), reminderAt = at)

        assertTrue("应当出现提醒时刻：$text", text.contains("🔔"))
        assertTrue("要能看见具体时间：$text", text.contains("2026-10-15"))
    }

    /**
     * ⚠️ **没有**提醒时列表里**不写**。
     *
     * 逐条挂一句"无提醒"会把整份列表淹没 —— 而"没提"在列表语境下
     * 就是"没有"的意思。
     */
    @Test
    fun `列表里没提醒就不写`() {
        val text = describeAgendaEntry(entry("i1"), reminderAt = null)

        assertFalse("不该出现提醒标记：$text", text.contains("🔔"))
    }

    /** 提醒与其它信息要能共存，不能互相吃掉。 */
    @Test
    fun `提醒与时刻截止优先级共存`() {
        val text = describeAgendaEntry(
            entry("i1", startMinute = null),
            reminderAt = Instant.parse("2026-10-15T05:45:00Z"),
        )

        assertTrue("时刻还在：$text", text.contains("无时刻"))
        assertTrue("提醒在：$text", text.contains("🔔"))
    }

    // ---------------------------------------------------------------- 详情

    /**
     * ⚠️ 详情里**没提醒也要明确写「无」**。
     *
     * 与列表刻意不同：用户问的是"这条有没有提醒"，必须给出明确答案。
     */
    @Test
    fun `详情里没提醒也明确说无`() {
        val text = renderItemDetail(item, emptyList(), emptyList(), emptyList())

        assertTrue("必须明确写出「提醒：无」：$text", text.contains("提醒：无"))
    }

    /** 详情里**有**提醒时写时刻。 */
    @Test
    fun `详情里有提醒就写时刻`() {
        val text = renderItemDetail(
            item = item,
            blocks = emptyList(),
            checklist = emptyList(),
            attachments = emptyList(),
            reminderAt = Instant.parse("2026-10-15T05:45:00Z"),
        )

        assertTrue("要写出提醒时刻：$text", text.contains("提醒："))
        assertTrue(text.contains("2026-10-15"))
        assertFalse("不该同时出现「无」", text.contains("提醒：无"))
    }
}
