package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.ExceptionAction
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.Occurrence
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * 把一个条目在区间内展开成具体发生。**纯函数** —— 例外由调用方传入，
 * 所以它既可以被用例包一层去读数据库，也可以被议程聚合直接调用而不碰数据库。
 *
 * 单次日程和重复日程在这里合流：调用方拿到的是同一个 [Occurrence] 列表，
 * 不需要为「这条是不是重复的」写两套分支。
 */
object OccurrenceExpander {

    fun expand(
        item: Item,
        exceptions: List<RecurrenceException>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<Occurrence> {
        if (to < from) return emptyList()

        val start = item.startAt ?: return emptyList()
        val anchor = start.atZone(zone)
        val duration = item.endAt?.let { Duration.between(start, it) }

        val parsed = RecurrenceRule.parse(item.rrule)
        if (parsed == null) {
            // 单次日程：只要落在区间内就产生一次。
            val date = anchor.toLocalDate()
            return if (date in from..to) {
                listOf(
                    Occurrence(
                        itemId = item.id,
                        title = item.title,
                        startAt = start,
                        endAt = item.endAt,
                        originalDate = date,
                    ),
                )
            } else {
                emptyList()
            }
        }

        // UNTIL 既可能写在 RRULE 里，也可能存在单独的列里，两处取并集才不会漏掉结束条件。
        val rule = if (parsed.until == null && item.rruleUntil != null) {
            parsed.copy(until = item.rruleUntil)
        } else {
            parsed
        }

        val byDate = exceptions.associateBy { it.date }
        val deletedDates = byDate
            .filterValues { it.action == ExceptionAction.DELETED }
            .keys

        return RecurrenceEngine
            .occurrences(
                rule = rule,
                anchor = anchor,
                from = from,
                to = to,
                excludedDates = deletedDates,
                zone = zone,
            )
            .map { occurrence ->
                val date = occurrence.toLocalDate()
                val override = byDate[date]
                if (override != null && override.action == ExceptionAction.OVERRIDDEN) {
                    val newStart = override.overrideStartAt ?: occurrence.toInstant()
                    Occurrence(
                        itemId = item.id,
                        title = override.overrideTitle ?: item.title,
                        startAt = newStart,
                        endAt = override.overrideEndAt ?: duration?.let { newStart.plus(it) },
                        originalDate = date,
                        isOverride = true,
                    )
                } else {
                    val instant = occurrence.toInstant()
                    Occurrence(
                        itemId = item.id,
                        title = item.title,
                        startAt = instant,
                        endAt = duration?.let { instant.plus(it) } ?: item.endAt,
                        originalDate = date,
                    )
                }
            }
    }
}
