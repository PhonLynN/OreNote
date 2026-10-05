package com.phonlynn.oreplan.domain.ai.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Chat Completions 协议的**数据形状与解析**。
 *
 * ## 为什么这一层单独存在
 *
 * 用户要求「换 API 要简单」，而这件事能否成立**完全取决于这一层写对没有**。
 * 各家都声称"兼容 OpenAI 格式"，但在三个地方有细微差别（见下面各处 ⚠️）。
 * 把这些差别收在一个文件里，换厂商时只改配置、不改业务代码。
 *
 * ## 刻意的取舍
 *
 * 只实现**各家都有的子集**。DeepSeek 的 `strict` 模式、各家的 reasoning 参数
 * 都不做硬依赖 —— 它们能提升效果，但不能成为"换一家就崩"的原因。
 */

// ---------------------------------------------------------------- 消息

/**
 * 一条消息。
 *
 * 用 `JSONObject` 而不是强类型的 data class 来承载 role/name/arguments 之外的
 * 自由字段（如各家扩展的 `reasoning_content`）——
 * **原样带上比解析后丢掉更安全**：丢掉会让某些厂商的行为悄悄变化。
 */
data class ChatMessage(
    val role: String,
    val content: String?,
    /** 助手要求调用工具时非空。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** role = "tool" 时，对应哪个调用。 */
    val toolCallId: String? = null,
    /**
     * 思考内容，**原样回传**。
     *
     * ## ⚠️ 一旦请求里带 `tools`，这个字段就是必需的
     *
     * 官方文档明确写了：带 `tools` 时，历史里所有轮的 `reasoning_content`
     * 都必须原样回传，**否则服务端直接 400**。
     *
     * 所以它不能像 [SendChatMessage] 里那样一律丢掉 ——
     * 不带工具时可以丢（省 token、避免延续上一轮结论），
     * 带工具时**必须带上**。由调用方决定，见 `buildMessages(echoReasoning = …)`。
     */
    val reasoningContent: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content ?: JSONObject.NULL)
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply { toolCalls.forEach { put(it.toJson()) } })
        }
        toolCallId?.let { put("tool_call_id", it) }
        reasoningContent?.takeIf { it.isNotBlank() }?.let { put("reasoning_content", it) }
    }

    companion object {
        fun system(text: String) = ChatMessage("system", text)
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String?, reasoning: String? = null) =
            ChatMessage("assistant", text, reasoningContent = reasoning)
        fun toolResult(toolCallId: String, content: String) =
            ChatMessage("tool", content, toolCallId = toolCallId)
    }
}

/**
 * 模型要求的一次工具调用。
 *
 * ⚠️ **兼容点 ①：`arguments` 是字符串还是对象？**
 *
 * · OpenAI 规范：是 **JSON 字符串**（`"{\"city\":\"杭州\"}"`），要自己解析
 * · 部分厂商（尤其国内直连）：直接给 **JSON 对象**
 *
 * 这个差别会让代码在两个分支上各崩一次（字符串当对象读 → 异常；
 * 对象当字符串解析 → 异常）。所以 [fromJson] **两种都接受**。
 *
 * 保留 `argumentsRaw` 原样的字符串形式，是因为**回填给模型时必须原样送回** ——
 * 重新序列化可能改变键顺序，某些厂商会因此认为参数不一致。
 */
data class ToolCall(
    val id: String,
    val name: String,
    /** 解析后的参数对象。解析失败时是空对象（见 [argumentsParseFailed]）。 */
    val arguments: JSONObject,
    /** 参数解析是否失败。失败时不该执行工具，而应把错误回给模型让它重试。 */
    val argumentsParseFailed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                // 规范要求字符串形式 —— 回填时统一用字符串，兼容性最好
                put("arguments", arguments.toString())
            },
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): ToolCall {
            val fn = obj.optJSONObject("function") ?: JSONObject()
            val name = fn.optString("name")
            val id = obj.optString("id").ifBlank { "call_$name" }

            // ⚠️ 兼容点 ①：字符串 / 对象 / 缺失，三种都得处理
            val raw = fn.opt("arguments")
            return when (raw) {
                is JSONObject -> ToolCall(id, name, raw, argumentsParseFailed = false)
                is String -> {
                    if (raw.isBlank()) {
                        // 有些厂商对"无参数"工具给空串 —— 那是合法的空参数，不是解析失败
                        ToolCall(id, name, JSONObject(), argumentsParseFailed = false)
                    } else {
                        val parsed = runCatching { JSONObject(raw) }.getOrNull()
                        ToolCall(
                            id = id,
                            name = name,
                            arguments = parsed ?: JSONObject(),
                            argumentsParseFailed = parsed == null,
                        )
                    }
                }
                else -> ToolCall(id, name, JSONObject(), argumentsParseFailed = false)
            }
        }
    }
}

// ---------------------------------------------------------------- 请求

/** 工具定义（JSON Schema）。 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 的 `parameters` 部分。 */
    val parameters: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put(
            "function",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            },
        )
    }
}

/** 一次请求的全部参数。 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val frequencyPenalty: Double? = null,
    /** 流式输出。 */
    val stream: Boolean = true,
    /** 是否**允许**模型调用工具。false 时不发送 `tools`（见下）。 */
    val allowTools: Boolean = true,
    /**
     * 深度思考（思考模式）。
     *
     * ## 为什么是 `Boolean?` 而不是 `Boolean`
     *
     * `null` = **不发这个字段**，把决定权留给服务端默认值。
     * 连接测试这类"只探活"的请求用它。
     *
     * ## ⚠️ 关闭时必须**显式发 disabled**（这一条修过一次真实 bug）
     *
     * 我原来的实现是「只在打开时发 enabled，关闭时什么都不发」，
     * 理由是"让默认行为跟随服务端"。**这个理由是错的** ——
     * DeepSeek 的思考模式**服务端默认就是开的**（默认 effort = high）。
     * 于是两边的请求完全一样：打开 = 默认 = 开着，关闭 = 不发 = 还是开着。
     * 表现就是用户报的「深度思考按钮没效果」——拨到哪一边行为都不变。
     *
     * 所以这里 `false` 会真的发出 `{"thinking":{"type":"disabled"}}`。
     * 参考：https://api-docs.deepseek.com/guides/thinking_mode/
     *
     * ## 顺带两条从同一份文档读到的约束
     *
     * · 思考模式下 `temperature` / `frequency_penalty` **不生效**（不报错、被忽略），
     *   `top_p` 只在 0.95–1.0 之间有效。所以设置页那几个参数在开思考时是"拨了没用"的，
     *   这是厂商行为，不是我们可以修的。
     * · ⚠️ **`frequency_penalty` 现在整个被官方废弃了**：文档原文是
     *   「This parameter is no longer supported. It will not take effect if you pass it」。
     *   也就是说**不管开不开思考**它都不起作用。设置页那一行「重复惩罚」目前是个
     *   摆设（传了不报错、只是被忽略）。**要删这行得先问用户** ——
     *   删掉一个可见的设置项属于改界面，不在"可以自行决定"的范围内。
     * · 一旦请求里带 `tools`，**历史里所有轮的 `reasoning_content` 都必须原样回传**，
     *   否则服务端直接 400（这一条已经实现了，见 `SendChatMessage.buildMessages`）。
     */
    val deepThinking: Boolean? = null,
    /** 思考深度：low / high / max。同样只在开了思考时才有意义。 */
    val reasoningEffort: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        put("stream", stream)
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        topP?.let { put("top_p", it) }
        frequencyPenalty?.let { put("frequency_penalty", it) }

        /*
         * 思考模式：**两个方向都要显式发**。
         *
         * 只发 enabled 是不够的 —— 服务端默认就开着，那样"关"这个动作等于没做。
         * `null` 才是"别插嘴，按服务端默认来"。
         */
        when (deepThinking) {
            true -> {
                put("thinking", JSONObject().put("type", "enabled"))
                reasoningEffort?.let { put("reasoning_effort", it) }
            }
            false -> put("thinking", JSONObject().put("type", "disabled"))
            null -> Unit
        }

        /*
         * 聊天模式（allowTools = false）时**根本不发 `tools` 字段**。
         *
         * 这是刻意的：用户要求「聊天模式不碰数据」，而"发工具定义但希望模型别调"
         * 是不可靠的 —— 模型仍可能调。**不发 = 机制上不可能调**，
         * 而不是靠提示词约束。顺带省下 18 个工具 schema 的 token。
         */
        if (allowTools && tools.isNotEmpty()) {
            put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } })
        }
    }
}

// ---------------------------------------------------------------- 响应

/**
 * 一次请求的 **token 用量**（用户 2026-10-04 要的「token 消耗显示」）。
 *
 * ## 为什么做成独立类型而不是三个 Int
 *
 * 三者是**一组**（输入 / 输出 / 合计），而且"有没有拿到"本身是一个信息
 *（`null` = 这家厂商没返回，界面就**不显示**那一行，而不是显示 0）。
 * 拆成三个可空 Int 会让"三个都空"与"部分空"变得难以表达。
 *
 * ## 字段名各家一致
 *
 * `prompt_tokens` / `completion_tokens` / `total_tokens` 是 OpenAI 兼容协议的
 * 标准名，DeepSeek、Qwen、OpenAI 都这么发。所以这里不做多字段名兜底
 *（真遇到不兼容的再加，不要现在就猜）。
 */
data class Usage(
    /** 输入（提示词 + 历史 + 工具定义）。 */
    val promptTokens: Int = 0,
    /** 输出（正文 + 思考 + 工具调用参数）。 */
    val completionTokens: Int = 0,
    /**
     * 合计。
     *
     * ⚠️ 有些厂商**不发这个字段**，所以 [from] 里会在它缺失时用
     * `prompt + completion` 兜底 —— 界面只读这一个值。
     */
    val totalTokens: Int = 0,
) {
    /** 界面用：`a,bcd tokens`（用户指定的格式）。 */
    val label: String get() = "${totalTokens.withThousandsSeparator()} tokens"

    companion object {
        fun from(json: JSONObject): Usage? {
            val prompt = json.optInt("prompt_tokens", 0)
            val completion = json.optInt("completion_tokens", 0)
            val total = json.optInt("total_tokens", 0)

            // 三个都读不到 —— 这不是"用量为 0"，而是"这家没给"，返回 null
            if (prompt == 0 && completion == 0 && total == 0) return null

            return Usage(
                promptTokens = prompt,
                completionTokens = completion,
                // 厂商没给 total 就自己加（它们本来就该相等）
                totalTokens = if (total > 0) total else prompt + completion,
            )
        }

        /**
         * 千位分隔（`12345` → `12,345`）。
         *
         * 用户指定的格式是 `a,bcd tokens` —— 带千位分隔符。
         * 不引 `String.format` 的 locale 版本：某些地区会用空格或逗号做小数分隔，
         * 而这里**要的就是逗号**，所以手写。
         */
        private fun Int.withThousandsSeparator(): String {
            val digits = toString()
            if (digits.length <= 3) return digits
            return digits.reversed()
                .chunked(3)
                .joinToString(",")
                .reversed()
        }
    }
}

/** 解析一次（非流式）响应。 */
data class ChatResponse(
    val content: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    /** 思考模式下的推理内容（有就给，没有就是 null）。 */
    val reasoning: String? = null,
    /** token 用量；`null` = 这家厂商没返回。 */
    val usage: Usage? = null,
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * 响应解析。
 *
 * ⚠️ **兼容点 ③：`finish_reason` 取值不一致。**
 *
 * 规范是 `stop` / `length` / `tool_calls`；
 * 早期格式与部分厂商用 `function_call`；也有厂商给中文或省略。
 * 所以 [ChatResponse.wantsTools] **不看 finish_reason，只以 toolCalls 是否为空为准** ——
 * 那才是真正决定"要不要执行工具"的信息，而它各家的形状是一致的。
 */
object ChatProtocol {

    fun parseResponse(body: String): ChatResponse? = runCatching {
        val root = JSONObject(body)
        // 有些厂商在 HTTP 200 里返回 error 对象
        if (root.has("error")) return@runCatching null

        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return@runCatching null
        val message = choice.optJSONObject("message") ?: JSONObject()

        ChatResponse(
            content = message.optString("content").takeIf { it.isNotBlank() && it != "null" },
            toolCalls = parseToolCalls(message.optJSONArray("tool_calls")),
            finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() },
            // 各家字段名不同，按可能性依次尝试；都没有就是 null
            reasoning = sequenceOf("reasoning_content", "reasoning", "thinking")
                .map { message.optString(it) }
                .firstOrNull { it.isNotBlank() },
            // 非流式响应里 `usage` 与 `choices` 平级（不在 choice 里）
            usage = root.optJSONObject("usage")?.let { Usage.from(it) },
        )
    }.getOrNull()

    /** 从错误响应体里提取可读原因（各家形状不同，尽力而为）。 */
    fun parseErrorMessage(body: String): String? = runCatching {
        val root = JSONObject(body)
        val err = root.optJSONObject("error") ?: return@runCatching null
        err.optString("message").takeIf { it.isNotBlank() }
            ?: err.optString("msg").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseToolCalls(array: JSONArray?): List<ToolCall> {
        if (array == null) return emptyList()
        val out = ArrayList<ToolCall>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out += ToolCall.fromJson(it) }
        }
        return out
    }
}
