package com.phonlynn.oreplan.domain.ai.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI 兼容协议的解析。
 *
 * ## 为什么这一层要测透
 *
 * 用户要求「换 API 要简单」，而**这件事能否成立完全取决于这一层写对没有**。
 * 各家都声称"兼容 OpenAI"，但在三个地方有细微差别 ——
 * 每一处都能让代码在换厂商时崩掉，而**在只用一个厂商时完全测不出来**。
 *
 * 所以这里刻意针对那三处差别各写用例：
 *  ① `arguments` 是字符串还是对象
 *  ② 流式分片的 `index` 缺失
 *  ③ `finish_reason` 取值不一致
 */
class ChatProtocolTest {

    // ---------------------------------------------------------------- 兼容点 ①

    /** 规范行为：`arguments` 是 JSON **字符串**，要自己解析。 */
    @Test
    fun `arguments 是字符串时能解析`() {
        val call = ToolCall.fromJson(
            JSONObject(
                """
                {"id":"call_1","type":"function",
                 "function":{"name":"get_schedule","arguments":"{\"from\":\"2026-03-05\"}"}}
                """.trimIndent(),
            ),
        )

        assertEquals("get_schedule", call.name)
        assertEquals("2026-03-05", call.arguments.getString("from"))
        assertFalse(call.argumentsParseFailed)
    }

    /**
     * 部分国内厂商**直接给对象**。
     *
     * 这个差别会让"把 arguments 当字符串解析"的实现直接抛异常 ——
     * 而它只在换到那家厂商时才暴露。
     */
    @Test
    fun `arguments 是对象时也能解析`() {
        val call = ToolCall.fromJson(
            JSONObject(
                """
                {"id":"call_1","type":"function",
                 "function":{"name":"get_schedule","arguments":{"from":"2026-03-05"}}}
                """.trimIndent(),
            ),
        )

        assertEquals("get_schedule", call.name)
        assertEquals("2026-03-05", call.arguments.getString("from"))
        assertFalse(call.argumentsParseFailed)
    }

    /** 无参数工具：有的厂商给空串，那是**合法的空参数**，不是解析失败。 */
    @Test
    fun `arguments 为空串时视为空参数`() {
        val call = ToolCall.fromJson(
            JSONObject("""{"function":{"name":"list_tags","arguments":""}}"""),
        )

        assertEquals("list_tags", call.name)
        assertEquals(0, call.arguments.length())
        assertFalse("空参数不该被判为解析失败", call.argumentsParseFailed)
    }

    /** arguments 缺失（键都没有）同样按空参数处理。 */
    @Test
    fun `arguments 缺失时视为空参数`() {
        val call = ToolCall.fromJson(JSONObject("""{"function":{"name":"now"}}"""))

        assertEquals("now", call.name)
        assertFalse(call.argumentsParseFailed)
    }

    /**
     * **参数真的坏了要能被识别出来。**
     *
     * 这条很重要：坏参数不该被静默当成空参数去执行 ——
     * 那会变成"用空参数调了一次工具"，可能删错东西。
     * 正确做法是标出来，让上层把错误回给模型重试。
     */
    @Test
    fun `arguments 是坏 JSON 时标记为解析失败`() {
        val call = ToolCall.fromJson(
            JSONObject("""{"function":{"name":"create_item","arguments":"{不是合法JSON"}}"""),
        )

        assertTrue("坏参数必须被标记出来，不能静默当空参数", call.argumentsParseFailed)
        assertEquals(0, call.arguments.length())
    }

    /** `id` 缺失时要能兜底（有些厂商省略 id）。 */
    @Test
    fun `id 缺失时有兜底值`() {
        val call = ToolCall.fromJson(JSONObject("""{"function":{"name":"now","arguments":"{}"}}"""))
        assertTrue("id 不该为空", call.id.isNotBlank())
    }

    /** 回填给模型时必须还原成**字符串**形式（规范要求）。 */
    @Test
    fun `回填时 arguments 是字符串`() {
        val call = ToolCall.fromJson(
            JSONObject("""{"id":"c1","function":{"name":"f","arguments":{"a":1}}}"""),
        )
        val json = call.toJson()

        assertTrue(
            "规范要求 arguments 是字符串，实际：" + json.getJSONObject("function").get("arguments"),
            json.getJSONObject("function").get("arguments") is String,
        )
        assertEquals("function", json.getString("type"))
    }

    // ---------------------------------------------------------------- 非流式响应

    @Test
    fun `解析普通回复`() {
        val response = ChatProtocol.parseResponse(
            """
            {"choices":[{"message":{"role":"assistant","content":"你好！"},"finish_reason":"stop"}]}
            """.trimIndent(),
        )

        assertEquals("你好！", response?.content)
        assertFalse(response!!.wantsTools)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `解析带工具调用的回复`() {
        val response = ChatProtocol.parseResponse(
            """
            {"choices":[{"message":{"role":"assistant","content":null,
              "tool_calls":[{"id":"c1","type":"function",
                "function":{"name":"get_schedule","arguments":"{\"from\":\"2026-03-05\"}"}}]},
              "finish_reason":"tool_calls"}]}
            """.trimIndent(),
        )

        assertTrue(response!!.wantsTools)
        assertEquals(1, response.toolCalls.size)
        assertEquals("get_schedule", response.toolCalls[0].name)
    }

    /**
     * ⚠️ **兼容点 ③：`finish_reason` 各家取值不同。**
     *
     * 所以判据必须是"有没有 toolCalls"，不能看 finish_reason。
     * 这条用例刻意用旧格式的 `function_call` 值 —— 若实现依赖它就会挂。
     */
    @Test
    fun `finish_reason 用旧格式时仍能识别工具调用`() {
        val response = ChatProtocol.parseResponse(
            """
            {"choices":[{"message":{"tool_calls":[{"id":"c1",
              "function":{"name":"f","arguments":"{}"}}]},"finish_reason":"function_call"}]}
            """.trimIndent(),
        )

        assertTrue("判据应看 toolCalls 而非 finish_reason", response!!.wantsTools)
    }

    /** 空 content 不该变成字符串 "null"。 */
    @Test
    fun `content 为 null 时不多出字符串 null`() {
        val response = ChatProtocol.parseResponse(
            """{"choices":[{"message":{"content":null},"finish_reason":"stop"}]}""",
        )
        assertNull(response?.content)
    }

    /** HTTP 200 里带 error 对象的情况（部分厂商这么干）。 */
    @Test
    fun `响应体含 error 时解析为 null`() {
        assertNull(ChatProtocol.parseResponse("""{"error":{"message":"invalid key"}}"""))
    }

    @Test
    fun `响应体不是 JSON 时返回 null 而不抛异常`() {
        assertNull(ChatProtocol.parseResponse("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `从错误体里提取可读原因`() {
        assertEquals(
            "Invalid API key",
            ChatProtocol.parseErrorMessage("""{"error":{"message":"Invalid API key"}}"""),
        )
        assertNull(ChatProtocol.parseErrorMessage("""{"error":{"code":401}}"""))
    }

    /** 思考内容各家字段名不同，依次尝试。 */
    @Test
    fun `能读出推理内容`() {
        val a = ChatProtocol.parseResponse(
            """{"choices":[{"message":{"content":"x","reasoning_content":"想了一下"}}]}""",
        )
        val b = ChatProtocol.parseResponse(
            """{"choices":[{"message":{"content":"x","reasoning":"想了一下"}}]}""",
        )

        assertEquals("想了一下", a?.reasoning)
        assertEquals("想了一下", b?.reasoning)
    }

    // ---------------------------------------------------------------- 请求组装

    /** 聊天模式（allowTools = false）**不能**把 tools 发出去。 */
    @Test
    fun `不允许工具时请求里没有 tools 字段`() {
        val request = ChatRequest(
            model = "m",
            messages = listOf(ChatMessage.user("hi")),
            tools = listOf(ToolSpec("f", "d", JSONObject("""{"type":"object"}"""))),
            allowTools = false,
        )

        assertFalse(
            "聊天模式必须物理上不带工具定义，否则模型仍可能调用",
            request.toJson().has("tools"),
        )
    }

    @Test
    fun `允许工具时请求里带 tools 字段`() {
        val request = ChatRequest(
            model = "m",
            messages = listOf(ChatMessage.user("hi")),
            tools = listOf(ToolSpec("f", "d", JSONObject("""{"type":"object"}"""))),
        )

        val json = request.toJson()
        assertTrue(json.has("tools"))
        assertEquals(1, json.getJSONArray("tools").length())
        val tool = json.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("f", tool.getJSONObject("function").getString("name"))
    }

    @Test
    fun `工具结果消息带上 tool_call_id`() {
        val message = ChatMessage.toolResult("call_1", """{"ok":true}""")
        val json = message.toJson()

        assertEquals("tool", json.getString("role"))
        assertEquals("call_1", json.getString("tool_call_id"))
    }

    @Test
    fun `可选的生成参数不填就不发送`() {
        val json = ChatRequest(model = "m", messages = listOf(ChatMessage.user("x"))).toJson()

        assertFalse(json.has("temperature"))
        assertFalse(json.has("max_tokens"))
    }

    @Test
    fun `生成参数填了就发送`() {
        val json = ChatRequest(
            model = "m",
            messages = listOf(ChatMessage.user("x")),
            temperature = 0.7,
            maxTokens = 100,
        ).toJson()

        assertEquals(0.7, json.getDouble("temperature"), 0.001)
        assertEquals(100, json.getInt("max_tokens"))
    }

    // ---------------------------------------------------------------- 端点拼接

    /**
     * 各家给的 `baseUrl` 写法不同 —— 有的带斜杠、有的不带。
     * **不自动补 `/v1`**：DeepSeek 的正确路径就不带它。
     */
    @Test
    fun `端点拼接处理末尾斜杠`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            com.phonlynn.oreplan.domain.ai.AiClient.endpoint(
                "https://api.deepseek.com",
                com.phonlynn.oreplan.domain.ai.AiClient.CHAT_PATH,
            ),
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            com.phonlynn.oreplan.domain.ai.AiClient.endpoint(
                "https://api.deepseek.com/",
                com.phonlynn.oreplan.domain.ai.AiClient.CHAT_PATH,
            ),
        )
    }

    /** 带 `/v1` 的地址要原样保留（OpenAI 官方就是这个形状）。 */
    @Test
    fun `带 v1 的地址不被改写`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            com.phonlynn.oreplan.domain.ai.AiClient.endpoint(
                "https://api.openai.com/v1",
                com.phonlynn.oreplan.domain.ai.AiClient.CHAT_PATH,
            ),
        )
    }
}
