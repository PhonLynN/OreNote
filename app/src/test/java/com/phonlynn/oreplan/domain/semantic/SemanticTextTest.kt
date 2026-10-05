package com.phonlynn.oreplan.domain.semantic

import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.ChecklistCategory
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.model.StepOrderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 语义层（「AI 读取舒适」那条标准的验收）。
 *
 * 最要紧的一条是 [输出里不含零宽字符与缩进符] —— 那是硬性要求：
 * AI 读到的必须是干净文本，而不是带隐形结构标记的串。
 */
class SemanticTextTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-09-30 14:00（东八区）。 */
    private val now: Instant = Instant.parse("2026-09-30T06:00:00Z")

    /** 富文本内核用来编码结构的隐形字符，一个都不许出现在给 AI 的文本里。 */
    private val forbidden = listOf('\u200B', '\u200C', '\u200D', '\u2060', '\u3000', '\u2002')

    private fun assertNoInvisible(text: String) {
        forbidden.forEach { ch ->
            assertFalse(
                "输出里出现了隐形字符 U+${ch.code.toString(16).uppercase()}：" +
                    text.replace("\u200B", "<ZWSP>").replace("\u3000", "<IDEO_SP>"),
                text.contains(ch),
            )
        }
    }

    // ---------------------------------------------------------------- 硬性要求

    @Test
    fun `输出里不含零宽字符与缩进符`() {
        // 正文里混进富文本的标记字符与全角缩进（模拟历史脏数据 + 富文本编码）
        val dirty = "正文开头\u200B列表项\u200C有序\u200D未勾选\u2060已勾选\u3000缩进\u2002再缩进"

        val item = Item.newRoot(
            kind = ItemKind.TASK,
            title = "标题也\u200B脏了",
            now = now,
            note = dirty,
        )
        val card = BoardCard(
            id = "c1",
            type = BoardCardType.QUICK,
            title = "卡片\u3000标题",
            body = dirty,
            createdAt = now,
            updatedAt = now,
        )

        assertNoInvisible(SemanticText.renderItem(item, zone)!!)
        assertNoInvisible(SemanticText.renderCard(card, zone))
        assertNoInvisible(
            SemanticText.renderItemList(listOf(item), "今天", zone),
        )
        assertNoInvisible(
            SemanticText.renderItem(
                item,
                zone,
                noteBlocks = listOf(
                    NoteBlock("nb", "i", "小节\u200B头", dirty, 0.0, now, now),
                ),
            )!!,
        )
    }

    // ---------------------------------------------------------------- 内部载体

    /** `HABIT_ALARM` 是提醒管线的实现细节，绝不能出现在给 AI 的文本里（do-not-touch A2）。 */
    @Test
    fun `内部载体不进入输出`() {
        val carrier = Item.newRoot(
            kind = ItemKind.HABIT_ALARM,
            title = "内部提醒载体",
            now = now,
        )

        assertNull("内部载体不该被单独渲染", SemanticText.renderItem(carrier, zone))
        assertTrue(SemanticText.isInternalCarrier(carrier))

        val list = SemanticText.renderItemList(
            listOf(carrier, Item.newRoot(ItemKind.TASK, "正常待办", now)),
            "今天",
            zone,
        )
        assertFalse("内部载体泄漏进了列表", list.contains("内部提醒载体"))
        assertTrue(list.contains("正常待办"))
    }

    // ---------------------------------------------------------------- 保密卡

    /** 保密是用户的显式意图：语义层不能绕过它把正文喂给 AI。 */
    @Test
    fun `保密卡片不输出正文`() {
        val card = BoardCard(
            id = "c1",
            type = BoardCardType.QUICK,
            title = "私密",
            body = "这是不该给 AI 看的内容",
            secret = true,
            secretHint = "暗号",
            createdAt = now,
            updatedAt = now,
        )

        val text = SemanticText.renderCard(card, zone)

        assertFalse("保密卡正文泄漏了", text.contains("不该给 AI 看"))
        assertTrue(text.contains("保密"))
    }

    // ---------------------------------------------------------------- 条目渲染

    @Test
    fun `日程渲染出时间 地点 标签与清单`() {
        val item = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "小组讨论",
            now = now,
            startAt = now,
            endAt = now.plusSeconds(5400),
            id = "i1",
        ).copy(location = "A301")

        val text = SemanticText.renderItem(
            item,
            zone,
            tags = listOf("学习"),
            checklist = listOf(
                ChecklistEntry(
                    id = "ck1", itemId = "i1", category = ChecklistCategory.MATERIAL,
                    title = "打印讨论稿", done = true, orderIndex = 1.0,
                    createdAt = now, updatedAt = now,
                ),
                ChecklistEntry(
                    id = "ck2", itemId = "i1", category = ChecklistCategory.CARRY,
                    title = "学生证", done = false, orderIndex = 2.0,
                    createdAt = now, updatedAt = now,
                ),
            ),
            attachments = listOf(
                Attachment(
                    id = "a1", ownerType = AttachmentOwner.ITEM, ownerId = "i1",
                    displayName = "讨论稿.pdf", mimeType = "application/pdf",
                    sizeBytes = 100L, storedPath = "p", createdAt = now,
                ),
            ),
        )!!

        assertTrue(text.contains("【日程】小组讨论"))
        assertTrue("同一天的时间段应当合并显示：$text", text.contains("2026-09-30 14:00–15:30"))
        assertTrue(text.contains("地点：A301"))
        assertTrue(text.contains("标签：学习"))
        assertTrue(text.contains("- [x] ［材料］打印讨论稿"))
        assertTrue(text.contains("- [ ] ［携带］学生证"))
        assertTrue(text.contains("附件（1）：讨论稿.pdf"))
        assertNoInvisible(text)
    }

    @Test
    fun `跨天的时间段两端都写全`() {
        val item = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "夜班",
            now = now,
            startAt = now,
            endAt = now.plusSeconds(20 * 3600),
            id = "i2",
        )

        val text = SemanticText.renderItem(item, zone)!!

        assertTrue("跨天时段应当两端写全：$text", text.contains("2026-09-30 14:00 – 2026-10-01 10:00"))
    }

    @Test
    fun `数量目标渲染出目标值与单位`() {
        val goal = Item.newRoot(
            kind = ItemKind.GOAL,
            title = "读完 6 本书",
            now = now,
            id = "g1",
        ).copy(
            goalType = GoalType.QUANTITY,
            unit = "本",
            targetValue = 6L,
            progress = 0.5f,
        )

        val text = SemanticText.renderItem(goal, zone)!!

        assertTrue(text.contains("【目标】读完 6 本书"))
        assertTrue(text.contains("目标类型：数量"))
        assertTrue(text.contains("目标值：6本"))
        assertTrue(text.contains("进度：50%"))
    }

    @Test
    fun `分步目标渲染出推进方式`() {
        val goal = Item.newRoot(ItemKind.GOAL, "分步目标", now, id = "g2").copy(
            goalType = GoalType.STEP,
            stepOrderMode = StepOrderMode.SEQ,
            autoAdvance = true,
        )

        val text = SemanticText.renderItem(goal, zone)!!

        assertTrue(text.contains("推进方式：按顺序解锁"))
        assertTrue(text.contains("完成后自动推进"))
    }

    @Test
    fun `提醒与计划跨度都能读出来`() {
        val item = Item.newRoot(
            kind = ItemKind.TASK,
            title = "写周报",
            now = now,
            softDueAt = now.plusSeconds(86400),
            planStartDay = LocalDate.of(2026, 9, 28),
            planEndDay = LocalDate.of(2026, 10, 4),
            id = "i3",
        ).copy(status = ItemStatus.DOING, priority = 2)

        val text = SemanticText.renderItem(
            item,
            zone,
            reminders = listOf(
                Reminder("r1", "i3", now, offsetMinutes = 15, enabled = true),
                Reminder("r2", "i3", now, offsetMinutes = 0, enabled = false),
            ),
        )!!

        assertTrue(text.contains("状态：进行中 · 优先级：中"))
        assertTrue(text.contains("期望完成：2026-10-01 14:00"))
        assertTrue(text.contains("计划跨度：2026-09-28 → 2026-10-04"))
        assertTrue(text.contains("提前 15 分钟"))
        assertFalse("停用的提醒不该出现", text.contains("提前 0"))
    }

    // ---------------------------------------------------------------- 列表

    @Test
    fun `空列表给出明确的一句话而不是空字符串`() {
        val text = SemanticText.renderItemList(emptyList(), "今天", zone)

        assertTrue(text.contains("# 今天（0 项）"))
        assertTrue("空结果必须说清楚，否则 AI 会以为工具坏了", text.contains("没有条目"))
    }

    @Test
    fun `列表里每一项都编号且缩进续行`() {
        val items = listOf(
            Item.newRoot(ItemKind.TASK, "第一件", now, note = "备注"),
            Item.newRoot(ItemKind.TASK, "第二件", now),
        )

        val text = SemanticText.renderItemList(items, "今天", zone)

        assertTrue(text.contains("# 今天（2 项）"))
        assertTrue(text.contains("1. 【待办】第一件"))
        assertTrue(text.contains("2. 【待办】第二件"))
    }

    // ---------------------------------------------------------------- 扩展抽屉（可扩展性的体现）

    /**
     * 抽屉的渲染是**通用的**：语义层不认得任何具体键名。
     * 所以「明天加一个新字段」不需要改语义层一行代码 —— 这正是可扩展性标准要的效果。
     */
    @Test
    fun `扩展抽屉按通用方式渲染 无需为新键改代码`() {
        val ext = ExtMap.EMPTY
            .putText("weather.sky", "多云")
            .putNum("mood.score", 4.0)
            .putFlag("mood.private", true)

        val item = Item.newRoot(ItemKind.TASK, "带抽屉的待办", now, id = "i9")
        val text = SemanticText.renderItem(item, zone, ext = ext)!!

        assertTrue(text.contains("扩展信息："))
        assertTrue(text.contains("mood.private：是"))
        assertTrue(text.contains("mood.score：4"))
        assertTrue(text.contains("weather.sky：多云"))
        assertNoInvisible(text)
    }

    /** 输出顺序稳定：同样的抽屉永远产生同样的文本，缓存与比对才不会抖。 */
    @Test
    fun `扩展抽屉的渲染顺序稳定`() {
        val a = ExtMap.EMPTY.putText("z", "1").putText("a", "2")
        val b = ExtMap.EMPTY.putText("a", "2").putText("z", "1")

        assertEquals(SemanticText.renderExtLines(a), SemanticText.renderExtLines(b))
    }

    @Test
    fun `空抽屉不产生任何行`() {
        assertTrue(SemanticText.renderExtLines(ExtMap.EMPTY).isEmpty())
    }

    /** 整数不该渲染成 `4.0`。 */
    @Test
    fun `抽屉里的整数不带小数点`() {
        val lines = SemanticText.renderExtLines(ExtMap.EMPTY.putNum("mood.score", 4.0))
        assertTrue(lines.toString(), lines.any { it.endsWith("：4") })
    }

    // ---------------------------------------------------------------- 白板卡片

    @Test
    fun `卡片渲染出标题 标签 与清单`() {
        val card = BoardCard(
            id = "c1",
            type = BoardCardType.TODO,
            title = "购物",
            body = "正文内容",
            createdAt = now,
            updatedAt = now,
        )

        val text = SemanticText.renderCard(
            card,
            zone,
            todoItems = listOf(
                BoardTodoItem("t1", "c1", "牛奶", done = true),
                BoardTodoItem("t2", "c1", "面包", done = false),
            ),
            tagNames = listOf("生活"),
        )

        assertTrue(text.contains("【卡片·待办】购物"))
        assertTrue(text.contains("标签：生活"))
        assertTrue(text.contains("正文内容"))
        assertTrue(text.contains("- [x] 牛奶"))
        assertTrue(text.contains("- [ ] 面包"))
        assertNoInvisible(text)
    }

    /** 卡片没有标题时，用正文首行做标识 —— 必须先转纯文本，不能截断原始编码串。 */
    @Test
    fun `无标题卡片用正文首行做标识`() {
        val card = BoardCard(
            id = "c1",
            type = BoardCardType.QUICK,
            title = null,
            body = "第一行内容\n第二行内容",
            createdAt = now,
            updatedAt = now,
        )

        val text = SemanticText.renderCard(card, zone)

        assertTrue(text.contains("第一行内容"))
        assertFalse("不该把富文本编码串直接暴露出来", text.contains("{\"v\""))
    }

    // ---------------------------------------------------------------- 专注

    @Test
    fun `一次专注渲染成一行`() {
        val session = FocusSession(
            id = "f1",
            startedAt = now,
            endedAt = now.plusSeconds(1500),
            minutes = 25,
            kind = FocusKind.POMODORO,
            plannedMinutes = 25,
            completed = true,
            label = "写周报",
            itemId = "i3",
            createdAt = now,
        )

        val text = SemanticText.renderFocus(session, zone)

        assertTrue(text.contains("2026-09-30 14:00"))
        assertTrue(text.contains("写周报"))
        assertTrue(text.contains("番茄"))
        assertTrue(text.contains("25/25 分钟"))
        assertTrue(text.contains("已完成"))
        assertTrue("关联条目是数据链的关键，必须出现", text.contains("关联条目：i3"))
    }

    @Test
    fun `专注统计汇总总时长与完成情况`() {
        val sessions = listOf(
            focus(minutes = 25, completed = true, kind = FocusKind.POMODORO, at = now),
            focus(minutes = 25, completed = true, kind = FocusKind.POMODORO, at = now.plusSeconds(3600)),
            focus(minutes = 10, completed = false, kind = FocusKind.COUNT_DOWN, at = now.plusSeconds(7200)),
        )

        val text = SemanticText.renderFocusSummary(sessions, zone = zone)

        assertTrue(text.contains("共 3 次，合计 1 小时"))
        assertTrue(text.contains("走完 2 次"))
        assertTrue(text.contains("中途结束 1 次"))
        assertTrue(text.contains("番茄：2 次"))
    }

    @Test
    fun `没有专注记录时给出明确的一句话`() {
        val text = SemanticText.renderFocusSummary(emptyList(), zone = zone)
        assertTrue(text.contains("没有专注记录"))
    }

    private fun focus(
        minutes: Int,
        completed: Boolean,
        kind: FocusKind,
        at: Instant,
    ) = FocusSession(
        id = "f-$at-$kind",
        startedAt = at,
        endedAt = at.plusSeconds(minutes * 60L),
        minutes = minutes,
        kind = kind,
        completed = completed,
        createdAt = at,
    )
}
