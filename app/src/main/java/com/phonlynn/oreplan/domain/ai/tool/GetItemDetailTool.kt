package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.domain.usecase.LoadItemDetailUseCase
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 取一条条目的完整正文（笔记正文、备忘清单、附件）。
 *
 * ## 为什么需要它（列表类工具都给不了这个）
 *
 * `get_items` 只给标题和时刻 —— 那是**列表**需要的信息。而用户的笔记正文
 * 可能很长，全部塞进列表会让一次查询就吃掉几万 token（这正是用户担心的
 * 「上下文爆炸」）。所以列表只给摘要，**要看全文就按 id 单独取**。
 *
 * 需要 id 这一点是刻意的：它让"展开全文"成为一个**模型主动做的决定**，
 * 而不是每次都默认发生。
 *
 * ## ⚠️ 笔记正文在 `note_blocks`，不在 `Item.note`
 *
 * 用户反馈「查询到笔记后只能获取摘要，无法在锁定目标后查询完整笔记」。
 *
 * 上一版只读了 `Item.note` —— 但**笔记编辑器根本不写那个字段**
 *（见 `EditorV2ViewModel`：它只写 `note_blocks`）。于是模型拿到的是一个空串，
 * 自然只能说"这条笔记没有正文"。
 *
 * 所以这里同时读两处：
 *
 * | 来源 | 谁写的 |
 * |---|---|
 * | `note_blocks`（标题 + 正文，按 orderIndex 排） | **笔记编辑器**（用户手写的都在这里） |
 * | `Item.note` | AI 的写入工具、以及更早的版本 |
 *
 * 两处都为空时如实说"没有正文"，**不要编**。
 */
@Singleton
class GetItemDetailTool @Inject constructor(
    private val loadItemDetail: LoadItemDetailUseCase,
    private val noteBlocks: NoteBlockRepository,
    private val reminders: ReminderRepository,
) : AiTool {

    override val name = "get_item_detail"
    override val displayName = "读取条目详情"
    override val danger = ToolDanger.READ

    override val description =
        "按 id 取一条条目（日程 / 待办 / 目标 / 笔记）的**全部字段与完整正文**，" +
            "含笔记正文、备忘清单、附件名、优先级、重复规则、目标进度等。" +
            "**列表工具 get_items 只给摘要**（正文和备注都截断了），" +
            "需要看全文或某个没列出的字段时用这个。" +
            /*
             * ⚠️ 说明里点名 id 的来源。
             *
             * 原来只写「id 从那些工具的返回值里拿」—— 而 `get_items`
             * 当时**根本不返回 id**，模型照着做只会失败（然后可能编一个）。
             * 现在 `get_items` 每行都带 `id=…` 了，这里也要写清是它。
             *
             * 顺带说明白 **id 不能来自 read_board** —— 白板卡片是另一套数据，
             * 没有对应条目。这条不写清楚，模型很容易跨工具乱传。
             */
            "**id 只能来自 get_items 的返回值**（每行的 `id=…`）。" +
            "白板卡片的 id 不行 —— 卡片与条目是两套数据，传过来会找不到。" +
            "**不要凭猜测编造 id**。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "id",
                    JSONObject().apply {
                        put("type", "string")
                        put("description", "条目 id，从 get_items 等工具的返回值里取")
                    },
                )
            },
        )
        put("required", JSONArray().put("id"))
    }

    override suspend fun run(args: JSONObject): ToolOutcome {
        val id = args.optString("id").trim()
        if (id.isBlank()) {
            return ToolOutcome(forModel = "参数错误：缺少 id。")
        }

        val detail = loadItemDetail(id)
            ?: return ToolOutcome(
                forModel = "找不到 id 为 `$id` 的条目。可能已被删除 —— " +
                    "请用 get_items 重新查一遍拿最新 id，不要沿用旧 id。",
                forUser = ToolDetail(arguments = id, result = "未找到"),
            )

        val blocks = runCatching { noteBlocks.getOf(id) }.getOrDefault(emptyList())
        /*
         * 提醒：答「这条日程有提醒吗」。
         *
         * 提醒是按 `itemId` 挂的，所以直接查这条条目自己的提醒就够 ——
         * 不用管它是不是卡片（卡片提醒挂在另一条载体上，那条载体的 id
         * 是用户拿不到的，也就不会被问起）。
         */
        val reminderAt = runCatching {
            reminders.observeOf(id).first().firstOrNull()?.triggerAt
        }.getOrNull()

        val text = renderItemDetail(
            detail.item, blocks, detail.checklist, detail.attachments, reminderAt,
        )
        return ToolOutcome(
            forModel = text,
            forUser = ToolDetail(
                arguments = detail.item.title,
                result = buildString {
                    append(detail.item.title.length)
                    append(" 字标题")
                    if (blocks.isNotEmpty()) append(" · 笔记 ${blocks.size} 段")
                    if (detail.checklist.isNotEmpty()) append(" · 清单 ${detail.checklist.size} 项")
                    if (detail.attachments.isNotEmpty()) append(" · 附件 ${detail.attachments.size} 个")
                    if (reminderAt != null) append(" · 有提醒")
                },
            ),
        )
    }

}

/**
 * 把一条条目的全部内容渲染成给模型的文本。
 *
 * 抽成顶层函数是为了**能直接测** —— 这里正是用户反馈出问题的地方
 *（笔记正文读不到），而它本身是纯函数，不该被依赖注入挡住。
 *
 * ## ⚠️ 这里的原则是「**能读到一切**」（用户口径）
 *
 * 用户原话：
 *
 * > 「日程的备注等更多字段我估计他也读取不了，这不对，
 * > **ai 需要完全能够读取数据库里的任何细节**」
 *
 * 所以这个函数与 `get_items`（列表）**刻意不同**：
 *
 * | | 列表 `get_items` | 详情 `get_item_detail` |
 * |---|---|---|
 * | 目的 | 一眼扫过几十条 | 回答"这条到底是什么情况" |
 * | 字段 | 短、且用户会问的那几项 | **全部有意义的字段** |
 * | 长文本 | 截断 | **全文** |
 *
 * 只有**数据库内部结构**不列：`treePath` / `depth` / `orderIndex`
 *（它们描述"在树里的位置与排序"，不是内容）。其余一律给。
 */
internal fun renderItemDetail(
    item: Item,
    blocks: List<NoteBlock>,
    checklist: List<ChecklistEntry>,
    attachments: List<Attachment>,
    /** 提醒时刻（没有就 null）。由调用方查好传入 —— 这里保持纯函数。 */
    reminderAt: Instant? = null,
): String =
    buildString {
        appendLine("标题：${item.title}")
        appendLine("id：${item.id}")
        appendLine("类型：${item.kind.name}　状态：${item.status.name}")
        item.location?.takeIf { it.isNotBlank() }?.let { appendLine("地点：$it") }
        item.startAt?.let { appendLine("开始：$it") }
        item.endAt?.let { appendLine("结束：$it") }

        /*
         * ---- 时间安排相关的布尔与规则 ----
         *
         * `allDay` / `rrule` 以前没输出，于是模型看到一条 13:00 的日程时
         * 分不清它是"全天事件里附带的一个时刻"还是"真的只有这一小时"。
         */
        appendLine("全天：${if (item.allDay) "是" else "否"}")
        item.rrule?.takeIf { it.isNotBlank() }?.let {
            appendLine("重复规则：$it")
            item.rruleUntil?.let { until -> appendLine("重复截止：$until") }
        }
        item.planStartDay?.let { appendLine("计划开始日：$it") }
        item.planEndDay?.let { appendLine("计划结束日：$it") }

        /*
         * ---- 优先级 / 组织 / 外观 ----
         *
         * 优先级**明确写「无」**（与提醒的处理一致）：用户问"这条重要吗"，
         * 模型必须能答"没设优先级"，而不是"没看到相关信息"。
         */
        appendLine("优先级：${priorityLabel(item.priority)}")
        appendLine("置顶：${if (item.pinned) "是" else "否"}")
        appendLine("在今日页显示：${if (item.showOnToday) "是" else "否"}")
        item.category?.takeIf { it.isNotBlank() }?.let { appendLine("分类：$it") }
        item.colorTag?.takeIf { it.isNotBlank() }?.let { appendLine("色标：$it") }

        /*
         * ---- 层级 ----
         *
         * 父 id 要输出 —— 模型据此知道"这是某个目标下的子项"，
         * 回答"我在推进什么"时用得上。但 `treePath` / `depth` 不输出，
         * 那是数据库的内部结构。
         */
        item.parentId?.let { appendLine("父条目 id：$it") }
        item.groupId?.let { appendLine("分组 id：$it") }

        /*
         * ---- 目标类字段 ----
         *
         * 只在真的是目标（或有值）时输出，避免给普通待办挂一堆空格。
         */
        item.goalType?.let { appendLine("目标类型：${it.name}") }
        item.unit?.takeIf { it.isNotBlank() }?.let { appendLine("计量单位：$it") }
        item.targetValue?.let { appendLine("目标值：$it") }
        item.progress?.let { appendLine("进度：${(it * 100).toInt()}%") }
        item.stepOrderMode?.let { appendLine("步骤顺序：${it.name}") }
        if (item.goalType != null) appendLine("自动推进：${if (item.autoAdvance) "是" else "否"}")
        item.goalNote?.takeIf { it.isNotBlank() }?.let { appendLine("目标备注：$it") }

        /*
         * ---- 时间戳 ----
         *
         * 创建与完成时刻以前完全没给。用户问"这条什么时候建的"
         * 或者"这周完成了几个"时，模型需要它们。
         */
        appendLine("创建：${item.createdAt}")
        appendLine("修改：${item.updatedAt}")
        item.completedAt?.let { appendLine("完成：$it") }

        /*
         * 提醒：有就写时刻，没有就**明确写「无」**。
         *
         * 这里与 `get_items` 的列表刻意不同 —— 列表里逐条写"无提醒"是噪音，
         * 但用户问的是"这条有没有提醒"，**必须给出明确答案**；
         * 否则模型只能答"没看到提醒信息"，那等于没答。
         */
        appendLine(reminderAt?.let { "提醒：${ItemArgs.fullText(it)}" } ?: "提醒：无")

        /*
         * 笔记正文：先给编辑器写的那份（用户手写的都在这里），
         * 再给 `Item.note`（AI 或旧版本写的）。两份都有时都列出来 ——
         * 宁可多给一段，也不要让模型以为"就这些"。
         */
        if (blocks.isNotEmpty()) {
            appendLine("笔记正文（${blocks.size} 段）：")
            blocks.sortedBy { it.orderIndex }.forEach { b ->
                if (b.isEmpty) return@forEach
                b.heading.takeIf { it.isNotBlank() }?.let { appendLine("## $it") }
                b.body.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                appendLine()
            }
        }
        item.note?.takeIf { it.isNotBlank() }?.let {
            appendLine(if (blocks.isEmpty()) "正文：" else "备注字段：")
            appendLine(it)
        }
        if (blocks.isEmpty() && item.note.isNullOrBlank()) {
            appendLine("（这条没有正文）")
        }

        if (checklist.isNotEmpty()) {
            appendLine("备忘清单：")
            checklist.sortedBy { it.orderIndex }.forEach { e ->
                appendLine("  [${if (e.done) "x" else " "}] ${e.title}")
            }
        }
        if (attachments.isNotEmpty()) {
            appendLine("附件：" + attachments.joinToString("、") { it.displayName })
        }
    }.trim()

/**
 * 优先级的说法。
 *
 * **明确给「无」而不是留空** —— 与提醒的处理一致：用户问"这条重要吗"，
 * 模型必须能答"没设优先级"，而不是"我没看到相关信息"。
 */
private fun priorityLabel(priority: Int): String = when (priority) {
    3 -> "高"
    2 -> "中"
    1 -> "低"
    else -> "无"
}
