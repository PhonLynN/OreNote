package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.NoteBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 两处**用户报出来的**读取缺陷。
 *
 * 它们的共同点是：工具没报错、模型也照常回答，只是**答的是错的** ——
 * 说"这条笔记没有正文"、"这些待办没有截止时间"。所以只能靠测试钉住。
 */
class ItemReadFixesTest {

    private val zone = ZoneId.systemDefault()

    private fun entry(
        title: String,
        startMinute: Int? = null,
        endMinute: Int? = null,
        dueDate: LocalDate? = null,
        dueAtMillis: Long? = null,
        priority: Int = 0,
        kind: ItemKind = ItemKind.TASK,
        status: ItemStatus = ItemStatus.TODO,
    ) = AgendaEntry(
        key = "k",
        source = AgendaSource.ITEM,
        date = LocalDate.of(2026, 10, 15),
        title = title,
        startMinute = startMinute,
        endMinute = endMinute,
        itemStatus = status,
        itemKind = kind,
        dueDate = dueDate,
        dueAtMillis = dueAtMillis,
        priority = priority,
    )

    // ---------------------------------------------------------------- 截止日期

    /**
     * ⚠️ **这条是用户报的那个 bug**：待办的截止日期以前完全不输出。
     *
     * `AgendaEntry` 一直带着 `dueDate` / `dueAtMillis`，只是渲染那一步没读。
     * 模型因此分不清「这天要做」和「这天到期」。
     */
    @Test
    fun `无时刻待办要输出截止日期`() {
        val text = describeAgendaEntry(
            entry("英语作业", dueAtMillis = Instant.parse("2026-10-15T07:59:00Z").toEpochMilli()),
        )

        assertTrue("必须写出截止时间：$text", text.contains("截止"))
        assertTrue("截止日期要能看见：$text", text.contains("2026-10-15"))
        assertTrue(text.contains("英语作业"))
    }

    /** 只有 `dueDate`（没有具体时刻）时也要输出。 */
    @Test
    fun `只有日期也要输出截止`() {
        val text = describeAgendaEntry(entry("交报告", dueDate = LocalDate.of(2026, 11, 1)))

        assertTrue("必须写出截止日期：$text", text.contains("截止"))
        assertTrue(text.contains("2026-11-01"))
    }

    /** 没有截止信息的待办**不要**硬编一个出来。 */
    @Test
    fun `没有截止就不写`() {
        val text = describeAgendaEntry(entry("随便看看"))

        assertFalse("没有截止信息时不该出现「截止」：$text", text.contains("截止"))
    }

    /**
     * ⚠️ 有开始时刻的**日程**不重复写截止。
     *
     * 它的日期与时间已经在行首了，再挂一个"截止"是噪音。
     */
    @Test
    fun `有时刻的日程不重复写截止`() {
        val text = describeAgendaEntry(
            entry(
                "概率论复习",
                startMinute = 14 * 60,
                endMinute = 15 * 60,
                kind = ItemKind.EVENT,
                dueAtMillis = Instant.parse("2026-10-15T06:00:00Z").toEpochMilli(),
            ),
        )

        assertTrue("时刻要写出来：$text", text.contains("14:00–15:00"))
        assertFalse("日程不该再挂一行截止：$text", text.contains("截止"))
    }

    /** 优先级只在设过时出现 —— 每行都挂「优先级无」是噪音。 */
    @Test
    fun `优先级只在设置过时输出`() {
        assertFalse(describeAgendaEntry(entry("A")).contains("优先级"))
        assertTrue(describeAgendaEntry(entry("B", priority = 3)).contains("高优先级"))
        assertTrue(describeAgendaEntry(entry("C", priority = 1)).contains("低优先级"))
    }

    // ---------------------------------------------------------------- 笔记正文

    private val baseItem = Item(
        id = "i1",
        kind = ItemKind.TASK,
        title = "期末复习",
        status = ItemStatus.TODO,
        treePath = "i1",
        depth = 0,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun block(heading: String, body: String, order: Double) = NoteBlock(
        id = "b$order",
        ownerId = "i1",
        heading = heading,
        body = body,
        orderIndex = order,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /**
     * ⚠️ **这条是用户报的另一个 bug**：查到了笔记，却只能拿到摘要。
     *
     * 根因是上一版只读 `Item.note`，而**笔记编辑器根本不写那个字段**
     *（它只写 `note_blocks`）。于是模型拿到空串，只能说"这条笔记没有正文"。
     */
    @Test
    fun `要输出 note_blocks 的正文`() {
        val text = renderItemDetail(
            item = baseItem,
            blocks = listOf(
                block("第一章", "这是第一章的正文内容。", 0.0),
                block("第二章", "这是第二章的正文内容。", 1.0),
            ),
            checklist = emptyList(),
            attachments = emptyList(),
        )

        assertTrue("第一段标题丢了：$text", text.contains("第一章"))
        assertTrue("第一段正文丢了：$text", text.contains("这是第一章的正文内容。"))
        assertTrue("第二段也要在：$text", text.contains("这是第二章的正文内容。"))
    }

    /** 多段笔记必须**按 orderIndex 排序**输出，否则读起来是乱的。 */
    @Test
    fun `笔记按顺序输出`() {
        val text = renderItemDetail(
            item = baseItem,
            blocks = listOf(
                block("第三", "ccc", 2.0),
                block("第一", "aaa", 0.0),
                block("第二", "bbb", 1.0),
            ),
            checklist = emptyList(),
            attachments = emptyList(),
        )

        assertTrue(
            "顺序错了：$text",
            text.indexOf("aaa") < text.indexOf("bbb") && text.indexOf("bbb") < text.indexOf("ccc"),
        )
    }

    /** 空段落要跳过，不能输出一堆空行。 */
    @Test
    fun `空段落跳过`() {
        val text = renderItemDetail(
            item = baseItem,
            blocks = listOf(block("", "", 0.0), block("有内容", "正文", 1.0)),
            checklist = emptyList(),
            attachments = emptyList(),
        )

        assertTrue(text.contains("有内容"))
        assertFalse("空段落不该产生内容：$text", text.contains("\n\n\n\n"))
    }

    /** 两处都没有正文时**如实说没有** —— 不要编，也不要留空让人猜。 */
    @Test
    fun `没有正文时明确说明`() {
        val text = renderItemDetail(baseItem, emptyList(), emptyList(), emptyList())

        assertTrue("应当明确说明没有正文：$text", text.contains("没有正文"))
    }

    /** `Item.note` 仍然要输出（AI 写入工具和旧版本用的是它）。 */
    @Test
    fun `Item note 也输出`() {
        val text = renderItemDetail(
            item = baseItem.copy(note = "这是 note 字段的内容"),
            blocks = emptyList(),
            checklist = emptyList(),
            attachments = emptyList(),
        )

        assertTrue("note 字段丢了：$text", text.contains("这是 note 字段的内容"))
    }
}
