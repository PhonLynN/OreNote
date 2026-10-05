package com.phonlynn.oreplan.v2.screens

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.order.OrderKeys
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.NoteBlock
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.NoteBlockRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.domain.usecase.DeleteItemUseCase
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.V2Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** 备注块草稿：正文 + 已复制进内部存储的附件。 */
data class EditorNoteDraft(
    val id: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
)

/** 可关联的父条目（待办/目标）。 */
data class ParentOption(val id: String, val title: String)

data class EditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val itemId: String? = null,
    val kind: ItemKind = ItemKind.EVENT,
    /** 已完成的待办：顶部按钮从「删除」换成「归档」。 */
    val isDone: Boolean = false,
    val title: String = "",
    val priority: Int = 0,
    // 日程时间
    val date: LocalDate = LocalDate.now(),
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 10 * 60,
    val allDay: Boolean = false,
    val endDay: LocalDate? = null,
    // 待办期望完成期
    val dueDate: LocalDate? = null,
    val dueTimeEnabled: Boolean = false,
    val dueMinute: Int = 23 * 60,
    // 关联
    val parentId: String? = null,
    val parentTitle: String? = null,
    /**
     * 归属的待办组。
     * 待办 → 落到 groupId；待办组 → 它是自己的**父组**，落库进 parentId（组的嵌套）。
     */
    val groupId: String? = null,
    val groupTitle: String? = null,
    /** 可选的待办组（全部未归档的组），供「所属待办组」选择器。 */
    val groupOptions: List<ParentOption> = emptyList(),
    val availableParents: List<ParentOption> = emptyList(),
    // 颜色
    val colorTag: String? = null,
    // 备注
    val location: String = "",
    val notes: List<EditorNoteDraft> = emptyList(),
    val canSave: Boolean = false,
)

/**
 * 日程/待办编辑器（V2）。
 *
 * 新建与编辑同页；新建时条目 id 提前生成（[draftItemId]），这样备注块与附件能先挂在
 * 同一个 id 下，点「保存」时才落库，不会出现 id 对不上。提醒是**写穿**的（改动即写库），
 * 这样「更多提醒设置」页写回后编辑器回来就能直接看到最新值，不用再手动同步。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditorV2ViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val noteBlockRepository: NoteBlockRepository,
    private val attachmentRepository: AttachmentRepository,
    private val reminderRepository: ReminderRepository,
    private val deleteTree: DeleteItemUseCase,
    private val settingsStore: AppSettingsStore,
    val attachmentStorage: AttachmentStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val argItemId: String? =
        savedStateHandle.get<String>(V2Routes.ARG_ITEM_ID)?.takeIf { it.isNotBlank() }
    private val argDay: LocalDate? =
        savedStateHandle.get<Int>(V2Routes.ARG_DAY)
            ?.takeIf { it != V2Routes.NO_INT }
            ?.let { LocalDate.ofEpochDay(it.toLong()) }
    private val argStartMinute: Int? =
        savedStateHandle.get<Int>(V2Routes.ARG_START_MINUTE)
            ?.takeIf { it != V2Routes.NO_INT }
    private val argKind: ItemKind? =
        savedStateHandle.get<String>(V2Routes.ARG_KIND)?.takeIf { it.isNotBlank() }
            ?.let { raw -> ItemKind.entries.firstOrNull { it.name == raw } }
    /** 预选的待办组（从待办页分组行的「+」进来时带）。 */
    private val argGroupId: String? =
        savedStateHandle.get<String>(V2Routes.ARG_GROUP_ID)?.takeIf { it.isNotBlank() }

    /** 新条目预生成的 id（编辑态为真实条目 id）。“再添加一个”会重新生成。 */
    private var draftItemId: String = Ids.newId()

    /** 本条目当前挂靠的 id：新建 = draftItemId，编辑 = 已有 id。 */
    private val _targetId = MutableStateFlow(draftItemId)
    val targetItemId: String get() = _targetId.value

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** 提醒写穿观察：本条目当前的提醒（开启 = 非 null）。 */
    val reminder: StateFlow<Reminder?> =
        _targetId
            .flatMapLatest { id -> reminderRepository.observeOf(id).map { it.firstOrNull() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val existing = argItemId?.let { itemRepository.getById(it) }
        _targetId.value = existing?.id ?: draftItemId
        val allItems = itemRepository.getAll()
        val parents = allItems
            .filter { it.kind == ItemKind.TASK || it.kind == ItemKind.GOAL }
            .filterNot { it.id == argItemId }
            .map { ParentOption(it.id, it.title) }
        // 待办组选项：全部未归档的组。用全角空格把层级表达在标题里 ——
        // 弹层是单行列表，不做真正的树控件。
        val groupOptions = allItems
            .filter { it.kind == ItemKind.TODO_GROUP && it.status != ItemStatus.CANCELLED }
            .sortedWith(compareBy({ it.depth }, { it.orderIndex }, { it.title }))
            .map { ParentOption(it.id, "　".repeat(it.depth) + it.title) }

        val base = if (existing != null) fromItem(existing) else newState()

        // 备注块与附件
        val blocks = existing?.let { noteBlockRepository.getOf(it.id) }.orEmpty()
        val notes = blocks.map { block ->
            EditorNoteDraft(
                id = block.id,
                text = block.body,
                attachments = attachmentRepository.getOf(block.id),
            )
        }

        // 新建时可能带了「预选待办组」（从分组行的 + 进来）：作为初始归属。
        val initialGroupId = base.groupId ?: argGroupId
        _state.value = base.copy(
            loading = false,
            availableParents = parents,
            groupOptions = groupOptions,
            groupId = initialGroupId,
            groupTitle = groupOptions.firstOrNull { it.id == initialGroupId }?.title?.trim(),
            notes = notes,
            canSave = base.title.isNotBlank(),
        )
    }

    private fun newState(): EditorUiState {
        val day = argDay ?: LocalDate.now(zone)
        val kind = argKind ?: if (argStartMinute != null) ItemKind.EVENT else ItemKind.TASK
        val startMinute = argStartMinute ?: 9 * 60
        return EditorUiState(
            loading = false,
            isNew = true,
            kind = kind,
            date = day,
            startMinute = startMinute,
            endMinute = startMinute + 60,
            dueDate = if (kind == ItemKind.TASK) day else null,
            canSave = false,
        )
    }

    private fun fromItem(item: Item): EditorUiState {
        val localStart = item.startAt?.atZone(zone)
        val localEnd = item.endAt?.atZone(zone)
        return EditorUiState(
            loading = false,
            isNew = false,
            itemId = item.id,
            kind = item.kind,
            isDone = item.status == com.phonlynn.oreplan.domain.model.ItemStatus.DONE,
            title = item.title,
            priority = item.priority,
            date = localStart?.toLocalDate()
                ?: item.softDueAt?.atZone(zone)?.toLocalDate()
                ?: LocalDate.now(zone),
            startMinute = localStart?.let { it.hour * 60 + it.minute } ?: 9 * 60,
            endMinute = localEnd?.let { it.hour * 60 + it.minute } ?: 10 * 60,
            allDay = item.allDay,
            endDay = if (item.allDay) {
                localEnd?.toLocalDate()?.minusDays(1) ?: item.startAt?.atZone(zone)?.toLocalDate()
            } else {
                null
            },
            dueDate = item.softDueAt?.atZone(zone)?.toLocalDate(),
            dueTimeEnabled = item.softDueAt != null,
            dueMinute = item.softDueAt?.atZone(zone)?.let { it.hour * 60 + it.minute } ?: 23 * 60,
            parentId = item.parentId,
            // 待办组的「所属待办组」存在 parentId 上（组的嵌套），界面上与待办的归属
            // 共用同一个字段位，所以读回来时要摆到 groupId 这一栏（保存时再映回去）。
            groupId = if (item.kind == ItemKind.TODO_GROUP) item.parentId else item.groupId,
            colorTag = item.colorTag,
            location = item.location.orEmpty(),
            canSave = item.title.isNotBlank(),
        )
    }

    // ---------------------------------------------------------------- 编辑动作

    fun setTitle(value: String) = update { it.copy(title = value, canSave = value.isNotBlank()) }
    fun setPriority(value: Int) = update { it.copy(priority = value.coerceIn(0, 3)) }
    fun setLocation(value: String) = update { it.copy(location = value) }

    fun setDate(value: LocalDate) = update { it.copy(date = value) }
    fun setStartMinute(value: Int) = update { state ->
        val v = value.coerceIn(0, 1439)
        val duration = state.endMinute - state.startMinute
        state.copy(startMinute = v, endMinute = (v + duration.coerceAtLeast(60)).coerceIn(0, 1439))
    }
    fun setEndMinute(value: Int) = update { it.copy(endMinute = value.coerceIn(0, 1439)) }
    fun setDurationMinutes(minutes: Int) = update { state ->
        val end = (state.startMinute + minutes).coerceAtMost(1439)
        state.copy(endMinute = end)
    }
    fun setAllDay(enabled: Boolean) = update { state ->
        state.copy(allDay = enabled, endDay = if (enabled) state.endDay ?: state.date else null)
    }
    fun setEndDay(value: LocalDate?) = update { it.copy(endDay = value) }

    fun setDueDate(value: LocalDate?) = update { it.copy(dueDate = value) }
    fun setDueTimeEnabled(enabled: Boolean) = update { it.copy(dueTimeEnabled = enabled) }
    fun setDueMinute(value: Int) = update { it.copy(dueMinute = value.coerceIn(0, 1439)) }

    fun setParent(parentId: String?, parentTitle: String?) = update {
        it.copy(parentId = parentId, parentTitle = parentTitle)
    }
    fun clearParent() = update { it.copy(parentId = null, parentTitle = null) }

    /**
     * 新建时切换「待办 / 待办组」。
     *
     * 只在新建态给切：编辑态改类型会牵扯已有子节点（组里的待办、目标里的子步），
     * 不是一次字段改写能完成的。
     */
    fun setKind(kind: ItemKind) = update { it.copy(kind = kind) }

    /** 选「所属待办组」（对待办=归属组；对待办组=它的父组）。 */
    fun setGroup(groupId: String?, groupTitle: String?) = update {
        it.copy(groupId = groupId, groupTitle = groupTitle)
    }
    fun clearGroup() = update { it.copy(groupId = null, groupTitle = null) }

    fun setColorTag(tag: String?) = update { it.copy(colorTag = tag) }

    // ---------------------------------------------------------------- 备注块

    fun addNoteBlock() = update { state ->
        state.copy(
            notes = state.notes + EditorNoteDraft(id = Ids.newId(), text = ""),
        )
    }

    fun setNoteText(blockId: String, text: String) = update { state ->
        state.copy(notes = state.notes.map { if (it.id == blockId) it.copy(text = text) else it })
    }

    fun deleteNoteBlock(blockId: String) {
        val removed = _state.value.notes.firstOrNull { it.id == blockId }
        update { state ->
            state.copy(notes = state.notes.filterNot { it.id == blockId })
        }
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
            update { state ->
                state.copy(
                    notes = state.notes.map { draft ->
                        if (draft.id == blockId) draft.copy(attachments = draft.attachments + imported) else draft
                    },
                )
            }
        }
    }

    fun removeAttachment(blockId: String, attachmentId: String) {
        val removed = _state.value.notes
            .firstOrNull { it.id == blockId }
            ?.attachments
            ?.firstOrNull { it.id == attachmentId }
        update { state ->
            state.copy(
                notes = state.notes.map { draft ->
                    if (draft.id == blockId) draft.copy(attachments = draft.attachments.filterNot { it.id == attachmentId }) else draft
                },
            )
        }
        removed?.let { attachment ->
            viewModelScope.launch { attachmentStorage.delete(attachment) }
        }
    }

    // ---------------------------------------------------------------- 提醒（写穿）

    fun toggleReminder(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                reminderRepository.deleteOf(targetItemId)
                return@launch
            }
            val lead = settingsStore.settings.first().reminderLeadMinutes
            upsertReminder(lead)
        }
    }

    fun selectReminderOffset(minutes: Int) {
        viewModelScope.launch { upsertReminder(minutes) }
    }

    private suspend fun upsertReminder(offsetMinutes: Int) {
        val reference = referenceStartAt(_state.value) ?: return
        val existing = reminderRepository.observeOf(targetItemId).first().firstOrNull()
        reminderRepository.upsert(
            Reminder(
                id = existing?.id ?: Ids.newId(),
                itemId = targetItemId,
                triggerAt = reference.minus(Duration.ofMinutes(offsetMinutes.toLong())),
                offsetMinutes = offsetMinutes,
                enabled = true,
            ),
        )
    }

    // ---------------------------------------------------------------- 草稿落库

    /**
     * 跳转「更多提醒设置」之前先把条目落库。
     *
     * 提醒表有指向 items 的外键：条目不存在时，提醒既读不出来也写不进去。
     * 草稿行用完整的目标 id 写入（title 兜底「未命名条目」），保存时同一 id 走
     * INSERT REPLACE 覆盖成完整内容；用户直接放弃编辑时，草稿行留在库里
     * （比「配置完提醒却又丢失」可接受）。
     */
    fun ensureDraftPersisted(onReady: () -> Unit) {
        val stateNow = _state.value
        if (!stateNow.isNew) {
            onReady()
            return
        }
        viewModelScope.launch {
            runCatching {
                val now = Instant.now()
                val (startAt, endAt) = computeStartEnd(stateNow)
                val softDueAt = if (stateNow.kind == ItemKind.TASK) {
                    stateNow.dueDate?.let { due ->
                        if (stateNow.dueTimeEnabled) {
                            due.atTime(stateNow.dueMinute / 60, stateNow.dueMinute % 60).atZone(zone).toInstant()
                        } else {
                            due.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant()
                        }
                    }
                } else {
                    null
                }
                val base = itemRepository.getById(draftItemId)
                val title = stateNow.title.trim().ifBlank { "未命名条目" }
                val item = if (base != null) {
                    base.copy(
                        title = title,
                        startAt = startAt,
                        endAt = endAt,
                        allDay = stateNow.kind == ItemKind.EVENT && stateNow.allDay,
                        softDueAt = softDueAt,
                        priority = stateNow.priority,
                        colorTag = stateNow.colorTag,
                        location = stateNow.location.trim().ifBlank { null },
                        updatedAt = now,
                    )
                } else {
                    val parent = stateNow.parentId?.let { itemRepository.getById(it) }
                    val draft = if (parent != null) {
                        Item.newChild(parent = parent, kind = stateNow.kind, title = title, now = now, id = draftItemId)
                    } else {
                        Item.newRoot(kind = stateNow.kind, title = title, now = now, id = draftItemId)
                    }
                    draft.copy(
                        startAt = startAt,
                        endAt = endAt,
                        allDay = stateNow.kind == ItemKind.EVENT && stateNow.allDay,
                        softDueAt = softDueAt,
                        priority = stateNow.priority,
                        colorTag = stateNow.colorTag,
                        location = stateNow.location.trim().ifBlank { null },
                    )
                }
                if (base == null) itemRepository.create(item) else itemRepository.update(item)
            }
            onReady()
        }
    }

    // ---------------------------------------------------------------- 保存

    fun save(onSaved: () -> Unit, saveAndNew: Boolean = false) {
        val current = _state.value
        if (!current.canSave) return
        val kind = current.kind
        viewModelScope.launch {
            saveInternal(current)
            if (saveAndNew) {
                resetForNew(kind)
            }
            onSaved()
        }
    }

    private suspend fun saveInternal(current: EditorUiState) {
            val existing = current.itemId?.let { itemRepository.getById(it) }
            val now = Instant.now()

            val (startAt, endAt) = computeStartEnd(current)
            val softDueAt = if (current.kind == ItemKind.TASK) {
                current.dueDate?.let { due ->
                    if (current.dueTimeEnabled) {
                        due.atTime(current.dueMinute / 60, current.dueMinute % 60).atZone(zone).toInstant()
                    } else {
                        due.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant()
                    }
                }
            } else {
                null
            }

            val isGroup = current.kind == ItemKind.TODO_GROUP
            // 待办组：父组走 parentId（组的嵌套）；待办：关联往 parentId，归属往 groupId。
            val nestParentId = if (isGroup) current.groupId else current.parentId
            val saved = if (existing != null) {
                existing.copy(
                    title = current.title.trim(),
                    // 组没有优先级/时间/截止/颜色/地点这些语义，一律清空 ——
                    // 否则会留下看不见的脏值（组行不展示它们）。
                    priority = if (isGroup) 0 else current.priority,
                    startAt = startAt,
                    endAt = endAt,
                    allDay = current.kind == ItemKind.EVENT && current.allDay,
                    softDueAt = softDueAt,
                    colorTag = if (isGroup) null else current.colorTag,
                    location = if (isGroup) null else current.location.trim().ifBlank { null },
                    groupId = if (isGroup) null else current.groupId,
                    updatedAt = now,
                )
            } else {
                val parent = nestParentId?.let { itemRepository.getById(it) }
                val draft = if (parent != null) {
                    Item.newChild(parent = parent, kind = current.kind, title = current.title.trim(), now = now, id = draftItemId)
                } else {
                    Item.newRoot(kind = current.kind, title = current.title.trim(), now = now, id = draftItemId)
                }
                if (isGroup) {
                    draft.copy(priority = 0, groupId = null)
                } else {
                    draft.copy(
                        priority = current.priority,
                        startAt = startAt,
                        endAt = endAt,
                        allDay = current.kind == ItemKind.EVENT && current.allDay,
                        softDueAt = softDueAt,
                        colorTag = current.colorTag,
                        location = current.location.trim().ifBlank { null },
                        groupId = current.groupId,
                    )
                }
            }

            if (existing == null) itemRepository.create(saved) else itemRepository.update(saved)

            // 组换了父组：必须走 reparent —— 它同时改写物化路径（treePath）。
            // 只在 update 里改 parentId 会让 treePath 过期，子树从此挂错地方。
            if (existing != null && isGroup && existing.parentId != current.groupId) {
                itemRepository.reparent(existing.id, current.groupId)
            }

            persistNotes(saved.id)
            // 事件时间可能变了，回填一次提醒的 triggerAt（真正排钟由 planner 按 offset 重算）
            fixReminderTrigger(saved)
    }

    /** 保存并再添加一个：清空表单，保留当前类型。 */
    private fun resetForNew(kind: ItemKind) {
        draftItemId = Ids.newId()
        _targetId.value = draftItemId
        val day = LocalDate.now(zone)
        _state.value = EditorUiState(
            loading = false,
            isNew = true,
            kind = kind,
            date = day,
            startMinute = 9 * 60,
            endMinute = 10 * 60,
            dueDate = if (kind == ItemKind.TASK) day else null,
            canSave = false,
        )
    }

    private fun computeStartEnd(state: EditorUiState): Pair<Instant?, Instant?> {
        if (state.kind != ItemKind.EVENT) return null to null
        return if (state.allDay) {
            val start = state.date.atStartOfDay(zone).toInstant()
            val endDay = state.endDay ?: state.date
            val end = endDay.plusDays(1).atStartOfDay(zone).toInstant()
            start to end
        } else {
            val start = state.date.atTime(state.startMinute / 60, state.startMinute % 60).atZone(zone).toInstant()
            var endMinute = state.endMinute
            if (endMinute <= state.startMinute) endMinute = (state.startMinute + 60).coerceAtMost(1439)
            val end = state.date.atTime(endMinute / 60, endMinute % 60).atZone(zone).toInstant()
            start to end
        }
    }

    private fun referenceStartAt(state: EditorUiState): Instant? = when (state.kind) {
        ItemKind.EVENT -> computeStartEnd(state).first
        ItemKind.TASK -> state.dueDate?.let { due ->
            if (state.dueTimeEnabled) {
                due.atTime(state.dueMinute / 60, state.dueMinute % 60).atZone(zone).toInstant()
            } else {
                due.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant()
            }
        }
        else -> null
    }

    /** 备注块 + 附件「整体对齐」落库。 */
    private suspend fun persistNotes(itemId: String) {
        val drafts = _state.value.notes
        val oldBlocks = noteBlockRepository.getOf(itemId)

        val blocks = drafts.mapIndexed { index, draft ->
            NoteBlock(
                id = draft.id,
                ownerId = itemId,
                heading = "",
                body = draft.text,
                orderIndex = (index + 1) * OrderKeys.GAP,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        }
        noteBlockRepository.upsertAll(blocks)
        noteBlockRepository.retainOnly(itemId, blocks.map { it.id })

        drafts.forEach { draft ->
            attachmentRepository.upsertAll(draft.attachments)
            attachmentRepository.retainOnly(draft.id, draft.attachments.map { it.id })
        }

        // 被删掉的备注块：清理它们的附件（文件在删除时已清，这里补删索引）
        val keptIds = drafts.map { it.id }.toSet()
        oldBlocks.filterNot { it.id in keptIds }.forEach { old ->
            attachmentRepository.getOf(old.id).forEach { attachmentStorage.delete(it) }
            attachmentRepository.deleteOf(old.id)
        }
    }

    /** 事件时间变了之后，回填提醒 triggerAt（真正排钟由 planner 用 offset 重算）。 */
    private suspend fun fixReminderTrigger(saved: Item) {
        val reminder = reminderRepository.observeOf(saved.id).first().firstOrNull() ?: return
        val reference = when (saved.kind) {
            ItemKind.EVENT -> saved.startAt
            ItemKind.TASK -> saved.softDueAt
            else -> null
        } ?: return
        val offset = reminder.offsetMinutes ?: 0
        reminderRepository.upsert(reminder.copy(triggerAt = reference.minus(Duration.ofMinutes(offset.toLong()))))
    }

    // ---------------------------------------------------------------- 删除

    /**
     * 归档已完成的待办（与详情浮层同一个动作、同一套约定）。
     *
     * 归档 = `ItemStatus.CANCELLED`（项目既有约定，见 PlanArchiveViewModel），
     * 归档后从待办列表 / 今日页消失，能在「日程设置 → 已归档的待办」里恢复。
     * 因为它可恢复，这里**不弹确认**（删除才会）。
     */
    fun archive(onArchived: () -> Unit) {
        val id = _state.value.itemId ?: return
        viewModelScope.launch {
            val item = itemRepository.getById(id) ?: return@launch
            itemRepository.update(
                item.copy(
                    status = com.phonlynn.oreplan.domain.model.ItemStatus.CANCELLED,
                    updatedAt = Instant.now(),
                ),
            )
            onArchived()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val current = _state.value
        val id = current.itemId ?: return
        viewModelScope.launch {
            // 备注块与附件（ITEM owner）不受 DeleteItemUseCase 管，先清
            cleanNotesAndAttachments(id)
            reminderRepository.deleteOf(id)
            // 删待办组：**连同组内的待办一起删**（与删除文件夹一致，已给用户确认）。
            //
            // 为什么不能只靠 deleteTree：待办的归属是 groupId 而不是物化路径，
            // 光删组会把组内待办留成「无组待办」—— 看起来就是「删了但没删干净」。
            // 子组则跟着物化路径一起被 deleteTree 带走，但它们的待办同样要单独收。
            if (current.kind == ItemKind.TODO_GROUP) {
                val group = itemRepository.getById(id)
                if (group != null) {
                    val all = itemRepository.getAll()
                    val groupIds = all
                        .filter { it.kind == ItemKind.TODO_GROUP && it.treePath.startsWith(group.treePath) }
                        .mapTo(HashSet()) { it.id }
                    all
                        .filter { it.kind == ItemKind.TASK && it.groupId in groupIds }
                        .forEach { task ->
                            cleanNotesAndAttachments(task.id)
                            reminderRepository.deleteOf(task.id)
                            // 待办自己也可能挂着子节点（关联），按子树删。
                            deleteTree(task.id)
                        }
                }
            }
            deleteTree(id)
            onDeleted()
        }
    }

    /** 清掉一个条目名下的备注块、以及这些块里的附件。 */
    private suspend fun cleanNotesAndAttachments(itemId: String) {
        noteBlockRepository.getOf(itemId).forEach { block ->
            attachmentRepository.getOf(block.id).forEach { attachmentStorage.delete(it) }
            attachmentRepository.deleteOf(block.id)
        }
        noteBlockRepository.deleteOf(itemId)
    }

    private inline fun update(transform: (EditorUiState) -> EditorUiState) {
        _state.value = transform(_state.value)
    }
}
