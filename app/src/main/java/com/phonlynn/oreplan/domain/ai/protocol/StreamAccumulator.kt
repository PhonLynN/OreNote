package com.phonlynn.oreplan.domain.ai.protocol

import org.json.JSONObject

/**
 * SSE 流式响应的解析。
 *
 * ## 为什么流式要单独处理
 *
 * 非流式拿到的是一整个 JSON；流式拿到的是若干行 `data: {...}`，而且
 * **工具调用的参数是分片到达的**：
 *
 * ```
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"create_item"}}]}}]}
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tit"}}]}}]}
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"le\":\"复"}}]}}]}
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"习\"}"}}]}}]}
 * ```
 *
 * 要按 `index` 分组、把 `name` 与 `arguments` 各自累加，最后才是一个完整的调用。
 *
 * ⚠️ **兼容点 ②：首片的 `index` 不一定给。**
 *
 * 规范要求每片都带 `index`，但有些厂商只在第一片给。所以本类**记住见过的最大 index**，
 * 缺失时归到"当前正在拼的那一个" —— 否则会把一次调用拆成多个，参数自然拼不出来。
 */
class StreamAccumulator {

    /** 按 index 累积的分片。 */
    private val pending = linkedMapOf<Int, PartialCall>()
    /** 上一次见到的 index（用于兼容"缺失 index"的厂商）。 */
    private var lastIndex = 0

    private val contentBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()
    var finishReason: String? = null
        private set

    /**
     * 本次请求的 **token 用量**（用户 2026-10-04 要的「token 消耗显示」）。
     *
     * ## 流式响应里的 `usage` 只在**最后一片**给
     *
     * OpenAI 兼容协议在流式模式下把用量放在最后那个 chunk 里（有些厂商每片都给、
     * 但值在最后才完整）。所以这里**后到的覆盖先到的** —— 只在有值时更新，
     * 不把之前拿到的清掉。
     *
     * ## 字段名各家略有差异
     *
     * 主字段 `prompt_tokens` / `completion_tokens` / `total_tokens` 各家的形状一致；
     * 本类只取 [Usage.totalTokens]，缺失时**回落到 prompt + completion** ——
     * 有些厂商不发 total。
     */
    var usage: Usage? = null
        private set

    /**
     * 按 index 累积的分片。
     *
     * `name` 也用 `StringBuilder`：规范里函数名只在首片给一次，
     * 但**有厂商会把它切开发两次**（尤其中文的函数名）。用可变缓冲区分片累加，
     * 两种行为都能正确处理。
     */
    private class PartialCall {
        /**
         * 服务端给的调用 id。
         *
         * ⚠️ **只在第一片里出现一次**（后面的分片不带），所以必须逐片累积时记住，
         * 不能等到最后再读 —— 那时它已经不在了。
         *
         * 拿不到时退回 `call_{index}_{name}`：这个 id 是我们自己编的，
         * 但**发起与回填两处用的是同一个值**，所以协议上是自洽的。
         * 能拿到服务端的 id 时优先用它 —— 那才是服务端认识的那一个。
         */
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    /** 是否收到过任何内容（用于判断空响应）。 */
    var sawAnything: Boolean = false
        private set

    /**
     * 喂入一行 SSE。
     *
     * @return 本次新增的**增量**（正文与推理各自一段）；没有增量时返回 [StreamDelta.EMPTY]。
     */
    fun feed(line: String): StreamDelta {
        if (!line.startsWith(DATA_PREFIX)) return StreamDelta.EMPTY
        val payload = line.removePrefix(DATA_PREFIX).trim()

        // `data: [DONE]` 是结束标记，不是 JSON
        if (payload.isEmpty() || payload == "[DONE]") return StreamDelta.EMPTY

        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return StreamDelta.EMPTY
        sawAnything = true

        /*
         * 用量：**每一片都看一眼**（不 return 掉）。
         *
         * ⚠️ 必须放在 `choices` 那行**之前** —— 有些厂商在最后一片里
         * 只给 `usage` 而 **`choices` 是空数组**（`[DONE]` 之前那一片），
         * 放到后面就会被 `?: return` 提前跳过，用量永远读不到。
         */
        json.optJSONObject("usage")?.let { usage = Usage.from(it) }

        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return StreamDelta.EMPTY
        choice.optString("finish_reason").takeIf { it.isNotBlank() }?.let { finishReason = it }

        val delta = choice.optJSONObject("delta") ?: return StreamDelta.EMPTY

        // 推理内容（思考模式）—— 各家字段名不同
        //
        // ⚠️ 必须同时排除字面量 `"null"`：`optString` 对 JSON null 返回字符串 `"null"`。
        // 不排除的话，那些 null 会被**拼进推理内容末尾** ——
        // 真机上表现为「思考内容结尾一大串 null」（用户反馈过）。
        //
        // ⚠️ **增量要立刻交出去**，不能攒到流结束再一次性给：
        // 思考往往在正文之前产生，一次性给等于"思考过程整段延迟到回答之后才出现"，
        // 用户会觉得思考没有流式效果（用户反馈过）。
        //
        // ⚠️ 这里同样**只能是 `isNotEmpty`** —— 思考内容里换行也会单独成片，
        // 用 `isNotBlank` 会把它们丢掉（与正文那条是同一个缺陷，见下面的长注释）。
        var reasoningDelta = ""
        sequenceOf("reasoning_content", "reasoning").forEach { key ->
            delta.optString(key)
                .takeIf { it.isNotEmpty() && it != "null" }
                ?.let {
                    reasoningBuilder.append(it)
                    reasoningDelta += it
                }
        }

        // 工具调用的分片
        delta.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                accumulate(array.optJSONObject(i) ?: continue)
            }
        }

        /*
         * 正文增量。
         *
         * ⚠️⚠️ **这里只能用 `isNotEmpty`，绝不能用 `isNotBlank`**（用户 2026-10-04 的崩溃级缺陷）。
         *
         * ## 症状
         *
         * 真机上渲染成一片粘连：
         *
         * ```
         * ##它想回答什么问题给定一堆流体——水、空气——在某时刻的状态…
         * -左边：流体微团受到的加速度（惯性项）
         * - $\rho$：密度- $p$：压强- $\mu$：动力粘度
         * ```
         *
         * 而同一段回复在 DeepSeek App 里是正常的 —— 因为**它收到的原文是完整的**，
         * 标准 Markdown 解析器（remark-gfm）直接就能渲染好。
         *
         * ## 根因：**只含空白的分片被整片丢掉了**
         *
         * SSE 流里**换行经常单独成片**（模型在每个块之间吐一个 `"\n\n"`）。
         * 而 `"\n\n".isNotBlank()` 是 **false** —— 于是这些换行片掉进 else 分支被丢弃，
         * 前后两行**直接拼接**，块结构就此消失。
         *
         * 丢掉之后就**再也补不回来**了：`##它想回答什么问题给定一堆流体`
         * 里"标题到哪结束"这个信息在字符串中根本不存在，
         * 任何渲染器都只能猜 —— 这正是之前几轮"在渲染层加启发式"全部失败的根因。
         *
         * ## `isNotBlank` 在这里是**用错了**
         *
         * 它唯一需要的职责是挡 JSON 的 `null`（`optString` 会把 JSON null
         * 变成字面量字符串 `"null"`，历史上真机出现过"回复结尾一大串 null"）。
         * 挡 null 用 `isNotEmpty` 就够了 —— 空白是**合法内容**，不是空内容。
         *
         * 守住这一条的用例：[com.phonlynn.oreplan.domain.ai.protocol.StreamWhitespaceTest]。
         */
        val text = delta.optString("content")
        val contentDelta = if (text.isNotEmpty() && text != "null") {
            contentBuilder.append(text)
            text
        } else {
            ""
        }

        return StreamDelta(content = contentDelta, reasoning = reasoningDelta)
    }

    private fun accumulate(obj: JSONObject) {
        // ⚠️ 兼容点 ②：index 缺失时沿用上一次的
        val index = if (obj.has("index")) obj.optInt("index") else lastIndex
        lastIndex = index

        val partial = pending.getOrPut(index) { PartialCall() }

        // 服务端 id：只在前几片出现，拿到就记下来（见 PartialCall.id 的注释）
        obj.optString("id").takeIf { it.isNotBlank() }?.let {
            partial.id.clear()
            partial.id.append(it)
        }

        val fn = obj.optJSONObject("function") ?: return
        fn.optString("name").takeIf { it.isNotBlank() }?.let { partial.name.append(it) }
        fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { partial.arguments.append(it) }
    }

    /** 流结束后，组装出完整的响应。 */
    fun build(): ChatResponse {
        val calls = pending.entries.sortedBy { it.key }.mapNotNull { (index, p) ->
            val name = p.name.toString()
            if (name.isBlank()) return@mapNotNull null
            val raw = p.arguments.toString()
            val parsed = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrNull()
            ToolCall(
                // 有服务端的 id 就用它，没有才自己编
                id = p.id.toString().takeIf { it.isNotBlank() } ?: "call_${index}_$name",
                name = name,
                arguments = parsed ?: JSONObject(),
                argumentsParseFailed = raw.isNotBlank() && parsed == null,
            )
        }
        return ChatResponse(
            /*
             * ⚠️ **`takeIf { isNotEmpty() }` 而不是 `isNotBlank()`** —— 与 `feed()` 里同一件事。
             *
             * 这里如果按"是不是空白"判断，**整条回复只有空白**时会被当成"没有内容"
             * （退回 null），下游就会显示成空回复。空白是合法正文，不是"没有正文"。
             *
             * `reasoning` 同理：思考内容常常以换行结尾，`isNotBlank` 虽然不会因此
             * 丢掉整段，但保持两处口径一致，免得以后有人照着改坏。
             */
            content = contentBuilder.toString().takeIf { it.isNotEmpty() },
            toolCalls = calls,
            finishReason = finishReason,
            reasoning = reasoningBuilder.toString().takeIf { it.isNotEmpty() },
            usage = usage,
        )
    }

    private companion object {
        const val DATA_PREFIX = "data:"
    }
}

/**
 * 一次 [StreamAccumulator.feed] 产生的增量。
 *
 * 正文与推理**分开**：两者的渲染位置不同（推理进折叠块、正文进气泡），
 * 而且推理常常在正文之前就开始到达 —— 合成一个字符串就没法分别显示。
 */
data class StreamDelta(
    val content: String = "",
    val reasoning: String = "",
) {
    val isEmpty: Boolean get() = content.isEmpty() && reasoning.isEmpty()

    companion object {
        val EMPTY = StreamDelta()
    }
}
