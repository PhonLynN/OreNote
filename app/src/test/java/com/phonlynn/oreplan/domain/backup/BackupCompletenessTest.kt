package com.phonlynn.oreplan.domain.backup

import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.AutoPinRule
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.ChecklistCategory
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.DailyReview
import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.HabitLog
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.model.StepOrderMode
import com.phonlynn.oreplan.domain.model.Tag
import com.phonlynn.oreplan.domain.model.Term
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek

/**
 * 备份完整性 —— **以数据库真实 schema 为基准**，逐表校验「每一个列都在备份里有对应字段」。
 *
 * ## 为什么必须有这个测试（而不是靠纪律）
 *
 * 老约定是「改模型必须同步 codec + 往返测试」。这条约定**已经失效过一次**：
 * `board_cards` 的 `secretHint` / `sortIndex` / `widthMode` / `showDate`
 * 四列和 `board_card_links.createdAt` 都漏在备份之外，而当时的 15 个往返测试**全绿**。
 *
 * 原因是结构性的：往返测试是 `encode → decode`，两边用的是**同一份手写字段清单**。
 * 清单里少一个字段，encode 不写、decode 不读，两边一致 → 测试必然通过。
 * **往返测试在原理上就抓不到「漏字段」。**
 *
 * 所以这里换一个基准：拿 **Room 编译器生成的 schema JSON**（`app/schemas/…/<版本>.json`）
 * 当唯一真相，反向要求 codec 的每个数组覆盖该表的全部列。
 * schema 是编译产物 —— 加了列而没进备份，这个测试立刻失败。
 */
class BackupCompletenessTest {

    /**
     * 备份里的 JSON 数组名 → 数据库表名。
     * 这张映射本身就是 codec 的对外契约；新表必须在这里登记，否则第一个测试会失败。
     */
    private val arrayToTable = mapOf(
        "terms" to "terms",
        "courses" to "courses",
        "courseSessions" to "course_sessions",
        "items" to "items",
        "tags" to "tags",
        "itemTags" to "item_tags",
        "reminders" to "reminders",
        "recurrenceExceptions" to "recurrence_exceptions",
        "noteBlocks" to "note_blocks",
        "checklistEntries" to "checklist_entries",
        "attachments" to "attachments",
        "boardCards" to "board_cards",
        "boardTodoItems" to "board_todo_items",
        "boardTags" to "board_tags",
        "boardCardTags" to "board_card_tags",
        "boardCardLinks" to "board_card_links",
        "habitLogs" to "habit_logs",
        "quantityLogs" to "quantity_logs",
        "dailyReviews" to "daily_reviews",
        "focusSessions" to "focus_sessions",
        "entityExt" to "entity_ext",
    )

    /**
     * **有意**不进备份的表。写成显式清单而不是默默跳过：
     * 哪天决定要备份它，必须来这里删掉这行 —— 是个会被看见的动作。
     */
    private val deliberatelyExcluded = mapOf(
        "app_meta" to "键值表：混了「设置项」与「未保存的卡片草稿」，草稿不该被恢复；等设置项盘点完再决定",
    )

    // ---------------------------------------------------------------- 测试

    /**
     * 第一道网：schema 里的每一张表都必须有备份数组（或明确登记为「有意不备份」）。
     * 加了新表却忘了接进备份 → 这里失败。
     */
    @Test
    fun `每一张数据库表都有对应的备份数组`() {
        val schemaTables = schemaTables().keys
        val covered = arrayToTable.values.toSet()
        val uncovered = schemaTables - covered - deliberatelyExcluded.keys

        assertTrue(
            "这些表既没有进备份、也没有登记为「有意不备份」：$uncovered",
            uncovered.isEmpty(),
        )
    }

    /**
     * 第二道网（核心）：逐表比对**每一个列**。
     *
     * 拿一份「把所有可选字段都填满」的快照去编码，再收集每个数组里出现的全部键，
     * 与 schema 的列集合求差 —— 少一个列就失败，并直接点出是哪个列。
     */
    @Test
    fun `每一张表的每一个列都在备份里出现`() {
        val root = JSONObject(BackupCodec.encode(fullyPopulatedSnapshot()))
        val tables = schemaTables()
        val problems = mutableListOf<String>()

        for ((array, table) in arrayToTable) {
            val columns = tables[table]
            if (columns == null) {
                problems += "备份里有数组 `$array`，但数据库里没有表 `$table`"
                continue
            }
            val jsonKeys = keysOf(root, array)
            if (jsonKeys.isEmpty()) {
                problems += "数组 `$array`（表 `$table`）在快照里根本没有产出内容"
                continue
            }
            val missing = columns - jsonKeys
            val extra = jsonKeys - columns
            if (missing.isNotEmpty()) {
                problems += "表 `$table` 的这些列**没有进备份**：${missing.sorted()}"
            }
            if (extra.isNotEmpty()) {
                problems += "表 `$table` 的备份里多出了数据库没有的键：${extra.sorted()}"
            }
        }

        assertTrue(
            "备份与数据库 schema 不一致（这正是历史上悄悄丢字段的地方）：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /** 旧备份（v1）必须仍然能导入：新数组缺失时按空处理，而不是报「文件损坏」。 */
    @Test
    fun `v1 旧备份仍然可以导入`() {
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

        assertEquals(0, restored.focusSessions.size)
        assertEquals(0, restored.entityExt.size)
    }

    /**
     * 格式版本必须**已经升过级**，这样旧应用导入新备份时会被明确拒绝
     *（而不是静默丢掉它不认识的字段）。
     *
     * ⚠️ 这里断言的是「≥ 4」而不是「== 某个具体数字」——
     * 原来的写法是 `assertEquals(4, …)`，每次合法升版都要回来改一次，
     * 而改的人很容易顺手把它改成新数字、却没想过**这个测试到底在守什么**。
     * 它守的是"升过级"这件事本身。
     */
    @Test
    fun `格式版本已升过级`() {
        assertTrue(
            "格式版本应当 ≥ 4（升过级，旧应用会拒绝导入），实际 ${BackupCodec.FORMAT_VERSION}",
            BackupCodec.FORMAT_VERSION >= 4,
        )
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 读**最新版本**的 Room schema。
     *
     * 取「版本号最大的 json」而不是写死 11：这样以后合法的版本升级
     * （比如加新表）不需要回来改这个测试；而「悄悄加列」仍然会被抓住 ——
     * 那正是我们要拦的行为。
     */
    private fun schemaTables(): Map<String, Set<String>> {
        val dir = candidateSchemaDirs().firstOrNull { it.isDirectory }
            ?: error(
                "找不到 Room schema 目录。单元测试的工作目录是 " +
                    "`${File(".").absolutePath}`，请确认 app/schemas 已随源码一起存在。",
            )
        val latest = dir.listFiles { f -> f.name.matches(Regex("""\d+\.json""")) }
            ?.maxByOrNull { it.name.removeSuffix(".json").toInt() }
            ?: error("schema 目录 $dir 里没有版本 JSON")
        val database = JSONObject(latest.readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val out = LinkedHashMap<String, Set<String>>()
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val fields = entity.getJSONArray("fields")
            val columns = LinkedHashSet<String>()
            for (j in 0 until fields.length()) {
                columns += fields.getJSONObject(j).getString("columnName")
            }
            out[entity.getString("tableName")] = columns
        }
        return out
    }

    private fun candidateSchemaDirs(): List<File> = listOf(
        File("schemas/com.phonlynn.oreplan.data.local.AppDatabase"),
        File("app/schemas/com.phonlynn.oreplan.data.local.AppDatabase"),
        File("../app/schemas/com.phonlynn.oreplan.data.local.AppDatabase"),
    )

    private fun keysOf(root: JSONObject, array: String): Set<String> {
        val arr: JSONArray = root.optJSONArray(array) ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            for (key in obj.keys()) out += key
        }
        return out
    }

    /**
     * 一份「把所有可选字段都填上值」的快照。
     *
     * 必须填满：有些键是**条件写入**的（如 `autoPinDurationMinutes` 仅在启用动态置顶时写），
     * 留空会让它们不出现，从而被误判成「漏字段」。
     */
    private fun fullyPopulatedSnapshot(): BackupSnapshot {
        val now = Instant.parse("2026-09-30T04:00:00Z")
        val day = LocalDate.of(2026, 9, 30)

        val goal = Item.newRoot(
            kind = ItemKind.GOAL,
            title = "读完 6 本书",
            now = now,
            id = "i-goal",
        )
        val item = Item.newChild(
            parent = goal,
            kind = ItemKind.TASK,
            title = "《人类简史》",
            now = now,
            id = "i-task",
        ).copy(
            note = "第三章",
            status = ItemStatus.DOING,
            priority = 2,
            startAt = now,
            endAt = now.plusSeconds(3600),
            allDay = true,
            rrule = "FREQ=WEEKLY;BYDAY=WE",
            rruleUntil = now.plusSeconds(86400 * 30),
            softDueAt = now.plusSeconds(86400),
            planStartDay = day,
            planEndDay = day.plusDays(7),
            groupId = "g-1",
            progress = 0.5f,
            completedAt = now,
            colorTag = "#123456",
            orderIndex = 1.5,
            goalType = GoalType.HABIT,
            unit = "本",
            targetValue = 6L,
            stepOrderMode = StepOrderMode.SEQ,
            autoAdvance = true,
            showOnToday = true,
            pinned = true,
            category = "学习",
            goalNote = "别断",
            location = "图书馆",
        )

        val card = BoardCard(
            id = "card-1",
            type = BoardCardType.TODO,
            title = "卡片",
            body = "正文",
            color = "#ABCDEF",
            pinned = true,
            secret = true,
            secretHint = "暗号",
            archived = false,
            createdAt = now,
            updatedAt = now,
            sortIndex = 42.0,
            widthMode = "full",
            showDate = false,
            imageLayout = "grid",
            autoPin = AutoPinRule.Recurring(
                rule = com.phonlynn.oreplan.domain.model.RecurrenceRule(
                    frequency = com.phonlynn.oreplan.domain.model.Frequency.WEEKLY,
                    byDay = setOf(DayOfWeek.MONDAY),
                ),
                minuteOfDay = 480,
                durationMinutes = 120,
            ),
            autoPinResolvedAt = now,
        )

        return BackupSnapshot(
            exportedAt = now,
            terms = listOf(
                Term(
                    id = "t1",
                    name = "2026 秋",
                    startDate = day,
                    totalWeeks = 18,
                    isActive = true,
                ),
            ),
            courses = listOf(
                Course(
                    id = "c1",
                    name = "高数",
                    teacher = "王",
                    defaultLocation = "A301",
                    colorHex = "#3B6FE0",
                    note = "备注",
                    credit = 3.0,
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
                    endWeek = 18,
                    parity = com.phonlynn.oreplan.core.time.WeekParity.ODD,
                    location = "A301",
                    note = "带书",
                ),
            ),
            items = listOf(goal, item),
            tags = listOf(Tag(id = "tag1", name = "学习", colorHex = "#3B6FE0")),
            itemTags = listOf(ItemTagLink(itemId = "i-task", tagId = "tag1")),
            reminders = listOf(
                Reminder(
                    id = "r1",
                    itemId = "i-task",
                    triggerAt = now,
                    offsetMinutes = 15,
                    enabled = true,
                ),
            ),
            recurrenceExceptions = listOf(
                RecurrenceException(
                    id = "x1",
                    itemId = "i-task",
                    date = day,
                    action = com.phonlynn.oreplan.domain.model.ExceptionAction.OVERRIDDEN,
                    overrideStartAt = now,
                    overrideEndAt = now,
                    overrideTitle = "改期",
                ),
            ),
            noteBlocks = listOf(
                NoteBlock(
                    id = "nb1",
                    ownerId = "i-goal",
                    heading = "标题",
                    body = "正文",
                    orderIndex = 1.0,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            checklistEntries = listOf(
                ChecklistEntry(
                    id = "ck1",
                    itemId = "i-task",
                    category = ChecklistCategory.MATERIAL,
                    title = "打印",
                    done = true,
                    orderIndex = 1.0,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
            attachments = listOf(
                Attachment(
                    id = "at1",
                    ownerType = AttachmentOwner.ITEM,
                    ownerId = "i-task",
                    displayName = "a.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 1024L,
                    storedPath = "attachments/at1/a.pdf",
                    createdAt = now,
                ),
            ),
            boardCards = listOf(card),
            boardTodoItems = listOf(
                BoardTodoItem(id = "bt1", cardId = "card-1", text = "买奶", done = true, sortIndex = 1),
            ),
            boardTags = listOf(
                BoardTag(id = "btag1", name = "生活", parentId = null, sortIndex = 1, hideFromAll = true),
            ),
            boardCardTags = listOf(BoardCardTag(cardId = "card-1", tagId = "btag1")),
            boardCardLinks = listOf(
                BoardCardLink(cardId = "card-1", linkedCardId = "card-2", createdAt = now),
            ),
            habitLogs = listOf(HabitLog(itemId = "i-goal", epochDay = 20000)),
            quantityLogs = listOf(
                QuantityLog(id = "q1", itemId = "i-goal", at = now, amount = 1L, label = "第一本"),
            ),
            dailyReviews = listOf(DailyReview(epochDay = 20000, text = "还行", updatedAt = now)),
            focusSessions = listOf(
                FocusSession(
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
                ),
            ),
            entityExt = listOf(
                ExtRecord(
                    owner = ExtOwner.BOARD_CARD,
                    ownerId = "card-1",
                    map = ExtMap.EMPTY.putText("weather.sky", "晴").putNum("mood.score", 4.0),
                ),
            ),
        )
    }
}
