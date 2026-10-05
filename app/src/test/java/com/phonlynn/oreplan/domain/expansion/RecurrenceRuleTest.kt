package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.Frequency
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RecurrenceRuleTest {

    @Test
    fun `每周规则序列化时省略默认间隔`() {
        val rule = RecurrenceRule(frequency = Frequency.WEEKLY)
        assertEquals("FREQ=WEEKLY", rule.toRRule())
    }

    @Test
    fun `间隔不为 1 时写入 INTERVAL`() {
        val rule = RecurrenceRule(frequency = Frequency.WEEKLY, interval = 2)
        assertEquals("FREQ=WEEKLY;INTERVAL=2", rule.toRRule())
    }

    @Test
    fun `按星期几重复会写入 BYDAY 且按周一到周日排序`() {
        val rule = RecurrenceRule(
            frequency = Frequency.WEEKLY,
            byDay = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rule.toRRule())
    }

    @Test
    fun `COUNT 与 UNTIL 分别序列化`() {
        assertEquals(
            "FREQ=DAILY;COUNT=10",
            RecurrenceRule(frequency = Frequency.DAILY, count = 10).toRRule(),
        )
        assertEquals(
            "FREQ=DAILY;UNTIL=20261231T235900Z",
            RecurrenceRule(
                frequency = Frequency.DAILY,
                until = Instant.parse("2026-12-31T23:59:00Z"),
            ).toRRule(),
        )
    }

    @Test
    fun `序列化与解析可以往返`() {
        val originals = listOf(
            RecurrenceRule(frequency = Frequency.DAILY),
            RecurrenceRule(frequency = Frequency.DAILY, interval = 3),
            RecurrenceRule(frequency = Frequency.WEEKLY, interval = 2, byDay = setOf(DayOfWeek.TUESDAY)),
            RecurrenceRule(frequency = Frequency.MONTHLY, count = 6),
            RecurrenceRule(frequency = Frequency.YEARLY, until = Instant.parse("2030-01-01T00:00:00Z")),
        )
        originals.forEach { original ->
            val text = original.toRRule()
            assertEquals("往返失败：$text", original, RecurrenceRule.parse(text))
        }
    }

    @Test
    fun `解析容忍 RRULE 前缀与大小写`() {
        val rule = RecurrenceRule.parse("RRULE:freq=weekly;interval=2;byday=mo,we")
        assertNotNull(rule)
        assertEquals(Frequency.WEEKLY, rule!!.frequency)
        assertEquals(2, rule.interval)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), rule.byDay)
    }

    @Test
    fun `解析不了就返回 null 当作不重复 而不是抛异常`() {
        assertNull(RecurrenceRule.parse(null))
        assertNull(RecurrenceRule.parse(""))
        assertNull(RecurrenceRule.parse("   "))
        assertNull(RecurrenceRule.parse("FREQ=HOURLY"))
        assertNull(RecurrenceRule.parse("随便一串垃圾"))
    }

    @Test
    fun `COUNT 与 UNTIL 同时出现视为非法`() {
        assertNull(RecurrenceRule.parse("FREQ=DAILY;COUNT=5;UNTIL=20261231T000000Z"))
    }

    @Test
    fun `非法的 INTERVAL 回落到 1`() {
        val rule = RecurrenceRule.parse("FREQ=DAILY;INTERVAL=0")
        assertNotNull(rule)
        assertEquals(1, rule!!.interval)

        val negative = RecurrenceRule.parse("FREQ=DAILY;INTERVAL=abc")
        assertEquals(1, negative!!.interval)
    }

    @Test
    fun `非 WEEKLY 规则忽略 BYDAY 而不是报错`() {
        val rule = RecurrenceRule.parse("FREQ=DAILY;BYDAY=MO,WE")
        assertNotNull(rule)
        assertEquals(emptySet<DayOfWeek>(), rule!!.byDay)
    }

    @Test
    fun `只写日期的 UNTIL 按当天零点解析`() {
        val rule = RecurrenceRule.parse("FREQ=DAILY;UNTIL=20261231")
        assertNotNull(rule)
        assertEquals(
            LocalDate.of(2026, 12, 31).atStartOfDay(ZoneOffset.UTC).toInstant(),
            rule!!.until,
        )
    }

    @Test
    fun `构造非法的规则会直接抛错 而不是静默接受`() {
        assertEquals(true, runCatching { RecurrenceRule(frequency = Frequency.DAILY, interval = 0) }.isFailure)
        assertEquals(true, runCatching { RecurrenceRule(frequency = Frequency.DAILY, count = 0) }.isFailure)
        assertEquals(
            true,
            runCatching {
                RecurrenceRule(
                    frequency = Frequency.DAILY,
                    count = 3,
                    until = Instant.parse("2026-12-31T00:00:00Z"),
                )
            }.isFailure,
        )
    }
}
