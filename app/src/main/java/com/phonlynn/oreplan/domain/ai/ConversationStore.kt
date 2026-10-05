package com.phonlynn.oreplan.domain.ai

import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 对话历史的存储。
 *
 * ## 存在哪：`app_meta` 里一个 JSON 键
 *
 * 不动 schema（v11 仍冻结），与 `AiSettingsStore` 同一策略。
 *
 * ## ⚠️ 这个实现有一个明确的性能边界，必须写下来
 *
 * 整个对话历史**作为一个 JSON 值存在一行里**，每次写入都是整段重写。
 * 这对「个人笔记 App 的 AI 对话」这个量级是够的（几十个会话、每个几十轮），
 * 但它**不是**一个能无限增长的设计：
 *
 *  · 行大小：5000 轮 × 每轮 400 字 ≈ 数 MB —— SQLite 单值能吃下，但读写会变慢
 *  · 每次追加消息都要序列化整个历史
 *
 * **什么时候该换实现**：当会话数超过约 200 个、或总轮数超过约 5000 时，
 * 应当改成一张真正的表（`conversations` + `chat_turns`）。
 * 那时需要动 schema（v12），而那是一次独立的重构 ——
 * 现在为它提前建表，只会让 v11 的冻结失去意义、且大概率建错。
 *
 * 所以：**先用简单实现，把"什么时候必须换"写成上面这句判断**。
 */
@Singleton
class ConversationStore @Inject constructor(
    private val database: AppDatabase,
) {

    private val mutex = Mutex()
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val _turns = MutableStateFlow<Map<String, List<ChatTurn>>>(emptyMap())

    /**
     * **还没落库的空会话（草稿）**。
     *
     * ## 为什么要有这一层（用户报「每次都留下很多空对话」）
     *
     * 原来 [create] 会**立刻写库**。于是每按一次「＋」、每次删掉当前会话，
     * 库里就多一条空记录；用户一句话没说就退出，它就永远留在列表里。
     * 连开几次就积一堆"新对话"。
     *
     * 现在新建只是在这里放一份草稿；**等第一条消息进来才转正**
     * （见 [promote]）。没人说过话的会话**从未进过数据库**，
     * 自然也不可能留在列表里。
     *
     * 内存里的草稿至多一条 —— 由 `ChatViewModel.startNew()` 的守卫保证。
     */
    private val drafts = mutableMapOf<String, Conversation>()

    /** 会话列表（按更新时间倒序，置顶在前）。**只含说过话的会话。** */
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun refresh() = mutex.withLock { load() }

    private suspend fun load() = withContext(Dispatchers.IO) {
        val raw = database.appMetaDao().find(KEY_CONVERSATIONS)?.value
        if (raw.isNullOrBlank()) {
            _conversations.value = emptyList()
            _turns.value = emptyMap()
            return@withContext
        }
        // 解析失败退回空 —— 历史坏了不该让 App 起不来
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext

        val turns = buildMap {
            val turnsObj = root.optJSONObject("turns") ?: JSONObject()
            turnsObj.keys().forEach { cid ->
                // ⚠️ `conversationId` **不在 JSON 里存**（它由外层的 key 表达）。
                // 读回时必须显式填上 —— 不填的话，从库里读出来的消息
                // 会带着空 conversationId，后续"追加到哪条会话"就错了。
                put(cid, turnsObj.optJSONArray(cid).mapObjects { turnFromJson(it, cid) })
            }
        }

        /*
         * 清掉历史遗留的空会话。
         *
         * 上面那层"草稿"只能防止**以后**再攒空会话；用户库里已经积了一批
         * （都是老版本 create() 立刻写库留下的）。这里启动时清一次，
         * 并把清理结果写回去，免得每次启动都白清一遍。
         */
        val stored = root.optJSONArray("list").mapObjects(::conversationFromJson)
        val kept = stored.filter { turns[it.id].orEmpty().isNotEmpty() }

        _turns.value = turns.filterKeys { id -> kept.any { it.id == id } }
        _conversations.value = sortConversations(kept)

        if (kept.size != stored.size) {
            persist(_conversations.value, _turns.value)
        }
    }

    /** 某个会话的消息。 */
    fun turnsOf(conversationId: String): List<ChatTurn> =
        _turns.value[conversationId].orEmpty()

    /**
     * 新建一个空会话，**但不落库** —— 只放进内存草稿。
     *
     * 它要等到第一条消息通过 [append] / [replaceTurns] 进来时才转正（见 [promote]）。
     * 这样"开了新对话但一句话没说"永远不会在列表里留下痕迹。
     */
    suspend fun create(): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = java.util.UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
        )
        mutex.withLock { drafts[conversation.id] = conversation }
        return conversation
    }

    /**
     * 让一条会话进入"已落库"列表。
     *
     * · 已经在列表里 → 原样返回（什么都不做）
     * · 是草稿 → 取出来转正
     * · 都没有（理论到不了）→ 就地补一条，宁可多一条也不要丢消息
     */
    private fun promote(id: String, at: Long): List<Conversation> {
        val list = _conversations.value
        if (list.any { it.id == id }) return list
        val draft = drafts.remove(id) ?: Conversation(id = id, createdAt = at, updatedAt = at)
        return list + draft
    }

    /** 追加一条消息（并顺带更新会话的标题/时间）。第一条消息会把草稿转正。 */
    suspend fun append(turn: ChatTurn) = mutex.withLock {
        val cid = turn.conversationId
        val currentTurns = _turns.value[cid].orEmpty()
        val nextTurns = _turns.value + (cid to (currentTurns + turn))

        val nextConversations = promote(cid, turn.createdAt).map { c ->
            if (c.id != cid) {
                c
            } else {
                c.copy(
                    // 标题：取第一条用户消息的前若干字。设计稿里列表项显示的就是这个。
                    title = c.title.ifBlank { deriveTitle(turn) },
                    updatedAt = turn.createdAt,
                )
            }
        }
        persist(sortConversations(nextConversations), nextTurns)
        _conversations.value = sortConversations(nextConversations)
        _turns.value = nextTurns
    }

    /**
     * 就地替换某个会话的全部消息（流式结束后落库，或工具状态更新）。
     *
     * ⚠️ **空列表 = 这条会话没有内容了**，那就该从列表里消失 ——
     * 与"不留空会话"是同一条口径。否则删光消息后列表里会留一条点进去空白的记录。
     */
    suspend fun replaceTurns(conversationId: String, turns: List<ChatTurn>) = mutex.withLock {
        val now = System.currentTimeMillis()
        val nextTurns = _turns.value + (conversationId to turns)

        val base = if (turns.isEmpty()) {
            drafts.remove(conversationId)
            _conversations.value.filterNot { it.id == conversationId }
        } else {
            promote(conversationId, now)
        }
        val nextConversations = base.map {
            if (it.id == conversationId) it.copy(updatedAt = now) else it
        }

        persist(sortConversations(nextConversations), nextTurns)
        _turns.value = nextTurns.filterKeys { id -> nextConversations.any { it.id == id } }
        _conversations.value = sortConversations(nextConversations)
    }

    /**
     * 保存「每一问当前选中的是第几轮」＋「各分支名下的下文」（分支功能，用户 2026-10-04）。
     *
     * ## 为什么要和 `replaceTurns` 分开的两个参数合成一个方法
     *
     * 分支切换会**同时**改三样东西：主列表（摘掉/接回下文）、
     * 选中轮次、以及各分支的下文。这三者**必须一起落库** ——
     * 只落了其中一半的话，下次进来会看到一个"消息对不上选中轮次"的畸形状态。
     *
     * 所以这里收成一个入口：调用方一次给全，内部一次写。
     *
     * ⚠️ `tails` 里的消息**也要一起写进 `turns`**：它们是这条对话真实存在的内容
     *（切回那条分支时就要显示出来）。所以这个方法内部先算"主列表 ∪ 所有分支下文"，
     * 再一次 `replaceTurns` 落库 —— 不这么做的话，非当前分支的下文会丢。
     */
    suspend fun saveBranchState(
        conversationId: String,
        branchTails: Map<String, List<ChatTurn>>,
        activeRounds: Map<String, Int>,
    ) = mutex.withLock {
        val now = System.currentTimeMillis()

        // 当前主列表 + 各分支暂存的下文（去重、保持原有顺序）
        val main = _turns.value[conversationId].orEmpty()
        val mainIds = main.map { it.id }.toSet()
        val stashed = branchTails.values.flatten().filterNot { it.id in mainIds }

        val allTurns = main + stashed
        val nextTurns = _turns.value + (conversationId to allTurns)

        val base = if (allTurns.isEmpty()) {
            drafts.remove(conversationId)
            _conversations.value.filterNot { it.id == conversationId }
        } else {
            promote(conversationId, now)
        }
        val nextConversations = base.map {
            if (it.id == conversationId) {
                it.copy(updatedAt = now, activeRounds = activeRounds)
            } else {
                it
            }
        }

        persist(sortConversations(nextConversations), nextTurns)
        _turns.value = nextTurns.filterKeys { id -> nextConversations.any { it.id == id } }
        _conversations.value = sortConversations(nextConversations)
    }

    suspend fun rename(conversationId: String, title: String) = mutex.withLock {
        // 草稿也要能改名 —— 它在列表里是可见的那一条「新对话」
        drafts[conversationId]?.let { drafts[conversationId] = it.copy(title = title) }
        val next = _conversations.value.map {
            if (it.id == conversationId) it.copy(title = title) else it
        }
        persist(sortConversations(next), _turns.value)
        _conversations.value = sortConversations(next)
    }

    suspend fun togglePin(conversationId: String) = mutex.withLock {
        drafts[conversationId]?.let { drafts[conversationId] = it.copy(pinned = !it.pinned) }
        val next = _conversations.value.map {
            if (it.id == conversationId) it.copy(pinned = !it.pinned) else it
        }
        persist(sortConversations(next), _turns.value)
        _conversations.value = sortConversations(next)
    }

    suspend fun delete(conversationId: String) = mutex.withLock {
        drafts.remove(conversationId)
        val next = _conversations.value.filterNot { it.id == conversationId }
        val nextTurns = _turns.value - conversationId
        persist(sortConversations(next), nextTurns)
        _conversations.value = sortConversations(next)
        _turns.value = nextTurns
    }

    // ---------------------------------------------------------------- 批量（多选）

    /**
     * 批量删除（用户 2026-10-04 的「对话列表加入多选」）。
     *
     * ## ⚠️ 必须一次落库，不能循环调 [delete]
     *
     * 每次 `delete` 都是一次 `persist`（**整块重写全部会话的 JSON**，
     * 见本文件顶部的阈值说明）。删 20 条就是 20 次整库重写 ——
     * 那是 O(n²) 的写放大，列表越长越慢，而用户只看到界面卡住。
     *
     * 所以这里**一次算完、一次落库**。其余批量操作同理。
     */
    suspend fun deleteAll(ids: Collection<String>) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        val idSet = ids.toSet()
        // 草稿也在批量删除的范围内（它同样是列表里可见的一条）
        idSet.forEach { drafts.remove(it) }

        val next = _conversations.value.filterNot { it.id in idSet }
        val nextTurns = _turns.value - idSet
        persist(sortConversations(next), nextTurns)
        _conversations.value = sortConversations(next)
        _turns.value = nextTurns
    }

    /**
     * 批量置顶 / 取消置顶。
     *
     * `pinned` 由调用方给定**目标值**（而不是"逐条取反"）——
     * 多选场景下用户的意思是"把这批都置顶"或"把这批都取消置顶"，
     * 逐条取反会让已经置顶和没置顶的混在一起，结果一片混乱。
     */
    suspend fun setPinnedAll(ids: Collection<String>, pinned: Boolean) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        val idSet = ids.toSet()
        idSet.forEach { id ->
            drafts[id]?.let { drafts[id] = it.copy(pinned = pinned) }
        }

        val next = _conversations.value.map {
            if (it.id in idSet) it.copy(pinned = pinned) else it
        }
        persist(sortConversations(next), _turns.value)
        _conversations.value = sortConversations(next)
    }

    // ---------------------------------------------------------------- 内部

    private fun sortConversations(list: List<Conversation>): List<Conversation> =
        list.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })

    /**
     * 标题从首条用户消息取。
     *
     * 截到 20 字：设计稿里列表项的标题都是一行，太长会被截断显示。
     * 若截断处正好在字符中间也没关系（Kotlin 的 String 按 UTF-16 下标，中文不会切坏）。
     */
    private fun deriveTitle(turn: ChatTurn): String {
        if (!turn.isUser) return ""
        val text = turn.content?.trim().orEmpty()
        return if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX)
    }

    private suspend fun persist(conversations: List<Conversation>, turns: Map<String, List<ChatTurn>>) =
        withContext(Dispatchers.IO) {
            val root = JSONObject().apply {
                put("list", JSONArray().apply { conversations.forEach { put(conversationToJson(it)) } })
                put(
                    "turns",
                    JSONObject().apply {
                        turns.forEach { (cid, list) ->
                            put(cid, JSONArray().apply { list.forEach { put(turnToJson(it)) } })
                        }
                    },
                )
            }
            database.appMetaDao().upsert(
                AppMetaEntity(key = KEY_CONVERSATIONS, value = root.toString()),
            )
        }

    private fun conversationToJson(c: Conversation): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put("pinned", c.pinned)
        /*
         * 分支选中态：`{"提问id": 轮次下标}`（用户 2026-10-04 的分支需求）。
         *
         * **空表不写** —— 绝大多数对话没有任何分支，给它们各加一个 `{}`
         * 是白占空间（会话是整块重写的，每个字节都成倍放大）。
         */
        if (c.activeRounds.isNotEmpty()) {
            put(
                "activeRounds",
                JSONObject().apply {
                    c.activeRounds.forEach { (questionId, index) -> put(questionId, index) }
                },
            )
        }
    }

    private fun conversationFromJson(o: JSONObject) = Conversation(
        id = o.optString("id"),
        title = o.optString("title"),
        createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"),
        pinned = o.optBoolean("pinned"),
        // 老数据没有这个键 → 空表 → 界面按"默认选最后一轮"处理（既有行为）
        activeRounds = o.optJSONObject("activeRounds")?.let { obj ->
            buildMap {
                obj.keys().forEach { key ->
                    put(key, obj.optInt(key))
                }
            }
        }.orEmpty(),
    )

    private fun turnToJson(t: ChatTurn): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("role", t.role.name)
        put("content", t.content ?: JSONObject.NULL)
        put("reasoning", t.reasoning ?: JSONObject.NULL)
        put("reasoningMillis", t.reasoningMillis ?: JSONObject.NULL)
        /*
         * token 用量：**只在有值时写**（用户 2026-10-04）。
         *
         * 没写 = 那一轮没拿到用量（厂商不返回）或老数据。
         * 不写 `0` —— 那会把"不知道"变成"消耗为零"，界面会显示一行假的数字。
         */
        t.totalTokens?.let { put("totalTokens", it) }
        // 多轮回答的归属（设计稿的 `2 / 2` 切换器靠它归组）
        put("answerTo", t.answerTo ?: JSONObject.NULL)
        put("createdAt", t.createdAt)
        put(
            "toolCalls",
            JSONArray().apply {
                t.toolCalls.forEach { call ->
                    put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("name", call.name)
                            put("displayName", call.displayName)
                            put("argumentsSummary", call.argumentsSummary)
                            put("resultSummary", call.resultSummary ?: JSONObject.NULL)
                            put("status", call.status.name)
                            // 回放历史要用的两份原始数据，见 ToolCallRecord 的注释
                            call.argumentsJson?.let { put("argumentsJson", it) }
                            call.resultForModel?.let { put("resultForModel", it) }
                        },
                    )
                }
            },
        )
        /*
         * 协议轮次：`[{"x":"正文","r":"推理","c":["call_1"]}]`。
         *
         * 用短键（`x` / `r` / `c`）是因为它**每条消息都存一份**，
         * 而会话是整块重写的（见本文件顶部的阈值说明），键名的字节数会成倍放大。
         * 只有三个字段，短键不影响可读性。
         *
         * 键**全部省略为空时**不写这个字段 —— 纯文本的老消息占绝大多数，
         * 给它们各加一个 `[]` 是白占空间。
         */
        if (t.rounds.isNotEmpty()) {
            put(
                "rounds",
                JSONArray().apply {
                    t.rounds.forEach { round ->
                        put(
                            JSONObject().apply {
                                round.content?.takeIf { it.isNotBlank() }?.let { put("x", it) }
                                round.reasoning?.takeIf { it.isNotBlank() }?.let { put("r", it) }
                                if (round.callIds.isNotEmpty()) {
                                    put("c", JSONArray().apply { round.callIds.forEach { put(it) } })
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun turnFromJson(o: JSONObject, conversationId: String) = ChatTurn(
        id = o.optString("id"),
        conversationId = conversationId,
        role = runCatching { ChatTurn.Role.valueOf(o.optString("role")) }
            .getOrDefault(ChatTurn.Role.ASSISTANT),
        content = o.optStringOrNull("content"),
        reasoning = o.optStringOrNull("reasoning"),
        reasoningMillis = if (o.has("reasoningMillis") && !o.isNull("reasoningMillis")) {
            o.optLong("reasoningMillis")
        } else {
            null
        },
        toolCalls = o.optJSONArray("toolCalls").mapObjects(::toolCallFromJson),
        rounds = o.optJSONArray("rounds").mapObjects(::roundFromJson),
        answerTo = o.optStringOrNull("answerTo"),
        /*
         * token 用量：**必须区分"键不在"与"值是 0"**。
         *
         * `optInt` 对缺失的键返回 0，那样老数据会变成"消耗 0 tokens"（假的）。
         * 所以判 `has` + `!isNull`，缺了就保持 null（界面不显示那一格）。
         */
        totalTokens = if (o.has("totalTokens") && !o.isNull("totalTokens")) {
            o.optInt("totalTokens")
        } else {
            null
        },
        createdAt = o.optLong("createdAt"),
    )

    private fun roundFromJson(o: JSONObject) = TurnRound(
        content = o.optStringOrNull("x"),
        reasoning = o.optStringOrNull("r"),
        callIds = o.optJSONArray("c")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty(),
    )

    private fun toolCallFromJson(o: JSONObject) = ToolCallRecord(
        id = o.optString("id"),
        name = o.optString("name"),
        displayName = o.optString("displayName"),
        argumentsSummary = o.optString("argumentsSummary"),
        resultSummary = o.optStringOrNull("resultSummary"),
        status = runCatching { ToolCallRecord.Status.valueOf(o.optString("status")) }
            .getOrDefault(ToolCallRecord.Status.DONE),
        argumentsJson = o.optStringOrNull("argumentsJson"),
        resultForModel = o.optStringOrNull("resultForModel"),
    )

    /** `optString` 对 JSON null 返回字符串 `"null"` —— 必须归一成真正的 null。 */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out += transform(it) }
        return out
    }

    companion object {
        const val KEY_CONVERSATIONS = "ai.conversations"
        private const val TITLE_MAX = 20
    }
}
