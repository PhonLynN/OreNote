package com.phonlynn.oreplan.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 动态置顶激活判定的边界测试。
 *
 * 重点是两处最容易错的地方：
 *  1. 归位判定必须带时刻（否则周期型第一次沉下后永不浮起）；
 *  2. 周期型取「最近一次触发」不能从锚点步进。
 */
class AutoPinLogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun instant(y: Int, m: Int, d: Int, h: Int, min: Int): Instant =
        LocalDate.of(y, m, d).atTime(h, min).atZone(zone).toInstant()

    private fun card(rule: AutoPinRule?, resolvedAt: Instant? = null) = BoardCard(
        id = "c1",
        type = BoardCardType.QUICK,
        createdAt = instant(2026, 1, 1, 0, 0),
        updatedAt = instant(2026, 1, 1, 0, 0),
        autoPin = rule,
        autoPinResolvedAt = resolvedAt,
    )

    // ---------------------------------------------------------------- 日期型

    @Test
    fun `日期型：未到点时不激活`() {
        val r = AutoPinRule.OnDate(instant(2026, 9, 25, 8, 0), durationMinutes = 1440)
        val now = instant(2026, 9, 25, 7, 59)
        assertFalse(AutoPinLogic.isActive(card(r), now, zone))
    }

    @Test
    fun `日期型：到点的那一刻激活`() {
        val r = AutoPinRule.OnDate(instant(2026, 9, 25, 8, 0), durationMinutes = 1440)
        assertTrue(AutoPinLogic.isActive(card(r), instant(2026, 9, 25, 8, 0), zone))
    }

    @Test
    fun `日期型：窗口内保持激活`() {
        val r = AutoPinRule.OnDate(instant(2026, 9, 25, 8, 0), durationMinutes = 1440)
        assertTrue(AutoPinLogic.isActive(card(r), instant(2026, 9, 26, 7, 59), zone))
    }

    @Test
    fun `日期型：超过时长后归位`() {
        val r = AutoPinRule.OnDate(instant(2026, 9, 25, 8, 0), durationMinutes = 1440)
        assertFalse(AutoPinLogic.isActive(card(r), instant(2026, 9, 26, 8, 0), zone))
    }

    @Test
    fun `日期型：手动沉下后立即归位（即使仍在窗口内）`() {
        val start = instant(2026, 9, 25, 8, 0)
        val r = AutoPinRule.OnDate(start, durationMinutes = 1440)
        val resolved = instant(2026, 9, 25, 9, 0) // 1 小时后手动沉下
        assertFalse(AutoPinLogic.isActive(card(r, resolved), instant(2026, 9, 25, 10, 0), zone))
    }

    @Test
    fun `日期型：归位时刻早于起始时刻时不影响本次`() {
        // 例如用户上一次归位是在规则被改之前——不该压制本次浮起。
        val start = instant(2026, 9, 25, 8, 0)
        val r = AutoPinRule.OnDate(start, durationMinutes = 1440)
        val staleResolved = instant(2026, 9, 20, 8, 0)
        assertTrue(AutoPinLogic.isActive(card(r, staleResolved), instant(2026, 9, 25, 9, 0), zone))
    }

    // ---------------------------------------------------------------- 无规则

    @Test
    fun `无规则时恒不激活`() {
        assertFalse(AutoPinLogic.isActive(card(null), instant(2026, 9, 25, 9, 0), zone))
        assertNull(AutoPinLogic.startOf(card(null), instant(2026, 9, 25, 9, 0), zone))
    }

    // ---------------------------------------------------------------- 周期型

    private fun weekly(weekday: DayOfWeek = DayOfWeek.MONDAY, minuteOfDay: Int = 8 * 60) =
        AutoPinRule.Recurring(
            rule = RecurrenceRule(frequency = Frequency.WEEKLY, byDay = setOf(weekday)),
            minuteOfDay = minuteOfDay,
            durationMinutes = 1440,
        )

    @Test
    fun `周期型：本周一当天且已过钟点时激活`() {
        // 2026-09-21 是周一。
        val r = weekly()
        assertTrue(AutoPinLogic.isActive(card(r), instant(2026, 9, 21, 9, 0), zone))
    }

    @Test
    fun `周期型：本周一但未到钟点时不激活（取的是上周那次）`() {
        // 周一 07:00：本周一那次还没到，最近一次触发是上周一 08:00，
        // 已超出 24 小时窗口 → 不激活。
        val r = weekly()
        assertFalse(AutoPinLogic.isActive(card(r), instant(2026, 9, 21, 7, 0), zone))
    }

    @Test
    fun `周期型：窗口跨到次日仍激活`() {
        // 周一 08:00 浮起 + 24 小时 → 周二 07:59 仍激活。
        val r = weekly()
        assertTrue(AutoPinLogic.isActive(card(r), instant(2026, 9, 22, 7, 59), zone))
    }

    @Test
    fun `周期型：超过窗口后归位`() {
        val r = weekly()
        assertFalse(AutoPinLogic.isActive(card(r), instant(2026, 9, 22, 8, 0), zone))
    }

    /**
     * 这是本项目最需要防的一条：归位记录必须只压制「同一次」浮起。
     *
     * 如果实现写成 `resolvedAt != null` 就归位，那么用户第一次手动沉下后，
     * 这张卡**永远不会再浮起**——而且要等整整一周才会被发现。
     */
    @Test
    fun `周期型：上一轮沉下不影响下一轮浮起`() {
        val r = weekly()
        val lastWeekResolved = instant(2026, 9, 14, 10, 0) // 上周一沉下
        // 本周一 09:00 应当照常浮起
        assertTrue(AutoPinLogic.isActive(card(r, lastWeekResolved), instant(2026, 9, 21, 9, 0), zone))
    }

    @Test
    fun `周期型：本轮沉下后本周不再浮起`() {
        val r = weekly()
        val thisWeekResolved = instant(2026, 9, 21, 10, 0)
        assertFalse(AutoPinLogic.isActive(card(r, thisWeekResolved), instant(2026, 9, 21, 12, 0), zone))
    }

    @Test
    fun `周期型：浮起时刻随 minuteOfDay 变化`() {
        val r = weekly(minuteOfDay = 22 * 60)
        // 周一 22:00 之后激活
        assertTrue(AutoPinLogic.isActive(card(r), instant(2026, 9, 21, 23, 0), zone))
        // 周一 21:59 还没到
        assertFalse(AutoPinLogic.isActive(card(r), instant(2026, 9, 21, 21, 59), zone))
    }

    @Test
    fun `周期型：低频规则（每年）也能查到最近一次触发`() {
        val r = AutoPinRule.Recurring(
            rule = RecurrenceRule(frequency = Frequency.YEARLY),
            minuteOfDay = 9 * 60,
            durationMinutes = 1440,
        )
        // 锚点是「今天」，所以今年的同一天会有一次触发。
        val now = instant(2026, 9, 21, 10, 0)
        assertTrue(AutoPinLogic.isActive(card(r), now, zone))
    }

    // ---------------------------------------------------------------- 结束时刻

    @Test
    fun `结束时刻等于起始加时长`() {
        val start = instant(2026, 9, 25, 8, 0)
        val r = AutoPinRule.OnDate(start, durationMinutes = 480)
        assertEquals(instant(2026, 9, 25, 16, 0), AutoPinLogic.endOf(card(r), start, zone))
    }

    // ---------------------------------------------------------------- 时长夹取

    @Test
    fun `时长夹取：下限一分钟上限一年`() {
        assertEquals(1, AutoPinLogic.normalizeDurationMinutes(0))
        assertEquals(1, AutoPinLogic.normalizeDurationMinutes(-100))
        assertEquals(365 * 24 * 60, AutoPinLogic.normalizeDurationMinutes(Int.MAX_VALUE))
        assertEquals(480, AutoPinLogic.normalizeDurationMinutes(480))
    }
}
