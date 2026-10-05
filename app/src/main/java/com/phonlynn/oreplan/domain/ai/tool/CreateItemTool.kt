package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 新建日程 / 待办。
 *
 * ## 为什么日程和待办是**一个**工具
 *
 * 它们是同一个实体（都是 [Item]，只差 [ItemKind]），界面上也是同一屏。
 * 拆成两个工具会让模型在语义重叠的两个工具之间选，而选择质量正是
 * 工具集最脆弱的地方。所以用 `kind` 参数区分，并在说明里讲清两者的语义差别。
 *
 * ## 写操作一律先确认
 *
 * 用户口径（2026-10-03）：「我觉得写部分不只是删除，如果修改/添加也需要确认」。
 * 所以本工具**不直接写库** —— [preview] 只算变更，用户确认后 [apply] 才写。
 *
 * ## ⚠️ 时间怎么解析
 *
 * 模型给的是 `2026-10-04T14:00`（不带时区）这种**墙上时间**。
 * 用户说"明天下午两点"指的是他手表上的两点，所以按**设备时区**解释 ——
 * 这正是 [ZoneId.systemDefault] 的用途。用 UTC 会整体偏 8 小时，
 * 而且偏得毫无规律（取决于用户在哪）。
 */
@Singleton
class CreateItemTool @Inject constructor(
    private val items: ItemRepository,
    private val reminders: ReminderRepository,
) : ConfirmableTool {

    override val name = "create_item"
    override val displayName = "创建日程"
    override val description =
        "新建一条日程或待办。" +
            "「日程」（kind=event）是**有确定时刻**的事，会出现在日程页的时间轴上；" +
            "「待办」（kind=task）是**没有确定时刻**的事，只出现在待办列表里。" +
            "用户说「安排到明天下午三点」用 event；说「记一下要做 X」用 task。" +
            "**调用前必须先确认清楚标题和时刻** —— 时刻含糊（如「下午」）就问清楚，" +
            "不要自己猜一个时间写进去。" +
            "用户提到提醒时（「提前 15 分钟提醒我」「提醒我一下」）**一定要填 remind_before** ——" +
            "用户说了提醒却不填，等于把提醒漏掉。" +
            "注意：待办没有时刻，所以**只能按绝对时间提醒**（见 remind_before 的说明）。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "kind",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("event").put("task"))
                        put("description", "event = 日程（有具体时刻）；task = 待办（无时刻）")
                    },
                )
                put(
                    "title",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "标题。要简洁具体，如「概率论复习 · 第 7 章」")
                    },
                )
                put(
                    "start",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "开始时刻，格式 YYYY-MM-DDTHH:mm。" +
                            "kind=event 时必填；kind=task 时表示期望完成期，可省略")
                    },
                )
                put(
                    "end",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "结束时刻，格式同上。省略则默认 1 小时")
                    },
                )
                put(
                    "location",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "地点（仅 event 有意义）")
                    },
                )
                put(
                    "note",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "备注正文")
                    },
                )
                put(
                    "priority",
                    JSONObject().apply {
                        put("type", "integer")
                        put("description", "优先级：0 无 / 1 低 / 2 中 / 3 高。默认 0")
                    },
                )
                put(
                    "remind_before",
                    JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "提前多久提醒：如 `15分钟` / `1小时` / `1天` / `准点`；" +
                                "不要提醒写 `none`；**用户没提提醒就别传**。" +
                                "⚠️ 只有日程（event）能按「提前多久」提醒；" +
                                "待办没有时刻，要提醒就用 remind_at 给绝对时间",
                        )
                    },
                )
                put(
                    "remind_at",
                    JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "提醒的**绝对时刻**，格式 `2026-10-04T15:00`。" +
                                "⚠️ **待办只能用它来设提醒**（待办没有开始时刻，算不出「提前多久」）。" +
                                "与 remind_before 二选一；两个都传时以 remind_before 为准",
                        )
                    },
                )
            },
        )
        put("required", JSONArray().put("kind").put("title"))
    }

    /** 变更预览：把要写的字段逐条列出来给用户看。 */
    override suspend fun preview(args: JSONObject): List<ChangeRecord> {
        val parsed = parse(args) ?: return emptyList()
        return listOf(
            ChangeRecord(
                id = RECORD_ID,
                typeLabel = if (parsed.kind == ItemKind.EVENT) "日程" else "待办",
                operation = ChangeOperation.CREATE,
                subject = ChangeSubject.of(parsed.title, parsed.note),
                fields = parsed.fields(),
            ),
        )
    }

    override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
        // 用户取消勾选了这一项 → 什么都不做，并如实告诉模型
        if (RECORD_ID !in accepted) {
            return ToolOutcome(
                forModel = "用户取消了这次创建，没有写入任何内容。",
                forUser = ToolDetail(arguments = args.optString("title"), result = "已取消"),
            )
        }

        val parsed = parse(args)
            ?: return ToolOutcome(forModel = "参数不合法，没有创建。请检查 kind / title / start 后重试。")

        val now = Instant.now()
        val item = Item.newRoot(
            kind = parsed.kind,
            title = parsed.title,
            now = now,
            note = parsed.note,
            priority = parsed.priority,
            startAt = parsed.startAt,
            endAt = parsed.endAt,
        ).copy(location = parsed.location)

        val id = items.create(item)

        /*
         * 挂提醒。
         *
         * ⚠️ 必须在 `items.create` **之后**做 —— 提醒要挂在条目 id 上，
         * 而 id 是那一刻才有的。
         *
         * 设不上时（待办没有时刻、或算出来的时刻已经过去）不要把整次创建
         * 判为失败：条目已经建好了，回滚反而更糟。**如实告诉模型**，
         * 让它决定是补一个绝对时刻、还是转告用户。
         */
        val reminderNote = attachReminder(args, id, parsed)

        return ToolOutcome(
            forModel = "已创建${if (parsed.kind == ItemKind.EVENT) "日程" else "待办"}：" +
                "${parsed.title}${parsed.timeText().let { if (it.isBlank()) "" else "，$it" }}。" +
                "id=$id$reminderNote",
            forUser = ToolDetail(
                arguments = parsed.title,
                result = "已写入${if (parsed.kind == ItemKind.EVENT) "日程" else "待办"}",
            ),
        )
    }

    /**
     * 建完之后挂提醒，返回一句**要补进工具结果里的话**。
     *
     * 三种情况都要说清楚，否则模型会以为提醒已经设好了：
     *
     * · 设成功了 → 「，已设提前 15 分钟提醒」
     * · 用户没提提醒 → 空字符串（不加噪音）
     * · 设不上 → 「（提醒没设上：…）」+ 原因
     */
    private suspend fun attachReminder(args: JSONObject, itemId: String, parsed: Parsed): String {
        val before = Reminders.parse(args.optTextOrNull("remind_before"))
        val atText = args.optTextOrNull("remind_at")
        val at = atText?.let { ItemArgs.parseInstant(it) }

        // 没提提醒 → 不动
        if (before == null && at == null) return ""

        // 明确说不要提醒
        if (before == ReminderSpec.None) {
            return "（本次不设提醒）"
        }

        if (before is ReminderSpec.Before) {
            val ok = Reminders.set(reminders, itemId, parsed.startAt, before.minutes)
            return if (ok) {
                "，已设「提前 ${Reminders.label(before.minutes)}」提醒"
            } else {
                "（**提醒没设上**：这条没有开始时刻，算不出提前量。" +
                    "要设的话改用 remind_at 给一个绝对时刻）"
            }
        }

        // 绝对时刻（待办走这条）
        val ok = at != null && Reminders.setAt(reminders, itemId, at)
        return if (ok) "，已设提醒" else "（**提醒没设上**：那个时刻已经过去了）"
    }

    // ---------------------------------------------------------------- 解析

    private data class Parsed(
        val kind: ItemKind,
        val title: String,
        val startAt: Instant?,
        val endAt: Instant?,
        val location: String?,
        val note: String?,
        val priority: Int,
    ) {
        fun timeText(): String {
            val zone = ZoneId.systemDefault()
            val s = startAt?.atZone(zone)
            val e = endAt?.atZone(zone)
            return when {
                s == null -> ""
                e == null -> "%d月%d日 %02d:%02d".format(s.monthValue, s.dayOfMonth, s.hour, s.minute)
                else -> "%d月%d日 %02d:%02d–%02d:%02d".format(
                    s.monthValue, s.dayOfMonth, s.hour, s.minute, e.hour, e.minute,
                )
            }
        }
    }

    private fun parse(args: JSONObject): Parsed? {
        val title = args.optString("title").trim()
        if (title.isBlank()) return null

        val kind = when (args.optString("kind").trim().lowercase()) {
            "task", "todo" -> ItemKind.TASK
            else -> ItemKind.EVENT
        }

        val start = parseInstant(args.optString("start"))
        // 日程必须有时刻 —— 没有时刻的日程在时间轴上无处安放，等于一条坏数据
        if (kind == ItemKind.EVENT && start == null) return null

        val end = parseInstant(args.optString("end"))
            // 有开始没结束时默认 1 小时，而不是留一个"没有结束"的日程
            ?: start?.plusSeconds(DEFAULT_DURATION_SECONDS)

        return Parsed(
            kind = kind,
            title = title,
            startAt = start,
            endAt = end.takeIf { kind == ItemKind.EVENT } ?: end,
            location = args.optString("location").trim().takeIf { it.isNotBlank() },
            note = args.optString("note").trim().takeIf { it.isNotBlank() },
            priority = args.optInt("priority", 0).coerceIn(0, 3),
        )
    }

    private fun Parsed.fields(): List<ChangeField> = buildList {
        add(ChangeField("标题", null, title))
        if (kind == ItemKind.EVENT) {
            add(ChangeField("时间", null, timeText()))
            location?.let { add(ChangeField("地点", null, it)) }
        } else {
            // 待办没有确定时刻；有期望完成期就显示出来
            if (startAt != null) add(ChangeField("期望完成", null, timeText()))
        }
        note?.let { add(ChangeField("备注", null, it)) }
        if (priority > 0) add(ChangeField("优先级", null, listOf("无", "低", "中", "高")[priority]))
    }

    /**
     * 解析墙上时间。
     *
     * 接受两种写法（模型两种都会用）：
     *  · `2026-10-04T14:00` —— 规范写法
     *  · `2026-10-04 14:00` —— 空格分隔，同样常见
     *
     * 按**设备时区**解释，见类注释。不带分钟的 `2026-10-04T14` 也接受。
     */
    private fun parseInstant(text: String): Instant? {
        val trimmed = text.trim().replace(' ', 'T')
        if (trimmed.isBlank()) return null
        val zone = ZoneId.systemDefault()

        runCatching { LocalDateTime.parse(trimmed) }.getOrNull()?.let {
            return it.atZone(zone).toInstant()
        }
        runCatching { LocalDate.parse(trimmed) }.getOrNull()?.let {
            // 只给日期：当成当天 09:00，比当成 00:00 更接近用户意图
            return it.atTime(9, 0).atZone(zone).toInstant()
        }
        return null
    }

    private companion object {
        const val RECORD_ID = "item"
        const val DEFAULT_DURATION_SECONDS = 3600L
    }
}
