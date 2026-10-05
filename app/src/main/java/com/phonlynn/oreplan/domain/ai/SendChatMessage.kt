package com.phonlynn.oreplan.domain.ai

import com.phonlynn.oreplan.domain.ai.protocol.ChatMessage
import com.phonlynn.oreplan.domain.ai.protocol.ChatRequest
import com.phonlynn.oreplan.domain.ai.protocol.ToolCall
import com.phonlynn.oreplan.domain.ai.tool.AiTool
import com.phonlynn.oreplan.domain.ai.tool.AskingTool
import com.phonlynn.oreplan.domain.ai.tool.ConfirmableTool
import com.phonlynn.oreplan.domain.ai.tool.ToolDetail
import com.phonlynn.oreplan.domain.ai.tool.ToolRegistry
import com.phonlynn.oreplan.domain.ai.tool.readableArguments
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次对话的发送流程。
 *
 * ## 职责：把「界面状态」翻译成「协议请求」
 *
 * 这一层只做三件事：
 *  ① 把历史消息裁成**能发出去的窗口**（见 [HISTORY_WINDOW]）
 *  ② 组装系统提示词
 *  ③ 调 [AiClient]
 *
 * ## ⚠️ 历史不能无限带上
 *
 * 第 50 轮对话若把全部历史发出去，输入可能几万 token —— 成本是**累积**的，
 * 而且超过模型窗口后会报错。所以只带最近 [HISTORY_WINDOW] 轮。
 *
 * 为什么是 20 轮（≈40 条消息）：一轮问答约 200~800 token，
 * 20 轮约 4k~16k token —— 在 DeepSeek 的 1M 窗口里是很小的一部分，
 * 但已经足够让模型理解上下文。**更早的内容靠记忆/摘要，而不是靠塞历史**，
 * 这正是用户说的「不要每次都用数据库填满上下文」。
 *
 * 将来的改进方向（现在不做）：把更早的历史做**摘要**后拼在系统提示里，
 * 这样既省 token 又保留长期上下文。
 */
@Singleton
class SendChatMessage @Inject constructor(
    private val client: AiClient,
    private val tools: ToolRegistry,
) {

    /**
     * 跑完一轮助手回合（**可能包含多次请求**）。
     *
     * ## 这里是一个循环，不是一次请求
     *
     * 接了工具之后，"一次对话"就不再等于"一次 HTTP 请求"了：
     *
     * ```
     * 请求 ① → 模型要求调 get_items
     *        → 本地执行，结果作为 role="tool" 追加
     * 请求 ② → 模型拿到结果，给出最终回答（不再要求调工具）→ 结束
     * ```
     *
     * 用户看到的是**一轮**回复，但底下可能发了三五次请求。
     * 这个循环就是「助手模式」和「聊天模式」真正的分界 ——
     * 聊天模式 `allowTools = false`，第一次请求就不会有 `tool_calls`，
     * 循环体一次都不进，行为和接工具之前完全一样。
     *
     * ## 循环上限是必需的
     *
     * 模型可能陷入"调工具 → 再调同一个工具"的循环（参数一直不对时尤其常见）。
     * 没有上限就是一个无限烧钱的循环。到上限时**不报错**，
     * 把已经拿到的内容正常返回 —— 用户至少能看到部分结果。
     *
     * @param onDelta 正文的流式增量
     * @param onReasoning 思考内容的增量
     * @param onProgress **当前回合的快照**，每轮更新一次。
     *
     * ## 为什么推快照而不是推事件
     *
     * 一开始我设计的是 `onToolStart` / `onToolEnd` 两个事件回调，
     * 界面自己把事件拼成"正文 → 卡片 → 正文"。那意味着**界面要重做一遍
     * 轮次拼装逻辑**（哪个工具属于哪一轮、正文该插在哪），而领域层已经在
     * [rounds] 里做过一次了 —— 两份实现迟早会漂移，而漂移的表现是
     * "生成中看到的顺序"和"生成完看到的顺序"不一样。
     *
     * 推快照则让界面**用和正式回合完全相同的渲染路径**：
     * 快照就是一个 `ChatTurn`，只是还没落库。
     */
    suspend operator fun invoke(
        config: AiProviderConfig,
        settings: AiSettings,
        history: List<ChatTurn>,
        answerTo: String? = null,
        /** 是否开启深度思考（来自输入框那一颗 chip，逐次对话可不同）。 */
        deepThinking: Boolean = false,
        /**
         * 这一轮**允不允许调工具**。由对话模式决定：聊天模式传 `false`。
         *
         * ⚠️ 这是「聊天模式不碰数据」的**唯一**实现点，而且必须是机制上的：
         * 请求里**根本不带 `tools` 字段**，模型物理上没法要求调用。
         *
         * 早年一度想过"发工具定义但用提示词叮嘱它别调"—— 那是靠模型自觉，
         * 而模型不听话的时候用户的数据已经被改了。不能那么做。
         *
         * 切换模式的口径：**中途切换等当前这轮结束才生效**，
         * 且只影响接下来的行为、对历史毫无影响 —— 在发起这一轮时读一次即可。
         */
        toolsEnabled: Boolean = true,
        onDelta: suspend (String) -> Unit = {},
        onReasoning: suspend (String) -> Unit = {},
        onProgress: suspend (ChatTurn) -> Unit = {},
    ): Result<ChatTurn> = runCatching {
        val allowTools = toolsEnabled && config.supportsTools && settings.enableTools

        /*
         * ⚠️ 带 tools 时**必须回传历史里的 reasoning_content**，否则服务端 400。
         * 不带工具时反过来要丢掉它（省 token、避免延续上一轮结论）。
         * 这条规则只在 buildMessages 一处实现，见那里的注释。
         */
        val messages = ArrayList(buildMessages(settings, history, allowTools = allowTools))

        val rounds = ArrayList<TurnRound>()
        val records = ArrayList<ToolCallRecord>()
        var lastContent: String? = null
        var lastReasoning: String? = null
        var lastMillis = 0L

        /**
         * 这一轮**累计**消耗的 token（用户 2026-10-04 要的显示）。
         *
         * ## 为什么要累加而不是取最后一次
         *
         * 助手模式下"一轮回复"底下可能发三五次请求（工具往返），
         * **每一次都是一次完整的计费调用**。只取最后一次的话，
         * 用户看到的数字会远小于真实消耗 —— 那比不显示更糟
         *（他会按这个数字去估自己的月度成本）。
         *
         * ## 为什么初值是 0 而不是 null，最后再判
         *
         * 有些厂商**整轮都不返回 usage**。那时要写回 `null`（界面不显示那一格），
         * 而不是 `0`。所以这里用 `anyUsage` 记"到底有没有见到过用量"。
         */
        var totalTokens = 0
        var anyUsage = false

        /**
         * 本轮的工具调用（可能还在跑）。
         *
         * 与 [records] 分开：`records` 只放**已有结果**的，用于回填历史；
         * 这个还包含"正在进行"的，只用于推给界面。
         */
        var toolsInFlight: List<ToolCallRecord> = emptyList()

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val startedAt = System.currentTimeMillis()
            val response = client.chat(
                config = config,
                request = ChatRequest(
                    model = config.model,
                    messages = messages,
                    tools = if (allowTools) tools.specs() else emptyList(),
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    topP = settings.topP,
                    frequencyPenalty = settings.frequencyPenalty,
                    stream = settings.streamOutput,
                    allowTools = allowTools,
                    deepThinking = deepThinking,
                    reasoningEffort = settings.thinkingDepth.apiValue.takeIf { deepThinking },
                ),
                onDelta = onDelta,
                onReasoning = onReasoning,
            )
            lastMillis = System.currentTimeMillis() - startedAt
            lastContent = response.content
            lastReasoning = response.reasoning

            // 用量累计：见 totalTokens 的注释（每次请求都算一次）
            response.usage?.let {
                totalTokens += it.totalTokens
                anyUsage = true
            }

            if (!response.wantsTools) {
                // 最后一轮：只说了一段话，没有工具调用
                rounds += TurnRound(content = response.content, reasoning = response.reasoning)
                break
            }

            /*
             * 回填这一轮助手消息时必须带上 `reasoning_content` ——
             * 带 tools 的对话里这是硬要求（见 ChatMessage 的注释）。
             */
            messages += ChatMessage(
                role = "assistant",
                content = response.content,
                toolCalls = response.toolCalls,
                reasoningContent = response.reasoning,
            )

            val callIds = ArrayList<String>(response.toolCalls.size)
            var waitingForUser = false

            /*
             * 先把这一轮的调用**全部以「进行中」推给界面**，再开始执行。
             *
             * 这样卡片是"先出现、再逐个填上结果"，而不是"执行完才一起冒出来"。
             * 本地读工具确实快到看不见转圈，但写操作要等用户确认 ——
             * 那时卡片必须已经在屏幕上了。
             */
            toolsInFlight = response.toolCalls.map { call ->
                ToolCallRecord.from(call, tools.displayName(call.name), call.readableArguments())
                    // 先标成「正在执行」让界面画转圈；写操作随后会改成「待确认」
                    .copy(status = ToolCallRecord.Status.RUNNING)
            }
            rounds += TurnRound(
                content = response.content,
                reasoning = response.reasoning,
                callIds = toolsInFlight.map { it.id },
            )
            onProgress(snapshot(history, rounds, records + toolsInFlight, answerTo))

            for ((index, call) in response.toolCalls.withIndex()) {
                val record = toolsInFlight[index]
                val tool = tools.find(call.name)

                /*
                 * 需要用户操作的工具：**不执行**，记成待确认。
                 *
                 * 两种都走这条分支：
                 *  · [ConfirmableTool] —— 写操作，等用户确认（用户口径：「确认必须发生在执行之前」）
                 *  · [AskingTool] —— 提问，等用户回答（答案只有用户知道，猜不得）
                 *
                 * 这里**不发** `role="tool"` 的结果消息 —— 因为确实还没有结果。
                 * 但紧接着就 `break` 了，所以这一组不完整的 `tool_calls`
                 * 永远不会被发出去（见下面 break 处的注释）。
                 */
                if (tool is ConfirmableTool || tool is AskingTool) {
                    // 从「正在执行」改成「待确认」—— 界面据此把转圈换成确认/作答入口
                    val pending = record.copy(status = ToolCallRecord.Status.PENDING)
                    records += pending
                    callIds += pending.id
                    waitingForUser = true
                    continue
                }

                val outcome = runTool(call, tool)
                val finished = record.copy(
                    resultSummary = outcome.display?.result ?: outcome.forModel.take(SNAPSHOT_CHARS),
                    resultForModel = outcome.forModel,
                    status = if (outcome.failed) {
                        ToolCallRecord.Status.FAILED
                    } else {
                        ToolCallRecord.Status.DONE
                    },
                )
                records += finished
                callIds += finished.id

                messages += ChatMessage.toolResult(call.id, outcome.forModel)

                // 每跑完一个就推一次：界面上的卡片逐个停转圈
                onProgress(snapshot(history, rounds, records, answerTo))
            }

            // 并行调用合成**一条**轮次（协议上就是一条 assistant 消息）
            rounds[rounds.lastIndex] = TurnRound(
                content = response.content,
                reasoning = response.reasoning,
                callIds = callIds,
            )

            /*
             * ⚠️ 停下来等用户，**必须在发下一次请求之前**。
             *
             * 上面那条 assistant 消息声明了 N 个 tool_calls，而待确认的那几个
             * 还没有 `role="tool"` 结果。协议要求两者**数量与 id 都要配上**，
             * 带着不完整的组再发一次请求会被服务端直接拒掉。
             *
             * 恢复的做法是：用户确认后把结果补进记录，**再调一次本方法** ——
             * `buildMessages` 会从 `rounds` 重建出完整的协议结构。
             * 所以不需要任何专门的"恢复"状态。
             */
            if (waitingForUser) break
        }

        val result = ChatTurn(
            conversationId = history.lastOrNull()?.conversationId.orEmpty(),
            role = ChatTurn.Role.ASSISTANT,
            content = lastContent,
            reasoning = lastReasoning,
            reasoningMillis = lastMillis.takeIf { it > 0 },
            toolCalls = records,
            rounds = rounds,
            answerTo = answerTo,
            // 一次用量都没见到 → 写 null（界面不显示那一格），而不是 0
            totalTokens = totalTokens.takeIf { anyUsage },
            createdAt = System.currentTimeMillis(),
        )

        // 收尾再推一次：界面把最后的正文与卡片状态对齐
        onProgress(result)
        result
    }

    /**
     * 生成一份"当前回合"的快照，供界面在生成过程中渲染。
     *
     * 故意**不落库**、也不设 `id` —— 它不是一个真实的回合，只是给界面看的。
     * 界面拿它走和正式回合相同的渲染路径，所以生成中与生成完看起来是一致的
     *（不一致会表现为"生成完顺序跳了一下"，而那正是这个设计要避免的）。
     */
    private fun snapshot(
        history: List<ChatTurn>,
        rounds: List<TurnRound>,
        records: List<ToolCallRecord>,
        answerTo: String?,
    ) = ChatTurn(
        conversationId = history.lastOrNull()?.conversationId.orEmpty(),
        role = ChatTurn.Role.ASSISTANT,
        content = rounds.lastOrNull()?.content,
        reasoning = rounds.lastOrNull()?.reasoning,
        toolCalls = records,
        rounds = rounds.toList(),
        answerTo = answerTo,
        createdAt = System.currentTimeMillis(),
    )

    /**
     * 执行一次工具调用，**把异常转成给模型的文字**。
     *
     * 失败不往上抛，是因为"工具报错"对模型来说是**有用的信息**：
     * 它看到「日期格式不对」会改参数重试，而抛出去只会让整轮对话以
     * 一个错误横幅结束。真正该抛的是网络/鉴权这类，那些在 [AiClient] 里。
     *
     * @param tool 已解析出的工具。`null` = 名字对不上（模型编了一个）。
     *   写工具**不会走到这里** —— 它们在循环里就被记成待确认了。
     */
    private suspend fun runTool(call: ToolCall, tool: AiTool?): ToolRunResult {
        if (call.argumentsParseFailed) {
            return ToolRunResult(
                forModel = "工具 `${call.name}` 的参数不是合法 JSON，无法执行。请重新调用并确保参数是合法 JSON。",
                failed = true,
            )
        }

        if (tool == null) {
            return ToolRunResult(
                forModel = "没有名为 `${call.name}` 的工具。可用工具：" +
                    tools.specs().joinToString("、") { it.name },
                failed = true,
            )
        }

        return runCatching { tool.run(call.arguments) }
            .fold(
                onSuccess = { ToolRunResult(it.forModel, it.forUser, failed = false) },
                onFailure = { e ->
                    ToolRunResult(
                        forModel = "工具 `${call.name}` 执行失败：${e.message ?: e::class.simpleName}",
                        failed = true,
                    )
                },
            )
    }

    /** 工具执行结果 + 给用户看的那两行。 */
    private data class ToolRunResult(
        val forModel: String,
        val display: ToolDetail? = null,
        val failed: Boolean,
    )

    /**
     * 组装发给模型的消息列表。
     *
     * ## 思考内容：**带工具时必须回传，不带时必须丢掉**
     *
     * 这两条看起来矛盾，但都是硬要求：
     *
     * · 带 `tools` 时，官方文档明确要求历史里所有轮的 `reasoning_content`
     *   原样回传，**否则服务端 400**。
     * · 不带工具时，回传它只会浪费 token（推理往往比正文长）并干扰本轮判断
     *   （模型倾向于延续上一轮的结论）。
     *
     * 所以由 `echoReasoning` 决定，调用方传"这次到底发不发 tools" ——
     * **只有一处**知道这个事实，不靠各处自己判断。
     *
     * ## 有工具调用的回合必须**逐轮重放**
     *
     * 一个工具调用在协议上是成组的：`assistant(tool_calls)` + 每个调用的 `tool(结果)`。
     * 缺任何一条服务端都会报错。所以：
     *
     * · 发起调用的那条 assistant 消息**即使 content 为空也要发** —— 它承载 `tool_calls`
     * · 并行调用必须**合并回一条** assistant 消息（当初就是这么发的）
     * · 每条 assistant 消息带**它自己那一轮**的 reasoning，不能拿最后一轮的顶替
     *
     * 这些信息只有 `ChatTurn.rounds` 记得住，所以有这个字段。
     * 老数据（`rounds` 为空）按"只有一段正文"处理，与接工具之前的行为一致。
     */
    private fun buildMessages(
        settings: AiSettings,
        history: List<ChatTurn>,
        /**
         * 这次请求**带不带 tools**。它同时决定两件相关的事：
         *
         * · 历史里的 `reasoning_content` 要不要回传（带 tools 时必须回传，否则 400）
         * · 系统提示词取哪一套（带了 = 助手模式，用助手那套）
         *
         * 合成一个参数是刻意的：这两件事的判据**本来就该是同一个事实**
         *（"这次到底有没有工具"），分开传迟早会出现两边不一致。
         */
        allowTools: Boolean,
    ): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(history.size + 1)

        buildSystemPrompt(settings, allowTools)?.let { out += ChatMessage.system(it) }

        history.takeLast(HISTORY_WINDOW * 2).let { windowed ->
            fitToBudget(windowed).forEach { turn ->
                when (turn.role) {
                    ChatTurn.Role.USER ->
                        turn.content?.takeIf { it.isNotBlank() }?.let { out += ChatMessage.user(it) }

                    ChatTurn.Role.ASSISTANT -> replayAssistant(out, turn, allowTools)

                    /*
                     * `role="tool"` 的结果消息**不单独存成 turn** —— 它们跟着发起调用的
                     * 那条 assistant 消息一起重放（见 [replayAssistant]），
                     * 这样顺序天然正确，也不会出现"结果和调用分家"。
                     * 这个分支保留只为兼容早期可能存过的数据：直接跳过。
                     */
                    ChatTurn.Role.TOOL -> Unit
                }
            }
        }
        return out
    }

    /**
     * 从**最近**往回收，收到累计长度接近 [HISTORY_TOKEN_BUDGET] 为止。
     *
     * ## 为什么是字符不是 token
     *
     * 精确算 token 要跑分词器，而这套代码不引第三方库。所以用字符数保守估算：
     * **中文约 1 字 ≈ 1 token**，英文/JSON 的字符/token 比更低（更省）。
     * 于是"按字符估"整体偏保守 —— 宁可少带几句，也不要撞上窗口被服务端拒。
     *
     * ## 为什么从最近往回收
     *
     * 用户刚说的话最相关。而且被丢掉的是**最老的**那几轮，
     * 与按轮数裁剪的方向一致，只是多了一层真正的计量。
     *
     * ## ⚠️ 至少保留最后一轮
     *
     * 哪怕最后一条本身就超预算（用户贴了一整篇长文让我总结 —— 那是正常用法），
     * 也必须带上它，否则就是"发了一个空的历史"，用户会以为 App 坏了。
     * 真超了的话，多出来的部分交给服务端报错，比静默什么都不发要好。
     */
    private fun fitToBudget(history: List<ChatTurn>): List<ChatTurn> {
        var used = 0
        val kept = ArrayList<ChatTurn>(history.size)

        for (turn in history.asReversed()) {
            val cost = turn.approxChars()
            if (kept.isNotEmpty() && used + cost > HISTORY_TOKEN_BUDGET) break
            used += cost
            kept += turn
        }
        return kept.asReversed()
    }

    /**
     * 一条回合大概多少「字符/token」。
     *
     * 正文、推理、工具参数与结果**都要算** —— 工具往返正是按轮数裁剪时
     * 最容易漏掉的那部分开销。
     */
    private fun ChatTurn.approxChars(): Int {
        var n = content?.length ?: 0
        n += reasoning?.length ?: 0
        toolCalls.forEach { call ->
            n += call.argumentsSummary.length
            n += call.resultForModel?.length ?: 0
        }
        return n
    }

    /** 把一条助手回合按协议结构重放成若干条消息。 */
    private fun replayAssistant(
        out: MutableList<ChatMessage>,
        turn: ChatTurn,
        allowTools: Boolean,
    ) {
        val byId = turn.toolCalls.associateBy { it.id }

        // 老数据没有 rounds：退化成"一段正文 + 工具卡都列在后面"
        if (turn.rounds.isEmpty()) {
            if (turn.toolCalls.isNotEmpty()) {
                out += ChatMessage(
                    role = "assistant",
                    content = turn.content,
                    toolCalls = turn.toolCalls.map { it.toProtocolCall() },
                    reasoningContent = turn.reasoning.takeIf { allowTools },
                )
                turn.toolCalls.forEach { call ->
                    call.resultForModel?.let { out += ChatMessage.toolResult(call.id, it) }
                }
            } else {
                turn.content?.takeIf { it.isNotBlank() }?.let {
                    out += ChatMessage.assistant(it, turn.reasoning.takeIf { allowTools })
                }
            }
            return
        }

        for (round in turn.rounds) {
            val calls = round.callIds.mapNotNull { byId[it] }
            if (calls.isEmpty()) {
                // 纯正文轮次
                round.content?.takeIf { it.isNotBlank() }?.let {
                    out += ChatMessage.assistant(it, round.reasoning.takeIf { allowTools })
                }
                continue
            }

            out += ChatMessage(
                role = "assistant",
                content = round.content,
                toolCalls = calls.map { it.toProtocolCall() },
                reasoningContent = round.reasoning.takeIf { allowTools },
            )
            calls.forEach { call ->
                /*
                 * 结果文本缺失时也要发一条 —— **成组出现是协议要求**，
                 * 少一条整个请求就会被判为不匹配。
                 *
                 * 待确认的那条要说清是"还没确认"而不是"结果丢了"：
                 * 用户可能把对话放很久才回来处理，模型不该以为工具坏了。
                 */
                val placeholder = when (call.status) {
                    ToolCallRecord.Status.PENDING -> "用户还没有确认这次改动，它尚未执行。"
                    else -> "（结果未记录）"
                }
                out += ChatMessage.toolResult(call.id, call.resultForModel ?: placeholder)
            }
        }
    }

    /**
     * 系统提示词。
     *
     * ## 单选，不是叠加
     *
     * 用户口径：**提示词就是单选** —— 一次只有一组生效。
     *（设计稿里那句「可叠加多组」与它画的 Radio 控件矛盾，用户澄清以单选为准。）
     *
     * 所以这里取**当前选中的那一组**，而不是把多组拼起来。
     *
     * ## 取值的优先级
     *
     * 用户的改动（`promptOverrides`）→ 自定义组自带文本 → 内置默认文本。
     * 这样用户改过的内容生效，而没改过的仍然是内置的定稿文案。
     *
     * ## 删掉的组不能再生效
     *
     * 内置的「精简回答」「苏格拉底式提问」是**可以删**的（用户口径）。
     * 删掉只意味着"不在列表里显示"，但万一 `activePromptId` 仍指向它
     *（比如删除逻辑将来改坏了），这里必须也认 `removedPrompts`，
     * 否则会出现"界面上没有这一组，但请求里却带着它的提示词"。
     *
     * ## 内置默认文本与设计稿的一处差异
     *
     * 设计稿的「系统默认」最后一句是「需要实时信息时调用联网搜索工具」，
     * 但第一批**没有联网搜索能力** —— 提示词里承诺一个不存在的工具，
     * 会让模型尝试调用并失败。所以内置文本里不含这一句，
     * 等联网搜索真做了再加回来。
     */
    /**
     * 组装系统提示词。
     *
     * @param allowTools 这一轮**实际带不带工具**（= 助手模式且厂商支持且设置里开着）。
     *   它决定取哪一套内置正文 —— 聊天那套讲"怎么说话"，助手那套在它之上
     *   又讲了工具纪律与输出结构。
     *
     * ⚠️ 判断依据是 `allowTools` 而不是"用户选了哪个模式"：模式是用户意图，
     * 而厂商不支持工具时那个模式**实际上退化成了聊天**，此时该用聊天那套 ——
     * 否则会对着一个没有工具能力的环境讲"先查再答"，它却查不了。
     */
    private fun buildSystemPrompt(settings: AiSettings, allowTools: Boolean): String? {
        val target = if (allowTools) PromptTarget.ASSISTANT else PromptTarget.CHAT
        val builtIns = BUILT_IN_PROMPTS.getValue(target)
        val set = settings.promptSet(target)
        val id = AiSettings.normalizePromptId(set.activeId)

        // 0. 选中的组已被删除 → 直接回落，不再看它的任何文本
        if (id in set.removed) {
            return builtIns.getValue(AiSettings.SYSTEM_DEFAULT)
        }

        // 1. 用户改过的正文优先
        set.overrides[id]?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. 自定义组自带的正文
        set.custom.firstOrNull { it.id == id }
            ?.text?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 3. 内置组的默认文本
        builtIns[id]?.let { return it }

        // 4. 选中的组不存在了（配置被改过）→ 退回系统默认
        return builtIns.getValue(AiSettings.SYSTEM_DEFAULT)
    }

    /** 内置提示词组的默认正文。 */
    private val BUILT_IN_PROMPTS = AiSettings.BUILT_IN_PROMPTS

    companion object {
        /**
         * 历史最多带多少轮问答（一轮 = 用户 + 助手，所以是 2 条消息）。
         *
         * ## 从 20 提到 200（用户口径）
         *
         * 20 轮对"边聊边干活"来说太短了 —— 用户前面交代过的约束，
         * 十几轮之后就悄悄丢了，而模型不会说"我忘了"，它会直接编一个。
         *
         * ## ⚠️ 光有轮数是不够的，所以还有 [HISTORY_TOKEN_BUDGET]
         *
         * "轮"不是个可靠的计量单位：一轮可以是「嗯」，也可以是
         * 一整篇笔记。而工具往返（调用参数 + 结果）还会额外吃 token，
         * 这部分按轮数完全算不到。
         *
         * 所以轮数只是**上限之一**，真正的保险丝是 token 预算 —— 见那里。
         */
        const val HISTORY_WINDOW = 200

        /**
         * 历史部分的 token 预算。
         *
         * ## 为什么需要一个 token 上限（用户问过这个）
         *
         * > 「上下文限度不是根据 token 来计算的吗」
         *
         * 是的，最终**必须**按 token 算：模型的窗口是 token 计的
         *（`deepseek-flash` / `deepseek-v4-pro` 是 **1M**），
         * 而"轮数"只是个方便人理解的近似 —— 我们没法从 turn 直接读出 token 数
         *（那要跑一次分词器，而这套代码不引第三方库）。
         *
         * 所以这里用**字符数**做保守估算：**中文约 1 字 ≈ 1 token**，
         * 而代码、英文、JSON 的字符/token 比更低（更省），所以
         * 「按字符估」是**偏保守**的方向 —— 宁可少带一点，也不要撞上窗口被拒。
         *
         * ## 为什么是 500K 而不是贴着 1M
         *
         * 输入侧除了历史还有三块：系统提示、工具定义（13 个）、
         * 以及**这一次的输出**（`max_tokens` 也算在窗口里）。
         *
         * ⚠️ 输出上限现在是 **384K**（官方最大值），所以：
         *
         * ```
         * 1M 窗口 = 历史 500K + 系统提示/工具定义 + 输出 384K + 余量
         * ```
         *
         * 500K 是这么倒推出来的 —— 留出足够空间，不会被服务端以
         * "maximum context length exceeded" 直接拒掉（那种错误在界面上
         * 只会显示成一句莫名其妙的报错）。
         */
        const val HISTORY_TOKEN_BUDGET = 500_000

        /**
         * 一次回合里最多发几轮请求。
         *
         * 模型可能陷进「调工具 → 参数还是不对 → 再调一次」的循环。
         * 没有上限就是一个**无限烧钱的循环**。
         *
         * 到上限时**不报错**：把已经拿到的内容正常返回，用户至少能看到部分结果。
         * 6 轮足够覆盖"查日程 → 细化 → 写入 → 回读确认"这类真实链路。
         */
        const val MAX_TOOL_ROUNDS = 6

        /** 工具没有自带给用户的摘要时，从回给模型的文本里截多长。 */
        private const val SNAPSHOT_CHARS = 120
    }
}
