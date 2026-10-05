package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VEmptyState
import com.phonlynn.oreplan.v2.components.VFAB
import com.phonlynn.oreplan.v2.components.VNavBar
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CourseListV2UiState(
    val courses: List<Course> = emptyList(),
    val sessionsByCourse: Map<String, List<CourseSession>> = emptyMap(),
    val loaded: Boolean = false,
)

@HiltViewModel
class CourseListV2ViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
) : ViewModel() {

    val uiState: StateFlow<CourseListV2UiState> = combine(
        courseRepository.observeCourses(),
        courseRepository.observeSessions(),
    ) { courses, sessions ->
        CourseListV2UiState(
            courses = courses,
            sessionsByCourse = sessions.groupBy { it.courseId },
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CourseListV2UiState(),
    )
}

@Composable
fun CourseListScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: CourseListV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(VColors.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            VNavBar(title = "全部课程", onBack = onBack)

            if (state.courses.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    VEmptyState(
                        icon = Lucide.GraduationCap,
                        title = "还没有课程",
                        description = "点右下角新建一门课，或到课表设置里导入课表。",
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        VText("课程清单", VTypo.section, color = VColors.ink, maxLines = 1)
                        VText(courseCountText(state.courses), VTypo.caption12, color = VColors.ink3, maxLines = 1)
                    }
                    state.courses.forEach { course ->
                        CourseRow(
                            course = course,
                            sessions = state.sessionsByCourse[course.id].orEmpty(),
                            onClick = { navigate(V2Routes.courseEditor(course.id)) },
                        )
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp)) {
            VFAB(onClick = { navigate(V2Routes.courseEditor()) }, icon = Lucide.Plus)
        }
    }
}

@Composable
private fun CourseRow(course: Course, sessions: List<CourseSession>, onClick: () -> Unit) {
    val tint = CoursePalette.tintFor(course.colorHex, course.id)
    val meta = courseListMeta(course, sessions)
    VCard(radius = 14.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .vPressable(scaleDown = 0.985f, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(30.dp).background(tint.soft, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
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

/** 课程清单行的副标题：教师 · 教室 · 周次（周次取该课程所有时段的范围）。 */
private fun courseListMeta(course: Course, sessions: List<CourseSession>): String {
    val parts = mutableListOf<String>()
    course.teacher?.takeIf { it.isNotBlank() }?.let { parts += it }
    course.defaultLocation?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (sessions.isNotEmpty()) {
        val start = sessions.minOf { it.startWeek }
        val endRaw = sessions.maxOf { it.endWeek }
        val end = if (endRaw == Int.MAX_VALUE) "学期末" else "$endRaw"
        parts += "$start-$end 周"
    }
    return parts.joinToString(" · ")
}
