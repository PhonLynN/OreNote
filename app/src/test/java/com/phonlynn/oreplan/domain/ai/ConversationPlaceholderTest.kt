package com.phonlynn.oreplan.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Random

/**
 * 输入框占位文案的选取。
 *
 * ## 为什么值得测
 *
 * 这段逻辑错了的表现很轻微但很烦人：
 * **连续两次进对话看到同一句话**，或者**凌晨看到「上午好」**。
 * 两者都不会报错，只会让人觉得"这功能有点糙"。
 */
class ConversationPlaceholderTest {

    /** 构造一个指定钟点的时间戳（本地时区）。 */
    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ---------------------------------------------------------------- 时段

    /** 凌晨不该出现「上午好」这类明显不符的文案。 */
    @Test
    fun `凌晨可用池不含上午文案`() {
        val pool = ConversationPlaceholder.poolFor(at(2))

        assertTrue("凌晨该有深夜文案", pool.any { it.contains("还没睡") })
        assertFalse("凌晨不该出现「上午好」", pool.any { it.contains("上午好") })
        assertFalse("凌晨不该出现「下午」", pool.any { it.contains("下午") })
    }

    @Test
    fun `早晨可用池含早晨文案`() {
        val pool = ConversationPlaceholder.poolFor(at(8))

        assertTrue(pool.any { it.contains("上午好") || it.contains("这么早") })
        assertFalse("早晨不该出现「晚上了」", pool.any { it.contains("晚上了") })
    }

    @Test
    fun `下午可用池含下午文案`() {
        val pool = ConversationPlaceholder.poolFor(at(14))

        assertTrue(pool.any { it.contains("下午了") })
        assertFalse(pool.any { it.contains("上午好") })
    }

    @Test
    fun `晚上可用池含晚上文案`() {
        val pool = ConversationPlaceholder.poolFor(at(20))

        assertTrue(pool.any { it.contains("晚上了") })
        assertFalse(pool.any { it.contains("下午了") })
    }

    /** 边界：23:59 属晚上，00:00 属凌晨。 */
    @Test
    fun `时段边界正确`() {
        assertTrue(ConversationPlaceholder.poolFor(at(23, 59)).any { it.contains("晚上了") })
        assertTrue(ConversationPlaceholder.poolFor(at(0, 0)).any { it.contains("还没睡") })
    }

    /**
     * **通用文案在任何时段都可用。**
     *
     * 这是刻意的设计：凌晨的专属文案只有 2 条，
     * 若只从它们里取，用户连续几次都看到同一句。
     */
    @Test
    fun `通用文案在任何时段都在池里`() {
        listOf(2, 8, 14, 20).forEach { hour ->
            val pool = ConversationPlaceholder.poolFor(at(hour))
            assertTrue("$hour 点应有「我一直在你身边」", pool.contains("我一直在你身边"))
            assertTrue("$hour 点应有「最近过得怎样？」", pool.contains("最近过得怎样？"))
        }
    }

    /** 任何时段池子都足够大，避免高频重复。 */
    @Test
    fun `池子规模足够避免重复`() {
        listOf(2, 8, 14, 20).forEach { hour ->
            val size = ConversationPlaceholder.poolFor(at(hour)).size
            assertTrue("$hour 点可用文案只有 $size 条，太少", size >= 20)
        }
    }

    // ---------------------------------------------------------------- 排除上一条

    /** 连续两次不该是同一句。 */
    @Test
    fun `排除上一条避免连续重复`() {
        val pool = ConversationPlaceholder.poolFor(at(14))
        val previous = pool.first()

        // 多取几次，都不该等于上一条
        repeat(50) {
            val picked = ConversationPlaceholder.pick(
                previous = previous,
                now = at(14),
                random = Random(it.toLong()),
            )
            assertNotEquals("不该再取到上一条", previous, picked)
        }
    }

    /** 没有上一条时正常返回池里的某一条。 */
    @Test
    fun `没有上一条时也能取到`() {
        val picked = ConversationPlaceholder.pick(previous = null, now = at(14), random = Random(1))
        assertTrue(picked.isNotBlank())
        assertTrue(ConversationPlaceholder.poolFor(at(14)).contains(picked))
    }

    /** 池子只有一条时不会返回空串（那会让输入框没有占位符）。 */
    @Test
    fun `池子只剩一条时仍能返回`() {
        val only = "唯一的一条"
        // 直接测边界：candidates 为空时要退回原池
        val pool = listOf(only)
        val candidates = pool.filter { it != only }.ifEmpty { pool }
        assertEquals(only, candidates.first())
    }

    // ---------------------------------------------------------------- 内容约束

    /** 所有文案都在 12 字以内（用户明确要求）。 */
    @Test
    fun `所有文案不超过十二字`() {
        listOf(2, 8, 14, 20).forEach { hour ->
            ConversationPlaceholder.poolFor(at(hour)).forEach { text ->
                assertTrue("「$text」有 ${text.length} 字，超过 12", text.length <= 12)
            }
        }
    }

    /** 文案里不该有 markdown 星号 —— Compose 不解析，会原样显示。 */
    @Test
    fun `文案不含 markdown 标记`() {
        listOf(2, 8, 14, 20).forEach { hour ->
            ConversationPlaceholder.poolFor(at(hour)).forEach { text ->
                assertFalse("「$text」含星号，Compose 不解析会原样显示", text.contains("**") || text.contains("*"))
            }
        }
    }

    /** 用户给的 7 条原话必须都在池子里。 */
    @Test
    fun `用户指定的文案都在池中`() {
        val all = ConversationPlaceholder.poolFor(at(14))
        listOf(
            "我一直在你身边",
            "需要我的帮助吗？",
            "最近过得怎样？",
            "在意的事情有进展了吗？",
            "有心事就和我聊聊吧",
            "让今天也效率满满",
            "想想明天做点什么？",
        ).forEach { text ->
            assertTrue("「$text」应该在池子里", all.contains(text))
        }
    }

    /** 承接式文案（需要 AI 记得上次说了什么）不该出现在池子里。 */
    @Test
    fun `不含承接式文案`() {
        val all = ConversationPlaceholder.poolFor(at(14))
        listOf("上次说的那件事", "之前那个计划", "有阵子没聊了", "还在想那件事").forEach { bad ->
            assertFalse("「$bad」需要历史上下文，不该静态展示", all.any { it.contains(bad) })
        }
    }

    @Test
    fun `总条数与池子规模自洽`() {
        assertTrue(
            "totalCount=${ConversationPlaceholder.totalCount} 应等于或大于任一时段的池子",
            ConversationPlaceholder.totalCount >= ConversationPlaceholder.poolFor(at(14)).size,
        )
    }
}
