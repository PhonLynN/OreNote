package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Frequency
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 重复规则展开 —— 只存规则、查询时展开，**不预生成实例**。
 *
 * 预生成的话，改一次「每周三改成每两周」就要重写几百行数据；而且无限重复的日程
 * 根本没有停止点。展开式正好规避这两个问题。
 *
 * 纯函数，不依赖数据库；日期级例外由调用方以 [excludedDates] 传入。
 */
object RecurrenceEngine {

    /**
     * 单次查询的最大迭代步数。
     *
     * 之所以要这个上限：重复规则是无限序列，一个畸形的 interval 会让循环转不完。
     * 正常情况下根本碰不到 —— 无 COUNT 的规则会先跳到区间附近（见 [firstRelevantStep]），
     * 迭代次数与「查询区间长度」成正比，而不是与「锚点到今天的距离」成正比。
     */
    private const val MAX_STEPS = 100_000

    fun occurrences(
        rule: RecurrenceRule,
        anchor: ZonedDateTime,
        from: LocalDate,
        to: LocalDate,
        excludedDates: Set<LocalDate> = emptySet(),
        zone: ZoneId = anchor.zone,
    ): List<ZonedDateTime> {
        if (to < from) return emptyList()

        val anchorDate = anchor.toLocalDate()
        val out = mutableListOf<ZonedDateTime>()

        /*
         * 关键性能与正确性处理：
         *
         * 无 COUNT 时不需要从头数，可以直接算出第一个可能落在 from 附近的步数。
         * 否则一条「从 2000 年起每天重复」的日程在 2026 年查询时要空转近一万步，
         * 撞到 MAX_STEPS 上限后会**静默返回空** —— 用户看不到任何报错，只是日程消失了。
         *
         * 有 COUNT 时不能跳：COUNT 限制的是序列总长度，必须从头累计。
         * 但那种情况循环次数天然被 count 卡住，不会失控。
         */
        val startStep = if (rule.count == null) firstRelevantStep(rule, anchorDate, from) else 0

        var step = startStep
        var producedCount = 0
        var iterations = 0

        while (iterations < MAX_STEPS) {
            if (rule.count != null && producedCount >= rule.count) break

            val dates = candidateDates(rule, anchorDate, step)
            step++
            iterations++
            if (dates.isEmpty()) break

            var shouldStop = false
            for (date in dates) {
                val occurrence = date.atTime(anchor.toLocalTime()).atZone(zone)

                if (rule.until != null && occurrence.toInstant() > rule.until) {
                    shouldStop = true
                    break
                }

                if (rule.count != null) {
                    producedCount++
                    if (producedCount > rule.count) {
                        shouldStop = true
                        break
                    }
                }

                // 候选日期在一轮内是升序的，越过区间上界就没有必要继续。
                if (date > to) {
                    shouldStop = true
                    break
                }

                if (date >= from && date !in excludedDates) {
                    out += occurrence
                }
            }
            if (shouldStop) break
        }

        out.sort()
        return out
    }

    /**
     * 估算第一个值得开始枚举的步数（步数从 0 起）。
     *
     * 刻意向下取整并**再退一步**：宁可多枚举一两轮，也不能因为取整把区间起点那一天的发生漏掉。
     */
    private fun firstRelevantStep(
        rule: RecurrenceRule,
        anchorDate: LocalDate,
        from: LocalDate,
    ): Int {
        val interval = rule.interval.toLong()
        val distance = when (rule.frequency) {
            Frequency.DAILY -> ChronoUnit.DAYS.between(anchorDate, from)
            Frequency.WEEKLY -> ChronoUnit.DAYS.between(anchorDate, from) / 7
            Frequency.MONTHLY -> ChronoUnit.MONTHS.between(anchorDate, from)
            Frequency.YEARLY -> ChronoUnit.YEARS.between(anchorDate, from)
        }
        if (distance <= 0) return 0
        val steps = (distance / interval) - 1
        return steps.coerceAtLeast(0).let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
    }

    /**
     * 第 [step] 轮（从 0 起）产生的候选日期。
     *
     * MONTHLY 用 `anchorDate.plusMonths(...)` 而不是逐月累加：这样 1 月 31 日
     * 的下一次是 2 月 28 日、再下一次回到 3 月 31 日。逐月累加会一路漂移到 28 日之后不再回来。
     */
    private fun candidateDates(
        rule: RecurrenceRule,
        anchorDate: LocalDate,
        step: Int,
    ): List<LocalDate> {
        val offset = step.toLong() * rule.interval
        return when (rule.frequency) {
            Frequency.DAILY -> listOf(anchorDate.plusDays(offset))

            Frequency.WEEKLY -> if (rule.byDay.isEmpty()) {
                listOf(anchorDate.plusWeeks(offset))
            } else {
                val weekStart = anchorDate
                    .minusDays((anchorDate.dayOfWeek.value - 1).toLong())
                    .plusWeeks(offset)
                rule.byDay
                    .sortedBy { it.value }
                    .map { weekStart.plusDays((it.value - 1).toLong()) }
            }

            Frequency.MONTHLY -> listOf(anchorDate.plusMonths(offset))

            Frequency.YEARLY -> listOf(anchorDate.plusYears(offset))
        }
    }

    /** 便于 UI 展示：把规则翻成一句中文。 */
    fun describe(rule: RecurrenceRule): String {
        val unit = when (rule.frequency) {
            Frequency.DAILY -> "天"
            Frequency.WEEKLY -> "周"
            Frequency.MONTHLY -> "月"
            Frequency.YEARLY -> "年"
        }
        val base = if (rule.interval == 1) "每$unit" else "每${rule.interval}$unit"
        val days = if (rule.byDay.isEmpty()) {
            ""
        } else {
            "周" + rule.byDay.sortedBy { it.value }.joinToString("、") { weekdayLabel(it) }
        }
        val tail = when {
            rule.count != null -> "，共 ${rule.count} 次"
            rule.until != null -> "，直到该日结束"
            else -> ""
        }
        return base + days + tail
    }

    private fun weekdayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "一"
        DayOfWeek.TUESDAY -> "二"
        DayOfWeek.WEDNESDAY -> "三"
        DayOfWeek.THURSDAY -> "四"
        DayOfWeek.FRIDAY -> "五"
        DayOfWeek.SATURDAY -> "六"
        DayOfWeek.SUNDAY -> "日"
    }
}
