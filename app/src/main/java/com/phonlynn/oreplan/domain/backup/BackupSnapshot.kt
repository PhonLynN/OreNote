package com.phonlynn.oreplan.domain.backup

import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.DailyReview
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.HabitLog
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.model.Tag
import com.phonlynn.oreplan.domain.model.Term
import java.time.Instant

/**
 * 一次完整的备份快照。
 *
 * 用领域模型而不是数据库实体来承载：以后表结构变了（比如给 items 加列），
 * 只要 Mapper 还在，旧备份文件依然能读进来。用实体的话，备份格式会和表结构绑死。
 *
 * v2 新增了笔记小节 / 备忘清单 / 附件索引三组数据。它们都是**可选数组**，
 * 所以 v1 的旧备份仍然能导入（缺失即空）。
 *
 * 注意：附件的**二进制不进备份**，只带元信息（文件名/类型/大小/相对路径）。
 * 恢复后清单项与附件条都会回来，但需要重新上传一次文件才能在应用里打开。
 */
data class BackupSnapshot(
    val formatVersion: Int = BackupCodec.FORMAT_VERSION,
    val exportedAt: Instant,
    val terms: List<Term> = emptyList(),
    val courses: List<Course> = emptyList(),
    val courseSessions: List<CourseSession> = emptyList(),
    val items: List<Item> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val itemTags: List<ItemTagLink> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val recurrenceExceptions: List<RecurrenceException> = emptyList(),
    val noteBlocks: List<NoteBlock> = emptyList(),
    val checklistEntries: List<ChecklistEntry> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    // v3 新增：白板 / 目标记录 / 每日复盘。全部可选，旧备份缺失即空。
    val boardCards: List<BoardCard> = emptyList(),
    val boardTodoItems: List<BoardTodoItem> = emptyList(),
    val boardTags: List<BoardTag> = emptyList(),
    val boardCardTags: List<BoardCardTag> = emptyList(),
    val boardCardLinks: List<BoardCardLink> = emptyList(),
    val habitLogs: List<HabitLog> = emptyList(),
    val quantityLogs: List<QuantityLog> = emptyList(),
    val dailyReviews: List<DailyReview> = emptyList(),
    // v4 新增：专注记录 + 扩展抽屉。同样是可选数组，v1~v3 的旧备份缺失即空。
    val focusSessions: List<FocusSession> = emptyList(),
    val entityExt: List<ExtRecord> = emptyList(),
    /**
     * v5 新增：**用户能自定义的一切设置**（`app_meta` 里的 KV）。
     *
     * ## 为什么是一个 Map，而不是给每类设置加一个字段
     *
     * 设置会一直加。每加一类就改一次备份格式，等于每次都要动编解码、
     * 还要考虑旧备份兼容 —— 而这些东西的共同点是**本来就是 key→value**。
     * 原样带走，以后加设置**不用改备份**。
     *
     * ## ⚠️ 哪些进、哪些不进
     *
     * 用户口径：「一切用户能够自定义的东西都应该能进备份，除了 api 密钥这种东西」。
     * 所以是**白名单**（见 `BackupKeys`），而不是"把 app_meta 整表搬走"：
     *
     * | 键 | 进备份 |
     * |---|---|
     * | `app_settings_v2`（外观/提醒/白板等全部设置） | ✅ |
     * | `ai.settings`（**API Key 会被剥掉**） | ✅ |
     * | `ai.conversations`（全部对话） | ✅ |
     * | `sync.enabled` 等同步偏好（**凭据被剥掉**） | ✅ |
     * | `sync.master_key`（Keystore 包裹的主密钥） | ❌ **绝不能出设备** |
     *
     * ⚠️ 白名单而不是黑名单：**新加一个密钥类的键时，默认是"不进备份"** ——
     * 漏了只是少备份一项，而黑名单漏了就是**密钥泄露**。这个方向的错误代价不对等。
     */
    val settings: Map<String, String> = emptyMap(),
) {
    val totalRows: Int
        get() = terms.size + courses.size + courseSessions.size + items.size +
            tags.size + itemTags.size + reminders.size + recurrenceExceptions.size +
            noteBlocks.size + checklistEntries.size + attachments.size +
            boardCards.size + boardTodoItems.size + boardTags.size + boardCardTags.size +
            boardCardLinks.size + habitLogs.size + quantityLogs.size + dailyReviews.size +
            focusSessions.size + entityExt.size + settings.size
}

/** 条目与标签的关联。备份时要一起带走，否则恢复出来标签全丢了。 */
data class ItemTagLink(
    val itemId: String,
    val tagId: String,
)
