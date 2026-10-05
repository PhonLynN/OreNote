package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import com.phonlynn.oreplan.domain.usecase.ExpandTimetableUseCase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 查课表。
 *
 * ## 和 [GetItemsTool] 的分工（这是本工具存在的唯一理由）
 *
 * 课程在数据层会**合流进日程**（`AgendaBuilder` 把课表展开后和条目并到一起），
 * 所以「这周有什么课」用 `get_items` 也能答。那两个工具就重叠了 ——
 * 而工具重叠是选择质量最大的杀手。
 *
 * 所以这里刻意只做 `get_items` **拿不到**的那部分：
 *
 * | | `get_items` | `get_timetable` |
 * |---|---|---|
 * | 单次上课的时间地点 | ✅ | ✅ |
 * | **第几周 / 学期总周数 / 学期名** | ❌ | ✅ |
 * | **教师、学分**（课程本身的属性） | ❌ | ✅ |
 * | 日程、待办 | ✅ | ❌ |
 *
 * 也就是：**问"哪天有什么"用 get_items，问"课表/学期/第几周"用它**。
 *
 * ## 课表为空有两种原因，必须分清
 *
 * 「没设学期」和「设了学期但这段时间没课」在界面上是两句不同的引导文案，
 * 对模型也必须分开说 —— 否则它会对着一个没配学期的用户说"你这周没课"。
 */
@Singleton
class GetTimetableTool @Inject constructor(
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val expandTimetable: ExpandTimetableUseCase,
) : AiTool {

    override val name = "get_timetable"
    override val displayName = "查看课表"
    override val danger = ToolDanger.READ

    override val description =
        "查课表：某个日期范围内有哪些课，以及**当前是第几周**、学期共几周。" +
            "用户问「这周有什么课」「下节课在哪」「现在第几周」「这学期有哪些课」时用它。" +
            "它不返回日程和待办（那些用 get_items）。" +
            "提问时若不确定用户指哪一周，先按本周查，不要凭猜。" +
            /*
             * ⚠️ 与 `get_items` 的**重叠区**要讲清。
             *
             * `get_items` 也说"课程也在里面" —— 那句话没说错（它确实会带出
             * 当天的课），但两个工具都这么讲，模型就不知道该调哪个。
             *
             * 分界线是**问的是什么**，不是"数据里有没有课"：
             * 问"课"→ 这里（有周次、教师、教室等课表专有信息）；
             * 问"安排"→ get_items（有提醒、优先级、完成状态）。
             */
            "**问「课」用这个**（它给周次、教师、教室等课表专有信息）；" +
            "**问「这一天/这周的整体安排」用 get_items** —— 它也会带出当天的课，" +
            "但更全（含待办、提醒、完成状态）。两个都要时分别调，不要只调一个。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "from",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "起始日期 YYYY-MM-DD（含当天）。省略则从本周一算起")
                    },
                )
                put(
                    "to",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "结束日期 YYYY-MM-DD（含当天）。省略则到本周日。跨度最长 31 天")
                    },
                )
                put(
                    "list_courses",
                    JSONObject().apply {
                        put("type", "boolean")
                        put(
                            "description",
                            "是否附上课程清单（名称、教师、学分）。" +
                                "用户问「我有哪些课」时传 true；只问某几天的安排时不用",
                        )
                    },
                )
            },
        )
        put("required", JSONArray())
    }

    override suspend fun run(args: JSONObject): ToolOutcome {
        val term = termRepository.getActiveTerm()
            ?: return ToolOutcome(
                forModel = "还没有设置学期，课表为空。请先让用户在课表页设置学期起止与周数。",
                forUser = ToolDetail(arguments = "课表", result = "未设置学期"),
            )

        val today = LocalDate.now()
        val from = parseDate(args.optString("from"))
            ?: TermClock.weekStartOf(today)
        val to = parseDate(args.optString("to"))
            ?: from.plusDays(6)

        if (to < from) return ToolOutcome(forModel = "参数错误：to 早于 from。")
        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        if (days > MAX_DAYS) {
            return ToolOutcome(forModel = "参数错误：跨度 $days 天超过上限 $MAX_DAYS 天。")
        }

        val instances = expandTimetable.expand(from, to)
        val courses = courseRepository.getCourses().associateBy { it.id }
        val currentWeek = TermClock.weekNumberOn(term.startDate, term.totalWeeks, today)

        val body = buildString {
            appendLine("学期：${term.name}，共 ${term.totalWeeks} 周" +
                (currentWeek?.let { "，今天是第 $it 周" } ?: "，今天不在学期内"))
            appendLine("范围：$from 至 $to")

            if (instances.isEmpty()) {
                appendLine("这段时间没有课。")
            } else {
                instances.groupBy { it.date }.toSortedMap().forEach { (date, list) ->
                    val week = TermClock.weekNumberOn(term.startDate, term.totalWeeks, date)
                    appendLine("$date（${WEEKDAYS[date.dayOfWeek.value - 1]}，第 $week 周）")
                    list.sortedBy { it.startMinuteOfDay }.forEach { inst ->
                        val course = courses[inst.courseId]
                        val where = (inst.location ?: course?.defaultLocation)
                            ?.takeIf { it.isNotBlank() }?.let { " @$it" }.orEmpty()
                        appendLine(
                            "  ${minuteLabel(inst.startMinuteOfDay)}–${minuteLabel(inst.endMinuteOfDay)}" +
                                "  ${course?.name ?: inst.courseId}$where",
                        )
                    }
                }
            }

            if (args.optBoolean("list_courses", false) && courses.isNotEmpty()) {
                appendLine("本学期课程清单：")
                courses.values.sortedBy { it.name }.forEach { c ->
                    val extras = buildList {
                        c.teacher?.takeIf { it.isNotBlank() }?.let { add("教师 $it") }
                        if (c.credit > 0) add("${c.credit} 学分")
                    }
                    appendLine("  ${c.name}" + if (extras.isEmpty()) "" else "（${extras.joinToString("，")}）")
                }
            }
        }.trim()

        return ToolOutcome(
            forModel = body,
            forUser = ToolDetail(
                arguments = "$from 至 $to" + if (args.optBoolean("list_courses", false)) " · 含课程清单" else "",
                result = when {
                    instances.isEmpty() -> "这段时间没有课"
                    else -> "${instances.size} 次课"
                },
            ),
        )
    }

    private fun parseDate(text: String): LocalDate? =
        text.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    private companion object {
        const val MAX_DAYS = 31L
        val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
