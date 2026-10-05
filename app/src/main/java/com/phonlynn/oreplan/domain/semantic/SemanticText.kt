package com.phonlynn.oreplan.domain.semantic

import com.phonlynn.oreplan.core.rt.plainTextOf
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AutoPinRule
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.ChecklistCategory
import com.phonlynn.oreplan.domain.model.ChecklistEntry
import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtValue
import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.model.GoalType
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.model.StepOrderMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor

/**
 * 语义层：把**给机器看的数据**翻译成**给人 / 给 AI 看的纯文本**。
 *
 * ## 为什么需要它
 *
 * 数据库里的数据是为「查询与约束」优化的：`items` 是一张 34 列的宽表、时间存 epoch 毫秒、
 * 卡片正文是带零宽字符的富文本编码。这些对 AI 都不友好 ——
 * 直接把原始结构丢给模型，既费 token，又要模型自己猜每列的含义。
 *
 * 用户的标准原话是「**AI 读取舒适：数据能转成干净的纯文本/自然语言**」。
 * 这一层就是那条标准的交付物。
 *
 * ## 三条硬约束
 *
 * 1. **不依赖富文本内部结构**。正文一律走 [plainTextOf]（项目约定的唯一合法入口），
 *    输出里**绝不允许**出现零宽字符（`U+200B/200C/200D/2060`）与全角缩进符。
 *    这一点由单测逐条盯住。
 * 2. **不泄漏内部载体**。`HABIT_ALARM` 是提醒管线的实现细节（见 do-not-touch 清单 A2），
 *    它绝不能出现在任何给 AI 的文本里 —— [renderItemList] 会过滤掉它。
 * 3. **输出稳定**。同样的数据永远产生同样的文本（扩展抽屉按键排序、
 *    列表按传入顺序），否则缓存、比对、测试都会抖。
 *
 * ## 只做「读」
 *
 * 写方向（AI 改数据）随 AI 功能一起做，且必须走 Tool Calling 的受控接口，
 * 不让模型直接拼数据。
 */
object SemanticText {

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * 同一行内的字段分隔符。
     *
     * 刻意用 ASCII 的 ` · `，**不用全角空格**：全角空格（U+3000）正是富文本内核
     * 用来编码缩进的隐形字符，本层输出里必须一个都不出现（有单测盯着）。
     */
    private const val SEP = " · "

    // ---------------------------------------------------------------- 条目

    /**
     * 内部载体不该出现在给 AI 的文本里。
     *
     * `HABIT_ALARM` 是「习惯目标的提醒载体」—— 一条为了让提醒调度器能按周期排程
     * 而存在的隐藏条目。它对用户不可见，对 AI 也应该不存在。
     */
    fun isInternalCarrier(item: Item): Boolean = item.kind == ItemKind.HABIT_ALARM

    /**
     * 渲染一条条目。内部载体返回 null（调用方无需自己判断）。
     *
     * @param tags 标签名（调用方自行取好；本层不碰数据库）
     * @param reminders 该条目的提醒
     * @param checklist 该条目的备忘清单
     * @param noteBlocks 该条目的笔记小节
     * @param attachments 附件（只渲染名字与数量，不含二进制）
     */
    fun renderItem(
        item: Item,
        zone: ZoneId = ZoneId.systemDefault(),
        tags: List<String> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        checklist: List<ChecklistEntry> = emptyList(),
        noteBlocks: List<NoteBlock> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        ext: ExtMap = ExtMap.EMPTY,
    ): String? {
        if (isInternalCarrier(item)) return null

        val out = mutableListOf<String>()
        out += "【${kindLabel(item.kind)}】${clean(item.title)}"
        out += "状态：${statusLabel(item.status)}" + prioritySuffix(item.priority)

        timeLine(item, zone)?.let { out += it }
        item.softDueAt?.let { out += "期望完成：${formatDateTime(it, zone)}" }
        planLine(item)?.let { out += it }
        item.location?.takeIf { it.isNotBlank() }?.let { out += "地点：${clean(it)}" }
        item.category?.takeIf { it.isNotBlank() }?.let { out += "分类：${clean(it)}" }
        if (tags.isNotEmpty()) out += "标签：${tags.joinToString("、") { clean(it) }}"

        goalLine(item)?.let { out += it }
        item.goalNote?.takeIf { it.isNotBlank() }?.let { out += "目标备注：${clean(it)}" }
        item.note?.takeIf { it.isNotBlank() }?.let { out += "备注：${clean(it)}" }

        reminderLines(reminders, zone).let { if (it.isNotEmpty()) out += it }
        checklistLines(checklist).let { if (it.isNotEmpty()) out += it }
        noteBlockLines(noteBlocks).let { if (it.isNotEmpty()) out += it }

        if (attachments.isNotEmpty()) {
            out += "附件（${attachments.size}）：" +
                attachments.joinToString("、") { clean(it.displayName) }
        }

        renderExtLines(ext).let { if (it.isNotEmpty()) out += it }

        return out.joinToString("\n")
    }

    /**
     * 渲染一批条目（如「今天要做的事」）。
     * 自动跳过内部载体；`items` 为空时返回一句明确的话，而不是空字符串
     * （空字符串会让 AI 以为「工具坏了」，而不是「确实没有」）。
     */
    fun renderItemList(
        items: List<Item>,
        title: String,
        zone: ZoneId = ZoneId.systemDefault(),
        emptyHint: String = "（没有条目）",
    ): String {
        val visible = items.filterNot(::isInternalCarrier)
        val header = "# $title（${visible.size} 项）"
        if (visible.isEmpty()) return "$header\n$emptyHint"
        val body = visible.mapIndexed { index, item ->
            (index + 1).toString() + ". " + renderItem(item, zone).orEmpty().replace("\n", "\n   ")
        }
        return header + "\n" + body.joinToString("\n")
    }

    // ---------------------------------------------------------------- 白板卡片

    /**
     * 渲染一张白板卡片。
     *
     * 正文走 [plainTextOf]：**卡片正文落库是富文本编码**（结构藏在零宽字符里），
     * 直接读会拿到 `{"v":2,...}` 或带零宽字符的串。
     * 保密卡（[BoardCard.secret]）只输出「已隐藏」，不把内容喂给 AI ——
     * 保密是用户的显式意图，语义层不该绕过它。
     */
    fun renderCard(
        card: BoardCard,
        zone: ZoneId = ZoneId.systemDefault(),
        todoItems: List<BoardTodoItem> = emptyList(),
        tagNames: List<String> = emptyList(),
        ext: ExtMap = ExtMap.EMPTY,
    ): String {
        val out = mutableListOf<String>()
        val name = card.title?.takeIf { it.isNotBlank() }?.let(::clean)
            ?: plainPreview(card.body)
        out += "【卡片·${cardTypeLabel(card)}】" + name.ifEmpty { "（无标题）" }
        out += "创建：${formatDateTime(card.createdAt, zone)}${SEP}更新：${formatDateTime(card.updatedAt, zone)}"
        if (tagNames.isNotEmpty()) out += "标签：${tagNames.joinToString("、") { clean(it) }}"
        card.autoPin?.let { rule ->
            val kind = when (rule) {
                is AutoPinRule.OnDate -> "指定日期"
                is AutoPinRule.Recurring -> "周期重复"
            }
            out += "动态置顶：$kind（本次持续 ${formatMinutes(rule.durationMinutes)}）"
        }

        if (card.secret) {
            out += "正文：（保密卡片，内容不对 AI 输出）"
        } else {
            val body = clean(plainTextOf(card.body))
            if (body.isNotBlank()) {
                out += "正文："
                out += body.lines().joinToString("\n") { "  $it" }
            }
        }

        if (todoItems.isNotEmpty()) {
            out += "清单："
            todoItems.forEach { out += "  - [${if (it.done) "x" else " "}] ${clean(it.text)}" }
        }

        renderExtLines(ext).let { if (it.isNotEmpty()) out += it }

        return out.joinToString("\n")
    }

    // ---------------------------------------------------------------- 专注

    /** 一次专注记录一行，适合按天/按周列出来看。 */
    fun renderFocus(session: FocusSession, zone: ZoneId = ZoneId.systemDefault()): String {
        val who = session.label?.takeIf { it.isNotBlank() }?.let(::clean) ?: "（未命名）"
        val how = session.plannedMinutes?.let { "${session.minutes}/$it 分钟" } ?: "${session.minutes} 分钟"
        val done = if (session.completed) "已完成" else "中途结束"
        val linked = session.itemId?.let { "${SEP}关联条目：$it" }.orEmpty()
        return listOf(
            formatDateTime(session.startedAt, zone),
            who,
            focusKindLabel(session.kind),
            how,
            done,
        ).joinToString(SEP) + linked
    }

    /**
     * 专注统计。这是「替代番茄Todo 核心」里最常被看的一句话，
     * 也是「专注 ↔ 待办」这条数据链的下游产出。
     */
    fun renderFocusSummary(
        sessions: List<FocusSession>,
        title: String = "专注统计",
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (sessions.isEmpty()) return "# $title\n（没有专注记录）"

        val totalMinutes = sessions.sumOf { it.minutes }
        val completed = sessions.count { it.completed }
        val out = mutableListOf<String>()
        out += "# $title"
        out += "共 ${sessions.size} 次，合计 ${formatMinutes(totalMinutes)}" +
            "（走完 $completed 次，中途结束 ${sessions.size - completed} 次）"
        sessions.groupBy { it.kind }
            .toSortedMap(compareBy { it.ordinal })
            .forEach { (kind, list) ->
                out += "· ${focusKindLabel(kind)}：${list.size} 次，${formatMinutes(list.sumOf { it.minutes })}"
            }
        sessions.firstOrNull()?.let { out += "最早：${formatDateTime(it.startedAt, zone)}" }
        sessions.lastOrNull()?.let { out += "最近：${formatDateTime(it.startedAt, zone)}" }
        return out.joinToString("\n")
    }

    // ---------------------------------------------------------------- 扩展抽屉

    /**
     * 把扩展抽屉渲染成文本行。
     *
     * 抽屉是「以后加什么字段都不用迁移」的载体，所以这一段的输出必须**通用**：
     * 不认得任何具体键名，只按 `键：值` 老实排出来（按键排序，保证稳定）。
     * 这样明天加一个新字段，语义层不需要改任何代码 —— 那正是可扩展性标准要的效果。
     */
    fun renderExtLines(ext: ExtMap): List<String> {
        if (ext.isEmpty) return emptyList()
        val lines = mutableListOf("扩展信息：")
        ext.sortedEntries().forEach { (key, value) ->
            lines += "  $key：${renderValue(value)}"
        }
        return lines
    }

    // ---------------------------------------------------------------- 内部工具

    private fun timeLine(item: Item, zone: ZoneId): String? {
        val start = item.startAt ?: return null
        val end = item.endAt
        return when {
            end == null -> "时间：${formatDateTime(start, zone)}"
            sameDay(start, end, zone) ->
                "时间：${formatDateTime(start, zone)}–${TIME.format(toZoned(end, zone))}"
            else -> "时间：${formatDateTime(start, zone)} – ${formatDateTime(end, zone)}"
        }
    }

    private fun planLine(item: Item): String? {
        val start = item.planStartDay ?: return null
        val end = item.planEndDay
        return if (end == null) {
            "计划跨度：${DATE.format(start)}"
        } else {
            "计划跨度：${DATE.format(start)} → ${DATE.format(end)}"
        }
    }

    private fun goalLine(item: Item): String? {
        val type = item.goalType ?: return null
        val parts = mutableListOf("目标类型：${goalTypeLabel(type)}")
        if (type == GoalType.QUANTITY) {
            val unit = item.unit?.takeIf { it.isNotBlank() }?.let(::clean).orEmpty()
            val target = item.targetValue
            if (target != null) parts += "目标值：$target$unit"
        }
        if (type == GoalType.STEP) {
            item.stepOrderMode?.let { mode ->
                parts += "推进方式：" + if (mode == StepOrderMode.SEQ) "按顺序解锁" else "自由完成"
                if (item.autoAdvance) parts += "完成后自动推进"
            }
        }
        item.progress?.let { parts += "进度：${(it * 100).toInt()}%" }
        return parts.joinToString(SEP)
    }

    private fun reminderLines(reminders: List<Reminder>, zone: ZoneId): List<String> {
        val enabled = reminders.filter { it.enabled }
        if (enabled.isEmpty()) return emptyList()
        return listOf(
            "提醒：" + enabled.joinToString("；") { reminder ->
                val offset = reminder.offsetMinutes?.let { "提前 ${formatMinutes(it)}" }
                listOfNotNull(offset, formatDateTime(reminder.triggerAt, zone)).joinToString(" ")
            },
        )
    }

    private fun checklistLines(checklist: List<ChecklistEntry>): List<String> {
        if (checklist.isEmpty()) return emptyList()
        val done = checklist.count { it.done }
        val lines = mutableListOf("备忘清单（$done/${checklist.size}）：")
        checklist.sortedBy { it.orderIndex }.forEach { entry ->
            val mark = if (entry.done) "x" else " "
            val category = if (entry.category == ChecklistCategory.OTHER) {
                ""
            } else {
                "［${checklistCategoryLabel(entry.category)}］"
            }
            lines += "  - [$mark] $category${clean(entry.title)}"
        }
        return lines
    }

    private fun noteBlockLines(blocks: List<NoteBlock>): List<String> {
        if (blocks.isEmpty()) return emptyList()
        val lines = mutableListOf("笔记小节：")
        blocks.sortedBy { it.orderIndex }.forEach { block ->
            val heading = clean(block.heading)
            lines += if (heading.isBlank()) "  ·" else "  · $heading"
            val body = clean(plainTextOf(block.body))
            if (body.isNotBlank()) {
                body.lines().forEach { lines += "      $it" }
            }
        }
        return lines
    }

    private fun prioritySuffix(priority: Int): String = when (priority) {
        0 -> ""
        1 -> "${SEP}优先级：低"
        2 -> "${SEP}优先级：中"
        else -> "${SEP}优先级：高"
    }

    private fun renderValue(value: ExtValue): String = when (value) {
        is ExtValue.Text -> value.value
        is ExtValue.Flag -> if (value.value) "是" else "否"
        is ExtValue.Num -> if (isWhole(value.value)) {
            value.value.toLong().toString()
        } else {
            value.value.toString()
        }
    }

    private fun isWhole(value: Double): Boolean =
        value.isFinite() && value == floor(value) && abs(value) < 9.0e15

    private fun formatDateTime(instant: Instant, zone: ZoneId): String =
        DATE_TIME.format(toZoned(instant, zone))

    private fun toZoned(instant: Instant, zone: ZoneId): ZonedDateTime =
        instant.atZone(zone)

    private fun sameDay(a: Instant, b: Instant, zone: ZoneId): Boolean =
        toZoned(a, zone).toLocalDate() == toZoned(b, zone).toLocalDate()

    private fun formatMinutes(minutes: Int): String {
        if (minutes < 60) return "$minutes 分钟"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) "$hours 小时" else "$hours 小时 $rest 分钟"
    }

    /**
     * 清掉不该进纯文本的显示层字符。
     *
     * 两件事：
     * 1. `sanitizeContent` 剥掉**结构标记**（零宽字符 U+200B/200C/200D/2060）。
     *    早期版本把零宽空格写进过正文，那些历史数据可能还在库里，
     *    所以每个文本字段都过一遍，而不是只处理「应该是富文本」的那些。
     * 2. 再把**缩进符**（全角空格 U+3000 + EN SPACE U+2002）降级成普通空格。
     *
     * 第 2 步必需：`sanitizeContent` 只在缩进符**紧跟标记字符**时才剥掉它，
     * 正文里独立出现的全角空格会原样留下 —— 对 AI 来说那是看不见的噪音
     * （这条是被单测抓出来的，见 `SemanticTextTest.输出里不含零宽字符与缩进符`）。
     *
     * ⚠️ 只改**本层输出**，绝不改落库内容。
     */
    private fun clean(text: String?): String =
        com.phonlynn.oreplan.core.rt.RichTextCodec.sanitizeContent(text.orEmpty())
            .replace('\u3000', ' ')
            .replace('\u2002', ' ')

    private fun plainPreview(raw: String?): String =
        clean(plainTextOf(raw)).replace('\n', ' ').trim().take(40)

    private fun kindLabel(kind: ItemKind): String = when (kind) {
        ItemKind.EVENT -> "日程"
        ItemKind.TASK -> "待办"
        ItemKind.GOAL -> "目标"
        ItemKind.STEP -> "步骤"
        ItemKind.HABIT_ALARM -> "习惯提醒载体"
        ItemKind.WORKSPACE -> "规划工作区"
        ItemKind.TODO_GROUP -> "待办组"
    }

    private fun statusLabel(status: ItemStatus): String = when (status) {
        ItemStatus.TODO -> "未完成"
        ItemStatus.DOING -> "进行中"
        ItemStatus.DONE -> "已完成"
        ItemStatus.CANCELLED -> "已取消"
    }

    private fun goalTypeLabel(type: GoalType): String = when (type) {
        GoalType.STEP -> "分步"
        GoalType.QUANTITY -> "数量"
        GoalType.HABIT -> "习惯"
    }

    private fun checklistCategoryLabel(category: ChecklistCategory): String = when (category) {
        ChecklistCategory.MATERIAL -> "材料"
        ChecklistCategory.CARRY -> "携带"
        ChecklistCategory.FORM -> "表单"
        ChecklistCategory.CHECKIN -> "打卡"
        ChecklistCategory.OTHER -> "其他"
    }

    private fun focusKindLabel(kind: FocusKind): String = kind.label

    private fun cardTypeLabel(card: BoardCard): String = card.type.label
}
