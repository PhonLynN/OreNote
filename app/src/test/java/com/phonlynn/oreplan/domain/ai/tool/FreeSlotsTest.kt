package com.phonlynn.oreplan.domain.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空闲时段计算。
 *
 * 这是 AI 回答「我什么时候有空」的**唯一依据** —— 算错不会报错、只会给错建议，
 * 而且用户很可能照着它去安排事情。所以把边界全钉一遍：
 * 相接、重叠、包含、窗口裁剪、刚好等于最小时长。
 *
 * 区间是**半开**的 `[start, end)`：`09:00–10:00` 和 `10:00–11:00` 中间没有空档。
 */
class FreeSlotsTest {

    private val from = 8 * 60
    private val to = 22 * 60

    /** 9:00–10:00 用 `540..599` 表达（半开）。 */
    private fun slot(startH: Int, startM: Int, endH: Int, endM: Int) =
        (startH * 60 + startM)..(endH * 60 + endM - 1)

    private fun free(occupied: List<IntRange>, min: Int = 30) =
        freeSlots(occupied, from, to, min)

    @Test
    fun `没有任何占用时整天都是空闲`() {
        val result = free(emptyList())
        assertEquals(1, result.size)
        assertEquals(from, result[0].first)
        assertEquals(to - 1, result[0].last)
    }

    @Test
    fun `占用把一天切成两段`() {
        val result = free(listOf(slot(10, 0, 11, 0)))
        assertEquals(2, result.size)
        assertEquals("08:00–10:00", label(result[0]))
        assertEquals("11:00–22:00", label(result[1]))
    }

    /**
     * ⚠️ 这条是半开区间的意义所在。
     *
     * `09:00–10:00` 与 `10:00–11:00` 首尾相接，中间**没有**空档。
     * 用闭区间会算出「10:00 到 10:00 空闲」这种零长度结果。
     */
    @Test
    fun `首尾相接的两个占用之间没有空档`() {
        val result = free(listOf(slot(9, 0, 10, 0), slot(10, 0, 11, 0)))
        assertEquals(2, result.size)
        assertEquals("08:00–09:00", label(result[0]))
        assertEquals("11:00–22:00", label(result[1]))
    }

    @Test
    fun `重叠的占用会被合并`() {
        val result = free(listOf(slot(9, 0, 12, 0), slot(10, 0, 11, 0)))
        assertEquals(2, result.size)
        assertEquals("12:00–22:00", label(result[1]))
    }

    @Test
    fun `乱序输入不影响结果`() {
        val a = free(listOf(slot(15, 0, 16, 0), slot(9, 0, 10, 0)))
        val b = free(listOf(slot(9, 0, 10, 0), slot(15, 0, 16, 0)))
        assertEquals(b.map { label(it) }, a.map { label(it) })
    }

    @Test
    fun `窗口外的占用被忽略`() {
        // 06:00–07:00 在窗口之前；23:00–23:30 在窗口之后
        val result = free(listOf(slot(6, 0, 7, 0), slot(23, 0, 23, 30)))
        assertEquals(1, result.size)
        assertEquals("08:00–22:00", label(result[0]))
    }

    @Test
    fun `跨越窗口边界的占用被裁剪`() {
        val result = free(listOf(slot(7, 0, 9, 0), slot(21, 0, 23, 0)))
        assertEquals(1, result.size)
        assertEquals("09:00–21:00", label(result[0]))
    }

    /** 短于最小时长的空档要丢掉 —— 15 分钟的缝不值得建议给用户。 */
    @Test
    fun `过短的空档被丢弃`() {
        // 10:00–11:00 占用后，10:50 之前是空的；再占 10:50 会留下 50 分钟的缝
        val result = freeSlots(
            listOf(slot(8, 0, 10, 50), slot(11, 0, 22, 0)),
            from, to, minMinutes = 60,
        )
        assertTrue("50 分钟的缝不该留下：${result.map { label(it) }}", result.isEmpty())
    }

    /** 刚好等于最小时长要**保留**（边界是闭的）。 */
    @Test
    fun `刚好等于最小时长要保留`() {
        val result = freeSlots(
            listOf(slot(8, 0, 12, 0), slot(13, 0, 22, 0)),
            from, to, minMinutes = 60,
        )
        assertEquals(1, result.size)
        assertEquals("12:00–13:00", label(result[0]))
    }

    @Test
    fun `占用覆盖整个窗口时没有空闲`() {
        assertTrue(free(listOf(slot(7, 0, 23, 0))).isEmpty())
    }

    @Test
    fun `窗口本身短于最小时长时直接返回空`() {
        assertTrue(freeSlots(emptyList(), 10 * 60, 10 * 60 + 20, minMinutes = 30).isEmpty())
    }

    /** `HH:mm` 文本，断言里比数字好读。 */
    private fun label(range: IntRange) = "${minuteLabel(range.first)}–${minuteLabel(range.last + 1)}"
}
