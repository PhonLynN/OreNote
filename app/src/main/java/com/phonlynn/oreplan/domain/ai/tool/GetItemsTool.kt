package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.domain.usecase.BuildAgendaUseCase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 查日程与待办。
 *
 * ## 为什么日程和待办是**一个**工具
 *
 * 它们在数据层是同一个实体（都是 `Item`，只差 `ItemKind`），
 * 界面上也是同一屏。拆成两个工具的话，模型要在语义重叠的两个工具之间选，
 * 而选择质量正是工具集最脆弱的地方。
 *
 * ## 为什么走 [BuildAgendaUseCase] 而不是查表
 *
 * 重复日程要展开、课程要按单双周展开、重复例外要处理 —— 那些逻辑都在 UseCase 里。
 * 自己查表会得到「每周三的例会」只有一条这样的结果，而界面上是十二条。
 */
@Singleton
class GetItemsTool @Inject constructor(
    private val buildAgenda: BuildAgendaUseCase,
    private val reminders: ReminderRepository,
) : AiTool {

    override val name = "get_items"
    override val displayName = "查看日程与待办"
    override val danger = ToolDanger.READ

    override val description =
        "查某个日期范围内的日程和待办。" +
            "用户问「明天有什么安排」「这周要做哪些事」「我有什么待办」时用它。" +
            "重复日程已经按规则展开（每周三的例会会返回每一条），" +
            "课程也在里面。**不带时间的待办**不落在具体某天，统一列在最后。" +
            "**每条都会带提醒信息**（如果有），所以「这条日程有提醒吗」也用它回答。" +
            /*
             * ⚠️ 两处最容易混的边界，写在这里比写在对方那里更有效 ——
             * 模型是"看到某个工具的描述才决定要不要调"，而误用往往发生在
             * **它先想到 get_items** 的时候。
             */
            "**两个边界：**" +
            "① 问「我什么时候有空」用 find_free_slots，**不要**用这个拉一堆再自己算" +
            "（课程的单双周、重复日程的例外它替你算好了）；" +
            "② 白板卡片不在这个工具里 —— 那是 read_board，两套数据。" +
            "**每行都会带 `id=…`**，需要看某条的完整内容时拿它调 get_item_detail。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "from",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "起始日期 YYYY-MM-DD（含当天）")
                    },
                )
                put(
                    "to",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "结束日期 YYYY-MM-DD（含当天）。跨度最长 31 天")
                    },
                )
                put(
                    "include_done",
                    JSONObject().apply {
                        put("type", "boolean")
                        put("description", "是否包含已完成的。默认 false")
                    },
                )
            },
        )
        put("required", JSONArray().put("from").put("to"))
    }

    override suspend fun run(args: JSONObject): ToolOutcome {
        val from = runCatching { LocalDate.parse(args.optString("from").trim()) }.getOrNull()
            ?: return ToolOutcome(forModel = "参数错误：from 不是合法日期，请用 YYYY-MM-DD。")
        val to = runCatching { LocalDate.parse(args.optString("to").trim()) }.getOrNull()
            ?: return ToolOutcome(forModel = "参数错误：to 不是合法日期，请用 YYYY-MM-DD。")
        if (to < from) return ToolOutcome(forModel = "参数错误：to 早于 from。")

        val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        if (days > MAX_DAYS) {
            return ToolOutcome(forModel = "参数错误：跨度 $days 天超过上限 $MAX_DAYS 天。")
        }

        val includeDone = args.optBoolean("include_done", false)
        val agenda = buildAgenda.build(from, to, ZoneId.systemDefault())

        /*
         * 提醒**一次查完**，做成 id → 时刻 的映射。
         *
         * 不逐条查库：一次 31 天的查询可能有上百条，逐条查就是上百次往返。
         * `getEnabled()` 本来就是"全部启用中的提醒"，正好是这里要的。
         */
        val reminderOf: Map<String, Instant> = runCatching {
            reminders.getEnabled()
                .groupBy { it.itemId }
                // 一条条目理论上只有一个提醒；真有多个就取最早的那个
                .mapValues { (_, list) -> list.minOf { it.triggerAt } }
        }.getOrDefault(emptyMap())

        fun describe(e: AgendaEntry): String =
            describeAgendaEntry(e, reminderOf[e.itemId])

        var timedCount = 0
        val body = buildString {
            for (date in from.datesUntil(to.plusDays(1))) {
                val entries = agenda.on(date)
                    .filter { includeDone || it.itemStatus != ItemStatus.DONE }
                if (entries.isEmpty()) continue

                appendLine("$date（${WEEKDAYS[date.dayOfWeek.value - 1]}）")
                // 有时刻的按时间排，无时刻的排在后面
                entries.sortedBy { it.startMinute ?: Int.MAX_VALUE }.forEach { e ->
                    appendLine("  " + describe(e))
                    if (e.isTimed) timedCount++
                }
            }

            // 无时限待办不落在某一天，单独列 —— 漏掉它们会让"我有什么待办"答不全
            val open = agenda.openTasks.filter { includeDone || it.itemStatus != ItemStatus.DONE }
            if (open.isNotEmpty()) {
                appendLine("无期限待办：")
                open.forEach { appendLine("  " + describe(it)) }
            }
        }.trim()

        val forModel = if (body.isBlank()) {
            "$from 至 $to 没有任何日程或待办。"
        } else {
            "$from 至 $to 的日程与待办：\n$body"
        }

        return ToolOutcome(
            forModel = forModel,
            forUser = ToolDetail(
                arguments = "$from 至 $to" + if (includeDone) " · 含已完成" else "",
                result = if (body.isBlank()) "没有内容" else "$timedCount 项有时刻的安排",
            ),
        )
    }

    /**
     * 一行描述（局部函数 `describe` 只是把 `reminderOf` 绑定进去，
     * 真正的渲染在顶层 [describeAgendaEntry] —— 那是纯函数，能直接测）。
     */
    private companion object {
        const val MAX_DAYS = 31L
        val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}

/**
 * 一行描述。
 *
 * ## ⚠️ 这里**必须带 id**（否则详情工具够不着）
 *
 * 用户问过：「ai 能做到粗略定位到一个对象之后为了查询更精准的信息用
 * `get_item_detail` 吗」—— 答案是**原来做不到**：
 *
 * ```
 * get_items        →  列出条目，但**没有 id**
 *      ↓
 * 模型想查详情     →  没有 id 可传 ✗
 *      ↓
 * get_item_detail  →  调不了
 * ```
 *
 * 而这个函数的注释原本写着「ID 不显示 —— 它对人没有意义，**需要时模型会另外要**」。
 * 问题在于**模型没法"另外要"** —— 没有任何工具能按标题反查 id。
 *
 * 所以现在带上 id。代价是每行多十几个字符（40 位 hex），
 * 换来的是**[get_item_detail] 真的能用** —— 那才是"列表粗筛 + 详情精读"
 * 这条链路的全部意义。
 *
 * 顺带：`get_item_detail` 的说明里写着「id 从那些工具的返回值里拿」——
 * 那句话现在才成立。
 *
 * ## 抽成顶层函数的理由
 *
 * 它能**直接测**：用户反馈「待办的截止日期读取不到」，而问题正出在这段渲染上。
 * 它是纯函数，不该被依赖注入挡在测试之外。
 *
 * ## ⚠️ 待办的**截止日期**以前整个丢了
 *
 * 根因是这里只渲染 `startMinute` / `endMinute`（**当天几点**），
 * 而截止日期在另外两个字段上：`dueDate` / `dueAtMillis`
 *（`AgendaEntry` 一直带着它们，只是没人用）。
 *
 * 后果不是"少一行信息"，而是模型**分不清"这天要做"和"这天到期"**，
 * 于是把截止日期当成安排时间，或者干脆说"这些待办没有截止时间"。
 */
internal fun describeAgendaEntry(
    e: AgendaEntry,
    /**
     * 这条条目的提醒时刻（没有提醒就传 null）。
     *
     * ⚠️ 由调用方**批量**查好传进来，不要在这里逐条查库 ——
     * 一次 30 天的查询可能有上百条，逐条查就是上百次数据库往返。
     */
    reminderAt: Instant? = null,
): String {
    val time = when {
        e.startMinute == null -> "无时刻"
        e.endMinute == null -> minuteLabel(e.startMinute)
        else -> "${minuteLabel(e.startMinute)}–${minuteLabel(e.endMinute)}"
    }
    val kind = if (e.isCourse) "课程" else if (e.isTimed) "日程" else "待办"
    val where = e.location?.takeIf { it.isNotBlank() }?.let { " @$it" }.orEmpty()

    /*
     * 截止信息**只对无时刻的待办补**。
     *
     * 有开始时刻的日程，日期和时间已经在前面写着了，再写一遍「截止」
     * 只是噪音（而且多数情况下两者本来就相等）。
     */
    val due = if (e.isTimed) {
        ""
    } else {
        when {
            e.dueAtMillis != null -> "　截止 ${ItemArgs.fullText(Instant.ofEpochMilli(e.dueAtMillis))}"
            e.dueDate != null -> "　截止 ${e.dueDate}"
            else -> ""
        }
    }

    // 优先级只在设过时写（0 = 未设置），否则每行都挂一个"优先级无"是噪音
    val priority = when (e.priority) {
        3 -> "　！高优先级"
        2 -> "　中优先级"
        1 -> "　低优先级"
        else -> ""
    }

    val state = when (e.itemStatus) {
        ItemStatus.DONE -> "（已完成）"
        ItemStatus.CANCELLED -> "（已取消）"
        else -> ""
    }

    /*
     * 提醒：**只在有提醒时写**。
     *
     * 没提醒就不写 —— 给每条都挂一句"无提醒"会让整份列表变得没法读，
     * 而"没提"和"没有"在这种列表语境下是一样的意思。
     */
    val reminder = reminderAt?.let { "　🔔${ItemArgs.fullText(it)}" }.orEmpty()

    /*
     * 状态：**只在设过时写**。
     *
     * 用户口径：
     *
     * > 「我注意到 ai 读取不了卡片现在的状态……日程的备注等更多字段我估计
     * > 他也读取不了，这不对，**ai 需要完全能够读取数据库里的任何细节**」
     *
     * ## ⚠️ 只能写 `AgendaEntry` 上**真实存在**的字段
     *
     * 我第一版凭印象写了 `pinned` / `rrule` / `category` —— 那三个
     * **`AgendaEntry` 上没有**（它们只存在于 `Item`）。渲染出来会编译不过，
     * 而更坏的情况是"看着像加上了、其实字段永远是空"。
     *
     * `AgendaEntry` 实际带着的、且**用户会问到**的是这几个：
     *
     * | 字段 | 写成什么 |
     * |---|---|
     * | `allDay` | 「全天」—— 决定时刻该不该显示 |
     * | `isOverride` | 「本日调整」—— 说明这条被单独改过 |
     * | `colorToken` | 「色标 X」—— 用户按颜色区分课程 |
     * | `checklistCount` | 「清单 3/5」—— 进度一眼可见 |
     *
     * ## 为什么列表里只补这几项
     *
     * `get_items` 一次可能返回上百条（30 天范围）。把全部字段塞进每一行
     * 会**炸掉上下文**（那正是用户最初担心的问题）。所以按"短、且用户会问"
     * 来挑；**完整字段去 `get_item_detail` 看**。
     */
    val status = buildList {
        if (e.allDay) add("全天")
        if (e.isOverride) add("本日调整")
        e.colorToken?.takeIf { it.isNotBlank() }?.let { add("色标 $it") }
        e.checklistCount?.let { c -> add("清单 ${c.done}/${c.total}") }
    }.let { if (it.isEmpty()) "" else "　" + it.joinToString("/") }

    /*
     * 备注：**截断**带走。
     *
     * 用户点名要的（「日程的备注等更多字段我估计他也读取不了」）。
     * 但备注可以很长（一整个段落），列表里逐条带全文会炸上下文 ——
     * 所以截到 `NOTE_EXCERPT_CHARS`，并在超长时留省略号，
     * 让模型知道"还有更多，要看全文就用详情工具"。
     */
    val note = e.note
        ?.replace('\n', ' ')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { text ->
            val shown = text.take(NOTE_EXCERPT_CHARS)
            "　备注：$shown" + if (text.length > NOTE_EXCERPT_CHARS) "…" else ""
        }
        .orEmpty()

    /*
     * id：**必须有**，否则 [GetItemDetailTool] 够不着（见函数注释）。
     *
     * 课程条目（来自课表）**没有 itemId** —— 它不是一条 `Item`，
     * 而是 `course_sessions` 里的一节课，没有详情可查。
     *
     * ⚠️ 课程那种写成 `[课程，无详情]` 而**不是** `课程id=…`。
     * 原来那种写法有歧义：`课程id=course-xyz` 里含子串 `id=course-xyz`，
     * 模型（和写测试的我）都会把它读成一个条目 id，然后拿它去调详情工具。
     * 明确写"无详情"就没有这个歧义了 —— 而且那正是模型需要知道的事。
     */
    val identity = when {
        e.itemId != null -> "　id=${e.itemId}"
        e.courseId != null -> "　[课程，无详情]"
        else -> ""
    }

    return "$time  $kind  ${e.title}$where$due$priority$status$note$reminder$state$identity"
}

/**
 * 列表里备注最多带多少字。
 *
 * 和 `read_board` 的摘要（80 字）保持同一量级 —— 两个读工具对"列表里的
 * 长文本"采取同样的克制，模型看到的风格才一致。
 */
private const val NOTE_EXCERPT_CHARS = 60
