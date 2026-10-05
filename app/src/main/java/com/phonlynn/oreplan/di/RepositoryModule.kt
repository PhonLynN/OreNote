package com.phonlynn.oreplan.di

import com.phonlynn.oreplan.data.repository.RoomAttachmentRepository
import com.phonlynn.oreplan.data.repository.RoomBoardRepository
import com.phonlynn.oreplan.data.repository.RoomChecklistRepository
import com.phonlynn.oreplan.data.repository.RoomCourseRepository
import com.phonlynn.oreplan.data.repository.RoomEntityExtRepository
import com.phonlynn.oreplan.data.repository.RoomFocusRepository
import com.phonlynn.oreplan.data.repository.RoomGoalLogRepository
import com.phonlynn.oreplan.data.repository.RoomItemRepository
import com.phonlynn.oreplan.data.repository.RoomNoteBlockRepository
import com.phonlynn.oreplan.data.repository.RoomRecurrenceExceptionRepository
import com.phonlynn.oreplan.data.repository.RoomReminderRepository
import com.phonlynn.oreplan.data.repository.RoomTagRepository
import com.phonlynn.oreplan.data.repository.RoomTermRepository
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.repository.ChecklistRepository
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.FocusRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.domain.repository.TagRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓储接口到实现的绑定。
 *
 * 用例只依赖 `domain.repository` 里的接口，所以换成别的实现（测试替身、将来可能的
 * 文件式存储）只需要改这里。**注意绑定是按能力拆开的**：以后接入笔记就是再加一行
 * `bindNoteRepository`，现有绑定一行都不用动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindItemRepository(impl: RoomItemRepository): ItemRepository

    @Binds
    @Singleton
    abstract fun bindTermRepository(impl: RoomTermRepository): TermRepository

    @Binds
    @Singleton
    abstract fun bindCourseRepository(impl: RoomCourseRepository): CourseRepository

    @Binds
    @Singleton
    abstract fun bindTagRepository(impl: RoomTagRepository): TagRepository

    @Binds
    @Singleton
    abstract fun bindReminderRepository(impl: RoomReminderRepository): ReminderRepository

    @Binds
    @Singleton
    abstract fun bindRecurrenceExceptionRepository(
        impl: RoomRecurrenceExceptionRepository,
    ): RecurrenceExceptionRepository

    @Binds
    @Singleton
    abstract fun bindNoteBlockRepository(impl: RoomNoteBlockRepository): NoteBlockRepository

    @Binds
    @Singleton
    abstract fun bindChecklistRepository(impl: RoomChecklistRepository): ChecklistRepository

    @Binds
    @Singleton
    abstract fun bindAttachmentRepository(impl: RoomAttachmentRepository): AttachmentRepository

    @Binds
    @Singleton
    abstract fun bindBoardRepository(impl: RoomBoardRepository): BoardRepository

    @Binds
    @Singleton
    abstract fun bindGoalLogRepository(impl: RoomGoalLogRepository): GoalLogRepository

    /** 扩展抽屉：新功能挂附属字段的入口，见 `EntityExtRepository`。 */
    @Binds
    @Singleton
    abstract fun bindEntityExtRepository(impl: RoomEntityExtRepository): EntityExtRepository

    /** 专注记录：数据链里「待办 → 专注」那一段。 */
    @Binds
    @Singleton
    abstract fun bindFocusRepository(impl: RoomFocusRepository): FocusRepository
}
