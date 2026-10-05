package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.usecase.DeleteItemUseCase
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删除日程 / 待办 / 目标。
 *
 * ## 删除是**唯一会丢数据**的操作，所以它单独一个工具
 *
 * 新增和修改都是可逆的（改回来就行），删除不是。单独成工具的好处不只是
 * "确认卡上的标题更准确"，更是让**提示词里可以明确要求模型在删除前先确认对象**。
 *
 * ## 一次可以删多条，但每条**各自成一条记录**
 *
 * 变更预览卡是按记录勾选的（设计稿 `h6z5Uh`），所以 N 个条目就是 N 条记录，
 * 用户可以只勾其中几条。若把 N 条合并成一条，用户就只能"全删或全不删"。
 *
 * ## ⚠️ 删除会连带子节点
 *
 * [DeleteItemUseCase] 删的是一棵子树（目标下的子任务一起走）。
 * 预览里必须**把这条说出来** —— 用户看到"删 1 个目标"和
 * "连它的 5 个子任务一起删"是两个完全不同的决定。
 */
@Singleton
class DeleteItemsTool @Inject constructor(
    private val items: ItemRepository,
    private val deleteItem: DeleteItemUseCase,
) : ConfirmableTool {

    override val name = "delete_items"
    override val displayName = "删除条目"
    override val description =
        "删除日程、待办或目标。**这是不可撤销的操作**，用户确认后才会真正执行。" +
            "id 必须来自 get_items / get_item_detail 等工具的返回值 —— " +
            "**绝不要凭标题猜 id，也不要编造 id**。" +
            "调用前应当先用 get_items 查一遍，把要删的条目列给用户看。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "ids",
                    JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "要删除的条目 id 列表。id 从查询类工具拿")
                    },
                )
            },
        )
        put("required", JSONArray().put("ids"))
    }

    override suspend fun preview(args: JSONObject): List<ChangeRecord> {
        val ids = args.optJSONArray("ids").toStringList()
        if (ids.isEmpty()) return emptyList()

        return ids.mapNotNull { id ->
            val item = items.getById(id) ?: return@mapNotNull null
            val children = items.getChildren(id)
            ChangeRecord(
                // id 用条目 id 本身：稳定、唯一，用户勾选时靠它排除
                id = item.id,
                typeLabel = when (item.kind) {
                    ItemKind.EVENT -> "日程"
                    ItemKind.GOAL -> "目标"
                    else -> "待办"
                },
                operation = ChangeOperation.DELETE,
                subject = ChangeSubject.of(item.title, item.note),
                fields = buildList {
                    // 删除时把旧值全列出来：用户靠它确认"删的确实是这一条"
                    add(ChangeField("标题", item.title, null))
                    item.location?.takeIf { it.isNotBlank() }?.let { add(ChangeField("地点", it, null)) }
                    item.startAt?.let { add(ChangeField("时间", it.toString(), null)) }
                    item.note?.takeIf { it.isNotBlank() }?.let {
                        add(ChangeField("备注", it.take(60), null))
                    }
                    /*
                     * ⚠️ 子节点数量必须显示出来 ——
                     * 「删 1 个目标」和「连同 5 个子任务一起删」是两个不同的决定。
                     */
                    if (children.isNotEmpty()) {
                        add(ChangeField("连同子项", "${children.size} 条", null))
                    }
                },
            )
        }
    }

    override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
        val ids = args.optJSONArray("ids").toStringList()

        // 勾选是按**条目 id** 来的，所以这里直接按 id 过滤
        val toDelete = ids.filter { it in accepted }
        val skipped = ids.size - toDelete.size

        if (toDelete.isEmpty()) {
            return ToolOutcome(
                forModel = "用户取消了这次删除，没有删除任何内容。",
                forUser = ToolDetail(arguments = "${ids.size} 条", result = "已取消"),
            )
        }

        val deleted = ArrayList<String>()
        val missing = ArrayList<String>()
        for (id in toDelete) {
            val item = items.getById(id)
            if (item == null) {
                missing += id
                continue
            }
            deleteItem(id)
            deleted += item.title
        }

        val forModel = buildString {
            if (deleted.isNotEmpty()) appendLine("已删除：${deleted.joinToString("、")}")
            if (missing.isNotEmpty()) {
                appendLine("以下 id 找不到（可能已被删除）：${missing.joinToString("、")}")
            }
            if (skipped > 0) appendLine("用户取消了其中 $skipped 条的删除。")
        }.trim()

        return ToolOutcome(
            forModel = forModel.ifBlank { "没有任何条目被删除。" },
            forUser = ToolDetail(
                arguments = "${ids.size} 条",
                result = if (deleted.isEmpty()) "未删除" else "已删除 ${deleted.size} 条",
            ),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}
