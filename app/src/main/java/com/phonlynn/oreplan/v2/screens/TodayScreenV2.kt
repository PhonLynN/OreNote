package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VCheckbox
import com.phonlynn.oreplan.v2.components.VCheckStyle
import com.phonlynn.oreplan.v2.components.VChip
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VScreenTitle
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VTab
import com.phonlynn.oreplan.v2.components.VTabBottomPadding
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VTabScaffold
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import java.time.LocalDate
import com.phonlynn.oreplan.v2.components.VDialogButtons

/**
 * 今日页（V2）—— 完全按设计稿 RJwbC 重建。
 *
 * 布局常量严格照 pen.dev「今日 V2」帧：
 * 内容区 pad [10,20]、列间距 20；各处行高/圆角/字号见下方常量与注释。
 * 功能逻辑（状态、导航、复盘弹窗）与重建前完全一致。
 */
@Composable
fun TodayScreenV2(
    navigate: (String) -> Unit,
    onSelectTab: (VTab) -> Unit,
    onAi: () -> Unit,
    viewModel: TodayV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var reviewEditing by remember { mutableStateOf<String?>(null) }

    // 日程详情浮层（设计稿 u0QP0i）——与日程页共用同一个浮层组件与装配逻辑。
    var detail by remember { mutableStateOf<EventDetailUi?>(null) }
    var pendingDelete by remember { mutableStateOf<EventDetailUi?>(null) }
    val scope = rememberCoroutineScope()
    val storage = viewModel.attachmentStorage

    fun openDetail(
        itemId: String?,
        courseId: String?,
        title: String,
        timeText: String?,
        isCourse: Boolean,
        durationText: String? = null,
        locationText: String? = null,
    ) {
        if (itemId == null && courseId == null) return
        scope.launch {
            detail = viewModel.loadDetail(
                itemId = itemId,
                courseId = courseId,
                title = title,
                timeText = timeText,
                durationText = durationText,
                dateText = state.dateTitle,
                locationText = locationText,
                isCourse = isCourse,
            )
        }
    }

    // 页面内容整体交给 DetailLayer：浮层显示期间（含退场动画）由它给内容加背景模糊，
    // 并保证退场动画播完之前数据不被清空。
    DetailLayer(
        target = detail,
        storage = storage,
        onRequestClose = { detail = null },
        onEdit = { d ->
            if (d.isCourse) {
                d.courseId?.let { navigate(V2Routes.courseEditor(it)) }
            } else {
                d.itemId?.let { navigate(V2Routes.editor(itemId = it)) }
            }
        },
        onDelete = { pendingDelete = it },
        onArchive = { it.itemId?.let(viewModel::archiveItem) },
    ) {
        VTabScaffold(active = VTab.Today, onSelect = onSelectTab) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = VTabBottomPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "header") {
                    VScreenTitle(
                        title = "今日",
                        overline = state.dateTitle,
                        overlineLetterSpacing = 0.2.sp,
                        onSidebar = { navigate(V2Routes.SETTINGS) },
                        onAi = onAi,
                    )
                }

                item(key = "hero") {
                    val hero = state.hero
                    if (hero != null) {
                        NextUpCard(
                            hero = hero,
                            // 点卡片看**详情**（设计稿 u0QP0i），不再直接跳编辑页。
                            onClick = {
                                openDetail(
                                    itemId = hero.itemId,
                                    courseId = hero.courseId,
                                    title = hero.title,
                                    timeText = hero.timeText,
                                    isCourse = hero.courseId != null,
                                    // 设计稿详情里有「地点」行 —— 首屏卡片的地点必须带过去，
                                    // 否则课程详情会少一行（用户会看到与设计稿不一致）。
                                    locationText = hero.locationText,
                                )
                            },
                        )
                    } else {
                        EmptyHeroCard(
                            onAddEvent = { navigate(V2Routes.editor(kind = ItemKind.EVENT.name)) },
                            onAddTask = { navigate(V2Routes.editor(kind = ItemKind.TASK.name)) },
                        )
                    }
                }

                item(key = "stats") {
                    GlanceStats(
                        scheduled = state.scheduledCount,
                        tasks = state.openTaskCount,
                    )
                }

                item(key = "timeline-head") {
                    VSectionHead(
                        title = "今日时间线",
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.vPressable(scaleDown = 0.94f) {
                                    CalendarEntryBus.openDay(LocalDate.now())
                                    navigate(V2Routes.CALENDAR)
                                },
                            ) {
                                VText("查看全部", VTypo.caption12, color = VColors.accent)
                                Icon(Lucide.ChevronRight, null, Modifier.size(13.dp), tint = VColors.accent)
                            }
                        },
                    )
                }

                if (state.timeline.isEmpty()) {
                    item(key = "timeline-empty") {
                        VCard {
                            Row(
                                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp)
                                    .vPressable(scaleDown = 0.985f) { navigate(V2Routes.editor(kind = ItemKind.EVENT.name)) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Lucide.Plus, null, Modifier.size(16.dp), tint = VColors.accent)
                                VText("今天还没有安排，点这里添加日程", VTypo.body, color = VColors.ink2)
                            }
                        }
                    }
                } else {
                    item(key = "timeline") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.timeline.forEach { row ->
                                TimelineRow(
                                    row = row,
                                    // 点时间轴上的安排 → 详情（同上）。
                                    onClick = {
                                        openDetail(
                                            itemId = row.itemId,
                                            courseId = row.courseId,
                                            title = row.title,
                                            timeText = row.timeText,
                                            isCourse = row.courseId != null,
                                            durationText = row.durationText,
                                            locationText = row.metaText,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.tasks.isNotEmpty()) {
                    item(key = "tasks-head") {
                        VSectionHead(
                            title = "今日待办",
                            note = "${state.tasksDone} / ${state.tasksTotal} 完成",
                            noteSize = 11.sp,
                        )
                    }
                    item(key = "tasks") {
                        VCard {
                            state.tasks.forEachIndexed { index, row ->
                                TaskRow(
                                    row = row,
                                    onToggle = { viewModel.toggleDone(row.itemId) },
                                    onOpen = {
                                           // 待办点开先看详情（与日程页一致，用户 2026-09-23），
                                           // 详情右上角三点菜单里再进编辑。
                                           openDetail(
                                               itemId = row.itemId,
                                               courseId = null,
                                               title = row.title,
                                               timeText = null,
                                               isCourse = false,
                                               locationText = null,
                                           )
                                       },
                                )
                                if (index != state.tasks.lastIndex) VDivider()
                            }
                            if (state.tasks.isNotEmpty()) VDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .padding(horizontal = 14.dp)
                                    .vPressable(scaleDown = 0.985f) {
                                        CalendarEntryBus.openTodo()
                                        navigate(V2Routes.CALENDAR)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                VText("全部待办", VTypo.body, color = VColors.ink2)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    VText("共 ${state.tasksTotal} 项", VTypo.caption, color = VColors.ink3)
                                    VChevron(size = 14.dp)
                                }
                            }
                        }
                    }
                }

                if (state.deadlines.isNotEmpty()) {
                    item(key = "deadlines-head") {
                        VSectionHead(title = "临近截止", note = "${state.deadlines.size} 项", noteSize = 11.sp)
                    }
                    item(key = "deadlines") {
                        VCard {
                            state.deadlines.forEachIndexed { index, row ->
                                DeadlineRow(
                                    row = row,
                                    onClick = { navigate(V2Routes.editor(itemId = row.itemId)) },
                                )
                                if (index != state.deadlines.lastIndex) VDivider()
                            }
                        }
                    }
                }

                item(key = "review-head") {
                    VSectionHead(
                        title = "今日复盘",
                        note = "${state.date.monthValue}月${state.date.dayOfMonth}日",
                        noteSize = 11.sp,
                    )
                }
                item(key = "review") {
                    VCard {
                        ReviewStats(
                            courses = state.reviewCourses,
                            tasks = state.reviewTasks,
                            done = state.reviewDone,
                        )
                        VDivider()
                        val text = state.reviewText
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .vPressable(scaleDown = 0.99f) { reviewEditing = text.orEmpty() },
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            VText("今天复盘", VTypo.caption, color = VColors.ink3)
                            VText(
                                text = text?.takeIf { it.isNotBlank() } ?: "点击写下今天的复盘…",
                                style = VTypo.body.copy(lineHeight = 13.sp * 1.45f),
                                color = if (text.isNullOrBlank()) VColors.ink3 else VColors.ink2,
                            )
                        }
                    }
                }

                item(key = "tomorrow-head") {
                    VSectionHead(
                        title = "明日预告",
                        note = if (state.tomorrow.isEmpty()) "暂无" else "${state.tomorrow.size} 项",
                        noteSize = 11.sp,
                    )
                }
                item(key = "tomorrow") {
                    VCard {
                        if (state.tomorrow.isEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                VText("明天暂无安排", VTypo.body, color = VColors.ink3)
                            }
                        } else {
                            state.tomorrow.forEachIndexed { index, row ->
                                TomorrowRow(
                                    row = row,
                                    onClick = {
                                        row.itemId?.let { navigate(V2Routes.editor(itemId = it)) }
                                            ?: row.courseId?.let { navigate(V2Routes.courseEditor(it)) }
                                    },
                                )
                                if (index != state.tomorrow.lastIndex) VDivider()
                            }
                        }
                    }
                }

                item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
            }
        }
    }


    val editing = reviewEditing
    if (editing != null) {
        ReviewEditDialog(
            initial = editing,
            onSave = { viewModel.saveReview(it); reviewEditing = null },
            onDismiss = { reviewEditing = null },
        )
    }

    pendingDelete?.let { d ->
        VConfirmDeleteDialog(
            objectName = d.title,
            onConfirm = {
                d.itemId?.let { viewModel.deleteItem(it) }
                pendingDelete = null
                detail = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ---------------------------------------------------------------- Hero（设计稿 Next Up Card：r22 / pad18 / gap12）

@Composable
private fun NextUpCard(hero: TodayHero, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(VColors.accent)
            .vPressable(scaleDown = 0.985f, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .background(VColors.white26, RoundedCornerShape(11.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                VText(hero.pillText, VTypo.caption, color = Color.White, maxLines = 1)
            }
            VText(hero.countdownText, VTypo.caption, color = VColors.white90, maxLines = 1)
        }
        VText(hero.title, VTypo.hero.copy(fontSize = 21.sp), color = Color.White, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            HeroMeta(icon = Lucide.Clock3, text = hero.timeText)
            hero.locationText?.let { HeroMeta(icon = Lucide.MapPin, text = it) }
            hero.teacherText?.let { HeroMeta(icon = Lucide.User, text = it) }
        }
    }
}

/** Hero 元信息：图标 14 / gap 5 / 文字 12，白 90%。 */
@Composable
private fun HeroMeta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = VColors.white90)
        VText(text, VTypo.caption12, color = VColors.white90, maxLines = 1)
    }
}

@Composable
private fun EmptyHeroCard(onAddEvent: () -> Unit, onAddTask: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(VColors.accent)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .background(VColors.white26, RoundedCornerShape(11.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            VText("今日安排", VTypo.caption, color = Color.White)
        }
        VText("今天还没有安排", VTypo.hero.copy(fontFamily = null), color = Color.White)
        VText("添加一条日程或待办，把今天安排得明明白白。", VTypo.caption12, color = VColors.white90)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .height(40.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .vPressable(scaleDown = 0.95f, onClick = onAddEvent)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.Calendar, null, Modifier.size(15.dp), tint = VColors.accent)
                VText("添加日程", VTypo.bodyMed, color = VColors.accent)
            }
            Row(
                Modifier
                    .height(40.dp)
                    .background(VColors.white26, RoundedCornerShape(12.dp))
                    .vPressable(scaleDown = 0.95f, onClick = onAddTask)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.ListTodo, null, Modifier.size(15.dp), tint = Color.White)
                VText("新建待办", VTypo.bodyMed, color = Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------- Glance Stats（设计稿：h68 / pad[14,0] / 值 18-600 / 标签 11）

@Composable
private fun GlanceStats(scheduled: Int, tasks: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(VColors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell(value = scheduled.toString(), label = "今日事项", modifier = Modifier.weight(1f))
        Box(Modifier.size(width = 1.dp, height = 30.dp).background(VColors.line))
        StatCell(value = tasks.toString(), label = "待办任务", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        VText(value, VTypo.numValue.copy(fontWeight = FontWeight.SemiBold), color = VColors.ink)
        VText(label, VTypo.caption, color = VColors.ink3)
    }
}

// ---------------------------------------------------------------- Timeline
//
// 设计稿「时间线」一行的精确几何（见 pen.dev 帧 M6K20U）：
//   整行固定高 78，列间距 12：
//   ├ Time Column  宽 44，顶部内缩 16，右对齐，列内 gap 2
//   ├ Marker       宽 10，顶部内缩 18，居中；点 11（当前）/ 8+2 描边（其余），线 2×64
//   └ Event Card   宽 fill，高 fill（=78），r15，pad[12,14]，gap 5，内容垂直居中
// 关键点：点比标记列（10）还宽（11），必须用 requiredSize 强制成**正圆**，
// 不能被父级约束压成圆槽；线宽 2 落在标记列的中轴上。

private val TimelineRowHeight = 78.dp
private val TimelineTimeColumnWidth = 44.dp
private val TimelineMarkerColumnWidth = 10.dp

@Composable
private fun TimelineRow(row: TodayTimelineRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(TimelineRowHeight)
            .alpha(if (row.isPast) 0.45f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 时间列：右对齐，顶部内缩 16
        Column(
            Modifier
                .width(TimelineTimeColumnWidth)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            VText(
                row.timeText,
                VTypo.numTime,
                color = if (row.isCurrent) VColors.accent else VColors.ink,
                maxLines = 1,
            )
            row.durationText?.let { VText(it, VTypo.micro, color = VColors.ink3, maxLines = 1) }
        }

        // 标记列：宽 10，点/线在中轴居中；点用 requiredSize 保证正圆
        Column(
            Modifier
                .width(TimelineMarkerColumnWidth)
                .fillMaxHeight()
                .padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TimelineDot(isCurrent = row.isCurrent)
            if (!row.isLast) {
                Box(Modifier.width(2.dp).height(64.dp).background(VColors.line))
            }
        }

        // 事件卡：填满整行高度，内容垂直居中
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    if (row.isCurrent) VColors.accentSoft else VColors.surface,
                    RoundedCornerShape(15.dp),
                )
                .border(
                    1.dp,
                    if (row.isCurrent) VColors.accent else VColors.line,
                    RoundedCornerShape(15.dp),
                )
                .vPressable(scaleDown = 0.985f, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VText(
                    row.title,
                    VTypo.bodyMed.copy(fontSize = 14.sp),
                    color = VColors.ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    VChip("即将开始", VColors.accent, Color.White)
                }
            }
            row.metaText?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Lucide.MapPin, null, Modifier.size(13.dp), tint = VColors.ink3)
                    VText(it, VTypo.caption, color = VColors.ink2, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 时间线节点圆点。
 * - 当前：11 实心 accent 圆；
 * - 其余：8 圆 + 2 内描边 ink3，圆心填充 surface。
 * 用 requiredSize 强制尺寸，父级 10 宽不会把它压成圆槽。
 */
@Composable
private fun TimelineDot(isCurrent: Boolean) {
    if (isCurrent) {
        Box(Modifier.requiredSize(11.dp).clip(CircleShape).background(VColors.accent))
    } else {
        Box(
            Modifier
                .requiredSize(8.dp)
                .clip(CircleShape)
                .background(VColors.surface)
                .border(2.dp, VColors.ink3, CircleShape),
        )
    }
}

// ---------------------------------------------------------------- Task row（设计稿：h58 / pad[10,14] / gap12 / 状态块 24 r8 / 文字 13 / meta 11）

@Composable
private fun TaskRow(row: TodayTaskRow, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VCheckbox(checked = row.done, onToggle = onToggle, style = VCheckStyle.Soft)
        Row(
            Modifier.weight(1f).fillMaxHeight().vPressable(scaleDown = 0.99f, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                VText(row.title, VTypo.body, color = if (row.done) VColors.ink3 else VColors.ink, maxLines = 1)
                row.metaText?.let { VText(it, VTypo.caption, color = VColors.ink3, maxLines = 1) }
            }
        }
        row.priorityLabel?.let { label ->
            when (row.priorityKind) {
                TodayChipKind.Rose -> VChip(label, VColors.roseSoft, VColors.rose)
                TodayChipKind.Amber -> VChip(label, VColors.amberSoft, VColors.amber)
                TodayChipKind.Accent -> VChip(label, VColors.accentSoft, VColors.accent)
                TodayChipKind.Grey -> VChip(label, VColors.surface2, VColors.ink3)
            }
        }
    }
}

// ---------------------------------------------------------------- Deadline row（设计稿：h62 / pad[10,14] / gap12 / 徽章 30 r10 / 标题 13-500 / chevron 14）

@Composable
private fun DeadlineRow(row: TodayDeadlineRow, onClick: () -> Unit) {
    val (badgeBg, badgeFg) = when (row.chipKind) {
        TodayChipKind.Rose -> VColors.roseSoft to VColors.rose
        TodayChipKind.Amber -> VColors.amberSoft to VColors.amber
        TodayChipKind.Accent -> VColors.accentSoft to VColors.accent
        TodayChipKind.Grey -> VColors.surface2 to VColors.ink3
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .vPressable(scaleDown = 0.99f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(30.dp).background(badgeBg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Timer, null, Modifier.size(15.dp), tint = badgeFg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(row.title, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
            VText(row.metaText, VTypo.caption, color = VColors.ink2, maxLines = 1)
        }
        when (row.chipKind) {
            TodayChipKind.Rose -> VChip(row.chipText, VColors.roseSoft, VColors.rose)
            TodayChipKind.Amber -> VChip(row.chipText, VColors.amberSoft, VColors.amber)
            TodayChipKind.Accent -> VChip(row.chipText, VColors.accentSoft, VColors.accent)
            TodayChipKind.Grey -> VChip(row.chipText, VColors.surface2, VColors.ink3)
        }
        VChevron(size = 14.dp)
    }
}

// ---------------------------------------------------------------- Review（设计稿：统计行 h68，值 16-700，标签 10；备注 pad[12,14] gap4）

@Composable
private fun ReviewStats(courses: Int, tasks: Int, done: Int) {
    Row(
        Modifier.fillMaxWidth().height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReviewStatCell(courses.toString(), "课程", Modifier.weight(1f))
        Box(Modifier.size(width = 1.dp, height = 34.dp).background(VColors.line))
        ReviewStatCell(tasks.toString(), "待办", Modifier.weight(1f))
        Box(Modifier.size(width = 1.dp, height = 34.dp).background(VColors.line))
        ReviewStatCell(done.toString(), "完成", Modifier.weight(1f))
    }
}

@Composable
private fun ReviewStatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        VText(value, VTypo.numStat, color = VColors.ink)
        VText(label, VTypo.micro, color = VColors.ink3)
    }
}

// ---------------------------------------------------------------- Tomorrow（设计稿：h56 / pad[0,14] / gap12 / 时间块 52×26 r8 / 标题 13 / meta 11 / chevron 14）

@Composable
private fun TomorrowRow(row: TodayTomorrowRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .vPressable(scaleDown = 0.99f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 26.dp)
                .background(VColors.bg, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            VText(row.timeText, VTypo.numChip, color = VColors.ink2, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(row.title, VTypo.body, color = VColors.ink, maxLines = 1)
            row.metaText?.let { VText(it, VTypo.caption, color = VColors.ink3, maxLines = 1) }
        }
        VChevron(size = 14.dp)
    }
}

// ---------------------------------------------------------------- Review dialog

@Composable
private fun ReviewEditDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    // 自动唤起键盘：这个弹窗就是为了写复盘。
    val reviewFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        reviewFocus.requestFocus()
        keyboard?.show()
    }
    VDialog(onDismissRequest = onDismiss, maxWidth = 330.dp) {
        VDialogPanel() {
            VText("今日复盘", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(VColors.bg, RoundedCornerShape(12.dp))
                    .border(1.5.dp, VColors.accent, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                if (text.isEmpty()) {
                    VText("今天做得怎么样？写两句…", VTypo.body, color = VColors.ink3)
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxSize().imePadding().focusRequester(reviewFocus),
                    textStyle = VTypo.body.copy(color = VColors.ink),
                    cursorBrush = SolidColor(VColors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
            }
            Spacer(Modifier.height(16.dp))
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = { onSave(text) },
                confirmText = "保存",
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}
