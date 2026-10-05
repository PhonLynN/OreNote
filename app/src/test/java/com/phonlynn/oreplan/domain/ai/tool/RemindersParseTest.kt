package com.phonlynn.oreplan.domain.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提前多久」的解析。
 *
 * ## 为什么这里必须钉死
 *
 * 提醒**设错了不会报错** —— 它只是在该响的时候不响。
 * 而用户看到的是"我明明让它提前 15 分钟提醒"，于是怀疑整个提醒功能坏了。
 *
 * 三种输入混在一起（没传 / 去掉 / 提前多久），最怕的是把
 * **"没传"当成"去掉"** —— 那样改个标题就会把提醒删掉。
 */
class RemindersParseTest {

    // ---------------------------------------------------------------- 三态

    /** 没传 → null（**不动**现有的提醒）。 */
    @Test
    fun `没传时不动作`() {
        assertNull(Reminders.parse(null))
        assertNull(Reminders.parse(""))
        assertNull(Reminders.parse("   "))
    }

    /** 明确说不要 → None（真的去掉）。 */
    @Test
    fun `明确去掉`() {
        listOf("none", "off", "false", "不提醒", "不要提醒", "取消提醒", "去掉提醒", "无")
            .forEach { text ->
                assertEquals(
                    "「$text」应当解析成「去掉」",
                    ReminderSpec.None,
                    Reminders.parse(text),
                )
            }
    }

    /** 准点提醒 = 提前 0 分钟（**不是**去掉）。 */
    @Test
    fun `准点`() {
        listOf("0", "0分钟", "准点", "开始时").forEach { text ->
            assertEquals("「$text」应当是提前 0 分钟", ReminderSpec.Before(0), Reminders.parse(text))
        }
    }

    // ---------------------------------------------------------------- 时长写法

    /**
     * 模型和用户都可能写各种说法。
     *
     * 这些**全是同一个意思**，解析岔一个就会设错提醒。
     */
    @Test
    fun `分钟的各种写法`() {
        listOf("15", "15分钟", "15分", "15m", "15min", "15 mins", "15 minutes").forEach { text ->
            assertEquals("「$text」", ReminderSpec.Before(15), Reminders.parse(text))
        }
    }

    @Test
    fun `小时的各种写法`() {
        listOf("1小时", "1时", "1h", "1hr", "1 hour", "1hours", "60分钟").forEach { text ->
            assertEquals("「$text」应当 = 60 分钟", ReminderSpec.Before(60), Reminders.parse(text))
        }
    }

    @Test
    fun `天与周`() {
        assertEquals(ReminderSpec.Before(24 * 60), Reminders.parse("1天"))
        assertEquals(ReminderSpec.Before(24 * 60), ReminderSpec.Before(1440))
        assertEquals(ReminderSpec.Before(2 * 24 * 60), Reminders.parse("2天"))
        assertEquals(ReminderSpec.Before(7 * 24 * 60), Reminders.parse("1周"))
        assertEquals(ReminderSpec.Before(7 * 24 * 60), Reminders.parse("1星期"))
    }

    /** 大小写、空格都要容错 —— 模型不保证格式。 */
    @Test
    fun `大小写与空格容错`() {
        assertEquals(ReminderSpec.Before(15), Reminders.parse("  15 分钟  "))
        assertEquals(ReminderSpec.Before(60), Reminders.parse("1 H"))
        assertEquals(ReminderSpec.Before(30), Reminders.parse("30MIN"))
    }

    // ---------------------------------------------------------------- 边界

    /**
     * ⚠️ 认不出来时要返回 null（= 当没传），**不能猜**。
     *
     * 猜错的后果是提醒在错误的时间响、或者不响，而用户不会怀疑是换算错了。
     */
    @Test
    fun `认不出来时不猜`() {
        assertNull(Reminders.parse("随便什么时候"))
        assertNull(Reminders.parse("一会儿"))
        assertNull(Reminders.parse("-5分钟"))
        assertNull(Reminders.parse("15光年"))
    }

    /** 超过一个月的不认 —— 那不是"提前提醒"，多半是模型理解错了。 */
    @Test
    fun `超过一个月不认`() {
        assertNull(Reminders.parse("100天"))
        assertNull(Reminders.parse("5周"))
    }

    /** 刚好一个月是允许的（边界）。 */
    @Test
    fun `一个月是上限`() {
        assertEquals(ReminderSpec.Before(30 * 24 * 60), Reminders.parse("30天"))
    }

    // ---------------------------------------------------------------- 展示

    /** 预览卡上要写成人话。 */
    @Test
    fun `提前量显示成人话`() {
        assertEquals("准点", Reminders.label(0))
        assertEquals("15 分钟", Reminders.label(15))
        assertEquals("1 小时", Reminders.label(60))
        assertEquals("2 小时", Reminders.label(120))
        assertEquals("1 天", Reminders.label(24 * 60))
    }

    // ---------------------------------------------------------------- 未来时刻

    /**
     * ⚠️ 提醒时刻**已经过去**时不能写入。
     *
     * 「提前 1 天提醒」而日程在两小时后 —— 算出来在过去，系统不会响。
     * 写进去只会让用户以为设好了。
     */
    @Test
    fun `过去的时刻不算未来`() {
        val now = java.time.Instant.parse("2026-10-04T12:00:00Z")

        assertTrue(Reminders.isFuture(java.time.Instant.parse("2026-10-04T12:00:01Z"), now))
        assertTrue(!Reminders.isFuture(java.time.Instant.parse("2026-10-04T11:59:59Z"), now))
        assertTrue("等于现在也不算未来", !Reminders.isFuture(now, now))
    }
}
