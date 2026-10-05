package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.ai.protocol.ToolCall
import com.phonlynn.oreplan.domain.model.DayWindow
import com.phonlynn.oreplan.domain.usecase.BuildAgendaUseCase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 查空闲时间。
 *
 * ## 数据来源：**和日程页时间轴完全同一份**
 *
 * 用户口径：
 * > 「直接根据日程页的时间轴部分空闲区域算出空闲时间，然后返回给 ai」
 *
 * 所以这里走 [BuildAgendaUseCase]（时间轴用的也是它），
 * 再取 `Agenda.scheduledOn(date)` —— 那正好就是**时间轴上画出来的那些**
 *（有具体时刻、且不是全天）。窗口取 [DayWindow]，和时间轴同一个常量。
 *
 * ⚠️ **不要绕过 Agenda 直接查条目表。** 重复日程要展开、课表要按单双周展开、
 * 例外要处理 —— 那些逻辑都在 UseCase 里。自己查表算出来的空闲时间
 * 会和界面上看到的对不上，而用户第一眼就会发现。
 *
 * ## 为什么"空闲"必须由代码算
 *
 * 这是纯区间运算，模型做这个会算错（它见过太多"看起来对"的答案）。
 * 把日程原样给它、让它自己推，等于每次都在赌它这次不出错。
 */
@Singleton
class FindFreeSlotsTool @Inject constructor(
    private val buildAgenda: BuildAgendaUseCase,
) : AiTool {

    override val name = "find_free_slots"
    override val displayName = "查找空闲时间"
    override val danger = ToolDanger.READ

    override val description =
        "查某个日期范围内**空闲**的连续时段（已经扣掉日程和课程）。" +
            "用户问「我什么时候有空」「这周哪天能安排两小时」时用它。" +
            "不要自己根据日程推算空闲 —— 课程要按单双周展开、重复日程有例外，容易算错。" +
            "返回的是空闲时段；已占用的时段不会返回（需要的话另外看日程）。" +
            /*
             * ⚠️ 明确切开与 `get_items` 的分工。
             *
             * 这两个工具都能回答"我这周忙不忙"，界限不说清，模型很容易
             * 用 get_items 拉一堆日程再自己心算 —— 而它算不准
             *（课程的单双周、重复日程的例外，它看不到）。
             */
            "**问「有没有空」就用这个，不要用 get_items 拉一堆日程再自己算**。" +
            "反过来，要看「具体有什么安排」用 get_items —— 这个工具只给空档，不给占用的内容。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "from",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "起始日期，格式 YYYY-MM-DD（含当天）")
                    },
                )
                put(
                    "to",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "结束日期，格式 YYYY-MM-DD（含当天）。跨度最长 31 天")
                    },
                )
                put(
                    "min_minutes",
                    JSONObject().apply {
                        put("type", "integer")
                        put("description", "只返回不短于这么多分钟的空档。默认 30")
                    },
                )
                put(
                    "day_from",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "每天的可用起点，HH:mm。默认 08:00（与日程页时间轴一致）")
                    },
                )
                put(
                    "day_to",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "每天的可用终点，HH:mm。默认 22:00")
                    },
                )
            },
        )
        put("required", JSONArray().put("from").put("to"))
    }

    override suspend fun run(args: JSONObject): ToolOutcome {
        val from = parseDate(args.optString("from")) ?: return bad("from")
        val to = parseDate(args.optString("to")) ?: return bad("to")
        if (to < from) return ToolOutcome(forModel = "参数错误：to 早于 from。")

        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        if (days > MAX_DAYS) {
            return ToolOutcome(
                forModel = "参数错误：跨度 $days 天超过上限 $MAX_DAYS 天。请分几次查，或缩小范围。",
            )
        }

        val minMinutes = args.optInt("min_minutes", 30).coerceIn(5, 24 * 60)
        val dayFrom = parseHhmm(args.optString("day_from")) ?: DayWindow.DEFAULT_FROM
        val dayTo = parseHhmm(args.optString("day_to")) ?: DayWindow.DEFAULT_TO

        val agenda = buildAgenda.build(from, to, ZoneId.systemDefault())

        val lines = ArrayList<String>(days.toInt())
        var total = 0
        for (date in from.datesUntil(to.plusDays(1))) {
            val occupied = agenda.scheduledOn(date).mapNotNull { entry ->
                val start = entry.startMinute ?: return@mapNotNull null
                // 没有结束时刻的条目按 1 小时算 —— 和界面上画出来的高度一致
                val end = entry.endMinute ?: (start + 60)
                start until end
            }
            val slots = freeSlots(occupied, dayFrom, dayTo, minMinutes)
            total += slots.size
            val weekday = WEEKDAYS[date.dayOfWeek.value - 1]
            if (slots.isEmpty()) {
                lines += "$date（$weekday）：无（$minMinutes 分钟以上的空档都没有）"
            } else {
                lines += "$date（$weekday）：" + slots.joinToString("、") { s ->
                    "${minuteLabel(s.first)}–${minuteLabel(s.last + 1)}（${durationLabel(s.last - s.first + 1)}）"
                }
            }
        }

        val window = DayWindow.label(dayFrom, dayTo)
        val forModel = buildString {
            appendLine("空闲时段（每天 $window，已扣除日程与课程；空档不短于 $minMinutes 分钟）")
            lines.forEach { appendLine(it) }
            if (total == 0) appendLine("整个范围内没有符合条件空档。")
        }.trim()

        val forUser = ToolDetail(
            arguments = "${from} 至 ${to} · 每天 $window · 不短于 $minMinutes 分钟",
            result = if (total == 0) "没有符合条件的空档" else "找到 $total 段空闲",
        )
        return ToolOutcome(forModel = forModel, forUser = forUser)
    }

    private fun bad(key: String) = ToolOutcome(
        forModel = "参数错误：$key 不是合法日期。请用 YYYY-MM-DD 格式重新调用。",
    )

    private companion object {
        const val MAX_DAYS = 31L
        val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        fun parseDate(text: String): LocalDate? =
            runCatching { LocalDate.parse(text.trim()) }.getOrNull()

        /** `HH:mm` → 分钟数。 */
        fun parseHhmm(text: String): Int? {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").find(text.trim()) ?: return null
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || min !in 0..59) return null
            return h * 60 + min
        }
    }
}
