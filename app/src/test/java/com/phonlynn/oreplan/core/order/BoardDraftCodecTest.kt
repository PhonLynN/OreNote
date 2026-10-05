package com.phonlynn.oreplan.core.order

import com.phonlynn.oreplan.domain.model.AutoPinRule
import com.phonlynn.oreplan.domain.model.Frequency
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未完成草稿的序列化往返。
 *
 * 盯的是「暂存了但回填丢字段」这类隐蔽问题：字段一旦在编码/解码任一侧漏掉，
 * 用户就要重打一遍内容。
 */
class BoardDraftCodecTest {

    private val sample = BoardDraftPayload(
        typeKey = "TODO",
        title = "买牛奶",
        body = "顺便看看鸡蛋",
        color = "amber",
        pinned = true,
        secret = true,
        secretHint = "别忘",
        tagId = "tag-1",
        widthMode = "full",
        imageLayout = "grid",
        showDate = false,
        reminderAtMillis = 1_700_000_000_000L,
        autoPin = AutoPinRule.OnDate(
            at = java.time.Instant.ofEpochMilli(1_800_000_000_000L),
            durationMinutes = 480,
        ),
    )

    @Test
    fun `往返不丢任何字段`() {
        val back = BoardDraftCodec.decode(BoardDraftCodec.encode(sample))
        assertEquals(sample, back)
    }

    @Test
    fun `可空字段为 null 时往返仍为 null`() {
        val p = sample.copy(
            color = null,
            tagId = null,
            widthMode = null,
            imageLayout = null,
            reminderAtMillis = null,
            autoPin = null,
        )
        val back = BoardDraftCodec.decode(BoardDraftCodec.encode(p))
        assertEquals(p, back)
        assertNull(back?.color)
        assertNull(back?.tagId)
        assertNull(back?.widthMode)
        assertNull(back?.imageLayout)
        assertNull(back?.reminderAtMillis)
        assertNull(back?.autoPin)
    }

    @Test
    fun `动态置顶：日期型往返不丢`() {
        val p = sample.copy(
            autoPin = AutoPinRule.OnDate(
                at = java.time.Instant.ofEpochMilli(1_800_123_456_000L),
                durationMinutes = 90,
            ),
        )
        assertEquals(p, BoardDraftCodec.decode(BoardDraftCodec.encode(p)))
    }

    @Test
    fun `动态置顶：周期型往返不丢`() {
        val p = sample.copy(
            autoPin = AutoPinRule.Recurring(
                rule = RecurrenceRule(
                    frequency = Frequency.WEEKLY,
                    byDay = setOf(java.time.DayOfWeek.MONDAY),
                ),
                minuteOfDay = 8 * 60 + 30,
                durationMinutes = 1440,
            ),
        )
        assertEquals(p, BoardDraftCodec.decode(BoardDraftCodec.encode(p)))
    }

    @Test
    fun `动态置顶：旧草稿没有该字段时不崩且视为未启用`() {
        // 模拟 v9 之前存下的草稿（JSON 里没有 autoPin* 三个键）。
        val legacy = """{"type":"QUICK","title":"旧草稿","body":"","color":null,"pinned":false,"secret":false,"secretHint":"","tagId":null,"widthMode":null,"imageLayout":null,"showDate":true,"reminderAtMillis":null}"""
        val back = BoardDraftCodec.decode(legacy)
        assertEquals("旧草稿", back?.title)
        assertNull(back?.autoPin)
    }

    @Test
    fun `空串与非法内容表示没有草稿`() {
        assertNull(BoardDraftCodec.decode(null))
        assertNull(BoardDraftCodec.decode(""))
        assertNull(BoardDraftCodec.decode("   "))
        assertNull(BoardDraftCodec.decode("{ 不是合法 JSON"))
    }

    @Test
    fun `缺字段时取安全默认值`() {
        val back = BoardDraftCodec.decode("{}")
        assertEquals("QUICK", back?.typeKey)
        assertEquals("", back?.title)
        assertEquals("", back?.body)
        assertFalse(back?.pinned ?: true)
        assertFalse(back?.secret ?: true)
        assertTrue(back?.showDate ?: false)
    }

    @Test
    fun `hasContent 只看标题与正文`() {
        assertTrue(sample.hasContent)
        assertFalse(sample.copy(title = "", body = "").hasContent)
        assertTrue(sample.copy(title = "", body = "只有正文").hasContent)
    }
}
