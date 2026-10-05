package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.repository.ItemRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 修改日程 / 待办 / 目标。
 *
 * ## 只改**显式给出**的字段
 *
 * 没传的字段保持原样。这是刻意的：模型很容易"顺手"补全它以为该有的值，
 * 而"把没提到的字段清空"是这类工具最常见的破坏方式。
 * 所以每个字段都区分「没传」和「传了」—— 见 [optTextOrNull] / [optBoolOrNull]。
 *
 * ## 时刻的解析与新建共用一套
 *
 * 见 [ItemArgs]。两边各写一份的话，同一个时刻"建出来"和"改出来"会不一致，
 * 而且不会报错，只会让用户发现差了几个小时。
 *
 * ## 时间字段的两种改法
 *
 * · `start` / `end` —— 改具体时刻
 * · `clear_time = true` —— **清掉时刻**，把它变成一条无时限待办
 *
 * 第二种必须有独立开关：`start` 传空字符串到底是"不动"还是"清空"没法从
 * JSON 里区分，让模型用一个显式布尔比让它猜要可靠。
 */
@Singleton
class UpdateItemTool @Inject constructor(
    private val items: ItemRepository,
) : ConfirmableTool {

    override val name = "update_item"
    override val displayName = "修改条目"
    override val description =
        "修改已存在的日程、待办或目标。**只改你传的字段**，没传的保持不变。" +
            "id 必须来自 get_items / get_item_detail 的返回值，不要凭标题猜。" +
            "改之前先用 get_items 拿到 id。" +
            "要把一条日程改成无时限的待办，传 clear_time=true，而不是传空的 start。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put("id", JSONObject().put("type", "string").put("description", "条目 id"))
                put("title", JSONObject().put("type", "string").put("description", "新标题"))
                put("note", JSONObject().put("type", "string").put("description", "新备注正文"))
                put(
                    "start",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "新开始时刻，YYYY-MM-DDTHH:mm。只给日期则按当天 09:00")
                    },
                )
                put("end", JSONObject().put("type", "string").put("description", "新结束时刻，格式同上"))
                put(
                    "clear_time",
                    JSONObject().apply {
                        put("type", "boolean")
                        put("description", "清掉时刻。true 时忽略 start/end，条目变成无时限待办")
                    },
                )
                put("location", JSONObject().put("type", "string").put("description", "新地点"))
                put(
                    "priority",
                    JSONObject().apply {
                        put("type", "integer")
                        put("description", "新优先级：0 无 / 1 低 / 2 中 / 3 高")
                    },
                )
                put(
                    "status",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("todo").put("doing").put("done").put("cancelled"))
                        put("description", "新状态")
                    },
                )
            },
        )
        put("required", JSONArray().put("id"))
    }

    /** 变更预览：逐字段列出旧值 → 新值。值没变的字段**不列**，避免噪音。 */
    override suspend fun preview(args: JSONObject): List<ChangeRecord> {
        val changes = computeChanges(args) ?: return emptyList()
        return listOf(
            ChangeRecord(
                id = changes.item.id,
                typeLabel = changes.typeLabel,
                operation = ChangeOperation.UPDATE,
                subject = ChangeSubject.of(changes.item.title, changes.item.note),
                fields = changes.fields,
            ),
        )
    }

    override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
        val changes = computeChanges(args)
            ?: return ToolOutcome(
                forModel = "找不到这条条目，或者没有任何字段需要修改。" +
                    "请用 get_items 确认 id 之后重试。",
            )

        if (changes.item.id !in accepted) {
            return ToolOutcome(
                forModel = "用户取消了这次修改，条目未改动。",
                forUser = ToolDetail(arguments = changes.item.title, result = "已取消"),
            )
        }

        items.update(changes.item.copy(updatedAt = Instant.now()))

        return ToolOutcome(
            forModel = "已修改「${changes.item.title}」：" +
                changes.fields.joinToString("；") { "${it.label} → ${it.new.orEmpty()}" } +
                "。id=${changes.item.id}",
            forUser = ToolDetail(
                arguments = changes.item.title,
                result = changes.fields.size.let { "已更新 $it 个字段" },
            ),
        )
    }

    /**
     * 算出「改后的条目」与「要显示的变更列表」。
     *
     * 两步在同一处算：分开算会让预览和执行有机会不一致 ——
     * 那意味着**用户确认的内容与实际写入的内容不同**，是这类界面最严重的问题。
     */
    private suspend fun computeChanges(args: JSONObject): Changes? {
        val id = args.optString("id").trim()
        if (id.isBlank()) return null
        val item = items.getById(id) ?: return null

        val fields = ArrayList<ChangeField>()

        fun <T> change(label: String, old: T?, next: T?, display: (T) -> String): T? {
            // 没传（null 且原本也是 null 时不动；传了才比较）
            if (next == null) return old
            if (old == next) return old
            fields += ChangeField(label, old?.let(display), display(next))
            return next
        }

        val clearTime = args.optBoolOrNull("clear_time") == true

        val newStart: Instant?
        val newEnd: Instant?
        if (clearTime) {
            if (item.startAt != null) {
                fields += ChangeField("时间", ItemArgs.rangeText(item.startAt, item.endAt), "无时刻")
            }
            newStart = null
            newEnd = null
        } else {
            val start = args.optTextOrNull("start")?.let { ItemArgs.parseInstant(it) }
            // 只给 start 不给 end 时，把 end 顺延同样时长，而不是留一个"结束早于开始"的条目
            val duration = item.startAt?.let { s -> item.endAt?.let { e -> e.epochSecond - s.epochSecond } }
                ?: DEFAULT_DURATION_SECONDS
            val end = args.optTextOrNull("end")?.let { ItemArgs.parseInstant(it) }
                ?: start?.plusSeconds(duration)

            if (start != null || end != null) {
                val s = start ?: item.startAt
                val e = end ?: item.endAt
                val oldText = ItemArgs.rangeText(item.startAt, item.endAt)
                val newText = ItemArgs.rangeText(s, e)
                if (oldText != newText) fields += ChangeField("时间", oldText.ifBlank { null }, newText)
            }
            newStart = start ?: item.startAt
            newEnd = end ?: item.endAt
        }

        val updated = item.copy(
            // 末尾的 `?: item.xxx` 运行时走不到（change 没传时就返回 old），
            // 只是为了让「非空字段」的类型成立。
            title = change("标题", item.title, args.optTextOrNull("title")) { it } ?: item.title,
            note = change("备注", item.note, args.optTextOrNull("note")) { it },
            location = change("地点", item.location, args.optTextOrNull("location")) { it },
            priority = change("优先级", item.priority.takeIf { it > 0 }, args.optIntOrNull("priority")) {
                PRIORITY_LABELS[it.coerceIn(0, 3)]
            } ?: 0,
            status = change("状态", item.status, args.optTextOrNull("status")?.let(ItemArgs::parseStatus)) {
                ItemArgs.statusLabel(it)
            } ?: item.status,
            startAt = newStart,
            endAt = newEnd,
        )

        if (fields.isEmpty()) return null

        return Changes(
            item = updated,
            typeLabel = when (updated.kind) {
                ItemKind.EVENT -> "日程"
                ItemKind.GOAL -> "目标"
                else -> "待办"
            },
            fields = fields,
        )
    }

    private data class Changes(
        val item: Item,
        val typeLabel: String,
        val fields: List<ChangeField>,
    )

    /** 取一个可选整数。`0` 是合法值（"无优先级"），所以不能用默认值区分。 */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private companion object {
        const val DEFAULT_DURATION_SECONDS = 3600L
        val PRIORITY_LABELS = listOf("无", "低", "中", "高")
    }
}
