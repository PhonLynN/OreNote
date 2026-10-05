package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Frequency
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceEngineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9): ZonedDateTime =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone)

    private fun dates(
        rule: RecurrenceRule,
        anchor: ZonedDateTime,
        from: String,
        to: String,
        excluded: Set<LocalDate> = emptySet(),
    ): List<LocalDate> = RecurrenceEngine
        .occurrences(rule, anchor, LocalDate.parse(from), LocalDate.parse(to), excluded, zone)
        .map { it.toLocalDate() }

    @Test
    fun `每天重复`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.DAILY),
            at(2026, 9, 1),
            "2026-09-01",
            "2026-09-05",
        )
        assertEquals(
            listOf("09-01", "09-02", "09-03", "09-04", "09-05").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `隔两天重复`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.DAILY, interval = 2),
            at(2026, 9, 1),
            "2026-09-01",
            "2026-09-10",
        )
        assertEquals(
            listOf("09-01", "09-03", "09-05", "09-07", "09-09").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `每周固定星期几重复`() {
        val result = dates(
            RecurrenceRule(
                frequency = Frequency.WEEKLY,
                byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            ),
            at(2026, 9, 7),
            "2026-09-07",
            "2026-09-20",
        )
        assertEquals(
            listOf("09-07", "09-09", "09-14", "09-16").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `每周不带 BYDAY 时沿用起始日的星期`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.WEEKLY),
            at(2026, 9, 7),
            "2026-09-07",
            "2026-09-28",
        )
        assertEquals(
            listOf("09-07", "09-14", "09-21", "09-28").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `每两周重复`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.WEEKLY, interval = 2),
            at(2026, 9, 7),
            "2026-09-07",
            "2026-10-19",
        )
        assertEquals(
            listOf("09-07", "09-21", "10-05", "10-19").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `COUNT 限制的是整个序列长度 与查询区间无关`() {
        val rule = RecurrenceRule(frequency = Frequency.DAILY, count = 5)
        // 序列是 09-01..09-05；从 09-03 开始查，应该只剩 3 次，而不是重新数 5 次
        val result = dates(rule, at(2026, 9, 1), "2026-09-03", "2026-09-30")
        assertEquals(
            listOf("09-03", "09-04", "09-05").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `UNTIL 之后不再产生发生`() {
        val rule = RecurrenceRule(
            frequency = Frequency.DAILY,
            until = Instant.parse("2026-09-03T00:00:00Z"),
        )
        // UNTIL 是 UTC 零点，对应北京时间 09-03 08:00；每天的发生在 09:00，所以 09-03 那次已经超出
        val result = dates(rule, at(2026, 9, 1), "2026-09-01", "2026-09-10")
        assertEquals(
            listOf("09-01", "09-02").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `每月重复时 31 号在短月会收拢到月末并回到 31 号`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.MONTHLY),
            at(2026, 1, 31),
            "2026-01-01",
            "2026-04-30",
        )
        assertEquals(
            listOf("01-31", "02-28", "03-31", "04-30").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `每次发生保留起始日的时刻`() {
        val occurrences = RecurrenceEngine.occurrences(
            RecurrenceRule(frequency = Frequency.DAILY),
            at(2026, 9, 1, hour = 14),
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-09-02"),
            emptySet(),
            zone,
        )
        assertEquals(listOf(14, 14), occurrences.map { it.hour })
    }

    @Test
    fun `被排除的日期不会出现`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.DAILY),
            at(2026, 9, 1),
            "2026-09-01",
            "2026-09-05",
            excluded = setOf(LocalDate.parse("2026-09-03")),
        )
        assertEquals(
            listOf("09-01", "09-02", "09-04", "09-05").map { LocalDate.parse("2026-$it") },
            result,
        )
    }

    @Test
    fun `查询区间反向时返回空`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.DAILY),
            at(2026, 9, 1),
            "2026-09-10",
            "2026-09-01",
        )
        assertEquals(emptyList<LocalDate>(), result)
    }

    @Test
    fun `结束条件在区间之前时返回空`() {
        val rule = RecurrenceRule(frequency = Frequency.DAILY, until = Instant.parse("2026-09-02T00:00:00Z"))
        val result = dates(rule, at(2026, 9, 1), "2026-10-01", "2026-10-10")
        assertEquals(emptyList<LocalDate>(), result)
    }

    @Test
    fun `锚点很远时无 COUNT 的规则会跳到区间附近 而不是空转返回空`() {
        // 这是一条真实的回归测试：曾经因为从锚点逐次步进、撞到步数上限，
        // 一条 2000 年起的每日重复在 2026 年查询时会静默返回空。
        val result = dates(
            RecurrenceRule(frequency = Frequency.DAILY),
            at(2000, 1, 1),
            "2026-09-01",
            "2026-09-03",
        )
        assertEquals(3, result.size)
        assertEquals(LocalDate.parse("2026-09-01"), result.first())
    }

    @Test
    fun `隔周重复的锚点很远时也能正确命中`() {
        val result = dates(
            RecurrenceRule(frequency = Frequency.WEEKLY, interval = 2),
            at(2000, 1, 3),
            "2026-09-07",
            "2026-09-21",
        )
        // 2000-01-03 是周一，隔周重复；区间内应恰好两次周一，且间隔 14 天
        assertEquals(2, result.size)
        assertEquals(DayOfWeek.MONDAY, result[0].dayOfWeek)
        assertEquals(14L, java.time.temporal.ChronoUnit.DAYS.between(result[0], result[1]))
    }
}
