package com.phonlynn.oreplan.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态置顶三项时间换算的边界测试。
 *
 * 盯的是**跨日**这一类边界：它是最容易算错、也最容易被忽略的地方
 * （晚上浮起、次日早晨收回是常见用法，算成负数会让整个功能失效）。
 */
class AutoPinTimeTest {

    private fun hm(hour: Int, minute: Int) = hour * 60 + minute

    // ---------------------------------------------------------------- 下沉时刻

    @Test
    fun `下沉：同日不跨日`() {
        // 08:00 浮起 + 10 小时 = 18:00 当日
        assertEquals(hm(18, 0), AutoPinTime.sinkMinuteOfDay(hm(8, 0), 600))
        assertEquals(0, AutoPinTime.sinkDayOffset(hm(8, 0), 600))
        assertFalse(AutoPinTime.sinkIsNextDay(hm(8, 0), 600))
    }

    @Test
    fun `下沉：跨日落到次日`() {
        // 22:00 浮起 + 8 小时 = 次日 06:00
        assertEquals(hm(6, 0), AutoPinTime.sinkMinuteOfDay(hm(22, 0), 480))
        assertEquals(1, AutoPinTime.sinkDayOffset(hm(22, 0), 480))
        assertTrue(AutoPinTime.sinkIsNextDay(hm(22, 0), 480))
    }

    @Test
    fun `下沉：正好整天回到同一钟点且算次日`() {
        // 08:00 浮起 + 24 小时 = 次日 08:00
        assertEquals(hm(8, 0), AutoPinTime.sinkMinuteOfDay(hm(8, 0), 1440))
        assertEquals(1, AutoPinTime.sinkDayOffset(hm(8, 0), 1440))
        assertTrue(AutoPinTime.sinkIsNextDay(hm(8, 0), 1440))
    }

    @Test
    fun `下沉：时长为零时留在原地且不跨日`() {
        assertEquals(hm(9, 30), AutoPinTime.sinkMinuteOfDay(hm(9, 30), 0))
        assertEquals(0, AutoPinTime.sinkDayOffset(hm(9, 30), 0))
        assertFalse(AutoPinTime.sinkIsNextDay(hm(9, 30), 0))
    }

    @Test
    fun `下沉：跨多天时天数正确`() {
        // 08:00 + 3 天 = 3 天后 08:00
        assertEquals(hm(8, 0), AutoPinTime.sinkMinuteOfDay(hm(8, 0), 3 * 1440))
        assertEquals(3, AutoPinTime.sinkDayOffset(hm(8, 0), 3 * 1440))
    }

    @Test
    fun `下沉：负数时长按零处理而不是回退`() {
        // 负时长是脏数据，按 0 处理（留在原地），不能算出「昨天」这种结果。
        assertEquals(hm(10, 0), AutoPinTime.sinkMinuteOfDay(hm(10, 0), -120))
        assertEquals(0, AutoPinTime.sinkDayOffset(hm(10, 0), -120))
    }

    // ---------------------------------------------------------------- 反算时长

    @Test
    fun `反算：同日得到正差`() {
        assertEquals(600, AutoPinTime.durationBetween(hm(8, 0), hm(18, 0)))
    }

    @Test
    fun `反算：跨日按次日处理`() {
        // 22:00 → 06:00 应为 8 小时，而不是 -960
        assertEquals(480, AutoPinTime.durationBetween(hm(22, 0), hm(6, 0)))
    }

    @Test
    fun `反算：两端相同时为整日`() {
        // 08:00 → 08:00 无法区分同日/次日，按整日（1440）处理。
        assertEquals(1440, AutoPinTime.durationBetween(hm(8, 0), hm(8, 0)))
    }

    @Test
    fun `反算恒为正`() {
        // 遍历任意组合，结果必须落在 1..1440，且绝不为负。
        for (start in listOf(0, 1, 539, 720, 1380, 1439)) {
            for (sink in listOf(0, 1, 539, 720, 1380, 1439)) {
                val d = AutoPinTime.durationBetween(start, sink)
                assertTrue("start=$start sink=$sink 得到 $d", d in 1..1440)
            }
        }
    }

    // ---------------------------------------------------------------- 双向一致性

    @Test
    fun `双向：时长经下沉再反算应当回到原值（一天以内）`() {
        for (start in listOf(0, 300, 720, 1320, 1439)) {
            for (dur in listOf(1, 60, 480, 720, 1200, 1439)) {
                val sink = AutoPinTime.sinkMinuteOfDay(start, dur)
                // 注意：这里只对「时长 < 1440」成立——超过一天时，
                // 下沉只会保留一天内的钟点，天数信息在反算时丢失。
                if (dur < AutoPinTime.MINUTES_PER_DAY) {
                    assertEquals("start=$start dur=$dur", dur, AutoPinTime.durationBetween(start, sink))
                }
            }
        }
    }

    // ---------------------------------------------------------------- 回显

    @Test
    fun `回显：同日不加前缀`() {
        assertEquals("18:00", AutoPinTime.formatSinkTime(hm(8, 0), 600))
    }

    @Test
    fun `回显：跨日必须标出次日`() {
        // 这是必须的：只显示 06:00 会让用户以为当天。
        assertEquals("次日 06:00", AutoPinTime.formatSinkTime(hm(22, 0), 480))
    }

    @Test
    fun `回显：跨多天标出天数`() {
        assertEquals("3 天后 08:00", AutoPinTime.formatSinkTime(hm(8, 0), 3 * 1440))
    }

    // ---------------------------------------------------------------- 时长文案

    @Test
    fun `文案：常见档位`() {
        assertEquals("30 分钟", AutoPinTime.durationText(30))
        assertEquals("8 小时", AutoPinTime.durationText(480))
        assertEquals("1 小时 30 分钟", AutoPinTime.durationText(90))
        assertEquals("1 天", AutoPinTime.durationText(1440))
        assertEquals("2 天", AutoPinTime.durationText(2880))
        assertEquals("1 天 6 小时", AutoPinTime.durationText(1440 + 360))
    }
}
