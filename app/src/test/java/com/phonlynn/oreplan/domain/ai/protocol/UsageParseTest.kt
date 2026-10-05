package com.phonlynn.oreplan.domain.ai.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * token 用量的解析（用户 2026-10-04 要的「token 消耗显示」）。
 *
 * ## 为什么这些形状值得逐个钉住
 *
 * 「显示不出来」的两种根因都在这里：
 *
 * 1. **流式**：`usage` 只在**最后一片**给，而且那一片的 `choices` 可能是
 *    **空数组**。若解析代码先 `?: return`（拿不到 choice 就退出），
 *    用量就永远读不到 —— 表现为"开关开了但一个数字都没有"。
 * 2. **非流式**：`usage` 与 `choices` **平级**（不在 choice 里）。
 *    写进 choice 里找就找不到。
 *
 * 这两条都是"看着能跑、实际永远拿不到值"的静默失败，所以必须有测试。
 */
class UsageParseTest {

    // ---------------------------------------------------------------- 流式

    private fun feed(vararg lines: String): StreamAccumulator {
        val acc = StreamAccumulator()
        lines.forEach { acc.feed(it) }
        return acc
    }

    /** 标准的最后一片：`choices` 是空数组，只有 `usage`。 */
    @Test
    fun `流式最后一片的 usage 要读到`() {
        val acc = feed(
            """data: {"choices":[{"delta":{"content":"你"}}]}""",
            """data: {"choices":[{"delta":{"content":"好"}}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}""",
            """data: [DONE]""",
        )

        val usage = acc.build().usage
        assertNotNull("usage 没读到 —— 多半是 choices 为空时提前 return 了", usage)
        assertEquals(120, usage!!.totalTokens)
        assertEquals(100, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
    }

    /** 正文与用量要**同时**拿到（读 usage 不能把正文吞掉）。 */
    @Test
    fun `读 usage 不影响正文`() {
        val acc = feed(
            """data: {"choices":[{"delta":{"content":"你好"}}]}""",
            """data: {"choices":[],"usage":{"total_tokens":5}}""",
        )

        val response = acc.build()
        assertEquals("你好", response.content)
        assertEquals(5, response.usage?.totalTokens)
    }

    /** 厂商没给 `total_tokens` → 用 prompt + completion 兜底。 */
    @Test
    fun `缺 total 时用输入加输出兜底`() {
        val acc = feed(
            """data: {"choices":[],"usage":{"prompt_tokens":30,"completion_tokens":12}}""",
        )

        assertEquals(42, acc.build().usage?.totalTokens)
    }

    /** 整条流都没有 usage → `null`（界面据此不显示那一格，而不是显示 0）。 */
    @Test
    fun `没有 usage 时是 null 而不是零`() {
        val acc = feed(
            """data: {"choices":[{"delta":{"content":"你好"}}]}""",
            """data: [DONE]""",
        )

        assertNull("没给用量就不该编一个 0 出来", acc.build().usage)
    }

    /** `usage` 全是 0（有些厂商"占位"这么发）也按"没给"处理。 */
    @Test
    fun `全零的 usage 视为没给`() {
        val acc = feed(
            """data: {"choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""",
        )

        assertNull(acc.build().usage)
    }

    // ---------------------------------------------------------------- 非流式

    /** 非流式：`usage` 与 `choices` **平级**。 */
    @Test
    fun `非流式在同级读 usage`() {
        val body = """
            {
              "choices": [{"message": {"content": "你好"}, "finish_reason": "stop"}],
              "usage": {"prompt_tokens": 8, "completion_tokens": 2, "total_tokens": 10}
            }
        """.trimIndent()

        val response = ChatProtocol.parseResponse(body)
        assertNotNull(response)
        assertEquals(10, response!!.usage?.totalTokens)
    }

    /** 非流式且没有 usage → null。 */
    @Test
    fun `非流式没有 usage 时是 null`() {
        val body = """{"choices":[{"message":{"content":"hi"}}]}"""
        assertNull(ChatProtocol.parseResponse(body)?.usage)
    }

    // ---------------------------------------------------------------- 格式

    /** 界面文案：`a,bcd tokens`（用户指定的格式）。 */
    @Test
    fun `千位分隔格式`() {
        assertEquals("999 tokens", Usage(totalTokens = 999).label)
        assertEquals("1,000 tokens", Usage(totalTokens = 1000).label)
        assertEquals("12,345 tokens", Usage(totalTokens = 12345).label)
        assertEquals("1,234,567 tokens", Usage(totalTokens = 1234567).label)
    }
}
