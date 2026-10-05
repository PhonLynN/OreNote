package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.Agenda
import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.routine.RoutineConfig
import com.phonlynn.oreplan.domain.routine.RoutineSchedule
import com.phonlynn.oreplan.domain.usecase.BuildAgendaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 今日页（V2）一行时间线条目。 */
data class TodayTimelineRow(
    val key: String,
    val timeText: String,
    val durationText: String?,
    val title: String,
    val metaText: String?,
    val isCurrent: Boolean,
    val isPast: Boolean,
    val isLast: Boolean,
    val itemId: String?,
    val courseId: String?,
)

/** 今日页待办行。 */
data class TodayTaskRow(
    val itemId: String,
    val title: String,
    val metaText: String?,
    val done: Boolean,
    val priorityLabel: String?,
    val priorityKind: TodayChipKind,
)

enum class TodayChipKind { Rose, Amber, Grey, Accent }

/** 临近截止行。 */
data class TodayDeadlineRow(
    val itemId: String,
    val title: String,
    val metaText: String,
    val chipText: String,
    val chipKind: TodayChipKind,
)

/** 明日预告行。 */
data class TodayTomorrowRow(
    val key: String,
    val timeText: String,
    val title: String,
    val metaText: String?,
    val itemId: String?,
    val courseId: String?,
)

/** Hero 卡（接下来的一节）。 */
data class TodayHero(
    val title: String,
    val pillText: String,
    val countdownText: String,
    val timeText: String,
    val locationText: String?,
    val teacherText: String?,
    val itemId: String?,
    val courseId: String?,
)

data class TodayV2UiState(
    val date: LocalDate = LocalDate.now(),
    val dateTitle: String = "",
    val loaded: Boolean = false,
    val hero: TodayHero? = null,
    val scheduledCount: Int = 0,
    val openTaskCount: Int = 0,
    val timeline: List<TodayTimelineRow> = emptyList(),
    val tasks: List<TodayTaskRow> = emptyList(),
    val tasksDone: Int = 0,
    val tasksTotal: Int = 0,
    val deadlines: List<TodayDeadlineRow> = emptyList(),
    val reviewText: String? = null,
    val reviewCourses: Int = 0,
    val reviewTasks: Int = 0,
    val reviewDone: Int = 0,
    val tomorrow: List<TodayTomorrowRow> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayV2ViewModel @Inject constructor(
    private val buildAgenda: BuildAgendaUseCase,
    private val itemRepository: ItemRepository,
    /**
     * 删除条目（连提醒、重复例外、清单一起清）。
     *
     * ⚠️ 用视图模型自己注入它、而不是直接调仓储：删除要清的**子表不止一张**，
     * 而它们都没有外键。少清一张就是一个孤儿（用户报过"删除后提醒还会响"）。
     */
    private val deleteItemUseCase: com.phonlynn.oreplan.domain.usecase.DeleteItemUseCase,
    private val courseRepository: CourseRepository,
    private val goalLogRepository: GoalLogRepository,
    private val settingsStore: AppSettingsStore,
    /** 日程详情的装配（与日程页共用，保证两处详情完全一致）。 */
    private val detailLoader: ScheduleDetailLoader,
    /** 详情浮层里要读附件缩略图。 */
    val attachmentStorage: com.phonlynn.oreplan.platform.attachment.AttachmentStorage,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 每 15 秒一跳：跨天检测 + “还有 X 分钟”倒计时刷新。 */
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(15_000)
        }
    }

    /**
     * 装配一条日程/课程的详情（设计稿 u0QP0i）。
     *
     * 今日页自己已经有「标题 / 时间 / 时长 / 地点」这些显示信息，
     * 这里只补齐提醒、备注、附件等细节 —— 与日程页共用 [ScheduleDetailLoader]，
     * 所以两处打开的详情必然一致。
     */
    suspend fun loadDetail(
        itemId: String?,
        courseId: String?,
        title: String,
        timeText: String?,
        durationText: String?,
        dateText: String?,
        locationText: String?,
        isCourse: Boolean,
    ): EventDetailUi = detailLoader.load(
        itemId = itemId,
        courseId = courseId,
        title = title,
        timeText = timeText,
        durationText = durationText,
        dateText = dateText,
        locationText = locationText,
        isCourse = isCourse,
    )

    /**
     * 详情浮层里的删除（与日程页一致）。
     *
     * ⚠️ 必须走 `DeleteItemUseCase`，**不能**直接 `itemRepository.deleteSubtree`。
     *
     * 直接调仓储只删 `items` 一张表，而**提醒与重复例外没有外键、不会跟着走** ——
     * 用户 2026-10-03 报的「删除条目时也要删提醒（孤儿提醒会响）」就是这个。
     * 详见 `DeleteItemUseCase` 的类注释（那里也写了为什么附件索引**不能**这么删）。
     */
    fun deleteItem(itemId: String) {
        viewModelScope.launch { deleteItemUseCase(itemId) }
    }

    /** 归档已完成的待办（复用 `ItemStatus.CANCELLED` 约定，见 CalendarV2ViewModel.archiveItem）。 */
    fun archiveItem(itemId: String) {
        viewModelScope.launch {
            val item = itemRepository.getById(itemId) ?: return@launch
            itemRepository.update(item.copy(status = ItemStatus.CANCELLED, updatedAt = Instant.now()))
        }
    }

    private val dateFlow = clock
        .map { Instant.now().atZone(zone).toLocalDate() }
        .distinctUntilChanged()

    val uiState: StateFlow<TodayV2UiState> = dateFlow
        .flatMapLatest { date ->
            combine(
                buildAgenda.observe(date, date.plusDays(1), zone),
                courseRepository.observeCourses(),
                goalLogRepository.observeReview(date.toEpochDay().toInt()),
                settingsStore.settings,
                clock,
            ) { agenda, courses, review, settings, nowTick ->
                buildState(
                    date = date,
                    now = nowTick,
                    agenda = agenda,
                    courses = courses,
                    reviewText = review?.text,
                    routine = settings.routine,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodayV2UiState(dateTitle = dateTitle(LocalDate.now(zone))),
        )

    fun toggleDone(itemId: String) {
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

    fun saveReview(text: String) {
        viewModelScope.launch {
            goalLogRepository.saveReview(LocalDate.now(zone).toEpochDay().toInt(), text.trim())
        }
    }

    // ---------------------------------------------------------------- 组装

    private fun buildState(
        date: LocalDate,
        now: Instant,
        agenda: Agenda,
        courses: List<Course>,
        reviewText: String?,
        routine: RoutineConfig,
    ): TodayV2UiState {
        val courseById = courses.associateBy { it.id }
        val nowMinute = now.atZone(zone).let { it.hour * 60 + it.minute }
        val isToday = date == LocalDate.now(zone)

        val scheduled = agenda.scheduledOn(date)
        val currentKey = scheduled.firstOrNull { entry ->
            val end = entry.endMinute ?: entry.startMinute ?: return@firstOrNull false
            (entry.startMinute ?: 0) <= nowMinute && nowMinute < end
        }?.key ?: scheduled.firstOrNull { entry ->
            (entry.startMinute ?: Int.MAX_VALUE) > nowMinute
        }?.key

        val timeline = scheduled.mapIndexed { index, entry ->
            val start = entry.startMinute ?: 0
            val end = entry.endMinute
            TodayTimelineRow(
                key = entry.key,
                timeText = RoutineSchedule.formatMinute(start),
                durationText = end?.let { "${it - start} 分钟" },
                title = entry.title,
                metaText = metaFor(entry, courseById),
                isCurrent = entry.key == currentKey,
                isPast = isToday && (end ?: start) <= nowMinute,
                isLast = index == scheduled.lastIndex,
                itemId = entry.itemId,
                courseId = entry.courseId,
            )
        }

        val heroEntry = scheduled.firstOrNull { it.key == currentKey }
        val hero = heroEntry?.let { entry ->
            val start = entry.startMinute ?: 0
            val end = entry.endMinute
            val course = entry.courseId?.let { courseById[it] }
            val periodLabel = end?.let { RoutineSchedule.periodLabel(routine, start, it) }
            val ongoing = start <= nowMinute && nowMinute < (end ?: start)
            val countdown = when {
                ongoing -> end?.let { "剩余 ${it - nowMinute} 分钟" } ?: "进行中"
                else -> {
                    val diff = start - nowMinute
                    when {
                        diff <= 0 -> "即将开始"
                        diff < 60 -> "还有 $diff 分钟"
                        else -> "还有 ${diff / 60} 小时 ${diff % 60} 分钟"
                    }
                }
            }
            TodayHero(
                title = entry.title,
                pillText = when {
                    periodLabel != null && ongoing -> "进行中 · $periodLabel"
                    periodLabel != null -> "接下来 · $periodLabel"
                    ongoing -> "进行中"
                    else -> "接下来"
                },
                countdownText = countdown,
                timeText = if (end != null) {
                    "${RoutineSchedule.formatMinute(start)} – ${RoutineSchedule.formatMinute(end)}"
                } else {
                    RoutineSchedule.formatMinute(start)
                },
                locationText = entry.location ?: course?.defaultLocation,
                teacherText = course?.teacher,
                itemId = entry.itemId,
                courseId = entry.courseId,
            )
        }

        // 按重要程度（优先级）从上到下：高 → 低（用户 2026-09-24）。
        // sortedByDescending 是稳定排序，同优先级内保持原有顺序。
        val taskEntries = agenda.taskCardEntries(date, isToday).sortedByDescending { it.priority }
        val tasks = taskEntries.map { entry ->
            val done = entry.itemStatus == ItemStatus.DONE
            TodayTaskRow(
                itemId = entry.itemId.orEmpty(),
                title = entry.title,
                metaText = taskMeta(entry, done, zone),
                done = done,
                priorityLabel = priorityLabel(entry, done),
                priorityKind = priorityKind(entry, done),
            )
        }
        val doneCount = tasks.count { it.done }

        // 「临近截止」只收**3 天以内**的（已过期的也算——它更需要被看到）。
        // 原来这里没有任何时间窗口，两个月后到期的事项也会挤进来（用户 2026-09-23）。
        val deadlines = agenda.openTasks
            .filter { it.itemStatus != ItemStatus.DONE && it.dueAtMillis != null }
            .filter { (it.dueAtMillis ?: 0L) - now.toEpochMilli() < 3L * 24 * 3600_000L }
            .sortedBy { it.dueAtMillis }
            .map { entry ->
                val dueAt = Instant.ofEpochMilli(entry.dueAtMillis!!)
                TodayDeadlineRow(
                    itemId = entry.itemId.orEmpty(),
                    title = entry.title,
                    metaText = deadlineMeta(dueAt, zone),
                    chipText = deadlineChipText(dueAt, now),
                    chipKind = deadlineChipKind(dueAt, now),
                )
            }

        val tomorrow = agenda.scheduledOn(date.plusDays(1)).map { entry ->
            TodayTomorrowRow(
                key = "tomorrow:${entry.key}",
                timeText = entry.startMinute?.let { RoutineSchedule.formatMinute(it) } ?: "--:--",
                title = entry.title,
                metaText = entry.location ?: entry.courseId?.let { courseById[it]?.defaultLocation },
                itemId = entry.itemId,
                courseId = entry.courseId,
            )
        }

        val courseCount = scheduled.count { it.isCourse }
        return TodayV2UiState(
            date = date,
            dateTitle = dateTitle(date),
            loaded = true,
            hero = hero,
            scheduledCount = scheduled.size,
            openTaskCount = agenda.openTasks.count { it.itemStatus != ItemStatus.DONE },
            timeline = timeline,
            tasks = tasks,
            tasksDone = doneCount,
            tasksTotal = tasks.size,
            deadlines = deadlines,
            reviewText = reviewText,
            reviewCourses = courseCount,
            reviewTasks = tasks.size,
            reviewDone = doneCount,
            tomorrow = tomorrow,
        )
    }

    private fun metaFor(entry: AgendaEntry, courses: Map<String, Course>): String? {
        val parts = mutableListOf<String>()
        (entry.location ?: entry.courseId?.let { courses[it]?.defaultLocation })?.let { parts += it }
        entry.courseId?.let { courses[it]?.teacher }?.let { parts += it }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun priorityLabel(entry: AgendaEntry, done: Boolean): String? = when {
        done -> "已完成"
        entry.priority >= 3 -> "紧急"
        entry.priority == 2 -> "重要"
        entry.priority == 1 -> "普通"
        else -> null
    }

    private fun priorityKind(entry: AgendaEntry, done: Boolean): TodayChipKind = when {
        done -> TodayChipKind.Grey
        entry.priority >= 3 -> TodayChipKind.Rose
        entry.priority == 2 -> TodayChipKind.Amber
        // 低优先级/普通 = 浅绿（与日程页待办一致），灰色只留给「已完成」。
        else -> TodayChipKind.Accent
    }

    private fun taskMeta(entry: AgendaEntry, done: Boolean, zone: ZoneId): String? {
        val due = entry.dueAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val dueText = due?.let {
            val d = it.toLocalDate()
            val today = LocalDate.now(zone)
            val hm = "%02d:%02d".format(it.hour, it.minute)
            when (d) {
                today -> "今天 $hm 前"
                today.plusDays(1) -> "明天 $hm 前"
                else -> "${d.monthValue}月${d.dayOfMonth}日 $hm 前"
            }
        }
        // 没有截止日期时写**创建日期**（与日程页待办同一口径，用户 2026-09-23）；
        // 原来是「有空再做」这种没有信息量的占位文案。
        val ms: Long? = entry.createdAtMillis
        val createdText = ms?.let {
            val c = Instant.ofEpochMilli(it).atZone(zone)
            "${c.monthValue}月${c.dayOfMonth}日创建"
        }
        return when {
            done -> listOfNotNull(dueText, "已打卡").joinToString(" · ")
            dueText != null -> dueText
            else -> createdText
        }
    }

    private fun deadlineMeta(dueAt: Instant, zone: ZoneId): String {
        val z = dueAt.atZone(zone)
        val today = LocalDate.now(zone)
        val hm = "%02d:%02d".format(z.hour, z.minute)
        return when (z.toLocalDate()) {
            today -> "今天 $hm 截止"
            today.plusDays(1) -> "明天 $hm 截止"
            else -> "${z.monthValue}月${z.dayOfMonth}日 $hm 截止"
        }
    }

    private fun deadlineChipText(dueAt: Instant, now: Instant): String {
        val diff = dueAt.toEpochMilli() - now.toEpochMilli()
        return when {
            diff <= 0 -> "已逾期"
            diff < 3600_000L -> "剩 ${(diff / 60_000L).coerceAtLeast(1)} 分钟"
            diff < 24 * 3600_000L -> "剩 ${(diff + 3599_999L) / 3600_000L} 小时"
            else -> "剩 ${(diff + 86_399_999L) / 86_400_000L} 天"
        }
    }

    private fun deadlineChipKind(dueAt: Instant, now: Instant): TodayChipKind =
        // 临期 = **3 天以内**（原来写的是 24 小时，等于刚过一天就只剩"重要"色，
        // 用户 2026-09-23 要求放宽）。
        if (dueAt.toEpochMilli() - now.toEpochMilli() < 3 * 24 * 3600_000L) TodayChipKind.Rose else TodayChipKind.Amber

    private fun dateTitle(date: LocalDate): String {
        val weekday = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "星期一"
            DayOfWeek.TUESDAY -> "星期二"
            DayOfWeek.WEDNESDAY -> "星期三"
            DayOfWeek.THURSDAY -> "星期四"
            DayOfWeek.FRIDAY -> "星期五"
            DayOfWeek.SATURDAY -> "星期六"
            DayOfWeek.SUNDAY -> "星期日"
        }
        return "${date.year}年${date.monthValue}月${date.dayOfMonth}日 · $weekday"
    }
}
