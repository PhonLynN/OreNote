package com.phonlynn.oreplan.v2.screens

import com.phonlynn.oreplan.domain.routine.RoutineConfig
import com.phonlynn.oreplan.domain.routine.RoutineSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表导入解析（2026-09-21 增加「节次」写法）。
 *
 * ## 为什么要测
 *
 * 导入的数据来自**外部文件**（用户手写或 AI 生成 Excel 转换而来），
 * 所以这是全项目里最容易被喂进脏数据的地方。解析必须：
 *  · 节次写法能按作息正确换算；
 *  · 越界的节次**丢弃该条**（不能夹取 —— 那会让错误的文件看起来导入成功）；
 *  · 旧的「分钟数」写法行为完全不变（向后兼容）。
 */
class CourseImportParseTest {

    /** 测试用作息：默认 08:00 开始、每节 45 分钟、5+5+4 节。 */
    private val routine = RoutineConfig.Default

    private val periods = RoutineSchedule.compute(routine)

    private fun json(sessions: String) = """
        {"courses":[{"name":"高等数学","teacher":"陈立言","location":"理科楼305",
         "sessions":[$sessions]}]}
    """.trimIndent()

    // ---------------------------------------------------------------- 节次写法

    @Test
    fun `节次：单节换算成该节的起止时间`() {
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":1,"startWeek":1,"endWeek":16}"""), routine)
        val s = list.single().sessions.single()
        assertEquals(periods[0].startMinute, s.startMinute)
        assertEquals(periods[0].endMinute, s.endMinute)
    }

    @Test
    fun `节次：连排（1-2 节）起点取第 1 节、终点取第 2 节`() {
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":1,"endPeriod":2,"startWeek":1,"endWeek":16}"""), routine)
        val s = list.single().sessions.single()
        assertEquals(periods[0].startMinute, s.startMinute)
        assertEquals(periods[1].endMinute, s.endMinute)
    }

    @Test
    fun `节次：只有 startPeriod 时视作单节`() {
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":3}"""), routine)
        val s = list.single().sessions.single()
        assertEquals(periods[2].startMinute, s.startMinute)
        assertEquals(periods[2].endMinute, s.endMinute)
    }

    @Test
    fun `节次：跟随作息设置（改第一节时间，结果跟着变）`() {
        val late = RoutineConfig.Default.copy(startMinute = 9 * 60)
        val latePeriods = RoutineSchedule.compute(late)
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":1}"""), late)
        assertEquals(latePeriods[0].startMinute, list.single().sessions.single().startMinute)
        // 与默认作息不同——证明它确实是按传入的作息算的
        assertTrue(periods[0].startMinute != latePeriods[0].startMinute)
    }

    // ---------------------------------------------------------------- 越界丢弃

    @Test
    fun `节次：超出总节数的时段被丢弃（不夹取）`() {
        val total = periods.size
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":${total + 5}}"""), routine)
        assertEquals("越界的时段应被丢弃", 0, list.single().sessions.size)
    }

    @Test
    fun `节次：endPeriod 超出总节数时整条丢弃`() {
        val total = periods.size
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":1,"endPeriod":${total + 1}}"""), routine)
        assertEquals(0, list.single().sessions.size)
    }

    @Test
    fun `节次：endPeriod 早于 startPeriod 时丢弃`() {
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":3,"endPeriod":1}"""), routine)
        assertEquals(0, list.single().sessions.size)
    }

    @Test
    fun `节次：0 与负数视为没写节次`() {
        // 0/-1 不是合法节次；没有其他时间写法 → 整条丢弃。
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startPeriod":0}"""), routine)
        assertEquals(0, list.single().sessions.size)
    }

    // ---------------------------------------------------------------- 两种写法并存

    @Test
    fun `两种都给时以节次为准`() {
        // startMinute 写成明显错误的值，验证真的按节次走。
        val list = parseCourseJson(
            json("""{"dayOfWeek":1,"startPeriod":1,"endPeriod":1,"startMinute":1,"endMinute":2}"""),
            routine,
        )
        val s = list.single().sessions.single()
        assertEquals(periods[0].startMinute, s.startMinute)
        assertEquals(periods[0].endMinute, s.endMinute)
    }

    // ---------------------------------------------------------------- 向后兼容

    @Test
    fun `兼容：旧的分钟数写法行为不变`() {
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startMinute":480,"endMinute":625,"startWeek":1,"endWeek":16}"""), routine)
        val s = list.single().sessions.single()
        assertEquals(480, s.startMinute)
        assertEquals(625, s.endMinute)
        assertEquals(1, s.startWeek)
        assertEquals(16, s.endWeek)
    }

    @Test
    fun `兼容：两种写法都没有时丢弃该时段`() {
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startWeek":1}"""), routine)
        assertEquals(0, list.single().sessions.size)
    }

    @Test
    fun `兼容：不传作息也能解析分钟写法（默认参数）`() {
        val list = parseCourseJson(json("""{"dayOfWeek":1,"startMinute":480,"endMinute":625}"""))
        assertEquals(480, list.single().sessions.single().startMinute)
    }

    // ---------------------------------------------------------------- 其他字段

    @Test
    fun `其他字段：教师、地点、周次、单双周都保留`() {
        val list = parseCourseJson(
            json("""{"dayOfWeek":3,"startPeriod":2,"startWeek":3,"endWeek":12,"parity":"ODD","location":"B203"}"""),
            routine,
        )
        val c = list.single()
        assertEquals("高等数学", c.name)
        assertEquals("陈立言", c.teacher)
        val s = c.sessions.single()
        assertEquals(3, s.dayOfWeek)
        assertEquals(3, s.startWeek)
        assertEquals(12, s.endWeek)
        assertEquals("B203", s.location)
    }

    // ---------------------------------------------------------------- CSV

    @Test
    fun `CSV：节次表头可用`() {
        val csv = """
            name,teacher,location,dayOfWeek,startPeriod,endPeriod,startWeek,endWeek,parity
            高等数学,陈立言,理科楼305,1,1,2,1,16,ALL
        """.trimIndent()
        val s = parseCourseCsv(csv, routine).single().sessions.single()
        assertEquals(periods[0].startMinute, s.startMinute)
        assertEquals(periods[1].endMinute, s.endMinute)
    }

    @Test
    fun `CSV：旧的分钟表头仍可用`() {
        val csv = """
            name,teacher,location,dayOfWeek,startMinute,endMinute,startWeek,endWeek,parity
            高等数学,陈立言,理科楼305,1,480,625,1,16,ALL
        """.trimIndent()
        val s = parseCourseCsv(csv, routine).single().sessions.single()
        assertEquals(480, s.startMinute)
        assertEquals(625, s.endMinute)
    }

    @Test
    fun `CSV：节次越界的行被跳过，其余行保留`() {
        val total = periods.size
        val csv = """
            name,dayOfWeek,startPeriod,endPeriod
            正常课,1,1,2
            坏数据,1,${total + 3},${total + 4}
        """.trimIndent()
        val list = parseCourseCsv(csv, routine)
        val normal = list.first { it.name == "正常课" }
        assertEquals(1, normal.sessions.size)
        // 坏数据那门课没有有效时段 → 不会产生课程（CSV 按课程名分组，无时段就不落库）
        assertTrue(list.none { it.name == "坏数据" })
    }

    // ---------------------------------------------------------------- 越界换算函数

    @Test
    fun `importPeriodsToMinutes：越界返回 null`() {
        val total = periods.size
        assertNull(importPeriodsToMinutes(routine, 0, 1))
        assertNull(importPeriodsToMinutes(routine, 1, total + 1))
        assertNull(importPeriodsToMinutes(routine, 3, 1))
        assertEquals(
            periods[0].startMinute to periods[0].endMinute,
            importPeriodsToMinutes(routine, 1, 1),
        )
    }
}
