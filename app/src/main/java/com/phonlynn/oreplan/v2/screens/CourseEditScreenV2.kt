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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.time.WeekParity
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.routine.RoutineConfig
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VFloatingInputDialog
import com.phonlynn.oreplan.v2.components.VNavBar
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import android.net.Uri
import java.time.Instant
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.phonlynn.oreplan.core.order.OrderKeys
import com.phonlynn.oreplan.core.rt.RichEditState
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.richtext.VKeyboardToolbar
import com.phonlynn.oreplan.v2.richtext.VRichToolbar
import com.phonlynn.oreplan.v2.components.VDialogButtons
import com.phonlynn.oreplan.v2.components.VBottomActionBar

data class SessionDraftV2(
    val id: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val startWeek: Int,
    /** 0 表示「上到学期末」。 */
    val endWeek: Int,
    val parity: WeekParity,
    val location: String?,
)

data class CourseEditV2State(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val courseId: String? = null,
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    val note: String = "",
    val credit: String = "",
    val colorHex: String? = null,
    val sessions: List<SessionDraftV2> = emptyList(),
    val routine: RoutineConfig = RoutineConfig.Default,
    val canSave: Boolean = false,
    /** 备注块（与待办的编辑页完全同构）。 */
    val noteBlocks: List<EditorNoteDraft> = emptyList(),
)

@HiltViewModel
class CourseEditV2ViewModel @Inject constructor(
    private val courseRepository: CourseRepository,
    private val appSettings: AppSettingsStore,
    private val noteBlockRepository: NoteBlockRepository,
    private val attachmentRepository: AttachmentRepository,
    /** 备注块与待办**同一套**：正文富文本 + 附件，读写也走同一张表。 */
    val attachmentStorage: AttachmentStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val argCourseId = savedStateHandle.get<String>(V2Routes.ARG_CARD_ID)?.takeIf { it.isNotBlank() }
    private val argDay = savedStateHandle.get<Int>(V2Routes.ARG_DAY)
        ?.takeIf { it != V2Routes.NO_INT }
        ?.let { LocalDate.ofEpochDay(it.toLong()) }
    private val argStartMinute = savedStateHandle.get<Int>(V2Routes.ARG_START_MINUTE)
        ?.takeIf { it != V2Routes.NO_INT }

    private val _state = MutableStateFlow(CourseEditV2State())
    val state: StateFlow<CourseEditV2State> = _state.asStateFlow()

    private val removedSessionIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            appSettings.settings.collect { s -> _state.value = _state.value.copy(routine = s.routine) }
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val routine = appSettings.settings.value.routine
        val existing = argCourseId?.let { courseRepository.getCourse(it) }
        val existingSessions = argCourseId?.let { courseRepository.getSessionsOf(it) }.orEmpty()

        // 备注块：挂在课程自己的 id 上；旧版本的纯文本备注迁成第一块，保存时才真正落库
        // （不保存就退出的话旧内容还在原字段里，不会丢）。
        val noteDrafts = run {
            val drafts = existing?.let { c ->
                noteBlockRepository.getOf(c.id).map { block ->
                    EditorNoteDraft(
                        id = block.id,
                        text = block.body,
                        attachments = attachmentRepository.getOf(block.id),
                    )
                }
            }.orEmpty()
            val legacy = existing?.note?.takeIf { it.isNotBlank() }
            if (drafts.isEmpty() && legacy != null) {
                listOf(EditorNoteDraft(id = Ids.newId(), text = legacy))
            } else {
                drafts
            }
        }

        if (existing != null) {
            _state.value = CourseEditV2State(
                loading = false,
                isNew = false,
                courseId = existing.id,
                name = existing.name,
                teacher = existing.teacher.orEmpty(),
                location = existing.defaultLocation.orEmpty(),
                note = existing.note.orEmpty(),
                credit = formatCredit(existing.credit),
                colorHex = existing.colorHex,
                sessions = existingSessions.map(::toDraft),
                routine = routine,
                canSave = existing.name.isNotBlank(),
                noteBlocks = noteDrafts,
            )
            return
        }

        val prefilled = if (argDay != null && argStartMinute != null) {
            val endMinute = argStartMinute + routine.periodMinutes
            listOf(
                SessionDraftV2(
                    id = Ids.newId(),
                    dayOfWeek = argDay.dayOfWeek.value,
                    startMinute = argStartMinute,
                    endMinute = endMinute,
                    startWeek = 1,
                    endWeek = 0,
                    parity = WeekParity.ALL,
                    location = null,
                ),
            )
        } else {
            emptyList()
        }

        _state.value = CourseEditV2State(
            loading = false,
            isNew = true,
            colorHex = CoursePalette.keys.first(),
            sessions = prefilled,
            routine = routine,
        )
    }

    private fun toDraft(session: CourseSession) = SessionDraftV2(
        id = session.id,
        dayOfWeek = session.dayOfWeek,
        startMinute = session.startMinuteOfDay,
        endMinute = session.endMinuteOfDay,
        startWeek = session.startWeek,
        endWeek = if (session.endWeek == Int.MAX_VALUE) 0 else session.endWeek,
        parity = session.parity,
        location = session.location,
    )

    fun setName(v: String) = update { it.copy(name = v, canSave = v.isNotBlank()) }
    fun setTeacher(v: String) = update { it.copy(teacher = v) }
    fun setLocation(v: String) = update { it.copy(location = v) }
    fun setNote(v: String) = update { it.copy(note = v) }

    // ---------------------------------------------------------------- 备注块（与待办的编辑页同一套）

    fun addNoteBlock() = update { st ->
        st.copy(noteBlocks = st.noteBlocks + EditorNoteDraft(id = Ids.newId(), text = ""))
    }

    fun setNoteText(blockId: String, text: String) = update { st ->
        st.copy(noteBlocks = st.noteBlocks.map { if (it.id == blockId) it.copy(text = text) else it })
    }

    fun deleteNoteBlock(blockId: String) {
        val removed = _state.value.noteBlocks.firstOrNull { it.id == blockId }
        update { st -> st.copy(noteBlocks = st.noteBlocks.filterNot { it.id == blockId }) }
        removed?.attachments?.forEach { attachment ->
            viewModelScope.launch { attachmentStorage.delete(attachment) }
        }
    }

    fun addAttachments(blockId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = uris.mapNotNull { uri ->
                attachmentStorage.importAttachment(
                    uri = uri,
                    ownerType = AttachmentOwner.ITEM,
                    ownerId = blockId,
                )
            }
            if (imported.isEmpty()) return@launch
            update { st ->
                st.copy(
                    noteBlocks = st.noteBlocks.map { draft ->
                        if (draft.id == blockId) draft.copy(attachments = draft.attachments + imported) else draft
                    },
                )
            }
        }
    }

    fun removeAttachment(blockId: String, attachmentId: String) {
        update { st ->
            st.copy(
                noteBlocks = st.noteBlocks.map { draft ->
                    if (draft.id == blockId) draft.copy(attachments = draft.attachments.filterNot { it.id == attachmentId }) else draft
                },
            )
        }
    }
    fun setCredit(v: String) = update { it.copy(credit = v) }
    fun setColor(hex: String) = update { it.copy(colorHex = hex) }

    fun addSession(draft: SessionDraftV2) = update { it.copy(sessions = it.sessions + draft) }
    fun updateSession(draft: SessionDraftV2) = update { state ->
        state.copy(sessions = state.sessions.map { if (it.id == draft.id) draft else it })
    }
    fun removeSession(sessionId: String) = update { state ->
        if (state.courseId != null) removedSessionIds += sessionId
        state.copy(sessions = state.sessions.filterNot { it.id == sessionId })
    }

    fun newSessionDraft(): SessionDraftV2 {
        val last = _state.value.sessions.lastOrNull()
        return SessionDraftV2(
            id = Ids.newId(),
            dayOfWeek = last?.dayOfWeek ?: 1,
            startMinute = last?.startMinute ?: _state.value.routine.startMinute,
            endMinute = last?.endMinute ?: (_state.value.routine.startMinute + _state.value.routine.periodMinutes),
            startWeek = last?.startWeek ?: 1,
            endWeek = last?.endWeek?.takeIf { it != Int.MAX_VALUE } ?: 0,
            parity = last?.parity ?: WeekParity.ALL,
            location = null,
        )
    }

    private inline fun update(transform: (CourseEditV2State) -> CourseEditV2State) {
        _state.value = transform(_state.value)
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            val courseId = current.courseId ?: Ids.newId()
            courseRepository.upsertCourse(
                Course(
                    id = courseId,
                    name = current.name.trim(),
                    teacher = current.teacher.trim().ifBlank { null },
                    defaultLocation = current.location.trim().ifBlank { null },
                    colorHex = current.colorHex,
                    // 备注内容统一走备注块表（旧字段只在迁移前保留原值）。
                    note = null,
                    credit = current.credit.trim().toDoubleOrNull() ?: 0.0,
                ),
            )
            removedSessionIds.forEach { courseRepository.deleteSession(it) }
            removedSessionIds.clear()
            current.sessions.forEach { draft ->
                courseRepository.upsertSession(
                    CourseSession(
                        id = draft.id,
                        courseId = courseId,
                        dayOfWeek = draft.dayOfWeek,
                        startMinuteOfDay = draft.startMinute,
                        endMinuteOfDay = draft.endMinute,
                        startWeek = draft.startWeek,
                        endWeek = if (draft.endWeek <= 0) Int.MAX_VALUE else draft.endWeek,
                        parity = draft.parity,
                        location = draft.location?.trim()?.ifBlank { null },
                        note = null,
                    ),
                )
            }
            persistNotes(courseId)
            onSaved()
        }
    }

    /** 备注块照待办的做法「保存时整体对齐」：全部 upsert + retainOnly 删掉不在列表里的。 */
    private suspend fun persistNotes(courseId: String) {
        val drafts = _state.value.noteBlocks
        val oldBlocks = noteBlockRepository.getOf(courseId)
        val blocks = drafts.mapIndexed { index, draft ->
            NoteBlock(
                id = draft.id,
                ownerId = courseId,
                heading = "",
                body = draft.text,
                orderIndex = (index + 1) * OrderKeys.GAP,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        }
        noteBlockRepository.upsertAll(blocks)
        noteBlockRepository.retainOnly(courseId, blocks.map { it.id })

        drafts.forEach { draft ->
            attachmentRepository.upsertAll(draft.attachments)
            attachmentRepository.retainOnly(draft.id, draft.attachments.map { it.id })
        }
        val kept = drafts.map { it.id }.toSet()
        oldBlocks.filterNot { it.id in kept }.forEach { old ->
            attachmentRepository.getOf(old.id).forEach { attachmentRepository.delete(it.id) }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val courseId = _state.value.courseId ?: return
        viewModelScope.launch {
            courseRepository.deleteCourse(courseId)
            // 课程自己的备注块与附件一起清掉。
            noteBlockRepository.getOf(courseId).forEach { block ->
                attachmentRepository.getOf(block.id).forEach { attachmentRepository.delete(it.id) }
            }
            noteBlockRepository.deleteOf(courseId)
            onDeleted()
        }
    }
}

@Composable
fun CourseEditScreenV2(
    onBack: () -> Unit,
    viewModel: CourseEditV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var editingField by remember { mutableStateOf<EditField?>(null) }
    var sessionTarget by remember { mutableStateOf<SessionDraftV2?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    // 备注块：与待办编辑页同一套（聚焦块 + 附件选择）。
    var activeNote by remember { mutableStateOf<Pair<String, RichEditState>?>(null) }
    var attachTarget by remember { mutableStateOf<String?>(null) }
    val imePx = WindowInsets.ime.getBottom(LocalDensity.current)

    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        attachTarget?.let { blockId -> viewModel.addAttachments(blockId, uris) }
        attachTarget = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            VNavBar(
                title = if (state.isNew) "新建课程" else "编辑课程",
                onBack = onBack,
                actions = {
                    if (!state.isNew && !state.loading) {
                        com.phonlynn.oreplan.v2.components.VIconButton(
                            icon = Lucide.Trash2,
                            onClick = { showDelete = true },
                            tint = VColors.rose,
                        )
                    }
                },
            )

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    VText("正在读取…", VTypo.bodyMed, color = VColors.ink3)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    VSectionHead(title = "课程信息")
                    VCard {
                        FieldRow("名称", state.name, "课程名称，例如：高等数学") { editingField = EditField.Name }
                        VDivider(14.dp)
                        FieldRow("教师", state.teacher, "任课老师（可选）") { editingField = EditField.Teacher }
                        VDivider(14.dp)
                        FieldRow("教室", state.location, "默认上课地点（可选）") { editingField = EditField.Location }
                        VDivider(14.dp)
                        FieldRow("学分", state.credit, "学分，例如：3") { editingField = EditField.Credit }
                    }

                    VSectionHead(title = "备注")
                    state.noteBlocks.forEach { draft ->
                        // 与待办、白板**同一套**富文本内核：每个备注块持有一个 RichEditState。
                        val noteState = remember(draft.id) { RichEditState.of(draft.text) }
                        NoteBlockView(
                            draft = draft,
                            state = noteState,
                            storage = viewModel.attachmentStorage,
                            onTextChange = { viewModel.setNoteText(draft.id, it) },
                            onFocused = { activeNote = draft.id to noteState },
                            onAddAttachment = {
                                attachTarget = draft.id
                                attachmentLauncher.launch(AttachmentStorage.PICKER_MIME_TYPES)
                            },
                            onRemoveAttachment = { viewModel.removeAttachment(draft.id, it.id) },
                            onDelete = { viewModel.deleteNoteBlock(draft.id) },
                            onOpenFile = { attachment -> openFile(context, viewModel.attachmentStorage, attachment) },
                        )
                    }
                    AddNoteButton(onClick = viewModel::addNoteBlock)

                    VSectionHead(title = "颜色")
                    VCard {
                        ColorPickerRow(state.colorHex, onSelect = viewModel::setColor)
                    }

                    VSectionHead(title = "上课时段", note = if (state.sessions.isEmpty()) null else "${state.sessions.size} 个")
                    VCard {
                        state.sessions.forEach { draft ->
                            SessionRow(draft, state.routine, onClick = { sessionTarget = draft }, onRemove = { viewModel.removeSession(draft.id) })
                            VDivider(14.dp)
                        }
                        AddSessionRow { sessionTarget = viewModel.newSessionDraft() }
                    }

                    if (!state.canSave) {
                        VText("课程名称不能为空", VTypo.caption, color = VColors.rose)
                    }
                }
            }
        }

        // 备注的富文本工具栏：钉在键盘上方（与待办编辑页、白板全屏编辑同一套）。
        // 显隐只看 IME inset，不看 state.focused（跨层引用的时序坑）。
        val active = activeNote
        if (imePx > 0 && active != null) {
            VKeyboardToolbar(
                visible = true,
                applyImePadding = true,
                gateOnImeInsets = false,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                VRichToolbar(
                    state = active.second,
                    onValueChange = { viewModel.setNoteText(active.first, it) },
                )
            }
        }

        // 底部保存 —— 改用共用 VBottomActionBar（用户 2026-09-29：保存条遮罩要与其它页统一）。
        // 原实现是手写 Box(.background(scrimTop))：**没有 36dp 渐变带**，与
        // Editor/Remind/BoardSettings 的 VBottomActionBar 观感不一致（另一处 GoalEditor 同样手写，
        // 但 GoalEditor 属规划模块、待重设计，本次不动）。
        // 说明：VBottomActionBar 的左右内衬为 20dp（原 12dp）、底部 14dp（原 16dp）——
        // 这是"统一到共用件"的必然结果，与 EditorScreenV2 完全一致。
        if (!state.loading) {
            VBottomActionBar(Modifier.align(Alignment.BottomCenter)) {
                // 与日程/卡片同源：走共用件。禁用时灰底灰字、颜色有过渡动画。
                // （原先内联手写、底色恒 accent —— canSave=false 时看着可用却点不动。）
                com.phonlynn.oreplan.v2.components.VSaveButton(
                    text = if (state.isNew) "添加课程" else "保存",
                    enabled = state.canSave,
                    // 课程没有 ✓ 图标（原先就没有）—— 保留原样，不顺手改没点名的部分。
                    showCheckIcon = false,
                    onClick = { viewModel.save(onSaved = onBack) },
                )
            }
        }
    }

    editingField?.let { field ->
        VFloatingInputDialog(
            icon = field.icon,
            value = field.value(state),
            onValueChange = { v -> field.onChange(viewModel, v) },
            onConfirm = { editingField = null },
            onDismiss = { editingField = null },
            placeholder = field.placeholder,
            confirmText = "确定",
        )
    }

    sessionTarget?.let { draft ->
        SessionDialog(
            initial = draft,
            routine = state.routine,
            isNew = state.sessions.none { it.id == draft.id },
            onDismiss = { sessionTarget = null },
            onConfirm = { updated ->
                val exists = state.sessions.any { it.id == updated.id }
                if (exists) viewModel.updateSession(updated) else viewModel.addSession(updated)
                sessionTarget = null
            },
        )
    }

    if (showDelete) {
        VConfirmDeleteDialog(
            title = "删除课程？",
            message = "删除后该课程及其全部上课时段会被移除，无法撤销。",
            objectName = state.name,
            onConfirm = {
                showDelete = false
                viewModel.delete(onDeleted = onBack)
            },
            onDismiss = { showDelete = false },
        )
    }
}

private enum class EditField(
    val placeholder: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val value: (CourseEditV2State) -> String,
    val onChange: (CourseEditV2ViewModel, String) -> Unit,
) {
    Name("课程名称", Lucide.Type, { it.name }, { vm, v -> vm.setName(v) }),
    Teacher("任课老师", Lucide.User, { it.teacher }, { vm, v -> vm.setTeacher(v) }),
    Location("上课地点", Lucide.MapPin, { it.location }, { vm, v -> vm.setLocation(v) }),
    Note("备注", Lucide.FileText, { it.note }, { vm, v -> vm.setNote(v) }),
    Credit("学分", Lucide.Hash, { it.credit }, { vm, v -> vm.setCredit(v) }),
}

/** 学分显示：整数不带小数位。 */
internal fun formatCredit(credit: Double): String =
    if (credit <= 0.0) "" else if (credit % 1.0 == 0.0) credit.toInt().toString() else credit.toString()

@Composable
private fun FieldRow(label: String, value: String, placeholder: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 14.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        VText(label, VTypo.body, color = VColors.ink, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VText(
                value.ifBlank { placeholder },
                VTypo.caption12,
                color = if (value.isBlank()) VColors.ink3 else VColors.ink2,
                maxLines = 1,
            )
            Icon(Lucide.ChevronRight, contentDescription = null, Modifier.size(16.dp), tint = VColors.ink3)
        }
    }
}

@Composable
private fun ColorPickerRow(selectedHex: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoursePalette.keys.forEach { hex ->
            val tint = CoursePalette.tintFor(hex, hex)
            val selected = hex == selectedHex
            Box(
                Modifier
                    .size(34.dp)
                    .background(tint.soft, RoundedCornerShape(10.dp))
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) VColors.accent else VColors.line,
                        RoundedCornerShape(10.dp),
                    )
                    .vPressable(scaleDown = 0.9f, onClick = { onSelect(hex) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(14.dp).background(tint.strong, RoundedCornerShape(5.dp)))
            }
        }
    }
}

@Composable
private fun SessionRow(draft: SessionDraftV2, routine: RoutineConfig, onClick: () -> Unit, onRemove: () -> Unit) {
    val tint = CoursePalette.tintFor(null, draft.id)
    val (sp, ep) = minutesToPeriods(routine, draft.startMinute, draft.endMinute)
    val weeksText = if (draft.endWeek <= 0) "第 ${draft.startWeek} 周 到 学期末" else "第 ${draft.startWeek}-${draft.endWeek} 周"
    val parityText = when (draft.parity) {
        WeekParity.ODD -> "单周"
        WeekParity.EVEN -> "双周"
        else -> null
    }
    val subtitle = listOfNotNull(weeksText, parityText, draft.location).joinToString(" · ")

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(tint.soft, RoundedCornerShape(10.dp))
                .vPressable(scaleDown = 0.9f, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            VText(WEEKDAY_LABELS[draft.dayOfWeek - 1], VTypo.bodyMed, color = tint.strong, maxLines = 1)
        }
        Column(
            Modifier.weight(1f).vPressable(scaleDown = 0.985f, onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            VText("第 $sp-$ep 节", VTypo.bodyMed, color = VColors.ink, maxLines = 1)
            VText(subtitle, VTypo.caption, color = VColors.ink2, maxLines = 1)
        }
        Box(
            Modifier.size(30.dp).vPressable(scaleDown = 0.85f, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, contentDescription = null, Modifier.size(16.dp), tint = VColors.ink3)
        }
    }
}

@Composable
private fun AddSessionRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).vPressable(scaleDown = 0.985f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Plus, contentDescription = null, Modifier.size(16.dp), tint = VColors.accent)
        Spacer(Modifier.width(6.dp))
        VText("添加上课时段", VTypo.bodyMed, color = VColors.accent, maxLines = 1)
    }
}

@Composable
private fun SessionDialog(
    initial: SessionDraftV2,
    routine: RoutineConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (SessionDraftV2) -> Unit,
) {
    val totalPeriods = routine.totalPeriods.coerceAtLeast(1)
    var dayOfWeek by remember { mutableStateOf(initial.dayOfWeek) }
    val (sp0, ep0) = minutesToPeriods(routine, initial.startMinute, initial.endMinute)
    var startPeriod by remember { mutableStateOf(sp0.coerceIn(1, totalPeriods)) }
    var endPeriod by remember { mutableStateOf(ep0.coerceIn(1, totalPeriods)) }
    var startWeek by remember { mutableStateOf(initial.startWeek) }
    var endWeek by remember { mutableStateOf(initial.endWeek) }
    var parity by remember { mutableStateOf(initial.parity) }
    var location by remember { mutableStateOf(initial.location.orEmpty()) }

    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText(if (isNew) "添加上课时段" else "编辑上课时段", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(16.dp))

            VText("星期", VTypo.caption, color = VColors.ink3)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WEEKDAY_LABELS.forEachIndexed { i, label ->
                    SelectChip(label, i + 1 == dayOfWeek) { dayOfWeek = i + 1 }
                }
            }

            Spacer(Modifier.height(14.dp))
            VText("节次", VTypo.caption, color = VColors.ink3)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStepper(startPeriod, 1, totalPeriods, "第") { v ->
                    startPeriod = v
                    if (endPeriod < v) endPeriod = v
                }
                Spacer(Modifier.weight(1f))
                VText("到", VTypo.body, color = VColors.ink2)
                Spacer(Modifier.weight(1f))
                MiniStepper(endPeriod, 1, totalPeriods, "第") { v ->
                    endPeriod = v
                    if (startPeriod > v) startPeriod = v
                }
            }

            Spacer(Modifier.height(14.dp))
            VText("周次范围", VTypo.caption, color = VColors.ink3)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniStepper(startWeek, 1, 30, "第") { v ->
                    startWeek = v
                    if (endWeek in 1..29 && endWeek < v) endWeek = v
                }
                VText("周 到", VTypo.body, color = VColors.ink2)
                if (endWeek <= 0) {
                    SelectChip("学期末", true) { endWeek = (startWeek + 4).coerceAtMost(30) }
                } else {
                    MiniStepper(endWeek, 1, 30, "第") { v ->
                        endWeek = v
                        if (startWeek > v) startWeek = v
                    }
                }
                SelectChip(if (endWeek <= 0) "改指定周" else "学期末", false) {
                    endWeek = if (endWeek <= 0) (startWeek + 4).coerceAtMost(30) else 0
                }
            }

            Spacer(Modifier.height(14.dp))
            VText("单双周", VTypo.caption, color = VColors.ink3)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectChip("每周", parity == WeekParity.ALL) { parity = WeekParity.ALL }
                SelectChip("单周", parity == WeekParity.ODD) { parity = WeekParity.ODD }
                SelectChip("双周", parity == WeekParity.EVEN) { parity = WeekParity.EVEN }
            }

            Spacer(Modifier.height(14.dp))
            VText("地点（可选）", VTypo.caption, color = VColors.ink3)
            Spacer(Modifier.height(6.dp))
            InlineField(value = location, onValueChange = { location = it }, placeholder = "留空则用课程默认地点")

            Spacer(Modifier.height(20.dp))
            VDialogButtons(
                onCancel = onDismiss,
                onConfirm = {
                    val (startMinute, endMinute) = periodsToMinutes(routine, startPeriod, endPeriod)
                    onConfirm(
                        initial.copy(
                            dayOfWeek = dayOfWeek,
                            startMinute = startMinute,
                            endMinute = endMinute,
                            startWeek = startWeek,
                            endWeek = endWeek,
                            parity = parity,
                            location = location.trim().ifBlank { null },
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) VColors.accent else VColors.surface2
    val fg = if (selected) Color.White else VColors.ink2
    Box(
        Modifier
            .height(32.dp)
            .background(bg, RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp)
            .vPressable(scaleDown = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(text, VTypo.caption12.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal), color = fg, maxLines = 1)
    }
}

@Composable
private fun MiniStepper(value: Int, min: Int, max: Int, prefix: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniStepButton(Lucide.Minus, value > min) { onChange((value - 1).coerceAtLeast(min)) }
        VText("$prefix$value", VTypo.bodyMed, color = VColors.ink, maxLines = 1)
        MiniStepButton(Lucide.Plus, value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun MiniStepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).background(VColors.bg, RoundedCornerShape(9.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(9.dp))
            .vPressable(scaleDown = 0.9f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = if (enabled) VColors.ink2 else VColors.ink3)
    }
}

@Composable
private fun InlineField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(VColors.bg, RoundedCornerShape(12.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            VText(placeholder, VTypo.body, color = VColors.ink3, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = VTypo.body.copy(color = VColors.ink),
            singleLine = true,
            cursorBrush = SolidColor(VColors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}
