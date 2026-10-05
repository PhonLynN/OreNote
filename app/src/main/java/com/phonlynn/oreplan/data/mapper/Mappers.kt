package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.core.time.WeekParity
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.ChecklistCategory
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.ExceptionAction
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.StepOrderMode
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.model.Tag
import com.phonlynn.oreplan.domain.model.Term
import com.phonlynn.oreplan.data.local.entity.AttachmentEntity
import com.phonlynn.oreplan.data.local.entity.ChecklistEntryEntity
import com.phonlynn.oreplan.data.local.entity.CourseEntity
import com.phonlynn.oreplan.data.local.entity.CourseSessionEntity
import com.phonlynn.oreplan.data.local.entity.ItemEntity
import com.phonlynn.oreplan.data.local.entity.NoteBlockEntity
import com.phonlynn.oreplan.data.local.entity.RecurrenceExceptionEntity
import com.phonlynn.oreplan.data.local.entity.ReminderEntity
import com.phonlynn.oreplan.data.local.entity.TagEntity
import com.phonlynn.oreplan.data.local.entity.TermEntity
import java.time.Instant
import java.time.LocalDate

/**
 * 领域模型与数据库实体之间的转换。
 *
 * 这一层存在的意义不是「好看」，而是**隔离**：数据库表结构变化只影响这里，
 * UI 与用例层不知情。以后接入笔记时，笔记表怎么设计都不会波及现有代码。
 *
 * 枚举转换全部走「解析失败则降级到默认值」而不是抛异常 —— 这些字段可能是旧版本
 * 写下的、或者来自以后要做的导入功能，脏数据的正确反应是降级显示，不是崩溃。
 */

internal fun ItemKind.toDbValue(): String = name
internal fun String.toItemKind(): ItemKind =
    ItemKind.entries.firstOrNull { it.name == this } ?: ItemKind.TASK

internal fun ItemStatus.toDbValue(): String = name
internal fun String.toItemStatus(): ItemStatus =
    ItemStatus.entries.firstOrNull { it.name == this } ?: ItemStatus.TODO

internal fun GoalType.toDbValue(): String = name
internal fun String?.toGoalTypeOrNull(): GoalType? =
    this?.let { raw -> GoalType.entries.firstOrNull { it.name == raw } }

internal fun StepOrderMode.toDbValue(): String = name
internal fun String?.toStepOrderModeOrNull(): StepOrderMode? =
    this?.let { raw -> StepOrderMode.entries.firstOrNull { it.name == raw } }

internal fun WeekParity.toDbValue(): String = name
internal fun String.toWeekParity(): WeekParity =
    WeekParity.entries.firstOrNull { it.name == this } ?: WeekParity.ALL

internal fun ExceptionAction.toDbValue(): String = name
internal fun String.toExceptionAction(): ExceptionAction =
    ExceptionAction.entries.firstOrNull { it.name == this } ?: ExceptionAction.DELETED

internal fun Instant?.toDbValue(): Long? = this?.toEpochMilli()
internal fun Long?.toInstantOrNull(): Instant? = this?.let(Instant::ofEpochMilli)

internal fun LocalDate?.toEpochDayValue(): Int? = this?.toEpochDay()?.toInt()
internal fun Int?.toLocalDateOrNull(): LocalDate? = this?.let { LocalDate.ofEpochDay(it.toLong()) }

// ---------------------------------------------------------------- 条目

internal fun ItemEntity.toDomain(): Item = Item(
    id = id,
    kind = kind.toItemKind(),
    title = title,
    note = note,
    status = status.toItemStatus(),
    priority = priority,
    startAt = startAt.toInstantOrNull(),
    endAt = endAt.toInstantOrNull(),
    allDay = allDay,
    rrule = rrule,
    rruleUntil = rruleUntil.toInstantOrNull(),
    softDueAt = softDueAt.toInstantOrNull(),
    planStartDay = planStartDay.toLocalDateOrNull(),
    planEndDay = planEndDay.toLocalDateOrNull(),
    parentId = parentId,
    treePath = treePath,
    depth = depth,
    progress = progress,
    completedAt = completedAt.toInstantOrNull(),
    colorTag = colorTag,
    orderIndex = orderIndex,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    goalType = goalType.toGoalTypeOrNull(),
    unit = unit,
    targetValue = targetValue,
    stepOrderMode = stepOrderMode.toStepOrderModeOrNull(),
    autoAdvance = autoAdvance,
    showOnToday = showOnToday,
    pinned = pinned,
    category = category,
    goalNote = goalNote,
    location = location,
    groupId = groupId,
)

internal fun Item.toEntity(): ItemEntity = ItemEntity(
    id = id,
    kind = kind.toDbValue(),
    title = title,
    note = note,
    status = status.toDbValue(),
    priority = priority,
    startAt = startAt.toDbValue(),
    endAt = endAt.toDbValue(),
    allDay = allDay,
    rrule = rrule,
    rruleUntil = rruleUntil.toDbValue(),
    softDueAt = softDueAt.toDbValue(),
    planStartDay = planStartDay.toEpochDayValue(),
    planEndDay = planEndDay.toEpochDayValue(),
    parentId = parentId,
    treePath = treePath,
    depth = depth,
    progress = progress,
    completedAt = completedAt.toDbValue(),
    colorTag = colorTag,
    orderIndex = orderIndex,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    goalType = goalType?.toDbValue(),
    unit = unit,
    targetValue = targetValue,
    stepOrderMode = stepOrderMode?.toDbValue(),
    autoAdvance = autoAdvance,
    showOnToday = showOnToday,
    pinned = pinned,
    category = category,
    goalNote = goalNote,
    location = location,
    groupId = groupId,
)

// ---------------------------------------------------------------- 学期

internal fun TermEntity.toDomain(): Term = Term(
    id = id,
    name = name,
    startDate = LocalDate.ofEpochDay(startDate.toLong()),
    totalWeeks = totalWeeks,
    isActive = isActive,
)

internal fun Term.toEntity(): TermEntity = TermEntity(
    id = id,
    name = name,
    startDate = startDate.toEpochDay().toInt(),
    totalWeeks = totalWeeks,
    isActive = isActive,
)

// ---------------------------------------------------------------- 课程

internal fun CourseEntity.toDomain(): Course = Course(
    id = id,
    name = name,
    teacher = teacher,
    defaultLocation = defaultLocation,
    colorHex = colorHex,
    note = note,
    credit = credit,
)

internal fun Course.toEntity(): CourseEntity = CourseEntity(
    id = id,
    name = name,
    teacher = teacher,
    defaultLocation = defaultLocation,
    colorHex = colorHex,
    note = note,
    credit = credit,
)

internal fun CourseSessionEntity.toDomain(): CourseSession = CourseSession(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    startWeek = startWeek,
    endWeek = endWeek,
    parity = parity.toWeekParity(),
    location = location,
    note = note,
)

internal fun CourseSession.toEntity(): CourseSessionEntity = CourseSessionEntity(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startMinuteOfDay = startMinuteOfDay,
    endMinuteOfDay = endMinuteOfDay,
    startWeek = startWeek,
    endWeek = endWeek,
    parity = parity.toDbValue(),
    location = location,
    note = note,
)

// ---------------------------------------------------------------- 标签

internal fun TagEntity.toDomain(): Tag = Tag(id = id, name = name, colorHex = colorHex)

internal fun Tag.toEntity(): TagEntity = TagEntity(id = id, name = name, colorHex = colorHex)

// ---------------------------------------------------------------- 提醒

internal fun ReminderEntity.toDomain(): Reminder = Reminder(
    id = id,
    itemId = itemId,
    triggerAt = Instant.ofEpochMilli(triggerAt),
    offsetMinutes = offsetMinutes,
    enabled = enabled,
)

internal fun Reminder.toEntity(): ReminderEntity = ReminderEntity(
    id = id,
    itemId = itemId,
    triggerAt = triggerAt.toEpochMilli(),
    offsetMinutes = offsetMinutes,
    enabled = enabled,
)

// ---------------------------------------------------------------- 重复例外

internal fun RecurrenceExceptionEntity.toDomain(): RecurrenceException = RecurrenceException(
    id = id,
    itemId = itemId,
    date = LocalDate.ofEpochDay(date.toLong()),
    action = action.toExceptionAction(),
    overrideStartAt = overrideStartAt.toInstantOrNull(),
    overrideEndAt = overrideEndAt.toInstantOrNull(),
    overrideTitle = overrideTitle,
)

internal fun RecurrenceException.toEntity(): RecurrenceExceptionEntity = RecurrenceExceptionEntity(
    id = id,
    itemId = itemId,
    date = date.toEpochDay().toInt(),
    action = action.toDbValue(),
    overrideStartAt = overrideStartAt.toDbValue(),
    overrideEndAt = overrideEndAt.toDbValue(),
    overrideTitle = overrideTitle,
)

// ---------------------------------------------------------------- 计划笔记小节

internal fun NoteBlockEntity.toDomain(): NoteBlock = NoteBlock(
    id = id,
    ownerId = ownerId,
    heading = heading,
    body = body,
    orderIndex = orderIndex,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun NoteBlock.toEntity(): NoteBlockEntity = NoteBlockEntity(
    id = id,
    ownerId = ownerId,
    heading = heading,
    body = body,
    orderIndex = orderIndex,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

// ---------------------------------------------------------------- 备忘清单

internal fun ChecklistCategory.toDbValue(): String = name

/** 认不出的分类降级到「其他」—— 数据可能来自旧版本或以后的导入功能。 */
internal fun String.toChecklistCategory(): ChecklistCategory =
    ChecklistCategory.entries.firstOrNull { it.name == this } ?: ChecklistCategory.OTHER

internal fun ChecklistEntryEntity.toDomain(): ChecklistEntry = ChecklistEntry(
    id = id,
    itemId = itemId,
    category = category.toChecklistCategory(),
    title = title,
    done = done,
    orderIndex = orderIndex,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun ChecklistEntry.toEntity(): ChecklistEntryEntity = ChecklistEntryEntity(
    id = id,
    itemId = itemId,
    category = category.toDbValue(),
    title = title,
    done = done,
    orderIndex = orderIndex,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

// ---------------------------------------------------------------- 附件

internal fun AttachmentOwner.toDbValue(): String = name

internal fun String.toAttachmentOwner(): AttachmentOwner =
    AttachmentOwner.entries.firstOrNull { it.name == this } ?: AttachmentOwner.ITEM

internal fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = id,
    ownerType = ownerType.toAttachmentOwner(),
    ownerId = ownerId,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    storedPath = storedPath,
    createdAt = Instant.ofEpochMilli(createdAt),
)

internal fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
    id = id,
    ownerType = ownerType.toDbValue(),
    ownerId = ownerId,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    storedPath = storedPath,
    createdAt = createdAt.toEpochMilli(),
)
