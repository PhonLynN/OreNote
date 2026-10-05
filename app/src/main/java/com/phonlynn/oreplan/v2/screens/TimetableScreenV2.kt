package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.data.settings.AppSettings
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.Term
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import com.phonlynn.oreplan.domain.routine.PeriodTime
import com.phonlynn.oreplan.domain.routine.RoutineConfig
import com.phonlynn.oreplan.domain.routine.RoutineSchedule
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VScreenTitle
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VTab
import com.phonlynn.oreplan.v2.components.VTabBottomPadding
import com.phonlynn.oreplan.v2.components.VTabScaffold
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

private val PERIOD_CELL_HEIGHT = 42.dp
private val GUTTER_WIDTH = 34.dp

/** 表头每一天的格子高度。「今天」的底色块与选中线框共用它。 */
private val DAY_HEADER_HEIGHT = 34.dp

// ---------------------------------------------------------------- 课程卡片几何
//
// 目标（用户 2026-09-21）：**一行至少显示 4 个汉字**，并尽量把信息显示全。
//
// 以 360dp 逻辑宽（1080px / 480dpi，最保守的设备）算：
//   每列宽 = (360 − 左右页面内边距 40 − 节次列 34) / 5 = 57.2dp
//   卡片文字宽 = 列宽 − 2×外距 − 2×内距
// 收紧前：外 2 / 内 6 / 11sp → 文字宽 41.2dp，**只能放 3.7 字**（用户反馈「最多三个」）。
// 收紧后：外 1 / 内 3 / 10sp → 文字宽 49.2dp，**可放 4.9 字**。
//
// 字号只降到 10sp（不降到 9sp）：中文在 9sp 下可读性明显变差，
// 而边距收紧已经足以达到「一行 4 字」的目标。
private val CELL_OUTER_PAD = 1.dp
private val CELL_INNER_PAD_H = 3.dp
private val CELL_INNER_PAD_V = 2.dp

/** 课程名字号。10sp：收紧边距后一行可放约 4.9 个汉字。 */
private val CELL_NAME_SP = 10.sp
private val CELL_NAME_LINE_H = 12.sp

/** 补充信息（地点/教师/周次）字号。8sp：三行合计 30dp，放得进单节格子。 */
private val CELL_SUB_SP = 8.sp
private val CELL_SUB_LINE_H = 10.sp

data class TimetableV2UiState(
    val term: Term? = null,
    val courses: List<Course> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val today: LocalDate = LocalDate.now(),
    val weekNumber: Int = 1,
    val currentWeekNumber: Int? = null,
    val periodTimes: List<PeriodTime> = emptyList(),
    val weekDays: List<LocalDate> = emptyList(),
    val weekStart: LocalDate? = null,
    val weekEnd: LocalDate? = null,
    val dayBlocks: Map<Int, List<GridBlock>> = emptyMap(),
    val activeInstanceCount: Int = 0,
    val loaded: Boolean = false,
) {
    val totalPeriods: Int get() = periodTimes.size
}

@HiltViewModel
class TimetableV2ViewModel @Inject constructor(
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val appSettings: AppSettingsStore,
    /** 课程详情浮窗的装配（与日程页、今日页共用同一个 loader）。 */
    private val detailLoader: ScheduleDetailLoader,
    /** 浮窗读附件需要它（与日程页同一份存储）。 */
    val attachmentStorage: com.phonlynn.oreplan.platform.attachment.AttachmentStorage,
) : ViewModel() {

    /**
     * 打开课程详情浮窗（用户 2026-09-26：点课表上的课程直接开详情，不是跳编辑页）。
     *
     * 与日程页同一套装配：备注块挂在 `courseId` 上，附件分 item 级与备注块级，
     * loader 两处都收 —— 所以这里只要给出课程的基本文本即可。
     */
    suspend fun loadCourseDetail(
        courseId: String,
        timeText: String?,
        dateText: String?,
        locationText: String?,
        startMinute: Int? = null,
        endMinute: Int? = null,
    ): EventDetailUi? {
        val course = courseRepository.getCourse(courseId) ?: return null
        return detailLoader.load(
            itemId = null,
            courseId = courseId,
            title = course.name,
            timeText = timeText,
            durationText = ScheduleDetailLoader.durationText(startMinute, endMinute),
            dateText = dateText,
            locationText = locationText?.takeIf { it.isNotBlank() } ?: course.defaultLocation,
            isCourse = true,
        )
    }

    /** 相对「本周」的周偏移。0 = 本周。 */
    private val weekOffset = MutableStateFlow(0)

    val uiState: StateFlow<TimetableV2UiState> = combine(
        termRepository.observeActiveTerm(),
        courseRepository.observeCourses(),
        courseRepository.observeSessions(),
        appSettings.settings,
        weekOffset,
    ) { term, courses, sessions, settings, offset ->
        buildState(term, courses, sessions, settings, offset)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TimetableV2UiState(),
    )

    private fun buildState(
        term: Term?,
        courses: List<Course>,
        sessions: List<CourseSession>,
        settings: AppSettings,
        offset: Int,
    ): TimetableV2UiState {
        val today = LocalDate.now()
        val currentWeek = term?.let { TermClock.weekNumberOn(it.startDate, it.totalWeeks, today) }
        val anchorWeek = when {
            term == null -> 1
            currentWeek != null -> currentWeek
            today < term.startDate -> 1
            else -> term.totalWeeks
        }
        val weekNumber = if (term == null) 1 else (anchorWeek + offset).coerceIn(1, term.totalWeeks)

        val periodTimes = RoutineSchedule.compute(settings.routine)
        val weekStart = term?.let { TermClock.weekStartDate(it.startDate, weekNumber) }
        val weekEnd = term?.let { TermClock.weekEndDate(it.startDate, weekNumber) }
        val weekDays = term?.let { (1..5).map { d -> TermClock.dateOf(it.startDate, weekNumber, d) } }.orEmpty()

        val dayBlocks = buildDayBlocks(term, courses, sessions, settings.routine, weekNumber)
        val activeCount = dayBlocks.values.flatten().count { it.active }

        return TimetableV2UiState(
            term = term,
            courses = courses,
            settings = settings,
            today = today,
            weekNumber = weekNumber,
            currentWeekNumber = currentWeek,
            periodTimes = periodTimes,
            weekDays = weekDays,
            weekStart = weekStart,
            weekEnd = weekEnd,
            dayBlocks = dayBlocks,
            activeInstanceCount = activeCount,
            loaded = true,
        )
    }

    private fun buildDayBlocks(
        term: Term?,
        courses: List<Course>,
        sessions: List<CourseSession>,
        config: RoutineConfig,
        weekNumber: Int,
    ): Map<Int, List<GridBlock>> {
        if (term == null) return emptyMap()
        val courseById = courses.associateBy { it.id }
        val result = HashMap<Int, MutableList<GridBlock>>()
        for (session in sessions) {
            val course = courseById[session.courseId]
            val name = course?.name?.takeIf { it.isNotBlank() } ?: "未命名课程"
            val range = periodRangeOf(config, session.startMinuteOfDay, session.endMinuteOfDay)
                ?: continue
            val active = session.startWeek <= weekNumber &&
                weekNumber <= session.endWeek &&
                session.parity.matches(weekNumber)
            val block = GridBlock(
                sessionId = session.id,
                courseId = session.courseId,
                name = name,
                location = session.location ?: course?.defaultLocation,
                teacher = course?.teacher,
                weeks = weeksLabel(session, term.totalWeeks),
                startPeriod = range.first,
                endPeriod = range.last,
                colorHex = course?.colorHex,
                active = active,
            )
            result.getOrPut(session.dayOfWeek) { mutableListOf() }.add(block)
        }
        result.values.forEach { it.sortBy { b -> b.startPeriod } }
        return result
    }

    fun shiftWeek(delta: Int) {
        val term = uiState.value.term ?: return
        val current = uiState.value
        val anchor = when {
            current.currentWeekNumber != null -> current.currentWeekNumber
            current.today < term.startDate -> 1
            else -> term.totalWeeks
        }
        val next = (weekOffset.value + delta)
            .coerceIn(-(anchor - 1), term.totalWeeks - anchor)
        if (next != weekOffset.value) weekOffset.value = next
    }

    fun goToCurrentWeek() {
        weekOffset.value = 0
    }
}

@Composable
fun TimetableScreenV2(
    navigate: (String) -> Unit,
    onSelectTab: (VTab) -> Unit,
    onAi: () -> Unit,
    viewModel: TimetableV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 课程详情浮窗（用户 2026-09-26：点课表上的课程直接开详情，不再跳编辑页）。
    // 与日程页共用同一套 DetailLayer + ScheduleDetailLoader，所以展示内容、
    // 动画、模糊都天然一致。
    var courseDetail by remember { mutableStateOf<EventDetailUi?>(null) }

    /** 打开课程详情：装配是挂起的（要读备注块/附件）。 */
    fun openCourseDetail(
        courseId: String,
        startMinute: Int? = null,
        endMinute: Int? = null,
        dateText: String? = null,
        dayOfWeek: Int? = null,
        locationOverride: String? = null,
    ) {
        val timeText = if (startMinute != null && endMinute != null) {
            "${RoutineSchedule.formatMinute(startMinute)} – ${RoutineSchedule.formatMinute(endMinute)}"
        } else {
            null
        }
        // 日期文案：周网格里由「星期几」推回具体日期；展开当日就用自己的日期。
        val date = dateText ?: run {
            val term = state.term
            val d = dayOfWeek
            if (term != null && d != null) {
                val date = TermClock.dateOf(term.startDate, state.weekNumber, d)
                "${date.monthValue}月${date.dayOfMonth}日 ${CalendarV2ViewModel.weekdayShort(date)}"
            } else {
                null
            }
        }
        scope.launch {
            courseDetail = viewModel.loadCourseDetail(
                courseId = courseId,
                timeText = timeText,
                dateText = date,
                locationText = locationOverride,
                startMinute = startMinute,
                endMinute = endMinute,
            )
        }
    }

    // 展开了哪一天（null = 周视图）。点表头日期切换。
    // 展开后**替换**周视图，显示该日的课程列表（用户 2026-09-22 要求）。
    var expandedDay by remember { mutableStateOf<Int?>(null) }

    // 页面内容交给 DetailLayer：它在浮层显示期间给内容加背景模糊，
    // 并保证退场动画播完之前数据不被清空（与日程页同一套机制）。
    DetailLayer(
        target = courseDetail,
        storage = viewModel.attachmentStorage,
        onRequestClose = { courseDetail = null },
        onEdit = { d -> d.courseId?.let { navigate(V2Routes.courseEditor(it)) } },
        // 课程在这套浮层里没有删除/归档入口（日程页对课程也不给）——
        // 删除在课程编辑页内，保持单一入口。
        onDelete = {},
        onArchive = {},
    ) {
    VTabScaffold(active = VTab.Timetable, onSelect = onSelectTab) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = 10.dp, bottom = VTabBottomPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VScreenTitle(
                title = "课表",
                overline = state.term?.name ?: "未设置学期",
                onSidebar = { navigate(V2Routes.TT_SETTINGS) },
                onAi = onAi,
            )

            // 加载完成前**不渲染任何内容**：uiState 的初始值是 term=null，
            // 若直接据此渲染，切到课表时会先闪一帧「还没有设置学期」空状态
            //（用户 2026-09-30 报「日程↔课表切换时课表闪现一帧空状态」）。
            // 这里用 VM 已有的 loaded 标志门控首帧 —— 只加判断，不改数据流。
            if (!state.loaded) {
                // 首帧占位：与页面同色，避免闪一下再出现。
                Box(Modifier.fillMaxSize().background(VColors.bg))
            } else if (state.term == null) {
                EmptyTerm(onOpenSettings = { navigate(V2Routes.TT_SETTINGS) })
            } else {
                // ⚠️ 周视图（含周网格）与「展开当日」是**互斥**的两套内容。
                //
                // 之前 TimetableBoard 写在这个 if 之外，于是展开时它仍然渲染，
                // 新内容被追加到很下方 —— 实测表现为「只有选中线框，页面没变化」。
                // 现在两者严格二选一：展开即整块替换。
                val expanded = expandedDay
                if (expanded != null) {
                    // 展开当日：**表头日期栏仍在**（它是切换入口，必须能再点一下收回），
                    // 周网格、提示条、周末、课程清单全部让位。
                    WeekSelector(state, onPrev = { viewModel.shiftWeek(-1) }, onNext = { viewModel.shiftWeek(1) })
                    DayHeaderCard(
                        state = state,
                        expandedDay = expandedDay,
                        onSelectDay = { day -> expandedDay = if (expandedDay == day) null else day },
                    )
                    ExpandedDaySection(
                        state = state,
                        day = expanded,
                        onClose = { expandedDay = null },
                        onOpenCourse = { id ->
                            // 展开当日：带具体日期（用户 2026-09-26：不再跳编辑页）。
                            openCourseDetail(id, dayOfWeek = expanded)
                        },
                    )
                } else {
                    WeekSelector(state, onPrev = { viewModel.shiftWeek(-1) }, onNext = { viewModel.shiftWeek(1) })
                    TimetableBoard(
                        state = state,
                        expandedDay = expandedDay,
                        onSelectDay = { day -> expandedDay = if (expandedDay == day) null else day },
                        onOpenCourse = { block, dayOfWeek ->
                            // 周网格里的课程格：按它占的节次换算出起止时间，
                            // 这样详情浮层的「时间」行与格子里显示的一致。
                            val s = state.periodTimes.getOrNull(block.startPeriod - 1)?.startMinute
                            val e = state.periodTimes.getOrNull(block.endPeriod - 1)?.endMinute
                            openCourseDetail(
                                courseId = block.courseId,
                                startMinute = s,
                                endMinute = e,
                                dayOfWeek = dayOfWeek,
                                locationOverride = block.location,
                            )
                        },
                    ) { dayOfWeek, period ->
                        val day = TermClock.dateOf(state.term!!.startDate, state.weekNumber, dayOfWeek)
                        val startMinute = state.periodTimes.getOrNull(period - 1)?.startMinute ?: return@TimetableBoard
                        navigate(V2Routes.courseEditor(courseId = null, day = day, startMinute = startMinute))
                    }
                    HintBar()
                    if (state.settings.showWeekend) {
                        WeekendSection(state)
                    }
                    CourseListSection(
                        state,
                        onOpenCourse = { id -> openCourseDetail(id, dayOfWeek = null) },
                    )
                    AllCoursesLink(state.courses.size, onOpenAll = { navigate(V2Routes.COURSES) })
                }
            }
        }
    }
    }
}

@Composable
private fun EmptyTerm(onOpenSettings: () -> Unit) {
    VCard {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Lucide.GraduationCap, contentDescription = null, Modifier.size(28.dp), tint = VColors.ink3)
            Spacer(Modifier.height(12.dp))
            VText("还没有设置学期", VTypo.bodyMed, color = VColors.ink, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            VText("先去课表设置里填开学日期与周数，才能看到周网格。", VTypo.caption, color = VColors.ink3, align = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(VColors.accent, RoundedCornerShape(13.dp))
                    .vPressable(scaleDown = 0.97f, onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                VText("去设置学期", VTypo.button, color = Color.White)
            }
        }
    }
}

@Composable
private fun WeekSelector(state: TimetableV2UiState, onPrev: () -> Unit, onNext: () -> Unit) {
    VCard(radius = 14.dp) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WeekNavButton(Lucide.ChevronLeft, onPrev)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                VText("第 ${state.weekNumber} 周", VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                val rangeText = buildString {
                    state.weekStart?.let { append(it.format(WEEK_RANGE_FORMAT)) }
                    if (state.weekStart != null && state.weekEnd != null) append(" – ")
                    state.weekEnd?.let { append(it.format(WEEK_RANGE_FORMAT)) }
                    append(" · ${state.activeInstanceCount} 节次")
                }
                VText(rangeText, VTypo.caption, color = VColors.ink3, maxLines = 1)
            }
            WeekNavButton(Lucide.ChevronRight, onNext)
        }
    }
}

@Composable
private fun WeekNavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .background(VColors.surface2, RoundedCornerShape(13.dp))
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = VColors.ink2)
    }
}

/**
 * 表头日期栏的**卡片外壳**（展开当日时用）。
 *
 * 与 [TimetableBoard] 里的表头保持完全相同的卡片几何
 *（`VCard(18.dp)` + 相同的内边距），
 * 所以展开前后表头位置不跳动，只有下方内容被替换。
 */
@Composable
private fun DayHeaderCard(
    state: TimetableV2UiState,
    expandedDay: Int?,
    onSelectDay: (Int) -> Unit,
) {
    VCard(radius = 18.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp),
        ) {
            DayHeaderRow(state = state, expandedDay = expandedDay, onSelectDay = onSelectDay)
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * 表头日期栏：周几 + 日期，点某天就展开/收起当天课程。
 *
 * 单独抽出来是因为它在**两种状态下都要在**：
 *  · 周视图：它是周网格的表头；
 *  · 展开当日：它仍是切换入口 —— 必须能让用户再点一下收回去。
 */
@Composable
private fun DayHeaderRow(
    state: TimetableV2UiState,
    expandedDay: Int?,
    onSelectDay: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(34.dp)) {
                Spacer(Modifier.width(GUTTER_WIDTH))
                state.weekDays.forEach { date ->
                    val isToday = date == state.today
                    val dow = date.dayOfWeek.value
                    val isExpanded = expandedDay == dow
                    Column(
                        Modifier
                            .weight(1f)
                            .height(DAY_HEADER_HEIGHT)
                            // 点日期 → 展开/收起当日课程（用户 2026-09-22）。
                            .vPressable(scaleDown = 0.94f) { onSelectDay(dow) }
                            // 今天的浅底（原有样式，保持不变）
                            .then(
                                if (isToday) {
                                    Modifier.background(VColors.accentSoft, RoundedCornerShape(9.dp))
                                } else {
                                    Modifier
                                },
                            )
                            // 选中线框：**强调色描边**，尺寸与圆角同「今天」的底色块
                            // （即它的外轮廓），所以只是把填充换成了边框。
                            .then(
                                if (isExpanded) {
                                    Modifier.border(
                                        width = 1.5.dp,
                                        color = VColors.accent,
                                        shape = RoundedCornerShape(9.dp),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        VText(
                            WEEKDAY_LABELS[dow - 1],
                            VTypo.micro.copy(fontSize = 9.sp),
                            color = if (isToday || isExpanded) VColors.accent else VColors.ink3,
                            maxLines = 1,
                        )
                        VText(
                            date.dayOfMonth.toString(),
                            VTypo.numTime.copy(fontSize = 12.sp),
                            color = if (isToday || isExpanded) VColors.accent else VColors.ink,
                            maxLines = 1,
                        )
                    }
                }
            }
}

@Composable
private fun TimetableBoard(
    state: TimetableV2UiState,
    /** 当前展开的那一天（null = 周视图）。用于表头选中线框。 */
    expandedDay: Int?,
    /** 点表头日期：切换展开。 */
    onSelectDay: (Int) -> Unit,
    /** 点课程格 → 开课程详情。 */
    onOpenCourse: (GridBlock, Int) -> Unit,
    onEmptyCell: (dayOfWeek: Int, period: Int) -> Unit,
) {
    VCard(radius = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)) {
            // 表头（周几 + 日期）：点日期可展开当日。
            DayHeaderRow(state = state, expandedDay = expandedDay, onSelectDay = onSelectDay)
            Spacer(Modifier.height(6.dp))
            // 表体：节次列 + 5 个星期列
            Row(Modifier.fillMaxWidth()) {
                PeriodGutter(state.periodTimes, state.settings.showPeriodTimes)
                for (day in 1..5) {
                    val blocks = state.dayBlocks[day].orEmpty()
                    DayColumn(
                        blocks = blocks,
                        totalPeriods = state.totalPeriods,
                        modifier = Modifier.weight(1f),
                        onOpenCourse = { block -> onOpenCourse(block, day) },
                        onEmptyCell = { period -> onEmptyCell(day, period) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodGutter(periodTimes: List<PeriodTime>, showPeriodTimes: Boolean) {
    Column(Modifier.width(GUTTER_WIDTH)) {
        periodTimes.forEach { period ->
            Column(
                Modifier.height(PERIOD_CELL_HEIGHT),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VText(period.period.toString(), VTypo.numChip, color = VColors.ink2, maxLines = 1)
                if (showPeriodTimes) {
                    VText(
                        RoutineSchedule.formatMinute(period.startMinute),
                        VTypo.micro.copy(fontSize = 9.sp, fontFamily = com.phonlynn.oreplan.v2.theme.NumFont),
                        color = VColors.ink3,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    blocks: List<GridBlock>,
    totalPeriods: Int,
    modifier: Modifier = Modifier,
    onOpenCourse: (GridBlock) -> Unit,
    onEmptyCell: (Int) -> Unit,
) {
    // ⚠️ **先过滤非本周课程，再分段**。顺序不能反。
    //
    // 分段函数按「连续节次覆盖的课程集合相同」来合并。若把非本周的课也喂进去，
    // 同一门课的多条 session（例如「概预算正课」同时有 3-13周/3-5节 与 14-15周/3-4节）
    // 会同时参与分段 → 3-4 与 5 的集合不同 → **一门三节连排被从中间切成两段**
    //（用户 2026-09-22 实测）。
    //
    // 过滤后，同一门课当周只会命中一条 session，分段自然还原为完整的 3-5 节。
    val activeBlocks = remember(blocks) { blocks.filter { it.active } }
    val cells = remember(activeBlocks, totalPeriods) {
        buildDayCells(activeBlocks, totalPeriods)
    }

    Column(modifier) {
        cells.forEach { cell ->
            if (cell.isEmpty) {
                // 空白格：占满该段高度，可点新建。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(PERIOD_CELL_HEIGHT * cell.span)
                        .vPressable(scaleDown = 0.985f, onClick = { onEmptyCell(cell.startPeriod) }),
                )
            } else {
                CourseBlockCell(cell = cell, blocks = cell.blocks, onOpen = onOpenCourse)
            }
        }
    }
}

@Composable
private fun CourseBlockCell(
    cell: DayCell,
    /** 该格要显示的课程（已滤掉非本周）。至少一门。 */
    blocks: List<GridBlock>,
    /** 点课程格 → 开课程详情（用户 2026-09-26：之前周网格里的课程格**完全没有点击入口**，点了没反应）。 */
    onOpen: (GridBlock) -> Unit,
) {
    // ---- 高度账（这是「五列对齐」的关键，改动前务必看这里）----
    //
    // 一格的总高度 = PERIOD_CELL_HEIGHT × span（与空白格**同一个口径**）。
    // 多余的高度不能凭空扣掉：旧实现写成 `PERIOD_CELL_HEIGHT * span - 4.dp`，
    // 于是每张卡片比格子矮 4dp，一列几张就累计少几十 dp ——
    // 表现为「相邻卡片缝隙消失、越往下越往上偏」（用户 2026-09-22 实测）。
    //
    // 现在的做法：外层占满 `PERIOD_CELL_HEIGHT × span`，用**纵向 padding** 做出
    // 与横向一致的间距；卡片本体填满 padding 之后的剩余空间。
    // 这样「格子高度」永远由 span 决定，加/减间距都不会破坏列间对齐。
    Box(
        Modifier
            .fillMaxWidth()
            .height(PERIOD_CELL_HEIGHT * cell.span)
            // 纵向间距 = 横向间距（用户 2026-09-22 要求）。
            // 上下各留 CELL_OUTER_PAD，所以间距是它的两倍。
            .padding(horizontal = CELL_OUTER_PAD, vertical = CELL_OUTER_PAD),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    CoursePalette.tintFor(blocks.first().colorHex, blocks.first().courseId).soft,
                    RoundedCornerShape(9.dp),
                )
                // 点课程格开详情。多门课堆叠时点**具体那一行**（见下），
                // 这里只作为容器兼兼容路径（整格可点 → 取第一门）。
                .vPressable(scaleDown = 0.97f) { blocks.firstOrNull()?.let(onOpen) }
                .padding(horizontal = CELL_INNER_PAD_H, vertical = CELL_INNER_PAD_V),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 同格多门课（同周次冲突）：纵向堆叠，信息不丢、格子高度不变。
            // 常见情况只有一门（非本周课程已过滤）。
            blocks.forEachIndexed { i, block ->
                if (i > 0) {
                    Spacer(Modifier.height(2.dp))
                }
                CourseBlockText(
                    block = block,
                    span = cell.span,
                    modifier = Modifier.vPressable(scaleDown = 0.97f) { onOpen(block) },
                )
            }
        }
    }
}

/** 格子里的一门课（名称 + 补充信息）。多门堆叠时重复使用。 */
@Composable
private fun CourseBlockText(
    block: GridBlock,
    span: Int,
    modifier: Modifier = Modifier,
) {
    // 单节格子纵向只够「课程名 2 行 + 一行补充信息」：
    //   42dp − 上下留白 = 约 38dp；名称 2×12=24 + 补充 1×10 = 34dp。
    // 所以单节只显示名称 + 地点，教师/周次留给更高的连排格子。
    val compactCell = span == 1
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VText(
            block.name,
            VTypo.caption.copy(fontSize = CELL_NAME_SP, lineHeight = CELL_NAME_LINE_H),
            color = VColors.ink,
            // 单节最多 2 行（多一行就挤掉补充信息）；连排格子更高，允许 3 行。
            maxLines = if (compactCell) 2 else 3,
            align = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        if (block.location != null) {
            VText(
                block.location,
                VTypo.micro.copy(fontSize = CELL_SUB_SP, lineHeight = CELL_SUB_LINE_H),
                color = VColors.ink2,
                maxLines = 1,
                align = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 教师与周次只在连排格子显示 —— 单节格子放不下（会挤掉名称）。
        if (!compactCell) {
            if (block.teacher != null) {
                VText(
                    block.teacher,
                    VTypo.micro.copy(fontSize = CELL_SUB_SP, lineHeight = CELL_SUB_LINE_H),
                    color = VColors.ink2,
                    maxLines = 1,
                    align = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            VText(
                block.weeks,
                VTypo.micro.copy(fontSize = CELL_SUB_SP, lineHeight = CELL_SUB_LINE_H),
                color = VColors.ink3,
                maxLines = 1,
                align = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 展开的「当日课程」视图（点表头日期进入）。
 *
 * ## 形式（用户 2026-09-22 定的）
 *
 *  · **替换周视图**，不是追加在下方；
 *  · 每门课**横向占满一整行**，显示完整信息（时间/地点/教师/周次）；
 *  · 字号比课表格子大 —— 这一屏是「看详情」的场景，不是「扫一眼」。
 *
 * 只列出本周有效（`active`）的课，与周视图口径一致。
 */
@Composable
private fun ExpandedDaySection(
    state: TimetableV2UiState,
    day: Int,
    onClose: () -> Unit,
    onOpenCourse: (String) -> Unit,
) {
    val date = state.weekDays.getOrNull(day - 1)
    val blocks = state.dayBlocks[day].orEmpty()
        .filter { it.active }
        .sortedBy { it.startPeriod }

    VCard(radius = 18.dp) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // —— 标题：周几 + 日期 + 关闭 ——
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VText(
                        "周" + WEEKDAY_LABELS[day - 1] + "的课",
                        VTypo.section.copy(fontSize = 16.sp),
                        color = VColors.ink,
                        maxLines = 1,
                    )
                    if (date != null) {
                        VText(
                            "${date.monthValue} 月 ${date.dayOfMonth} 日",
                            VTypo.caption12,
                            color = VColors.ink3,
                            maxLines = 1,
                        )
                    }
                }
                Box(
                    Modifier
                        .size(30.dp)
                        .background(VColors.surface2, RoundedCornerShape(10.dp))
                        .vPressable(scaleDown = 0.9f) { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = "收起", Modifier.size(16.dp), tint = VColors.ink2)
                }
            }

            if (blocks.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("今天没有课", VTypo.body, color = VColors.ink3, maxLines = 1)
                }
            } else {
                blocks.forEach { block ->
                    ExpandedDayRow(block = block, onOpenCourse = onOpenCourse)
                }
            }
        }
    }
}

/**
 * 当日课程的一行：**横向占满**、完整信息、大字号。
 *
 * 左侧色条标记课程色，右侧是内容。整行可点（进课程编辑）。
 */
@Composable
private fun ExpandedDayRow(block: GridBlock, onOpenCourse: (String) -> Unit) {
    val tint = CoursePalette.tintFor(block.colorHex, block.courseId)
    Row(
        Modifier
            .fillMaxWidth()
            .background(tint.soft, RoundedCornerShape(12.dp))
            .vPressable(scaleDown = 0.985f) { onOpenCourse(block.courseId) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：节次（比正文更大，因为这是「看时间」的场景）
        Column(
            Modifier.width(58.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            VText(
                "${block.startPeriod}",
                VTypo.numHero.copy(fontSize = 20.sp),
                color = tint.strong,
                maxLines = 1,
            )
            VText(
                if (block.endPeriod > block.startPeriod) "– ${block.endPeriod} 节" else "节",
                VTypo.caption,
                color = tint.strong,
                maxLines = 1,
            )
        }
        // 右侧：信息
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            VText(
                block.name,
                VTypo.bodyMed.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = VColors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(block.location, block.teacher).joinToString(" · ")
            if (meta.isNotBlank()) {
                VText(meta, VTypo.caption12, color = VColors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            VText(block.weeks, VTypo.caption, color = VColors.ink3, maxLines = 1)
        }
        Icon(Lucide.ChevronRight, contentDescription = null, Modifier.size(16.dp), tint = VColors.ink3)
    }
}

@Composable
private fun HintBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(VColors.accentSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Lucide.Zap, contentDescription = null, Modifier.size(16.dp), tint = VColors.accent)
        VText(
            "支持单节、3–4 节连排与空白天数，按实际节次高度自适应（1–14 节可配）",
            VTypo.caption.copy(lineHeight = 14.sp),
            color = VColors.accent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WeekendSection(state: TimetableV2UiState) {
    val saturday = state.dayBlocks[6].orEmpty()
    val sunday = state.dayBlocks[7].orEmpty()
    // 只数本周有效的课（非本周课程不再显示）。
    val satCount = saturday.count { it.active }
    val sunCount = sunday.count { it.active }

    VSectionHead(
        title = "周末安排",
        note = "周六 ${if (satCount == 0) "无课" else "$satCount 门"} · 周日 ${if (sunCount == 0) "无课" else "$sunCount 门"}",
    )
    WeekendDayCard("周六", saturday, state)
    WeekendDayCard("周日", sunday, state)
}

@Composable
private fun WeekendDayCard(label: String, blocks: List<GridBlock>, state: TimetableV2UiState) {
    // 非本周课程不显示（与课表主体一致）。
    val visible = blocks.filter { it.active }
    if (visible.isEmpty()) {
        VCard(radius = 14.dp) {
            Row(
                Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(width = 42.dp, height = 36.dp).background(VColors.surface2, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    VText(label, VTypo.bodyMed, color = VColors.ink3, maxLines = 1)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VText("无课", VTypo.bodyMed, color = VColors.ink3, maxLines = 1)
                    VText("全天无安排", VTypo.caption, color = VColors.ink3, maxLines = 1)
                }
            }
        }
        return
    }
    visible.forEach { block ->
        val tint = CoursePalette.tintFor(block.colorHex, block.courseId)
        val meta = listOfNotNull(
            periodSpanLabel(block.startPeriod, block.endPeriod),
            block.location,
            block.teacher,
        ).joinToString(" · ")
        VCard(radius = 14.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(width = 42.dp, height = 36.dp).background(tint.soft, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    VText(label, VTypo.bodyMed, color = tint.strong, maxLines = 1)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VText(block.name, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                    VText(meta, VTypo.caption, color = VColors.ink2, maxLines = 1)
                }
                VText("${block.endPeriod - block.startPeriod + 1} 节", VTypo.numChip, color = VColors.ink3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CourseListSection(state: TimetableV2UiState, onOpenCourse: (String) -> Unit) {
    VSectionHead(title = "课程清单", note = courseCountText(state.courses))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.courses.forEach { course ->
            val tint = CoursePalette.tintFor(course.colorHex, course.id)
            val meta = courseMeta(course)
            VCard(radius = 14.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .vPressable(scaleDown = 0.985f, onClick = { onOpenCourse(course.id) })
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).background(tint.soft, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        VText(course.name.take(1), VTypo.bodyMed.copy(fontSize = 12.sp), color = tint.strong, maxLines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        VText(course.name, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
                        VText(meta, VTypo.caption, color = VColors.ink2, maxLines = 1)
                    }
                    if (course.credit > 0.0) {
                        VText("${formatCredit(course.credit)} 学分", VTypo.caption, color = VColors.ink3, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 课程计数文本：N 门 · M 学分。 */
internal fun courseCountText(courses: List<Course>): String {
    val total = courses.sumOf { it.credit }
    return if (total > 0.0) "${courses.size} 门 · ${formatCredit(total)} 学分" else "${courses.size} 门"
}

@Composable
private fun AllCoursesLink(count: Int, onOpenAll: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(VColors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(12.dp))
            .vPressable(scaleDown = 0.98f, onClick = onOpenAll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        VText("查看全部 $count 门课程", VTypo.caption12, color = VColors.accent, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Lucide.ChevronRight, contentDescription = null, Modifier.size(14.dp), tint = VColors.accent)
    }
}

/** 课程清单行的副标题：教师 · 教室 · 周次（取该课程所有时段的周次范围）。 */
private fun courseMeta(course: Course): String {
    val parts = mutableListOf<String>()
    course.teacher?.takeIf { it.isNotBlank() }?.let { parts += it }
    course.defaultLocation?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ")
}
