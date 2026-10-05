package com.phonlynn.oreplan.v2.screens

import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 白板搜索：空格分词 + 关键词智能识别。
 *
 * 一条查询被拆成若干「词」，全部满足才算命中（AND）。
 * 每个词先尝试被识别为**结构化条件**（类型 / 状态 / 时间 / 颜色），
 * 识别不了就退化为**文本关键词**，在卡片的全元数据索引文本里做匹配。
 *
 * 例：
 *  - `待办 今天`      → 类型=待办 且 更新时间在今天
 *  - `置顶 考研`      → 置顶 且 文本含"考研"
 *  - `紫色 生活 阅读`  → 颜色=紫 且 文本含"生活"和"阅读"
 *  - `9月16日 摘抄`   → 类型=摘抄 且 含日期文本
 */
object BoardSearch {

    /** 一个被解析出来的查询词。 */
    sealed interface Term {
        /** 结构化条件：直接对卡片取值判断。 */
        data class Structured(val predicate: (BoardCard) -> Boolean) : Term

        /** 文本关键词：在索引文本里做包含匹配。 */
        data class Text(val keyword: String) : Term
    }

    /** 把原始查询拆成词列表。空白（含全角空格）分隔，忽略空词。 */
    fun tokenize(query: String): List<String> =
        query.split(' ', '\u3000', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 解析一条查询为若干 Term。 */
    fun parse(query: String): List<Term> = tokenize(query).map { raw ->
        parseOne(raw)
    }

    /**
     * 解析单个词。优先识别为结构化条件；无法识别则是文本关键词。
     *
     * 注意：像「绿」这种词既可能是颜色，也可能是正文里的字。
     * 这里选择识别为颜色条件——因为用户主动输入颜色名时，
     * 意图几乎总是筛颜色；要搜正文里的「绿」可以写成更长的词（如「绿色海报」）。
     */
    private fun parseOne(raw: String): Term {
        val w = raw.lowercase()

        // 类型
        TYPE_ALIASES[w]?.let { type ->
            return Term.Structured { it.type == type }
        }

        // 状态
        when (w) {
            "置顶", "已置顶", "pin", "pinned" -> return Term.Structured { it.pinned }
            "保密", "已隐藏", "隐藏", "secret" -> return Term.Structured { it.secret }
            "归档", "已归档", "archive", "archived" -> return Term.Structured { it.archived }
        }

        // 时间（基于更新时间）
        when (w) {
            "今天", "today" -> return Term.Structured { withinDays(it, 0) }
            "昨天", "yesterday" -> return Term.Structured { withinDays(it, 1) }
            "本周", "这周", "近一周", "7天", "七天" -> return Term.Structured { withinDays(it, 7) }
            "本月", "这个月", "近一月", "30天" -> return Term.Structured { withinDays(it, 30) }
        }

        // 颜色
        COLOR_ALIASES[w]?.let { colorKey ->
            return Term.Structured { (it.color ?: BoardColors.WHITE) == colorKey }
        }

        return Term.Text(raw)
    }

    /**
     * 卡片是否命中查询。
     *
     * [matchAll] 决定多关键词之间的关系：
     *  - true（交集，默认）：每个词都要命中（AND）；
     *  - false（并集）：任一命中即可（OR）。
     * 可在设置里切换。
     */
    fun matches(
        query: String,
        card: BoardCard,
        searchIndexOf: (BoardCard) -> String,
        matchAll: Boolean = true,
    ): Boolean {
        if (query.isBlank()) return true
        val terms = parse(query)
        if (terms.isEmpty()) return true
        val index = searchIndexOf(card)
        val hit: (Term) -> Boolean = { term ->
            when (term) {
                is Term.Structured -> term.predicate(card)
                is Term.Text -> index.contains(term.keyword, ignoreCase = true)
            }
        }
        return if (matchAll) terms.all(hit) else terms.any(hit)
    }

    /** 卡片更新时间是否落在从今天往前数 [daysBack] 天内（0 = 仅今天）。 */
    private fun withinDays(card: BoardCard, daysBack: Int): Boolean {
        val now = LocalDate.now(ZoneId.systemDefault())
        val day = card.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate()
        val diff = ChronoUnit.DAYS.between(day, now)
        return diff in 0..daysBack.toLong()
    }

    // ---------------------------------------------------------------- 词表

    private val TYPE_ALIASES: Map<String, BoardCardType> = buildMap {
        put("待办", BoardCardType.TODO)
        put("todo", BoardCardType.TODO)
        put("速记", BoardCardType.QUICK)
        put("笔记", BoardCardType.QUICK)
        put("摘抄", BoardCardType.QUOTE)
        put("引用", BoardCardType.QUOTE)
        put("目标", BoardCardType.GOAL)
        put("goal", BoardCardType.GOAL)
    }

    /** 颜色别名 → 颜色 key。同时覆盖中文名与常见简写。 */
    private val COLOR_ALIASES: Map<String, String> = buildMap {
        fun reg(key: String, vararg names: String) = names.forEach { put(it.lowercase(), key) }
        reg(BoardColors.WHITE, "白", "白色", "默认", "white")
        reg(BoardColors.ACCENT, "绿", "绿色", "强调", "green")
        reg(BoardColors.AMBER, "琥珀", "橙", "橙色", "黄", "黄色", "amber")
        reg(BoardColors.LILAC, "紫", "紫色", "淡紫", "lilac")
        reg(BoardColors.ROSE, "玫红", "粉", "粉色", "玫瑰", "rose")
        reg(BoardColors.GREY, "灰", "灰色", "grey", "gray")
    }
}
