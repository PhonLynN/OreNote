package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardColors
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/*
 * 白板的三个写工具放在**同一个文件**里。
 *
 * 与"一个工具一个文件"的约定不冲突：那条约定是为了**加新工具不用改别处**，
 * 而这三个共享同一套私有逻辑（把颜色名转成色值、按名字找标签、把卡片描述成字段列表）。
 * 拆成三个文件就必须把那些提成 internal —— 那是为了形式牺牲封装。
 * 将来加**新的**工具仍然是一个新文件。
 *
 * 三者都要用户确认（`ConfirmableTool`），见 `AiTool.kt` 里关于危险级别的说明。
 */

// ---------------------------------------------------------------- 新增

/**
 * 新建白板卡片。
 *
 * ⚠️ **不暴露"卡片类型"**。用户 2026-10-03 明确：「白板只有一种，四种类型都是
 * 哪年的老黄历了」—— 类型选择器与类型徽章都是死代码，已删。
 * 新卡固定 [BoardCardType.QUICK]。`BoardCardType` 只是持久化残留，
 * 不要把它做成工具参数，否则模型会开始问用户"要哪种类型"。
 */
@Singleton
class CreateBoardCardTool @Inject constructor(
    private val board: BoardRepository,
    private val appSettings: AppSettingsStore,
    private val items: ItemRepository,
    private val reminders: ReminderRepository,
) : ConfirmableTool {

    override val name = "create_board_card"
    override val displayName = "新建卡片"
    override val description =
        "在白板上新建一张卡片。用户说「记一下…」「把这段存到白板」时用它。" +
            "**卡片没有类型之分**，不要问用户要哪种类型。" +
            "正文可以是多行。标签只有一个（白板是单标签关系）。" +
            "用户提到「半宽」「整宽」「置顶」「保密」「不显示日期」时，" +
            "对应的参数见下面各项 —— **能表达就一定要表达出来**，" +
            "不要建一张普通卡片再让用户自己去改。"

    /**
     * 参数**尽量覆盖卡片模型上的可选字段**。
     *
     * ## ⚠️ 这里曾经只暴露了 4 个字段（用户报的）
     *
     * 用户口径：「我如果想让 AI 创建一张卡片，可以，但是我没法让 AI 创建一张
     * **半宽的置顶保密卡片**」。
     *
     * 而 `BoardCard` 模型上这些字段**一直都有**（`widthMode` / `pinned` /
     * `secret` / `secretHint` / `showDate` / `imageLayout`），只是工具的
     * `parameters` 里没写 —— 模型看不到的参数，它就不可能填。
     *
     * 后果是"能建卡但建不全"：用户得自己进白板再改一遍。
     * 这类"模型能做的事比它以为的少"是工具设计里最隐蔽的一种缺失 ——
     * 没有报错、没有异常，只是能力被**悄悄阉割**了。
     *
     * ## 为什么没加 `autoPin`（动态置顶）
     *
     * 它的 `Recurring` 形态要一个 RRULE 子集，让模型生成 RRULE 出错率很高，
     * 而错了之后表现为"卡片在该浮起的时候没浮起" —— 极难排查。
     * 真需要的话再单独做一个参数化程度更高的工具，不要塞进这里。
     */
    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put("title", JSONObject().put("type", "string").put("description", "标题，可省略"))
                put("body", JSONObject().put("type", "string").put("description", "正文"))
                put(
                    "color",
                    JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "卡片颜色：白/绿/橙/紫/粉/灰，或 #RRGGBB。省略则自动分配",
                        )
                    },
                )
                put("tag", JSONObject().put("type", "string").put("description", "标签名（只支持一个）"))
                put(
                    "width",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("half").put("full").put("auto"))
                        put(
                            "description",
                            "卡片宽度：half=半宽（一行两张）、full=整宽、auto=自动。" +
                                "用户说「半宽」「占半行」用 half；说「整宽」「占满」用 full；没说就别传",
                        )
                    },
                )
                put(
                    "pinned",
                    JSONObject().apply {
                        put("type", "boolean")
                        put("description", "是否置顶。用户说「置顶」「顶上去」时传 true")
                    },
                )
                put(
                    "secret",
                    JSONObject().apply {
                        put("type", "boolean")
                        put(
                            "description",
                            "是否保密卡。true 时主页只显示一个模糊块（内容不直接可见）。" +
                                "用户说「保密」「别让人看到」「私密」时传 true",
                        )
                    },
                )
                put(
                    "secret_hint",
                    JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "保密卡在主页模糊块上显示的**暗号文案**（不是密码，只是给自己的提醒）。" +
                                "只在 secret=true 时有意义；不传则显示「已隐藏」",
                        )
                    },
                )
                put(
                    "show_date",
                    JSONObject().apply {
                        put("type", "boolean")
                        put("description", "卡片底部是否显示创建日期。默认 true。用户说「不显示日期」时传 false")
                    },
                )
                put(
                    "image_layout",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("fill").put("grid"))
                        put(
                            "description",
                            "有图片时的排布：fill=一张大图横向铺满、grid=缩略网格（一排 3 个）。" +
                                "只在卡片带图时有意义",
                        )
                    },
                )
                put(
                    "remind_at",
                    JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "给这张卡片设提醒的**绝对时刻**，格式 `2026-10-04T15:00`。" +
                                "用户说「明天九点提醒我看这张卡」时填这里。" +
                                "⚠️ 卡片没有开始时刻，所以**只能给绝对时刻** —— " +
                                "用户说「提前多久」时先算出时刻再填",
                        )
                    },
                )
            },
        )
        put("required", JSONArray().put("body"))
    }

    override suspend fun preview(args: JSONObject): List<ChangeRecord> {
        val title = args.optString("title").trim().takeIf { it.isNotBlank() }
        val body = args.optString("body").trim()
        val tag = args.optString("tag").trim().takeIf { it.isNotBlank() }
        val flags = cardFlagsOf(args)

        return listOf(
            ChangeRecord(
                id = RECORD_ID,
                typeLabel = "卡片",
                operation = ChangeOperation.CREATE,
                subject = ChangeSubject.of(title, body),
                fields = buildList {
                    title?.let { add(ChangeField("标题", null, it)) }
                    add(ChangeField("内容", null, body))
                    tag?.let { add(ChangeField("标签", null, it)) }
                    boardColorOf(args.optString("color"))?.let { add(ChangeField("卡片颜色", null, it.first)) }
                    /*
                     * 这几个是**一眼要看见**的：用户确认的就是"半宽置顶保密卡片"，
                     * 预览里不写出来的话，他没法在写入前核对。
                     */
                    widthLabelOf(args.optString("width"))?.let { add(ChangeField("宽度", null, it)) }
                    if (flags.pinned) add(ChangeField("置顶", null, "是"))
                    if (flags.secret) {
                        add(
                            ChangeField(
                                "保密",
                                null,
                                flags.secretHint?.let { "是（暗号：$it）" } ?: "是",
                            ),
                        )
                    }
                    if (!flags.showDate) add(ChangeField("显示日期", null, "否"))
                    imageLayoutLabelOf(args.optString("image_layout"))?.let {
                        add(ChangeField("图片排布", null, it))
                    }
                    // 提醒也要在确认前看见 —— 否则用户不知道这张卡会不会响
                    args.optTextOrNull("remind_at")?.let { text ->
                        ItemArgs.parseInstant(text)?.let {
                            add(ChangeField("提醒", null, ItemArgs.fullText(it)))
                        }
                    }
                },
            ),
        )
    }

    override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
        if (RECORD_ID !in accepted) {
            return ToolOutcome(
                forModel = "用户取消了这次新建，没有写入任何内容。",
                forUser = ToolDetail(arguments = args.optString("title"), result = "已取消"),
            )
        }

        val body = args.optString("body").trim()
        if (body.isBlank()) {
            return ToolOutcome(forModel = "参数错误：body 不能为空。")
        }

        val now = Instant.now()
        val color = boardColorOf(args.optString("color"))?.second ?: BoardColors.randomPreset()
        val flags = cardFlagsOf(args)
        val card = BoardCard(
            id = Ids.newId(),
            type = BoardCardType.QUICK,
            title = args.optString("title").trim().takeIf { it.isNotBlank() },
            body = body,
            color = color,
            pinned = flags.pinned,
            secret = flags.secret,
            secretHint = flags.secretHint,
            widthMode = flags.widthMode,
            showDate = flags.showDate,
            imageLayout = flags.imageLayout,
            createdAt = now,
            updatedAt = now,
            sortIndex = nextSortIndex(),
        )

        val tagId = resolveTagId(args.optString("tag").trim())
        // 新建时清单为空列表（本来就没有），标签按解析结果
        board.replaceCardWithRelations(card, emptyList(), listOfNotNull(tagId))

        /*
         * 挂提醒。
         *
         * ⚠️ 走 `CardReminders` —— 与卡片编辑页**同一个函数**。
         * 卡片自己挂不了提醒（调度器只认条目），得先建一条不可见的载体条目
         * `card_alarm_{cardId}`。那套逻辑只此一份，两边不会漂移。
         *
         * 设不上时（时刻已过去）如实说，不装作设好了：
         * 卡片已经建好了，把整次创建判为失败反而更糟。
         */
        val reminderNote = attachReminder(args, card.id)

        return ToolOutcome(
            forModel = "已新建卡片${card.title?.let { "「$it」" } ?: ""}${flags.describe()}" +
                "$reminderNote。id=${card.id}",
            forUser = ToolDetail(
                arguments = card.title ?: body.take(20),
                result = if (reminderNote.isBlank()) "已写入白板" else "已写入白板，并设了提醒",
            ),
        )
    }

    /**
     * 按 `remind_at` 挂提醒，返回一句补进工具结果的话。
     *
     * 三种情况都要说清，否则模型会以为提醒已经设好了。
     */
    private suspend fun attachReminder(args: JSONObject, cardId: String): String {
        val text = args.optTextOrNull("remind_at") ?: return ""
        val at = ItemArgs.parseInstant(text)
            ?: return "（**提醒没设上**：`$text` 不是能识别的时刻格式，" +
                "要用 `2026-10-04T15:00` 这种）"

        if (!Reminders.isFuture(at)) {
            return "（**提醒没设上**：那个时刻已经过去了）"
        }

        CardReminders.set(items, reminders, cardId, title = args.optString("title"), at = at)
        return "，已设提醒（${ItemArgs.fullText(at)}）"
    }

    /** 新卡落在顶部还是底部，跟用户在设置里的选择走。 */
    private suspend fun nextSortIndex(): Double {
        val position = appSettings.settings.first().boardNewCardPosition
        val cards = board.observeCards().first()
        return if (position == "BOTTOM") {
            (cards.maxOfOrNull { it.sortIndex } ?: 0.0) + 1.0
        } else {
            (cards.minOfOrNull { it.sortIndex } ?: 0.0) - 1.0
        }
    }

    private suspend fun resolveTagId(name: String): String? {
        if (name.isBlank()) return null
        return board.observeTags().first().firstOrNull { it.name == name }?.id
    }

    private companion object {
        const val RECORD_ID = "card"
    }
}

// ---------------------------------------------------------------- 修改

/**
 * 修改白板卡片。
 *
 * ## ⚠️ 只能改**显式给出的**字段
 *
 * 没传的字段保持原样。这与 [BoardRepository.replaceCardWithRelations] 的
 * `null = 本次不改动该关联` 是同一套思路：把"忘了传"从**静默清空**变成**显式不动**。
 *
 * ## ⚠️ 关联必须传 null，不能传空列表
 *
 * `replaceCardWithRelations` 会**先删再重建**清单项与标签关联 ——
 * 仓库注释里写着一次真实事故（拖动排序清空所有卡片标签）。
 * 所以这里改卡片自身字段时，两个关联参数一律传 `null`（不动），
 * 而不是 `emptyList()`（清空）。
 */
@Singleton
class UpdateBoardCardTool @Inject constructor(
    private val board: BoardRepository,
    private val items: ItemRepository,
    private val reminders: ReminderRepository,
) : ConfirmableTool {

    override val name = "update_board_card"
    override val displayName = "修改卡片"
    override val description =
        "修改已存在的白板卡片。**只改你传的字段**，没传的保持不变。" +
            "id 必须来自 read_board 的返回值，不要凭标题猜。" +
            "改之前先用 read_board 拿到 id 并确认是哪一张。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put("id", JSONObject().put("type", "string").put("description", "卡片 id"))
                put("title", JSONObject().put("type", "string").put("description", "新标题"))
                put("body", JSONObject().put("type", "string").put("description", "新正文"))
                put("color", JSONObject().put("type", "string").put("description", "新颜色：白/绿/橙/紫/粉/灰 或 #RRGGBB"))
                put("pinned", JSONObject().put("type", "boolean").put("description", "是否置顶"))
                put("archived", JSONObject().put("type", "boolean").put("description", "是否归档"))
                /*
                 * ⚠️ 这几个是补上的：建卡能设、改卡改不了是最招人烦的一种不对称。
                 * 工具集里凡是"创建时可填的字段"，修改时都应当能改。
                 */
                put(
                    "width",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("half").put("full").put("auto"))
                        put("description", "卡片宽度：half=半宽、full=整宽、auto=跟随默认")
                    },
                )
                put("secret", JSONObject().put("type", "boolean").put("description", "是否保密卡"))
                put(
                    "secret_hint",
                    JSONObject().put("type", "string")
                        .put("description", "保密卡的暗号文案（只在 secret=true 时有意义）"),
                )
                put("show_date", JSONObject().put("type", "boolean").put("description", "是否显示创建日期"))
                put(
                    "image_layout",
                    JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("fill").put("grid"))
                        put("description", "图片排布：fill=大图铺满、grid=缩略网格")
                    },
                )
                put(
                    "remind_at",
                    JSONObject().apply {
                        put("type", "string")
                        put(
                            "description",
                            "改提醒时刻（绝对时刻，如 `2026-10-04T15:00`）。" +
                                "**不要提醒写 `none`**；不传则不动现有的提醒。" +
                                "⚠️ 卡片没有开始时刻，所以只能给绝对时刻",
                        )
                    },
                )
            },
        )
        put("required", JSONArray().put("id"))
    }

    override suspend fun preview(args: JSONObject): List<ChangeRecord> {
        val card = findCard(args.optString("id")) ?: return emptyList()

        val fields = buildList {
            args.optStringOrNull("title")?.let { add(ChangeField("标题", card.title, it)) }
            args.optStringOrNull("body")?.let { add(ChangeField("内容", card.body, it)) }
            args.optStringOrNull("color")?.let {
                boardColorOf(it)?.let { (label, _) ->
                    add(ChangeField("卡片颜色", boardColorLabelOf(card.color), label))
                }
            }
            args.optBooleanOrNull("pinned")?.let {
                add(ChangeField("置顶", yesNo(card.pinned), yesNo(it)))
            }
            args.optBooleanOrNull("archived")?.let {
                add(ChangeField("归档", yesNo(card.archived), yesNo(it)))
            }
            args.optStringOrNull("width")?.let {
                add(
                    ChangeField(
                        "宽度",
                        widthLabelOf(card.widthMode) ?: "自动",
                        widthLabelOf(widthModeOf(it)) ?: "自动",
                    ),
                )
            }
            args.optBooleanOrNull("secret")?.let {
                add(ChangeField("保密", yesNo(card.secret), yesNo(it)))
            }
            args.optStringOrNull("secret_hint")?.let {
                add(ChangeField("暗号文案", card.secretHint, it))
            }
            args.optBooleanOrNull("show_date")?.let {
                add(ChangeField("显示日期", yesNo(card.showDate), yesNo(it)))
            }
            args.optStringOrNull("image_layout")?.let {
                add(
                    ChangeField(
                        "图片排布",
                        imageLayoutLabelOf(card.imageLayout) ?: "跟随默认",
                        imageLayoutLabelOf(imageLayoutOf(it)) ?: "跟随默认",
                    ),
                )
            }
            // 提醒：改没改、改成几号，都要在确认前看见
            args.optTextOrNull("remind_at")?.let { text ->
                val newAt = ItemArgs.parseInstant(text)
                add(
                    ChangeField(
                        "提醒",
                        CardReminders.triggerOf(reminders, card.id)?.let { ItemArgs.fullText(it) } ?: "无",
                        newAt?.let { ItemArgs.fullText(it) } ?: "无",
                    ),
                )
            }
        }
        if (fields.isEmpty()) return emptyList()

        return listOf(
            ChangeRecord(
                id = card.id,
                typeLabel = "卡片",
                operation = ChangeOperation.UPDATE,
                subject = ChangeSubject.of(card.title, card.body),
                fields = fields,
            ),
        )
    }

    override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
        val card = findCard(args.optString("id"))
            ?: return ToolOutcome(
                forModel = "找不到这张卡片，可能已被删除。请用 read_board 重新查一遍。",
            )
        if (card.id !in accepted) {
            return ToolOutcome(
                forModel = "用户取消了这次修改，卡片未改动。",
                forUser = ToolDetail(arguments = card.title.orEmpty(), result = "已取消"),
            )
        }

        val updated = card.copy(
            title = args.optStringOrNull("title") ?: card.title,
            body = args.optStringOrNull("body") ?: card.body,
            color = args.optStringOrNull("color")?.let { boardColorOf(it)?.second } ?: card.color,
            pinned = args.optBooleanOrNull("pinned") ?: card.pinned,
            archived = args.optBooleanOrNull("archived") ?: card.archived,
            /*
             * `width` / `image_layout` 传 `auto` 或 `跟随默认` 时，`xxxOf()` 返回 null
             * —— 那正好是"跟随默认"的存储值，所以这里直接赋值是对的。
             */
            widthMode = args.optStringOrNull("width")?.let { widthModeOf(it) } ?: card.widthMode,
            secret = args.optBooleanOrNull("secret") ?: card.secret,
            secretHint = args.optStringOrNull("secret_hint") ?: card.secretHint,
            showDate = args.optBooleanOrNull("show_date") ?: card.showDate,
            imageLayout = args.optStringOrNull("image_layout")?.let { imageLayoutOf(it) } ?: card.imageLayout,
            updatedAt = Instant.now(),
        )

        /*
         * 关联传 null = **本次不改动**。
         * 传 emptyList() 会把这张卡的清单项与标签全部删掉 —— 仓库注释里
         * 记着因为这个丢过一次数据。
         */
        board.replaceCardWithRelations(updated, todoItems = null, tagIds = null)

        /*
         * 提醒：`none` 去掉、给了时刻就改、没传就不动。
         *
         * 走 `CardReminders`（与卡片编辑页同一个函数），所以"改提醒"在这两处
         * 的行为完全一致 —— 包括"先把旧载体删干净再重建"那一步。
         */
        val reminderNote = args.optTextOrNull("remind_at")?.let { text ->
            if (text.equals("none", ignoreCase = true) || text == "不提醒") {
                CardReminders.clear(items, reminders, updated.id)
                "（已去掉提醒）"
            } else {
                val at = ItemArgs.parseInstant(text)
                when {
                    at == null -> "（**提醒没改**：`$text` 不是能识别的时刻格式）"
                    !Reminders.isFuture(at) -> "（**提醒没改**：那个时刻已经过去了）"
                    else -> {
                        CardReminders.set(items, reminders, updated.id, updated.title, at)
                        "（提醒改到 ${ItemArgs.fullText(at)}）"
                    }
                }
            }
        }.orEmpty()

        return ToolOutcome(
            forModel = "已更新卡片${updated.title?.let { "「$it」" } ?: ""}$reminderNote。id=${card.id}",
            forUser = ToolDetail(arguments = card.title ?: card.id, result = "已更新"),
        )
    }

    private suspend fun findCard(id: String): BoardCard? {
        if (id.isBlank()) return null
        return board.observeCards().first().firstOrNull { it.id == id }
    }
}

// ---------------------------------------------------------------- 删除

/**
 * 删除白板卡片。
 *
 * 与日程/待办的删除一样，是**不可撤销**的，所以单独一个工具、
 * 每个 id 各自成一条记录（用户可以只勾其中几张）。
 */
@Singleton
class DeleteBoardCardsTool @Inject constructor(
    private val board: BoardRepository,
) : ConfirmableTool {

    override val name = "delete_board_card"
    override val displayName = "删除卡片"
    override val description =
        "删除白板卡片。**不可撤销**，用户确认后才会真正执行。" +
            "id 必须来自 read_board 的返回值。" +
            "卡片有「归档」这个更温和的选项 —— 用户只是想让它别出现在墙上时，" +
            "优先提议归档而不是删除。"

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
                        put("description", "要删除的卡片 id 列表")
                    },
                )
            },
        )
        put("required", JSONArray().put("ids"))
    }

    override suspend fun preview(args: JSONObject): List<ChangeRecord> {
        val ids = args.optJSONArray("ids").toStringList()
        if (ids.isEmpty()) return emptyList()
        val cards = board.observeCards().first()

        return ids.mapNotNull { id ->
            val card = cards.firstOrNull { it.id == id } ?: return@mapNotNull null
            ChangeRecord(
                id = card.id,
                typeLabel = "卡片",
                operation = ChangeOperation.DELETE,
                subject = ChangeSubject.of(card.title, card.body),
                fields = buildList {
                    add(ChangeField("标题", card.title ?: "（无标题）", null))
                    card.body?.takeIf { it.isNotBlank() }?.let {
                        add(ChangeField("内容", it.replace('\n', ' ').take(60), null))
                    }
                },
            )
        }
    }

    override suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome {
        val ids = args.optJSONArray("ids").toStringList()
        val toDelete = ids.filter { it in accepted }
        val skipped = ids.size - toDelete.size

        if (toDelete.isEmpty()) {
            return ToolOutcome(
                forModel = "用户取消了这次删除，没有删除任何卡片。",
                forUser = ToolDetail(arguments = "${ids.size} 张", result = "已取消"),
            )
        }

        val cards = board.observeCards().first().associateBy { it.id }
        val deleted = ArrayList<String>()
        val missing = ArrayList<String>()
        for (id in toDelete) {
            val card = cards[id]
            if (card == null) {
                missing += id
                continue
            }
            board.deleteCard(id)
            deleted += card.title ?: card.body?.take(20) ?: id
        }

        val forModel = buildString {
            if (deleted.isNotEmpty()) appendLine("已删除卡片：${deleted.joinToString("、")}")
            if (missing.isNotEmpty()) appendLine("以下 id 找不到（可能已被删除）：${missing.joinToString("、")}")
            if (skipped > 0) appendLine("用户取消了其中 $skipped 张的删除。")
        }.trim()

        return ToolOutcome(
            forModel = forModel.ifBlank { "没有任何卡片被删除。" },
            forUser = ToolDetail(
                arguments = "${ids.size} 张",
                result = if (deleted.isEmpty()) "未删除" else "已删除 ${deleted.size} 张",
            ),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

// ---------------------------------------------------------------- 共用

/**
 * 卡片上那几组"可选开关"的解析结果。
 *
 * 打包成一个类是因为建卡与改卡**都要用同一套解析** —— 分成两处写迟早会漂移，
 * 而漂移的表现是"建卡时的半宽和改卡时的半宽不是一回事"。
 */
internal data class CardFlags(
    /** null = 自动 / "half" / "full"。 */
    val widthMode: String?,
    val pinned: Boolean,
    val secret: Boolean,
    val secretHint: String?,
    val showDate: Boolean,
    /** null = 跟全局默认 / "fill" / "grid"。 */
    val imageLayout: String?,
) {
    /** 回给模型的一句话补充，把"建成了什么样的卡"说清楚。 */
    fun describe(): String = buildList {
        widthLabelOf(widthMode)?.let { add(it) }
        if (pinned) add("已置顶")
        if (secret) add("保密卡")
        if (!showDate) add("不显示日期")
        imageLayoutLabelOf(imageLayout)?.let { add(it) }
    }.joinToString("，").let { if (it.isBlank()) "" else "（$it）" }
}

/**
 * 解析卡片开关。
 *
 * ## ⚠️ `secret` 与 `show_date` 用 `optBooleanOrNull` 而不是 `optBoolean`
 *
 * 因为要区分**"没传"和"传了 false"**：
 *
 * · 没传 → 用模型默认（`showDate` 默认 **true**）
 * · 传了 `false` → 明确要关掉
 *
 * 用 `optBoolean(key, true)` 的话，"传了 false"和"没传"就分不出来了 ——
 * 而这两者的意思完全相反。
 */
internal fun cardFlagsOf(args: JSONObject): CardFlags = CardFlags(
    widthMode = widthModeOf(args.optString("width")),
    pinned = args.optBooleanOrNull("pinned") == true,
    secret = args.optBooleanOrNull("secret") == true,
    secretHint = args.optString("secret_hint").trim().takeIf { it.isNotBlank() },
    // 默认 true：卡片本来就显示日期，只有明确说 false 才关
    showDate = args.optBooleanOrNull("show_date") ?: true,
    imageLayout = imageLayoutOf(args.optString("image_layout")),
)

/** 布尔值在预览卡上的显示（`true` → "是"）。 */
private fun yesNo(value: Boolean): String = if (value) "是" else "否"

/**
 * 宽度参数 → 存储值。
 *
 * 存储用的是 `null` / `"half"` / `"full"`（见 `BoardCard.widthMode`）。
 * `auto` 映射成 `null` 而不是字面量 `"auto"` —— 那是"跟随默认"的意思，
 * 存成字符串会让它在界面上被当成一个未知的宽度值。
 */
internal fun widthModeOf(input: String): String? = when (input.trim().lowercase()) {
    "half", "半宽", "半", "1/2" -> "half"
    "full", "整宽", "全宽", "满宽", "占满", "铺满", "1/1" -> "full"
    else -> null
}

/** 宽度存储值 → 中文标签（预览卡上用）。 */
internal fun widthLabelOf(stored: String?): String? = when (stored) {
    "half" -> "半宽"
    "full" -> "整宽"
    else -> null
}

/** 图片排布：`fill` / `grid`；认不出来就返回 null（跟随默认）。 */
internal fun imageLayoutOf(input: String): String? = when (input.trim().lowercase()) {
    "fill", "大图", "填充" -> "fill"
    "grid", "网格", "九宫格" -> "grid"
    else -> null
}

internal fun imageLayoutLabelOf(stored: String?): String? = when (stored) {
    "fill" -> "大图铺满"
    "grid" -> "缩略网格"
    else -> null
}

/**
 * 把用户/模型给的颜色名转成色值。
 *
 * 同时接受中文名、英文键、`#RRGGBB`。返回 `(中文标签, 存储值)`。
 *
 * 为什么中文名也要认：模型在中文语境里会写 `绿色`，而存储值是 `accent`。
 * 只认英文键的话它会反复试错。
 */
private fun boardColorOf(input: String): Pair<String, String>? {
    val text = input.trim()
    if (text.isEmpty()) return null
    if (BoardColors.isCustom(text)) return "自定义 $text" to text.uppercase()

    return when (text.lowercase()) {
        "白", "白色", "白底", "white" -> "白色" to BoardColors.WHITE
        "绿", "绿色", "accent", "green" -> "绿色" to BoardColors.ACCENT
        "橙", "橙色", "琥珀", "amber", "orange" -> "橙色" to BoardColors.AMBER
        "紫", "紫色", "淡紫", "lilac", "purple" -> "紫色" to BoardColors.LILAC
        "粉", "粉色", "玫红", "rose", "pink" -> "粉色" to BoardColors.ROSE
        "灰", "灰色", "grey", "gray" -> "灰色" to BoardColors.GREY
        else -> null
    }
}

/**
 * 把**存储值**转回中文名，用于变更预览的"旧值"那一格。
 *
 * 刻意**不用** `v2/screens/BoardUi.kt` 里的 `boardColorLabel`：
 * 那是界面层的函数（同一个文件里还有 `VColors`、`VText` 等 Compose 依赖）。
 * 领域层的工具去 import 界面文件，会把 UI 依赖拖进 AI 层 ——
 * 这里的映射只有六行，重复它比引入那条依赖划算。
 */
internal fun boardColorLabelOf(stored: String?): String = when (stored) {
    null, BoardColors.WHITE -> "白色"
    BoardColors.ACCENT -> "绿色"
    BoardColors.AMBER -> "橙色"
    BoardColors.LILAC -> "紫色"
    BoardColors.ROSE -> "粉色"
    BoardColors.GREY -> "灰色"
    else -> if (BoardColors.isCustom(stored)) "自定义 $stored" else "白色"
}

/** 取一个可选字符串；缺失或空串都算"没传"。 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }

/**
 * 取一个可选布尔。
 *
 * 不能用 `optBoolean(key, false)` —— 那分不清"没传"和"传了 false"，
 * 而这里两者语义完全不同（不动 vs 取消置顶）。
 */
private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)
