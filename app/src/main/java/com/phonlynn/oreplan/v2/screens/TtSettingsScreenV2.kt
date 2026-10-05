package com.phonlynn.oreplan.v2.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.time.TermClock
import com.phonlynn.oreplan.data.settings.AppSettings
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.Term
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import com.phonlynn.oreplan.domain.routine.RoutineSchedule
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VIconButton
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.NumFont
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VRadius
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import com.phonlynn.oreplan.v2.components.VSettingRow
import com.phonlynn.oreplan.v2.components.VSettingSwitchRow
import com.phonlynn.oreplan.v2.components.VDialogButtons

data class TtSettingsUiState(
    val term: Term? = null,
    val courses: List<Course> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val conflicts: List<String> = emptyList(),
    val loaded: Boolean = false,
)

data class TtSettingsDraft(
    val startDate: LocalDate = TermClock.weekStartOf(LocalDate.now()),
    val totalWeeks: Int = 20,
    val currentWeek: Int = 1,
    val showWeekend: Boolean = true,
    // 「显示非本周课程」已移除（用户 2026-09-22：非本周课程一律不显示）。
    // 字段保留在草稿里只为不破坏既有构造调用，取值不再生效。
    @Suppress("unused")
    val showNonCurrentWeek: Boolean = false,
    val showPeriodTimes: Boolean = true,
)

@HiltViewModel
class TtSettingsV2ViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termRepository: TermRepository,
    private val courseRepository: CourseRepository,
    private val appSettings: AppSettingsStore,
) : ViewModel() {

    val uiState: StateFlow<TtSettingsUiState> = combine(
        termRepository.observeActiveTerm(),
        courseRepository.observeCourses(),
        courseRepository.observeSessions(),
        appSettings.settings,
    ) { term, courses, sessions, settings ->
        TtSettingsUiState(
            term = term,
            courses = courses,
            settings = settings,
            conflicts = computeConflicts(courses, sessions),
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TtSettingsUiState(),
    )

    private val _draft = MutableStateFlow(TtSettingsDraft())
    val draft: StateFlow<TtSettingsDraft> = _draft.asStateFlow()

    private val _importFeedback = MutableStateFlow<String?>(null)
    val importFeedback: StateFlow<String?> = _importFeedback.asStateFlow()

    /** 用户动过控件后不再跟随存储，避免编辑中途被刷新覆盖。 */
    private var userEdited = false

    init {
        viewModelScope.launch {
            combine(termRepository.observeActiveTerm(), appSettings.settings) { term, settings -> term to settings }
                .collect { (term, settings) ->
                    if (!userEdited) {
                        val today = LocalDate.now()
                        val currentWeek = term?.let { TermClock.weekNumberOn(it.startDate, it.totalWeeks, today) } ?: 1
                        _draft.value = TtSettingsDraft(
                            startDate = term?.startDate ?: TermClock.weekStartOf(today),
                            totalWeeks = term?.totalWeeks ?: 20,
                            currentWeek = currentWeek,
                            showWeekend = settings.showWeekend,
                            showNonCurrentWeek = settings.showNonCurrentWeekCourses,
                            showPeriodTimes = settings.showPeriodTimes,
                        )
                    }
                }
        }
    }

    private fun edit(transform: (TtSettingsDraft) -> TtSettingsDraft) {
        userEdited = true
        _draft.value = transform(_draft.value)
    }

    fun setStartDate(date: LocalDate) = edit { d ->
        val aligned = TermClock.weekStartOf(date)
        val cw = TermClock.weekNumberOn(aligned, d.totalWeeks, LocalDate.now()) ?: 1
        d.copy(startDate = aligned, currentWeek = cw)
    }

    fun setTotalWeeks(weeks: Int) = edit { d ->
        val w = weeks.coerceIn(1, 30)
        val cw = TermClock.weekNumberOn(d.startDate, w, LocalDate.now()) ?: 1
        d.copy(totalWeeks = w, currentWeek = cw)
    }

    fun setCurrentWeek(week: Int) = edit { d ->
        val today = LocalDate.now()
        val newStart = TermClock.weekStartOf(today).minusWeeks((week - 1).toLong())
        d.copy(startDate = newStart, currentWeek = week.coerceIn(1, d.totalWeeks))
    }

    fun setShowWeekend(v: Boolean) = edit { it.copy(showWeekend = v) }
    fun setShowPeriodTimes(v: Boolean) = edit { it.copy(showPeriodTimes = v) }

    private fun computeConflicts(courses: List<Course>, sessions: List<CourseSession>): List<String> {
        val byId = courses.associateBy { it.id }
        val result = mutableListOf<String>()
        for (i in sessions.indices) {
            for (j in i + 1 until sessions.size) {
                val a = sessions[i]
                val b = sessions[j]
                if (a.dayOfWeek != b.dayOfWeek || a.courseId == b.courseId) continue
                val timeOverlap = a.startMinuteOfDay < b.endMinuteOfDay && b.startMinuteOfDay < a.endMinuteOfDay
                val weekOverlap = a.startWeek <= b.endWeek && b.startWeek <= a.endWeek
                if (timeOverlap && weekOverlap) {
                    val nameA = byId[a.courseId]?.name ?: "未命名"
                    val nameB = byId[b.courseId]?.name ?: "未命名"
                    result += "周${WEEKDAY_LABELS[a.dayOfWeek - 1]}：$nameA 与 $nameB 节次重叠"
                }
            }
        }
        return result.distinct()
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            val d = _draft.value
            val existing = termRepository.getActiveTerm()
            termRepository.upsert(
                Term(
                    id = existing?.id ?: Ids.newId(),
                    name = existing?.name ?: "本学期",
                    startDate = d.startDate,
                    totalWeeks = d.totalWeeks,
                    isActive = true,
                ),
            )
            appSettings.update {
                it.copy(
                    showWeekend = d.showWeekend,
                    showNonCurrentWeekCourses = d.showNonCurrentWeek,
                    showPeriodTimes = d.showPeriodTimes,
                )
            }
            userEdited = false
            onSaved()
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _importFeedback.value = null
            val result = withContext(Dispatchers.IO) {
                try {
                    val name = displayName(uri)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                    // 当前作息：文件里若用「节次」写法，需要它换算成具体时间。
                    val routine = appSettings.settings.value.routine
                    when (ext) {
                        "json" -> Result.success(importCourses(parseCourseJson(text, routine)))
                        "csv" -> Result.success(importCourses(parseCourseCsv(text, routine)))
                        "ics", "xls", "xlsx" -> Result.failure(IllegalArgumentException("暂不支持该格式（$ext）"))
                        else -> Result.failure(IllegalArgumentException("暂不支持该格式（$ext）"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result.fold(
                // 文案写明「替换」：这是整体替换语义，去掉原有的课。
                onSuccess = { count -> _importFeedback.value = "已导入 $count 门课程（原课表已替换）" },
                onFailure = { e -> _importFeedback.value = e.message ?: "导入失败" },
            )
        }
    }

    fun consumeImportFeedback() {
        _importFeedback.value = null
    }

    /**
     * 导入课表。
     *
     * ## 整体替换，不是合并
     *
     * 导入的是一整套课表，**先清空原有课程与安排再写入**（用户 2026-09-22）。
     * 否则新旧会叠加：同名课程各留一份、时段翻倍，课表格子里还会因为
     * 同格多课而显示错乱。
     *
     * ## 为什么「先解析、再清空」
     *
     * [list] 是**已经解析成功**的结果（解析失败会在调用方抛异常、根本走不到这里）。
     * 所以不会出现「文件格式错了，反而把旧课表清光」这种情况。
     */
    private suspend fun importCourses(list: List<ImportedCourse>): Int {
        // 解析已成功，可以安全地清空旧的。
        courseRepository.clearAll()
        for (ic in list) {
            val courseId = Ids.newId()
            courseRepository.upsertCourse(
                Course(
                    id = courseId,
                    name = ic.name,
                    teacher = ic.teacher,
                    defaultLocation = ic.location,
                    colorHex = ic.colorHex,
                    note = null,
                ),
            )
            for (s in ic.sessions) {
                courseRepository.upsertSession(
                    CourseSession(
                        id = Ids.newId(),
                        courseId = courseId,
                        dayOfWeek = s.dayOfWeek,
                        startMinuteOfDay = s.startMinute,
                        endMinuteOfDay = s.endMinute,
                        startWeek = s.startWeek,
                        endWeek = if (s.endWeek <= 0) Int.MAX_VALUE else s.endWeek,
                        parity = s.parity,
                        location = s.location,
                        note = null,
                    ),
                )
            }
        }
        return list.size
    }

    private fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: ""
        }
        return uri.lastPathSegment ?: ""
    }
}

@Composable
fun TtSettingsScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: TtSettingsV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val importFeedback by viewModel.importFeedback.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDatePicker by remember { mutableStateOf(false) }
    var showWeeksDialog by remember { mutableStateOf(false) }
    var showCurrentWeekDialog by remember { mutableStateOf(false) }
    var showFormatExample by remember { mutableStateOf(false) }

    LaunchedEffect(importFeedback) {
        importFeedback?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeImportFeedback()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFrom) }

    val periodTimes = remember(state.settings.routine) { RoutineSchedule.compute(state.settings.routine) }

    Column(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIconButton(Lucide.ChevronLeft, onBack, tint = VColors.ink)
            Spacer(Modifier.width(12.dp))
            VText("课表设置", VTypo.pageTitle, color = VColors.ink, maxLines = 1)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 学期设置
            VSectionHead(title = "学期设置")
            VCard {
                SettingRow("学期开始日期", draft.startDate.format(DATE_FORMAT_FULL)) { showDatePicker = true }
                VDivider(14.dp)
                SettingRow("总周数", "${draft.totalWeeks} 周") { showWeeksDialog = true }
                VDivider(14.dp)
                SettingRow("当前周", "第 ${draft.currentWeek} 周") { showCurrentWeekDialog = true }
                VDivider(14.dp)
                SettingRow("单节课时长", "${state.settings.routine.periodMinutes} 分钟") { navigate(V2Routes.ROUTINE) }
                VDivider(14.dp)
                SettingRow("课间休息", "${state.settings.routine.smallBreakMinutes} 分钟") { navigate(V2Routes.ROUTINE) }
                VDivider(14.dp)
                SettingRow("每日节数", "${state.settings.routine.totalPeriods} 节 · 可配置") { navigate(V2Routes.ROUTINE) }
            }

            // 作息时间
            VSectionHead(title = "作息时间", trailing = {
                Row(Modifier.vPressable(scaleDown = 0.94f, onClick = { navigate(V2Routes.ROUTINE) }), verticalAlignment = Alignment.CenterVertically) {
                    VText("编辑", VTypo.caption12, color = VColors.accent, maxLines = 1)
                    Spacer(Modifier.width(3.dp))
                    Icon(Lucide.ChevronRight, contentDescription = null, Modifier.size(14.dp), tint = VColors.accent)
                }
            })
            PeriodPreviewCard(periodTimes, state.settings.routine)

            // 导入课表
            VSectionHead(title = "导入课表")
            ImportCard(onChooseFile = { importLauncher.launch(arrayOf("*/*")) }, onShowExample = { showFormatExample = true })

            // 课程管理
            VSectionHead(title = "课程管理")
            VCard {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 14.dp)
                        .vPressable(scaleDown = 0.985f, onClick = { navigate(V2Routes.COURSES) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    VText("全部课程", VTypo.body, color = VColors.ink, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VText(courseCountText(state.courses), VTypo.caption12, color = VColors.ink2, maxLines = 1)
                        Icon(Lucide.ChevronRight, contentDescription = null, Modifier.size(16.dp), tint = VColors.ink3)
                    }
                }
                VDivider(14.dp)
                Row(
                    Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 14.dp)
                        .vPressable(scaleDown = 0.985f, onClick = { navigate(V2Routes.courseEditor()) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(26.dp).background(VColors.accentSoft, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                            Icon(Lucide.Plus, contentDescription = null, Modifier.size(14.dp), tint = VColors.accent)
                        }
                        VText("手动添加课程", VTypo.body, color = VColors.accent, maxLines = 1)
                    }
                    Icon(Lucide.ChevronRight, contentDescription = null, Modifier.size(16.dp), tint = VColors.ink3)
                }
                VDivider(14.dp)
                ConflictRow(state.conflicts)
            }

            // 显示设置
            VSectionHead(title = "显示设置")
            VCard {
                SwitchRow("显示周末", draft.showWeekend) { viewModel.setShowWeekend(it) }
                VDivider(14.dp)
                SwitchRow("在课表中显示节次时间", draft.showPeriodTimes) { viewModel.setShowPeriodTimes(it) }
            }

            VText(
                "课表数据仅保存在本机，可随时导出为 JSON 备份。",
                VTypo.caption.copy(lineHeight = 13.sp),
                color = VColors.ink3,
            )
        }

        // 底部保存
        Box(
            Modifier
                .fillMaxWidth()
                .background(VColors.scrimTop)
                .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 16.dp),
        ) {
            SaveButton {
                viewModel.save {
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        }
    }

    if (showDatePicker) {
        TermDatePickerDialog(
            initial = draft.startDate,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                viewModel.setStartDate(date)
                showDatePicker = false
            },
        )
    }

    if (showWeeksDialog) {
        ValuePickerDialog("总周数", draft.totalWeeks, 1, 30, 1, "周", onConfirm = {
            viewModel.setTotalWeeks(it)
            showWeeksDialog = false
        }, onDismiss = { showWeeksDialog = false })
    }

    if (showCurrentWeekDialog) {
        ValuePickerDialog("当前周", draft.currentWeek, 1, draft.totalWeeks, 1, "周", onConfirm = {
            viewModel.setCurrentWeek(it)
            showCurrentWeekDialog = false
        }, onDismiss = { showCurrentWeekDialog = false })
    }

    if (showFormatExample) {
        FormatExampleDialog(onDismiss = { showFormatExample = false })
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    // 统一走共用件（50dp 普通行）；与设计稿一致。
    VSettingRow(label = label, value = value, onClick = onClick)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // 统一走共用件（50dp 开关行）。
    VSettingSwitchRow(label = label, checked = checked, onCheckedChange = onChange)
}

@Composable
private fun PeriodPreviewCard(periodTimes: List<com.phonlynn.oreplan.domain.routine.PeriodTime>, routine: com.phonlynn.oreplan.domain.routine.RoutineConfig) {
    VCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            periodTimes.chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { period ->
                        Column(
                            Modifier.weight(1f).height(34.dp).background(VColors.bg, RoundedCornerShape(10.dp)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            VText(period.period.toString(), VTypo.numChip, color = VColors.ink2, maxLines = 1)
                            VText(RoutineSchedule.formatMinute(period.startMinute), VTypo.micro.copy(fontSize = 9.sp, fontFamily = NumFont), color = VColors.ink3, maxLines = 1)
                        }
                    }
                    // 补齐末行到 7 格，保持对齐
                    repeat(7 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            VText(
                RoutineSchedule.rangeText(routine),
                VTypo.caption.copy(lineHeight = 13.sp),
                color = VColors.ink2,
            )
            VText(
                "每日节数与每节起止时间均可调整，单双周、临时调课会按实际节次高度重排。",
                VTypo.caption.copy(lineHeight = 13.sp),
                color = VColors.ink3,
            )
        }
    }
}

@Composable
private fun ImportCard(onChooseFile: () -> Unit, onShowExample: () -> Unit) {
    VCard {
        // 内层浅绿容器必须跟外层 VCard 同一个圆角（VRadius.card）：
        // 不给 shape 时它是直角，会把卡的圆角“顶破”，四角看起来是方的（用户 2026-09-26）。
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.accentSoft, RoundedCornerShape(VRadius.card))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.size(40.dp).background(VColors.accent, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Icon(Lucide.FileDown, contentDescription = null, Modifier.size(20.dp), tint = Color.White)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VText("导入结构化课表", VTypo.cardTitle.copy(fontSize = 14.sp), color = VColors.ink, maxLines = 1)
                    VText("支持 JSON / CSV / XLSX / ICS", VTypo.caption, color = VColors.ink2, maxLines = 1)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(46.dp).background(VColors.accent, RoundedCornerShape(13.dp))
                    .vPressable(scaleDown = 0.97f, onClick = onChooseFile),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Lucide.Download, contentDescription = null, Modifier.size(16.dp), tint = Color.White)
                    VText("选择文件导入", VTypo.bodyMed, color = Color.White, maxLines = 1)
                }
            }
            Row(
                Modifier.vPressable(scaleDown = 0.94f, onClick = onShowExample),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(Lucide.Link, contentDescription = null, Modifier.size(12.dp), tint = VColors.accent)
                VText("查看导入格式示例", VTypo.micro, color = VColors.accent, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ConflictRow(conflicts: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (conflicts.isEmpty()) Lucide.Check else Lucide.CircleAlert,
                contentDescription = null,
                Modifier.size(16.dp),
                tint = if (conflicts.isEmpty()) VColors.accent else VColors.rose,
            )
            VText(
                if (conflicts.isEmpty()) "节次冲突检测：未发现冲突" else "节次冲突检测：发现 ${conflicts.size} 处冲突",
                VTypo.caption12,
                color = VColors.ink2,
                maxLines = 1,
            )
        }
        if (conflicts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            conflicts.forEach { c ->
                VText(c, VTypo.caption, color = VColors.rose, maxLines = 2)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermDatePickerDialog(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { androidx.compose.material3.Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { androidx.compose.material3.Text("取消") }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun ValuePickerDialog(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var v by remember { mutableStateOf(value) }
    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText(title, VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                PickerStepButton(Lucide.Minus, v > min) { v -= step }
                Spacer(Modifier.width(20.dp))
                VText("$v $unit", VTypo.hero, color = VColors.ink, maxLines = 1)
                Spacer(Modifier.width(20.dp))
                PickerStepButton(Lucide.Plus, v < max) { v += step }
            }
            Spacer(Modifier.height(24.dp))
            VDialogButtons(onCancel = onDismiss, onConfirm = { onConfirm(v) })
        }
    }
}

@Composable
private fun PickerStepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).background(VColors.bg, RoundedCornerShape(9.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(9.dp))
            .vPressable(scaleDown = 0.9f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = if (enabled) VColors.ink2 else VColors.ink3)
    }
}

@Composable
private fun FormatExampleDialog(onDismiss: () -> Unit) {
    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText("导入格式示例", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(16.dp))
            VText(
                "JSON：",
                VTypo.bodyMed,
                color = VColors.ink,
            )
            Spacer(Modifier.height(4.dp))
            VText(
                "{\n  \"courses\": [\n    {\n      \"name\": \"高等数学\",\n      \"teacher\": \"陈立言\",\n      \"location\": \"理科楼 305\",\n      \"sessions\": [\n        {\"dayOfWeek\": 1, \"startPeriod\": 1, \"endPeriod\": 2, \"startWeek\": 1, \"endWeek\": 16}\n      ]\n    }\n  ]\n}",
                VTypo.caption.copy(fontFamily = NumFont, lineHeight = 16.sp),
                color = VColors.ink2,
            )
            Spacer(Modifier.height(10.dp))
            VText(
                "时间两种写法（二选一）：\n· 节次：startPeriod / endPeriod（第几节，推荐）——跟随作息设置；\n· 分钟：startMinute / endMinute（当天第几分钟，480 = 8:00）。\n写了节次就以节次为准；节次超出作息总节数时该时段会被跳过。",
                VTypo.caption.copy(lineHeight = 15.sp),
                color = VColors.ink2,
            )
            Spacer(Modifier.height(12.dp))
            VText("CSV（首行表头，每行一个时段）：", VTypo.bodyMed, color = VColors.ink)
            Spacer(Modifier.height(4.dp))
            VText(
                "name,teacher,location,dayOfWeek,startPeriod,endPeriod,startWeek,endWeek,parity\n高等数学,陈立言,理科楼305,1,1,2,1,16,ALL",
                VTypo.caption.copy(fontFamily = NumFont, lineHeight = 16.sp),
                color = VColors.ink2,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.fillMaxWidth().height(48.dp).background(VColors.accent, RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.96f, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { VText("知道了", VTypo.buttonBold, color = Color.White) }
        }
    }
}

@Composable
private fun SaveButton(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(50.dp).background(VColors.accent, RoundedCornerShape(15.dp))
            .vPressable(scaleDown = 0.97f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText("保存设置", VTypo.button, color = Color.White, maxLines = 1)
    }
}
