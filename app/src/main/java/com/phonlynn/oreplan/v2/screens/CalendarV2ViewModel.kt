package com.phonlynn.oreplan.v2.screens

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.data.settings.AppSettings
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.core.todo.TodoDropTarget
import com.phonlynn.oreplan.core.todo.TodoRow
import com.phonlynn.oreplan.core.todo.buildTodoRows
import com.phonlynn.oreplan.core.todo.groupMetaText
import com.phonlynn.oreplan.core.todo.planTodoDrop
import com.phonlynn.oreplan.domain.model.Agenda
import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import com.phonlynn.oreplan.domain.routine.RoutineConfig
import com.phonlynn.oreplan.domain.routine.RoutineSchedule
import com.phonlynn.oreplan.domain.usecase.BuildAgendaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import com.phonlynn.oreplan.domain.model.ItemKind

/**
 * 月视图的一天。
 *
 * `@Immutable` 不是装饰：`buildState` 每次都重建这些格子对象，而 `events` 是 `List`
 * （Compose 判为 unstable）—— 不标注则整个类 unstable，跳过判断退化成「实例相等」，
 * 于是**每次状态变化 42 个格子全部重组**（连带一两百个文本节点重新处理）。
 * 那正是展开日视图时首帧重帧的一部分（用户 2026-09-25：点格子展开有明显延迟）。
 * 字段全部由 `buildMonthWeeks` 新建、从不原地修改，所以标注成立。
 * （实证：Compose 编译器报告 `app-classes.txt` 里 CalendarDayCell 为 unstable，
 *   加标注后变 stable。）
 */
@Immutable
data class CalendarDayCell(
    val date: LocalDate,
    val dayOfMonth: Int,
    val inMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val hasEvents: Boolean,
    /** 月格里的彩色事件条（课程 + 日程；待办不占月格）。最多 [MonthCellMaxItems] 条。 */
    val events: List<MonthCellEvent> = emptyList(),
    /** 超出上限的部分：日格右上角显示「+N」。 */
    val moreCount: Int = 0,
)

/** 月格事件条：短标签 + 取色 token（颜色映射由界面层用 EventTone 做，VM 不依赖 Color）。 */
data class MonthCellEvent(
    /** 短标签：标题前 4 个字（设计稿里全部 ≤4 字）。 */
    val label: String,
    val colorToken: String?,
)

/** 关键日期卡的一行（设计稿「日期徽章 + 标题 + 时间地点 + 类型标签」）。 */
data class KeyDateRow(
    val key: String,
    /** 日期徽章，如「3/14」。 */
    val badge: String,
    val title: String,
    /** 时间 · 地点 · 时长。 */
    val meta: String,
    /** 类型标签（日程 / 课程）。 */
    val tag: String,
    val colorToken: String?,
)

/** 周条的一天。 */
data class WeekStripDay(
    val date: LocalDate,
    val weekdayLabel: String,
    val dayOfMonth: Int,
    val isToday: Boolean,
    val isSelected: Boolean,
    val hasEvents: Boolean,
)

/** 议程行（月视图/日视图共用，字段按各自规格填充）。 */
data class CalendarAgendaRowUi(
    val key: String,
    val startMinute: Int?,
    val endMinute: Int?,
    val startText: String,
    val endText: String?,
    val title: String,
    val metaText: String?,
    val colorTag: String?,
    val isCourse: Boolean,
    /** 待办（V3 时间轴上画成「圆点 + 时间 + 标题」的横排块）。 */
    val isTask: Boolean = false,
    val itemId: String?,
    val courseId: String?,
    /**
     * 条目创建时刻（毫秒）。**仅用于「并列同起点」时的稳定排序**：
     * 同一开始时刻的多个日程，后添加的应排在右侧（用户 2026-09-29）。
     * 课程展开的条没有创建时刻概念，取 0。
     */
    val createdAtMillis: Long = 0L,
)

/** 议程分组。 */
data class CalendarAgendaGroup(
    val label: String,
    val rows: List<CalendarAgendaRowUi>,
)

data class CalendarTodoRow(
    val itemId: String,
    val title: String,
    val metaText: String?,
    val done: Boolean,
    val priorityLabel: String?,
    val priorityKind: TodayChipKind,
)

/**
 * 待办页的一行：分组头 或 待办。
 *
 * 它与 core/todo/TodoTree.kt 的 [TodoRow] 差一层：那边是**纯数据**
 * （能脱离界面单测），这里带上了界面要的文案与颜色。
 */
sealed interface TodoListRow {
    /** 嵌套深度（0 = 顶层）；分组行与待办行都用它做缩进。 */
    val depth: Int

    data class Group(
        val groupId: String,
        val title: String,
        /** 「4 项 · 3 未完成」（递归统计，含子组）。 */
        val meta: String,
        override val depth: Int,
        /** 组内是否还有内容 —— 空组不画折叠箭头。 */
        val hasChildren: Boolean,
        val collapsed: Boolean,
        /**
         * 该组自己 + 全部后代组的 id。
         * 拖动时用它拒绝「把组拖进自己的后代」—— 成环会把物化路径搅成自相矛盾，
         * 整棵树从界面消失（用户 2026-09-26 实测的「整个组湣灭」）。
         * 必须由 VM 从完整数据算好：折叠在组里的后代不在可见行里，界面拿不到。
         */
        val subtreeIds: Set<String> = emptySet(),
    ) : TodoListRow

    data class Todo(val row: CalendarTodoRow, override val depth: Int) : TodoListRow
}

/**
 * 详情页里的**一个备注小节**。
 *
 * 为什么不是三段扁平列表（noteText / photoPaths / fileAttachments）：
 * 那三个字段把「哪个附件属于哪条备注」这个信息丢掉了 —— 多组备注在展示页**分不开**，
 * 而且备注只取得到第一个非空小节（用户 2026-09-28 报的正是这两件事）。
 * 附件在数据层就是挂在**备注小节 id** 上的，这里如实透出来即可。
 */
data class NoteSectionUi(
    /** 小节正文（富文本编码，展示端用 VRichText 渲染）。 */
    val body: String,
    /** 本节内的图片（相对路径）。 */
    val photos: List<String>,
    /** 本节内的其它附件。 */
    val files: List<Attachment>,
)

/** 事件详情弹层内容。 */
data class EventDetailUi(
    val itemId: String?,
    val courseId: String?,
    val categoryLabel: String,
    val title: String,
    val timeText: String?,
    val durationText: String?,
    val dateText: String?,
    val locationText: String?,
    val reminderText: String?,
    val noteText: String?,
    val photoPaths: List<String>,
    val fileAttachments: List<Attachment>,
    /**
     * 备注小节（**展示端按它渲染**）：小节之间画分割线，小节内部不再画线。
     * 老的三个扁平字段保留给「全部图片查看」等既有用途。
     */
    val notes: List<NoteSectionUi> = emptyList(),
    val isCourse: Boolean,
    /** 待办：底部按钮写「编辑待办」。 */
    val isTask: Boolean = false,
    /** 已完成：已完成的待办底部按钮从「删除」换成「归档」。 */
    val isDone: Boolean = false,
    /** 待办：创建时间（浅灰小字，放在标题下）。没有截止时间时它是 null —— 那种情况创建时间走「日期」行。 */
    val createdText: String? = null,
)

data class CalendarV2UiState(
    val view: CalendarView = CalendarView.Month,
    val mode: CalendarMode = CalendarMode.Schedule,
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
    // 导航文本
    val navTitle: String = "",
    val navSubtitle: String = "",
    /** 月视图视角的标题/副标题。转场时日期数字要淡入、副标题要交叉淡化（用户 2026-09-25），
     *  所以两套文本都留在 state 里 —— UI 不依赖 view 去反推。 */
    val navTitleMonth: String = "",
    val navTitleDay: String = "",
    val navSubtitleMonth: String = "",
    val navSubtitleDay: String = "",
    /** 形如「第 11 周」的学期周标签（无学期时为「本周」）。 */
    val weekLabel: String = "",
    // 月视图
    val monthWeeks: List<List<CalendarDayCell>> = emptyList(),
    /** 月视图 · 选中日信息条标题（「今天 · 3月12日 星期三」）。 */
    val dayInfoTitle: String = "",
    /** 月视图 · 选中日信息条副标题（「6 项安排 · 08:00 英语精读 → 20:30 晚自习」）。 */
    val dayInfoMeta: String = "",
    /** 月视图「隐藏课程」开关状态（只在月视图生效）。 */
    val hideCoursesInMonth: Boolean = false,
    /** 关键日期（有提醒的日程，最多 4 条）。 */
    val keyDates: List<KeyDateRow> = emptyList(),
    val showTodayPill: Boolean = true,
    // 周条
    val weekStrip: List<WeekStripDay> = emptyList(),
    // 议程
    val agendaTitle: String = "",
    val agendaCountText: String = "",
    val agendaGroups: List<CalendarAgendaGroup> = emptyList(),
    // 待办
    val todoRows: List<CalendarTodoRow> = emptyList(),
    /** 待办页的分组行（分组头 + 待办，已算好嵌套深度与折叠状态）。 */
    val todoListRows: List<TodoListRow> = emptyList(),
    val todoDoneText: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarV2ViewModel @Inject constructor(
    private val buildAgenda: BuildAgendaUseCase,
    private val itemRepository: ItemRepository,
    /** 删除条目（连提醒/重复例外/清单一并清），见 [deleteItem]。 */
    private val deleteItemUseCase: com.phonlynn.oreplan.domain.usecase.DeleteItemUseCase,
    private val courseRepository: CourseRepository,
    private val reminderRepository: ReminderRepository,
    private val noteBlockRepository: NoteBlockRepository,
    private val attachmentRepository: AttachmentRepository,
    private val termRepository: TermRepository,
    private val settingsStore: AppSettingsStore,
    /** 界面层需要用同一份存储来读附件（展示/打开）。 */
    val attachmentStorage: com.phonlynn.oreplan.platform.attachment.AttachmentStorage,
    /** 日程详情的装配（与今日页共用）。 */
    private val detailLoader: ScheduleDetailLoader,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * 最近一次装配用的**原始议程**。
     *
     * 拖动排序落库时要把「落点」换算成新序号，而换算需要完整的分组与待办
     * （包括折叠在组里、不在可见行里的那些）—— uiState 里只有算好的行，信息不够。
     */
    private var lastAgenda: Agenda = Agenda(days = emptyMap())
    /**
     * 从设置构造视图状态（含容错）。抽出来是为了**初始化与恢复共用同一套解析**。
     */
    private fun viewStateOf(s: AppSettings): ViewState = ViewState(
        view = runCatching { CalendarView.valueOf(s.calendarView) }
            .getOrDefault(CalendarView.Month),
        mode = runCatching { CalendarMode.valueOf(s.calendarMode) }
            .getOrDefault(CalendarMode.Schedule),
        date = s.calendarDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now(zone),
    )

    /**
     * 视图状态。
     *
     * ⚠️ **初始值必须尽量取「已缓存的真实设置」**，否则会闪变：
     * 旧写法恒以 `CalendarView.Month` 起步、再在 init 里异步读设置覆盖，
     * 于是「从别的 Tab 切回日程」时，日期栏会先按**月视图样式**渲染一帧、
     * 再跳成真正的视图（用户 2026-09-30 报「日期数/小字从月视图样式跳变」）。
     *
     * Tab 切换会重建页面与 VM，但 `AppSettingsStore` 是 `@Singleton` 且进程内缓存，
     * 此时 `isLoaded` 通常已为 true —— 直接取缓存值即可得到**正确的首帧**。
     * 冷启动时设置尚未加载，仍回退到默认值，再由 init 里的异步恢复纠正（那是启动、非切 Tab）。
     */
    private val viewState = MutableStateFlow(
        if (settingsStore.isLoaded) {
            viewStateOf(settingsStore.settings.value)
        } else {
            ViewState(view = CalendarView.Month, mode = CalendarMode.Schedule, date = LocalDate.now(zone))
        },
    )

    data class ViewState(
        val view: CalendarView,
        val mode: CalendarMode,
        val date: LocalDate,
        /** 月视图「隐藏课程」（只在月视图生效，内存态、不持久化）。 */
        val hideCoursesInMonth: Boolean = false,
        /**
         * 已折叠的待办组 id（内存态，不持久化 —— 与折叠状态的定位一致：
         * 它是一次浏览里的临时选择，不是需要记住的设置）。
         */
        val collapsedGroups: Set<String> = emptySet(),
    )

    init {
        // 恢复上次的视图/模式/日期 ——「显示记忆」（用户 2026-09-24：
        // 从待办退出再进来要回到待办，从月视图退出再进来要回到月视图）。
        viewModelScope.launch {
            viewState.value = viewStateOf(settingsStore.settings.first())
        }
        // 一次性跳转意图（今日页 → 查看全部 / 全部待办）。
        viewModelScope.launch {
            CalendarEntryBus.request.collect { req ->
                if (req != null) {
                    viewState.value = ViewState(
                        view = req.view,
                        mode = req.mode,
                        date = req.date ?: viewState.value.date,
                    )
                    CalendarEntryBus.consume()
                }
            }
        }
    }

    /** 视图/模式/日期写回设置，供下次进日程页恢复。 */
    private fun persistViewState() {
        val s = viewState.value
        viewModelScope.launch {
            settingsStore.update {
                it.copy(
                    calendarView = s.view.name,
                    calendarMode = s.mode.name,
                    calendarDate = s.date.toString(),
                )
            }
        }
    }

    /**
     * 待办页拖动排序落库。
     *
     * 落点解析（分区 + 滞回）在拖动层完成，这里只把 **当前完整数据** 交给
     * [planTodoDrop] 换算成「写哪些序号 / 要不要换组」，再一次性写库。
     */
    fun moveTodo(draggedId: String, target: TodoDropTarget) {
        val agenda = lastAgenda
        val changes = planTodoDrop(
            groups = agenda.todoGroups,
            tasks = agenda.openTasks,
            draggedId = draggedId,
            target = target,
        )
        if (changes.isEmpty()) return
        viewModelScope.launch { itemRepository.applyTodoOrder(changes) }
    }

    fun setView(view: CalendarView) {
        viewState.value = viewState.value.copy(view = view)
        persistViewState()
    }

    fun setMode(mode: CalendarMode) {
        viewState.value = viewState.value.copy(mode = mode)
        persistViewState()
    }

    fun selectDate(date: LocalDate) {
        viewState.value = viewState.value.copy(date = date)
        persistViewState()
    }

    fun goToday() {
        viewState.value = viewState.value.copy(date = LocalDate.now(zone))
        persistViewState()
    }

    /** 月视图「隐藏课程」：只影响月格的课程条，不影响日视图。 */
    fun toggleHideCoursesInMonth() {
        viewState.value = viewState.value.copy(hideCoursesInMonth = !viewState.value.hideCoursesInMonth)
    }

    /** 折叠 / 展开一个待办组（待办页的分组行点箭头）。 */
    fun toggleTodoGroup(groupId: String) {
        val current = viewState.value.collapsedGroups
        viewState.value = viewState.value.copy(
            collapsedGroups = if (groupId in current) current - groupId else current + groupId,
        )
    }

    /** 点月格：一次写完日期与视图，避免两次状态写引发两次 UI 重算（用户 2026-09-25）。 */
    fun openDay(date: LocalDate) {
        viewState.value = viewState.value.copy(date = date, view = CalendarView.Day)
        persistViewState()
    }

    fun shift(forward: Boolean) {
        val s = viewState.value
        val step = when (s.view) {
            CalendarView.Month -> java.time.Period.ofMonths(if (forward) 1 else -1)
            CalendarView.Day -> java.time.Period.ofDays(if (forward) 1 else -1)
        }
        viewState.value = s.copy(date = s.date.plus(step))
        persistViewState()
    }

    val uiState: StateFlow<CalendarV2UiState> = viewState
        // **只按「月份」重启数据流**：同一个月内的操作（点格子、切月/日视图）
        // 直接复用已订阅的 agenda、立即重算 state —— 不再等一次数据库往返。
        // 之前按整个 viewState 重启，点击后要重新查询数据库动画才出来，延迟很大（用户 2026-09-25）。
        .map { YearMonth.from(it.date) }
        .distinctUntilChanged()
        .flatMapLatest { ym ->
            val first = ym.atDay(1)
            val gridStart = first.minusDays(((first.dayOfWeek.value + 6) % 7).toLong())
            // 月网格 6×7 的完整范围：两种视图都取它（日视图期间背景仍要渲染月视图）。
            val range = gridStart to gridStart.plusDays(41)
            combine(
                buildAgenda.observe(range.first, range.second, zone),
                viewState,
                termRepository.observeActiveTerm(),
                settingsStore.settings,
                // 关键日期要用「有提醒的条目」这一筛选口径（用户 2026-09-25 先按这个来）。
                reminderRepository.observeEnabled(),
            ) { agenda, vs, term, settings, reminders ->
                buildState(vs, agenda, term?.let { it }, settings.routine, reminders)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CalendarV2UiState(),
        )

    private fun visibleRange(vs: ViewState): Pair<LocalDate, LocalDate> {
        // 月网格 6×7 的实际覆盖范围（含首尾月外补格）。
        // **两种视图都取这个范围** —— 日视图显示期间背景仍要渲染月视图
        // （转场时可见、直到被完全覆盖），agenda 若只取当天，
        // 其他格子内的事件会在进入日视图时提前消失（用户 2026-09-25）。
        val ym = YearMonth.from(vs.date)
        val first = ym.atDay(1)
        val gridStart = first.minusDays(((first.dayOfWeek.value + 6) % 7).toLong())
        return gridStart to gridStart.plusDays(41)
    }

    // ---------------------------------------------------------------- 状态组装

    private fun buildState(
        vs: ViewState,
        agenda: Agenda,
        term: com.phonlynn.oreplan.domain.model.Term?,
        routine: RoutineConfig,
        reminders: List<Reminder>,
    ): CalendarV2UiState {
        val today = LocalDate.now(zone)
        lastAgenda = agenda
        val periods = RoutineSchedule.compute(routine)
        val afternoonStart = periods.getOrNull(routine.morningCount)?.startMinute ?: 12 * 60
        val eveningStart = periods.getOrNull(routine.morningCount + routine.afternoonCount)?.startMinute ?: 18 * 60

        val ym = YearMonth.from(vs.date)
        // 月格数据**始终计算**，不随视图清空 ——
        // 日视图显示期间背景仍要渲染月视图（转场时可见，直到被完全覆盖），
        // 之前按 view 清空导致转场一开始月网格就凭空消失（用户 2026-09-25）。
        val monthWeeks = buildMonthWeeks(vs, agenda, today)

        // 月视图 · 选中日信息条 + 关键日期（V3 设计稿的 Selected Day / Key Dates）。
        val monthDayTimed = if (vs.view == CalendarView.Month) agenda.scheduledOn(vs.date) else emptyList()
        val dayInfoTitle = if (vs.view == CalendarView.Month) buildString {
            if (vs.date == today) append("今天 · ")
            append("${vs.date.monthValue}月${vs.date.dayOfMonth}日 ${weekdayShort(vs.date)}")
        } else ""
        val dayInfoMeta = if (vs.view == CalendarView.Month) {
            if (monthDayTimed.isEmpty()) "暂无安排" else buildString {
                append("${monthDayTimed.size} 项安排")
                val first = monthDayTimed.first()
                first.startMinute?.let { append(" · ${minuteLabel(it)} ${first.title.take(6)}") }
                if (monthDayTimed.size > 1) {
                    val last = monthDayTimed.last()
                    last.startMinute?.let { append(" → ${minuteLabel(it)} ${last.title.take(6)}") }
                }
            }
        } else ""
        val reminderIds = reminders.map { it.itemId }.toSet()
        val keyDates = if (vs.view == CalendarView.Month) {
            agenda.days.entries.asSequence()
                .filter { it.key >= today }
                .sortedBy { it.key }
                .flatMap { it.value.asSequence() }
                .filter { it.itemId != null && it.itemId in reminderIds }
                .sortedWith(compareBy({ it.date }, { it.startMinute ?: Int.MAX_VALUE }))
                .take(4)
                .map { e ->
                    KeyDateRow(
                        key = e.key,
                        badge = "${e.date.monthValue}/${e.date.dayOfMonth}",
                        title = e.title,
                        meta = listOfNotNull(
                            e.startMinute?.let { m -> minuteLabel(m) },
                            e.location?.takeIf { it.isNotBlank() },
                            e.startMinute?.let { s -> e.endMinute?.let { en -> "${en - s} 分钟" } },
                        ).joinToString(" · ").ifEmpty { "无具体时间" },
                        tag = if (e.isCourse) "课程" else "日程",
                        colorToken = e.colorToken,
                    )
                }
                .toList()
        } else emptyList()

        val ws = weekStart(vs.date)
        val weekStrip = if (vs.view != CalendarView.Month) buildWeekStrip(vs, agenda, today, ws) else emptyList()

        val dayEntries = agenda.on(vs.date)
        val scheduled = dayEntries.filter { it.isTimed && !it.allDay }
        val agendaGroups = if (vs.view != CalendarView.Month) groupAgenda(scheduled, afternoonStart, eveningStart) else emptyList()

        val monthRows = if (vs.view == CalendarView.Month) {
            scheduled.map { rowOf(it, showEnd = false, courses = emptyMap()) }
        } else emptyList()

        // 待办行与视图/模式**无关**地算：「待办任务」按钮的数量徽标要用它。
        // 原来只在「日视图 + 待办模式」才算，导致没点进去时按钮上永远显示 0（用户 2026-09-23）。
        val todoRows = agenda.openTasks.map { todoRowOf(it) }
        val todoTotal = agenda.openTasks.size
        // 待办页的分组行：树形装配在纯函数里（嵌套/折叠/递归统计），
        // 这里只把其中的任务行换成界面模型（文案/颜色已在 todoRowOf 里算好）。
        // 每个组「自己 + 全部后代组」的 id：拖动落点判定用它拒绝成环的移动。
        // 必须在 VM 算：折叠在组里的后代不在可见行里，界面层自己是算不出来的。
        val groupSubtreeIds: Map<String, Set<String>> = agenda.todoGroups.associate { group ->
            group.id to agenda.todoGroups
                .filter { it.treePath.startsWith(group.treePath) }
                .mapTo(HashSet()) { it.id }
        }
        val todoRowByItemId = todoRows.associateBy { it.itemId }
        val todoListRows: List<TodoListRow> = buildTodoRows(
            groups = agenda.todoGroups,
            tasks = agenda.openTasks,
            collapsed = vs.collapsedGroups,
        ).map { row ->
            when (row) {
                is TodoRow.GroupRow -> TodoListRow.Group(
                    groupId = row.groupId,
                    title = row.title,
                    meta = groupMetaText(row.total, row.undone),
                    depth = row.depth,
                    hasChildren = row.hasChildren,
                    collapsed = row.collapsed,
                    subtreeIds = groupSubtreeIds[row.groupId].orEmpty(),
                )

                is TodoRow.TaskRow -> TodoListRow.Todo(
                    row = todoRowByItemId[row.task.itemId.orEmpty()] ?: todoRowOf(row.task),
                    depth = row.depth,
                )
            }
        }

        val navTexts = navText(vs, agenda, term, routine)
        val navTitle = if (vs.view == CalendarView.Day) navTexts.titleDay else navTexts.titleMonth
        val navSubtitle =
            if (vs.view == CalendarView.Day) navTexts.subtitleDay else navTexts.subtitleMonth
        val dayCount = dayEntries.count { it.isTimed && !it.allDay }
        val hours = scheduled.sumOf { ((it.endMinute ?: it.startMinute ?: 0) - (it.startMinute ?: 0)).coerceAtLeast(0) } / 60.0
        val agendaCountText = if (vs.view == CalendarView.Month) {
            "$dayCount 项安排"
        } else {
            val hText = if (hours >= 1) " · ${trimHours(hours)} 小时" else ""
            "$dayCount 项$hText"
        }

        val weekLabel = term?.let { t ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(t.startDate, vs.date)
            "第 ${(days / 7).toInt() + 1} 周"
        } ?: "本周"
        return CalendarV2UiState(
            view = vs.view,
            mode = vs.mode,
            selectedDate = vs.date,
            today = today,
            loaded = true,
            navTitle = navTitle,
            navSubtitle = navSubtitle,
            navTitleMonth = navTexts.titleMonth,
            navTitleDay = navTexts.titleDay,
            navSubtitleMonth = navTexts.subtitleMonth,
            navSubtitleDay = navTexts.subtitleDay,
            weekLabel = weekLabel,
            monthWeeks = monthWeeks,
            dayInfoTitle = dayInfoTitle,
            dayInfoMeta = dayInfoMeta,
            hideCoursesInMonth = vs.hideCoursesInMonth,
            keyDates = keyDates,
            showTodayPill = true,
            weekStrip = weekStrip,
            agendaTitle = "${vs.date.monthValue}月${vs.date.dayOfMonth}日 · ${weekdayCn(vs.date)}",
            agendaCountText = agendaCountText,
            agendaGroups = if (vs.view == CalendarView.Month) {
                if (monthRows.isEmpty()) emptyList() else listOf(CalendarAgendaGroup("", monthRows))
            } else agendaGroups,
            todoRows = todoRows,
            todoListRows = todoListRows,
            todoDoneText = "${todoRows.count { it.done }} / $todoTotal 完成",
        )
    }

    private fun buildMonthWeeks(
        vs: ViewState,
        agenda: Agenda,
        today: LocalDate,
    ): List<List<CalendarDayCell>> {
        val ym = YearMonth.from(vs.date)
        val first = ym.atDay(1)
        val gridStart = first.minusDays(((first.dayOfWeek.value + 6) % 7).toLong())
        return (0 until 6).map { w ->
            (0 until 7).map { d ->
                val date = gridStart.plusDays((w * 7 + d).toLong())
                // 月格只放课程 + 日程：待办不占月格（用户 2026-09-25 确认）。
                val dayEntries = agenda.scheduledOn(date)
                    .filter { it.isCourse || it.itemKind == ItemKind.EVENT }
                    .let { list -> if (vs.hideCoursesInMonth) list.filterNot { it.isCourse } else list }
                CalendarDayCell(
                    date = date,
                    dayOfMonth = date.dayOfMonth,
                    inMonth = YearMonth.from(date) == ym,
                    isToday = date == today,
                    isSelected = date == vs.date,
                    hasEvents = dayEntries.isNotEmpty(),
                    events = dayEntries.take(MonthCellMaxItems).map {
                        MonthCellEvent(label = it.title.take(4), colorToken = it.colorToken)
                    },
                    moreCount = (dayEntries.size - MonthCellMaxItems).coerceAtLeast(0),
                )
            }
        }
    }

    private fun buildWeekStrip(
        vs: ViewState,
        agenda: Agenda,
        today: LocalDate,
        weekStart: LocalDate,
    ): List<WeekStripDay> = (0 until 7).map { i ->
        val date = weekStart.plusDays(i.toLong())
        WeekStripDay(
            date = date,
            weekdayLabel = weekdayShort(date),
            dayOfMonth = date.dayOfMonth,
            isToday = date == today,
            isSelected = date == vs.date,
            hasEvents = agenda.on(date).any { it.isTimed },
        )
    }

    private fun navText(
        vs: ViewState,
        agenda: Agenda,
        term: com.phonlynn.oreplan.domain.model.Term?,
        routine: RoutineConfig,
    ): NavTexts {
        val weekIndex = term?.let { t ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(t.startDate, vs.date)
            (days / 7).toInt() + 1
        }
        // 两套文本都算出来（不管当前是哪个视图）：日视图的日期数字要从月版标题上淡入，
        // 副标题要「月版淡出 → 日版淡入」，所以两版必须同时可得（用户 2026-09-25）。
        val ym = YearMonth.from(vs.date)
        val count = (1..ym.lengthOfMonth()).sumOf { d ->
            agenda.on(ym.atDay(d)).count { it.isTimed && !it.allDay }
        }
        // V3：月视图「2025年3月」/「3月1日 – 3月31日 · 共 195 项安排」。
        val titleMonth = "${ym.year}年${ym.monthValue}月"
        val subtitleMonth =
            "${ym.monthValue}月1日 – ${ym.monthValue}月${ym.atEndOfMonth().dayOfMonth}日 · 共 $count 项安排"
        // V3：日视图「2025年3月12日」/「周三 · 共 10 项安排」。
        val dayCount = agenda.scheduledOn(vs.date).size
        val titleDay = "${vs.date.year}年${vs.date.monthValue}月${vs.date.dayOfMonth}日"
        val subtitleDay = "${weekdayShort(vs.date)} · 共 $dayCount 项安排"
        return NavTexts(titleMonth, subtitleMonth, titleDay, subtitleDay)
    }

    private fun countPeriods(entries: List<AgendaEntry>, routine: RoutineConfig): Int {
        val periods = RoutineSchedule.compute(routine)
        return entries.sumOf { e ->
            val start = e.startMinute ?: return@sumOf 0
            val end = e.endMinute ?: start + routine.periodMinutes
            periods.count { p -> p.startMinute < end && p.endMinute > start }
        }
    }

    private fun groupAgenda(
        scheduled: List<AgendaEntry>,
        afternoonStart: Int,
        eveningStart: Int,
    ): List<CalendarAgendaGroup> {
        val morning = mutableListOf<CalendarAgendaRowUi>()
        val afternoon = mutableListOf<CalendarAgendaRowUi>()
        val evening = mutableListOf<CalendarAgendaRowUi>()
        scheduled.forEach { e ->
            val start = e.startMinute ?: 0
            val row = rowOf(e, showEnd = true, courses = emptyMap())
            when {
                start < afternoonStart -> morning += row
                start < eveningStart -> afternoon += row
                else -> evening += row
            }
        }
        return buildList {
            if (morning.isNotEmpty()) add(CalendarAgendaGroup("上午", morning))
            if (afternoon.isNotEmpty()) add(CalendarAgendaGroup("下午", afternoon))
            if (evening.isNotEmpty()) add(CalendarAgendaGroup("晚上", evening))
        }
    }

    private fun rowOf(entry: AgendaEntry, showEnd: Boolean, courses: Map<String, Course>): CalendarAgendaRowUi = CalendarAgendaRowUi(
        key = entry.key,
        startMinute = entry.startMinute,
        endMinute = entry.endMinute,
        startText = entry.startMinute?.let { RoutineSchedule.formatMinute(it) } ?: "--:--",
        endText = if (showEnd) entry.endMinute?.let { RoutineSchedule.formatMinute(it) } else null,
        title = entry.title,
        metaText = entry.location ?: entry.courseId?.let { courses[it]?.defaultLocation },
        colorTag = entry.colorToken,
        isCourse = entry.isCourse,
                            isTask = entry.itemKind == ItemKind.TASK,
        itemId = entry.itemId,
        courseId = entry.courseId,
        createdAtMillis = entry.createdAtMillis,
    )

    private fun dueText(entry: AgendaEntry): String? {
        val due = entry.dueAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return null
        val d = due.toLocalDate()
        val hm = "%02d:%02d".format(due.hour, due.minute)
        return when (d) {
            LocalDate.now(zone) -> "今天 $hm 前"
            LocalDate.now(zone).plusDays(1) -> "明天 $hm 前"
            else -> "${d.monthValue}月${d.dayOfMonth}日 $hm 前"
        }
    }

    /** 待办行的展示模型：文案与颜色都在这里定，界面只负责画。 */
    private fun todoRowOf(entry: AgendaEntry): CalendarTodoRow {
        val done = entry.itemStatus == ItemStatus.DONE
        return CalendarTodoRow(
            itemId = entry.itemId.orEmpty(),
            title = entry.title,
            metaText = todoMeta(entry),
            done = done,
            priorityLabel = when {
                done -> "已完成"
                entry.priority >= 3 -> "紧急"
                entry.priority == 2 -> "重要"
                entry.priority == 1 -> "普通"
                else -> null
            },
            priorityKind = when {
                // 只有**已完成**才是灰色；低优先级/普通用浅绿（accent 软底），
                // 否则「普通」和「已完成」在视觉上分不开（用户 2026-09-23）。
                done -> TodayChipKind.Grey
                entry.priority >= 3 -> TodayChipKind.Rose
                entry.priority == 2 -> TodayChipKind.Amber
                else -> TodayChipKind.Accent
            },
        )
    }

    /**
     * 待办行标题下的小字（用户 2026-09-23 统一口径）：
     * **有截止日期就写截止日期**（位置不变），没有就写创建日期。
     */
    private fun todoMeta(entry: AgendaEntry): String? {
        dueText(entry)?.let { return it }
        val ms: Long? = entry.createdAtMillis
        val created = ms?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return null
        return "${created.monthValue}月${created.dayOfMonth}日创建"
    }

    // ---------------------------------------------------------------- 详情 / 操作

    /** 打开事件详情弹层：按需加载条目详情（提醒 / 笔记块 / 附件）。 */
    suspend fun loadDetail(entryKey: String): EventDetailUi? {
        val row = uiState.value.agendaGroups.flatMap { it.rows }.firstOrNull { it.key == entryKey }
        if (row == null) {
            // 待办不在 agendaGroups 里，必须单独兜底 ——
            // 否则点待办时详情是 null，浮层根本不会出现（用户 2026-09-23：
            // 「待办的详情根本无法显示」，于是点击只能跳编辑页）。
            val todo = uiState.value.todoRows.firstOrNull { it.itemId == entryKey } ?: return null
            return detailLoader.load(
                itemId = todo.itemId,
                courseId = null,
                title = todo.title,
                timeText = null,
                durationText = null,
                // 待办的「日期」行放截止时间（metaText 就是「今天 23:59 前」这类文案）。
                dateText = todo.metaText,
                locationText = null,
                isCourse = false,
            )
        }
        val timeText = if (row.endText != null) "${row.startText} – ${row.endText}" else row.startText
        return detailLoader.load(
            itemId = row.itemId,
            courseId = row.courseId,
            title = row.title,
            timeText = timeText,
            durationText = ScheduleDetailLoader.durationText(row.startMinute, row.endMinute),
            dateText = "${uiState.value.selectedDate.monthValue}月${uiState.value.selectedDate.dayOfMonth}日 " +
                weekdayShort(uiState.value.selectedDate),
            locationText = row.metaText,
            isCourse = row.isCourse,
        )
    }

    /**
     * 删除条目。
     *
     * ⚠️ 必须走 `DeleteItemUseCase`（与今日页一致）——
     * 直接调 `itemRepository.deleteSubtree` 只会删 `items` 一张表，
     * 而**提醒与重复例外没有外键、不会跟着走**，会留下孤儿
     *（用户 2026-10-03 报的「删除条目时也要删提醒」）。
     */
    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            deleteItemUseCase(itemId)
        }
    }

    /**
     * 归档完成后的待办。归档**复用项目既有约定** —— `ItemStatus.CANCELLED`
     * （目标归档也是它，见 PlanArchiveViewModel），不另加字段：
     * 待办列表与今日页本来就过滤掉了 CANCELLED，归档后自然从列表消失。
     */
    fun archiveItem(itemId: String) {
        viewModelScope.launch {
            val item = itemRepository.getById(itemId) ?: return@launch
            itemRepository.update(item.copy(status = ItemStatus.CANCELLED, updatedAt = Instant.now()))
        }
    }

    fun toggleTodo(itemId: String) {
        viewModelScope.launch {
            val item = itemRepository.getById(itemId) ?: return@launch
            val nowDone = item.status != ItemStatus.DONE
            itemRepository.update(
                item.copy(
                    status = if (nowDone) ItemStatus.DONE else ItemStatus.TODO,
                    completedAt = if (nowDone) Instant.now() else null,
                    updatedAt = Instant.now(),
                ),
            )
        }
    }

    companion object {
        fun weekStart(date: LocalDate): LocalDate =
            date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())

        fun weekdayCn(date: LocalDate): String = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "星期一"
            DayOfWeek.TUESDAY -> "星期二"
            DayOfWeek.WEDNESDAY -> "星期三"
            DayOfWeek.THURSDAY -> "星期四"
            DayOfWeek.FRIDAY -> "星期五"
            DayOfWeek.SATURDAY -> "星期六"
            DayOfWeek.SUNDAY -> "星期日"
        }

        fun weekdayShort(date: LocalDate): String = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "周一"
            DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"
            DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"
            DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
        }

        fun trimHours(hours: Double): String =
            if (hours % 1.0 == 0.0) hours.toInt().toString() else String.format("%.1f", hours)
    }
}

/** 月格最多显示的事件条数；超出部分在日格右上角以「+N」提示。 */
private const val MonthCellMaxItems = 6

/** 分钟数 → 「HH:mm」。 */
private fun minuteLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)


/** 日期栏的两套文本（月视图视角 / 日视图视角）。 */
private data class NavTexts(
    val titleMonth: String,
    val subtitleMonth: String,
    val titleDay: String,
    val subtitleDay: String,
)
