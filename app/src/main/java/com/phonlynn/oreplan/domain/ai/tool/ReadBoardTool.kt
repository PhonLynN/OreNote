package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.AutoPinRule
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.repository.BoardRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 浏览白板卡片。
 *
 * ## 白板是**独立实体**
 *
 * 它不挂 `Item`（见 `BoardCard` 与 `Item` 是两个模型），所以必须有自己的一套工具。
 * 日程/待办的工具看不到白板，反之亦然。
 *
 * ## ⚠️ 不过滤 `secret` 卡片
 *
 * 卡片有个 `secret` 标记（界面上是一把锁）。**这里刻意不过滤它** ——
 * 用户在 `docs/AI-待开发清单.md` §9 里明确写过：
 *
 * > 「端到端加密 / 隐私开关 —— 用户明确不在乎隐私。不做 `secret` 卡片过滤」
 *
 * 所以别在这里加过滤：那既违背已定的口径，也会让 AI 给出
 * "你没有这张卡片"这种**和界面上对不上**的答案。
 *
 * ## 为什么是"浏览"而不是"搜索"
 *
 * 白板卡片量不大（几十到几百张），而且没有向量索引 ——
 * 这里做的是**关键词 + 标签 + 时间**的粗筛，不是语义检索。
 * 工具说明里必须讲清楚这一点，否则模型会以为它能像搜索引擎那样工作。
 */
@Singleton
class ReadBoardTool @Inject constructor(
    private val board: BoardRepository,
) : AiTool {

    override val name = "read_board"
    override val displayName = "浏览白板"
    override val danger = ToolDanger.READ

    override val description =
        "按关键词、标签或时间浏览白板卡片。用户问「我记过关于 X 的卡片吗」" +
            "「白板上有什么」「最近记了些什么」时用它。" +
            "**这是关键词粗筛，不是语义检索** —— 换个说法可能就搜不到，" +
            "搜不到时应当换关键词再试，而不是断言「没有」。" +
            /*
             * ⚠️ 这里原来写着「拿到 id 后再调用 get_item_detail」—— **那是错的**。
             *
             * 白板卡片与日程/待办是**两套独立的数据**（`BoardCard` 上没有任何
             * 指向 `Item` 的字段），把卡片 id 传给 `get_item_detail` 只会找不到。
             * 模型照着做就会失败，然后可能编一个答案出来。
             *
             * 卡片自己的正文已经在下面输出了；真要更长，用 `limit` 控制
             * 返回张数，而不是转去别的工具。
             */
            "返回值里每张卡片都带 id、状态（置顶/保密/动态浮起）与正文摘要。" +
            "**卡片没有单独的详情工具** —— 它和日程/待办是两套数据，" +
            "不要把卡片 id 传给 get_item_detail。要看某张卡片的全文，" +
            "把 query 收窄到能唯一命中它。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "query",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "关键词，会在标题和正文里匹配。不区分大小写")
                    },
                )
                put(
                    "tags",
                    JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "标签名列表。命中**任意一个**即可（或的关系）")
                    },
                )
                put(
                    "from",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "只看这个日期之后创建或修改的卡片，YYYY-MM-DD")
                    },
                )
                put(
                    "to",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "只看这个日期之前创建或修改的卡片，YYYY-MM-DD")
                    },
                )
                put(
                    "include_archived",
                    JSONObject().apply {
                        put("type", "boolean")
                        put("description", "是否包含已归档的卡片。默认 false")
                    },
                )
                put(
                    "limit",
                    JSONObject().apply {
                        put("type", "integer")
                        put("description", "最多返回多少张（默认 20，上限 50）。按修改时间从新到旧")
                    },
                )
            },
        )
        put("required", JSONArray())
    }

    override suspend fun run(args: JSONObject): ToolOutcome {
        // 白板没有 `suspend fun getAll()`；仓库只暴露 Flow，取首个值即可
        val all = board.observeCards().first()
        val tags = board.observeTags().first().associateBy { it.id }
        val cardTags = board.observeCardTags().first().groupBy { it.cardId }

        val includeArchived = args.optBoolean("include_archived", false)
        val query = args.optString("query").trim().takeIf { it.isNotBlank() }
        val wantedTags = args.optJSONArray("tags").toStringList()
        val from = parseDate(args.optString("from"))
        val to = parseDate(args.optString("to"))
        val limit = args.optInt("limit", 20).coerceIn(1, MAX_LIMIT)

        var pool = all.filter { includeArchived || !it.archived }

        query?.let { q ->
            val lower = q.lowercase()
            pool = pool.filter { card ->
                card.title?.lowercase()?.contains(lower) == true ||
                    card.body?.lowercase()?.contains(lower) == true
            }
        }

        if (wantedTags.isNotEmpty()) {
            pool = pool.filter { card ->
                cardTags[card.id].orEmpty().any { link ->
                    tags[link.tagId]?.name?.let { it in wantedTags } == true
                }
            }
        }

        if (from != null || to != null) {
            pool = pool.filter { card ->
                val day = card.updatedAt.toLocalDate()
                (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to))
            }
        }

        val hits = pool.sortedByDescending { it.updatedAt }.take(limit)

        val body = if (hits.isEmpty()) {
            "没有匹配的白板卡片。"
        } else {
            buildString {
                appendLine("匹配到 ${pool.size} 张，显示最近 $limit 张：")
                hits.forEach { card ->
                    appendLine("- ${describe(card, cardTags[card.id].orEmpty().mapNotNull { tags[it.tagId]?.name })}")
                }
            }.trim()
        }

        val filters = buildList {
            query?.let { add("关键词「$it」") }
            if (wantedTags.isNotEmpty()) add("标签 ${wantedTags.joinToString("/")}")
            if (from != null || to != null) add("时间 ${from ?: "…"} 至 ${to ?: "…"}")
        }

        return ToolOutcome(
            forModel = body,
            forUser = ToolDetail(
                arguments = if (filters.isEmpty()) "全部卡片" else filters.joinToString(" · "),
                result = if (hits.isEmpty()) "没有匹配" else "${pool.size} 张匹配",
            ),
        )
    }

    /**
     * 一行描述。
     *
     * ## ⚠️ 这里曾经只输出 5 个字段（用户报的「AI 读不到卡片状态」）
     *
     * 用户原话：
     *
     * > 「我注意到 ai 读取不了卡片现在的状态，他不知道现在这个卡片是不是
     * > 置顶的保密的动态浮起的……**ai 需要完全能够读取数据库里的任何细节**」
     *
     * `BoardCard` 有 17 个字段，而原来只输出「标题 + 摘要 + id + 修改日期 +
     * 标签」。缺的那些（置顶 / 保密 / 动态浮起 / 宽度 / 颜色）恰恰是
     * **用户会问到的状态** —— 于是 AI 只能答"我看不到"，或者更糟：**瞎猜**。
     *
     * ## 现在输出哪些、为什么不输出另一些
     *
     * | 字段 | 输出 | 说明 |
     * |---|---|---|
     * | `pinned` / `secret` / `autoPin` | ✅ | 用户明确问到的"状态" |
     * | `widthMode` / `color` | ✅ | 用户会问"哪张是半宽的" |
     * | `createdAt` / `updatedAt` | ✅ | 原来只有修改时间 |
     * | `secretHint` | ✅ | 保密卡的暗号文案 |
     * | `showDate` / `imageLayout` | ✅ | 展示设置，用户可能问 |
     * | ~~`sortIndex`~~ | ❌ | 纯排序键，对人没有意义 |
     * | ~~`type`~~ | ❌ | 已废弃（白板只有一种卡，见 `BoardCardType`） |
     *
     * ## 状态用**中文词**而不是 true/false
     *
     * 模型看到 `置顶` 比看到 `pinned=true` 更容易用对 —— 而且紧凑：
     * 一行里可能要塞五六个状态。
     *
     * 正文仍然截断（`EXCERPT_CHARS`）—— 那是**刻意的上下文控制**，
     * 要看全文用 `limit` 之后单独读某一张。这次补的是字段，不是正文长度。
     */
    private fun describe(card: BoardCard, tagNames: List<String>): String {
        val title = card.title?.takeIf { it.isNotBlank() } ?: "（无标题）"
        val excerpt = card.body
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(EXCERPT_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?.let { if (card.body.length > EXCERPT_CHARS) "$it…" else it }

        val tail = buildList {
            add("id=${card.id}")

            // ---- 状态（用户明确问过的那几项）----
            if (card.pinned) add("置顶")
            if (card.archived) add("已归档")
            if (card.secret) {
                add(card.secretHint?.takeIf { it.isNotBlank() }?.let { "保密（暗号：$it）" } ?: "保密")
            }
            card.autoPin?.let { add("动态浮起：${autoPinLabel(it)}") }

            // ---- 外观 ----
            widthLabelOf(card.widthMode)?.let { add(it) }
            card.color?.takeIf { it.isNotBlank() }?.let { add("颜色 ${boardColorLabelOf(it)}") }
            imageLayoutLabelOf(card.imageLayout)?.let { add(it) }
            if (!card.showDate) add("不显示日期")

            // ---- 关联与时间 ----
            if (tagNames.isNotEmpty()) add("标签 " + tagNames.joinToString("/"))
            add("创建于 ${card.createdAt.toLocalDate()}")
            add("改于 ${card.updatedAt.toLocalDate()}")
        }
        return "$title" + (excerpt?.let { "：$it" } ?: "") + "（${tail.joinToString("，")}）"
    }

    /**
     * 动态置顶规则的**中文说法**。
     *
     * `AutoPinRule` 是个 sealed interface，两态：
     *
     * · `OnDate` —— 某个时刻浮起，持续 N 分钟
     * · `Recurring` —— 周期浮起（RRULE）
     *
     * 模型不需要知道 RRULE 的细节，只需要知道"这张卡会不会自己浮起来"。
     * 所以这里给的是**人能读的概括**，不是原始规则 ——
     * 真要改规则得用卡片编辑页，AI 工具目前也不支持改它。
     */
    private fun autoPinLabel(rule: AutoPinRule): String = when (rule) {
        is AutoPinRule.OnDate -> "指定日期 ${ItemArgs.fullText(rule.at)}"
        is AutoPinRule.Recurring -> "周期重复"
    }

    private fun parseDate(text: String): LocalDate? =
        text.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    private fun Instant.toLocalDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private companion object {
        const val MAX_LIMIT = 50
        const val EXCERPT_CHARS = 80
    }
}
