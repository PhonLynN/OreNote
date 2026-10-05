package com.phonlynn.oreplan.domain.reminder

import com.phonlynn.oreplan.domain.expansion.OccurrenceExpander
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.Reminder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 算出「每个提醒下一次应该在什么时刻响」。纯函数。
 *
 * 为什么需要它：重复日程不应该也不可能给每一次发生都预先建一条提醒记录。数据库里
 * 一条提醒存的是「提前多少分钟」（以及首次触发时刻），真正排给系统的必须是
 * **下一个未来时刻**。这个换算逻辑独立出来，才能用普通单元测试覆盖，
 * 而不是靠「等 20 分钟看它响不响」。
 */
object ReminderPlanner {

    /** 搜索窗口。重复日程是无限序列，必须限定一个上界。 */
    const val HORIZON_DAYS = 60L

    data class Planned(
        val reminderId: String,
        val itemId: String,
        val title: String,
        val note: String?,
        val triggerAt: Instant,
    )

    fun plan(
        items: List<Item>,
        reminders: List<Reminder>,
        exceptionsByItem: Map<String, List<RecurrenceException>>,
        now: Instant,
        zone: ZoneId,
        horizonDays: Long = HORIZON_DAYS,
    ): List<Planned> {
        if (reminders.isEmpty()) return emptyList()
        val itemsById = items.associateBy { it.id }

        val planned = reminders.mapNotNull { reminder ->
            if (!reminder.enabled) return@mapNotNull null

            val item = itemsById[reminder.itemId] ?: return@mapNotNull null
            // 已取消或已完成的东西不该再响 —— 这是最容易让人恼怒的一类通知。
            if (item.status == ItemStatus.CANCELLED || item.status == ItemStatus.DONE) {
                return@mapNotNull null
            }
            val startAt = item.startAt ?: return@mapNotNull null

            val offset = Duration.ofMinutes((reminder.offsetMinutes ?: 0).toLong())
            val trigger = nextTrigger(
                item = item,
                startAt = startAt,
                exceptions = exceptionsByItem[item.id].orEmpty(),
                offset = offset,
                now = now,
                zone = zone,
                horizonDays = horizonDays,
            ) ?: return@mapNotNull null

            Planned(
                reminderId = reminder.id,
                itemId = item.id,
                title = item.title,
                note = item.note,
                triggerAt = trigger,
            )
        }

        return planned.sortedBy { it.triggerAt }
    }

    private fun nextTrigger(
        item: Item,
        startAt: Instant,
        exceptions: List<RecurrenceException>,
        offset: Duration,
        now: Instant,
        zone: ZoneId,
        horizonDays: Long,
    ): Instant? {
        if (!item.isRecurring) {
            val trigger = startAt.minus(offset)
            return trigger.takeIf { it > now }
        }

        // 重复日程：从今天往前留一天，向后搜索到窗口末尾，取第一个仍在未来的触发时刻。
        val today = now.atZone(zone).toLocalDate()
        val occurrences = OccurrenceExpander.expand(
            item = item,
            exceptions = exceptions,
            from = today.minusDays(1),
            to = today.plusDays(horizonDays),
            zone = zone,
        )
        return occurrences
            .asSequence()
            .map { it.startAt.minus(offset) }
            .firstOrNull { it > now }
    }
}
