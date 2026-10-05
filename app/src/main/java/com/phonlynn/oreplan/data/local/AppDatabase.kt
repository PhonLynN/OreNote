package com.phonlynn.oreplan.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import com.phonlynn.oreplan.data.local.dao.AppMetaDao
import com.phonlynn.oreplan.data.local.dao.AttachmentDao
import com.phonlynn.oreplan.data.local.dao.BoardCardDao
import com.phonlynn.oreplan.data.local.dao.BoardCardLinkDao
import com.phonlynn.oreplan.data.local.dao.BoardCardTagDao
import com.phonlynn.oreplan.data.local.dao.BoardTagDao
import com.phonlynn.oreplan.data.local.dao.BoardTodoItemDao
import com.phonlynn.oreplan.data.local.dao.ChecklistDao
import com.phonlynn.oreplan.data.local.dao.CourseDao
import com.phonlynn.oreplan.data.local.dao.DailyReviewDao
import com.phonlynn.oreplan.data.local.dao.EntityExtDao
import com.phonlynn.oreplan.data.local.dao.FocusSessionDao
import com.phonlynn.oreplan.data.local.dao.HabitLogDao
import com.phonlynn.oreplan.data.local.dao.ItemDao
import com.phonlynn.oreplan.data.local.dao.NoteBlockDao
import com.phonlynn.oreplan.data.local.dao.QuantityLogDao
import com.phonlynn.oreplan.data.local.dao.RecurrenceExceptionDao
import com.phonlynn.oreplan.data.local.dao.ReminderDao
import com.phonlynn.oreplan.data.local.dao.TagDao
import com.phonlynn.oreplan.data.local.dao.TermDao
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import com.phonlynn.oreplan.data.local.entity.AttachmentEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardLinkEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTodoItemEntity
import com.phonlynn.oreplan.data.local.entity.ChecklistEntryEntity
import com.phonlynn.oreplan.data.local.entity.CourseEntity
import com.phonlynn.oreplan.data.local.entity.CourseSessionEntity
import com.phonlynn.oreplan.data.local.entity.DailyReviewEntity
import com.phonlynn.oreplan.data.local.entity.EntityExtEntity
import com.phonlynn.oreplan.data.local.entity.FocusSessionEntity
import com.phonlynn.oreplan.data.local.entity.HabitLogEntity
import com.phonlynn.oreplan.data.local.entity.ItemEntity
import com.phonlynn.oreplan.data.local.entity.ItemTagCrossRef
import com.phonlynn.oreplan.data.local.entity.NoteBlockEntity
import com.phonlynn.oreplan.data.local.entity.QuantityLogEntity
import com.phonlynn.oreplan.data.local.entity.RecurrenceExceptionEntity
import com.phonlynn.oreplan.data.local.entity.ReminderEntity
import com.phonlynn.oreplan.data.local.entity.TagEntity
import com.phonlynn.oreplan.data.local.entity.TermEntity

/**
 * 应用数据库。
 *
 * 表结构对应方案第 2 节：
 *  - `items` 统一承载日程与规划（靠 kind 判别）；
 *  - `courses` / `course_sessions` 是**课表模板层**，不展开成日程存库；
 *  - `recurrence_exceptions` 承载重复日程的「删除本次 / 本次改期」。
 *
 * 没有 TypeConverter：实体一律用基本类型（Long/Int/String），
 * 时间与枚举的转换集中在 `data/mapper`。这样数据库层看到的东西最朴素，
 * 出问题时排查范围也最小。
 *
 * v2 只**新增了三张表**（计划笔记小节 / 备忘清单 / 附件索引），没有改任何现有列。
 * 正因为是纯新增，迁移交给 Room 的自动迁移生成 —— 手写 `CREATE TABLE` 要和 Room 期望的
 * schema 逐字节对齐（列顺序、NOT NULL、索引名），对不齐就是用户升级时数据库打不开，
 * 而这份 SQL 由编译器生成就不会对不齐。
 *
 * v5 是数据整理而不是结构变更：白板卡片与标签改为「单标签」关系，每张卡片残留的
 * 多余关联在 `DatabaseModule` 的手写迁移里清理（表结构不变，所以不走 AutoMigration）。
 */
@Database(
    entities = [
        AppMetaEntity::class,
        ItemEntity::class,
        TermEntity::class,
        CourseEntity::class,
        CourseSessionEntity::class,
        TagEntity::class,
        ItemTagCrossRef::class,
        ReminderEntity::class,
        RecurrenceExceptionEntity::class,
        NoteBlockEntity::class,
        ChecklistEntryEntity::class,
        AttachmentEntity::class,
        BoardCardEntity::class,
        BoardTodoItemEntity::class,
        BoardTagEntity::class,
        BoardCardTagEntity::class,
        BoardCardLinkEntity::class,
        HabitLogEntity::class,
        QuantityLogEntity::class,
        DailyReviewEntity::class,
        // v11 新增：扩展抽屉（全库共用的「新字段收纳柜」）+ 专注记录。
        EntityExtEntity::class,
        FocusSessionEntity::class,
    ],
    version = 11,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        // v7：board_cards 新增 imageLayout（可空，带 DEFAULT NULL）——纯加列，可自动迁移。
        AutoMigration(from = 6, to = 7),
        // v8：board_tags 新增 hideFromAll（布尔，DEFAULT 0）——纯加列，可自动迁移。
        AutoMigration(from = 7, to = 8),
        // v9：board_cards 新增动态置顶四列（全可空，DEFAULT NULL）——纯加列，可自动迁移。
        AutoMigration(from = 8, to = 9),
        // v10：items 新增 groupId（归属待办组，可空，DEFAULT NULL）——纯加列，可自动迁移。
        // 不复用 parentId：那个字段对任务是「关联事项」语义，一字段两含义会互相污染。
        AutoMigration(from = 9, to = 10),
        // v11：**只新增两张表，既有的 20 张表一列都没动。**
        //   entity_ext     —— 扩展抽屉：以后所有新字段都放这里，老表永不再改（可扩展性的地基）。
        //   focus_sessions —— 专注记录：靠 itemId 把「专注」接进数据链（课表→日程→待办→专注→复盘）。
        // 因为不碰任何既有列，所以仍然是「纯新增」这一最安全的迁移形态，可自动迁移。
        AutoMigration(from = 10, to = 11),
    ],
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appMetaDao(): AppMetaDao
    abstract fun itemDao(): ItemDao
    abstract fun termDao(): TermDao
    abstract fun courseDao(): CourseDao
    abstract fun tagDao(): TagDao
    abstract fun reminderDao(): ReminderDao
    abstract fun recurrenceExceptionDao(): RecurrenceExceptionDao
    abstract fun noteBlockDao(): NoteBlockDao
    abstract fun checklistDao(): ChecklistDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun boardCardDao(): BoardCardDao
    abstract fun boardTodoItemDao(): BoardTodoItemDao
    abstract fun boardTagDao(): BoardTagDao
    abstract fun boardCardTagDao(): BoardCardTagDao

    abstract fun boardCardLinkDao(): BoardCardLinkDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun quantityLogDao(): QuantityLogDao
    abstract fun dailyReviewDao(): DailyReviewDao

    abstract fun entityExtDao(): EntityExtDao
    abstract fun focusSessionDao(): FocusSessionDao

    companion object {
        const val NAME = "oreplan.db"
    }
}
