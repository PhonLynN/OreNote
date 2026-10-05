package com.phonlynn.oreplan.v2.screens

import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **日程详情的数据装配**（2026-09-23 抽出）。
 *
 * # 为什么要抽出来
 *
 * 详情浮层（设计稿 u0QP0i）以前只能在日程页里打开 —— 因为装配逻辑写在
 * `CalendarV2ViewModel.loadDetail(key)` 里，且**依赖日程页自己的议程列表**
 *（要先在 `agendaGroups` 里按 key 找到那一行）。
 *
 * 于是今日页点日程时根本没有详情可用，只能直接跳编辑页
 *（用户报告：「点击今日页的日程无法进入日程详情，而是先直接进入编辑页面」）。
 *
 * 现在装配逻辑独立成一个可注入的类：**任何页面**只要手里有
 * 「标题 / 时间 / 地点 / 分类」这些**行内已有信息**，就能装配出详情。
 * 日程页与今日页共用它，行为必然一致。
 *
 * # 职责边界
 *
 * - **调用方提供**：行上已经显示出来的信息（标题、时间文本、时长、地点、分类）。
 *   这些由各自的 AgendaBuilder 展开结果决定，不在这里重算，避免两套日程展开逻辑。
 * - **本类补充**：提醒文案、备注、图片、文件，以及「待办 or 日程」的分类判定。
 */
@Singleton
class ScheduleDetailLoader @Inject constructor(
    private val itemRepository: ItemRepository,
    private val reminderRepository: ReminderRepository,
    private val noteBlockRepository: NoteBlockRepository,
    private val attachmentRepository: AttachmentRepository,
) {

    /**
     * 装配详情。
     *
     * @param itemId       条目 id（日程/待办）；课程为 null
     * @param courseId     课程 id；普通条目为 null
     * @param dateText     日期文案（各页面自己格式化，例如「9月23日 周三」）
     */
    suspend fun load(
        itemId: String?,
        courseId: String?,
        title: String,
        timeText: String?,
        durationText: String?,
        dateText: String?,
        locationText: String?,
        isCourse: Boolean,
    ): EventDetailUi {
        val item = itemId?.let { itemRepository.getById(it) }
        val reminders = itemId?.let { reminderRepository.observeOf(it).first() }.orEmpty()
        // 课程没有 itemId：它的备注块挂在 courseId 上（与待办同一张表、同一套读写）。
        val noteBlocks = (itemId ?: courseId)?.let { noteBlockRepository.getOf(it) }.orEmpty()
        // 附件有两处来源：item 级 + **每个备注块**（编辑页添加附件时 owner 是备注块 id）。
        // 只查 item 级的话详情页一条附件都拿不到 —— 这正是"加了附件但详情里啥都没有"的根因。
        val itemAttachments = itemId?.let { attachmentRepository.getOf(it) }.orEmpty()
        // 每个备注小节的附件挂在**小节 id** 上：按小节分组收集，
        // 展示端才能「同组内不画线、组与组之间画线」。
        val noteSections = noteBlocks.map { block ->
            val own = attachmentRepository.getOf(block.id)
            NoteSectionUi(
                body = block.body,
                photos = own.filter { it.isImage }.map { it.storedPath },
                files = own.filterNot { it.isImage },
            )
        }.toMutableList()
        // 兼容旧数据：条目级正文 / 条目级附件（备注小节模型之前写入的）如果没被任何小节覆盖，
        // 单独作为一个小节放在最后，否则它们会从详情里消失。
        val legacyBody = item?.note?.takeIf { it.isNotBlank() }
        if (legacyBody != null || itemAttachments.isNotEmpty()) {
            noteSections += NoteSectionUi(
                body = legacyBody.orEmpty(),
                photos = itemAttachments.filter { it.isImage }.map { it.storedPath },
                files = itemAttachments.filterNot { it.isImage },
            )
        }
        val attachments = buildList {
            addAll(itemAttachments)
            noteBlocks.forEach { block -> addAll(attachmentRepository.getOf(block.id)) }
        }

        val isTaskItem = item?.kind == com.phonlynn.oreplan.domain.model.ItemKind.TASK
        val createdText = item
            ?.takeIf { isTaskItem }
            ?.let { created ->
                val c = created.createdAt.atZone(java.time.ZoneId.systemDefault())
                "${c.monthValue}月${c.dayOfMonth}日 创建"
            }
        // 待办没有截止时间时，创建时间**占用「日期」行的位置**（一行就够，
        // 不能上面一排小字创建时间、下面再写一次，那会变成两排一样的字）。
        val hasDue = item?.softDueAt != null

        val category = when {
            isCourse || courseId != null -> "课程"
            item?.kind == com.phonlynn.oreplan.domain.model.ItemKind.TASK -> "待办"
            else -> "日程"
        }

        return EventDetailUi(
            itemId = itemId,
            courseId = courseId,
            categoryLabel = category,
            title = title,
            timeText = timeText,
            durationText = durationText,
            dateText = if (isTaskItem && !hasDue) createdText else dateText,
            // 有截止时间时才把创建时间作为浅灰小字显示在标题下。
            createdText = createdText?.takeIf { isTaskItem && hasDue },
            // 调用方没传地点时回落到条目自己的 location ——
            // 待办的地点就存在这里；原来调用方传 null，导致「填了地点但详情里不显示」（用户 2026-09-24）。
            locationText = locationText ?: item?.location,
            reminderText = reminders.firstOrNull { (it.offsetMinutes ?: 0) != 0 }
                ?.let { describeReminder(it) },
            // 老字段保留（有调用方用它做「有没有备注」的判断），但展示端改用 notes。
            noteText = noteSections.firstOrNull { it.body.isNotBlank() }?.body,
            photoPaths = attachments.filter { it.isImage }.map { it.storedPath },
            fileAttachments = attachments.filterNot { it.isImage },
            notes = noteSections,
            isCourse = isCourse || courseId != null,
            // 待办与日程共用这个浮层，但右上角按钮不同：
            // 待办是「三点菜单」（先进详情，再从菜单进编辑/删除），日程是「关闭」。
            isTask = item?.kind == com.phonlynn.oreplan.domain.model.ItemKind.TASK,
            isDone = item?.status == com.phonlynn.oreplan.domain.model.ItemStatus.DONE,
        )
    }

    /** 提醒偏移 → 文案（「提前 15 分钟」）。 */
    fun describeReminder(r: Reminder): String {
        val offset = r.offsetMinutes ?: 15
        return when {
            offset % (24 * 60) == 0 -> "提前 ${offset / (24 * 60)} 天"
            offset % 60 == 0 -> "提前 ${offset / 60} 小时"
            else -> "提前 $offset 分钟"
        }
    }

    companion object {
        /** 分钟数 → 「2 小时 30 分」；用于定时事件的时长文案。 */
        fun durationText(startMinute: Int?, endMinute: Int?): String? {
            if (startMinute == null || endMinute == null || endMinute <= startMinute) return null
            val total = endMinute - startMinute
            val h = total / 60
            val m = total % 60
            return when {
                h > 0 && m > 0 -> "$h 小时 $m 分"
                h > 0 -> "$h 小时"
                else -> "$m 分钟"
            }
        }
    }
}
