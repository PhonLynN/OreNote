package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.core.time.TermClock
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 现在的日期与时间。
 *
 * ## 为什么必须有这个工具
 *
 * **模型不知道今天是几号。** 它的知识截止在训练时，系统提示里也没有日期
 * （刻意的：写死日期的话，隔天就得重发一次，还会和真实时间漂移）。
 *
 * 所以只要用户说「明天」「这周三」「下个月」，模型就必须先知道今天 ——
 * 否则它会拿一个**训练时**的日期去算，或者干脆瞎猜一个。
 *
 * ## ⚠️ 为什么把「明天/昨天/本周/下周」也算好
 *
 * 因为这些正是模型最容易算错的地方。让它自己做 `2026-10-03` 加一天、
 * 或者算"下周一"是哪天，是**明知它不擅长还硬让它做**：
 *
 * · 大模型在日期算术上是出了名的不稳（跨月、跨年、闰年尤其）
 * · 算错的后果不是"回答得不好"，而是**基于错误日期去读写用户的数据**
 *
 * 一次算好、直接给它，这类错就没有发生的余地。
 *
 * ## 不返回「第几周」—— 那是 [GetTimetableTool] 的事
 *
 * 课表工具已经会返回学期周次。工具之间**重叠是选择质量最大的杀手**
 *（见 `GetTimetableTool` 的注释），所以这里不重复。
 */
@Singleton
class GetCurrentTimeTool @Inject constructor() : AiTool {

    override val name = "get_current_time"
    override val displayName = "当前时间"
    override val danger = ToolDanger.READ

    override val description =
        "查询**现在的日期和时间**（含星期、本周与下周的起止）。" +
            "用户提到「今天」「明天」「这周三」「下周」这类相对时间时，**先调它**，" +
            "不要凭记忆猜日期 —— 你的知识有截止时间，猜出来的日期通常是错的。" +
            "它不需要任何参数。" +
            "另外：学期第几周不在这里，用 get_timetable 查。"

    /**
     * 无参数。
     *
     * `properties` 必须是空对象而不是省略 —— 服务端要求每个工具都有它。
     */
    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun run(args: JSONObject): ToolOutcome {
        val now = Instant.now()
        return ToolOutcome(
            forModel = describeNow(now, ZoneId.systemDefault()),
            forUser = ToolDetail(
                arguments = "无参数",
                result = ItemArgs.fullText(now),
            ),
        )
    }
}

/**
 * 把"现在"写成模型直接能用的文本。
 *
 * 做成**纯函数**（传入 `now`）而不是在工具里直接读时钟：测试要断言"今天是几号"，
 * 不能依赖跑测试那一刻的真实时间。
 */
internal fun describeNow(now: Instant, zone: ZoneId): String {
    val today = now.atZone(zone).toLocalDate()
    val time = ItemArgs.fullText(now, zone)
    val weekday = TermClock.weekdayLabel(today.dayOfWeek)

    fun day(date: LocalDate): String = "%s（%s）".format(
        date.toString(),
        TermClock.weekdayLabel(date.dayOfWeek),
    )

    val thisWeekStart = TermClock.weekStartOf(today)
    val thisWeekEnd = thisWeekStart.plusDays(6)
    val nextWeekStart = thisWeekEnd.plusDays(1)

    return buildString {
        appendLine("现在是 $time，星期$weekday。")
        appendLine()
        appendLine("· 今天：${day(today)}")
        appendLine("· 明天：${day(today.plusDays(1))}")
        appendLine("· 昨天：${day(today.minusDays(1))}")
        appendLine("· 本周：${day(thisWeekStart)} ～ ${day(thisWeekEnd)}")
        appendLine("· 下周：${day(nextWeekStart)} ～ ${day(nextWeekStart.plusDays(6))}")
        appendLine("· 时区：${zone.id}")
        append("（以上日期已经算好，直接用，不要再自己推算。）")
    }
}
