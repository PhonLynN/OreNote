package com.phonlynn.oreplan.domain.backup

import com.phonlynn.oreplan.core.time.WeekParity
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AutoPinRule
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.Frequency
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.ChecklistCategory
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.ExceptionAction
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.model.Tag
import com.phonlynn.oreplan.domain.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class BackupCodecTest {

    private val now: Instant = Instant.parse("2026-09-11T04:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 9, 11)

    private fun sample(): BackupSnapshot {
        val goal = Item.newRoot(
            kind = ItemKind.GOAL,
            title = "读完 6 本书",
            now = now,
            planStartDay = LocalDate.of(2026, 9, 7),
            planEndDay = LocalDate.of(2027, 1, 10),
            id = "i-goal",
        )
        val child = Item.newChild(
            parent = goal,
            kind = ItemKind.TASK,
            title = "《人类简史》",
            now = now,
            status = ItemStatus.DONE,
            id = "i-child",
        )

        return BackupSnapshot(
            exportedAt = now,
            terms = listOf(
                Term(
                    id = "t1",
                    name = "2026 秋季学期",
                    startDate = LocalDate.of(2026, 9, 7),
                    totalWeeks = 18,
                    isActive = true,
                ),
            ),
            courses = listOf(
                Course(
                    id = "c1",
                    name = "高等数学",
                    teacher = "王建国",
                    defaultLocation = "A301",
                    colorHex = "#3B6FE0",
                    note = null,
                ),
            ),
            courseSessions = listOf(
                CourseSession(
                    id = "s1",
                    courseId = "c1",
                    dayOfWeek = 1,
                    startMinuteOfDay = 480,
                    endMinuteOfDay = 580,
                    startWeek = 1,
                    endWeek = Int.MAX_VALUE,
                    parity = WeekParity.ODD,
                    location = "A301",
                    note = null,
                ),
            ),
            items = listOf(
                goal,
                child,
                Item.newRoot(
                    kind = ItemKind.EVENT,
                    title = "小组讨论",
                    now = now,
                    startAt = Instant.parse("2026-09-11T06:00:00Z"),
                    endAt = Instant.parse("2026-09-11T07:30:00Z"),
                    rrule = "FREQ=WEEKLY;BYDAY=FR",
                    rruleUntil = Instant.parse("2026-12-31T00:00:00Z"),
                    id = "i-event",
                ).copy(
                    note = "记得带材料",
                    priority = 2,
                    softDueAt = Instant.parse("2026-09-12T00:00:00Z"),
                    progress = 0.25f,
                    colorTag = "#12A08C",
                    orderIndex = 1.5,
                ),
            ),
            tags = listOf(Tag(id = "tag1", name = "学习", colorHex = "#3B6FE0")),
            itemTags = listOf(ItemTagLink(itemId = "i-event", tagId = "tag1")),
            reminders = listOf(
                Reminder(
                    id = "r1",
                    itemId = "i-event",
                    triggerAt = Instant.parse("2026-09-11T05:45:00Z"),
                    offsetMinutes = 15,
                    enabled = true,
                ),
                Reminder(
                    id = "r2",
                    itemId = "i-child",
                    triggerAt = Instant.parse("2026-09-11T05:00:00Z"),
                    offsetMinutes = null,
                    enabled = false,
                ),
            ),
            recurrenceExceptions = listOf(
                RecurrenceException(
                    id = "x1",
                    itemId = "i-event",
                    date = LocalDate.of(2026, 9, 18),
                    action = ExceptionAction.OVERRIDDEN,
                    overrideStartAt = Instant.parse("2026-09-19T06:00:00Z"),
                    overrideEndAt = null,
                    overrideTitle = "小组讨论（改期）",
                ),
                RecurrenceException(
                    id = "x2",
                    itemId = "i-event",
                    date = LocalDate.of(2026, 9, 25),
                    action = ExceptionAction.DELETED,
                ),
            ),
            noteBlocks = listOf(
                NoteBlock(
                    id = "nb1",
                    ownerId = "i-goal",
                    heading = "为什么定这个目标",
                    body = "想把历史类打通，先啃《人类简史》。",
                    orderIndex = 1024.0,
                    createdAt = now,
                    updatedAt = now,
                ),
                NoteBlock(
                    id = "nb2",
                    ownerId = "i-goal",
                    heading = "注意点",
                    body = "每周至少留三个晚上，别堆到周末。",
                    orderIndex = 2048.0,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            checklistEntries = listOf(
                ChecklistEntry(
                    id = "ck1",
                    itemId = "i-event",
                    category = ChecklistCategory.MATERIAL,
                    title = "打印小组讨论稿",
                    done = true,
                    orderIndex = 1024.0,
                    createdAt = now,
                    updatedAt = now,
                ),
                ChecklistEntry(
                    id = "ck2",
                    itemId = "i-event",
                    category = ChecklistCategory.CARRY,
                    title = "学生证",
                    done = false,
                    orderIndex = 2048.0,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            attachments = listOf(
                Attachment(
                    id = "at1",
                    ownerType = AttachmentOwner.CHECKLIST,
                    ownerId = "ck1",
                    displayName = "讨论稿.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 20480L,
                    storedPath = "attachments/at1/讨论稿.pdf",
                    createdAt = now,
                ),
            ),
        )
    }

    @Test
    fun `导出再导入得到完全一致的数据`() {
        val original = sample()
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original.copy(exportedAt = restored.exportedAt), restored)
    }

    @Test
    fun `往返保留可空字段的 null`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sample()))
        // 课程 note 为 null、提醒 offsetMinutes 有一条为 null
        assertEquals(null, restored.courses.single().note)
        assertEquals(null, restored.reminders.first { it.id == "r2" }.offsetMinutes)
        assertEquals(null, restored.recurrenceExceptions.first { it.id == "x2" }.overrideStartAt)
    }

    @Test
    fun `往返保留结束周为无穷大的时段`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sample()))
        assertEquals(Int.MAX_VALUE, restored.courseSessions.single().endWeek)
    }

    @Test
    fun `往返保留浮点进度与排序键`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sample()))
        val event = restored.items.first { it.id == "i-event" }
        assertEquals(0.25f, event.progress)
        assertEquals(1.5, event.orderIndex, 0.0001)
        assertEquals(2, event.priority)
    }

    @Test
    fun `往返保留树的父子关系与路径`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sample()))
        val child = restored.items.first { it.id == "i-child" }
        assertEquals("i-goal", child.parentId)
        assertEquals("/i-goal/i-child/", child.treePath)
        assertEquals(1, child.depth)
    }

    @Test
    fun `空快照也能正常往返`() {
        val empty = BackupSnapshot(exportedAt = now)
        val restored = BackupCodec.decode(BackupCodec.encode(empty))
        assertEquals(0, restored.totalRows)
        assertEquals(BackupCodec.FORMAT_VERSION, restored.formatVersion)
    }

    @Test
    fun `条数统计正确`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sample()))
        // terms1 + courses1 + sessions1 + items3 + tags1 + itemTags1 + reminders2 + exceptions2
        // + noteBlocks2 + checklistEntries2 + attachments1
        assertEquals(17, restored.totalRows)
    }

    @Test
    fun `往返保留笔记小节与备忘清单`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sample()))
        val blocks = restored.noteBlocks.filter { it.ownerId == "i-goal" }.sortedBy { it.orderIndex }
        assertEquals(listOf("为什么定这个目标", "注意点"), blocks.map { it.heading })
        assertEquals("想把历史类打通，先啃《人类简史》。", blocks.first().body)

        val carry = restored.checklistEntries.first { it.id == "ck2" }
        assertEquals(ChecklistCategory.CARRY, carry.category)
        assertEquals(false, carry.done)
        assertEquals("i-event", carry.itemId)
    }

    @Test
    fun `往返保留附件索引但不含二进制`() {
        val restored = BackupCodec.decode(BackupCodec.encode(sample()))
        val attachment = restored.attachments.single()
        assertEquals(AttachmentOwner.CHECKLIST, attachment.ownerType)
        assertEquals("ck1", attachment.ownerId)
        assertEquals("attachments/at1/讨论稿.pdf", attachment.storedPath)
        assertEquals(20480L, attachment.sizeBytes)
    }

    /**
     * v1 的备份里没有三组新数组。它们是可选的，导入时应当按空处理，
     * 而不是报「文件损坏」把用户挡在外面。
     */
    @Test
    fun `v1 旧备份缺少新数组时按空处理`() {
        val v1 = """
            {
              "formatVersion": 1,
              "exportedAt": 1789000000000,
              "terms": [], "courses": [], "courseSessions": [],
              "items": [], "tags": [], "itemTags": [],
              "reminders": [], "recurrenceExceptions": []
            }
        """.trimIndent()

        val restored = BackupCodec.decode(v1)
        assertEquals(1, restored.formatVersion)
        assertEquals(0, restored.noteBlocks.size)
        assertEquals(0, restored.checklistEntries.size)
        assertEquals(0, restored.attachments.size)
    }

    @Test
    fun `非法 JSON 抛出可读异常而不是静默返回空`() {
        val failure = runCatching { BackupCodec.decode("这不是 json") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `缺少格式版本时拒绝导入`() {
        val failure = runCatching { BackupCodec.decode("""{"items":[]}""") }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `备份来自更新版本时拒绝导入 并说明原因`() {
        val future = """{"formatVersion":${BackupCodec.FORMAT_VERSION + 1},"items":[]}"""
        val failure = runCatching { BackupCodec.decode(future) }
        assertTrue(failure.isFailure)
        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("v${BackupCodec.FORMAT_VERSION + 1}"))
    }

    @Test
    fun `损坏的单行不会让整个文件变成空快照`() {
        // 数组里混进一个非对象元素：该元素被跳过，其余数据仍然可用
        val json = """
            {
              "formatVersion": 1,
              "exportedAt": 0,
              "tags": [ {"id":"tag1","name":"学习","colorHex":null}, "坏数据" ]
            }
        """.trimIndent()
        val restored = BackupCodec.decode(json)
        assertEquals(1, restored.tags.size)
        assertEquals("学习", restored.tags.single().name)
    }

    // ---------------------------------------------------------------- 动态置顶（v9）

    private fun cardWith(rule: AutoPinRule?, resolvedAt: Instant? = null) = BoardCard(
        id = "card-1",
        type = BoardCardType.QUICK,
        title = "动态置顶卡",
        createdAt = now,
        updatedAt = now,
        autoPin = rule,
        autoPinResolvedAt = resolvedAt,
    )

    private fun snapshotWith(card: BoardCard) =
        BackupSnapshot(exportedAt = now, boardCards = listOf(card))

    @Test
    fun `动态置顶：日期型经备份往返不丢`() {
        val card = cardWith(
            AutoPinRule.OnDate(at = Instant.ofEpochMilli(1_800_000_000_000L), durationMinutes = 480),
        )
        val restored = BackupCodec.decode(BackupCodec.encode(snapshotWith(card)))
        val back = restored.boardCards.single().autoPin
        assertTrue(back is AutoPinRule.OnDate)
        assertEquals(1_800_000_000_000L, (back as AutoPinRule.OnDate).at.toEpochMilli())
        assertEquals(480, back.durationMinutes)
    }

    @Test
    fun `动态置顶：周期型经备份往返不丢`() {
        val card = cardWith(
            AutoPinRule.Recurring(
                rule = RecurrenceRule(Frequency.WEEKLY, byDay = setOf(java.time.DayOfWeek.MONDAY)),
                minuteOfDay = 510,
                durationMinutes = 1440,
            ),
        )
        val restored = BackupCodec.decode(BackupCodec.encode(snapshotWith(card)))
        val back = restored.boardCards.single().autoPin
        assertTrue(back is AutoPinRule.Recurring)
        back as AutoPinRule.Recurring
        assertEquals(510, back.minuteOfDay)
        assertEquals(1440, back.durationMinutes)
        assertEquals(Frequency.WEEKLY, back.rule.frequency)
    }

    @Test
    fun `动态置顶：归位时刻经备份往返不丢`() {
        val card = cardWith(
            AutoPinRule.OnDate(at = now, durationMinutes = 60),
            resolvedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        )
        val restored = BackupCodec.decode(BackupCodec.encode(snapshotWith(card)))
        assertEquals(1_700_000_000_000L, restored.boardCards.single().autoPinResolvedAt?.toEpochMilli())
    }

    @Test
    fun `动态置顶：未启用时往返仍为 null`() {
        val restored = BackupCodec.decode(BackupCodec.encode(snapshotWith(cardWith(null))))
        assertEquals(null, restored.boardCards.single().autoPin)
    }

    @Test
    fun `动态置顶：超长时长在导入时被夹到一年`() {
        // 手造一个时长为 100 年的备份，导入后必须被夹到 365 天。
        val card = cardWith(AutoPinRule.OnDate(at = now, durationMinutes = 365 * 24 * 60))
        val json = BackupCodec.encode(snapshotWith(card))
            .replace("\"autoPinDurationMinutes\":525600", "\"autoPinDurationMinutes\":52560000")
        val restored = BackupCodec.decode(json)
        assertEquals(365 * 24 * 60, restored.boardCards.single().autoPin?.durationMinutes)
    }

    // ---------------------------------------------------------------- 补齐历史漏字段

    /**
     * 回归测试：这四列**曾经整列漏在备份之外**，而当时的往返测试全绿 ——
     * 因为往返测试用的字段清单和 codec 是同一份，少了也一致。
     *
     * 现在逐列断言「改了值之后能活着回来」。
     */
    @Test
    fun `曾经漏掉的卡片四列现在往返不丢`() {
        val card = BoardCard(
            id = "card-x",
            type = BoardCardType.QUICK,
            title = "保密卡",
            secret = true,
            secretHint = "别点开",
            createdAt = now,
            updatedAt = now,
            sortIndex = 123.5,
            widthMode = "full",
            showDate = false,
        )

        val back = BackupCodec.decode(BackupCodec.encode(snapshotWith(card))).boardCards.single()

        assertEquals("别点开", back.secretHint)
        assertEquals(123.5, back.sortIndex, 0.0001)
        assertEquals("full", back.widthMode)
        assertEquals(false, back.showDate)
    }

    /** `sortIndex` 丢失的后果是**所有卡片顺序塌掉**（全变 0），这条专门盯它。 */
    @Test
    fun `卡片排序键丢失会让顺序塌掉 所以必须往返保留`() {
        val cards = listOf(
            cardWith(null).copy(id = "a", sortIndex = 1.0),
            cardWith(null).copy(id = "b", sortIndex = 2.0),
            cardWith(null).copy(id = "c", sortIndex = 3.0),
        )
        val restored = BackupCodec.decode(
            BackupCodec.encode(BackupSnapshot(exportedAt = now, boardCards = cards)),
        )

        assertEquals(
            listOf(1.0, 2.0, 3.0),
            restored.boardCards.sortedBy { it.id }.map { it.sortIndex },
        )
    }

    /** 旧备份（v3 及以前）没有这四列 → 取与数据库列相同的默认值。 */
    @Test
    fun `旧备份缺少卡片四列时取数据库默认值`() {
        val json = """
            {
              "formatVersion": 3,
              "exportedAt": 0,
              "boardCards": [ {"id":"c1","type":"QUICK","title":"老卡"} ]
            }
        """.trimIndent()

        val card = BackupCodec.decode(json).boardCards.single()

        assertEquals(null, card.secretHint)
        assertEquals(0.0, card.sortIndex, 0.0001)
        assertEquals(null, card.widthMode)
        assertEquals(true, card.showDate)
    }

    /** `board_card_links.createdAt` 曾经也没进备份，恢复时被改写成「恢复那一刻」。 */
    @Test
    fun `卡片关联的建立时刻往返不丢`() {
        val linkTime = Instant.ofEpochMilli(1_700_000_000_000L)
        val snapshot = BackupSnapshot(
            exportedAt = now,
            boardCardLinks = listOf(
                BoardCardLink(cardId = "c1", linkedCardId = "c2", createdAt = linkTime),
            ),
        )

        val back = BackupCodec.decode(BackupCodec.encode(snapshot)).boardCardLinks.single()

        assertEquals(linkTime, back.createdAt)
    }

    // ---------------------------------------------------------------- v4 新增

    @Test
    fun `专注记录往返不丢`() {
        val session = FocusSession(
            id = "f1",
            startedAt = now,
            endedAt = now.plusSeconds(1500),
            minutes = 25,
            kind = FocusKind.POMODORO,
            plannedMinutes = 25,
            completed = true,
            label = "写周报",
            itemId = "i-task",
            createdAt = now,
        )
        val snapshot = BackupSnapshot(exportedAt = now, focusSessions = listOf(session))

        val back = BackupCodec.decode(BackupCodec.encode(snapshot)).focusSessions.single()

        assertEquals(session, back)
    }

    /** 专注记录挂在待办上 —— 这条关联就是「数据打通」的那一段，不能丢。 */
    @Test
    fun `专注记录与条目的关联往返不丢`() {
        val session = FocusSession(
            id = "f2",
            startedAt = now,
            endedAt = now,
            minutes = 10,
            kind = FocusKind.COUNT_DOWN,
            plannedMinutes = 15,
            completed = false,
            itemId = "i-42",
            createdAt = now,
        )
        val snapshot = BackupSnapshot(exportedAt = now, focusSessions = listOf(session))

        val back = BackupCodec.decode(BackupCodec.encode(snapshot)).focusSessions.single()

        assertEquals("i-42", back.itemId)
        assertEquals(15, back.plannedMinutes)
        assertEquals(false, back.completed)
    }

    @Test
    fun `扩展抽屉往返不丢`() {
        val record = ExtRecord(
            owner = ExtOwner.BOARD_CARD,
            ownerId = "c1",
            map = ExtMap.EMPTY
                .putText("weather.sky", "多云")
                .putNum("mood.score", 4.0)
                .putFlag("mood.private", true),
        )
        val snapshot = BackupSnapshot(exportedAt = now, entityExt = listOf(record))

        val back = BackupCodec.decode(BackupCodec.encode(snapshot)).entityExt.single()

        assertEquals(record, back)
    }

    /**
     * 抽屉的最后写入时刻也要进备份。
     *
     * 这条是**被 `BackupCompletenessTest` 抓出来的**：`entity_ext.updatedAt` 一度没进备份，
     * 恢复时被改写成「恢复那一刻」。补上之后，恢复出来的时间戳与备份时一致。
     */
    @Test
    fun `扩展抽屉的最后写入时刻往返不丢`() {
        val stamp = Instant.ofEpochMilli(1_690_000_000_000L)
        val record = ExtRecord(
            owner = ExtOwner.DAILY_REVIEW,
            ownerId = "20000",
            map = ExtMap.EMPTY.putNum("mood.score", 5.0),
            updatedAt = stamp,
        )

        val back = BackupCodec.decode(
            BackupCodec.encode(BackupSnapshot(exportedAt = now, entityExt = listOf(record))),
        ).entityExt.single()

        assertEquals(stamp, back.updatedAt)
    }

    /** 抽屉内容在备份里是**原样嵌套的对象**，方便人和 AI 直接读，而不是一串转义字符串。 */
    @Test
    fun `扩展抽屉在备份里是可读的嵌套对象`() {
        val record = ExtRecord(
            owner = ExtOwner.ITEM,
            ownerId = "i1",
            map = ExtMap.EMPTY.putText("weather.sky", "晴"),
        )
        val json = BackupCodec.encode(BackupSnapshot(exportedAt = now, entityExt = listOf(record)))

        // 是嵌套对象（可读），而不是被转义的字符串
        assertTrue("抽屉内容被写成了转义字符串：$json", !json.contains("\\\"weather.sky\\\""))
        assertTrue("抽屉内容应当直接可读：$json", json.contains("\"weather.sky\""))
    }

    /** 认不出的表名（更新的版本写下的）跳过该行，而不是让整份备份导入失败。 */
    @Test
    fun `认不出的抽屉表名被跳过而不是让导入失败`() {
        val json = """
            {
              "formatVersion": 4,
              "exportedAt": 0,
              "entityExt": [
                {"ownerTable":"future_table","ownerId":"x","ext":{"a":"1"}},
                {"ownerTable":"items","ownerId":"i1","ext":{"b":"2"}}
              ]
            }
        """.trimIndent()

        val back = BackupCodec.decode(json).entityExt

        assertEquals(1, back.size)
        assertEquals(ExtOwner.ITEM, back.single().owner)
        assertEquals("2", back.single().map.text("b"))
    }

}
