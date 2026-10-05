package com.phonlynn.oreplan.v2.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalDensity
import com.phonlynn.oreplan.core.rt.RichEditState
import com.phonlynn.oreplan.v2.richtext.VKeyboardToolbar
import com.phonlynn.oreplan.v2.richtext.VRichTextField
import com.phonlynn.oreplan.v2.richtext.VRichToolbar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.platform.attachment.AttachmentOpener
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.QuickDurationRow
import com.phonlynn.oreplan.v2.components.VChipSmall
import com.phonlynn.oreplan.v2.components.VBadge
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VFloatingInputDialog
import com.phonlynn.oreplan.v2.components.VIconButton
import com.phonlynn.oreplan.v2.components.VSwitch
import com.phonlynn.oreplan.v2.components.VTopBarClose
import com.phonlynn.oreplan.v2.components.VSearchField
import com.phonlynn.oreplan.v2.components.VSaveButton
import com.phonlynn.oreplan.v2.components.VSegmented
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** 颜色预设：null = 默认（自动分色），其余是色板键。 */
private val COLOR_PRESETS: List<String?> = listOf(null, "accent", "amber", "lilac", "rose")

/** 自定义取色的候选色（#RRGGBB）。 */
private val CUSTOM_PALETTE: List<String> = listOf(
    "#1C6B58", "#2E8C72", "#9C6516", "#BC5F63", "#6A61BE", "#A5484E",
    "#3B82F6", "#06B6D4", "#10B981", "#84CC16", "#F59E0B", "#F97316",
    "#EF4444", "#EC4899", "#8B5CF6", "#6366F1", "#64748B", "#334155",
)

/** 快捷时长档位（分钟）。 */
private val QUICK_DURATIONS: List<Int> = listOf(30, 60, 90, 120, 180)

/**
 * 快捷时长胶囊的文案。
 *
 * 整小时不写小数（60 → 「1 小时」、180 → 「3 小时」），非整小时写小数
 * （90 → 「1.5 小时」）——用户 2026-09-23 反馈：原来 90 分钟显示成
 * 「1 小时 30 分钟」，比其他胶囊长出一截，把整行挤得难看了。
 *
 * 注意只用于**快捷时长胶囊**；详情页那种「时长」描述仍走
 * `AutoPinTime.durationText`（「1 小时 35 分」），两者口径不同是有意的。
 */
private fun quickDurationLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> String.format(java.util.Locale.US, "%.1f 小时", minutes / 60.0)
}

/**
 * 日程/待办编辑器（08 规格）。新建与编辑同页。
 */
@Composable
fun EditorScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
) {
    val viewModel: EditorV2ViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reminder by viewModel.reminder.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 附件选择器：记住这次是给哪个备注块加附件
    var attachTarget by remember { mutableStateOf<String?>(null) }
    // 日期/时间弹窗
    var dateTarget by remember { mutableStateOf<DateTarget?>(null) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }
    // 其他弹窗
    var showLocation by remember { mutableStateOf(false) }
    var showParentPicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 当前聚焦的备注块（id + 富文本状态）：键盘上方的工具栏作用于它。
    // 工具栏显隐**不看** state.focused（跨层引用的时序坑，白板注释里记过），
    // 只看 IME inset；focused 只用来判断「工具栏该作用在哪个块」。
    var activeNote by remember { mutableStateOf<Pair<String, RichEditState>?>(null) }
    val imePx = WindowInsets.ime.getBottom(LocalDensity.current)

    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        attachTarget?.let { blockId -> viewModel.addAttachments(blockId, uris) }
        attachTarget = null
    }

    val isTask = state.kind == ItemKind.TASK
    /** 待办组：只关心「名称 + 所属待办组」，其余字段（时间/截止/优先级/提醒/颜色/备注）都没有意义。 */
    val isGroup = state.kind == ItemKind.TODO_GROUP
    val title = when {
        isGroup && state.isNew -> "新建待办组"
        isGroup -> "编辑待办组"
        isTask && state.isNew -> "新建待办"
        isTask -> "编辑待办"
        state.isNew -> "新建日程"
        else -> "编辑日程"
    }
    val saveLabel = when {
        isGroup -> "保存待办组"
        isTask -> "保存待办"
        else -> "保存日程"
    }

    Box(Modifier.fillMaxSize().background(VColors.bg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            VTopBarClose(
                title = title,
                onClose = onBack,
                trailing = {
                    if (!state.isNew) {
                        if (isTask && state.isDone) {
                            // 已完成的待办：这里是**归档**而不是删除 —— 完成的成果不该被直接删掉。
                            // 归档可恢复，所以不弹确认；归档后能在「日程设置 → 已归档的待办」找回。
                            VIconButton(
                                icon = Lucide.Archive,
                                onClick = { viewModel.archive(onArchived = onBack) },
                                tint = VColors.accent,
                            )
                        } else {
                            VIconButton(icon = Lucide.Trash2, onClick = { showDeleteConfirm = true }, tint = VColors.rose)
                        }
                    }
                },
            )

            Column(
                Modifier
                    // 用 weight(1f) 而不是 fillMaxSize()：后者会在父 Column 里吃掉
                    // **全部**剩余高度，把下方的保存条挤出屏幕（新建页看不到保存按钮）。
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // 底部留出悬浮操作条的高度（36dp 渐变 + 按钮区 + 导航栏），
                    // 否则最后一条备注会被底栏遮住。
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 20.dp,
                        // 底栏高度；键盘弹出时补上**键盘 + 工具栏**的高度 ——
                        // 否则正文最后几行没有可滚动空间、被键盘挡死（用户 2026-09-23）。
                        bottom = 168.dp + if (imePx > 0) {
                            with(LocalDensity.current) { imePx.toDp() } + 56.dp
                        } else {
                            0.dp
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 新建时选「待办 / 待办组」（用户 2026-09-26）。
                // 只在新建态给切：编辑态改类型会牵扯已有子节点（组里的待办）。
                if (state.isNew) {
                    VSegmented(
                        options = listOf("待办", "待办组"),
                        selectedIndex = if (isGroup) 1 else 0,
                        onSelect = { index ->
                            viewModel.setKind(if (index == 1) ItemKind.TODO_GROUP else ItemKind.TASK)
                        },
                    )
                }

                FieldLabel(if (isGroup) "名称" else "标题")
                TitleInput(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    placeholder = when {
                        isGroup -> "例如：学习"
                        isTask -> "例如：写完实验报告"
                        else -> "例如：小组讨论"
                    },
                )

                when (state.kind) {
                    ItemKind.TASK -> {
                        // 待办：期望完成期 + 优先级
                        FieldLabel("期望完成期")
                        TaskDueCard(
                            dueDate = state.dueDate,
                            dueTimeEnabled = state.dueTimeEnabled,
                            dueMinute = state.dueMinute,
                            onPickDate = { dateTarget = DateTarget.DUE },
                            onPickTime = { timeTarget = TimeTarget.DUE },
                            onToggleTime = viewModel::setDueTimeEnabled,
                            onClearDate = { viewModel.setDueDate(null) },
                        )
                        FieldLabel("优先级")
                        PriorityRow(priority = state.priority, onSelect = viewModel::setPriority)
                    }

                    ItemKind.EVENT -> {
                        // 日程：时间卡 + 快捷时长
                        FieldLabel("时间")
                        TimeCard(
                            state = state,
                            onPickDate = { dateTarget = DateTarget.START },
                            onPickEndDate = { dateTarget = DateTarget.END },
                            onPickStartTime = { timeTarget = TimeTarget.START },
                            onPickEndTime = { timeTarget = TimeTarget.END },
                            onToggleAllDay = viewModel::setAllDay,
                        )
                        if (!state.allDay) {
                            FieldLabel("快捷时长")
                            // 选中判定沿用原口径：结束时刻 == 开始 + 该档位（且不跨过当天末尾）。
                            QuickDurationRow(
                                durations = QUICK_DURATIONS,
                                labelOf = ::quickDurationLabel,
                                selectedMinutes = QUICK_DURATIONS.firstOrNull { minutes ->
                                    state.endMinute == (state.startMinute + minutes).coerceAtMost(1439)
                                },
                                onSelect = viewModel::setDurationMinutes,
                            )
                        }
                    }

                    // 待办组：没有时间/截止/优先级语义（设计稿的组行也不展示它们）。
                    ItemKind.TODO_GROUP -> Unit
                    else -> Unit
                }

                if (!isGroup) {
                    FieldLabel("详情")
                    ParentCard(
                        parentTitle = state.parentTitle,
                        onClick = { showParentPicker = true },
                    )
                }

                // 待办 → 归属组（groupId）；待办组 → 它的父组（落库进 parentId）。
                // 两者对用户是同一句话「放进哪个组」，所以共用一行、同一个选择器。
                if (isTask || isGroup) {
                    FieldLabel("所属待办组")
                    ParentCard(
                        label = "所属待办组",
                        icon = Lucide.Folder,
                        parentTitle = state.groupTitle,
                        emptyText = "未加入",
                        onClick = { showGroupPicker = true },
                    )
                }

                if (!isGroup) {
                FieldLabel("提醒")
                ReminderCardV2(
                    enabled = reminder != null,
                    offsetMinutes = reminder?.offsetMinutes ?: 15,
                    onToggleEnabled = viewModel::toggleReminder,
                    onSelectOffset = viewModel::selectReminderOffset,
                    onMoreSettings = {
                        viewModel.ensureDraftPersisted {
                            navigate(V2Routes.remind(viewModel.targetItemId))
                        }
                    },
                )

                FieldLabel("颜色")
                ColorRow(
                    colorTag = state.colorTag,
                    onSelect = viewModel::setColorTag,
                    onCustom = { showColorPicker = true },
                )

                FieldLabel("备注")
                LocationRow(location = state.location, onClick = { showLocation = true })
                state.notes.forEach { draft ->
                    // 与白板**同一套**富文本内核：每个备注块持有一个 RichEditState。
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
                }
            }
        }

        // 底部操作条：**悬浮**在内容之上（与 Tab 栏、卡片编辑页完全同款：
        // 36dp「透明 → scrimTop」渐变带 + 纯色实底，滚动内容从它下面淡出）。
        // 之前把它放在 Column 里（不悬浮），渐变带下面没有内容，看着就是一条突兀的灰带。
        // 备注的富文本工具栏：钉在键盘上方（和全屏卡片编辑页同一套）。
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

        VBottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            // ⚠️ 这里原来是**内联手写**的：底色恒为 accent、只用 `vPressable(enabled=canSave)`
            //    控制可点性 —— 于是 `canSave=false` 时按钮**看起来完全可用却点不动**
            //    （与卡片页同一个坑）。现改走共用件，禁用时灰底灰字、且颜色有过渡动画。
            //
            // 判据说明：日程/待办**所有字段都是可选的**（时间/优先级都有默认值），
            // 它唯一的"内容"就是标题 ⇒ 用「标题非空」是**对的**（与卡片的"正文"口径不冲突）。
            VSaveButton(
                text = saveLabel,
                enabled = state.canSave,
                onClick = { viewModel.save(onSaved = onBack, saveAndNew = false) },
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .vPressable(scaleDown = 0.98f, enabled = state.canSave) {
                        viewModel.save(onSaved = { }, saveAndNew = true)
                    },
                contentAlignment = Alignment.Center,
            ) {
                VText("保存并再添加一个", VTypo.caption12, color = VColors.accent)
            }
        }
    }

    // ---------------- 弹窗 ----------------

    dateTarget?.let { target ->
        val initial = when (target) {
            DateTarget.START -> state.date
            DateTarget.END -> state.endDay ?: state.date
            DateTarget.DUE -> state.dueDate ?: state.date
        }
        VDatePickerDialog(
            initial = initial,
            onConfirm = { picked ->
                when (target) {
                    DateTarget.START -> viewModel.setDate(picked)
                    DateTarget.END -> viewModel.setEndDay(picked)
                    DateTarget.DUE -> viewModel.setDueDate(picked)
                }
                dateTarget = null
            },
            onDismiss = { dateTarget = null },
        )
    }

    timeTarget?.let { target ->
        val initial = when (target) {
            TimeTarget.START -> state.startMinute
            TimeTarget.END -> state.endMinute
            TimeTarget.DUE -> state.dueMinute
        }
        VTimePickerDialog(
            initialMinute = initial,
            onConfirm = { value ->
                when (target) {
                    TimeTarget.START -> viewModel.setStartMinute(value)
                    TimeTarget.END -> viewModel.setEndMinute(value)
                    TimeTarget.DUE -> viewModel.setDueMinute(value)
                }
                timeTarget = null
            },
            onDismiss = { timeTarget = null },
        )
    }

    if (showLocation) {
        VFloatingInputDialog(
            icon = Lucide.MapPin,
            value = state.location,
            onValueChange = viewModel::setLocation,
            onConfirm = { showLocation = false },
            onDismiss = { showLocation = false },
            placeholder = "输入地点，例如：图书馆 3F 研讨间",
            confirmText = "确定",
        )
    }

    if (showParentPicker) {
        ParentPickerDialog(
            parents = state.availableParents,
            selectedId = state.parentId,
            onSelect = { id, title -> viewModel.setParent(id, title); showParentPicker = false },
            onClear = { viewModel.clearParent(); showParentPicker = false },
            onDismiss = { showParentPicker = false },
        )
    }

    if (showGroupPicker) {
        ParentPickerDialog(
            parents = state.groupOptions,
            selectedId = state.groupId,
            title = "所属待办组",
            clearLabel = "移出待办组",
            onSelect = { id, title -> viewModel.setGroup(id, title); showGroupPicker = false },
            onClear = { viewModel.clearGroup(); showGroupPicker = false },
            onDismiss = { showGroupPicker = false },
        )
    }

    if (showColorPicker) {
        VDialog(onDismissRequest = { showColorPicker = false }) {
            VDialogPanel() {
                VText("自定义颜色", VTypo.dialogTitle, color = VColors.ink)
                Spacer(Modifier.height(14.dp))
                CUSTOM_PALETTE.chunked(6).forEach { rowColors ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowColors.forEach { hex ->
                            val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(VColors.accent)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(color)
                                    .vPressable(scaleDown = 0.94f) {
                                        viewModel.setColorTag(hex)
                                        showColorPicker = false
                                    },
                            )
                        }
                        // 补齐空位保持对齐
                        repeat(6 - rowColors.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) {
                            viewModel.setColorTag(null)
                            showColorPicker = false
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    VText("恢复默认", VTypo.button, color = VColors.ink)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        VConfirmDeleteDialog(
            title = "确认删除？",
            // 待办组：说清楚组内的东西会一起没（否则用户以为只是删个文件夹）。
            message = when {
                isGroup -> "删除后无法撤销，这个待办组以及组内的待办都会被永久移除。"
                isTask -> "删除后无法撤销，这条待办将被永久移除。"
                else -> "删除后无法撤销，这条日程将被永久移除。"
            },
            objectName = state.title.ifBlank { "未命名" },
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete(onDeleted = onBack)
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

private enum class DateTarget { START, END, DUE }
private enum class TimeTarget { START, END, DUE }

@Composable
private fun FieldLabel(text: String) {
    VText(text, VTypo.caption, color = VColors.ink3, modifier = Modifier.fillMaxWidth())
}

/** 标题输入：聚焦时 accent 描边。 */
@Composable
private fun TitleInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(if (focused) 1.5.dp else 1.dp, if (focused) VColors.accent else VColors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                VText(placeholder, VTypo.bodyMed, color = VColors.ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                textStyle = VTypo.bodyMed.copy(color = VColors.ink),
                singleLine = true,
                cursorBrush = SolidColor(VColors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }
    }
}

/** 时间卡：日期/开始/结束/全天（非全天）或 起止日/全天（全天）。 */
@Composable
private fun TimeCard(
    state: EditorUiState,
    onPickDate: () -> Unit,
    onPickEndDate: () -> Unit,
    onPickStartTime: () -> Unit,
    onPickEndTime: () -> Unit,
    onToggleAllDay: (Boolean) -> Unit,
) {
    VCard {
        if (state.allDay) {
            TimeRow(Lucide.CalendarDays, "开始日期", formatDateOnly(state.date), onClick = onPickDate)
            VDivider()
            TimeRow(Lucide.CalendarDays, "结束日期", formatDateOnly(state.endDay ?: state.date), onClick = onPickEndDate)
        } else {
            TimeRow(Lucide.CalendarDays, "日期", formatDateOnly(state.date), onClick = onPickDate)
            VDivider()
            TimeRow(Lucide.Clock3, "开始", "%02d:%02d".format(state.startMinute / 60, state.startMinute % 60), onClick = onPickStartTime)
            VDivider()
            TimeRow(
                Lucide.Timer,
                "结束",
                "%02d:%02d".format(state.endMinute / 60, state.endMinute % 60),
                trailing = {
                    VChipSmall(durationText(state.endMinute - state.startMinute))
                },
                onClick = onPickEndTime,
            )
        }
        VDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VBadge(icon = Lucide.Sun, iconSize = 15.dp)
            Spacer(Modifier.width(11.dp))
            VText("全天", VTypo.body, color = VColors.ink, modifier = Modifier.weight(1f))
            VSwitch(checked = state.allDay, onCheckedChange = onToggleAllDay)
        }
    }
}

@Composable
private fun TimeRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        VBadge(icon = icon, iconSize = 15.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(label, VTypo.caption, color = VColors.ink3)
            VText(value, VTypo.bodyMed, color = VColors.ink)
        }
        if (trailing != null) trailing()
        VChevron()
    }
}

/** 待办期望完成期卡。 */
@Composable
private fun TaskDueCard(
    dueDate: LocalDate?,
    dueTimeEnabled: Boolean,
    dueMinute: Int,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onToggleTime: (Boolean) -> Unit,
    onClearDate: () -> Unit,
) {
    VCard {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .vPressable(scaleDown = 0.985f, onClick = onPickDate)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            VBadge(icon = Lucide.CalendarDays, iconSize = 15.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText("日期", VTypo.caption, color = VColors.ink3)
                VText(if (dueDate != null) formatDateOnly(dueDate) else "不设期望完成期", VTypo.bodyMed, color = VColors.ink)
            }
            VChevron()
        }
        if (dueDate != null) {
            VDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VBadge(icon = Lucide.Clock3, iconSize = 15.dp)
                Spacer(Modifier.width(11.dp))
                VText("设置时间", VTypo.body, color = VColors.ink, modifier = Modifier.weight(1f))
                VSwitch(checked = dueTimeEnabled, onCheckedChange = onToggleTime)
            }
            if (dueTimeEnabled) {
                VDivider()
                TimeRow(
                    Lucide.Clock3,
                    "时间",
                    "%02d:%02d".format(dueMinute / 60, dueMinute % 60),
                    onClick = onPickTime,
                )
            }
            VDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .vPressable(scaleDown = 0.97f, onClick = onClearDate)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Lucide.X, null, Modifier.size(14.dp), tint = VColors.ink3)
                VText("清除期望完成期", VTypo.caption12, color = VColors.ink3)
            }
        }
    }
}

/** 优先级 chips：无/低/中/高 → 0-3。 */
@Composable
private fun PriorityRow(priority: Int, onSelect: (Int) -> Unit) {
    val options = listOf(0 to "无", 1 to "低", 2 to "中", 3 to "高")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val selected = value == priority
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(if (selected) VColors.accent else VColors.surface, RoundedCornerShape(11.dp))
                    .border(1.dp, if (selected) VColors.accent else VColors.line, RoundedCornerShape(11.dp))
                    .vPressable(scaleDown = 0.95f) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                VText(label, VTypo.caption, color = if (selected) Color.White else VColors.ink2)
            }
        }
    }
}

/** 详情卡：关联事项行。 */
@Composable
private fun ParentCard(
    parentTitle: String?,
    onClick: () -> Unit,
    /** 行标题（关联事项 / 所属待办组 共用这一行）。 */
    label: String = "关联事项",
    icon: ImageVector = Lucide.ListTodo,
    /** 未选时的占位文案（关联：不关联；待办组：未加入）。 */
    emptyText: String = "不关联",
) {
    VCard {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .vPressable(scaleDown = 0.985f, onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            VBadge(icon = icon, iconSize = 15.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText(label, VTypo.caption, color = VColors.ink3)
                VText(parentTitle ?: emptyText, VTypo.bodyMed, color = VColors.ink)
            }
            VChevron()
        }
    }
}

/** 关联事项选择弹层。 */
@Composable
private fun ParentPickerDialog(
    parents: List<ParentOption>,
    selectedId: String?,
    onSelect: (String, String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    /** 弹层标题（关联事项 / 所属待办组 共用）。 */
    title: String = "关联事项",
    /** 清除项的文案（待办组用「移出待办组」）。 */
    clearLabel: String = "清除关联",
) {
    // 搜索词只活在弹层内部：弹层一关（从组合里移除）就自动清空，不需要外部复位。
    var query by remember { mutableStateOf("") }
    // 与白板页同一套分词口径：空格 / 全角空格 / 制表符分词，**每个词都要命中**才算匹配
    // （所以多打一个词是"继续收窄"，与白板页搜索的心智一致）。
    val tokens = BoardSearch.tokenize(query)
    val shown = if (tokens.isEmpty()) {
        parents
    } else {
        parents.filter { p -> tokens.all { p.title.contains(it, ignoreCase = true) } }
    }

    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText(title, VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(12.dp))
            VSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索事项（可用空格分词）…",
            )
            Spacer(Modifier.height(12.dp))
            // 列表高度从 360 收到 300：加进来的搜索框（46 + 12）几乎等量，弹层总高基本不变。
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedId != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(VColors.surface2, RoundedCornerShape(12.dp))
                            .vPressable(scaleDown = 0.97f, onClick = onClear)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VText(clearLabel, VTypo.bodyMed, color = VColors.ink)
                    }
                }
                shown.forEach { parent ->
                    val active = parent.id == selectedId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(if (active) VColors.accentSoft else VColors.surface2, RoundedCornerShape(12.dp))
                            .vPressable(scaleDown = 0.97f) { onSelect(parent.id, parent.title) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        VText(parent.title, VTypo.bodyMed, color = if (active) VColors.accent else VColors.ink, maxLines = 1)
                        if (active) Icon(Lucide.Check, null, Modifier.size(16.dp), tint = VColors.accent)
                    }
                }
                if (parents.isEmpty()) {
                    VText("还没有可关联的待办或目标", VTypo.caption12, color = VColors.ink3)
                } else if (shown.isEmpty()) {
                    VText("没有匹配的事项", VTypo.caption12, color = VColors.ink3)
                }
            }
        }
    }
}

/** 颜色选择行：5 预设 + 自定义。 */
@Composable
private fun ColorRow(
    colorTag: String?,
    onSelect: (String?) -> Unit,
    onCustom: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COLOR_PRESETS.forEach { tag ->
            val selected = tag == colorTag
            val bg = swatchBg(tag)
            Box(
                Modifier
                    .size(44.dp)
                    .background(bg, RoundedCornerShape(14.dp))
                    .border(if (selected) 1.5.dp else 1.dp, if (selected) VColors.accent else VColors.line, RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.94f) { onSelect(tag) },
                contentAlignment = Alignment.Center,
            ) {
                if (tag == null && selected) {
                    Icon(Lucide.Check, null, Modifier.size(18.dp), tint = VColors.accent)
                }
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .background(
                    Brush.sweepGradient(
                        listOf(VColors.accentSoft, VColors.amberSoft, VColors.roseSoft, VColors.lilacSoft, VColors.accentSoft),
                    ),
                    RoundedCornerShape(14.dp),
                )
                .border(1.dp, VColors.line, RoundedCornerShape(14.dp))
                .vPressable(scaleDown = 0.94f, onClick = onCustom),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.Pencil, null, Modifier.size(16.dp), tint = VColors.ink2)
        }
    }
}

private fun swatchBg(tag: String?): Color = when (tag) {
    null -> VColors.surface
    "accent" -> VColors.accentSoft
    "amber" -> VColors.amberSoft
    "lilac" -> VColors.lilacSoft
    "rose" -> VColors.roseSoft
    else -> runCatching { Color(android.graphics.Color.parseColor(tag)) }.getOrDefault(VColors.accentSoft)
}

/** 地点行。 */
@Composable
private fun LocationRow(location: String, onClick: () -> Unit) {
    VCard(radius = 14.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .vPressable(scaleDown = 0.985f, onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            VBadge(icon = Lucide.MapPin, iconSize = 15.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText("地点", VTypo.caption, color = VColors.ink3)
                VText(location.ifBlank { "添加地点" }, VTypo.bodyMed, color = if (location.isBlank()) VColors.ink3 else VColors.ink)
            }
            VChevron()
        }
    }
}

/** 单个备注块：多行文本 + 图片缩略图 + 文件行 + 添加附件。 */
@Composable
fun NoteBlockView(
    draft: EditorNoteDraft,
    state: RichEditState,
    storage: AttachmentStorage,
    onTextChange: (String) -> Unit,
    onFocused: () -> Unit,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onDelete: () -> Unit,
    onOpenFile: (Attachment) -> Unit,
) {
    VCard(radius = 14.dp) {
        // 备注正文：与白板卡片正文**同一个**富文本编辑器（编辑、渲染、工具栏都一套）。
        LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        Box(
            Modifier
                .fillMaxWidth()
                // 底部只留一点点：文本下方原本还叠着 minHeight 的余量，
                // 加起来看着就是"下半行距过大"（用户 2026-09-23）。
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
        ) {
            VRichTextField(
                value = draft.text,
                state = state,
                onValueChange = onTextChange,
                textStyle = VTypo.body.copy(lineHeight = 19.sp),
                placeholder = "补充说明（可选）",
                // 高度完全由内容决定（minHeight=0 即只占一行）。
                // ⚠️ 曾误设为 38dp（"参考添加附件行"）—— 但那参考对象是页面底部按钮，
                // 与备注框无关；结果备注框被撑成约两行高（用户 2026-09-30 报「太高」）。
                minHeight = 0,
            )
        }

        val images = draft.attachments.filter { it.isImage }
        val files = draft.attachments.filter { !it.isImage }
        if (images.isNotEmpty()) {
            VDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    // 用户 2026-09-29：文本与附件间距过大 -> 取「文本与上方分割线」
                    // 同一档 = 6dp（原 12dp）。
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                images.forEach { attachment ->
                    ImageThumbnail(
                        attachment = attachment,
                        storage = storage,
                        onClick = { onOpenFile(attachment) },
                        onRemove = { onRemoveAttachment(attachment) },
                    )
                }
            }
        }
        files.forEach { attachment ->
            VDivider()
            FileRow(attachment = attachment, storage = storage, onClick = { onOpenFile(attachment) }, onRemove = { onRemoveAttachment(attachment) })
        }
        VDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .vPressable(scaleDown = 0.97f, onClick = onAddAttachment),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Lucide.Paperclip, null, Modifier.size(14.dp), tint = VColors.accent)
                VText("添加附件", VTypo.caption12, color = VColors.accent)
            }
            // 删除整块备注：放在这一行的最右端（原来独占右上角一行，挤压正文行宽）。
            //
            // 右移 6dp：28dp 的触摸框里有 15dp 图标、图标左右各余 ~6.5dp，
            // 所以**视觉上的** × 原本比分割线右缘内缩一截（用户 2026-09-28：太靠左）。
            // 触摸框右移后视觉右缘正好顶到分割线右缘，触摸区仍在卡片内。
            Box(
                Modifier
                    .size(28.dp)
                    .offset(x = 6.dp)
                    .vPressable(scaleDown = 0.9f, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.X, null, Modifier.size(15.dp), tint = VColors.ink3)
            }
        }
    }
}

@Composable
private fun ImageThumbnail(
    attachment: Attachment,
    storage: AttachmentStorage,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bitmap = rememberThumbnail(storage.fileOf(attachment))
    Box(
        Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .vPressable(scaleDown = 0.96f, onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(VColors.surface2), contentAlignment = Alignment.Center) {
                Icon(Lucide.Image, null, Modifier.size(20.dp), tint = VColors.ink3)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(18.dp)
                .background(VColors.dialogScrim, CircleShape)
                .vPressable(scaleDown = 0.85f, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, null, Modifier.size(11.dp), tint = Color.White)
        }
    }
}

@Composable
private fun FileRow(
    attachment: Attachment,
    storage: AttachmentStorage,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        VBadge(icon = fileIcon(attachment.mimeType), iconSize = 15.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(attachment.displayName, VTypo.bodyMed, color = VColors.ink, maxLines = 1)
            VText("${typeLabel(attachment.mimeType)} · ${formatSize(attachment.sizeBytes)}", VTypo.caption, color = VColors.ink3)
        }
        Box(
            Modifier
                .size(28.dp)
                .vPressable(scaleDown = 0.85f, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, null, Modifier.size(14.dp), tint = VColors.ink3)
        }
    }
}

private fun fileIcon(mimeType: String): ImageVector = when {
    mimeType.contains("sheet") || mimeType.contains("excel") -> Lucide.FileSpreadsheet
    else -> Lucide.FileText
}

/** 添加备注按钮。 */
@Composable
fun AddNoteButton(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(14.dp))
            .vPressable(scaleDown = 0.97f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.Plus, null, Modifier.size(16.dp), tint = VColors.accent)
        Spacer(Modifier.width(6.dp))
        VText("添加备注", VTypo.bodyMed, color = VColors.accent)
    }
}

/** 用系统应用打开附件文件。 */
fun openFile(context: android.content.Context, storage: AttachmentStorage, attachment: Attachment) {
    AttachmentOpener.openExternally(context, storage.fileOf(attachment), attachment.mimeType)
}

/** 图片缩略图：按需下采样解码，避免整张原图进内存。 */
@Composable
private fun rememberThumbnail(file: File): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 200 && bounds.outHeight / sample > 200) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

private fun typeLabel(mimeType: String): String = when {
    mimeType.startsWith("image/") -> "图片"
    mimeType.startsWith("audio/") -> "音频"
    mimeType.contains("pdf") -> "PDF"
    mimeType.contains("sheet") || mimeType.contains("excel") -> "Excel"
    mimeType.contains("word") -> "Word"
    else -> "文件"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
