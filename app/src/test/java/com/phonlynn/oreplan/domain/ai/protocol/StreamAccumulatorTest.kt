package com.phonlynn.oreplan.domain.ai.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 流式解析。
 *
 * ## 这一层为什么最容易出隐藏 bug
 *
 * 流式输出的错误**不报错，只是内容不对** —— 用户看到的是
 * "AI 的回答偶尔少几个字"或"工具参数拼错了导致执行失败"。
 * 而且它只在真实流式请求下暴露，非流式的测试完全测不到。
 *
 * 所以这里逐片模拟真实的 SSE 序列。
 */
class StreamAccumulatorTest {

    private fun line(json: String) = "data: $json"

    // ---------------------------------------------------------------- 文本流

    @Test
    fun `逐字拼接文本`() {
        val acc = StreamAccumulator()
        var shown = ""
        listOf("你", "好", "！").forEach { piece ->
            shown += acc.feed(line("""{"choices":[{"delta":{"content":"$piece"}}]}""")).content
        }
        acc.feed(line("[DONE]"))

        assertEquals("你好！", shown)
        assertEquals("你好！", acc.build().content)
    }

    /** `[DONE]` 不是 JSON，不该被当成解析失败。 */
    @Test
    fun `DONE 标记不产生内容`() {
        val acc = StreamAccumulator()
        assertEquals("", acc.feed(line("[DONE]")).content)
        assertNull(acc.build().content)
    }

    /** 空行与注释行（SSE 允许 `: keep-alive`）要忽略。 */
    @Test
    fun `空行与心跳行被忽略`() {
        val acc = StreamAccumulator()
        assertEquals("", acc.feed("").content)
        assertEquals("", acc.feed(": keep-alive").content)
        assertEquals("", acc.feed("data:").content)
        assertFalse(acc.sawAnything)
    }

    @Test
    fun `非 data 开头的行被忽略`() {
        val acc = StreamAccumulator()
        assertEquals("", acc.feed("event: message").content)
        assertFalse(acc.sawAnything)
    }

    @Test
    fun `坏 JSON 行被跳过而不抛异常`() {
        val acc = StreamAccumulator()
        assertEquals("", acc.feed(line("{坏掉的")).content)
        assertEquals("好", acc.feed(line("""{"choices":[{"delta":{"content":"好"}}]}""")).content)
    }

    // ---------------------------------------------------------------- 兼容点 ②

    /**
     * **工具调用参数分片到达** —— 这是流式最核心的难点。
     *
     * 参数会一个字符一个字符地拼，必须按 index 归组累加。
     */
    @Test
    fun `工具参数分片能正确拼接`() {
        val acc = StreamAccumulator()
        listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"create_item"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tit"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"le\":\"复习\"}"}}]}}]}""",
        ).forEach { acc.feed(line(it)) }

        val calls = acc.build().toolCalls
        assertEquals(1, calls.size)
        assertEquals("create_item", calls[0].name)
        assertEquals("复习", calls[0].arguments.getString("title"))
        assertFalse(calls[0].argumentsParseFailed)
    }

    /**
     * ⚠️ **兼容点 ②：首片可能不带 `index`。**
     *
     * 规范要求每片都带，但有的厂商只在第一片给。
     * 实现若把"缺失"当成 index=0，多工具场景就会串味；
     * 若当成新调用，参数就拼不起来。正确做法是**沿用上一次的 index**。
     */
    @Test
    fun `分片缺失 index 时沿用上一次`() {
        val acc = StreamAccumulator()
        listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"f"}}]}}]}""",
            // 后续分片没有 index
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"a\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":":1}"}}]}}]}""",
        ).forEach { acc.feed(line(it)) }

        val calls = acc.build().toolCalls
        assertEquals("缺失 index 不该被拆成多个调用", 1, calls.size)
        assertEquals(1, calls[0].arguments.getInt("a"))
    }

    /** 多个工具调用要按 index 分开，不能混在一起。 */
    @Test
    fun `并行工具调用按 index 分开`() {
        val acc = StreamAccumulator()
        listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"a","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"b","arguments":"{}"}}]}}]}""",
        ).forEach { acc.feed(line(it)) }

        val calls = acc.build().toolCalls
        assertEquals(2, calls.size)
        assertEquals("a", calls[0].name)
        assertEquals("b", calls[1].name)
    }

    /** 乱序到达也要按 index 排好（有的厂商并行时顺序不定）。 */
    @Test
    fun `乱序到达的调用按 index 排序`() {
        val acc = StreamAccumulator()
        listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"second","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"first","arguments":"{}"}}]}}]}""",
        ).forEach { acc.feed(line(it)) }

        val calls = acc.build().toolCalls
        assertEquals("first", calls[0].name)
        assertEquals("second", calls[1].name)
    }

    /** 没有 name 的分片（只有参数）不该产出一个无名调用。 */
    @Test
    fun `只有参数没有函数名时不产生调用`() {
        val acc = StreamAccumulator()
        acc.feed(line("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}"""))

        assertTrue(acc.build().toolCalls.isEmpty())
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `捕获 finish_reason`() {
        val acc = StreamAccumulator()
        acc.feed(line("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertEquals("stop", acc.finishReason)
    }

    @Test
    fun `捕获推理内容`() {
        val acc = StreamAccumulator()
        acc.feed(line("""{"choices":[{"delta":{"reasoning_content":"想"}}]}"""))
        acc.feed(line("""{"choices":[{"delta":{"reasoning_content":"一下"}}]}"""))

        assertEquals("想一下", acc.build().reasoning)
    }

    /**
     * **推理必须逐片交出来**，不能攒到流结束。
     *
     * 思考通常先于正文产生；一次性给等于「思考过程整段延迟到回答之后才出现」，
     * 用户会觉得思考没有流式效果（用户反馈过这个）。
     */
    @Test
    fun `推理按增量逐片给出`() {
        val acc = StreamAccumulator()
        val pieces = listOf("先", "想", "再答")

        val got = pieces.joinToString("") { piece ->
            acc.feed(line("""{"choices":[{"delta":{"reasoning_content":"$piece"}}]}""")).reasoning
        }

        assertEquals("先想再答", got)
    }

    /** 同一片里正文与推理都可能有增量 —— 两者要分别交出去。 */
    @Test
    fun `同一片里正文与推理分别给出`() {
        val acc = StreamAccumulator()
        val delta = acc.feed(
            line("""{"choices":[{"delta":{"reasoning_content":"想","content":"答"}}]}"""),
        )

        assertEquals("答", delta.content)
        assertEquals("想", delta.reasoning)
    }

    /** 文本与工具调用可能同时出现（模型边说边调）。 */
    @Test
    fun `文本与工具调用并存`() {
        val acc = StreamAccumulator()
        acc.feed(line("""{"choices":[{"delta":{"content":"好的，"}}]}"""))
        acc.feed(
            line(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,
                   "function":{"name":"f","arguments":"{}"}}]}}]}""",
            ),
        )

        val response = acc.build()
        assertEquals("好的，", response.content)
        assertEquals(1, response.toolCalls.size)
    }

    /** 参数拼出坏 JSON 时要标记出来。 */
    @Test
    fun `拼接后是坏 JSON 时标记失败`() {
        val acc = StreamAccumulator()
        acc.feed(line("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"f"}}]}}]}"""))
        acc.feed(line("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{坏"}}]}}]}"""))

        assertTrue(acc.build().toolCalls[0].argumentsParseFailed)
    }

    /** 完全空的流不该崩，也不该产出内容。 */
    @Test
    fun `空流是安全的`() {
        val acc = StreamAccumulator()
        val response = acc.build()

        assertNull(response.content)
        assertTrue(response.toolCalls.isEmpty())
    }
}
