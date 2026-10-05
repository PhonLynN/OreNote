package com.phonlynn.oreplan.data.backup

import androidx.room.withTransaction
import com.phonlynn.oreplan.data.local.dao.AppMetaDao
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import com.phonlynn.oreplan.domain.backup.BackupKeys
import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.dao.AttachmentDao
import com.phonlynn.oreplan.data.local.dao.BoardCardDao
import com.phonlynn.oreplan.data.local.dao.BoardCardLinkDao
import com.phonlynn.oreplan.data.local.dao.BoardCardTagDao
import com.phonlynn.oreplan.data.local.dao.BoardTagDao
import com.phonlynn.oreplan.data.local.dao.BoardTodoItemDao
import com.phonlynn.oreplan.data.local.dao.ChecklistDao
import com.phonlynn.oreplan.data.local.dao.DailyReviewDao
import com.phonlynn.oreplan.data.local.dao.EntityExtDao
import com.phonlynn.oreplan.data.local.dao.FocusSessionDao
import com.phonlynn.oreplan.data.local.dao.HabitLogDao
import com.phonlynn.oreplan.data.local.dao.QuantityLogDao
import com.phonlynn.oreplan.data.local.dao.CourseDao
import com.phonlynn.oreplan.data.local.dao.ItemDao
import com.phonlynn.oreplan.data.local.dao.NoteBlockDao
import com.phonlynn.oreplan.data.local.dao.RecurrenceExceptionDao
import com.phonlynn.oreplan.data.local.dao.ReminderDao
import com.phonlynn.oreplan.data.local.dao.TagDao
import com.phonlynn.oreplan.data.local.dao.TermDao
import com.phonlynn.oreplan.data.local.entity.AttachmentEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTodoItemEntity
import com.phonlynn.oreplan.data.local.entity.ChecklistEntryEntity
import com.phonlynn.oreplan.data.local.entity.DailyReviewEntity
import com.phonlynn.oreplan.data.local.entity.FocusSessionEntity
import com.phonlynn.oreplan.data.local.entity.QuantityLogEntity
import com.phonlynn.oreplan.data.local.entity.CourseEntity
import com.phonlynn.oreplan.data.local.entity.CourseSessionEntity
import com.phonlynn.oreplan.data.local.entity.ItemEntity
import com.phonlynn.oreplan.data.local.entity.ItemTagCrossRef
import com.phonlynn.oreplan.data.local.entity.NoteBlockEntity
import com.phonlynn.oreplan.data.local.entity.RecurrenceExceptionEntity
import com.phonlynn.oreplan.data.local.entity.ReminderEntity
import com.phonlynn.oreplan.data.local.entity.TagEntity
import com.phonlynn.oreplan.data.local.entity.TermEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.data.mapper.toRecord
import com.phonlynn.oreplan.domain.backup.BackupSnapshot
import com.phonlynn.oreplan.domain.backup.ItemTagLink
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.NoteBlock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份的读写。
 *
 * 直接用 DAO 而不是仓储：备份要的是「所有行」，而仓储接口是围绕界面需求设计的
 * （多半是 Flow + 过滤）；而且恢复必须在**一个事务**里完成，走仓储会把事务边界切碎。
 *
 * 恢复采用**整体替换**而不是合并。合并看似更温柔，但同一个 id 在两边都改了的情况下
 * 没有正确答案，用户也无法预期结果；整体替换至少是确定性的，配合界面上的明确提示即可。
 */
@Singleton
class RoomBackupService @Inject constructor(
    private val database: AppDatabase,
    private val itemDao: ItemDao,
    private val termDao: TermDao,
    private val courseDao: CourseDao,
    private val tagDao: TagDao,
    private val reminderDao: ReminderDao,
    private val exceptionDao: RecurrenceExceptionDao,
    private val noteBlockDao: NoteBlockDao,
    private val checklistDao: ChecklistDao,
    private val attachmentDao: AttachmentDao,
    private val boardCardDao: BoardCardDao,
    private val boardTodoItemDao: BoardTodoItemDao,
    private val boardTagDao: BoardTagDao,
    private val boardCardTagDao: BoardCardTagDao,
    private val boardCardLinkDao: BoardCardLinkDao,
    private val habitLogDao: HabitLogDao,
    private val quantityLogDao: QuantityLogDao,
    private val dailyReviewDao: DailyReviewDao,
    private val entityExtDao: EntityExtDao,
    private val focusSessionDao: FocusSessionDao,
    /** v5：设置也在 `app_meta` 里，备份要能读写它。 */
    private val appMetaDao: AppMetaDao,
) {

    /** 白名单里「整体带走」的那几项（同步偏好另算，见 [exportSettings]）。 */
    private val backupKeys = listOf(
        BackupKeys.APP_SETTINGS,
        BackupKeys.AI_CONVERSATIONS,
        BackupKeys.AI_SETTINGS,
    )

    suspend fun export(): BackupSnapshot = BackupSnapshot(
        exportedAt = Instant.now(),
        terms = termDao.findAll().map(TermEntity::toDomain),
        courses = courseDao.findCourses().map(CourseEntity::toDomain),
        courseSessions = courseDao.findSessions().map(CourseSessionEntity::toDomain),
        items = itemDao.findAll().map(ItemEntity::toDomain),
        tags = tagDao.findAll().map(TagEntity::toDomain),
        itemTags = tagDao.findAllLinks().map { ItemTagLink(it.itemId, it.tagId) },
        reminders = reminderDao.findAll().map(ReminderEntity::toDomain),
        recurrenceExceptions = exceptionDao.findAll()
            .map(RecurrenceExceptionEntity::toDomain),
        noteBlocks = noteBlockDao.findAll().map(NoteBlockEntity::toDomain),
        checklistEntries = checklistDao.findAll().map(ChecklistEntryEntity::toDomain),
        attachments = attachmentDao.findAll().map(AttachmentEntity::toDomain),
        boardCards = boardCardDao.findAll().map(BoardCardEntity::toDomain),
        boardTodoItems = boardTodoItemDao.findAll().map(BoardTodoItemEntity::toDomain),
        boardTags = boardTagDao.listAll().map(BoardTagEntity::toDomain),
        boardCardTags = boardCardTagDao.findAll().map(BoardCardTagEntity::toDomain),
        boardCardLinks = boardCardLinkDao.findAll().map {
            com.phonlynn.oreplan.domain.model.BoardCardLink(
                cardId = it.cardId,
                linkedCardId = it.linkedCardId,
                // 以前这里没带 createdAt（备份 codec 也漏了这个键），
                // 结果「关联建立时间」在恢复后被改写成恢复那一刻。
                createdAt = Instant.ofEpochMilli(it.createdAt),
            )
        },
        habitLogs = habitLogDao.listAll().map { com.phonlynn.oreplan.domain.model.HabitLog(it.itemId, it.epochDay) },
        quantityLogs = quantityLogDao.listAll().map(QuantityLogEntity::toDomain),
        dailyReviews = dailyReviewDao.listAll().map(DailyReviewEntity::toDomain),
        // v4 新增。
        focusSessions = focusSessionDao.findAll().map(FocusSessionEntity::toDomain),
        entityExt = entityExtDao.findAll().mapNotNull { it.toRecord() },
        // v5 新增：用户能自定义的一切设置（密钥类被排除 / 脱敏）。
        settings = exportSettings(),
    )

    /**
     * 导出 `app_meta` 里**该带走的那部分**。
     *
     * 走 [BackupKeys] 的白名单 —— 见那里的说明：白名单漏一个只是少备份一项，
     * 黑名单漏一个就是**密钥泄露**，两类错误的代价不对等。
     *
     * 脱敏（剥掉 API Key）也在这一层做：`scrub` 返回 null 表示"解析不了、
     * 宁可整条不带走"，绝不原样输出。
     */
    private suspend fun exportSettings(): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        // ① 整体带走的几项
        backupKeys.forEach { key ->
            val raw = appMetaDao.find(key)?.value ?: return@forEach
            BackupKeys.scrub(key, raw)?.let { out[key] = it }
        }

        // ② 云同步的**偏好**（一个键一个值，不是一段 JSON）
        //    凭据（r2_access_key / r2_secret / master_key）不在这个集合里。
        BackupKeys.SYNC_PREFERENCE_KEYS.forEach { key ->
            val raw = appMetaDao.find(key)?.value ?: return@forEach
            BackupKeys.scrub(key, raw)?.let { out[key] = it }
        }
        return out
    }

    /**
     * 用快照整体替换现有数据。
     *
     * 删除顺序必须**先子后父**：`item_tags` 引用 items 和 tags、`reminders` 与
     * `recurrence_exceptions` 引用 items、`course_sessions` 引用 courses。
     * 反过来删会触发外键约束失败，整个恢复回滚。
     */
    suspend fun restore(snapshot: BackupSnapshot) = database.withTransaction {
        boardCardTagDao.clearAll()
        boardCardLinkDao.clearAll()
        boardTodoItemDao.clearAll()
        boardCardDao.clearAll()
        boardTagDao.clearAll()
        habitLogDao.clearAll()
        quantityLogDao.clearAll()
        dailyReviewDao.clearAll()
        // 专注记录有外键指向 items，必须在清空 items **之前**先清掉，
        // 否则清 items 会触发对 focus_sessions 的 SET NULL（虽然不报错，但白做一遍）。
        focusSessionDao.clearAll()
        entityExtDao.clearAll()
        tagDao.clearAllLinks()
        reminderDao.clearAll()
        exceptionDao.clearAll()
        noteBlockDao.clearAll()
        checklistDao.clearAll()
        attachmentDao.clearAll()
        itemDao.clearAll()
        courseDao.clearAllSessions()
        courseDao.clearAllCourses()
        tagDao.clearAll()
        termDao.clearAll()

        snapshot.terms.forEach { termDao.upsert(it.toEntity()) }
        snapshot.courses.forEach { courseDao.upsertCourse(it.toEntity()) }
        snapshot.courseSessions.forEach { courseDao.upsertSession(it.toEntity()) }
        snapshot.items.forEach { itemDao.upsert(it.toEntity()) }
        // 专注记录有外键指向 items，必须在条目**之后**插入，否则外键约束失败、整次恢复回滚。
        if (snapshot.focusSessions.isNotEmpty()) {
            focusSessionDao.upsertAll(snapshot.focusSessions.map { it.toEntity() })
        }
        snapshot.tags.forEach { tagDao.upsert(it.toEntity()) }
        if (snapshot.itemTags.isNotEmpty()) {
            tagDao.insertItemTags(
                snapshot.itemTags.distinct().map { ItemTagCrossRef(it.itemId, it.tagId) },
            )
        }
        snapshot.reminders.forEach { reminderDao.upsert(it.toEntity()) }
        snapshot.recurrenceExceptions.forEach { exceptionDao.upsert(it.toEntity()) }
        // 三张新表没有外键，顺序随意；用批量插入而不是逐条 upsert，避免大备份恢复时慢
        if (snapshot.noteBlocks.isNotEmpty()) {
            noteBlockDao.upsertAll(snapshot.noteBlocks.map(NoteBlock::toEntity))
        }
        if (snapshot.checklistEntries.isNotEmpty()) {
            checklistDao.upsertAll(snapshot.checklistEntries.map(ChecklistEntry::toEntity))
        }
        if (snapshot.attachments.isNotEmpty()) {
            attachmentDao.upsertAll(snapshot.attachments.map(Attachment::toEntity))
        }
        // v3 新表没有外键，顺序随意。
        if (snapshot.boardCards.isNotEmpty()) {
            snapshot.boardCards.forEach { boardCardDao.upsert(it.toEntity()) }
        }
        if (snapshot.boardTodoItems.isNotEmpty()) {
            boardTodoItemDao.upsertAll(snapshot.boardTodoItems.map { it.toEntity() })
        }
        if (snapshot.boardTags.isNotEmpty()) {
            snapshot.boardTags.forEach { boardTagDao.upsert(it.toEntity()) }
        }
        if (snapshot.boardCardTags.isNotEmpty()) {
            // 标签为单标签关系：旧备份里的多标签只保留每张卡的第一条。
            boardCardTagDao.insertAll(snapshot.boardCardTags.distinctBy { it.cardId }.map { it.toEntity() })
        }
        if (snapshot.boardCardLinks.isNotEmpty()) {
            boardCardLinkDao.insertAll(
                snapshot.boardCardLinks.map {
                    com.phonlynn.oreplan.data.local.entity.BoardCardLinkEntity(
                        cardId = it.cardId,
                        linkedCardId = it.linkedCardId,
                        // 优先用备份里的建立时刻；旧备份（v3 及以前）没有这个键，
                        // 才退回当前时刻 —— 与旧行为一致，不会更糟。
                        createdAt = it.createdAt?.toEpochMilli() ?: System.currentTimeMillis(),
                    )
                },
            )
        }
        if (snapshot.habitLogs.isNotEmpty()) {
            snapshot.habitLogs.forEach { habitLogDao.insert(it.toEntity()) }
        }
        if (snapshot.quantityLogs.isNotEmpty()) {
            snapshot.quantityLogs.forEach { quantityLogDao.insert(it.toEntity()) }
        }
        if (snapshot.dailyReviews.isNotEmpty()) {
            snapshot.dailyReviews.forEach { dailyReviewDao.upsert(it.toEntity()) }
        }
        // 扩展抽屉没有外键，顺序随意。
        if (snapshot.entityExt.isNotEmpty()) {
            val now = System.currentTimeMillis()
            entityExtDao.upsertAll(snapshot.entityExt.map { it.toEntity(now) })
        }

        /*
         * v5：恢复设置。
         *
         * ⚠️ **只覆盖备份里带的键，不动的键原样留着** —— 这是与其它表
         * "整体替换"刻意不同的地方：
         *
         * · 数据表是用户的**内容**，整体替换是对的（备份就是全部）
         * · 而 `app_meta` 里还混着**不能跨设备的键**（`sync.master_key` 绑本机
         *   Keystore、`sync.device_id` 是本机身份）。备份里没有它们，
         *   若这里 clearAll 就会**把本机的同步身份抹掉**，用户得重新配一遍。
         *
         * 旧备份（v4 及以前）没有 `settings`，这里是空 Map，等于什么都不做 ——
         * 与旧行为一致，不会更糟。
         */
        snapshot.settings.forEach { (key, value) ->
            // 白名单之外的键不该出现在备份里；真出现了也**不写进库** ——
            // 那是别人伪造的备份，可能塞进来一个 sync.master_key。
            if (!BackupKeys.isRestorable(key)) return@forEach
            appMetaDao.upsert(AppMetaEntity(key = key, value = value))
        }
    }
}
