package com.phonlynn.oreplan.domain.ai

import com.phonlynn.oreplan.domain.ai.protocol.ToolCall
import com.phonlynn.oreplan.domain.ai.tool.AiTool
import com.phonlynn.oreplan.domain.ai.tool.ChangeField
import com.phonlynn.oreplan.domain.ai.tool.ChangeOperation
import com.phonlynn.oreplan.domain.ai.tool.ChangeRecord
import com.phonlynn.oreplan.domain.ai.tool.ConfirmableTool
import com.phonlynn.oreplan.domain.ai.tool.ToolDanger
import com.phonlynn.oreplan.domain.ai.tool.ToolDetail
import com.phonlynn.oreplan.domain.ai.tool.ToolOutcome
import com.phonlynn.oreplan.domain.ai.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent loop。
 *
 * ## 为什么这一段必须测
 *
 * 它是"助手模式"的全部风险所在，而且**三类错误都只在真机上暴露**：
 *
 * · 少回传 `reasoning_content` → 服务端 **400**（带 tools 时的硬要求）
 * · 少回传 `role="tool"` 的结果 → 服务端报"tool 消息与调用不匹配"
 * · 循环不收敛 → 无限烧钱
 *
 * 而这三条全是纯协议问题，**不需要联网也不需要 API Key** 就能钉死 ——
 * `AiClient` 的 `transport` 是可替换的，这里注入一个假的把请求体抓下来。
 */
class AgentLoopTest {

    // ---------------------------------------------------------------- 假件

    /** 抓下每次请求的 body，并按顺序吐出预置的 SSE 流。 */
    private class FakeTransport(private val scripts: List<List<String>>) : AiClient.Transport {
        val requests = ArrayList<String>()
        private var index = 0

        override suspend fun postJson(
            url: String,
            apiKey: String,
            body: String,
            stream: Boolean,
            onLine: (suspend (String) -> Unit)?,
        ): AiClient.RawResponse {
            requests += body
            val lines = scripts.getOrElse(index) { emptyList() }
            index++
            lines.forEach { onLine?.invoke(it) }
            return AiClient.RawResponse(200, "")
        }
    }

    /** 一个只读的假工具，把收到的参数原样回显，方便断言"结果确实回给了模型"。 */
    private class EchoTool : AiTool {
        var calls = 0
        override val name = "echo"
        override val displayName = "回声"
        override val description = "测试用"
        override val danger = ToolDanger.READ
        override val parameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject())
        }

        override suspend fun run(args: JSONObject): ToolOutcome {
            calls++
            return ToolOutcome(
                forModel = "回声：${args.optString("q")}",
                forUser = ToolDetail(arguments = args.optString("q"), result = "ok"),
            )
        }
    }

    private fun line(json: String) = "data: $json"

    private fun textChunk(text: String) =
        line("""{"choices":[{"delta":{"content":"$text"}}]}""")

    private fun reasoningChunk(text: String) =
        line("""{"choices":[{"delta":{"reasoning_content":"$text"}}]}""")

    private fun toolCallChunk(name: String, argsJson: String) = listOf(
        line("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"$name"}}]}}]}"""),
        line(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":${JSONObject.quote(argsJson)}}}]}}]}""",
        ),
    )

    private val config = AiProviderConfig(
        id = "p1",
        baseUrl = "https://example.invalid",
        apiKey = "k",
        model = "m",
        supportsTools = true,
    )

    private val settings = AiSettings(enableTools = true, streamOutput = true)

    private fun newTurn(content: String) = ChatTurn(
        conversationId = "c1",
        role = ChatTurn.Role.USER,
        content = content,
        createdAt = 1L,
    )

    // ---------------------------------------------------------------- 用例

    /**
     * 主干：模型先要求调工具，拿到结果后再给最终回答。
     *
     * 走完应当是**两次请求**，且第二次请求里带着第一次的工具结果。
     */
    @Test
    fun `工具调用一轮后给出最终回答`() = runBlocking {
        val tool = EchoTool()
        val transport = FakeTransport(
            listOf(
                // 第一轮：先推理，再要求调 echo
                buildList {
                    add(reasoningChunk("需要查一下"))
                    addAll(toolCallChunk("echo", """{"q":"明天"}"""))
                },
                // 第二轮：拿到结果后收尾
                listOf(textChunk("明天没有安排")),
            ),
        )
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(tool)))

        val result = sender(config, settings, listOf(newTurn("明天有事吗"))).getOrThrow()

        assertEquals("工具应当被执行一次", 1, tool.calls)
        assertEquals("应当发了两次请求", 2, transport.requests.size)

        // 第二次请求里必须能看到第一轮的工具结果
        val second = transport.requests[1]
        assertTrue("缺少 role=tool 的结果消息：$second", second.contains("\"role\":\"tool\""))
        assertTrue("结果正文没回传：$second", second.contains("回声：明天"))

        /*
         * `tool_call_id` 必须与 assistant 消息里 `tool_calls[].id` **完全一致** ——
         * 这是服务端配对调用与结果的唯一依据。
         *
         * 这里断言"两处一致"而不是写死某个字符串：id 可能来自服务端
         *（分片里带的），也可能是我们自己编的，两种都合法，一致才重要。
         */
        val callId = result.toolCalls[0].id
        assertTrue("结果消息里的 tool_call_id 不对：$second", second.contains("\"tool_call_id\":\"$callId\""))
        assertTrue("assistant 消息里的 id 与结果对不上：$second", second.contains("\"id\":\"$callId\""))

        // 最终回答与轮次结构
        assertEquals("明天没有安排", result.content)
        assertEquals(2, result.rounds.size)
        assertEquals("轮次里要记下它调了哪个工具", 1, result.rounds[0].callIds.size)
        assertEquals(result.toolCalls[0].id, result.rounds[0].callIds[0])
        assertEquals(1, result.toolCalls.size)
        assertEquals(ToolCallRecord.Status.DONE, result.toolCalls[0].status)
        assertTrue("工具结果要留存给以后回放", !result.toolCalls[0].resultForModel.isNullOrBlank())
    }

    /**
     * ⚠️ **带 tools 时必须回传 `reasoning_content`** —— 这是 400 的头号来源。
     *
     * 规则：带 tools 的请求里，历史中每条 assistant 消息都要带它；
     * 不带 tools 时反过来要丢掉（省 token、避免延续上一轮结论）。
     */
    @Test
    fun `带工具时回传推理内容不带工具时丢掉`() = runBlocking {
        val prior = listOf(
            newTurn("问题"),
            ChatTurn(
                conversationId = "c1",
                role = ChatTurn.Role.ASSISTANT,
                content = "上一次的回答",
                reasoning = "上一次的推理过程",
                createdAt = 2L,
            ),
        )

        // 带工具：必须出现
        run {
            val transport = FakeTransport(listOf(listOf(textChunk("好"))))
            val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))
            sender(config, settings, prior).getOrThrow()
            assertTrue(
                "带 tools 时必须回传 reasoning_content，否则服务端 400：${transport.requests[0]}",
                transport.requests[0].contains("reasoning_content"),
            )
            assertTrue(transport.requests[0].contains("上一次的推理过程"))
            assertTrue("必须真的带上了 tools", transport.requests[0].contains("\"tools\""))
        }

        // 不带工具（供应商不支持）：必须丢掉
        run {
            val transport = FakeTransport(listOf(listOf(textChunk("好"))))
            val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))
            sender(config.copy(supportsTools = false), settings, prior).getOrThrow()
            assertFalse(
                "不带 tools 时不该回传推理内容：${transport.requests[0]}",
                transport.requests[0].contains("reasoning_content"),
            )
            assertFalse(transport.requests[0].contains("\"tools\""))
        }
    }

    /**
     * **聊天模式必须机制上不可能调工具** —— 不发 `tools` 字段，
     * 而不是"发了但希望模型别调"。
     */
    @Test
    fun `聊天模式不发 tools 字段`() = runBlocking {
        val transport = FakeTransport(listOf(listOf(textChunk("闲聊"))))
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))

        sender(config, settings.copy(enableTools = false), listOf(newTurn("你好"))).getOrThrow()

        assertFalse(
            "关掉工具开关后请求里不该有 tools：${transport.requests[0]}",
            transport.requests[0].contains("\"tools\""),
        )
    }

    /** 工具执行失败要**转成文字回给模型**，而不是让整轮以错误结束。 */
    @Test
    fun `工具失败不抛出而是回给模型`() = runBlocking {
        val failing = object : AiTool {
            override val name = "boom"
            override val displayName = "会炸的工具"
            override val description = "测试用"
            override val danger = ToolDanger.READ
            override val parameters = JSONObject().apply { put("type", "object") }
            override suspend fun run(args: JSONObject) = error("内部错误")
        }
        val transport = FakeTransport(
            listOf(
                toolCallChunk("boom", "{}"),
                listOf(textChunk("我遇到问题了")),
            ),
        )
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(failing)))

        val result = sender(config, settings, listOf(newTurn("试试"))).getOrThrow()

        assertEquals("我遇到问题了", result.content)
        assertEquals(ToolCallRecord.Status.FAILED, result.toolCalls[0].status)
        assertTrue(
            "失败原因要回给模型，它才能改参数重试：${transport.requests[1]}",
            transport.requests[1].contains("执行失败"),
        )
    }

    /** 模型要调一个不存在的工具时，把**可用工具列表**回给它。 */
    @Test
    fun `未知工具会把可用列表回给模型`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                toolCallChunk("nonexistent", "{}"),
                listOf(textChunk("好的")),
            ),
        )
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))

        sender(config, settings, listOf(newTurn("试试"))).getOrThrow()

        assertTrue(
            "要告诉模型有哪些工具可用：${transport.requests[1]}",
            transport.requests[1].contains("echo"),
        )
    }

    /**
     * 模型可能陷进「一直调工具」的循环，**必须有上限**。
     *
     * 到上限时不该抛错，而是把已有内容正常返回。
     */
    @Test
    fun `无限调工具会被轮次上限截断`() = runBlocking {
        // 每次都要求调工具，永不收敛
        val transport = FakeTransport(List(50) { toolCallChunk("echo", """{"q":"x"}""") })
        val tool = EchoTool()
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(tool)))

        sender(config, settings, listOf(newTurn("试试"))).getOrThrow()

        assertEquals(
            "请求次数必须被 MAX_TOOL_ROUNDS 卡住",
            SendChatMessage.MAX_TOOL_ROUNDS,
            transport.requests.size,
        )
        assertEquals(SendChatMessage.MAX_TOOL_ROUNDS, tool.calls)
    }

    /** 一条工具调用记录要能原样还原成协议调用 —— 回放历史的正确性靠它。 */
    @Test
    fun `工具记录能还原成协议调用`() {
        val call = ToolCall(
            id = "call_9",
            name = "create_item",
            arguments = JSONObject().put("title", "复习"),
        )
        val record = ToolCallRecord.from(call, "创建日程", "title 复习")
        val restored = record.toProtocolCall()

        assertEquals(call.id, restored.id)
        assertEquals(call.name, restored.name)
        assertEquals("复习", restored.arguments.getString("title"))
    }

    // ---------------------------------------------------------------- 写操作挂起

    /** 一个写工具：`preview` 只算不写，`apply` 才真写。 */
    private class WriteTool : ConfirmableTool {
        var previews = 0
        var applies = 0
        override val name = "create_thing"
        override val displayName = "创建东西"
        override val description = "测试用"
        override val parameters = JSONObject().apply { put("type", "object") }
        override val danger get() = ToolDanger.WRITE

        override suspend fun run(args: JSONObject): ToolOutcome =
            ToolOutcome(forModel = "不该被直接调用")

        override suspend fun preview(args: JSONObject): List<ChangeRecord> {
            previews++
            return listOf(
                ChangeRecord(
                    id = "r1",
                    typeLabel = "日程",
                    operation = ChangeOperation.CREATE,
                    fields = listOf(ChangeField("标题", null, args.optString("title"))),
                ),
            )
        }

        override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
            applies++
            return ToolOutcome(forModel = "已创建")
        }
    }

    /**
     * ⚠️ **写操作必须挂起，不能执行。**
     *
     * 用户口径：「确认必须发生在执行之前」。
     *
     * 而且**不能带着不完整的 `tool_calls` 组再发一次请求** ——
     * 那条 assistant 消息声明了调用，却没有对应的 `role="tool"` 结果，
     * 服务端会直接拒掉。所以循环必须在发下一次请求**之前**停下来。
     */
    @Test
    fun `写操作挂起且不再发请求`() = runBlocking {
        val write = WriteTool()
        val transport = FakeTransport(
            listOf(
                toolCallChunk("create_thing", """{"title":"复习"}"""),
                // 万一循环没停，这里会给出第二轮回答 —— 断言请求数就能发现
                listOf(textChunk("不该走到这里")),
            ),
        )
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(write)))

        val result = sender(config, settings, listOf(newTurn("建一条"))).getOrThrow()

        assertEquals("只该发一次请求（挂起后就停）", 1, transport.requests.size)
        assertEquals("写工具不该被执行", 0, write.applies)
        assertEquals(1, result.toolCalls.size)
        assertEquals(
            "必须记成待确认，界面才知道要弹确认卡",
            ToolCallRecord.Status.PENDING,
            result.toolCalls[0].status,
        )

        // 卡片的「调用参数」要有内容，且预览能按参数重算出来
        assertTrue(result.toolCalls[0].argumentsSummary.isNotBlank())
        val argsJson = requireNotNull(result.toolCalls[0].argumentsJson) { "原始参数必须留存" }
        assertEquals("复习", JSONObject(argsJson).optString("title"))
        assertEquals(1, write.preview(JSONObject(argsJson)).size)
    }

    /**
     * 待确认的记录**回放时要补一条占位结果**。
     *
     * 用户可能把对话放很久才回来处理。此时若把这条不完整的历史发出去，
     * 服务端会因为"声明了调用却没有结果"而报错 —— 所以占位是必需的，
     * 而且措辞要说清是"还没确认"，不能让模型以为工具坏了。
     */
    @Test
    fun `待确认的记录回放时有占位结果`() = runBlocking {
        val pendingTurn = ChatTurn(
            conversationId = "c1",
            role = ChatTurn.Role.ASSISTANT,
            content = null,
            toolCalls = listOf(
                ToolCallRecord(
                    id = "call_w",
                    name = "create_thing",
                    displayName = "创建东西",
                    argumentsSummary = "title 复习",
                    argumentsJson = """{"title":"复习"}""",
                    status = ToolCallRecord.Status.PENDING,
                ),
            ),
            rounds = listOf(TurnRound(callIds = listOf("call_w"))),
            createdAt = 2L,
        )

        val transport = FakeTransport(listOf(listOf(textChunk("好"))))
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(WriteTool())))

        // 用户没处理那条待确认，直接又发了一句 —— 历史里就带着 PENDING 记录
        sender(config, settings, listOf(newTurn("在吗"), pendingTurn)).getOrThrow()

        val body = transport.requests[0]
        assertTrue("必须补上 role=tool 的占位结果：$body", body.contains("\"role\":\"tool\""))
        assertTrue("占位措辞要说清是还没确认：$body", body.contains("还没有确认"))
        assertTrue("配对的 tool_call_id 不能少：$body", body.contains("\"tool_call_id\":\"call_w\""))
    }

    /**
     * **用户确认之后**的历史回放：协议结构必须完整且配对。
     *
     * ## 这是整个"挂起/恢复"里最容易出错的一步
     *
     * 恢复的做法是"把结果补进记录、再发一次请求"。那次请求里的历史必须长成：
     *
     * ```
     * assistant  tool_calls:[{id: call_w, ...}]   ← 当初发起调用的那条，即使 content 为空也要发
     * tool       tool_call_id: call_w             ← 结果，id 必须**一模一样**
     * ```
     *
     * 少一条、或者 id 对不上，服务端都会直接拒掉整次请求。
     * 而这条路径在真机上要走"模型调写工具 → 用户确认 → 继续"才会经过，
     * 平时根本碰不到 —— 所以必须在这里钉死。
     */
    @Test
    fun `确认之后的历史回放协议完整`() = runBlocking {
        val resolvedTurn = ChatTurn(
            conversationId = "c1",
            role = ChatTurn.Role.ASSISTANT,
            content = null,
            toolCalls = listOf(
                ToolCallRecord(
                    id = "call_w",
                    name = "create_thing",
                    displayName = "创建东西",
                    argumentsSummary = "title 复习",
                    argumentsJson = """{"title":"复习"}""",
                    // 用户已经处理过了
                    status = ToolCallRecord.Status.DONE,
                    resultForModel = "已创建日程：复习",
                    resultSummary = "已写入日程",
                ),
            ),
            rounds = listOf(TurnRound(callIds = listOf("call_w"))),
            createdAt = 2L,
        )

        val transport = FakeTransport(listOf(listOf(textChunk("好的"))))
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(WriteTool())))
        sender(config, settings, listOf(newTurn("建一条"), resolvedTurn)).getOrThrow()

        val body = transport.requests[0]

        // ① 发起调用的那条 assistant 消息必须在，且带着 tool_calls
        assertTrue("缺少带 tool_calls 的 assistant 消息：$body", body.contains("\"tool_calls\""))
        assertTrue("tool_calls 里缺少调用 id：$body", body.contains("\"id\":\"call_w\""))

        // ② 结果消息必须在，且 id 与调用**完全一致**
        assertTrue("缺少 role=tool 的结果：$body", body.contains("\"role\":\"tool\""))
        assertTrue("tool_call_id 与调用对不上：$body", body.contains("\"tool_call_id\":\"call_w\""))
        assertTrue("结果正文丢了：$body", body.contains("已创建日程：复习"))

        // ③ 顺序：assistant(tool_calls) 必须**在** tool 结果之前
        val callAt = body.indexOf("\"tool_calls\"")
        val resultAt = body.indexOf("\"role\":\"tool\"")
        assertTrue("顺序反了：结果出现在调用之前", callAt in 0 until resultAt)
    }

    /**
     * 并行工具调用必须**合并回一条** assistant 消息。
     *
     * 当初是这么发出去的（一条 assistant 带两个 tool_calls），回放时拆成两条
     * 会被判为与 `tool` 结果不匹配。
     */
    @Test
    fun `并行调用回放时合并成一条`() = runBlocking {
        val twoCalls = ChatTurn(
            conversationId = "c1",
            role = ChatTurn.Role.ASSISTANT,
            content = null,
            toolCalls = listOf("call_a", "call_b").map { id ->
                ToolCallRecord(
                    id = id,
                    name = "echo",
                    displayName = "回声",
                    argumentsSummary = "q $id",
                    argumentsJson = """{"q":"$id"}""",
                    status = ToolCallRecord.Status.DONE,
                    resultForModel = "回声：$id",
                )
            },
            // 一轮里两个调用 → 同一条 rounds 条目
            rounds = listOf(TurnRound(callIds = listOf("call_a", "call_b"))),
            createdAt = 2L,
        )

        val transport = FakeTransport(listOf(listOf(textChunk("好"))))
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))
        sender(config, settings, listOf(newTurn("都查一下"), twoCalls)).getOrThrow()

        val body = transport.requests[0]
        // 两个 id 必须在**同一个** tool_calls 数组里
        val firstCall = body.indexOf("\"tool_calls\"")
        val secondCall = body.indexOf("\"tool_calls\"", firstCall + 1)
        assertEquals("两个并行调用被拆成了两条 assistant 消息", -1, secondCall)
    }

    /**
     * **聊天模式（`toolsEnabled = false`）请求里不能出现 `tools` 字段。**
     *
     * ## 为什么必须是"不发"而不是"发了但叮嘱它别调"
     *
     * 用户要求「聊天模式不碰数据」。靠提示词约束是**靠模型自觉** ——
     * 模型不听话的那一刻，用户的数据已经被改了。所以这条保证必须落在机制上：
     * 请求里没有工具定义，模型物理上无法要求调用。
     *
     * 这条也顺带守住了默认值：`ChatMode.CHAT` 是默认模式，
     * 所以"默认安全的那一侧"就是这里断言的这一侧。
     */
    @Test
    fun `聊天模式请求里没有工具定义`() = runBlocking {
        val transport = FakeTransport(listOf(listOf(textChunk("你好"))))
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))
        sender(config, settings, listOf(newTurn("你好")), toolsEnabled = false).getOrThrow()

        val body = transport.requests[0]
        assertFalse("聊天模式绝不能带 tools：$body", body.contains("\"tools\""))
    }

    /** 助手模式则**必须**带上，否则工具永远调不起来。 */
    @Test
    fun `助手模式请求里带上工具定义`() = runBlocking {
        val transport = FakeTransport(listOf(listOf(textChunk("你好"))))
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))
        sender(config, settings, listOf(newTurn("你好")), toolsEnabled = true).getOrThrow()

        val body = transport.requests[0]
        assertTrue("助手模式必须带 tools：$body", body.contains("\"tools\""))
        assertTrue("工具名要在定义里：$body", body.contains("\"echo\""))
    }

    /** 厂商声明不支持工具时，即使开着助手模式也不发 —— 发了会被直接拒。 */
    @Test
    fun `厂商不支持工具时不发`() = runBlocking {
        val transport = FakeTransport(listOf(listOf(textChunk("你好"))))
        val sender = SendChatMessage(AiClient(transport), ToolRegistry(setOf(EchoTool())))
        val noTools = config.copy(supportsTools = false)
        sender(noTools, settings, listOf(newTurn("你好")), toolsEnabled = true).getOrThrow()

        assertFalse(transport.requests[0].contains("\"tools\""))
    }
}
