package com.phonlynn.oreplan.domain.ai

import com.phonlynn.oreplan.domain.ai.protocol.ToolCall
import java.util.UUID

/**
 * 一段对话。
 *
 * ## 为什么自己生成 id 而不是自增主键
 *
 * 与项目里其它实体一致（`Item`/`BoardCard` 都是 `Ids.newId()`）：
 * id 由客户端生成，将来若要同步或导入导出，不会因为主键冲突而错乱。
 *
 * ## `pinned` 与分组
 *
 * 设计稿的列表页有「置顶 / 今天 / 昨天 / 7 天内」分组。
 * 分组是**由 `updatedAt` 算出来的**（不存字段），只有「置顶」是真实状态。
 * 这样避免了"存了分组字段、时间过去后分组就过期了"的问题。
 */
data class Conversation(
    val id: String,
    /** 标题。首条用户消息发出后自动生成。 */
    val title: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    /**
     * 每一问**当前选中的是第几轮回答**：`提问 id → 轮次下标`。
     *
     * ## 为什么它要落库（用户 2026-10-04 的分支需求带出来的）
     *
     * 用户口径：
     *
     * > 「重新生成中间消息……通过消息选择器（`<n/m>`）来切换分支，
     * > 重新生成中部消息后，在这个新分支内清空下方的消息」
     *
     * 于是"现在选着哪一轮"决定了**这条对话里当前有哪些消息**
     *（选了分支 1 就有它的下文，选了分支 2 就没有）。
     * 那它就不是纯界面状态了 —— 它是对话内容的一部分。
     *
     * 不落库的话：用户切到分支 2、退出、再进来，会被"默认选最后一轮"
     * 重算一次 —— 而最后一轮正好也是分支 2，看起来"对"，但
     * **用户从分支 2 切回分支 1 时下面的消息回不来**（因为没有存过那份下文）。
     *
     * ## 为什么是 Map 而不是只存一个下标
     *
     * 一条对话里可能**有好几处**重新生成过（第 3 条、第 8 条各一个分支），
     * 它们各自记着自己选了哪一轮。
     */
    val activeRounds: Map<String, Int> = emptyMap(),
) {
    val hasTitle: Boolean get() = title.isNotBlank()
}

/**
 * 一条消息。
 *
 * ## 为什么要存工具调用
 *
 * 设计稿里工具调用卡是**显示在对话里**的（「创建日程 · 已完成」+ 参数 + 结果）。
 * 所以这些必须持久化 —— 否则重进对话就看不到 AI 做过什么。
 *
 * ## `reasoning` 单独存
 *
 * 「已思考（用时 N 秒）」块的内容与正文分开存：
 * UI 上它是可折叠的，且**回传给模型时不该带上**
 * （那是上一轮的推理过程，带回去只会浪费 token 并干扰判断）。
 */
data class ChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: Role,
    /** 正文。工具调用轮次可能为空。 */
    val content: String? = null,
    /** 思考内容（「已思考」块）。 */
    val reasoning: String? = null,
    /** 思考耗时（毫秒），用于显示「用时 N 秒」。 */
    val reasoningMillis: Long? = null,
    /** 模型要求调用的工具（已发生的调用记录）。 */
    val toolCalls: List<ToolCallRecord> = emptyList(),
    /**
     * 这一轮的**协议轮次**：每一次模型响应对应一条，含它说了什么、调了哪些工具。
     *
     * ## 为什么按"轮次"建模，而不是按"显示分段"
     *
     * 一轮助手回复底下可能发了多次请求：
     *
     * ```
     * 轮次 1  正文「好的，我来创建一个日程。」 + tool_calls[创建日程]
     * 轮次 2  正文「已创建：明天 10:00–11:30…」（不再要求调工具，结束）
     * ```
     *
     * 这两条信息**两个地方都要用，而且要求不同**：
     *
     * · **回放历史**要求严格的协议结构：`assistant(tool_calls)` 与 `tool(结果)`
     *   必须成对、并行调用必须合并在**同一条** assistant 消息里、
     *   每条助手消息要带**它自己那一轮**的 `reasoning_content`（缺了服务端 400）。
     * · **界面显示**要的是顺序：正文→卡片→正文。
     *
     * 按轮次建模则两者都能满足 —— 显示顺序从轮次**派生**出来即可。
     * 反过来（按显示分段建模）会丢掉"哪几段属于同一轮"这个信息，
     * 回放时就还原不出正确的协议结构。
     *
     * ## 与 [content] / [reasoning] 的关系
     *
     * 那两个字段仍然保留，分别等于**最后**一轮的正文与推理
     *（界面的「已思考」块显示的就是最后一次思考），以及 [content] 供复制/搜索/导出直接使用。
     * `rounds` 为空 = 接工具之前存的老数据，按"只有一段正文"处理。
     */
    val rounds: List<TurnRound> = emptyList(),
    /**
     * 这条助手回复回答的是哪条用户消息。
     *
     * ## 为什么需要它：多轮（设计稿的 `2 / 2` 切换器）
     *
     * 同一条提问可以有多份回答 —— 点一次「重新生成」就多一份。
     * 设计稿的消息操作行右侧有一个轮次切换器（`‹ 2 / 2 ›`），
     * 就是在这些回答之间翻页。所以每条回答必须记住**自己回答的是哪一问**，
     * 否则无法把它们归成"同一问的若干轮"。
     *
     * `null` = 早期数据或无法归属（点工具结果等），
     * 那时该条自成一轮、不参与切换。
     */
    val answerTo: String? = null,
    /**
     * 这一轮回复消耗的 **token 数**（用户 2026-10-04 要的显示）。
     *
     * ## 为什么记成"整轮合计"而不是每一轮各自的
     *
     * 一轮助手回复底下可能发三五次请求（工具往返），每次都有各自的用量。
     * 用户要看的是**这一轮我一共花了多少**，所以在 `SendChatMessage` 里
     * **累加**后写进这一个字段。
     *
     * `null` = 厂商没返回用量（或老数据）—— 界面**不显示**那一行，
     * 而不是显示 `0 tokens`（那是在撒谎：明明用了却没有数）。
     */
    val totalTokens: Int? = null,
    /** 提交给模型时的原始工具调用（回填历史时要用）。 */
    val createdAt: Long,
) {
    enum class Role { USER, ASSISTANT, TOOL }

    val isUser: Boolean get() = role == Role.USER
    val isAssistant: Boolean get() = role == Role.ASSISTANT

    /**
     * **全部正文**（按轮次顺序拼起来）。
     *
     * [content] 只是**最后一段** —— 界面按 `rounds` 分段渲染，它给不出全文。
     * 复制、导出、以及任何"要这条回复的完整文字"的地方都应该用它。
     */
    val fullText: String
        get() = if (rounds.isEmpty()) {
            content.orEmpty()
        } else {
            rounds.mapNotNull { it.content?.takeIf { c -> c.isNotBlank() } }
                .joinToString("\n\n")
                .ifBlank { content.orEmpty() }
        }
}

/**
 * 一次模型响应 —— 一轮回复里的**一个协议轮次**。
 *
 * 见 `ChatTurn.rounds` 的注释：这是回放历史唯一够用的粒度。
 */
data class TurnRound(
    /** 这一轮说的正文。要求调工具时通常为空，但不保证（模型常先说一句再调）。 */
    val content: String? = null,
    /** 这一轮的思考内容。**回放时必须原样带上**（带 tools 时缺了会 400）。 */
    val reasoning: String? = null,
    /**
     * 这一轮要求调用的工具，按 [ToolCallRecord.id] 指向 `ChatTurn.toolCalls`。
     *
     * **必须是一条列表而不是逐个追加** —— 并行工具调用在协议上是
     * **一条** assistant 消息带多个 `tool_calls`，拆开回放会被服务端判为不匹配。
     */
    val callIds: List<String> = emptyList(),
)

/**
 * 一次工具调用的**记录**（用于显示与回填）。
 *
 * 与协议层的 [ToolCall] 分开：那个是"模型刚要求的"，
 * 这个是"已经发生并记录了结果的"，多了执行状态与结果。
 */
data class ToolCallRecord(
    val id: String,
    val name: String,
    /** 给用户看的工具名（如「创建日程」）。 */
    val displayName: String,
    /** 调用参数的**可读形式**（设计稿里显示「调用参数：标题 …, 时间 …」）。 */
    val argumentsSummary: String,
    /** 返回结果的**可读形式**（设计稿里显示「返回结果：…」）。 */
    val resultSummary: String? = null,
    val status: Status = Status.PENDING,
    /**
     * 模型给的**原始参数**（JSON 字符串，原样）。
     *
     * ## 为什么可读形式之外还要存原始形式
     *
     * 回放历史时必须把 `tool_calls` **原样**送回：协议要求 assistant 消息里的
     * 参数与当初那次调用一致，服务端会拿它和后面的 `tool` 结果做配对校验。
     * [argumentsSummary] 是给人看的（去掉了引号、键名没翻译），拿它回放会被判为不匹配。
     *
     * 与 [ToolCall] 里的注释是同一个理由（那边保留 `argumentsRaw` 也是为了回填）。
     */
    val argumentsJson: String? = null,
    /**
     * 当初**回给模型**的原始结果文本（`role="tool"` 的正文）。
     *
     * 必须原样存下来，不能靠"重算一遍"：重算会查到**当前**的数据，
     * 而模型当初看到的是**当时**的数据 —— 两者不一致时，
     * 回放出的历史会变成一段从未发生过的对话。
     */
    val resultForModel: String? = null,
) {
    enum class Status {
        /**
         * **正在执行**（界面画转圈）。
         *
         * 只出现在生成过程中的快照里，**从不落库** —— 生成结束时每个调用
         * 必然已经是 [DONE] / [FAILED] / [PENDING] 之一。
         *
         * 为什么不复用 [PENDING]：那个的语义是「等**用户**做点什么」。
         * 混用会让界面分不清该画转圈还是该画「待确认」——
         * 而这两者的意思完全相反（一个叫用户等，一个叫用户动手）。
         */
        RUNNING,

        /** 等待用户操作（写操作的确认，或提问工具等待回答）。 */
        PENDING,

        /** 已执行完成。 */
        DONE,

        /** 用户拒绝 / 执行失败。 */
        FAILED,
    }

    /** 还原成协议层的调用，用于回放历史。 */
    fun toProtocolCall(): ToolCall = ToolCall(
        id = id,
        name = name,
        arguments = argumentsJson
            ?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            ?: org.json.JSONObject(),
        argumentsParseFailed = false,
    )

    companion object {
        fun from(call: ToolCall, displayName: String, summary: String) = ToolCallRecord(
            id = call.id,
            name = call.name,
            displayName = displayName,
            argumentsSummary = summary,
            argumentsJson = call.arguments.toString(),
        )
    }
}

/**
 * 多轮回答的挑选（设计稿消息操作行右侧的 `‹ 2 / 2 ›`）。
 *
 * ## 模型
 *
 * 同一条提问可以有多份回答：点一次「重新生成」就追加一份，
 * 它们靠 [ChatTurn.answerTo] 归到同一问下，按先后顺序编号 0..n-1。
 *
 * ## 为什么是纯函数
 *
 * 这段逻辑同时被三处用到（界面渲染、发给模型的历史、轮次切换器），
 * 三处各写一遍必然走偏 —— 尤其"发给模型的历史"若带了多份回答，
 * 模型会看到自己对同一个问题答了两次，行为立刻变怪。
 * 所以只在这里算一次，三处共用。
 */
object Rounds {

    /** 一圈轮次：某一问的若干份回答。 */
    data class Group(val questionId: String, val answers: List<ChatTurn>) {
        val size: Int get() = answers.size
        fun at(index: Int): ChatTurn? = answers.getOrNull(index)
    }

    /**
     * 把消息列表按「提问 → 该问的若干轮回答」归组。
     *
     * 只收集 [ChatTurn.Role.ASSISTANT] 且带 `answerTo` 的；
     * 工具结果等其它角色的消息不参与轮次。
     */
    fun groups(turns: List<ChatTurn>): Map<String, Group> = turns
        .filter { it.role == ChatTurn.Role.ASSISTANT && it.answerTo != null }
        .groupBy { it.answerTo!! }
        .mapValues { (questionId, answers) -> Group(questionId, answers) }

    /**
     * 只保留**每一问当前选中的那一轮**。
     *
     * @param active 提问 id → 选中的轮次下标。缺失或越界时回落到**最后一轮**
     *   （最新的一份就是用户刚看到的那份，重新进入对话时应该显示它）。
     */
    fun visible(turns: List<ChatTurn>, active: Map<String, Int> = emptyMap()): List<ChatTurn> {
        val grouped = groups(turns)
        if (grouped.isEmpty()) return turns

        return turns.filter { turn ->
            val questionId = turn.answerTo
            if (turn.role != ChatTurn.Role.ASSISTANT || questionId == null) {
                // 用户消息、工具结果、以及无法归属的旧数据：原样保留
                true
            } else {
                val answers = grouped[questionId]?.answers.orEmpty()
                val index = selectedIndex(active[questionId], answers.size)
                answers.getOrNull(index)?.id == turn.id
            }
        }
    }

    /** 选中下标：缺省/越界都回落到最后一轮。 */
    fun selectedIndex(requested: Int?, count: Int): Int {
        if (count <= 0) return -1
        val fallback = count - 1
        if (requested == null) return fallback
        return if (requested in 0 until count) requested else fallback
    }
}
