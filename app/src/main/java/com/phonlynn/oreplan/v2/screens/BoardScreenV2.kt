package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.tag.nextTagSortIndex
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AutoPinLogic
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.core.rt.plainTextOf
import com.phonlynn.oreplan.core.rt.RichEditState
import com.phonlynn.oreplan.core.rt.plainPreviewOf
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VEmptyState
import com.phonlynn.oreplan.v2.components.VFAB
import com.phonlynn.oreplan.v2.components.vShadowRoom
import com.phonlynn.oreplan.v2.components.VSearchField
import com.phonlynn.oreplan.v2.components.VFloatingInputDialog
import com.phonlynn.oreplan.v2.components.VScreenTitle
import com.phonlynn.oreplan.v2.components.VTab
import com.phonlynn.oreplan.v2.components.VTabBottomPadding
import com.phonlynn.oreplan.v2.components.VTabScaffold
import com.phonlynn.oreplan.v2.components.openAttachmentV2
import com.phonlynn.oreplan.v2.components.VFullscreenImageViewer
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class BoardScreenUiState(
    val compact: Boolean = false,
    /** 卡片字号档：small / medium / large（只影响卡片标题与正文）。 */
    val cardFontSize: String = "medium",
    /** 卡片间距预设键：normal / compact。 */
    val spacingKey: String = "normal",
    /** 聚焦展示形态：card / fullscreen。 */
    val focusStyle: String = "card",
    val doubleTapEdit: Boolean = true,
    val dragReorder: Boolean = false,
    val newCardEntry: String = "fab",
    val defaultType: String = "QUICK",
    val cards: List<BoardCard> = emptyList(),
    val tags: List<BoardTag> = emptyList(),
    val cardTags: List<BoardCardTag> = emptyList(),
    val todoByCard: Map<String, List<BoardTodoItem>> = emptyMap(),
    val query: String = "",
    val selectedTagId: String? = null,
    val totalCount: Int = 0,
    val lastUpdatedText: String = "—",
    val loaded: Boolean = false,
    /** 完成待办后沉底（聚焦浮层用）。 */
    val doneToEnd: Boolean = true,
    /** 卡片是否带轻微随机倾斜。 */
    val cardTilt: Boolean = true,
    /** 自动宽度阈值：标题+正文总字数达到该值即为整行卡片。 */
    val autoWidthChars: Int = 24,
    /** 每张卡片的图片附件（按时间排序）。界面据它渲染图片区。 */    val imagesByCard: Map<String, List<com.phonlynn.oreplan.domain.model.Attachment>> = emptyMap(),
    /** 每张卡片的全部附件数（含非图片），用于「n 附件」气泡。 */
    val attachmentCountByCard: Map<String, Int> = emptyMap(),
    /** 每张卡片的标签链（从高到低），卡片底部每个标签一个胶囊。 */
    val tagChainByCard: Map<String, List<BoardTag>> = emptyMap(),
    /** 全局默认图片样式（卡片自身未设时用它）。 */
    val defaultImageLayout: String = "fill",
)

@HiltViewModel
class BoardScreenV2ViewModel @Inject constructor(
    private val repo: BoardRepository,
    private val settingsStore: com.phonlynn.oreplan.data.settings.AppSettingsStore,
    private val attachmentRepository: AttachmentRepository,
    private val reminderRepository: ReminderRepository,
    val attachmentStorage: AttachmentStorage,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedTagId = MutableStateFlow<String?>(null)

    private val filter = combine(query, selectedTagId) { q, t -> q to t }

    private val extrasFlow = combine(filter, settingsStore.settings) { f, s -> f to s }

    // 卡片关联：用于让搜索能命中「关联卡片标题」。
    // 与 extrasFlow 叠成一个输入，避免 combine 超过 5 路上限。
    /**
     * 新建卡片入口（fab / header）。
     *
     * 直接从设置流派生，**不经过 uiState 的 combine 缓存**：
     * uiState 用 `stateIn(WhileSubscribed(5s))`，页面在后台停留超过 5 秒后上游会被取消；
     * 回到页面时若某个 Room Flow 未立即发射，stateIn 会继续用旧值，
     * 导致「设置里改成悬浮了、右下角按钮不回来」（2026-09-19 实障）。
     */
    val newCardEntry: StateFlow<String> = settingsStore.settings
        .map { it.boardNewCardEntry }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            settingsStore.settings.value.boardNewCardEntry,
        )

    private data class BoardExtras(
        val query: String,
        val selectedTagId: String?,
        val settings: com.phonlynn.oreplan.data.settings.AppSettings,
        val links: List<com.phonlynn.oreplan.domain.model.BoardCardLink>,
        /** 全部附件（含非图片）。界面按 ownerId 分组、只取图片用。 */
        val attachments: List<com.phonlynn.oreplan.domain.model.Attachment>,
        /** 当前时刻（每分钟推进一次）。动态置顶的排序依赖它。 */
        val now: java.time.Instant,
    )

    /**
     * 动态置顶的时间推进源：每分钟发一次当前时刻。
     *
     * **为什么不直接在组合期读 `System.currentTimeMillis()`**：
     * 那样卡片不会「到点自动浮上来」，必须等下一次重组（比如用户碰了别的控件）
     * 才生效——用户设了时间却看不到任何反应。
     *
     * 一分钟一次足够：动态置顶的精度是分钟级，而每分钟一次的排序重算开销可忽略。
     */
    private val nowTick: kotlinx.coroutines.flow.Flow<java.time.Instant> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(java.time.Instant.now())
            kotlinx.coroutines.delay(60_000L)
        }
    }

    /**
     * 删除之后**主动**踢一下界面。
     *
     * ## 为什么光靠流不够（用户报过两次）
     *
     * 软删只往 `entity_ext` 写墓碑、**主表一行不动**。仓储那层已经用
     * `tombstones.observing(...)` 把墓碑接到了流里（见 `TombstoneRegistry`），
     * 所以**订阅活着的时候**是能刷新的。
     *
     * 但这个页面的 `uiState` 用了 `stateIn(WhileSubscribed(5_000))` ——
     * 上游会在无人订阅 5 秒后被取消。而**取消期间发生的墓碑变化，
     * 在恢复订阅时不会补发**：
     *
     * · Room 的流恢复时会重新查库 → 能拿到最新
     * · 但墓碑集合**在内存里没变过**，`combine` 只在它**变化**时发射 →
     *   恢复订阅的瞬间它给的还是"变化前"的那个值
     *
     * 结果就是用户看到的那句「删除后要切一下页面才刷新」。
     *
     * ## 做法
     *
     * 删除是**用户明确动作**，此时主动推一次计数器 ——
     * 一个自增的 Int 就够了，`combine` 会因此重算。不依赖任何时序假设。
     */
    private val refreshTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 让 [uiState] 立刻重算一次。删除 / 归档这类"改的是墓碑不是主表"的操作后调用。 */
    private fun invalidate() {
        refreshTick.value += 1
    }

    private val inputsWithLinks = combine(
        extrasFlow,
        repo.observeCardLinks(),
        attachmentRepository.observeAll(),
        nowTick,
        refreshTick,
    ) { e, links, attachments, now, _ ->
        val (pair, settings) = e
        BoardExtras(pair.first, pair.second, settings, links, attachments, now)
    }

    val uiState: StateFlow<BoardScreenUiState> = combine(
        repo.observeCards(),
        repo.observeTags(),
        repo.observeCardTags(),
        repo.observeTodoItems(),
        inputsWithLinks,
    ) { cards, tags, cardTags, todos, extras ->
        build(
            cards, tags, cardTags, todos,
            extras.query, extras.selectedTagId, extras.settings, extras.links, extras.attachments,
            extras.now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardScreenUiState())

    /** 拖动排序：把一张卡片移动到新的「显示序号」。 */
    fun reorder(cardId: String, targetIndex: Int) {
        viewModelScope.launch {
            val cards = repo.observeCards().first()
            val ordered = cards.sortedWith(
                compareByDescending<BoardCard> { it.pinned }.thenBy { it.sortIndex }.thenByDescending { it.updatedAt },
            ).toMutableList()
            val from = ordered.indexOfFirst { it.id == cardId }
            if (from < 0) return@launch
            val moved = ordered.removeAt(from)
            val to = targetIndex.coerceIn(0, ordered.size)
            ordered.add(to, moved)
            // 重写排序键：pinned 隔开（置顶卡保持在前），其余按新顺序编号。
            val pinnedFirst = ordered.filter { it.pinned }
            val rest = ordered.filterNot { it.pinned }
            (pinnedFirst + rest).forEachIndexed { index, card ->
                val pinnedPart = if (card.pinned) 0.0 else 10_000.0
                val target = pinnedPart + index
                if (card.sortIndex != target) {
                    repo.setCardSortIndex(card.id, target)
                }
            }
        }
    }

    /**
     * 拖动落位：按 [orderedIds] 给出的完整显示顺序一次性重写 sortIndex。
     *
     * 与旧的 reorder(cardId, targetIndex) 相比：
     *  - 直接接受最终顺序，不需要再根据位移反推目标位，落点与手指所见完全一致；
     *  - 一次批量写入，避免拖动中多次写库造成顺序抖动。
     * 置顶卡片仍保持在前（与列表排序规则一致）。
     */
    fun applyOrder(orderedIds: List<String>, movedCardId: String) {
        viewModelScope.launch {
            val cards = repo.observeCards().first()
            val byId = cards.associateBy { it.id }
            // 按拖动结果排序；置顶卡片整体前置，保持「置顶永远在前」的既有语义。
            val moved = orderedIds.mapNotNull { byId[it] }
            val missing = cards.filterNot { it.id in orderedIds }
            val full = (moved + missing)
            val pinnedFirst = full.filter { it.pinned }
            val rest = full.filterNot { it.pinned }
            // 先算出需要写的项，再**一次性**提交（单事务）。
            // 逐张写会让每次 upsert 各发一次 Flow → N 次重组 → 全墙抖动。
            val updates = (pinnedFirst + rest).mapIndexedNotNull { index, card ->
                val pinnedPart = if (card.pinned) 0.0 else 10_000.0
                val target = pinnedPart + index
                if (card.sortIndex != target) card.id to target else null
            }
            repo.setCardSortIndices(updates)
            // movedCardId 暂无额外用途，保留参数以便日后做「拖动后定位」提示。
            @Suppress("UNUSED_EXPRESSION")
            movedCardId
        }
    }

    /** 多选批量归档。 */
    fun archiveCards(ids: List<String>) {
        viewModelScope.launch { ids.forEach { repo.setCardArchived(it, true) } }
    }

    /** 多选批量删除。 */
    fun deleteCards(ids: List<String>) {
        viewModelScope.launch {
            ids.forEach { repo.deleteCard(it) }
            // 同上：软删后主动重算。批量删更要踢 —— 一次删多张时
            // 只靠流的发射次数不一定够。
            invalidate()
        }
    }

    /**
     * 多选批量移动标签：把选中卡片统一改为 [tagId]（null = 移出标签）。
     * 卡片是单标签模型，所以直接覆盖。
     */
    fun moveCardsToTag(ids: List<String>, tagId: String?) {
        viewModelScope.launch {
            ids.forEach { id -> repo.setCardTags(id, if (tagId == null) emptyList() else listOf(tagId)) }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun selectTag(id: String?) {
        selectedTagId.value = id
    }

    /** 新建标签：挂到 [parentId] 下（null = 顶层），排序键追加到同组末尾。 */
    fun createTag(name: String, parentId: String?) {
        viewModelScope.launch {
            val tags = repo.observeTags().first()
            val sortIndex = nextTagSortIndex(tags.filter { it.parentId == parentId }.map { it.sortIndex })
            repo.upsertTag(BoardTag(id = Ids.newId(), name = name, parentId = parentId, sortIndex = sortIndex))
        }
    }

    // ---------------------------------------------------------------- 卡片聚焦浮层

    private val focusedCardId = MutableStateFlow<String?>(null)

    /** 当前聚焦的卡片 id（null = 没在聚焦）。 */
    val focusId: StateFlow<String?> = focusedCardId.asStateFlow()

    /** 聚焦卡片本体。 */
    val focusedCard: StateFlow<BoardCard?> = combine(focusedCardId, repo.observeCards()) { id, cards ->
        id?.let { i -> cards.firstOrNull { it.id == i } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 聚焦卡片的待办清单。 */
    val focusTodos: StateFlow<List<BoardTodoItem>> =
        combine(focusedCardId, repo.observeTodoItems()) { id, all ->
            if (id == null) emptyList() else all.filter { it.cardId == id }.sortedBy { it.sortIndex }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 聚焦卡片的关联卡片（id + 标题）。 */
    val focusLinks: StateFlow<List<Pair<String, String>>> =
        combine(focusedCardId, repo.observeCardLinks(), repo.observeCards()) { id, links, cards ->
            if (id == null) {
                emptyList()
            } else {
                val byId = cards.associateBy { it.id }
                links.filter { it.cardId == id }.mapNotNull { link ->
                    val target = byId[link.linkedCardId] ?: return@mapNotNull null
                    target.id to (target.title?.takeIf { it.isNotBlank() } ?: plainPreviewOf(target.body, 12).ifBlank { "未命名卡片" })
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 聚焦卡片的附件。 */
    val focusAttachments: StateFlow<List<Attachment>> =
        combine(focusedCardId, repo.observeCards()) { id, cards ->
            if (id == null || cards.none { it.id == id }) emptyList() else attachmentRepository.getOf(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 聚焦卡片的提醒时刻（毫秒）。 */
    val focusReminderAt: StateFlow<Long?> =
        combine(focusedCardId, reminderRepository.observeEnabled()) { id, reminders ->
            if (id == null) null else reminders.firstOrNull { it.itemId == "card_alarm_$id" }?.triggerAt?.toEpochMilli()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun focus(cardId: String) {
        focusedCardId.value = cardId
    }

    fun unfocus() {
        focusedCardId.value = null
    }

    fun toggleTodoItem(id: String, done: Boolean) {
        viewModelScope.launch { repo.toggleTodoItem(id, done) }
    }

    /** 回写卡片正文（正文内复选框勾选后调用）。 */
    fun setCardBody(id: String, body: String) {
        viewModelScope.launch { repo.setCardBody(id, body) }
    }

    /** 全屏就地编辑用：一次写回标题与正文（标题空串按 null 存，与编辑页口径一致）。 */
    fun setCardTitleBody(id: String, title: String, body: String) {
        viewModelScope.launch { repo.setCardTitleBody(id, title.ifBlank { null }, body) }
    }

    /**
     * 复制一张卡片：内容 + 颜色 + 标签，新卡排到最前（sortIndex 最小 - 1）。
     *
     * 与卡片编辑页的「复制卡片」同一套口径（那边是 `duplicate()`）：
     * 只复制卡片自身的内容与标签，附件不复制 —— 附件是独立文件，
     * 复制一份会让磁盘占用翻倍，而用户要的是"继续编辑"，不是"备份二进制"。
     */
    fun duplicateCard(source: BoardCard) {
        val now = java.time.Instant.now()
        viewModelScope.launch {
            val copy = source.copy(
                id = com.phonlynn.oreplan.core.id.Ids.newId(),
                archived = false,
                pinned = false,
                autoPin = null,
                autoPinResolvedAt = null,
                createdAt = now,
                updatedAt = now,
                sortIndex = (repo.observeCards().first().minOfOrNull { it.sortIndex } ?: 0.0) - 1.0,
            )
            repo.replaceCardWithRelations(copy, emptyList(), emptyList())
        }
    }

    fun archiveFocusedCard() {
        val id = focusedCardId.value ?: return
        viewModelScope.launch {
            repo.setCardArchived(id, true)
            focusedCardId.value = null
        }
    }

    /**
     * 把聚焦卡片的**本次**动态浮起提前结束（「沉下」）。
     *
     * 与取消置顶的区别：这不改规则本身，只记下「这一次已经处理过了」，
     * 所以下一次到期它还会照常浮上来（周期型尤其重要）。
     */
    fun sinkFocusedCard() {
        val id = focusedCardId.value ?: return
        viewModelScope.launch {
            repo.setCardAutoPinResolvedAt(id, java.time.Instant.now())
        }
    }

    fun deleteFocusedCard() {
        val id = focusedCardId.value ?: return
        viewModelScope.launch {
            repo.deleteCard(id)
            focusedCardId.value = null
            // 软删改的是墓碑不是主表，主动踢一下界面 —— 见 refreshTick 的说明
            invalidate()
        }
    }

    // ---------------------------------------------------------------- 保密卡片识别

    private fun build(
        cards: List<BoardCard>,
        tags: List<BoardTag>,
        cardTags: List<BoardCardTag>,
        todos: List<BoardTodoItem>,
        q: String,
        selTag: String?,
        settings: com.phonlynn.oreplan.data.settings.AppSettings,
        cardLinks: List<com.phonlynn.oreplan.domain.model.BoardCardLink> = emptyList(),
        attachments: List<com.phonlynn.oreplan.domain.model.Attachment> = emptyList(),
        /** 当前时刻。动态置顶的激活判定与排序都用它。 */
        now: java.time.Instant = java.time.Instant.now(),
    ): BoardScreenUiState {
        // 每张卡片的**图片附件**（按 createdAt 已排好）：界面用它决定怎么画图片区。
        val imagesByCard = attachments
            .filter { it.isImage }
            .groupBy { it.ownerId }
        // 每张卡片的**全部**附件数（含非图片）：用于「n 附件」气泡。
        val attachmentCountByCard = attachments.groupingBy { it.ownerId }.eachCount()
        val tagById = tags.associateBy { it.id }
        val tagNamesByCard = cardTags
            .groupBy({ it.cardId }, { tagById[it.tagId]?.name.orEmpty() })
            .mapValues { (_, names) -> names.filter { it.isNotBlank() } }
        // 每张卡片的标签**链**（从高到低），供卡片底部「每个标签一个胶囊」渲染。
        // 卡片是单标签（取第一个），链由 tagPathChain 向上回溯得出。
        val tagChainByCard = cardTags
            .groupBy({ it.cardId }, { it.tagId })
            .mapValues { (_, ids) -> tagPathChain(tags, ids.firstOrNull()) }
        val todosByCard = todos.groupBy { it.cardId }
        // 标签可能已被删除：当前筛选指向不存在的标签时退回「全部」，避免空板假象。
        val activeTag = selTag?.takeIf { id -> tags.any { it.id == id } }
        val selSet = activeTag?.let { tagWithDescendants(it, tags) }

        // 「关联卡片标题」按卡片分组：链接单向存储，两侧都要能被搜到。
        val titleByCardId = cards.associate { it.id to (it.title?.takeIf { t -> t.isNotBlank() } ?: plainPreviewOf(it.body, 12)) }
        val linkedTitlesByCard = buildMap<String, MutableList<String>> {
            cardLinks.forEach { link ->
                titleByCardId[link.linkedCardId]?.takeIf { it.isNotBlank() }
                    ?.let { getOrPut(link.cardId) { mutableListOf() }.add(it) }
                titleByCardId[link.cardId]?.takeIf { it.isNotBlank() }
                    ?.let { getOrPut(link.linkedCardId) { mutableListOf() }.add(it) }
            }
        }

        /**
         * 卡片的全元数据索引文本：搜索时只需对这一整段做匹配，
         * 就不再需要为每个字段单独写一个 contains（新字段只要往这里补一项）。
         */
        fun searchIndexOf(card: BoardCard): String = buildString {
            append(card.title.orEmpty())
            append(' ')
            // 只把**纯文字**放进索引：正文落库是 JSON（含 k/t/s 等结构字段），
            // 直接拼进去会让「搜 text/bold/check」这类词误命中结构字段。
            append(plainTextOf(card.body))
            append(' ')
            append(tagNamesByCard[card.id].orEmpty().joinToString(" "))
            append(' ')
            append(card.type.label)
            append(' ')
            append(todosByCard[card.id].orEmpty().joinToString(" ") { it.text })
            append(' ')
            append(boardDateText(card.createdAt))
            append(' ')
            append(boardDateText(card.updatedAt))
            append(' ')
            // 日期也补一份「月-日」数字形式，方便用 9-16 这种写法搜到。
            val z = card.updatedAt.atZone(java.time.ZoneId.systemDefault())
            append("${z.monthValue}-${z.dayOfMonth} ")
            append("${z.monthValue}/${z.dayOfMonth} ")
            if (card.pinned) append("置顶 ")
            if (card.secret) append("保密 已隐藏 ")
            if (card.archived) append("归档 ")
            append(boardColorLabel(card.color))
            append(' ')
            append(linkedTitlesByCard[card.id].orEmpty().joinToString(" "))
        }

        val filtered = cards.filter { card ->
            val okTag = selSet == null || cardTags.any { it.cardId == card.id && it.tagId in selSet }
            if (!okTag) return@filter false
            // 「不在全部中显示」：当未筛选任何标签（看“全部”）时，
            // 跳过那些挂在开启了 hideFromAll 的标签（含其子标签）下的卡片。
            if (selSet == null) {
                val hiddenTagIds = tagWithHideFromAll(tags)
                if (hiddenTagIds.isNotEmpty()) {
                    val cardHidden = cardTags.any { it.cardId == card.id && it.tagId in hiddenTagIds }
                    if (cardHidden) return@filter false
                }
            }
            // 空格分词 + 关键词智能识别。
            // 识别为结构化条件的词（类型/状态/时间/颜色）直接判卡片字段，
            // 其余词在全元数据索引文本里做包含匹配。
            // 词之间的关系（交集/并集）由设置决定。
            BoardSearch.matches(q, card, ::searchIndexOf, settings.boardSearchMatchAll)
        }

        // 动态置顶的激活状态**先预算一次**再排序。
        // 每张卡的 isActive 都要做时区/窗口计算，而比较排序会调用 O(n log n) 次
        // 比较器；把它放在比较器里会被反复重算。
        val autoActive: Map<String, Boolean> = cards.associate { c ->
            c.id to AutoPinLogic.isActive(c, now)
        }

        val ordered = filtered.sortedWith(
            compareByDescending<BoardCard> { it.pinned }              // ① 手动置顶最前
                .thenByDescending { autoActive[it.id] == true }        // ② 动态浮起次之
                .thenBy { it.sortIndex }                              // ③ 组内保持用户顺序
                .thenByDescending { it.updatedAt },
        )

        return BoardScreenUiState(
            cards = ordered,
            tags = tags,
            cardTags = cardTags,
            todoByCard = todosByCard,
            query = q,
            selectedTagId = activeTag,
            totalCount = cards.size,
            lastUpdatedText = cards.maxOfOrNull { it.updatedAt }?.let { boardDateText(it) } ?: "—",
            loaded = true,
            compact = settings.boardCompact,
            cardFontSize = settings.boardCardFontSize,
            spacingKey = settings.boardSpacing,
            focusStyle = settings.boardFocusStyle,
            doubleTapEdit = settings.boardDoubleTapEdit,
            dragReorder = settings.boardDragReorder,
            newCardEntry = settings.boardNewCardEntry,
            defaultType = settings.boardDefaultType,
            doneToEnd = settings.boardDoneToEnd,
            cardTilt = settings.boardCardTilt,
            autoWidthChars = settings.boardAutoWidthChars,
            imagesByCard = imagesByCard,
            tagChainByCard = tagChainByCard,
            attachmentCountByCard = attachmentCountByCard,
            defaultImageLayout = settings.boardDefaultImageLayout,
        )
    }
}

/**
 * 白板主页（V2）—— 设计稿 JRggW。
 * 标题 → 搜索 → 标签筛选栏 → 瀑布流卡片墙；右下 FAB 新建卡片。
 */
/**
 * 拖动时「被拖卡片应该插到哪个位置」——**限定在它所在的置顶/非置顶组内**。
 *
 * 置顶卡片与非置顶卡片是两个互不跨越的组（与列表排序规则一致）：
 * 置顶卡拖不出置顶区，非置顶卡也挤不进置顶区。所以候选插入位置只能落在本组内。
 *
 * 做法：把本组的卡片按它们原有的**全局槽位**排成一条序列，
 * 让 [MasonryDrop] 在这条序列里枚举候选下标（落点模拟也只用本组成员，
 * 因为布局本就按全局顺序把两组分开排，模拟结果一致）。
 */
private fun dragTargetIndexInGroup(
    globalOrder: List<BoardCard>,
    groupOrder: List<BoardCard>,
    draggedId: String,
    pointerInRoot: androidx.compose.ui.geometry.Offset,
    slots: SlotTable,
    originInRoot: androidx.compose.ui.geometry.Offset,
    columnWidth: Float,
    gap: Float,
    rowGap: Float,
    isFullWidth: (BoardCard) -> Boolean,
    currentLocalTarget: Int,
    hysteresis: Float,
): Int {
    val draggedSlot = slots.get(draggedId)
    return com.phonlynn.oreplan.core.order.MasonryDrop.targetIndex(
        orderIds = groupOrder.map { it.id },
        draggedId = draggedId,
        draggedW = draggedSlot?.w ?: columnWidth,
        draggedH = draggedSlot?.h ?: 0f,
        isFullWidthOf = { id ->
            val c = globalOrder.firstOrNull { it.id == id }
            c != null && isFullWidth(c)
        },
        pointerX = pointerInRoot.x - originInRoot.x,
        pointerY = pointerInRoot.y - originInRoot.y,
        columnWidth = columnWidth,
        gap = gap,
        rowGap = rowGap,
        slotOf = { id -> slots.get(id) },
        currentTarget = currentLocalTarget,
        hysteresis = hysteresis,
    )
}

/** 筛选 epoch 的全局自增源：每次筛选条件变化时递增，用于重放卡片错峰淡入。 */
/**
 * 把白板已有的卡片数据推给编辑页，让编辑页第一帧就有完整内容，
 * 不需要等 Room 查询（与新建卡同步就绪一致）。
 * 关联数据（标签/附件/关联/提醒）不带，由编辑页后台补齐。
 */
private fun pushCardPrefill(card: BoardCard) {
    BoardCardEntryBus.push(
        CardPrefill(
            cardId = card.id,
            typeKey = card.type.key,
            title = card.title.orEmpty(),
            body = card.body.orEmpty(),
            color = card.color,
            pinned = card.pinned,
            secret = card.secret,
            secretHint = card.secretHint.orEmpty(),
            widthMode = card.widthMode,
            imageLayout = card.imageLayout,
            showDate = card.showDate,
            autoPin = card.autoPin,
        ),
    )
}

// ---------------------------------------------------------------- 卡片墙几何
// 间距预设集中在这里：MasonryBoard 的布局、拖放落点模拟、列表左右内边距**必须共用**，
// 否则模拟出的落点会与实际布局不符（「看着能钻的缝钻不进去」），
// 或列宽与实际留白不一致。
//
// 三种间距一起变的意义：空间利用率主要由「左右边缘留白 + 列间距 + 行间距」共同决定，
// 只缩小其中一个效果不明显，还会让比例失衡。

data class BoardSpacing(
    /** 卡片与屏幕左右边缘的间隙（列表 contentPadding）。 */
    val edge: Dp,
    /** 两列之间的间距。 */
    val column: Dp,
    /** 行与行之间的间距。 */
    val row: Dp,
)

/** 默认间距（与历史行为一致）。 */
val BOARD_SPACING_NORMAL = BoardSpacing(edge = 20.dp, column = 10.dp, row = 16.dp)

/** 紧凑间距：缩小边缘留白与行/列间距，提升空间利用率。 */
val BOARD_SPACING_COMPACT = BoardSpacing(edge = 12.dp, column = 8.dp, row = 10.dp)

/** 按设置键取间距预设；未知值回退默认。 */
/**
 * 卡片间距：**固定紧凑**。
 *
 * 用户 2026-09-25：设置里的「卡片间距」选项已删，紧凑成为唯一默认；
 * 原来的「默认」预设保留为常量，需要时可直接换回来。
 */
@Suppress("UNUSED_PARAMETER")
fun boardSpacingOf(key: String?): BoardSpacing = BOARD_SPACING_COMPACT

// ---------------------------------------------------------------- 卡片换场节奏
/** 入场错峰步长：每张卡片比前一张晚这么久开始淡入。 */
private const val CARD_ENTER_STEP_MS = 40L
/** 入场错峰上限：无论多少张卡，最多只排到这里。 */
private const val CARD_ENTER_MAX_DELAY_MS = 480L
/** 单张卡退场时长：略短于入场，更利落。 */
private const val CARD_EXIT_DURATION_MS = 160

/**
 * 双击检测（**手写**，刻意不用 `detectTapGestures`）。
 *
 * 为什么：`detectTapGestures` 在等 up 时会把「被子节点消费掉的 up」当成手势取消 ——
 * 而全屏展示页里点空白会被背景 scrim 消费、点正文会被正文控件消费，
 * 于是双击**永远成立不了**（用户 2026-09-27 实测：双击没有任何反馈）。
 * 这里自己等 up、并且**不看消费状态**；down 用 `requireUnconsumed = false` 收，
 * 不跟子节点抢手势。两次 up 间隔 ≤320ms、位移 <72px 才算双击。
 */
@Composable
private fun Modifier.boardDoubleTap(onDoubleTap: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier {
    val cb by rememberUpdatedState(onDoubleTap)
    // 记下本层的窗口原点：指针事件给的是节点内坐标，换算成窗口坐标才能与正文块范围比较。
    var originInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    return this
        .onGloballyPositioned { originInRoot = it.positionInRoot() }
        .pointerInput(Unit) {
        var lastUpTime = 0L
        var lastUpPos = androidx.compose.ui.geometry.Offset.Zero
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        up = ch
                        break
                    }
                }
                val u = up ?: continue
                val gap = u.uptimeMillis - lastUpTime
                val near = lastUpTime != 0L && (u.position - lastUpPos).getDistance() < 72f
                if (gap in 1..320 && near) {
                    lastUpTime = 0L
                    cb(originInRoot + u.position)
                } else {
                    lastUpTime = u.uptimeMillis
                    lastUpPos = u.position
                }
            }
        }
    }
}

@Composable
fun BoardScreenV2(
    navigate: (String) -> Unit,
    onSelectTab: (VTab) -> Unit,
    onAi: () -> Unit,
    viewModel: BoardScreenV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 新建入口直接订阅设置流（绕开 uiState 缓存，修「改回悬浮不生效」）。
    val newCardEntry by viewModel.newCardEntry.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 筛选标识：标签或搜索词变化时，epoch 递增，用于重放卡片换场动画。
    val filterKey = (state.selectedTagId ?: "") + "\u0000" + state.query

    // ---------------------------------------------------------------- 多选模式
    // 双指捏合进入：卡片等比例缩小，左侧腾出的空白放复选框；
    // 缩小时点击卡片仍然进入聚焦，勾选靠复选框。
    var selectMode by remember { mutableStateOf(false) }
    val selectedCardIds = remember { mutableStateMapOf<String, Boolean>() }
    // 捏合进度：1 = 正常大小，0.94 = 多选下略缩的小尺寸。
    // 用户 2026-09-19：之前 0.88 缩得太多、内容难读；
    // 缩小只是为了在左侧腾出放复选框的空间，留一点点就够了。
    // 同时用 selectT 驱动复选框槽位宽度（见卡片项），因此不得改成硬切。
    // 捏合进度：0 = 正常，1 = 多选态。
    // 一个进度同时驱动：① 卡片等比缩小；② 复选框槽位由窄变宽；③ 复选框淡入。
    // 卡片缩得不多（0.94）：只是为了在左侧腾出放复选框的空间。
    val selectAnim = remember { Animatable(0f) }
    LaunchedEffect(selectMode) {
        selectAnim.animateTo(
            targetValue = if (selectMode) 1f else 0f,
            animationSpec = VMotion.settle(),
        )
    }
    // 进入多选时清空上一次的选择，避免残留。
    LaunchedEffect(selectMode) {
        if (selectMode) selectedCardIds.clear()
    }
    // 多选模式下系统返回键先退出多选，而不是直接离开白板。
    androidx.activity.compose.BackHandler(enabled = selectMode) { selectMode = false }

    // 拖动状态。指针位置走**非组合读取**路径（只在动画循环里读），
    // 所以指针高频移动不会触发任何重组 —— 这是切断反馈环的关键。
    val drag = remember { BoardDragState() }
    // 卡片墙的静态槽位表。自己创建并传给 MasonryBoard，因为**插入位置判定**要用它：
    // 必须用静态槽位而非视觉位置（让位位移每帧在变，会导致判定边界抖动）。
    val boardSlots = remember { SlotTable() }
    // 拖到上下边缘时自动滚动：指针当前的 root Y（只在滚动循环里读，不进组合期）、
    // 以及卡片墙可视区在 root 里的上下边界（由 LazyColumn 的 onGloballyPositioned 写入）。
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    var boardTopY by remember { mutableFloatStateOf(0f) }
    var boardBottomY by remember { mutableFloatStateOf(0f) }
    // 手指相对卡片左上角的抓取点：拖动时保持这个相对关系，卡片才不会跳。
    var grabDx by remember { mutableStateOf(0f) }
    var grabDy by remember { mutableStateOf(0f) }

    // ---------------------------------------------------------------- 卡片出现 / 消失
    //
    // **单一机制**（2026-09-19 重做）。此前把这套逻辑拆成了三处状态互相牵制：
    //   · `playedCards`：记录「已经播过入场的卡」，但它按 filterEpoch 重建 →
    //     筛选中**保留**的卡被当成新卡重播入场；改成全局存活后，
    //     「从筛选回到全部」的卡又因为早就在集合里，**永远不再播入场**。
    //   · `exitingCards`：另一份列表，更新时机与上面不同，晚一帧 →
    //     卡片先真的消失再回来，退场动画看不见。
    //   · `fadePhase` / `filterEpoch`：一套全局相位，让**所有**卡一起淡出淡入 →
    //     保留的卡也跟着闪。
    // 三处各自为政，任何一处改动都会把问题挪到别处 —— 这就是反复出错的根源。
    //
    // 现在的模型只有**一个**概念：每张卡有自己的「在场程度」 presence ∈ [0,1]。
    //   · 目标值由「它是否在 state.cards 里」决定：在 → 1，不在 → 0；
    //   · **渲染列表 = state.cards ∪ (presence > 0 的、已离场的卡)**；
    //   · 入场（0→1）带错峰与轻微弹性；退场（1→0）淡出后自动从列表移除。
    //
    // 正确性来自「只有一个真值来源」：
    //   · 保留的卡：id 一直在 state.cards 里 → 目标恒为 1，动画 key 不变 → 不重播；
    //   · 离开的卡：目标变 0 → 播退场 → 播完自动移出渲染列表；
    //   · 回来的卡：目标变 1 → **重新播入场**（因为它已经离场、presence 归 0）。
    // 不再需要 filterEpoch / playedCards / fadePhase / exitingCards。
    var renderedCards by remember { mutableStateOf(state.cards) }
    // 正在播退场的卡片（id → 卡片数据）。入场不需要它：
    // 卡片本就在 state.cards 里；只有「已离开数据、但还要淡出一下」的卡才进来。
    // 每张卡自己的动画播完后会把它从这里移除。
    val leavingCards = remember { mutableStateMapOf<String, BoardCard>() }
    // 已播完退场的卡片 id（追加即变，用来触发清理 effect）。
    var leavingDone by remember { mutableStateOf<List<String>>(emptyList()) }
    // 每张卡的「在场程度」0..1，由父层持有。
    // 必须放在**父层**而不是卡片自己 remember 里：卡片退场后会从渲染列表移除、
    // 它的 remember 随之销毁；下次回来时若重新从 1 开始，入场动画就丢了。
    // 用 map 记住「上次归 0 过」，回来时才能正确地重新播入场。
    val presence = remember { mutableStateMapOf<String, Float>() }

    val cardsSignature = state.cards
    // 空状态入场的重放计数：从「空标签」切到另一个「空标签」时卡片集合不变（都为空），
    // 文案会变，需要重放一次入场避免硬切。
    var emptyEpoch by remember { mutableIntStateOf(0) }
    var lastShownFilter by remember { mutableStateOf(filterKey) }

    // 数据变化 → 更新渲染列表与离场集合。
    //
    // **顺序的所有权规则**（这是拖动/筛选能否共存的关键）：
    //   · 筛选变化 → 数据层说了算，渲染列表换成 state.cards；
    //   · 拖动中或刚拖完（写库还没落地）→ **用户拖出来的顺序说了算**，
    //     绝不能用 state.cards 覆盖 renderedCards。
    // 之前这版 effect 无条件用 state.cards 重建列表，于是松手后异步写库落地
    // 会触发它重跑，把用户刚拖好的顺序打回旧顺序 —— 表现为「挤压与落位又不对」。
    LaunchedEffect(cardsSignature, filterKey) {
        // 拖动进行中：完全不动。
        if (drag.draggedId != null) return@LaunchedEffect

        val nextIds = state.cards.map { it.id }.toSet()
        val prevIds = renderedCards.map { it.id }.toSet()
        val filterChanged = filterKey != lastShownFilter
        lastShownFilter = filterKey

        // 本轮**新离场**的卡（在上一版渲染列表里、但现在不在数据里）：
        // 放进 leavingCards，由它们自己的动画把它淡出，播完自动移除。
        renderedCards.filterNot { it.id in nextIds }
            .filterNot { it.id in leavingCards }
            .forEach { c ->
                leavingCards[c.id] = c
            }

        // 反向：已经进入离场集合、但这轮又回到数据里的卡（用户在淡出播完前
        // 撤销了筛选 / 点回了原标签），必须立刻从离场集合移出。
        //
        // 不这样做会有一个不利后果：离场集合是「冻结位置」的判据（MasonryBoard
        // 的 isLeaving），残留条目会把已回来的卡**永久钉在它当初离场的位置**上，
        // 不再跟随布局。淡出动画那边本身会自动改道回 1（LaunchedEffect 重启），
        // 所以这里只需要把集合清掉。
        leavingCards.keys.removeAll { it in nextIds }

        // 集合与顺序都与数据层一致，**且内容也一致**、且没有要离场的卡 → 无事可做。
        // （这不只是优化：它是拖动与筛选的“停战”条件。）
        //
        // 2026-09-19 修：原先只比较 id 集合，于是「只改了内容」（例如主页勾选复选框改正文、
        // 编辑卡片后返回）会直接 return，renderedCards 里的旧内容一直不刷新 ——
        // 必须重新进页/筛选才看到变化。这里把「内容签名」也纳入停战条件。
        val sameSet = nextIds == prevIds
        val sameContent = state.cards == renderedCards.filter { it.id in nextIds }
        if (!filterChanged && sameSet && sameContent && leavingCards.isEmpty()) return@LaunchedEffect

        // **只有筛选变化**才用数据层顺序替换渲染列表。
        // 非筛选的数据变化（编辑卡片内容、拖动写库落地、增删等）**保留现有顺序**，
        // 只做两件事：把离场的卡纳入离场集合、把仍在场的数据同步进列表。
        val merged = if (filterChanged) {
            ArrayList(state.cards)
        } else {
            // 保留渲染列表现有顺序；把数据层新增的卡插入到**数据层顺序对应的位置**；
            // 内容字段（标题/颜色等）用数据层的最新值刷新。
            //
            // 为什么不能简单地 `kept + added`（2026-09-19 修）：
            // 这样会把新卡无条件摆到列表**末尾**。而新建卡片在数据层是按
            // `sortIndex`（设置决定置顶/置底）排序的，于是「新建置顶」的卡
            // 会先出现在底部，要重新进页/筛选（走 filterChanged 分支）才归位。
            // 正解：按数据层顺序把新增卡插到相对位置 —— 用新增卡在 state.cards 里
            // 的下标作为插入点，保证置顶的新卡也能立即出现在顶部。
            val byId = state.cards.associateBy { it.id }
            val kept = renderedCards.mapNotNull { byId[it.id] }
            val addedIds = state.cards.map { it.id }.filterNot { it in prevIds }
            if (addedIds.isEmpty()) {
                ArrayList(kept)
            } else {
                // 数据层下标：用于决定新卡插在保留卡的哪一侧。
                val dataIndex = state.cards.withIndex().associate { (i, c) -> c.id to i }
                val result = ArrayList(kept)
                addedIds.forEach { id ->
                    val card = byId[id] ?: return@forEach
                    if (result.any { it.id == id }) return@forEach
                    val myIndex = dataIndex[id] ?: Int.MAX_VALUE
                    // 插入点 = 第一个「在数据层排在新卡之后」的保留卡的位置；
                    // 找不到就放末尾。这样置顶的新卡（数据层下标小）会落到最前。
                    val insertAt = result.indexOfFirst { dataIndex[it.id] ?: Int.MAX_VALUE > myIndex }
                        .let { if (it < 0) result.size else it }
                    result.add(insertAt, card)
                }
                result
            }
        }
        // 正在离场的卡（按原下标插回原位）——让它们是「留在原地淡出」。
        val order = renderedCards.map { it.id }
        leavingCards.values
            .filterNot { it.id in nextIds }
            .sortedByDescending { order.indexOf(it.id) }
            .forEach { c ->
                val at = order.indexOf(c.id)
                if (at in 0..merged.size) merged.add(at, c) else merged.add(c)
            }
        renderedCards = merged.distinctBy { it.id }

        // 「空标签 A」→「空标签 B」：集合都为空但文案变了，重放空状态入场。
        if (nextIds.isEmpty() && prevIds.isEmpty() && filterChanged) emptyEpoch++
    }

    // 已淡出的卡片：从离场集合与渲染列表里移除。
    // 单独一个 effect，由 leavingDone 变化触发；不放在卡片自身里，
    // 避免多张卡同时改共享状态互相覆盖。
    LaunchedEffect(leavingDone) {
        if (leavingDone.isEmpty()) return@LaunchedEffect
        val done = leavingDone.toSet()
        leavingDone = emptyList()
        leavingCards.keys.removeAll(done)
        renderedCards = renderedCards.filterNot { it.id in done }
    }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var treePanelOpen by remember { mutableStateOf(false) }
    // TagMenuPresenter 的「本次打开」签到：每次由关变开时变化，交给它的 resetKey。
    //
    // 为什么必须有：TagMenuPresenter 内部靠 key(resetKey) 重建位置动画状态，
    // 让 animateFloatAsState 以当前锚点为初值（等价于 snapTo，直接落位）。
    // resetKey 不变时 key 不重建，动画会从上一个位置平滑滑过去——
    // 典型表现：「全部」→ 子标签（菜单位于右侧）→ 再点开时菜单从右侧飞入。
    //
    // 用 remember(open) 派生而非在组合期写状态：后者会把当前组合标记为「需再组一次」，
    // 且与 key 重建、内部 frozen 重置存在竞态。remember 以 open 为键，
    // 打开时自然得到与前一次不同的实例（每次打开 ++），无副作用、无竞态。
    // 用全局计数器保证「打开→关闭→再打开」也递增（仅靠 remember(open) 会回到同一值）。
    var filterMenuEpoch by remember { mutableIntStateOf(0) }
    var filterMenuShown by remember { mutableStateOf(false) }
    if (filterMenuOpen != filterMenuShown) {
        filterMenuShown = filterMenuOpen
        if (filterMenuOpen) filterMenuEpoch++
    }
    var treePanelEpoch by remember { mutableIntStateOf(0) }
    var treePanelShown by remember { mutableStateOf(false) }
    if (treePanelOpen != treePanelShown) {
        treePanelShown = treePanelOpen
        if (treePanelOpen) treePanelEpoch++
    }
    var createDialogOpen by remember { mutableStateOf(false) }
    var createParentId by remember { mutableStateOf<String?>(null) }
    var newTagName by remember { mutableStateOf("") }
    var tagBarBottomPx by remember { mutableFloatStateOf(0f) }
    // 列表视口顶边在 root 坐标系里的 y。与 tagBarBottomPx 相减得到「列表内已用高度」，
    // 空状态靠它精确居中（两种头部形态 + 标签栏换行都能自适应）。
    var listTopPx by remember { mutableFloatStateOf(0f) }
    val pillLeftPx = remember { mutableStateMapOf<String, Float>() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val context = LocalContext.current
    // —— 三点菜单的动作（用户 2026-09-28：复制 / 导出 / 分享 / 更多）——
    // 导出与分享都要「知道这张卡的内容 + 全部附件」，所以在这一层组装。
    val menuScope = rememberCoroutineScope()
    // 分享选择（图片 / 文字）：记下当时的卡片与附件 ——
    // 弹窗打开期间浮层可能已经被收起，那时 focusAttachments 就空了。
    var shareTarget by remember {
        mutableStateOf<Pair<BoardCard, List<com.phonlynn.oreplan.domain.model.Attachment>>?>(null)
    }
    // 分享方式选择（图片 / 文字）——用户 2026-09-28 要求两种都要能选。
    shareTarget?.let { (card, atts) ->
        val sizes = cardFontSizes(state.cardFontSize, state.compact)
        com.phonlynn.oreplan.v2.components.ShareChoiceDialog(
            onDismiss = { shareTarget = null },
            onShareImage = {
                shareTarget = null
                com.phonlynn.oreplan.v2.components.shareCardImage(
                    context = context, scope = menuScope,
                    storage = viewModel.attachmentStorage,
                    title = card.title, body = card.body, attachments = atts,
                    background = boardCardColor(card.color),
                    ink = VColors.ink, ink3 = VColors.ink3,
                    titleSp = sizes.title.value, bodySp = sizes.body.value,
                )
            },
            onShareText = {
                shareTarget = null
                com.phonlynn.oreplan.platform.export.CardLongImage.shareText(context, card.title, card.body)
            },
        )
    }
    // 拖动插入判定的滞回宽度：只有新候选明显更优才换位置，
    // 否则指针停在边界附近时插入位置会反复翻转（卡片抽摘）。
    val dragHysteresis = with(density) { 14.dp.toPx() }
    // 卡片墙实测宽度（由 MasonryBoard 容器的 onGloballyPositioned 写入），
    // 用于拖放落点模拟里的列宽/宽卡判定。
    var boardWidthPx by remember { mutableFloatStateOf(0f) }
    // 间距预设（normal / compact）：布局、落点模拟、左右内边距都用它 —— 只有一处真值。
    val spacing = boardSpacingOf(state.spacingKey)
    val boardGapPx = with(density) { spacing.column.toPx() }
    val boardRowGapPx = with(density) { spacing.row.toPx() }
    val focusId by viewModel.focusId.collectAsStateWithLifecycle()
    // 背景模糊进度：0 = 不模糊，1 = 完全模糊。
    //
    // 之前是「focusId 非空就拉上 2dp 模糊」的二元开关，模糊在一帧里切到位，
    // 而蒙层/卡片都是渐变的，看起来就是背景「卡」地一声糊上/散开。
    // 现在改为连续动画值，并与浮层动画对齐：
    //   进入：focusId 非空 → 与浮层入场的 springy 同步拉满；
    //   退出：浮层开始收起（focusDismissing）→ 与它的 130ms 退场同步淡出。
    // 注意：这里只驱动 alpha（见下方双层交叉淡化），不逐步改模糊半径。
    val blurProgress = remember { Animatable(0f) }
    // 浮层是否已开始收起：由 BoardFocusOverlay 的 onDismissStart 置位。
    var focusDismissing by remember { mutableStateOf(false) }
    // 「不是收起浮层，而是整个离开白板页（如进卡片编辑）」的标志。
    //
    // 区别很重要：收起浮层要播 130ms 退场；但跳去另一页时不能再播——
    // 新页面已经进场，旧页的模糊/浮层还在后台多跑 130ms，就是「保存按钮慢半拍」
    // 的卡顿感来源（从主页新建不经过聚焦，所以没这个问题）。
    // 置位后模糊直接 snap 到 0，不留尾巴。
    var focusNavigatingAway by remember { mutableStateOf(false) }
    LaunchedEffect(focusId, focusDismissing, focusNavigatingAway) {
        if (focusNavigatingAway) {
            // 立即清掉模糊，不播过渡（页面已经要切走了）。
            blurProgress.snapTo(0f)
        } else if (focusId != null && !focusDismissing) {
            blurProgress.animateTo(1f, animationSpec = VMotion.springy())
        } else if (focusId != null || focusDismissing) {
            blurProgress.animateTo(0f, animationSpec = tween(durationMillis = 130, easing = VMotion.Accelerate))
        } else {
            blurProgress.snapTo(0f)
        }
    }
    // 复位跳转标志：进入新的聚焦（focusId 非空）时重置，
    // 让下一次聚焦能正常播模糊淡入。若在 focusId 变空时重置，
    // 会被 onEdit 里紧跟的 unfocus() 立刻抹掉，标志就失效了。
    LaunchedEffect(focusId) { if (focusId != null) focusNavigatingAway = false }
    LaunchedEffect(focusId) { if (focusId == null) focusDismissing = false }
    val focusedCard by viewModel.focusedCard.collectAsStateWithLifecycle()
    val focusTodos by viewModel.focusTodos.collectAsStateWithLifecycle()
    val focusAttachments by viewModel.focusAttachments.collectAsStateWithLifecycle()
    val focusLinks by viewModel.focusLinks.collectAsStateWithLifecycle()
    val focusReminderAt by viewModel.focusReminderAt.collectAsStateWithLifecycle()
    val tileBounds = remember { mutableStateMapOf<String, Rect>() }
    // MasonryBoard 容器左上角在 root 坐标系里的位置（推挤计算要把 root 坐标换算成容器内坐标）。
    var masonryOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    // 边缘自动滚动的速度上限（px/s）与触发区高度。
    val edgeScrollMaxSpeed = with(density) { 1400.dp.toPx() }
    val edgeScrollZone = with(density) { 96.dp.toPx() }

    /**
     * 重算被拖卡片的落点并即时重排。
     *
     * 做成局部函数是因为有**两个触发点**：
     *  1. 指针移动（onDrag）；
     *  2. 边缘自动滚动——此时指针在屏幕上不动，但内容在滚，落点仍会变，
     *     所以滚动循环每帧也要调它一次。
     */
    fun resolveDropTarget(order: List<BoardCard>) {
        val draggedId = drag.draggedId ?: return
        val from = order.indexOfFirst { it.id == draggedId }
        if (from < 0) return
        // 置顶卡片与非置顶卡片是**两个互不跨越的组**（与列表排序规则一致：
        // pinned 优先，其后按 sortIndex）。拖动只能在自家组内重排：
        // 置顶卡拖不出置顶区，非置顶卡也挤不进置顶区。
        // 做法：先算出本组在原列表里的下标区间 [groupStart, groupEnd]，
        // 再把这个区间以外的下标从候选里剔除（见下面合并回全局顺序的写法）。
        val draggedPinned = order[from].pinned
        // 本组的全局下标集合（保持原有相对顺序）。
        val groupIndices = order.indices.filter { order[it].pinned == draggedPinned }
        val groupStart = groupIndices.first()
        val groupEnd = groupIndices.last()

        // 先拿本组内部的顺序做落点判定，再把结果映射回全局下标。
        val groupOrder = groupIndices.map { order[it] }
        val localFrom = groupOrder.indexOfFirst { it.id == draggedId }
        if (localFrom < 0) return

        // 槽位表是全局坐标，判定直接用全局列表即可；这里只需把“目标本组内位置”
        // 限制在 [0, groupOrder.size]，并把结果只作用到本组。
        val localTarget = dragTargetIndexInGroup(
            globalOrder = order,
            groupOrder = groupOrder,
            draggedId = draggedId,
            pointerInRoot = Offset(drag.pointerRootX, drag.pointerRootY),
            slots = boardSlots,
            originInRoot = masonryOriginInRoot,
            columnWidth = ((boardWidthPx - boardGapPx) / 2f).coerceAtLeast(1f),
            gap = boardGapPx,
            rowGap = boardRowGapPx,
            isFullWidth = { cardIsFullWidth(it, state.autoWidthChars, state.imagesByCard[it.id].orEmpty().isNotEmpty()) },
            currentLocalTarget = drag.insertIndex,
            hysteresis = dragHysteresis,
        ).coerceIn(0, groupOrder.size)
        drag.insertIndex = localTarget
        val localInsertAt = if (localTarget > localFrom) localTarget - 1 else localTarget
        if (localInsertAt == localFrom) return
        // 重排本组，再写回全局顺序（本组占用的全局下标位置不变）。
        val nextGroup = groupOrder.toMutableList()
        val moving = nextGroup.removeAt(localFrom)
        nextGroup.add(localInsertAt.coerceIn(0, nextGroup.size), moving)
        val next = order.toMutableList()
        groupIndices.forEachIndexed { i, globalIndex -> next[globalIndex] = nextGroup[i] }
        renderedCards = next
        // groupStart/groupEnd 已在上面用于划定范围（保留变量便于阅读意图）。
        @Suppress("UNUSED_EXPRESSION")
        (groupStart to groupEnd)
    }

    // ---------------------------------------------------------------- 边缘自动滚动
    // 拖到（或靠近）卡片墙可视区的上/下边缘时自动滚动，以便把卡片拖到屏幕外的位置。
    //
    // 用 withFrameNanos 自己算位移而不是 animateScrollBy：
    //  - 指针位置是**连续变化**的，滚动速度需要每帧重算（越靠边越快）；
    //  - 且必须能随时停下（指针离开边缘/松手），用循环 + 每帧判定最直接。
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            // withFrameNanos 的 lambda 不是 suspend，所以只在这里取时间与判定，
            // 需要挂起的 scrollBy 放到 lambda 外面调用。
            val speed = withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                if (drag.draggedId == null || dt <= 0f) return@withFrameNanos 0f
                val top = boardTopY
                val bottom = boardBottomY
                val usable = edgeScrollZone.coerceAtMost((bottom - top) / 2f)
                if (usable <= 0f) return@withFrameNanos 0f
                // 指针离边的「深入程度」→ 0..1，再映射到速度。
                val up = ((top + usable) - dragPointerY) / usable
                val down = (dragPointerY - (bottom - usable)) / usable
                val pxPerSecond = when {
                    up > 0f -> -edgeScrollMaxSpeed * up.coerceIn(0f, 1f)
                    down > 0f -> edgeScrollMaxSpeed * down.coerceIn(0f, 1f)
                    else -> 0f
                }
                pxPerSecond * dt
            }
            if (speed != 0f) {
                // scrollBy 会自行夹在可滚动范围内，到底/到顶时自动停。
                listState.scrollBy(speed)
                // 内容滚了 → 卡片槽位变了 → 落点可能变。
                // 指针在屏幕上没动，所以必须在这里主动重算一次，
                // 否则拖到边缘只滚不换位（无法把卡片拖到屏幕外）。
                resolveDropTarget(renderedCards)
            }
        }
    }

    var focusStartBounds by remember { mutableStateOf<Rect?>(null) }
    // 全屏图片查看：待看图片路径 + 起始下标（null = 关闭）。
    var fsImages by remember { mutableStateOf<List<String>?>(null) }
    var fsStart by remember { mutableStateOf(0) }

    // ---- 全屏展示页 →（双击）就地全屏编辑 ----
    // 唯一真值源是 fsEditActive；进场/退场都由它驱动同一个 progress，
    // 展示页与编辑层分别读 (1-progress) / progress，形成真正的交叉淡化。
    var fsEditActive by remember { mutableStateOf(false) }
    // 「更多设置」退场后要恢复哪一层（用户 2026-09-30 规则：保存→展示页、退出→编辑页）。
    //
    // 为什么必须在这里接：展示页/编辑页都是**本页内的浮层**、不是导航目的地，
    // 所以那一层（独立路由）退场、本页重新回到前台时，只有本页能决定"露出谁"。
    // 卡片 id 由**发起导航的那一处**写进信箱（见 BoardReturnBus 的说明）。
    // 一次性消费，读完立刻清空，避免下次返回误触发。
    LaunchedEffect(Unit) {
        val req = BoardReturnBus.consume() ?: return@LaunchedEffect
        // 先聚焦这张卡：展示页与编辑页都以"有聚焦卡片"为前提。
        viewModel.focus(req.cardId)
        fsEditActive = req.target == BoardReturnTarget.EDITOR
    }

    var fsEditState by remember { mutableStateOf<RichEditState?>(null) }
    // 就地编辑的**草稿**：编辑期间不写库 —— ✓ 才提交、X 直接丢弃。
    //（用户 2026-09-28：X 的意义是放弃更改，之前实时写库让它毫无意义。）
    var fsEditTitle by remember { mutableStateOf("") }
    var fsEditBody by remember { mutableStateOf("") }
    // 展示页正文文字块在窗口里的范围。双击时用它把点击点换算成「正文内相对位置」。
    var focusBodyBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 本次进编辑要落位的光标点（正文文字坐标；null = 默认落在文末）。
    var fsEditTapAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // 进入就地全屏编辑。两个展示分支（卡片形态在脚手架内、全屏形态在根层）作用域不同，
    // 所以定义放这里、由调用方把卡片传进来。
    //
    // ⚠️ 这里**故意没有 `if (!fsEditActive)` 门闩**（2026-09-30 修 #35）。
    //
    // 原写法是 `if (!fsEditActive) { 建草稿; fsEditActive = true }`，
    // 本意是"已经在编辑态就别重复初始化草稿"。但只要出现
    // **fsEditActive=true 而 fsEditState=null** 的中间态，它就会卡死：
    //   · 编辑层可见条件是 `fsEditMounted && fsEditState != null && focusedOnce != null`，
    //     state 为 null ⇒ 编辑层画不出来（用户看到的是展示页）；
    //   · 同时 `!fsEditActive` 恒为假 ⇒ **双击再也进不去编辑**。
    // 实测（设备日志）：详情→编辑→⋯更多→退出 之后正是
    //   `fsEditActive=true  fsEditState=false  focusedOnce=true`
    // —— 这就是用户报的「之后无法再次通过双击进入编辑」。
    //
    // 现在改为**每次双击都重建草稿并置为编辑态**：双击是明确的"我要进编辑"意图，
    // 重复触发最坏也只是把草稿重置为当前卡片内容。展示页正文在编辑层之下、
    // 编辑层在场时点不到，所以不存在"编辑到一半被双击重置"的实际路径。
    val enterFullscreenTextEdit: (BoardCard, androidx.compose.ui.geometry.Offset?) -> Unit =
        remember { { card, tapAt ->
            val body = card.body.orEmpty()
            fsEditTitle = card.title.orEmpty()
            fsEditBody = body
            fsEditState = RichEditState.of(body)
            // 「双击哪里，光标就放哪里」：把窗口坐标的点击点换算成
            // **正文文字块内**的相对位置（展示页与编辑页的正文起点不同，
            // 只有相对正文的位置才是两页通用的）。取不到就 null → 默认落文末。
            val b = focusBodyBounds
            fsEditTapAt = if (tapAt != null && b != null) {
                androidx.compose.ui.geometry.Offset(tapAt.x - b.left, tapAt.y - b.top)
            } else {
                null
            }
            fsEditActive = true
        } }

    /**
     * 组装一张卡片的菜单动作（展示页与就地编辑页共用同一份实现）。
     *
     * 必须放在 [enterFullscreenTextEdit] 之后：它引用 `focusNavigatingAway` /
     * `pushCardPrefill`，这两个局部量在上面才声明。
     */
    fun menuActionsFor(
        card: BoardCard,
        atts: List<com.phonlynn.oreplan.domain.model.Attachment>,
    ): com.phonlynn.oreplan.v2.components.CardMenuActions {
        val sizes = cardFontSizes(state.cardFontSize, state.compact)
        return com.phonlynn.oreplan.v2.components.CardMenuActions(
            // 「复制」= 复制卡片文本到剪贴板（不是生成副本卡片）。
            onCopy = {
                com.phonlynn.oreplan.platform.export.CardLongImage.copyText(context, card.title, card.body)
            },
            onExport = {
                com.phonlynn.oreplan.v2.components.exportCardLongImage(
                    context = context, scope = menuScope,
                    storage = viewModel.attachmentStorage,
                    title = card.title, body = card.body, attachments = atts,
                    background = boardCardColor(card.color),
                    ink = VColors.ink, ink3 = VColors.ink3,
                    titleSp = sizes.title.value, bodySp = sizes.body.value,
                )
            },
            onShare = { shareTarget = card to atts },
            // 「更多」= 进该卡片的「更多设置」页（原来的三点行为原样保留成这一项）。
            onMore = {
                focusNavigatingAway = true
                pushCardPrefill(card)
                viewModel.unfocus()
                // ⚠️ 这里**曾经**有一行 `fsEditActive = true`（2026-09-30 我加的），
                // 本意是让"更多设置"会退回到编辑层。**已删除**，因为：
                //   1. 它并没有让"退出→编辑页"成立（#34 仍未修）；
                //   2. 它把 `enterFullscreenTextEdit` 的门闩（`if (!fsEditActive)`）锁死，
                //      导致**返回之后双击再也进不去编辑**（#35，用户明确说这条比 #34 严重得多）。
                // 正确做法：离开本页时**不要**去动本页的浮层状态 ——
                // 展示页/编辑页都是本页内的浮层，导航离开时把它们置成"半开"只会制造非法中间态。
                navigate(V2Routes.boardCard(cardId = card.id))
            },
        )
    }
    val fsEditProgress by animateFloatAsState(
        targetValue = if (fsEditActive) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = VMotion.Emphasized),
        label = "boardFullscreenTextEdit",
    )
    val fsEditMounted = fsEditProgress > 0.001f
    // 退场动画跑完再卸下编辑状态：中途换 doc 会让文字跳一下。
    LaunchedEffect(fsEditMounted) { if (!fsEditMounted) fsEditState = null }

    // 拖动卡片墙时收起标签菜单/面板（菜单展开期间卡片墙不可滚，这里兜底标签栏自身的拖动）。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            filterMenuOpen = false
            treePanelOpen = false
        }
    }

    // 根容器：让「全屏图片查看」能画在最上层（Tab 栏之上）。
    Box(Modifier.fillMaxSize()) {
    // 当前聚焦卡片（提到根层作用域）：根层的全屏浮层也要用它。
    val focusedOnce = focusedCard
    VTabScaffold(
        active = VTab.Board,
        onSelect = onSelectTab,
        // FAB 不放在脚手架的 fab 槽位：那个槽位绘制在内容**之上**，
        // 聚焦时无法被聚焦层的模糊盖住。改为在内容里自己画（见下方），
        // 放在聚焦浮层之前，让浮层自然盖住它。
        fab = null,
    ) {
        // 双指捏合进入多选模式、张开退出。
        //
        // 位置很关键：必须挂在**外层容器**上，而不是 LazyColumn 自己身上。
        // LazyColumn 内部会消费手势，挂在它自己身上时双指事件传不到。
        //
        // 为什么不用 detectTransformGestures（用户反馈「非常难以触发」）：
        // 它在跟踪前需要跨过触摸 slop，而且 zoom 是逐帧比率、要一次捏出
        // 0.98 以上的**单帧**变化才命中——两指缓慢、小幅地收拢时几乎永远达不到。
        // 现在改成自己累乘缩放：
        //  - 只要出现**第二根手指**就开始跟踪；
        //  - 累积 scale 变化量跨过很小阈值（捏到 0.97 / 张开到 1.03）就切换，
        //    比单帧阈值宽松得多，一次自然的两指收拢即可；
        //  - 单指触摸完全不介入，滚动与点击不受影响。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var accumulated = 1f
                    var twoFinger = false
                    awaitEachGesture {
                        accumulated = 1f
                        twoFinger = false
                        do {
                            // 必须在 **Initial** pass 读事件，不能留在默认的 Main。
                            //
                            // 原因：父节点用 Main pass 时，内层 LazyColumn 会**先**拿到
                            // 事件（Main 是自底向上分发的），双指平移的第一根手指已经
                            // 让它滚过了——等父节点再消费已经晚了。
                            // Initial 是自顶向下分发（父 → 子），在这里消费后，
                            // LazyColumn 在 Main 看到的就是已消费事件，不会滚动。
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) twoFinger = true
                            if (twoFinger) {
                                // 双指期间必须消费**全部**事件，而不仅是有缩放增量的那一帧。
                                //
                                // 原实现只在 `zoom != 1f` 时消费，而捏合过程中有很多帧的
                                // zoom 恰好等于 1（两根手指同步平移、刚落下还没获得瞬间间距等），
                                // 那些帧的事件会透下去给到 LazyColumn → 页面跟着被拖动，
                                // 表现就是「双指捏合时屏幕还在被拖」。
                                //
                                // 一旦出现第二根手指，这个手势就归捏合管，直到所有手指抬起
                                //（do-while 退出）。单指不干预：滚动、点击、卡片长按拖动均不受影响。
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    accumulated *= zoom
                                    if (!selectMode && accumulated < 0.97f) {
                                        selectMode = true
                                        accumulated = 1f
                                    } else if (selectMode && accumulated > 1.03f) {
                                        selectMode = false
                                        accumulated = 1f
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            // 聚焦时卡片墙轻度模糊（设计稿 Blur Backdrop：模糊 + 白色蒙层）。
            //
            // 实现：**固定半径的双层交叉淡化**，避免逐帧重建 RenderEffect。
            // Modifier.blur 的半径一旦变化，Compose 就要重建一次 RenderEffect，
            // 逐帧重建非常重（实测滞卡）。固定 2dp 后 RenderEffect 只建一次，
            // 过渡只改图层 alpha，开销极小。
            //   清晰层：alpha = 1 - t
            //   模糊层：恒定 2dp 模糊，alpha = t
            // t = blurProgress，进入随浮层入场 springy，退出随浮层 130ms 同步。
            //
            // 全屏形态下不模糊：全屏页铺满屏幕，背景完全看不到，
            // 做模糊只是白花 GPU（每帧一次全屏模糊），没有任何视觉收益。
            val blurT = if (state.focusStyle == "fullscreen") 0f
            else blurProgress.value.coerceIn(0f, 1f)

            // 卡片墙内容（列表）提取为局部可复用 lambda，供两层共用。
            // Kotlin 局部 lambda 会捕获所在作用域的变量，无需手动传参。
            val boardContent: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .onGloballyPositioned { coords ->
                    val top = coords.positionInRoot().y
                    if (abs(top - listTopPx) > 0.5f) listTopPx = top
                    // 供边缘自动滚动使用：卡片墙可视区的上下边界。
                    // 上边界从标签栏之下算起（标题/搜索/标签栏不参与滚动）。
                    boardTopY = top + (tagBarBottomPx - top).coerceAtLeast(0f)
                    boardBottomY = coords.positionInRoot().y + coords.size.height
                },
            contentPadding = PaddingValues(
                // 卡片与屏幕左右边缘的间隙：跟随间距预设（紧凑时更小，空间利用率更高）。
                start = spacing.edge,
                end = spacing.edge,
                // 顶部留白固定（顶部多选条已删，不再需要额外下移）。
                top = 8.dp,
                bottom = VTabBottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                if (state.newCardEntry == "header") {
                    // 白板：右侧留「新建卡片 +」（那是**页内动作**，不是设置入口）。
                    VScreenTitle(
                        title = "白板",
                        overline = "${state.totalCount} 张卡片 · 最近更新 ${state.lastUpdatedText}",
                        onSidebar = { navigate(V2Routes.BOARD_SETTINGS) },
                        onAi = onAi,
                        trailing = {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(VColors.surface, androidx.compose.foundation.shape.CircleShape)
                                    .border(1.dp, VColors.line, androidx.compose.foundation.shape.CircleShape)
                                    .vPressable(scaleDown = 0.9f) {
                                        BoardCardEntryBus.pushNewCardTag(state.selectedTagId)
                                        navigate(V2Routes.boardCard(type = state.defaultType))
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Lucide.Plus, contentDescription = "新建卡片", modifier = Modifier.size(20.dp), tint = VColors.accent)
                            }
                        },
                    )
                } else {
                    VScreenTitle(
                        title = "白板",
                        overline = "${state.totalCount} 张卡片 · 最近更新 ${state.lastUpdatedText}",
                        onSidebar = { navigate(V2Routes.BOARD_SETTINGS) },
                        onAi = onAi,
                    )
                }
            }

            item(key = "search") {
                BoardSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                )
            }

            item(key = "tagbar") {
                val pathChain = tagPathChain(state.tags, state.selectedTagId)
                val pills = buildList {
                    add(
                        TagPillSpec(
                            key = TAG_PILL_ROOT_KEY,
                            label = "全部",
                            chevron = if (pathChain.isEmpty()) {
                                if (filterMenuOpen || treePanelOpen) TagPillChevron.Down else TagPillChevron.Right
                            } else {
                                TagPillChevron.None
                            },
                        ),
                    )
                    pathChain.forEach { tag ->
                        add(
                            TagPillSpec(
                                key = tag.id,
                                label = tag.name,
                                chevron = if (tag.id == state.selectedTagId) {
                                    if (filterMenuOpen || treePanelOpen) TagPillChevron.Down else TagPillChevron.Right
                                } else {
                                    TagPillChevron.None
                                },
                            ),
                        )
                    }
                }
                // 标签栏渲染序列：当前胶囊 + 正在退场的胶囊（退场状态由副作用维护，
                // 与卡片编辑页共用同一份实现，避免两处表现不一致）。
                val renderedPills = rememberPillsWithExiting(pills)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 标签栏可横向滑动：胶囊总宽超过可用宽度时，用户可以左右拖动查看。
                    // 用 weight(1f) 占满左侧剩余空间（右侧留给「全部标签」按钮）。
                    Box(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                    TagPillRow(
                        pills = renderedPills,
                        onPillClick = { index ->
                            val pill = pills[index]
                            if (index == pills.lastIndex) {
                                if (filterMenuOpen) {
                                    filterMenuOpen = false
                                } else {
                                    treePanelOpen = false
                                    filterMenuOpen = true
                                }
                            } else {
                                // 点上级胶囊：回到那一级（不展开菜单）。
                                viewModel.selectTag(if (pill.key == TAG_PILL_ROOT_KEY) null else pill.key)
                                filterMenuOpen = false
                                treePanelOpen = false
                            }
                        },
                        onPillLeft = { key, left -> if (pillLeftPx[key] != left) pillLeftPx[key] = left },
                        onBottom = { bottom -> if (abs(bottom - tagBarBottomPx) > 0.5f) tagBarBottomPx = bottom },
                    )
                    }
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(VColors.surface, CircleShape)
                            .border(1.dp, VColors.line, CircleShape)
                            .vPressable(scaleDown = 0.9f) {
                                treePanelOpen = !treePanelOpen
                                filterMenuOpen = false
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.FolderTree, contentDescription = "全部标签", modifier = Modifier.size(16.dp), tint = VColors.ink2)
                    }
                }
            }

            // 空状态的分支条件必须看 renderedCards（换场缓冲），而不是 state.cards：
            // 若看实时数据，筛到空标签的那一帧 state.cards 已为空，卡片墙会被立即卸载，
            // 它的退场动画还没来得及播就被丢掉 —— 用户看到的就是「卡片瞬间消失」。
            if (state.loaded && renderedCards.isEmpty()) {
                item(key = "empty") {
                    // 空状态的入场 = 卡片入场的逆动画：淡入 + 从 0.92 放大到位。
                    val emptyProgress = remember { Animatable(0f) }
                    LaunchedEffect(emptyEpoch) {
                        emptyProgress.snapTo(0f)
                        emptyProgress.animateTo(1f, animationSpec = VMotion.springy())
                    }
                    val emptyAlpha = emptyProgress.value.coerceIn(0f, 1f)
                    val emptyScale = 0.92f + 0.08f * emptyProgress.value
                    // 空状态居中：让它在「头部 + 搜索 + 标签栏」下方的剩余区域里垂直居中。
                    // 做法：本项铺满整个列表视口，再整体上移 chrome/2，
                    // 使容器中心从「视口中心」移到「chrome 下方剩余区域」的中心。
                    // 不能用 padding(top = chrome)：那是在满高容器里往下推内容，会掉到页面下方。
                    val chromePx = (tagBarBottomPx - listTopPx).coerceAtLeast(0f)
                    val shiftDp = with(density) { (chromePx / 2f + 16.dp.toPx() / 2f).toDp() }
                    Box(
                        modifier = Modifier
                            .fillParentMaxHeight()
                            .offset(y = -shiftDp)
                            .graphicsLayer {
                                alpha = emptyAlpha
                                scaleX = emptyScale
                                scaleY = emptyScale
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        VEmptyState(
                            icon = Lucide.Layers,
                            title = if (state.selectedTagId != null) "这个标签下还没有卡片" else "还没有卡片",
                            description = if (state.selectedTagId != null) {
                                "换一个标签，或点上方的「全部」看回所有卡片。"
                            } else {
                                "点右下角新建，把想法、待办、摘抄或目标记下来。"
                            },
                        )
                    }
                }
            } else {
                // 双列瀑布流：每张卡片独立放入当前较矮的那一列，互不绑定。
                // （旧实现把卡片切成「行」，行内两张卡由 Row 绑定、高度互相拖累，
                //   拖动时也永远整行一起动——这正是「卡片不独立」的根源。）
                item(key = "masonry") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                val p = coords.positionInRoot()
                                if (masonryOriginInRoot != p) masonryOriginInRoot = p
                                // 拖放落点模拟需要容器宽度（算列宽/宽卡判定）。
                                val wpx = coords.size.width.toFloat()
                                if (boardWidthPx != wpx) boardWidthPx = wpx
                            },
                    ) {
                        MasonryBoard(
                            // 渲染列表 = 当前卡片 + 正在离场的卡片（已按原下标插回原位）。
                            // 离场卡在 presence 归 0 后会从 renderedCards 里移除，
                            // 所以不会重复 id（MasonryBoard 用 card.id 作 key）。
                            cards = renderedCards,
                            isFullWidth = { cardIsFullWidth(it, state.autoWidthChars, state.imagesByCard[it.id].orEmpty().isNotEmpty()) },
                            modifier = Modifier.fillMaxWidth(),
                            // 推挤层输入：被拖卡片的**实时视觉矩形** + 它自己的 id。
                            // 拖动中其余卡片会根据与它的二维交叠量被连续挤开。
                            // 拖动状态交给 MasonryBoard：目标是「静态槽位 + 连续推挤」，
                            // 指针位置只在它的动画循环里读，不经过这里的组合。
                            drag = drag,
                            slots = boardSlots,
                            columnGap = spacing.column,
                            rowGap = spacing.row,
                            // 容器原点（每帧读取）：被拖卡片的跟手要把 root 坐标换算成容器内坐标。
                            // 必须传：漏传会让卡片被摆到 root 坐标处（偏下约一个容器上边距），
                            // 就是「长按后卡片跳位」。参数现在**没有默认值**，漏传直接编译不过。
                            originInRoot = { masonryOriginInRoot },
                            // 离场卡冻结位置：让它们**原地淡出**而不是被重排
                            // 带来的新槽位拖走（用户 2026-09-22：「卡位快速飞离」）。
                            // 判据取自 leavingCards（离场集合）——
                            // 它与卡片 presence 的目标值是同一个真值来源
                            //（“这张卡还在不在 state.cards 里”），不会出现两套口径。
                            isLeaving = { id -> leavingCards.containsKey(id) },
                        ) { card ->
                            // 多选模式下：左侧留出复选框，卡片整体向右让位并等比例缩小。
                            // 复选框槽位的宽度与透明度都由 selectT 驱动，
                            // 因此卡片是「被逐渐让位」而不是被一下子挤开。
                            val selT = selectAnim.value
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp * selT),
                            ) {
                                // 复选框槽位：宽度 0 → 22dp，同时淡入。
                                // 不用 if 硬切：那会在同一帧把宽度撑开，把卡片挤走。
                                if (selT > 0.001f || selectMode) {
                                    val checked = selectedCardIds[card.id] == true
                                    Box(
                                        Modifier
                                            .padding(top = 4.dp)
                                            .size(width = (22.dp * selT), height = 22.dp)
                                            .graphicsLayer { alpha = selT }
                                            .vPressable(scaleDown = 0.9f, enabled = selT > 0.5f) {
                                                if (checked) selectedCardIds.remove(card.id)
                                                else selectedCardIds[card.id] = true
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        // 只有宽度足够时才画圆圈，避免被压成椭圆。
                                        if (selT > 0.35f) {
                                            Box(
                                                Modifier
                                                    .size(22.dp)
                                                    .background(
                                                        if (checked) VColors.accent else VColors.surface,
                                                        CircleShape,
                                                    )
                                                    .border(
                                                        1.5.dp,
                                                        if (checked) VColors.accent else VColors.line,
                                                        CircleShape,
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (checked) {
                                                    Icon(Lucide.Check, contentDescription = "已选中", modifier = Modifier.size(13.dp), tint = Color.White)
                                                }
                                            }
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                val tagPath = tagPathText(
                                    state.cardTags.filter { it.cardId == card.id }.map { it.tagId },
                                    state.tags,
                                )
                                val dragEnabled = state.dragReorder
                                // ---- 单张卡的「在场程度」动画 ----
                                //
                                // 唯一真值来源：这张卡现在**在不在数据里**（state.cards）。
                                //   · 在 → 目标 1；不在 → 目标 0（播完退场后从渲染列表移除）。
                                // 卡片只负责**驱动进度**，进度本身存在父层的 presence 表里，
                                // 并且在从 0 变 1 时**先错峰再动画**。
                                //
                                // 关键：presence 存在父层 → 卡片退场被移除、再次回来时，
                                // 它能正确地从 0 开始播入场（不会因为 remember 被销毁而丢掉）。
                                val inState = state.cards.any { it.id == card.id }
                                // 不在表里 = 第一次出现在这一屏 → 视为「刚开始入场」（0）。
                                // 注意不要在组合期写表（会触发重组循环）；由 effect 去建立条目。
                                val p0 = presence[card.id] ?: 0f
                                LaunchedEffect(inState, card.id) {
                                    if (inState) {
                                        if (presence[card.id] == 1f) return@LaunchedEffect
                                        // 首次出现且表里还没有 → 从 0 起跑（播入场）。
                                        if (presence[card.id] == null) presence[card.id] = 0f
                                        val ordinal = renderedCards.indexOfFirst { it.id == card.id }
                                            .coerceAtLeast(0)
                                        delay((ordinal * CARD_ENTER_STEP_MS).coerceAtMost(CARD_ENTER_MAX_DELAY_MS))
                                        androidx.compose.animation.core.animate(
                                            initialValue = presence[card.id] ?: 0f,
                                            targetValue = 1f,
                                            animationSpec = VMotion.springy(),
                                        ) { v, _ -> presence[card.id] = v }
                                        presence[card.id] = 1f
                                    } else {
                                        androidx.compose.animation.core.animate(
                                            initialValue = presence[card.id] ?: 1f,
                                            targetValue = 0f,
                                            animationSpec = tween(
                                                durationMillis = CARD_EXIT_DURATION_MS,
                                                easing = VMotion.Accelerate,
                                            ),
                                        ) { v, _ -> presence[card.id] = v }
                                        presence[card.id] = 0f
                                        // 通知父层把它从渲染列表移除（不让卡片自己改共享列表）。
                                        leavingDone = leavingDone + card.id
                                    }
                                }
                                val p = presence[card.id] ?: p0
                                // alpha 夹在 0..1；缩放允许弹簧的轻微过冲（>1）呈现出来。
                                val alphaIn = p.coerceIn(0f, 1f)
                                // 缩放：重渐变、轻缩放（1 → 0.97）。
                                val ki = 0.92f + 0.08f * p
                                val ko = 0.97f + 0.03f * p
                                BoardCardTile(
                                    card = card,
                                    todos = state.todoByCard[card.id].orEmpty(),
                                    tagChain = state.tagChainByCard[card.id].orEmpty(),
                                    images = state.imagesByCard[card.id].orEmpty(),
                                    imageLayout = card.imageLayout ?: state.defaultImageLayout,
                                    isWideCard = cardIsFullWidth(card, state.autoWidthChars, state.imagesByCard[card.id].orEmpty().isNotEmpty()),
                                    attachmentCount = state.attachmentCountByCard[card.id] ?: 0,
                                    storage = viewModel.attachmentStorage,
                                    // 主页直接勾选正文里的复选框（只改正文）。
                                    onBodyChange = { newBody -> viewModel.setCardBody(card.id, newBody) },
                                    onClick = {
                                        focusStartBounds = tileBounds[card.id]
                                        viewModel.focus(card.id)
                                    },
                                    // 宽度由 MasonryBoard 统一给定：整行卡横跨两列，
                                    // 窄卡占单列宽。这里只要填满分配到的槽位即可，
                                    // 不再参与「宽度规则」的判断（避免与分行互为因果）。
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = alphaIn
                                            // 多选时等比例缩小，腾出的空白正好放左侧复选框。
                                            val s2 = 1f - 0.06f * selectAnim.value
                                            scaleX = ki * ko * s2
                                            scaleY = ki * ko * s2
                                        }
                                        .onGloballyPositioned { coords ->
                                            val rect = coords.boundsInRoot()
                                            if (tileBounds[card.id] != rect) tileBounds[card.id] = rect
                                        },
                                    rotation = if (state.cardTilt) rotationFor(card.id, cardIsFullWidth(card, state.autoWidthChars, state.imagesByCard[card.id].orEmpty().isNotEmpty())) else 0f,
                                    compact = state.compact,
                                    fontSizes = cardFontSizes(state.cardFontSize, state.compact),
                                    onDoubleClick = if (state.doubleTapEdit) {
                                        {
                                            pushCardPrefill(card)
                                            navigate(V2Routes.boardCard(cardId = card.id, focusBody = true))
                                        }
                                    } else {
                                        null
                                    },
                                    onDragStart = if (dragEnabled) {
                                        { pointerInRoot ->
                                            val rect = tileBounds[card.id]
                                            if (rect != null) {
                                                // 抓取点：手指相对卡片左上角的偏移。
                                                // 拖动中保持这个关系，卡片才不会在抓起的瞬间跳。
                                                grabDx = pointerInRoot.x - rect.left
                                                grabDy = pointerInRoot.y - rect.top
                                                // 只传 root 坐标 + 抓取偏移；容器内坐标由动画层每帧现算。
                                                // （千万不要在这里预先换算成容器坐标并缓存：
                                                //   容器会随滚动移动，缓存值会过期，
                                                //   曾导致「长按后卡片跳位」与「让位区停住」。）
                                                drag.start(
                                                    id = card.id,
                                                    rootX = pointerInRoot.x,
                                                    rootY = pointerInRoot.y,
                                                    grabDx = grabDx,
                                                    grabDy = grabDy,
                                                    insertIndex = renderedCards.indexOfFirst { it.id == card.id }
                                                        .coerceAtLeast(0),
                                                )
                                                // 供边缘自动滚动使用（只在滚动循环里读）。
                                                dragPointerY = pointerInRoot.y
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                    onDrag = if (dragEnabled) {
                                        { pointerInRoot ->
                                            // 指针位置交给拖动状态（root 坐标）。
                                            // 卡片的容器内位置由动画层用「root − 容器原点 − 抓取偏移」自己算：
                                            // 容器会随列表滚动而移动，在那里算才能同时正确处理滚动。
                                            drag.moveRoot(pointerInRoot.x, pointerInRoot.y)
                                            dragPointerY = pointerInRoot.y
                                            // 重新算落点（自动滚动时指针不动也要重算，见上面循环）。
                                            resolveDropTarget(renderedCards)
                                        }
                                    } else {
                                        null
                                    },
                                    onDragEnd = if (dragEnabled) {
                                        {
                                            // 顺序已在拖动中实时更新，这里写库。
                                            viewModel.applyOrder(renderedCards.map { it.id }, card.id)
                                            // 松手：清掉拖动标记。卡片的目标随即变回「静态槽位」，
                                            // 动画循环会从当前指针位置**连续收敛**到槽位——
                                            // 「拖动」与「落位」是同一个动画，不存在突变。
                                            drag.end()
                                        }
                                    } else {
                                        null
                                    },
                                    // 长按**一动不动**就松手 = 复制这张卡的文本（问题 #13）。
                                    // 与三点菜单里的「复制」共用同一份实现，口径一致（标题 + 正文纯文本）。
                                    onCopyText = if (dragEnabled) {
                                        {
                                            com.phonlynn.oreplan.platform.export.CardLongImage
                                                .copyText(context, card.title, card.body)
                                        }
                                    } else {
                                        null
                                    },
                                    // 浮起样式只在开始/结束拖动时变（一次拖动两次），
                                    // 不会造成高频重组。
                                    dragging = drag.draggedId == card.id,
                                )
                                }
                            }
                            }
                        }
                    }
                }
            }
            }
            // 新建 FAB 放进 boardContent 内部：这样它会被上面那层模糊一起盖住
            // （聚焦时看起来自然变糊），而不用做淡入淡出。
            //
            // 多选模式：FAB **淡出消失**（不再用删除按钮去盖它），
            // 退出多选后淡入回来。用 selectAnim（0=正常，1=多选）反向做 alpha，
            // 与卡片缩放/复选框用同一个进度，时间轴自动对齐。
            // 新建 FAB。**始终存在**（用户 2026-09-19）：不参与多选、不做淡入淡出。
            // 原因：alpha 动画会给节点建离屏缓冲、缓冲按节点尺寸建立，阴影超出部分被裁；
            // 既然这个效果反复出问题，就不做任何显隐变换，永远显示。
            // 因此多选操作按钮整体上移，不再占用 FAB 的位置（见下方）。
            if (newCardEntry == "fab") {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp)
                        .padding(bottom = VTabBottomPadding)
                        .navigationBarsPadding()
                        // 聚焦时它已被模糊遮盖，就不应再响应点击。
                        .then(if (focusId != null) Modifier.pointerInput(Unit) {} else Modifier),
                ) {
                    VFAB(onClick = {
                        BoardCardEntryBus.pushNewCardTag(state.selectedTagId)
                        navigate(V2Routes.boardCard(type = state.defaultType))
                    })
                }
            }
            }  // end boardContent

            // 双层交叉淡化。
            //
            // 实现：卡片墙只**组合一次**，内容记录到一个 GraphicsLayer，
            // 再把这一层画两遍——底层清晰、顶层模糊。
            //
            // 为什么必须只组合一次：早先调两次 boardContent()、两层各一个
            // LazyColumn，但两者共用同一个 listState —— 这是未定义行为，
            // 两个 LazyColumn 争同一个滚动状态会死锁，表现为卡死、滑不动、
            // 点不进聚焦（2026-09-17 真实 bug）。
            //
            // alpha / 模糊都设在 layer 自身属性上，不依赖 drawLayer 的重载参数。
            // 两个独立图层：各自持有自己的 alpha / renderEffect，互不干扰。
            // （本版 Compose 的 drawLayer 只收图层本身，alpha 与模糊都设在图层属性上。）
            val sharpLayer = rememberGraphicsLayer()
            val blurLayer = rememberGraphicsLayer()
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        // 内容只组合一次；但**不该画的图层就不记**：
                        //  · 不聚焦（blurT = 0）：模糊层整层透明，记它等于每帧把整面卡片墙
                        //    多记一遍 —— 滑动与离页转场都白付一遍；
                        //  · 模糊到位（blurT = 1）：清晰层已被盖住，也不必再画。
                        // 改的只是**绘制**，组合结构一字未动 —— 上面「两层结构必须恒定」
                        // 的约束仍然成立（它管的是不能按进度拆/建子树）。
                        if (blurT < 0.999f) {
                            sharpLayer.record { this@drawWithContent.drawContent() }
                            // 底层：清晰，随 t 增大而淡出。
                            sharpLayer.alpha = 1f - blurT
                            sharpLayer.renderEffect = null
                            drawLayer(sharpLayer)
                        }
                        // 顶层：固定 2dp 模糊，随 t 增大而淡入；t = 0 则整层透明不画。
                        // 半径固定 → RenderEffect 不逐帧变化（逐帧重建会明显卡）。
                        if (blurT > 0.001f) {
                            blurLayer.record { this@drawWithContent.drawContent() }
                            blurLayer.alpha = blurT
                            blurLayer.renderEffect = BlurEffect(2.dp.toPx(), 2.dp.toPx(), TileMode.Clamp)
                            drawLayer(blurLayer)
                        }
                    },
            ) {
                boardContent()
            }
        }

        // ---------------------------------------------------------------- 多选操作按钮（右下）
        //
        // 用户 2026-09-19 两次调整后定稿：
        //  · 第1步：**删除**按钮原地出现（不位移、不缩放），直接盖住右下角的加号（FAB 不隐藏）；
        //  · 第2步：**归档 / 移动标签**从删除按钮下方向上弹出（错峰 + 向上位移 + 淡入），
        //    从下到上依次是：删除 → 归档 → 移动标签；
        //  · 退出多选：两个上按钮先收回，删除再消失。
        // 配色：删除 = 红底白标；归档 / 移动标签 = 绿底白标（与加号同色）。
        val selectedCount = selectedCardIds.size
        val hasSelection = selectedCount > 0
        // 三个按钮各自的入场进度：
        //  · deleteProgress —— 删除按钮：随多选淡入淡出（原地，不位移不缩放）；
        //  · riseProgress[0] / [1] —— 归档、移动标签：从删除按钮下方向上错峰弹出。
        val deleteProgress = remember { Animatable(0f) }
        val riseProgress = remember { List(2) { Animatable(0f) } }
        LaunchedEffect(selectMode) {
            if (selectMode) {
                // 删除先在自己的位置淡入，随后两个上按钮错峰冒出。
                launch { deleteProgress.animateTo(1f, tween(160, easing = VMotion.Emphasized)) }
                riseProgress.forEachIndexed { i, anim ->
                    launch {
                        delay(90L + i * 60L)
                        anim.animateTo(1f, VMotion.springy())
                    }
                }
            } else {
                // 退场：从上到下依次收回（最上面的先退），最后删除淡出。
                riseProgress.reversed().forEachIndexed { i, anim ->
                    launch {
                        delay(i * 45L)
                        anim.animateTo(0f, tween(150, easing = VMotion.Accelerate))
                    }
                }
                launch {
                    delay(120L)
                    deleteProgress.animateTo(0f, tween(150, easing = VMotion.Accelerate))
                }
            }
        }
        // 选中数量变化时，按钮配色用连续过渡而不是硬切（用户 2026-09-19）。
        val selectTint by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (hasSelection) 1f else 0f,
            animationSpec = tween(200, easing = VMotion.Emphasized),
            label = "selectTint",
        )
        // 移动标签：弹出选择标签的菜单。
        var moveTagMenuOpen by remember { mutableStateOf(false) }

        // 退场时仍需挂着渲染直到动画收完。
        val stackVisible = selectMode || deleteProgress.value > 0.01f || riseProgress.any { it.value > 0.01f }
        if (stackVisible) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp)
                    // FAB **始终显示**（不参与多选），所以多选按钮整体上移，
                    // 避免与右下角的加号重叠：底部留白 = FAB 的底距 + FAB 高度 + 一个间距。
                    .padding(bottom = VTabBottomPadding + 56.dp + 14.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 从上到下依次渲染：移动标签 → 归档 → 删除。
                // 前两个用 riseProgress[0]=移动标签? 不：移动标签在最上，应最后出现；
                // 所以 riseProgress[0] 对应**归档**（靠下、先出现），
                // riseProgress[1] 对应**移动标签**（靠上、后出现）。
                val greenBg = VColors.accent
                val greenFg = Color.White

                // ① 移动标签（最上，后出现）
                RiseActionButton(
                    label = "移动标签",
                    icon = Lucide.FolderInput,
                    progress = riseProgress[1].value,
                    bg = androidx.compose.ui.graphics.lerp(VColors.surface2, greenBg, selectTint),
                    fg = androidx.compose.ui.graphics.lerp(VColors.ink3, greenFg, selectTint),
                    enabled = hasSelection,
                    onClick = { moveTagMenuOpen = true },
                )
                // ② 归档（中间，先出现）
                RiseActionButton(
                    label = "归档",
                    icon = Lucide.Archive,
                    progress = riseProgress[0].value,
                    bg = androidx.compose.ui.graphics.lerp(VColors.surface2, greenBg, selectTint),
                    fg = androidx.compose.ui.graphics.lerp(VColors.ink3, greenFg, selectTint),
                    enabled = hasSelection,
                    onClick = {
                        if (hasSelection) {
                            viewModel.archiveCards(selectedCardIds.keys.toList())
                            selectMode = false
                        }
                    },
                )
                // ③ 删除（最下，贴 FAB 位；**原地出现，无位移/无缩放**，但要有淡入淡出）
                //
                // 单个节点，`shadow` 与 `background` 都在 `graphicsLayer` **之内**：
                // 这样阴影就是图层内容的一部分，alpha 淡入淡出时阴影跟着一起淡，
                // 也不会出现“阴影孤立在图层外被裁”的方形截断。
                // vShadowRoom 在最外（对外 56dp），内层 fillMaxSize 建立 92dp 的
                // alpha 缓冲（所以阴影不会被裁），最内的 56dp 圆由父 Box 居中。
                // 三层结构（与 VFAB 完全同款，顺序不能变）：
                //   ① vShadowRoom 最外 —— 对外报 56dp，但强制按 92dp 测量内容；
                //   ② 中间 fillMaxSize 透明层 —— 让下面的动画/绘制发生在一个
                //      92dp 节点上，离屏缓冲因此够大；
                //   ③ 最内 56dp 圆 —— **shadow 与 background 必须在同一个节点上**，
                //      拆开会画出 92dp 的方形阴影并丢掉底色。
                //
                // 2026-09-21：①② 曾丢失（只剩注释在提 vShadowRoom），
                // 于是缓冲退回 56dp、阴影被裁成方形。
                Box(
                    Modifier.vShadowRoom(contentSize = 56.dp, slack = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = deleteProgress.value },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .shadow(
                                    elevation = 12.dp,
                                    shape = CircleShape,
                                    clip = false,
                                    ambientColor = Color(0x591C6B58),
                                    spotColor = Color(0x591C6B58),
                                )
                                .background(
                                    androidx.compose.ui.graphics.lerp(VColors.surface2, VColors.roseDeep, selectTint),
                                    CircleShape,
                                )
                                .vPressable(scaleDown = 0.92f, enabled = hasSelection) {
                                    if (hasSelection) {
                                        viewModel.deleteCards(selectedCardIds.keys.toList())
                                        selectMode = false
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.Trash2,
                                contentDescription = "删除",
                                modifier = Modifier.size(22.dp),
                                tint = androidx.compose.ui.graphics.lerp(VColors.ink3, Color.White, selectTint),
                            )
                        }
                    }
                }
            }
            }

        // 「移动标签」：选中卡片后选择要移入的标签。
        if (moveTagMenuOpen) {
            MoveCardsToTagDialog(
                tags = state.tags,
                onPick = { tagId ->
                    viewModel.moveCardsToTag(selectedCardIds.keys.toList(), tagId)
                    moveTagMenuOpen = false
                    selectMode = false
                },
                onDismiss = { moveTagMenuOpen = false },
            )
        }

        // ---------------------------------------------------------------- 拖动
        // 拖动中的卡片就留在列表里、在它自己的小格子上；位置由 MasonryBoard 的
        // 动画层统一驱动（被拖时跟指针，松手后连续收敛回槽位）。
        // 这里不需要任何浮卡或落位阶段。

        val menuGapPx = with(density) { 16.dp.toPx() }

        // 菜单内容快照：仅在菜单可见期间跟随数据更新；淡出期间沿用上一份快照。
        // 否则点末级标签时，children 瞬间变空，菜单会在淡出时闪出「新建标签」一行。
        // 用快照后，菜单就是「保持原样原地淡出」，不再跳变。
        val menuChildrenRaw = state.tags.filter { it.parentId == state.selectedTagId }.sortedBy { it.sortIndex }
        var menuChildrenSnapshot by remember { mutableStateOf<List<BoardTag>>(emptyList()) }
        if (filterMenuOpen) {
            menuChildrenSnapshot = menuChildrenRaw
        }
        val menuAnchorRaw = pillLeftPx[state.selectedTagId ?: TAG_PILL_ROOT_KEY]
        var menuAnchorSnapshot by remember { mutableStateOf<Float?>(null) }
        if (filterMenuOpen && menuAnchorRaw != null) {
            menuAnchorSnapshot = menuAnchorRaw
        }

        TagMenuPresenter(
            visible = filterMenuOpen,
            topPx = tagBarBottomPx + menuGapPx,
            leftPx = menuAnchorSnapshot ?: with(density) { 20.dp.toPx() },
            onDismiss = { filterMenuOpen = false },
            menuWidthPx = with(density) { 210.dp.toPx() },
            resetKey = filterMenuEpoch,
            // 遮罩从标签栏底边开始，不盖住标签栏：菜单打开时仍可直接点其他胶囊切级。
            scrimTopPx = tagBarBottomPx,
        ) {
            TagDrillMenu(
                children = menuChildrenSnapshot,
                onPick = { tag ->
                    viewModel.selectTag(tag.id)
                    // 末级标签（没有下级）点完直接原地收起菜单，不再展开下一级。
                    if (state.tags.none { it.parentId == tag.id }) filterMenuOpen = false
                },
                onCreate = {
                    // 在「当前选中的标签」下新建子标签。
                    // 之前这里把二级标签的新建请求回退给了它的父标签（旧的两级限制），
                    // 导致在末级标签里新建的标签被挂到了父标签下。数据层本就支持任意层级。
                    createParentId = state.selectedTagId
                    newTagName = ""
                    createDialogOpen = true
                },
            )
        }

        TagMenuPresenter(
            visible = treePanelOpen,
            topPx = tagBarBottomPx + menuGapPx,
            leftPx = 0f,
            onDismiss = { treePanelOpen = false },
            resetKey = treePanelEpoch,
            scrimTopPx = tagBarBottomPx,
        ) {
            TagTreePanel(
                tags = state.tags,
                onPick = { tag ->
                    viewModel.selectTag(tag.id)
                    treePanelOpen = false
                },
                onSettings = { tag ->
                    treePanelOpen = false
                    navigate(V2Routes.boardTagEdit(tag.id))
                },
                onCreate = {
                    createParentId = null
                    newTagName = ""
                    createDialogOpen = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
        }

        // 卡片形态的聚焦浮层：留在脚手架内容区。
        // Tab 栏照旧画在它**之上**（与历史行为一致）。
        // 全屏形态不渲染在这里 —— 它要**覆盖** Tab 栏，见下面根层的那一份。
        if (focusId != null && focusedOnce != null && state.focusStyle != "fullscreen") {
        // 卡片形态也支持「双击进全屏编辑」：两种展示形态同一入口，
        // 避免用户因为设置不同而觉得功能时有时无。
        Box(
            Modifier.fillMaxSize().boardDoubleTap { p -> enterFullscreenTextEdit(focusedOnce, p) },
        ) {
        BoardFocusOverlay(
            card = focusedOnce,
            todos = focusTodos,
            tagChain = tagPathChain(state.tags, state.cardTags.firstOrNull { it.cardId == focusedOnce.id }?.tagId),
            attachments = focusAttachments,
            attachmentStorage = viewModel.attachmentStorage,
            linkedCards = focusLinks,
            reminderAt = focusReminderAt,
            doneToEnd = state.doneToEnd,
            startBounds = focusStartBounds,
            cardTilt = state.cardTilt,
            style = state.focusStyle,
            fontSizes = cardFontSizes(state.cardFontSize, state.compact),
            onToggleTodo = viewModel::toggleTodoItem,
            // 正文里的复选框可点：直接回写该卡正文（全屏展示页此前勾不上）。
            onToggleBody = { newBody -> viewModel.setCardBody(focusedOnce.id, newBody) },
            onOpenAttachment = { att ->
                if (att.isImage) {
                    // 图片：应用内**全屏查看**（可左右滑动切换该卡全部图片）。
                    val imgs = focusAttachments.filter { it.isImage }.map { it.storedPath }
                    fsImages = imgs
                    fsStart = imgs.indexOf(att.storedPath).coerceAtLeast(0)
                } else {
                    openAttachmentV2(context, viewModel.attachmentStorage, att) {}
                }
            },
            onOpenLinked = { id ->
                focusStartBounds = tileBounds[id]
                viewModel.focus(id)
            },
            onEdit = {
                // 跳去卡片编辑页：标记为「整个离开白板页」，让背景模糊立即 snapTo(0)、
                // 不播 130ms 退场。否则新页面已进场、旧页的模糊/浮层还在后台多跑一段，
                // 表现为「编辑页保存按钮慢半拍」的卡顿感（主页新建不经聚焦，所以无此问题）。
                focusNavigatingAway = true
                pushCardPrefill(focusedOnce)
                viewModel.unfocus()
                navigate(V2Routes.boardCard(cardId = focusedOnce.id))
            },
            onArchive = { viewModel.archiveFocusedCard() },
            onDelete = { viewModel.deleteFocusedCard() },
            onSink = { viewModel.sinkFocusedCard() },
            onDismissStart = { focusDismissing = true },
            // 双击空白同样进就地编辑：必须挂在这里，父层手势拦不住
            // 「单击空白 = 收起浮层」——双击的第一下会先把浮层收掉（闪一下白板主页）。
            onDoubleTap = { enterFullscreenTextEdit(focusedOnce, null) },
            onBodyBounds = { focusBodyBounds = it },
            onDismiss = {
                viewModel.unfocus()
                focusStartBounds = null
            },
        )
        }
    }

    }

    // 全屏形态的聚焦浮层：在**根层**渲染（脚手架之外），
    // 因此会盖在 Tab 栏之上 —— 是**覆盖**，不是把 Tab 栏隐藏掉（用户 2026-09-19 要求）。
    if (focusId != null && focusedOnce != null && state.focusStyle == "fullscreen") {
        Box(
            Modifier
                .fillMaxSize()
                // ⚠️ 展示页**保持不透明**：淡出会让「展示页 + 编辑层」同时半透明，
                // 后面的白板主页就透出来了 —— 用户 2026-09-28 报的「双击后闪一下主页」。
                // 进编辑靠编辑层的底色在它**上面**淡入盖住，两层之间不留任何缝。
                // 双击直接进全屏编辑。单击不做任何事，子节点的点击照旧。
                .boardDoubleTap { p -> enterFullscreenTextEdit(focusedOnce, p) },
        ) {
        BoardFocusOverlay(
                card = focusedOnce,
                todos = focusTodos,
                tagChain = tagPathChain(state.tags, state.cardTags.firstOrNull { it.cardId == focusedOnce.id }?.tagId),
                attachments = focusAttachments,
                attachmentStorage = viewModel.attachmentStorage,
                linkedCards = focusLinks,
                reminderAt = focusReminderAt,
                doneToEnd = state.doneToEnd,
                startBounds = focusStartBounds,
                cardTilt = state.cardTilt,
                style = state.focusStyle,
                fontSizes = cardFontSizes(state.cardFontSize, state.compact),
                onToggleTodo = viewModel::toggleTodoItem,
            // 正文里的复选框可点：直接回写该卡正文（全屏展示页此前勾不上）。
            onToggleBody = { newBody -> viewModel.setCardBody(focusedOnce.id, newBody) },
                onOpenAttachment = { att ->
                    if (att.isImage) {
                        // 图片：应用内**全屏查看**（可左右滑动切换该卡全部图片）。
                        val imgs = focusAttachments.filter { it.isImage }.map { it.storedPath }
                        fsImages = imgs
                        fsStart = imgs.indexOf(att.storedPath).coerceAtLeast(0)
                    } else {
                        openAttachmentV2(context, viewModel.attachmentStorage, att) {}
                    }
                },
                onOpenLinked = { id ->
                    focusStartBounds = tileBounds[id]
                    viewModel.focus(id)
                },
                onEdit = {
                    // 跳去卡片编辑页：标记为「整个离开白板页」，让背景模糊立即 snapTo(0)、
                    // 不播 130ms 退场。否则新页面已进场、旧页的模糊/浮层还在后台多跑一段，
                    // 表现为「编辑页保存按钮慢半拍」的卡顿感（主页新建不经聚焦，所以无此问题）。
                    focusNavigatingAway = true
                    pushCardPrefill(focusedOnce)
                    viewModel.unfocus()
                    navigate(V2Routes.boardCard(cardId = focusedOnce.id))
                },
                onArchive = { viewModel.archiveFocusedCard() },
                onDelete = { viewModel.deleteFocusedCard() },
                onSink = { viewModel.sinkFocusedCard() },
                onDismissStart = { focusDismissing = true },
                onDoubleTap = { enterFullscreenTextEdit(focusedOnce, null) },
                onBodyBounds = { focusBodyBounds = it },
                menu = menuActionsFor(focusedOnce, focusAttachments),
                onDismiss = {
                    viewModel.unfocus()
                    focusStartBounds = null
                },
            )
        }
        }

        // 就地全屏编辑层（从全屏展示页双击进入）。
        // 提交（对勾）与 X 都只是关掉这一层 → 回到全屏展示页，**不进卡片编辑页**。
        // 内容实时写回卡片（与展示页的复选框回写同一口径），所以退出后展示页已是新内容。
        if (fsEditMounted && fsEditState != null && focusedOnce != null) {
            val fsEditMd = fsEditState!!
            Box(Modifier.fillMaxSize()) {
                // 挡住下层展示页的输入：Initial 阶段消费，展示页的处理器会看到「已被消费」。
                // 本层内容排在其后（在上方），所以编辑区自己的点击不受影响。
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                )
                BoardCardFullscreenTextEdit(
                    progress = fsEditProgress,
                    fadeBackground = true,
                    // 图片区与展示页同一画法（用卡片自己的 imageLayout）。
                    imageLayout = focusedOnce.imageLayout,
                    // 三点下拉菜单：复制 / 导出 / 分享 / 更多（用户 2026-09-28）。
                    menu = menuActionsFor(focusedOnce, focusAttachments),
                    // 双击位置 → 光标落位（null 时默认落文末）。
                    initialCursorAt = fsEditTapAt,
                    onInitialCursorPlaced = { fsEditTapAt = null },
                    // 草稿：编辑期间不写库。
                    title = fsEditTitle,
                    body = fsEditBody,
                    mdState = fsEditMd,
                    color = boardCardColor(focusedOnce.color),
                    tags = state.tags,
                    tagId = state.cardTags.firstOrNull { it.cardId == focusedOnce.id }?.tagId,
                    attachments = focusAttachments,
                    attachmentStorage = viewModel.attachmentStorage,
                    showDate = focusedOnce.showDate,
                    pinned = focusedOnce.pinned,
                    secret = focusedOnce.secret,
                    autoPin = focusedOnce.autoPin,
                    autoPinResolvedAt = focusedOnce.autoPinResolvedAt,
                    fontSizes = cardFontSizes(state.cardFontSize, state.compact),
                    onTitleChange = { fsEditTitle = it },
                    onBodyChange = { fsEditBody = it },
                    onOpenAttachment = { att ->
                        if (att.isImage) {
                            val imgs = focusAttachments.filter { it.isImage }.map { it.storedPath }
                            fsImages = imgs
                            fsStart = imgs.indexOf(att.storedPath).coerceAtLeast(0)
                        } else {
                            openAttachmentV2(context, viewModel.attachmentStorage, att) {}
                        }
                    },
                    // ✓：**提交**草稿（标题 + 正文一次写回），再回全屏展示页。
                    //
                    // ⚠️ 这里**不加 saveEnabled 校验**，是有意的：
                    // 这条路径只有「已有卡片」会走（从展示页双击进来的就地编辑层），
                    // 它的语义是"提交我改的内容"，不是"用内容创建一张卡"。
                    // 用户 2026-09-30 定的口径 —— **「空内容不能保存」只管创建，不管保存/提交**。
                    // 新建卡片那颗 ✓ 在 BoardCardScreenV2 里，那边才是要拦的地方。
                    onSaveText = {
                        viewModel.setCardTitleBody(focusedOnce.id, fsEditTitle, fsEditBody)
                        fsEditActive = false
                    },
                    // X：**放弃**更改（草稿直接丢掉），回全屏展示页
                    //（不是首页 —— 用户是从展示页双击进来的）。
                    onExitHome = { fsEditActive = false },
                    // 长按对勾：提交后进该卡片的「更多设置」页（用户 2026-09-28）。
                    // 与三点进设置页走同一套离开白板的处理（背景模糊立即收起，
                    // 否则新页面已进场、旧页还在收尾）。
                    onSaveLongPress = {
                        focusNavigatingAway = true
                        pushCardPrefill(focusedOnce)
                        viewModel.unfocus()
                        navigate(V2Routes.boardCard(cardId = focusedOnce.id))
                    },
                )
            }
        }

        // 全屏图片查看：必须在**整个页面最外层**、且在 Tab 栏之上（见末尾）。
        // （之前放在 VTabScaffold 内容区，会被底部的 Tab 栏拦住下半部分。）

        if (createDialogOpen) {
            VFloatingInputDialog(
                icon = Lucide.Hash,
                value = newTagName,
                onValueChange = { newTagName = it },
                onConfirm = {
                    val name = newTagName.trim()
                    if (name.isNotEmpty()) viewModel.createTag(name, createParentId)
                    newTagName = ""
                    createDialogOpen = false
                },
                onDismiss = { createDialogOpen = false },
                placeholder = if (createParentId == null) "标签名称" else "子标签名称",
                confirmText = "创建",
            )
        }
        // 聚焦浮层：必须画在**根容器内、Tab 栏之上**。
        // （此前它放在 VTabScaffold 的内容槽里，底部 Tab 栏会盖住浮层下半部分；
        //   全屏形态下尤其明显：Tab 栏会直接压在整页上。）
        // 全屏图片查看：画在根容器**最上层**（在 VTabScaffold 及其 Tab 栏之后），
        // 才不会下半部分被 Tab 栏拦住。
        fsImages?.let { imgs ->
            VFullscreenImageViewer(
                paths = imgs,
                startIndex = fsStart,
                storage = viewModel.attachmentStorage,
                onDismiss = { fsImages = null },
            )
        }
        }   // end 根 Box

    }

@Composable
private fun BoardSearchBar(query: String, onQueryChange: (String) -> Unit) {
    // 实现在公用组件里（VSearchField），白板页与「关联事项」等弹层共用同一套外观，
    // 从此不会两处慢慢长歪。
    VSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = "搜索卡片（可用空格分词，如：待办 今天 紫色）…",
    )
}



/**
 * 「移动标签」弹窗（多选模式用，用户 2026-09-19）。
 *
 * 列出全部标签（按层级缩进），选中后把当前多选的卡片统一移入该标签。
 * 卡片是单标签模型，所以这里是**覆盖**式移动。
 * 顶部另给一个「移出标签」项（对应 tagId = null）。
 */
@Composable
private fun MoveCardsToTagDialog(
    tags: List<BoardTag>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 按层级深度展开成扁平行（顶层在前，子标签紧随其后）。
    val rows = remember(tags) {
        val children = tags.groupBy { it.parentId }
        val out = ArrayList<Pair<BoardTag, Int>>()
        fun walk(parentId: String?, depth: Int) {
            children[parentId].orEmpty().sortedBy { it.sortIndex }.forEach { t ->
                out.add(t to depth)
                walk(t.id, depth + 1)
            }
        }
        walk(null, 0)
        out
    }

    VDialog(onDismissRequest = onDismiss, maxWidth = 300.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel(horizontalPadding = 0.dp, verticalPadding = 12.dp) {
            VText(
                "移动标签",
                VTypo.dialogTitle,
                color = VColors.ink,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                    item {
                        MoveTagRow(label = "移出标签", depth = 0, isRoot = true) { close { onPick(null) } }
                    }
                    items(rows.size) { i ->
                        val (tag, depth) = rows[i]
                        MoveTagRow(label = tag.name, depth = depth, isRoot = false) { close { onPick(tag.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveTagRow(
    label: String,
    depth: Int,
    isRoot: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = (18 + 16 * depth).dp, end = 18.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (isRoot) Lucide.X else Lucide.Hash,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (isRoot) VColors.ink3 else VColors.accent,
        )
        VText(label, VTypo.body, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

/**
 * 多选模式右侧「向上弹出」的动作按钮（归档 / 移动标签）。
 *
 * [progress] 0 = 未出现（在原位置下方 20dp、透明、略小）；1 = 就位。
 * 删除按钮不走这个动画（它原地出现直接盖住 FAB）。
 */
@Composable
private fun RiseActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    progress: Float,
    bg: Color,
    fg: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // vShadowRoom 在最外（对外 56dp），内层 fillMaxSize 建立 92dp 的动画缓冲
    // （位移/缩放/淡入淡出都不会裁阴影），最内的 56dp 圆由父 Box 居中。
    // 三层结构：vShadowRoom（92dp 缓冲）→ 动画层 → 56dp 圆（shadow+background 同节点）。
    // 与 VFAB / 删除按钮同款，避免阴影被裁成方形。
    Box(
        Modifier.vShadowRoom(contentSize = 56.dp, slack = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - progress) * 20.dp.toPx()
                    alpha = progress
                    val s = 0.86f + 0.14f * progress
                    scaleX = s
                    scaleY = s
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color(0x591C6B58),
                        spotColor = Color(0x591C6B58),
                    )
                    .background(bg, CircleShape)
                    .vPressable(scaleDown = 0.92f, enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = fg)
            }
        }
    }
}

