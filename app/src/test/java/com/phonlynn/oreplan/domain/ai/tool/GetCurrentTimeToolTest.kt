package com.phonlynn.oreplan.domain.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「当前时间」工具。
 *
 * ## 这里测的是**日期算术**，不是文本格式
 *
 * 工具把「明天/昨天/本周/下周」预先算好，唯一理由就是**模型自己算不可靠**。
 * 所以这段算术错一位，比这个工具不存在还糟 —— 模型会拿着一个"权威"的错日期
 * 去读写用户的数据。
 *
 * 因此重点覆盖边界：**跨月、跨年、周日与周一**（一周的首尾正是最容易差一天的地方）。
 */
class GetCurrentTimeToolTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 30): Instant =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    /** 从输出里取「· 今天：2026-10-03（六）」这类行里的日期。 */
    private fun dateOf(text: String, label: String): LocalDate {
        val line = text.lineSequence().first { it.startsWith("· $label：") }
        val iso = Regex("""(\d{4}-\d{2}-\d{2})""").find(line)!!.groupValues[1]
        return LocalDate.parse(iso)
    }

    @Test
    fun `今天明天昨天`() {
        val text = describeNow(at(2026, 10, 3), zone)

        assertEquals(LocalDate.of(2026, 10, 3), dateOf(text, "今天"))
        assertEquals(LocalDate.of(2026, 10, 4), dateOf(text, "明天"))
        assertEquals(LocalDate.of(2026, 10, 2), dateOf(text, "昨天"))
    }

    /** 2026-10-03 是**周六** → 本周应是 09-28（周一）～ 10-04（周日）。 */
    @Test
    fun `本周是周一到周日`() {
        val text = describeNow(at(2026, 10, 3), zone)
        val line = text.lineSequence().first { it.startsWith("· 本周：") }

        assertTrue("本周起点该是周一 09-28：$line", line.contains("2026-09-28"))
        assertTrue("本周终点该是周日 10-04：$line", line.contains("2026-10-04"))
    }

    /** ⚠️ 周日属于**这一周**，不能算成下周的第一天。 */
    @Test
    fun `周日仍属于本周`() {
        // 2026-10-04 是周日
        val text = describeNow(at(2026, 10, 4), zone)

        assertEquals(LocalDate.of(2026, 10, 4), dateOf(text, "今天"))
        assertTrue(
            "周日应当仍在本周（09-28～10-04）里：$text",
            text.lineSequence().first { it.startsWith("· 本周：") }.contains("2026-10-04"),
        )
        assertTrue(
            "下周应当是 10-05 起：$text",
            text.lineSequence().first { it.startsWith("· 下周：") }.contains("2026-10-05"),
        )
    }

    /** ⚠️ 周一的"昨天"是**上周日** —— 跨周，最容易差一天。 */
    @Test
    fun `周一的昨天是上周日`() {
        // 2026-10-05 是周一
        val text = describeNow(at(2026, 10, 5), zone)

        assertEquals(LocalDate.of(2026, 10, 5), dateOf(text, "今天"))
        assertEquals(LocalDate.of(2026, 10, 4), dateOf(text, "昨天"))
        assertTrue(
            "周一所在周应从它自己开始：$text",
            text.lineSequence().first { it.startsWith("· 本周：") }.contains("2026-10-05"),
        )
    }

    /** ⚠️ 跨月：10-31 的明天是 11-01。 */
    @Test
    fun `跨月`() {
        // 2026-10-31 是周六
        val text = describeNow(at(2026, 10, 31), zone)

        assertEquals(LocalDate.of(2026, 11, 1), dateOf(text, "明天"))
        assertTrue(
            "本周应跨到 11-01：$text",
            text.lineSequence().first { it.startsWith("· 本周：") }.contains("2026-11-01"),
        )
    }

    /** ⚠️ 跨年：12-31 的明天是次年 01-01。 */
    @Test
    fun `跨年`() {
        // 2026-12-31 是周四
        val text = describeNow(at(2026, 12, 31), zone)

        assertEquals(LocalDate.of(2027, 1, 1), dateOf(text, "明天"))
        assertEquals(LocalDate.of(2026, 12, 30), dateOf(text, "昨天"))
        assertTrue(
            "下周应落在 2027 年：$text",
            text.lineSequence().first { it.startsWith("· 下周：") }.contains("2027-01-04"),
        )
    }

    /** 闰年 2 月：2028-02-28 的明天是 02-29。 */
    @Test
    fun `闰年二月`() {
        // 2028 是闰年；2028-02-28 是周一
        val text = describeNow(at(2028, 2, 28), zone)

        assertEquals(LocalDate.of(2028, 2, 29), dateOf(text, "明天"))
        assertEquals(LocalDate.of(2028, 3, 1), dateOf(text, "今天").plusDays(2))
    }

    /** 时间格式必须与 `get_items` 返回的**完全一致**，否则模型要在两套写法间换算。 */
    @Test
    fun `时间格式与条目工具一致`() {
        val now = at(2026, 10, 3, 8, 5)
        val text = describeNow(now, zone)

        assertEquals(ItemArgs.fullText(now, zone), text.lineSequence().first().let {
            Regex("""现在是 (.+?)，""").find(it)!!.groupValues[1]
        })
        assertTrue("时间应是 yyyy-MM-dd HH:mm：$text", text.contains("2026-10-03 08:05"))
    }

    /** 星期要用中文单字，和课表、日历里的写法一致。 */
    @Test
    fun `星期是中文单字`() {
        // 2026-10-03 周六
        val text = describeNow(at(2026, 10, 3), zone)
        assertTrue("应显示星期六：$text", text.contains("（六）"))
        assertTrue(text.contains("星期六"))
    }

    /** 工具本身：无参数、只读、且真的能跑出内容。 */
    @Test
    fun `工具无参数且只读`() {
        val tool = GetCurrentTimeTool()

        assertEquals(ToolDanger.READ, tool.danger)
        assertEquals("get_current_time", tool.name)
        assertEquals("object", tool.parameters.optString("type"))
        assertTrue("无参数工具要有空的 properties", tool.parameters.optJSONObject("properties") != null)
        assertEquals("无参数工具不该有 required", null, tool.parameters.optJSONArray("required"))
    }
}
