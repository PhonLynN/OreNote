package com.phonlynn.oreplan.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.dao.AppMetaDao
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据层依赖。
 *
 * 数据库是单例（Room 实例本身线程安全、创建成本高），DAO 每次从同一个数据库取 ——
 * DAO 是轻量代理，单独做单例没有收益。
 *
 * 外键约束由 Room 在打开数据库时自动开启，所以 `course_sessions` 上的级联删除是生效的。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v4 → v5：白板卡片与标签改为「单标签」关系。
     * 表结构不变，只是把每张卡片可能残留的多条关联保留最早的一条（MIN(rowid)），
     * 其余删除 —— 因此是手写的数据迁移，而不是处理 schema 的 AutoMigration。
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM board_card_tags WHERE rowid NOT IN " +
                    "(SELECT MIN(rowid) FROM board_card_tags GROUP BY cardId)",
            )
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(MIGRATION_4_5)
        .build()

    @Provides
    fun provideAppMetaDao(database: AppDatabase): AppMetaDao = database.appMetaDao()

    @Provides
    fun provideItemDao(database: AppDatabase): ItemDao = database.itemDao()

    @Provides
    fun provideTermDao(database: AppDatabase): TermDao = database.termDao()

    @Provides
    fun provideCourseDao(database: AppDatabase): CourseDao = database.courseDao()

    @Provides
    fun provideTagDao(database: AppDatabase): TagDao = database.tagDao()

    @Provides
    fun provideReminderDao(database: AppDatabase): ReminderDao = database.reminderDao()

    @Provides
    fun provideRecurrenceExceptionDao(
        database: AppDatabase,
    ): RecurrenceExceptionDao = database.recurrenceExceptionDao()

    @Provides
    fun provideNoteBlockDao(database: AppDatabase): NoteBlockDao = database.noteBlockDao()

    @Provides
    fun provideChecklistDao(database: AppDatabase): ChecklistDao = database.checklistDao()

    @Provides
    fun provideAttachmentDao(database: AppDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideBoardCardDao(database: AppDatabase): BoardCardDao = database.boardCardDao()

    @Provides
    fun provideBoardTodoItemDao(database: AppDatabase): BoardTodoItemDao = database.boardTodoItemDao()

    @Provides
    fun provideBoardTagDao(database: AppDatabase): BoardTagDao = database.boardTagDao()

    @Provides
    fun provideBoardCardTagDao(database: AppDatabase): BoardCardTagDao = database.boardCardTagDao()

    @Provides
    fun provideBoardCardLinkDao(database: AppDatabase): BoardCardLinkDao = database.boardCardLinkDao()

    @Provides
    fun provideHabitLogDao(database: AppDatabase): HabitLogDao = database.habitLogDao()

    @Provides
    fun provideQuantityLogDao(database: AppDatabase): QuantityLogDao = database.quantityLogDao()

    @Provides
    fun provideDailyReviewDao(database: AppDatabase): DailyReviewDao = database.dailyReviewDao()

    @Provides
    fun provideEntityExtDao(database: AppDatabase): EntityExtDao = database.entityExtDao()

    @Provides
    fun provideFocusSessionDao(database: AppDatabase): FocusSessionDao = database.focusSessionDao()
}
