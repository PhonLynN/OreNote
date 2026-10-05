package com.phonlynn.oreplan.domain.plan

import com.phonlynn.oreplan.domain.model.ExtMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展抽屉里的规划字段 —— 编解码必须对称。
 *
 * 这些字段放在 `entity_ext` 而不是 `items` 的新列上（见 PlanMeta 的说明），
 * 代价是**没有任何类型约束**：写进去的是一个字符串，读回来要自己解析。
 * 所以这里的用例重点是「脏数据不崩」与「编解码往返一致」。
 */
class PlanMetaTest {

    @Test
    fun `每天分钟数读写对称`() {
        val map = ExtMap.EMPTY.putNum(PlanMeta.KEY_DAILY_MINUTES, 30.0)
        assertEquals(30, PlanMeta.dailyMinutes(map))
    }

    @Test
    fun `每天分钟数为零或负数时视为没设置`() {
        assertNull(PlanMeta.dailyMinutes(ExtMap.EMPTY.putNum(PlanMeta.KEY_DAILY_MINUTES, 0.0)))
        assertNull(PlanMeta.dailyMinutes(ExtMap.EMPTY.putNum(PlanMeta.KEY_DAILY_MINUTES, -5.0)))
        assertNull(PlanMeta.dailyMinutes(ExtMap.EMPTY))
    }

    @Test
    fun `提醒时间必须是合法时钟`() {
        assertEquals("07:00", PlanMeta.checkInTime(ExtMap.EMPTY.putText(PlanMeta.KEY_CHECK_IN_TIME, "07:00")))
        assertEquals("23:59", PlanMeta.checkInTime(ExtMap.EMPTY.putText(PlanMeta.KEY_CHECK_IN_TIME, "23:59")))
        // 脏值一律当没设置，不抛异常
        assertNull(PlanMeta.checkInTime(ExtMap.EMPTY.putText(PlanMeta.KEY_CHECK_IN_TIME, "25:00")))
        assertNull(PlanMeta.checkInTime(ExtMap.EMPTY.putText(PlanMeta.KEY_CHECK_IN_TIME, "7点")))
        assertNull(PlanMeta.checkInTime(ExtMap.EMPTY.putText(PlanMeta.KEY_CHECK_IN_TIME, "07:60")))
        assertNull(PlanMeta.checkInTime(ExtMap.EMPTY))
    }

    @Test
    fun `提醒档位编解码往返一致且已排序去重`() {
        val encoded = PlanMeta.encodeRemindLeads(listOf(1440, 15, 1440, 60))
        assertEquals("15,60,1440", encoded)
        assertEquals(listOf(15, 60, 1440), PlanMeta.remindLeads(ExtMap.EMPTY.putText(PlanMeta.KEY_REMIND_LEADS, encoded)))
    }

    @Test
    fun `提醒档位最多三档`() {
        val encoded = PlanMeta.encodeRemindLeads(listOf(15, 60, 1440, 4320, 10080))
        assertEquals(listOf(15, 60, 1440), PlanMeta.remindLeads(ExtMap.EMPTY.putText(PlanMeta.KEY_REMIND_LEADS, encoded)))
    }

    @Test
    fun `提醒档位遇到脏字符串不崩`() {
        val dirty = ExtMap.EMPTY.putText(PlanMeta.KEY_REMIND_LEADS, "abc,15,,x,-3,60")
        assertEquals(listOf(15, 60), PlanMeta.remindLeads(dirty))
        assertTrue(PlanMeta.remindLeads(ExtMap.EMPTY).isEmpty())
    }

    @Test
    fun `加档位受三档上限与三十分钟间隔约束`() {
        // 空表加第一档
        assertEquals(listOf(15), PlanMeta.addLead(emptyList(), 15))
        // 与已有档位差 10 分钟 → 拒绝
        assertEquals(listOf(15), PlanMeta.addLead(listOf(15), 25))
        // 正好 30 分钟 → 接受
        assertEquals(listOf(15, 45), PlanMeta.addLead(listOf(15), 45))
        // 已满三档 → 拒绝
        assertEquals(listOf(15, 60, 1440), PlanMeta.addLead(listOf(15, 60, 1440), 4320))
    }

    @Test
    fun `提醒文案按天小时分钟分档`() {
        assertEquals("提前 15 分钟", PlanMeta.leadLabel(15))
        assertEquals("提前 1 小时", PlanMeta.leadLabel(60))
        assertEquals("提前 1 天", PlanMeta.leadLabel(1440))
        assertEquals("提前 3 天", PlanMeta.leadLabel(4320))
        assertEquals("提前 90 分钟", PlanMeta.leadLabel(90))
    }

    @Test
    fun `关联日程编解码往返一致`() {
        val encoded = PlanMeta.encodeLinkedEvents(listOf("ev1", "ev2", "ev1", ""))
        assertEquals("ev1,ev2", encoded)
        assertEquals(listOf("ev1", "ev2"), PlanMeta.linkedEvents(ExtMap.EMPTY.putText(PlanMeta.KEY_LINKED_EVENTS, encoded)))
        assertTrue(PlanMeta.linkedEvents(ExtMap.EMPTY).isEmpty())
    }

    @Test
    fun `应打卡日按频率判断`() {
        // 2025-05-14 是周三
        val wednesday = java.time.LocalDate.of(2025, 5, 14)
        val sunday = java.time.LocalDate.of(2025, 5, 18)
        assertTrue(PlanMeta.isHabitDay((1..7).toSet(), wednesday))
        assertTrue(PlanMeta.isHabitDay((1..5).toSet(), wednesday))
        assertTrue(!PlanMeta.isHabitDay((1..5).toSet(), sunday))
        assertTrue(PlanMeta.isHabitDay(setOf(6, 7), sunday))
    }
}
