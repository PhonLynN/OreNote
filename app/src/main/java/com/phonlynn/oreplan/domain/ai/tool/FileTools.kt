package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 列出**尚未安排**的文件。
 *
 * ## 用户口径（这个工具存在的理由）
 *
 * > 「模型能读取的文件类型很有限，遇到这种，就不发给 ai，而是放入和
 * > 其他附件在一起的数据库，**给 ai 文件索引**，让 ai 根据用户指令安排
 * > 这些附件的位置，ai 负责给对应位置的对象添加对应的文件索引。」
 *
 * 所以这里**只给索引**（名字 / 类型 / 大小 / 时间 / id），
 * **不给文件内容** —— 模型不需要读懂它，只需要知道"有这么个文件"，
 * 然后按用户的指令决定它挂到哪儿。
 *
 * ## 为什么"不给内容"反而更强
 *
 * pdf / docx / zip 这类模型读不了，硬塞进去要么报错、要么浪费上下文。
 * 而**挂载关系本身是纯数据操作**，跟文件是什么格式完全无关 ——
 * 把"读内容"和"安排位置"解耦之后，任何类型的文件都能被 AI 安排。
 */
@Singleton
class ListFilesTool @Inject constructor(
    private val attachments: AttachmentRepository,
) : AiTool {

    override val name = "list_files"
    override val displayName = "查看待整理文件"
    override val danger = ToolDanger.READ

    override val description =
        "列出用户从对话里添加、**还没安排到任何对象上**的文件。" +
            "用户问「有哪些文件还没整理」「我刚加的那个文件放哪了」时用它。" +
            "返回每个文件的 id、文件名、类型、大小 —— **不含文件内容**" +
            "（内容模型读不了，也不需要读）。" +
            "要把它挂到某个日程/待办/卡片上，用 attach_file。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
        put("required", JSONArray())
    }

    override suspend fun run(args: JSONObject): ToolOutcome {
        val all = attachments.getAll()
        val pending = all.filter { it.ownerType == AttachmentOwner.UNASSIGNED }

        val body = if (pending.isEmpty()) {
            "没有待整理的文件。"
        } else {
            buildString {
                appendLine("待整理文件 ${pending.size} 个：")
                pending.sortedBy { it.createdAt }.forEach { a ->
                    appendLine("- ${describeFile(a)}")
                }
            }.trim()
        }

        return ToolOutcome(
            forModel = body,
            forUser = ToolDetail(
                arguments = "待整理文件",
                result = if (pending.isEmpty()) "没有待整理" else "${pending.size} 个",
            ),
        )
    }
}

/**
 * 把文件挂到某个对象上（**这就是"安排位置"**）。
 *
 * ## 它改的是附件的归属字段
 *
 * ```
 * Attachment(ownerType = UNASSIGNED, ownerId = "")
 *                    ↓  这个工具
 * Attachment(ownerType = ITEM, ownerId = "item-abc")
 * ```
 *
 * 文件本身**不动**（还在原路径），只是索引行换了个归属 ——
 * 所以这个操作是廉价且可逆的。
 *
 * ## 为什么目标用"类型 + id"而不是让模型猜
 *
 * 目标必须是 `item` / `board_card` 二选一，且 id 要存在。
 * 校验放在 `preview` 里：**用户看到预览之前**就知道 id 对不对，
 * 而不是点了应用才发现找不到对象。
 */
@Singleton
class AttachFileTool @Inject constructor(
    private val attachments: AttachmentRepository,
    private val items: ItemRepository,
    private val board: BoardRepository,
) : ConfirmableTool {

    override val name = "attach_file"
    override val displayName = "安排文件位置"
    override val description =
        "把一个**待整理的文件**挂到指定的日程 / 待办 / 白板卡片上。" +
            "file_id 从 list_files 拿，target_id 从 get_items 或 read_board 拿。" +
            "**不要凭猜测编造 id**。" +
            "挂上去之后文件就不在「待整理」里了，可以在那个对象上打开。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put("file_id", JSONObject().put("type", "string").put("description", "文件 id（来自 list_files）"))
                put(
                    "target_type",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("item").put("board_card"))
                        put("description", "挂到哪类对象上：item=日程/待办/目标/笔记，board_card=白板卡片")
                    },
                )
                put("target_id", JSONObject().put("type", "string").put("description", "目标对象的 id"))
            },
        )
        put("required", JSONArray().put("file_id").put("target_type").put("target_id"))
    }

    /** 解析并校验一次。第一个元素为 null 表示参数不合法，理由在第二个元素。 */
    private class Resolved(
        val file: Attachment,
        val ownerType: AttachmentOwner,
        val targetLabel: String,
        val targetName: String,
    )

    private suspend fun resolve(args: JSONObject): Pair<Resolved?, String> {
        val fileId = args.optString("file_id").takeIf { it.isNotBlank() }
            ?: return null to "缺少 file_id"
        val targetType = args.optString("target_type").takeIf { it.isNotBlank() }
            ?: return null to "缺少 target_type"
        val targetId = args.optString("target_id").takeIf { it.isNotBlank() }
            ?: return null to "缺少 target_id"

        val file = attachments.findById(fileId)
            ?: return null to "找不到文件 `$fileId`（先用 list_files 确认）"

        return when (targetType) {
            "item" -> {
                // ⚠️ 方法名是 `getById`（不是 findById）；`board` 那边只有
                // Flow 形式的 `observeCard`，取首个值即可（工具调用是一次性的）。
                val item = items.getById(targetId)
                    ?: return null to "找不到条目 `$targetId`（先用 get_items 确认）"
                Resolved(file, AttachmentOwner.ITEM, "条目", item.title) to ""
            }

            "board_card" -> {
                val card = board.observeCard(targetId).first()
                    ?: return null to "找不到卡片 `$targetId`（先用 read_board 确认）"
                Resolved(
                    file = file,
                    ownerType = AttachmentOwner.BOARD_CARD,
                    targetLabel = "卡片",
                    targetName = card.title?.takeIf { it.isNotBlank() } ?: "（无标题）",
                ) to ""
            }

            else -> null to "target_type 只能是 item 或 board_card，收到 `$targetType`"
        }
    }

    override suspend fun preview(args: JSONObject): List<ChangeRecord> {
        val (resolved, reason) = resolve(args)
        if (resolved == null) {
            /*
             * 参数不合法时**不产出预览** —— 产出一条空记录让用户点"应用"
             * 是误导：应用之后什么也不会发生。
             *
             * 模型会从 apply 的返回里读到错误理由（见 lastError），从而自己修正。
             */
            lastError = reason
            return emptyList()
        }
        lastError = ""

        return listOf(
            ChangeRecord(
                id = resolved.file.id,
                typeLabel = "文件",
                operation = ChangeOperation.UPDATE,
                subject = resolved.file.displayName,
                fields = listOf(
                    ChangeField("挂到", null, "${resolved.targetLabel}「${resolved.targetName}」"),
                    ChangeField("文件名", null, resolved.file.displayName),
                    ChangeField("类型", null, resolved.file.mimeType),
                ),
            ),
        )
    }

    override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
        val (resolved, reason) = resolve(args)
        if (resolved == null) return ToolOutcome(forModel = "安排失败：$reason")

        // 没勾选（用户在界面上取消了）→ 什么都不做，如实告知
        if (resolved.file.id !in accepted) {
            return ToolOutcome(
                forModel = "用户取消了这次安排，文件仍留在「待整理」里。",
                forUser = ToolDetail(arguments = resolved.file.displayName, result = "已取消"),
            )
        }

        /*
         * 只改归属两个字段，其余原样 —— 文件在磁盘上的位置**不动**。
         *
         * 这很关键：`storedPath` 是按 id 生成的（`attachments/<id>/<名字>`），
         * 与 owner 无关。所以换归属是**改一行索引**，不需要搬文件。
         */
        attachments.upsert(
            resolved.file.copy(
                ownerType = resolved.ownerType,
                ownerId = args.optString("target_id"),
            ),
        )

        return ToolOutcome(
            forModel = "已把「${resolved.file.displayName}」挂到${resolved.targetLabel}" +
                "「${resolved.targetName}」上（id=${args.optString("target_id")}）。" +
                "文件已不在待整理列表里。",
            forUser = ToolDetail(
                arguments = resolved.file.displayName,
                result = "已挂到${resolved.targetLabel}",
            ),
        )
    }

    /**
     * 上次 [preview] 解析失败的理由。
     *
     * `preview` 返回空列表时界面不会渲染任何东西，模型也就无从知道
     * "为什么没挂上"。把理由记在这里，[apply] 时一并返回给模型，
     * 让它能自我修正（比如改用正确的 id）。
     *
     * ⚠️ 这是个**有状态的**字段，而工具是单例。并发调用会互相覆盖 ——
     * 但工具调用在 `SendChatMessage` 里本来就是**串行**的，
     * 而且它只用于"出错时给个理由"，覆盖的后果是理由不准，不会写坏数据。
     */
    private var lastError: String = ""
}

/**
 * 一行文件描述。
 *
 * 只要**索引**：id / 名字 / 类型 / 大小 / 添加时间。
 * 不含内容 —— 见 [ListFilesTool] 的说明。
 */
internal fun describeFile(a: Attachment): String {
    val size = when {
        a.sizeBytes >= 1024 * 1024 -> "%.1f MB".format(a.sizeBytes / 1024.0 / 1024.0)
        a.sizeBytes >= 1024 -> "${a.sizeBytes / 1024} KB"
        else -> "${a.sizeBytes} B"
    }
    val kind = when {
        a.isImage -> "图片"
        a.isAudio -> "音频"
        else -> a.mimeType
    }
    return "${a.displayName}（id=${a.id}，$kind，$size）"
}
