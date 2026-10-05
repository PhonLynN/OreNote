package com.phonlynn.oreplan.domain.backup

import com.phonlynn.oreplan.core.time.WeekParity
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.data.mapper.AutoPinCodec
import com.phonlynn.oreplan.data.mapper.ExtCodec
import com.phonlynn.oreplan.domain.model.AutoPinRule
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.ChecklistCategory
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.DailyReview
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.HabitLog
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.domain.model.StepOrderMode
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * 备份文件的读写。
 *
 * 纯函数式编解码：输入输出都只有字符串和领域模型，不碰数据库、不依赖 Android 上下文，
 * 所以「导出再导入是否等价」这件事可以用普通单元测试直接验证。
 *
 * 用 org.json 而不是引入序列化库：备份格式只有一种、字段固定，
 * 为此再引一个编译期插件和新依赖不值当（而且本项目 Kotlin 被 AGP 锁在 2.2.x，
 * 新库的编译版本不一定兼容）。
 */
object BackupCodec {

    /**
     * 当前格式版本。
     *
     * - v2：多了笔记小节 / 备忘清单 / 附件索引三组数组；
     * - v3：多了白板 / 目标记录 / 每日复盘；
     * - v4：多了专注记录与扩展抽屉，并**补齐了此前漏写的字段**
     *   （`board_cards` 的 `secretHint` / `sortIndex` / `widthMode` / `showDate`，
     *   以及 `board_card_links` 的 `createdAt`）；
     * - **v5**：多了 `settings` —— **用户能自定义的一切设置**
     *   （`app_meta` 的 KV：全部设置、全部对话、AI 配置、同步偏好）。
     *
     * ## v5 的关键约束：**密钥不进备份**
     *
     * 用户口径：「一切用户能够自定义的东西都应该能进备份，除了 api 密钥这种东西」。
     * 所以 v5 带的是白名单内容（见 [BackupKeys]），其中：
     *
     * · `ai.settings` 里的 **API Key 被剥掉**（恢复后用户重填一次）
     * · `sync.master_key` / `sync.r2_access_key` / `sync.r2_secret` **从不写入**
     *
     * 所有数组都是**可选**的，所以 v1~v4 的旧备份仍然能导入（缺失即空）。
     * 反过来，**旧版应用导入 v5 备份会被明确拒绝**：v5 里有它不认识的设置，
     * 静默丢掉比报错更糟（与本文件既有的「宁可报错也不静默丢数据」一致）。
     */
    const val FORMAT_VERSION = 5

    // ---------------------------------------------------------------- 写

    fun encode(snapshot: BackupSnapshot): String {
        val root = JSONObject()
        root.put("formatVersion", snapshot.formatVersion)
        root.put("exportedAt", snapshot.exportedAt.toEpochMilli())

        root.put("terms", snapshot.terms.map(::termToJson).toJsonArray())
        root.put("courses", snapshot.courses.map(::courseToJson).toJsonArray())
        root.put("courseSessions", snapshot.courseSessions.map(::sessionToJson).toJsonArray())
        root.put("items", snapshot.items.map(::itemToJson).toJsonArray())
        root.put("tags", snapshot.tags.map(::tagToJson).toJsonArray())
        root.put(
            "itemTags",
            snapshot.itemTags.map { link ->
                JSONObject().apply {
                    put("itemId", link.itemId)
                    put("tagId", link.tagId)
                }
            }.toJsonArray(),
        )
        root.put("reminders", snapshot.reminders.map(::reminderToJson).toJsonArray())
        root.put(
            "recurrenceExceptions",
            snapshot.recurrenceExceptions.map(::exceptionToJson).toJsonArray(),
        )
        root.put("noteBlocks", snapshot.noteBlocks.map(::noteBlockToJson).toJsonArray())
        root.put(
            "checklistEntries",
            snapshot.checklistEntries.map(::checklistEntryToJson).toJsonArray(),
        )
        root.put("attachments", snapshot.attachments.map(::attachmentToJson).toJsonArray())

        root.put("boardCards", snapshot.boardCards.map(::boardCardToJson).toJsonArray())
        root.put("boardTodoItems", snapshot.boardTodoItems.map(::boardTodoItemToJson).toJsonArray())
        root.put("boardTags", snapshot.boardTags.map(::boardTagToJson).toJsonArray())
        root.put(
            "boardCardTags",
            snapshot.boardCardTags.map { link ->
                JSONObject().apply { put("cardId", link.cardId); put("tagId", link.tagId) }
            }.toJsonArray(),
        )
        root.put(
            "boardCardLinks",
            snapshot.boardCardLinks.map { link ->
                JSONObject().apply {
                    put("cardId", link.cardId)
                    put("linkedCardId", link.linkedCardId)
                    // 曾经漏掉这个字段：恢复时被改写成「恢复那一刻」，
                    // 于是「关联是什么时候建立的」被静默改掉。
                    putNullable("createdAt", link.createdAt?.toEpochMilli())
                }
            }.toJsonArray(),
        )
        root.put("habitLogs", snapshot.habitLogs.map { log ->
            JSONObject().apply { put("itemId", log.itemId); put("epochDay", log.epochDay) }
        }.toJsonArray())
        root.put("quantityLogs", snapshot.quantityLogs.map(::quantityLogToJson).toJsonArray())
        root.put("dailyReviews", snapshot.dailyReviews.map(::dailyReviewToJson).toJsonArray())

        // v4 新增两组。
        root.put("focusSessions", snapshot.focusSessions.map(::focusSessionToJson).toJsonArray())
        root.put(
            "entityExt",
            snapshot.entityExt.map { record ->
                JSONObject().apply {
                    put("ownerTable", record.owner.table)
                    put("ownerId", record.ownerId)
                    // 抽屉内容**原样嵌成对象**，不再套一层字符串：
                    // 备份文件因此对人、对 AI 都是直接可读的。
                    put("ext", ExtCodec.toJsonObject(record.map))
                    // 时间戳必须一起走：否则恢复后「抽屉最后改动时间」被改写成恢复那一刻。
                    putNullable("updatedAt", record.updatedAt?.toEpochMilli())
                }
            }.toJsonArray(),
        )

        // v5 新增：用户能自定义的设置（密钥类已在导出时排除 / 脱敏）。
        //
        // 存成 `{"键": "值"}` 的对象 —— 值本身往往是**一段 JSON 字符串**
        //（比如 ai.settings 是一整个 JSON），所以这里保持字符串原样，
        // 由各自的 Store 去解析。备份层不解释它的内容。
        if (snapshot.settings.isNotEmpty()) {
            root.put(
                "settings",
                JSONObject().apply {
                    snapshot.settings.forEach { (k, v) -> put(k, v) }
                },
            )
        }

        return root.toString(2)
    }

    // ---------------------------------------------------------------- 读

    /**
     * 解析备份。**解析失败抛 [IllegalArgumentException]**，由调用方转成用户可读的提示 ——
     * 静默返回空快照会让用户以为「导入成功但数据没了」，那比报错更糟。
     */
    fun decode(json: String): BackupSnapshot {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw IllegalArgumentException("文件不是有效的备份（不是 JSON）") }

        val version = root.optInt("formatVersion", -1)
        if (version <= 0) throw IllegalArgumentException("缺少格式版本，可能不是本应用的备份")
        if (version > FORMAT_VERSION) {
            throw IllegalArgumentException(
                "备份来自更新的版本（v$version），当前应用只认到 v$FORMAT_VERSION",
            )
        }

        return BackupSnapshot(
            formatVersion = version,
            exportedAt = Instant.ofEpochMilli(root.optLong("exportedAt", 0L)),
            terms = root.array("terms").mapObjects(::termFromJson),
            courses = root.array("courses").mapObjects(::courseFromJson),
            courseSessions = root.array("courseSessions").mapObjects(::sessionFromJson),
            items = root.array("items").mapObjects(::itemFromJson),
            tags = root.array("tags").mapObjects(::tagFromJson),
            itemTags = root.array("itemTags").mapObjects { obj ->
                ItemTagLink(itemId = obj.getString("itemId"), tagId = obj.getString("tagId"))
            },
            reminders = root.array("reminders").mapObjects(::reminderFromJson),
            recurrenceExceptions = root.array("recurrenceExceptions")
                .mapObjects(::exceptionFromJson),
            noteBlocks = root.array("noteBlocks").mapObjects(::noteBlockFromJson),
            checklistEntries = root.array("checklistEntries")
                .mapObjects(::checklistEntryFromJson),
            attachments = root.array("attachments").mapObjects(::attachmentFromJson),
            boardCards = root.array("boardCards").mapObjects(::boardCardFromJson),
            boardTodoItems = root.array("boardTodoItems").mapObjects(::boardTodoItemFromJson),
            boardTags = root.array("boardTags").mapObjects(::boardTagFromJson),
            boardCardTags = root.array("boardCardTags").mapObjects { obj ->
                BoardCardTag(cardId = obj.getString("cardId"), tagId = obj.getString("tagId"))
            },
            boardCardLinks = root.array("boardCardLinks").mapObjects { obj ->
                BoardCardLink(
                    cardId = obj.getString("cardId"),
                    linkedCardId = obj.getString("linkedCardId"),
                    // 旧备份没有这个键 → null（恢复侧会补当前时刻，与旧行为一致）。
                    createdAt = obj.nullableInstant("createdAt"),
                )
            },
            habitLogs = root.array("habitLogs").mapObjects { obj ->
                HabitLog(itemId = obj.getString("itemId"), epochDay = obj.getInt("epochDay"))
            },
            quantityLogs = root.array("quantityLogs").mapObjects(::quantityLogFromJson),
            dailyReviews = root.array("dailyReviews").mapObjects(::dailyReviewFromJson),
            // v4 新增。旧备份（v1~v3）没有这两个键 → 空数组。
            focusSessions = root.array("focusSessions").mapObjects(::focusSessionFromJson),
            entityExt = root.array("entityExt").mapObjects(::extRecordFromJson).filterNotNull(),
            // v5 新增。旧备份（v1~v4）没有这个键 → 空 Map（= 不动任何设置）。
            settings = root.optJSONObject("settings")?.let { obj ->
                buildMap {
                    obj.keys().forEach { k ->
                        // ⚠️ 只收**字符串**值：类型不对说明这个备份被人改过，
                        // 那种值写进 app_meta 只会让 Store 解析失败。
                        obj.optString(k).takeIf { obj.get(k) is String }
                            ?.let { v -> put(k, v) }
                    }
                }
            }.orEmpty(),
        )
    }

    // ---------------------------------------------------------------- 各表写入

    internal fun termToJson(term: Term) = JSONObject().apply {
        put("id", term.id)
        put("name", term.name)
        put("startDate", term.startDate.toEpochDay())
        put("totalWeeks", term.totalWeeks)
        put("isActive", term.isActive)
    }

    internal fun courseToJson(course: Course) = JSONObject().apply {
        put("id", course.id)
        put("name", course.name)
        putNullable("teacher", course.teacher)
        putNullable("defaultLocation", course.defaultLocation)
        putNullable("colorHex", course.colorHex)
        putNullable("note", course.note)
        put("credit", course.credit)
    }

    internal fun sessionToJson(session: CourseSession) = JSONObject().apply {
        put("id", session.id)
        put("courseId", session.courseId)
        put("dayOfWeek", session.dayOfWeek)
        put("startMinuteOfDay", session.startMinuteOfDay)
        put("endMinuteOfDay", session.endMinuteOfDay)
        put("startWeek", session.startWeek)
        put("endWeek", session.endWeek)
        put("parity", session.parity.name)
        putNullable("location", session.location)
        putNullable("note", session.note)
    }

    /**
     * ⚠️ `internal` 而不是 `private`：**云同步复用这一份映射**。
     *
     * 为什么不给同步另写一份 item↔JSON：
     *  · 字段表已经有 20+ 项，且会随功能增长 —— 两份必然漂移；
     *  · 漂移的后果是**静默丢字段**（同步过去的实体少一个键，
     *    另一端读出来就是默认值），而这种 bug 在界面上表现为"某个设置莫名其妙没了"；
     *  · 这里有 `BackupCompletenessTest` 用**编译器生成的 schema 反向校验**覆盖，
     *    是全项目唯一能抓出"漏字段"的机制。同步复用即自动继承这道防线。
     *
     * 反向的 `itemFromJson` 同理。改字段时两处调用方（备份、同步）一起受益。
     */
    internal fun itemToJson(item: Item) = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind.name)
        put("title", item.title)
        putNullable("note", item.note)
        put("status", item.status.name)
        put("priority", item.priority)
        putNullable("startAt", item.startAt?.toEpochMilli())
        putNullable("endAt", item.endAt?.toEpochMilli())
        put("allDay", item.allDay)
        putNullable("rrule", item.rrule)
        putNullable("rruleUntil", item.rruleUntil?.toEpochMilli())
        putNullable("softDueAt", item.softDueAt?.toEpochMilli())
        putNullable("planStartDay", item.planStartDay?.toEpochDay())
        putNullable("planEndDay", item.planEndDay?.toEpochDay())
        putNullable("parentId", item.parentId)
        put("treePath", item.treePath)
        put("depth", item.depth)
        putNullable("progress", item.progress)
        putNullable("completedAt", item.completedAt?.toEpochMilli())
        putNullable("colorTag", item.colorTag)
        put("orderIndex", item.orderIndex)
        put("createdAt", item.createdAt.toEpochMilli())
        put("updatedAt", item.updatedAt.toEpochMilli())
        putNullable("goalType", item.goalType?.name)
        putNullable("unit", item.unit)
        putNullable("targetValue", item.targetValue)
        putNullable("stepOrderMode", item.stepOrderMode?.name)
        put("autoAdvance", item.autoAdvance)
        put("showOnToday", item.showOnToday)
        put("pinned", item.pinned)
        putNullable("category", item.category)
        putNullable("goalNote", item.goalNote)
        putNullable("location", item.location)
        // v10 新增：归属待办组。旧备份没有这个键 → 解码时回 null（向下兼容，不升格式号）。
        putNullable("groupId", item.groupId)
    }

    internal fun tagToJson(tag: Tag) = JSONObject().apply {
        put("id", tag.id)
        put("name", tag.name)
        putNullable("colorHex", tag.colorHex)
    }

    private fun reminderToJson(reminder: Reminder) = JSONObject().apply {
        put("id", reminder.id)
        put("itemId", reminder.itemId)
        put("triggerAt", reminder.triggerAt.toEpochMilli())
        putNullable("offsetMinutes", reminder.offsetMinutes)
        put("enabled", reminder.enabled)
    }

    private fun exceptionToJson(exception: RecurrenceException) = JSONObject().apply {
        put("id", exception.id)
        put("itemId", exception.itemId)
        put("date", exception.date.toEpochDay())
        put("action", exception.action.name)
        putNullable("overrideStartAt", exception.overrideStartAt?.toEpochMilli())
        putNullable("overrideEndAt", exception.overrideEndAt?.toEpochMilli())
        putNullable("overrideTitle", exception.overrideTitle)
    }

    private fun noteBlockToJson(block: NoteBlock) = JSONObject().apply {
        put("id", block.id)
        put("ownerId", block.ownerId)
        put("heading", block.heading)
        put("body", block.body)
        put("orderIndex", block.orderIndex)
        put("createdAt", block.createdAt.toEpochMilli())
        put("updatedAt", block.updatedAt.toEpochMilli())
    }

    private fun checklistEntryToJson(entry: ChecklistEntry) = JSONObject().apply {
        put("id", entry.id)
        put("itemId", entry.itemId)
        put("category", entry.category.name)
        put("title", entry.title)
        put("done", entry.done)
        put("orderIndex", entry.orderIndex)
        put("createdAt", entry.createdAt.toEpochMilli())
        put("updatedAt", entry.updatedAt.toEpochMilli())
    }

    internal fun attachmentToJson(attachment: Attachment) = JSONObject().apply {
        put("id", attachment.id)
        put("ownerType", attachment.ownerType.name)
        put("ownerId", attachment.ownerId)
        put("displayName", attachment.displayName)
        put("mimeType", attachment.mimeType)
        put("sizeBytes", attachment.sizeBytes)
        put("storedPath", attachment.storedPath)
        put("createdAt", attachment.createdAt.toEpochMilli())
    }

    // ---------------------------------------------------------------- 各表读取

    internal fun termFromJson(obj: JSONObject) = Term(
        id = obj.getString("id"),
        name = obj.getString("name"),
        startDate = LocalDate.ofEpochDay(obj.getLong("startDate")),
        totalWeeks = obj.getInt("totalWeeks"),
        isActive = obj.optBoolean("isActive", false),
    )

    internal fun courseFromJson(obj: JSONObject) = Course(
        id = obj.getString("id"),
        name = obj.getString("name"),
        teacher = obj.nullableString("teacher"),
        defaultLocation = obj.nullableString("defaultLocation"),
        colorHex = obj.nullableString("colorHex"),
        note = obj.nullableString("note"),
        credit = obj.optDouble("credit", 0.0),
    )

    internal fun sessionFromJson(obj: JSONObject) = CourseSession(
        id = obj.getString("id"),
        courseId = obj.getString("courseId"),
        dayOfWeek = obj.getInt("dayOfWeek"),
        startMinuteOfDay = obj.getInt("startMinuteOfDay"),
        endMinuteOfDay = obj.getInt("endMinuteOfDay"),
        startWeek = obj.optInt("startWeek", 1),
        endWeek = obj.optInt("endWeek", Int.MAX_VALUE).let {
            if (it <= 0) Int.MAX_VALUE else it
        },
        parity = obj.optString("parity").toParity(),
        location = obj.nullableString("location"),
        note = obj.nullableString("note"),
    )

    /** 与 [itemToJson] 成对；`internal` 的理由见那里。 */
    internal fun itemFromJson(obj: JSONObject) = Item(
        id = obj.getString("id"),
        kind = obj.optString("kind").toKind(),
        title = obj.getString("title"),
        note = obj.nullableString("note"),
        status = obj.optString("status").toStatus(),
        priority = obj.optInt("priority", 0),
        startAt = obj.nullableInstant("startAt"),
        endAt = obj.nullableInstant("endAt"),
        allDay = obj.optBoolean("allDay", false),
        rrule = obj.nullableString("rrule"),
        rruleUntil = obj.nullableInstant("rruleUntil"),
        softDueAt = obj.nullableInstant("softDueAt"),
        planStartDay = obj.nullableEpochDay("planStartDay"),
        planEndDay = obj.nullableEpochDay("planEndDay"),
        parentId = obj.nullableString("parentId"),
        treePath = obj.optString("treePath"),
        depth = obj.optInt("depth", 0),
        progress = obj.nullableFloat("progress"),
        completedAt = obj.nullableInstant("completedAt"),
        colorTag = obj.nullableString("colorTag"),
        orderIndex = obj.optDouble("orderIndex", 0.0),
        createdAt = Instant.ofEpochMilli(obj.optLong("createdAt", 0L)),
        updatedAt = Instant.ofEpochMilli(obj.optLong("updatedAt", 0L)),
        goalType = obj.nullableString("goalType")?.let { raw ->
            GoalType.entries.firstOrNull { it.name == raw }
        },
        unit = obj.nullableString("unit"),
        targetValue = if (obj.isNull("targetValue")) null else obj.optLong("targetValue"),
        stepOrderMode = obj.nullableString("stepOrderMode")?.let { raw ->
            StepOrderMode.entries.firstOrNull { it.name == raw }
        },
        autoAdvance = obj.optBoolean("autoAdvance", false),
        showOnToday = obj.optBoolean("showOnToday", false),
        pinned = obj.optBoolean("pinned", false),
        category = obj.nullableString("category"),
        goalNote = obj.nullableString("goalNote"),
        location = obj.nullableString("location"),
        groupId = obj.nullableString("groupId"),
    )

    internal fun tagFromJson(obj: JSONObject) = Tag(
        id = obj.getString("id"),
        name = obj.getString("name"),
        colorHex = obj.nullableString("colorHex"),
    )

    private fun reminderFromJson(obj: JSONObject) = Reminder(
        id = obj.getString("id"),
        itemId = obj.getString("itemId"),
        triggerAt = Instant.ofEpochMilli(obj.getLong("triggerAt")),
        offsetMinutes = obj.nullableInt("offsetMinutes"),
        enabled = obj.optBoolean("enabled", true),
    )

    private fun exceptionFromJson(obj: JSONObject) = RecurrenceException(
        id = obj.getString("id"),
        itemId = obj.getString("itemId"),
        date = LocalDate.ofEpochDay(obj.getLong("date")),
        action = if (obj.optString("action") == ExceptionAction.OVERRIDDEN.name) {
            ExceptionAction.OVERRIDDEN
        } else {
            ExceptionAction.DELETED
        },
        overrideStartAt = obj.nullableInstant("overrideStartAt"),
        overrideEndAt = obj.nullableInstant("overrideEndAt"),
        overrideTitle = obj.nullableString("overrideTitle"),
    )

    private fun noteBlockFromJson(obj: JSONObject) = NoteBlock(
        id = obj.getString("id"),
        ownerId = obj.getString("ownerId"),
        heading = obj.optString("heading"),
        body = obj.optString("body"),
        orderIndex = obj.optDouble("orderIndex", 0.0),
        createdAt = Instant.ofEpochMilli(obj.optLong("createdAt", 0L)),
        updatedAt = Instant.ofEpochMilli(obj.optLong("updatedAt", 0L)),
    )

    private fun checklistEntryFromJson(obj: JSONObject) = ChecklistEntry(
        id = obj.getString("id"),
        itemId = obj.getString("itemId"),
        category = obj.optString("category").toChecklistCategory(),
        title = obj.optString("title"),
        done = obj.optBoolean("done", false),
        orderIndex = obj.optDouble("orderIndex", 0.0),
        createdAt = Instant.ofEpochMilli(obj.optLong("createdAt", 0L)),
        updatedAt = Instant.ofEpochMilli(obj.optLong("updatedAt", 0L)),
    )

    /**
     * 附件的二进制不在备份里，这里读到的只是索引。
     * 恢复后 `storedPath` 指向的文件很可能不在 —— 界面负责把「文件已丢失」说清楚，
     * 而不是点下去没反应。
     */
    internal fun attachmentFromJson(obj: JSONObject) = Attachment(
        id = obj.getString("id"),
        ownerType = obj.optString("ownerType").toAttachmentOwner(),
        ownerId = obj.getString("ownerId"),
        displayName = obj.optString("displayName"),
        mimeType = obj.optString("mimeType"),
        sizeBytes = obj.optLong("sizeBytes", 0L),
        storedPath = obj.optString("storedPath"),
        createdAt = Instant.ofEpochMilli(obj.optLong("createdAt", 0L)),
    )

    // ---------------------------------------------------------------- 白板 / 目标记录（v3）

    internal fun boardCardToJson(card: BoardCard) = JSONObject().apply {
        put("id", card.id)
        put("type", card.type.key)
        putNullable("title", card.title)
        putNullable("body", card.body)
        putNullable("color", card.color)
        put("pinned", card.pinned)
        put("secret", card.secret)
        putNullable("secretHint", card.secretHint)
        put("archived", card.archived)
        put("createdAt", card.createdAt.toEpochMilli())
        put("updatedAt", card.updatedAt.toEpochMilli())
        // ---- 以下四列曾经**整列漏在备份之外**（历史数据丢失缺口）----
        //   sortIndex 丢了 → 恢复后所有卡片排序键都变成 0，**卡片顺序整个塌掉**；
        //   widthMode / showDate 丢了 → 卡片宽度与「显示日期」回到默认值；
        //   secretHint 丢了 → 保密卡不再显示你写的暗号，退回默认的「已隐藏」。
        // 现补齐。之所以能保证以后不再漏，靠的是 `BackupCompletenessTest`：
        // 它拿**编译器生成的 schema** 当基准反向校验，而不是拿这份手写清单自我比对。
        put("sortIndex", card.sortIndex)
        putNullable("widthMode", card.widthMode)
        put("showDate", card.showDate)
        putNullable("imageLayout", card.imageLayout)
        // 动态置顶（v9 新增）。与 AutoPinCodec 共用同一份编解码，
        // 避免「存的能读、还原后丢配置」。
        putNullable("autoPinKind", AutoPinCodec.kindOf(card.autoPin))
        putNullable("autoPinRule", card.autoPin?.let { AutoPinCodec.encodeRule(it) })
        if (card.autoPin != null) put("autoPinDurationMinutes", card.autoPin.durationMinutes)
        card.autoPinResolvedAt?.let { put("autoPinResolvedAt", it.toEpochMilli()) }
    }

    internal fun boardCardFromJson(obj: JSONObject) = BoardCard(
        id = obj.getString("id"),
        type = BoardCardType.fromKey(obj.optString("type")),
        title = obj.nullableString("title"),
        body = obj.nullableString("body"),
        color = obj.nullableString("color"),
        pinned = obj.optBoolean("pinned", false),
        secret = obj.optBoolean("secret", false),
        secretHint = obj.nullableString("secretHint"),
        archived = obj.optBoolean("archived", false),
        createdAt = Instant.ofEpochMilli(obj.optLong("createdAt", 0L)),
        updatedAt = Instant.ofEpochMilli(obj.optLong("updatedAt", 0L)),
        // 旧备份（v3 及以前）没有这几项 → 取**与数据库列相同的默认值**，
        // 保证「旧备份导入后 = 新装应用该有的样子」，而不是另一套默认。
        sortIndex = obj.optDouble("sortIndex", 0.0),
        widthMode = obj.nullableString("widthMode"),
        showDate = obj.optBoolean("showDate", true),
        imageLayout = obj.nullableString("imageLayout"),
        // 动态置顶：缺失或非法都降级为未启用，不让旧备份读崩。
        autoPin = AutoPinCodec.decode(
            kind = obj.nullableString("autoPinKind"),
            rule = obj.nullableString("autoPinRule"),
            durationMinutes = if (obj.has("autoPinDurationMinutes")) {
                obj.optInt("autoPinDurationMinutes", AutoPinRule.DEFAULT_DURATION_MINUTES)
            } else {
                null
            },
        ),
        autoPinResolvedAt = if (obj.has("autoPinResolvedAt")) {
            Instant.ofEpochMilli(obj.optLong("autoPinResolvedAt", 0L))
        } else {
            null
        },
    )

    internal fun boardTodoItemToJson(item: BoardTodoItem) = JSONObject().apply {
        put("id", item.id)
        put("cardId", item.cardId)
        put("text", item.text)
        put("done", item.done)
        put("sortIndex", item.sortIndex)
    }

    internal fun boardTodoItemFromJson(obj: JSONObject) = BoardTodoItem(
        id = obj.getString("id"),
        cardId = obj.getString("cardId"),
        text = obj.optString("text"),
        done = obj.optBoolean("done", false),
        sortIndex = obj.optInt("sortIndex", 0),
    )

    internal fun boardTagToJson(tag: BoardTag) = JSONObject().apply {
        put("id", tag.id)
        put("name", tag.name)
        putNullable("parentId", tag.parentId)
        put("sortIndex", tag.sortIndex)
        put("hideFromAll", tag.hideFromAll)
    }

    internal fun boardTagFromJson(obj: JSONObject) = BoardTag(
        id = obj.getString("id"),
        name = obj.optString("name"),
        parentId = obj.nullableString("parentId"),
        sortIndex = obj.optInt("sortIndex", 0),
        hideFromAll = obj.optBoolean("hideFromAll", false),
    )

    private fun quantityLogToJson(log: QuantityLog) = JSONObject().apply {
        put("id", log.id)
        put("itemId", log.itemId)
        put("at", log.at.toEpochMilli())
        put("amount", log.amount)
        putNullable("label", log.label)
    }

    private fun quantityLogFromJson(obj: JSONObject) = QuantityLog(
        id = obj.getString("id"),
        itemId = obj.getString("itemId"),
        at = Instant.ofEpochMilli(obj.optLong("at", 0L)),
        amount = obj.optLong("amount", 0L),
        label = obj.nullableString("label"),
    )

    private fun dailyReviewToJson(review: DailyReview) = JSONObject().apply {
        put("epochDay", review.epochDay)
        put("text", review.text)
        put("updatedAt", review.updatedAt.toEpochMilli())
    }

    private fun dailyReviewFromJson(obj: JSONObject) = DailyReview(
        epochDay = obj.getInt("epochDay"),
        text = obj.optString("text"),
        updatedAt = Instant.ofEpochMilli(obj.optLong("updatedAt", 0L)),
    )

    // ---------------------------------------------------------------- 专注 / 扩展抽屉（v4）

    internal fun focusSessionToJson(session: FocusSession) = JSONObject().apply {
        put("id", session.id)
        put("startedAt", session.startedAt.toEpochMilli())
        put("endedAt", session.endedAt.toEpochMilli())
        put("minutes", session.minutes)
        put("kind", session.kind.key)
        putNullable("plannedMinutes", session.plannedMinutes)
        put("completed", session.completed)
        putNullable("label", session.label)
        putNullable("itemId", session.itemId)
        put("createdAt", session.createdAt.toEpochMilli())
    }

    internal fun focusSessionFromJson(obj: JSONObject) = FocusSession(
        id = obj.getString("id"),
        startedAt = Instant.ofEpochMilli(obj.optLong("startedAt", 0L)),
        endedAt = Instant.ofEpochMilli(obj.optLong("endedAt", 0L)),
        minutes = obj.optInt("minutes", 0),
        kind = FocusKind.fromKey(obj.nullableString("kind")),
        plannedMinutes = obj.nullableInt("plannedMinutes"),
        completed = obj.optBoolean("completed", false),
        label = obj.nullableString("label"),
        itemId = obj.nullableString("itemId"),
        createdAt = Instant.ofEpochMilli(obj.optLong("createdAt", 0L)),
    )

    /**
     * 一条抽屉行。
     *
     * 表名认不出（更新的版本写下的、或手工改过备份）→ **跳过这一行**，
     * 而不是让整份备份导入失败。一条读不懂的附属信息，不值得挡住全部数据。
     */
    private fun extRecordFromJson(obj: JSONObject): ExtRecord? {
        val owner = obj.nullableString("ownerTable")?.let(ExtOwner::fromTable) ?: return null
        val ownerId = obj.nullableString("ownerId") ?: return null
        val map = ExtCodec.fromJsonObject(obj.optJSONObject("ext"))
        if (map.isEmpty) return null
        return ExtRecord(
            owner = owner,
            ownerId = ownerId,
            map = map,
            updatedAt = obj.nullableInstant("updatedAt"),
        )
    }

    // ---------------------------------------------------------------- 小工具

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    private fun JSONObject.nullableFloat(key: String): Float? =
        if (isNull(key)) null else optDouble(key).toFloat()

    private fun JSONObject.nullableInstant(key: String): Instant? =
        if (isNull(key)) null else Instant.ofEpochMilli(optLong(key))

    private fun JSONObject.nullableEpochDay(key: String): LocalDate? =
        if (isNull(key)) null else LocalDate.ofEpochDay(optLong(key))

    private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

    private fun List<JSONObject>.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach { array.put(it) } }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }

    private fun String.toParity(): WeekParity =
        WeekParity.entries.firstOrNull { it.name == this } ?: WeekParity.ALL

    private fun String.toKind(): ItemKind =
        ItemKind.entries.firstOrNull { it.name == this } ?: ItemKind.TASK

    private fun String.toStatus(): ItemStatus =
        ItemStatus.entries.firstOrNull { it.name == this } ?: ItemStatus.TODO

    private fun String.toChecklistCategory(): ChecklistCategory =
        ChecklistCategory.entries.firstOrNull { it.name == this } ?: ChecklistCategory.OTHER

    private fun String.toAttachmentOwner(): AttachmentOwner =
        AttachmentOwner.entries.firstOrNull { it.name == this } ?: AttachmentOwner.ITEM
}
