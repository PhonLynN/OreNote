package com.phonlynn.oreplan.v2.screens

import android.net.Uri
import com.phonlynn.oreplan.domain.ai.tool.CardReminders
import com.phonlynn.oreplan.v2.components.attachmentMetaText
import com.phonlynn.oreplan.v2.components.VDividerFull
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.statusBars
import com.phonlynn.oreplan.v2.components.CardInfoBarPullOut
import com.phonlynn.oreplan.v2.components.CardInfoBarTopDownShift
import com.phonlynn.oreplan.v2.components.CardInfoBarToTitleGap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.core.tag.nextTagSortIndex
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.data.settings.BoardDraftStore
import com.phonlynn.oreplan.core.order.BoardDraftPayload
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardColors
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.core.rt.RichEditState
import com.phonlynn.oreplan.core.rt.plainPreviewOf
import com.phonlynn.oreplan.core.rt.plainTextOf
import com.phonlynn.oreplan.v2.richtext.VRichTextField
import com.phonlynn.oreplan.v2.richtext.VRichToolbar
import com.phonlynn.oreplan.v2.richtext.VKeyboardToolbar
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.QuickDurationRow
import com.phonlynn.oreplan.v2.components.VChipSmall
import com.phonlynn.oreplan.v2.components.VSegmented
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.components.VFloatingInputDialog
import com.phonlynn.oreplan.v2.components.VFullscreenImageViewer
import com.phonlynn.oreplan.v2.components.VRow
import com.phonlynn.oreplan.v2.components.VSwitch
import com.phonlynn.oreplan.v2.components.VTopBarClose
import com.phonlynn.oreplan.v2.components.openAttachmentV2
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.components.CardInfoBar
import com.phonlynn.oreplan.v2.components.FullscreenCardLayout
import com.phonlynn.oreplan.v2.components.VSaveButton
import com.phonlynn.oreplan.v2.components.fullscreenScrollTopPadding
import com.phonlynn.oreplan.v2.components.ThreeDotMenuButton
import com.phonlynn.oreplan.v2.components.cardWordCount
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class BoardCardDraft(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val type: BoardCardType = BoardCardType.QUICK,
    val title: String = "",
    val body: String = "",
    val color: String? = BoardColors.WHITE,
    val pinned: Boolean = false,
    val secret: Boolean = false,
    /** 保密卡在主页显示的暗号文案（写给自己的一句提醒），不是解锁密码。 */
    val secretHint: String = "",
    /** 卡片所属标签（单标签，null = 未选）。 */
    val tagId: String? = null,
    /** 卡片宽度：null=自动 / "half" / "full"。 */
    val widthMode: String? = null,
    /** 有图片时的展示样式：null=跟全局默认 / "fill" = 横向填充 / "grid" = 缩略网格。 */
    val imageLayout: String? = null,
    /** 卡片底部是否显示创建日期。 */
    val showDate: Boolean = true,
    val attachments: List<Attachment> = emptyList(),
    val linkedCardIds: List<String> = emptyList(),
    /** 卡片提醒时刻（毫秒），null = 未设置。 */
    val reminderAtMillis: Long? = null,
    /** 动态置顶规则。null = 未启用。与 [pinned] 独立并存。 */
    val autoPin: com.phonlynn.oreplan.domain.model.AutoPinRule? = null,
    /**
     * 本次浮起是否已归位（用于判定「此刻是否真的在浮起」）。
     *
     * 全屏编辑页顶部栏的时钟标记需要它：只判 `autoPin != null` 会把
     * 「已经沉下去的卡」也标成浮起中。
     */
    val autoPinResolvedAt: java.time.Instant? = null,
)

@HiltViewModel
class BoardCardScreenV2ViewModel @Inject constructor(
    private val repo: BoardRepository,
    private val settingsStore: AppSettingsStore,
    private val boardDraftStore: BoardDraftStore,
    private val itemRepository: ItemRepository,
    private val reminderRepository: ReminderRepository,
    private val attachmentRepository: AttachmentRepository,
    val attachmentStorage: AttachmentStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val cardId: String? =
        savedStateHandle.get<String>(V2Routes.ARG_CARD_ID)?.takeIf { it.isNotBlank() }
    private val argType: BoardCardType =
        savedStateHandle.get<String>(V2Routes.ARG_TYPE)?.takeIf { it.isNotBlank() }
            ?.let { BoardCardType.fromKey(it) } ?: BoardCardType.QUICK

    /**
     * 新建卡片在保存前先占用的 id（附件/提醒都挂在它名下）。
     *
     * 是 `var`：“保存并再记一笔”落库后必须换一个新 id，
     * 否则下一张卡会沿用同一个 id 把上一张**覆盖掉**
     * （表现为“再记一笔”看似没生效）。
     */
    private var workingId: String = cardId ?: Ids.newId()

    /** 其他卡片（关联选择用）。 */
    val allCards: StateFlow<List<BoardCard>> = repo.observeCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前草稿对应的卡片 id（新建时是预分配的 id）。 */
    val workingCardId: String get() = workingId

    /**
     * 「使用全屏视图新建」设置（用户 2026-09-19）：
     * 新建卡片时是否自动打开全屏文本编辑浮层。
     */
    val newCardFullscreen: StateFlow<Boolean> = settingsStore.settings
        .map { it.boardNewCardFullscreen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 当前「卡片字号」档（与主页卡片、聚焦展示页**同一来源**）。
     *
     * 用户 2026-09-22：全屏文本编辑页的标题/正文原来写成硬编码 16sp/15sp，
     * 而全屏展示页的字号来自用户设置（小 15/14、中 16/15、大 19/18）。
     * 只有设置恰好是「中号」时两页才恰好一致，其余档位下文字大小不同——
     * 这就是「两页外观不一样」的一个真因。
     * 改成从设置派生后，两页永久同步、不再靠「碰巧相等」。
     */
    val cardFontSizeKey: StateFlow<String?> = settingsStore.settings
        .map { it.boardCardFontSize }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 卡片是否紧凑模式（字号档位计算需要它）。 */
    val boardCompact: StateFlow<Boolean> = settingsStore.settings
        .map { it.boardCompact }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _draft = MutableStateFlow(
        BoardCardDraft(
            type = argType,
            // 新建卡片在**声明时就分配随机颜色**，不等 init 里的异步设置加载。
            // 否则进页头几帧预览是默认色，设置读回来后才换成随机色（闪一下）。
            // 「卡片随机颜色」开关从设置缓存的当前值同步读取——设置几乎总已加载完毕，
            // 因此关闭开关时不会先闪一个随机色再清掉。
            // 编辑（cardId != null）时下方会用预填/查库的真实颜色覆盖它。
            color = if (cardId == null && settingsStore.settings.value.boardRandomColor) {
                BoardColors.randomPreset()
            } else {
                null
            },
        ),
    )
    val draft: StateFlow<BoardCardDraft> = _draft.asStateFlow()

    init {
        // 首帧预填：白板传过来的卡片数据（如果有）。
        //
        // 只带卡片自身的字段，先让页面第一帧就是完整内容，
        // 不用等 Room 查询——编辑页的进场动画与内容同时到位，
        // 手感和新建一致。标签/附件/关联/提醒这些关联数据下方再补齐。
        if (cardId != null) {
            val p = BoardCardEntryBus.prefill.value?.takeIf { it.cardId == cardId }
            if (p != null) {
                _draft.value = BoardCardDraft(
                    loaded = true,
                    isNew = false,
                    type = BoardCardType.fromKey(p.typeKey),
                    title = p.title,
                    body = p.body,
                    color = p.color,
                    pinned = p.pinned,
                    secret = p.secret,
                    secretHint = p.secretHint,
                    widthMode = p.widthMode,
                    showDate = p.showDate,
                    imageLayout = p.imageLayout,
                    autoPin = p.autoPin,
                )
                // 用完即消费，避免下次进页（尤其是新建）误用旧数据。
                BoardCardEntryBus.consume()
            }
        }
    }

    val tags: StateFlow<List<BoardTag>> = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var existingCard: BoardCard? = null
    private var existingTodos: List<BoardTodoItem> = emptyList()

    /**
     * 本次是不是「新建时长按对勾 → 就地进更多设置」进来的。
     *
     * 只有这种情况，更多设置页的 X 才要**切回全屏文本编辑页**
     *（用户 2026-09-28）；从白板直接打开已有卡的更多设置时 X 仍是「离开」。
     */
    private var openSettingsFromNew = false

    /**
     * 本页（「更多设置」）**下面压着哪一层** —— 也就是"上一级是谁"。
     *
     * 用户 2026-09-30 定的规则（原话）：
     * > 卡片更多设置的上一级是也只能是全屏展示页/编辑页，
     * > 如果在更多设置内点保存更改就回到展示页，如果按退出就回到编辑页。
     *
     * ⇒ **不存在"回白板"这条路**。本页永远只是压在
     * 「全屏展示页」或「全屏编辑页」上面的一层。
     *
     * 与 [openSettingsFromNew] 的分工：
     *  · 这里答的是「退出回哪」（编辑页）；
     *  · [openSettingsFromNew] 额外答「这张卡是不是还没落库」（新建态要先落库）。
     *
     * 历史：2026-09-28 只给「新建长按」那一条入口做了"X 切回编辑页"，
     * 其余入口一律 `popBackStack()` → 掉回白板。用户当时报的就是这个，
     * 但因为只有一条路径做对了、又被"双击自动开全屏"顺带掩盖，所以看着像修好了。
     */
    private var settingsBackToEditor = false

    init {
        viewModelScope.launch {
            // 这些查询只依赖 cardId、彼此不依赖，必须**并行**。
            // 原先全部串行 await，一次 Room 往返叠一次，进页要等好几个往返，
            // 表现为「卡片数据延迟」（主页新建不需要查这些，所以没这个问题）。
            val settingsDeferred = async { settingsStore.settings.first() }
            val cardDeferred = async { cardId?.let { repo.observeCard(it).first() } }
            val card = cardDeferred.await()
            if (card != null) {
                val todosDeferred = async { repo.observeTodoItems().first() }
                val tagsDeferred = async { repo.observeCardTags().first() }
                val linksDeferred = async { repo.observeCardLinks().first() }
                val attachmentsDeferred = async { attachmentRepository.getOf(card.id) }
                val reminderDeferred = async { CardReminders.triggerOf(reminderRepository, card.id) }
                existingCard = card
                existingTodos = todosDeferred.await().filter { it.cardId == card.id }
                val tagId = tagsDeferred.await().firstOrNull { it.cardId == card.id }?.tagId
                val linked = linksDeferred.await().filter { it.cardId == card.id }.map { it.linkedCardId }
                val attachments = attachmentsDeferred.await()
                // CardReminders.triggerOf 已经返回"那一条或 null"
                val reminder = reminderDeferred.await()
                // 只**补齐关联数据**（标签/附件/关联/提醒），不重建整个草稿。
                // 因为首帧已经用预填数据（白板带过来的卡片字段）填过了，
                // 这里若整体覆盖，会把这期间用户已经开始输入的内容一并冲掉。
                _draft.value = _draft.value.copy(
                    loaded = true,
                    isNew = false,
                    tagId = tagId,
                    attachments = attachments,
                    linkedCardIds = linked,
                    reminderAtMillis = reminder?.toEpochMilli(),
                    // 卡片自身的图片样式（无预填时也保证能从库里补上）。
                    imageLayout = _draft.value.imageLayout ?: card.imageLayout,
                    // 动态置顶同理：预填不带时就靠这一步从库里补。
                    autoPin = _draft.value.autoPin ?: card.autoPin,
                    autoPinResolvedAt = card.autoPinResolvedAt,
                )
            } else {
                val settings = settingsDeferred.await()
                // 草稿恢复：「自动保存草稿」开启且存在上次未写完的草稿时，
                // 用它回填（优先于各项默认值），用户接着上次的内容继续写。
                val saved = if (settings.boardAutoDraft) boardDraftStore.awaitDraft() else null
                _draft.value = BoardCardDraft(
                    loaded = true,
                    isNew = true,
                    type = saved?.typeKey?.let { BoardCardType.fromKey(it) } ?: argType,
                    title = saved?.title.orEmpty(),
                    body = saved?.body.orEmpty(),
                    pinned = saved?.pinned ?: false,
                    secret = saved?.secret ?: false,
                    secretHint = saved?.secretHint.orEmpty(),
                    reminderAtMillis = saved?.reminderAtMillis,
                    showDate = saved?.showDate ?: settings.boardShowCreatedTime,
                    autoPin = saved?.autoPin,
                    widthMode = saved?.widthMode
                        ?: settings.boardDefaultWidth.takeIf { it != "auto" },
                    // 颜色：有草稿时用草稿的；否则按「卡片随机颜色」开关
                    // （声明时已决定，关闭时为 null）。
                    color = saved?.color ?: _draft.value.color,
                    // 图片样式：有草稿时用草稿的；否则取全局默认
                    // （「自动」对应 null，与编辑页的三态一致）。
                    imageLayout = saved?.imageLayout
                        ?: settings.boardDefaultImageLayout.takeIf { it != "auto" },
                    // 标签：草稿优先；否则用主页已筛选的标签（新建直接落在它下面）。
                    tagId = saved?.tagId ?: BoardCardEntryBus.newCardTagId.value.also {
                        // 用完即消费，避免下次进页误用。
                        BoardCardEntryBus.consumeNewCardTag()
                    },
                )
            }
        }
    }

    /**
     * 全屏文本编辑浮层是否打开。**唯一真值来源**。
     *
     * 2026-09-19 第二次修（上一版会白屏）：上一版把“打开”放在
     * Composable 的局部 state、又另写一条 effect 去“关”，两者互相驱动：
     * effect A 置 true → 编辑页本体被关掉；effect B（key 只有 workingCardId）首次执行时
     * 读到的还是 false → 立即调 closeAutoFullscreen() 把设置改回 false → effect A 重跑又把它关上。
     * 结果两层都不渲染 = **白屏**。
     *
     * 现在只保留一个状态、一个写入方：
     *  · 自动打开：进页决策完成后直接置 true（在 ViewModel 内，不经 Composable）；
     *  · 手动打开：编辑页右下的放大按钮调 [openFullscreenTextEdit]()；
     *  · 关闭：只有 [closeFullscreenTextEdit]()。
     *
     * 如此“开”与“关”都是单向写入，不存在互相触发的环。
     */
    private val _fullscreenTextEdit = MutableStateFlow(false)
    val fullscreenTextEdit: StateFlow<Boolean> = _fullscreenTextEdit.asStateFlow()

    /**
     * 全屏层是否应当**进场时就直接满不透明**（不播 0→1 淡入）。
     *
     * ## 为什么需要这个区分（首启闪烁的真正根因）
     *
     * 原本只有 `fullscreenTextEdit` 一个布尔量，而全屏层用
     * `animateFloatAsState` 从 0 播到 1（200ms 淡入）。
     *
     * 自动进全屏时，界面从未展示过，但动画仍从 0 开始 →
     * 那一瞬间 `fsProgress = 0`，编辑页的 alpha = `1 − 0 = 1`，
     * 于是编辑页**满不透明地闪一帧**，然后才被全屏层逐渐盖住。
     * 之前反复改门控（autoOpenPending / 渲染条件 / 时序）都没用，
     * 因为问题不在「什么时候渲染」，而在「渲染时动画从几开始」。
     *
     * 现在：自动进入时同步置 true，全屏层第一帧就是 fsProgress=1，
     * 编辑页第一帧 alpha 就是 0——**不存在那一帧**。
     * 手动点放大按钮仍走 0→1 淡入（那是用户主动操作，应当有过渡）。
     */
    private val _fullscreenInstant = MutableStateFlow(false)
    val fullscreenInstant: StateFlow<Boolean> = _fullscreenInstant.asStateFlow()

    /**
     * 「双击进来那一次自动开全屏」是否已经消费过 —— **只拦同一次，不拦下一次**。
     *
     * 为什么需要它：`focusBodyOnLoad` 这个导航参数在整次停留期间都是 true，
     * 而从全屏层退回本页会让 effect 重跑 —— 没有门闩就会立刻又弹回全屏（退不出去）。
     *
     * 为什么放在 ViewModel 而不是 `remember`：`remember` 的生命周期是「本页这一次停留」，
     * 置位后**再也不会复位**，于是这一次停留里再也无法双击进编辑（用户 2026-09-30 实测的 bug）。
     * 放在这里并在关闭全屏层时清掉，门闩就只生效一次。
     */
    private val _autoFocusConsumedCardId = MutableStateFlow<String?>(null)
    val autoFocusConsumedCardId: StateFlow<String?> = _autoFocusConsumedCardId.asStateFlow()

    fun markAutoFocusConsumed(cardId: String) {
        _autoFocusConsumedCardId.value = cardId
    }

    /**
     * 打开「更多设置」时登记：本页下面压着**全屏编辑页**。
     *
     * 退出（X / 系统返回）时要切回它 —— 见 [closeFromSettings]。
     * 三种进入方式都会调它：
     *  · 卡片墙双击 → 自动开全屏编辑层（`focusBody`）；
     *  · 新建卡片 → 自动开全屏编辑层；
     *  · 全屏展示页的「⋯」/ 就地编辑层的长按 → 先开全屏编辑层再进来。
     */
    fun markSettingsBacksToEditor() {
        settingsBackToEditor = true
    }

    /** 手动打开全屏文本编辑（编辑页右下放大按钮）——带动画。 */
    fun openFullscreenTextEdit() {
        _fullscreenInstant.value = false
        _fullscreenTextEdit.value = true
    }

    /**
     * 自动打开全屏文本编辑（新建卡片 + 设置已开）——**无动画**。
     *
     * 内容在打开前从未展示过，因此直接以满不透明状态出现才是正确的；
     * 播淡入会让底下的编辑页先露一帧。
     */
    private fun openFullscreenTextEditInstantly() {
        _fullscreenInstant.value = true
        _fullscreenTextEdit.value = true
        // 自动进全屏编辑 = 本页下面压着编辑页 ⇒ 退出去编辑页（用户 2026-09-30 规则）。
        markSettingsBacksToEditor()
        // 自动进全屏也算「自动聚焦已消费」，避免 focusBody 的 effect 再开一次。
        markAutoFocusConsumed(workingId)
    }

    /**
     * 关闭全屏文本编辑。
     *
     * 同时把自动置位标记消掉：用户已经看过这张新卡的全屏了，
     * 不应因为任何重组再次弹开（否则就是“关不掉”）。
     *
     * 并且**复位自动聚焦门闩**：这次的「双击进来」已经结束，
     * 下一次再双击必须还能进编辑（用户 2026-09-30 实测的 bug）。
     */
    fun closeFullscreenTextEdit() {
        _fullscreenTextEdit.value = false
        _autoFocusConsumedCardId.value = null
    }

    /**
     * 进页时的自动全屏决策：**每张新卡各判一次**。
     *
     * 关键：先等设置**首条真实值**与草稿都就绪再判定——不能在组合期读一个
     * 初始值就下结论（那正是“开关打开也不生效”的根因：
     * `settingsStore.settings` 的 `stateIn` 初值是 false，而设置是异步读；
     * 新建卡片的 `draft.loaded` 首帧就是 true —— 决策跌在空值上）。
     *
     * 用“已判定过的卡片 id”作守卫：`workingId` 每张新卡都不同，
     * 因此“保存并再记一笔”后的下一张会重新判定；同一张卡则只判一次。
     */
    private var autoFullscreenDecidedFor: String? = null

    /**
     * 进页自动全屏的判定是否尚未出结果（编辑页据此不渲染/不抢跑键盘）。
     *
     * **起点必须同步决定**（用户 2026-09-19：首启又闪了一下编辑页）：
     * 设置缓存几乎总是已加载好的，所以这里在**构造期同步读一次缓存**，
     * 只有在“缓存确实还没加载”时才退化成 pending（等异步）。
     * 若一味从 true 起步，首启（首次读库较慢）就会先空一帧、再冒出来 = 闪一下。
     */
    /**
     * **自动全屏判定是否还在进行**。
     *
     * 初值必须为 **true**：进页那一刻判定尚未完成（要等首次读设置库），
     * 这几帧里**不能画编辑页**，否则它会先露一下再被全屏层盖上
     * （用户反复反馈的「首启闪一下卡片编辑页」）。
     *
     * 曾经初值写成 false（且从未被置 true），于是这个门控形同虚设：
     * `draft.loaded && !autoOpenPending` 在草稿就绪的瞬间就成立、
     * 立即画出编辑页，随后异步判定才把全屏层淡入——就是那一帧闪烁。
     *
     * 两条判定路径（同步 / 异步）都必须在结束时调 [finishAutoOpenPending]，
     * 否则会永久停在白底。
     */
    private val _autoOpenPending = MutableStateFlow(true)
    val autoOpenPending: StateFlow<Boolean> = _autoOpenPending
    private fun finishAutoOpenPending() { _autoOpenPending.value = false }

    /**
     * 对当前草稿做一次自动全屏判定（每张卡只判一次）。
     *
     * 入站时调一次；「保存并再记一笔」换了新 id 后也要再调一次——
     * 否则第二张新卡不会自动进全屏（用户当初要的就是“新建就直接写”）。
     */
    /**
     * 结束判定：先定下全屏开关，再清 pending。
     *
     * **顺序很重要**：两个 StateFlow 的写入会各自触发一次重组。
     * 若先清 pending（此时全屏开关还是 false），就会出现一个
     * 「pending=false 且 fullscreen=false」的瞬间——
     * 编辑页以 alpha=1 被画出来，下一帧才被全屏层盖上（就是那一帧闪）。
     * 先打开全屏再清 pending，则编辑页第一次被渲染时 alpha 已经是 0。
     */
    private fun finishDecision(openFullscreen: Boolean) {
        if (openFullscreen) openFullscreenTextEditInstantly()
        finishAutoOpenPending()
    }

    private fun decideAutoFullscreenAsync() {
        viewModelScope.launch {
            // 草稿等待加**超时兜底**：若加载异常/卡住，不能把 pending 永久挂起。
            val draftDeferred = async {
                withTimeoutOrNull(3_000) { draft.first { it.loaded } }
            }
            val d = draftDeferred.await()
            // 同步路径已经判过了：**仍要清 pending**。
            // 不能直接 return —— pending 初值为 true，
            // 漏掉这一步会永久停在「判定中」的白底。
            if (autoFullscreenDecidedFor == workingId) {
                finishAutoOpenPending()
                return@launch
            }
            autoFullscreenDecidedFor = workingId
            // 只对**新建**卡片自动打开；编辑已有卡片不动。
            // 用户 2026-09-28：新建**一律**进全屏新建页
            //（原来由「使用全屏视图新建」设置决定，那个设置从此不再参与判定）。
            finishDecision(d != null && d.isNew)
        }
    }

    /**
     * 入站时能同步定下的判定（不进 pending）。
     *
     * 只处理“新建 + 开关已开 + 草稿已就绪”这一种情形：
     * 此时可以立刻决定进全屏，编辑页不需要先画一帧再被盖住。
     * 其余情形交给 [decideAutoFullscreenAsync] 兜底（它一定会清 pending）。
     */
    /**
     * 入站时能**同步**定下的判定（草稿已就绪即可下结论）。
     * 未就绪就交给 [decideAutoFullscreenAsync]（它一定会清 pending）。
     *
     * 用户 2026-09-28 起这里不再读任何设置：新建一律进全屏新建页，
     * 判定只看 `isNew` —— 顺带也消掉了「设置首帧是默认值、被误判为关闭」
     * 那类时序问题。
     */
    private fun decideAutoFullscreenSyncIfPossible() {
        // 草稿未加载完不敢下结论（isNew 还可能是默认值）；交给异步。
        if (!draft.value.loaded) return
        autoFullscreenDecidedFor = workingId
        finishDecision(draft.value.isNew)
    }

    init {
        // 先尝试同步判定（设置缓存已就绪时不产生任何空白帧），再挂异步兜底。
        decideAutoFullscreenSyncIfPossible()
        decideAutoFullscreenAsync()
    }

    fun update(transform: (BoardCardDraft) -> BoardCardDraft) {
        _draft.value = transform(_draft.value)
    }

    /** 载体条目 id 的前缀只在 `CardReminders` 定义一次，别在这里写字面量。 */
    private fun alarmIdOf(id: String): String = CardReminders.alarmIdOf(id)

    /** 新建卡片排序键：按设置放到列表顶部/底部。 */
    private suspend fun newSortIndex(): Double {
        val settings = settingsStore.settings.first()
        val cards = repo.observeCards().first()
        return if (settings.boardNewCardPosition == "BOTTOM") {
            (cards.maxOfOrNull { it.sortIndex } ?: 0.0) + 1.0
        } else {
            (cards.minOfOrNull { it.sortIndex } ?: 0.0) - 1.0
        }
    }

    private fun buildCard(d: BoardCardDraft, sortIndex: Double): BoardCard {
        val now = Instant.now()
        return BoardCard(
            id = existingCard?.id ?: workingId,
            type = d.type,
            title = d.title.ifBlank { null },
            body = d.body.ifBlank { null },
            color = d.color,
            pinned = d.pinned,
            secret = d.secret,
            secretHint = d.secretHint.takeIf { it.isNotBlank() },
            archived = existingCard?.archived ?: false,
            createdAt = existingCard?.createdAt ?: now,
            updatedAt = now,
            sortIndex = existingCard?.sortIndex ?: sortIndex,
            widthMode = d.widthMode,
            showDate = d.showDate,
            imageLayout = d.imageLayout,
            autoPin = d.autoPin,
            // 归位记录保留原值：规则/内容变了不该把「已沉下」重置，
            // 否则用户刚沉下的卡片会因为顺手改个字又浮上来。
            autoPinResolvedAt = existingCard?.autoPinResolvedAt,
        )
    }

    private suspend fun persist(d: BoardCardDraft): BoardCard {
        val card = buildCard(d, newSortIndex())
        // 卡片与标签是单标签关系：只保留一个仍存在的标签。
        val validTagIds = d.tagId
            ?.takeIf { id -> repo.observeTags().first().any { it.id == id } }
            ?.let { listOf(it) }
            ?: emptyList()
        repo.replaceCardWithRelations(card, existingTodos, validTagIds)
        repo.setCardLinks(card.id, d.linkedCardIds)
        syncCardReminder(card.id, card.title.orEmpty(), d.reminderAtMillis)
        return card
    }

    /**
     * 卡片提醒：挂一条不可见的载体条目（提醒的调度只认它）。
     *
     * ⚠️ 实现已抽到 `CardReminders.set` —— AI 工具走的是**同一个函数**，
     * 两边不会漂移。这里只做 millis ↔ Instant 的适配。
     */
    private suspend fun syncCardReminder(id: String, title: String, atMillis: Long?) {
        CardReminders.set(
            items = itemRepository,
            reminders = reminderRepository,
            cardId = id,
            title = title,
            at = atMillis?.let { Instant.ofEpochMilli(it) },
        )
    }

    /** 保存并返回。 */
    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            persist(_draft.value)
            // 卡片已正式落库，之前暂存的草稿不再需要，清掉。
            boardDraftStore.clear()
            onDone()
        }
    }

    /**
     * 「先提交，再就地进更多设置」——新建卡片长按对勾走这条（用户 2026-09-28）。
     *
     * ## 为什么不能靠 navigate
     *
     * 原来的写法是 `save { navigate(boardCard(cardId = workingCardId)) }`，
     * 即提交后再**导航到同一个路由**（只是参数不同）。这会造成两个问题：
     *  · 它在栈上又压了一层 `boardCard`，于是「更多设置」页的保存/返回
     *    只能弹一层，落回的是**下面那层的新建页（全屏编辑器还挂着）**，
     *    而不是白板首页 —— 用户看到的就是「点了保存修改却回去了编辑页、
     *    卡片也不在首页」（2026-09-28 报的真 bug）。
     *  · 新建页那一层并没有被释放，它的草稿仍是 `isNew = true`。
     *
     * ## 正确做法：不导航，就地切换视图
     *
     * 这一页本来就是「新建卡片 / 更多设置」二合一的（标题按 `isNew` 切）。
     * 所以只要把草稿从「新建」变成「已有卡片」，**同一页**就会渲染成更多设置视图：
     *  持久化 → `existingCard` 记为刚建的卡 → `isNew = false` → 关掉全屏编辑器。
     * 此后用户在更多设置里改任何东西，点保存走的就是普通的「更新已有卡片」+
     * `onBack` 回白板，栈上不再多一层。
     */
    fun commitAndOpenSettings() {
        viewModelScope.launch {
            val d = _draft.value
            val card = persist(d)
            // 从此这一页代表「刚建好的这张卡」：保存条会变成「保存修改」，
            // 落库走 update 而不是 insert。
            existingCard = card
            _draft.value = _draft.value.copy(isNew = false, loaded = true)
            // 记住「本次是从新建长按进来的」：左上 X 要据此切回全屏文本编辑页
            //（用户 2026-09-28：更多设置的 X = 回全屏编辑页，不是放弃也不只是离开）。
            openSettingsFromNew = true
            // 卡片已落库，草稿不再需要。
            boardDraftStore.clear()
            // 关掉全屏文本编辑层，露出下面的「更多设置」本体。
            _fullscreenTextEdit.value = false
        }
    }

    /**
     * 「更多设置」左上 X / 系统返回 —— **退出**。
     *
     * 用户 2026-09-30 的规则：**退出回「全屏编辑页」**（继续改标题/正文），
     * 不是回白板、也不是丢弃。见 [settingsBackToEditor] 上的完整说明。
     *
     * 实现：把全屏编辑层**重新打开**（它一直压在本页下面），
     * 然后本页照常退场 + 收尾。用户看到的效果是「回到编辑页」。
     *
     * ⚠️ 注意顺序：必须先打开编辑层再 `closeDraft`（后者会导航）。
     * 本页是独立路由，退场后下面露出的就是编辑层所在的白板页。
     */
    fun closeFromSettings(onDone: () -> Unit) {
        if (settingsBackToEditor) {
            // 切回全屏编辑层（仍停在这一页的栈上，卡片已建好、不再写库）。
            _fullscreenTextEdit.value = true
            // 新建态：卡片还没落库，退出应保留草稿而不是清掉（closeDraft 内部按 isNew 分支）。
        }
        // 白板页需要知道"露出编辑页"（本页退场后它自己恢复那一层）。
        if (!_draft.value.isNew) {
            BoardReturnBus.request(BoardReturnTarget.EDITOR, workingId)
        }
        openSettingsFromNew = false
        closeDraft(onDone)
    }

    companion object {
        // 返回意图信箱见 `BoardReturnBus`（顶层对象）。
        //
        // 为什么**不**放在这个伴生对象里：`BoardCardScreenV2` 这个名字同时是
        // ViewModel 类与 @Composable 函数，写 `BoardCardScreenV2.xxx()`
        // 会被解析成那个 @Composable → 编译错误（已踩）。
    }

    /**
     * 「更多设置」底部**保存更改**。
     *
     * 用户 2026-09-30 的规则：**保存回「全屏展示页」**。
     *
     * 实现：本页是独立路由，退场后白板页需要知道"要露出展示页"，
     * 所以先写意图再导航（见 [companion] 的说明）。
     *
     * 与「保存」语义的关系（**必须保持**）：已有卡片的落库点是本页底部保存条，
     * 这里调 [save] 就是那条路径本身；新建卡片则落库并回白板。
     */
    fun saveFromSettingsToDisplay(onDone: () -> Unit) {
        val d = _draft.value
        if (!d.isNew) {
            BoardReturnBus.request(BoardReturnTarget.DISPLAY, workingId)
        }
        save(onDone)
    }

    /** 保存并再记一笔：落库后清空标题/正文，保留类型/颜色/标签。 */
    fun saveAndAnother() {
        viewModelScope.launch {
            persist(_draft.value)
            // 这张卡已落库；紧接着是**另一张**新卡：
            // 必须换一个新 id。否则下一张会沿用同一个 id，把刚保存的卡覆盖掉
            // （表面看“再记一笔”没生效，实际是静默覆盖）。
            workingId = Ids.newId()
            // 旧草稿也一并清掉。
            boardDraftStore.clear()
            _draft.value = _draft.value.copy(title = "", body = "", reminderAtMillis = null)
            existingCard = null
            // 这已经是**另一张**新卡：重新判一次自动全屏（否则第二张不会进全屏）。
            // 先把开关关掉：这一张已经写完了，不该把上一张的全屏状态带给下一张。
            _fullscreenTextEdit.value = false
            // 重新进入「判定中」：与进页时同理，判定完成前不能画编辑页，
            // 否则第二张新卡也会先闪一下编辑页。
            // 判定完成后由 finishDecision 重新决定是否即时进入。
            _fullscreenInstant.value = false
            _autoOpenPending.value = true
            decideAutoFullscreenAsync()
        }
    }

    /** 复制当前卡片为新卡片并进入其编辑。 */
    fun duplicate(onDone: (String) -> Unit) {
        val d = _draft.value
        val newId = Ids.newId()
        val now = Instant.now()
        viewModelScope.launch {
            val copy = BoardCard(
                id = newId,
                type = d.type,
                title = d.title.ifBlank { null },
                body = d.body.ifBlank { null },
                color = d.color,
                pinned = d.pinned,
                secret = d.secret,
                secretHint = d.secretHint.takeIf { it.isNotBlank() },
                archived = false,
                createdAt = now,
                updatedAt = now,
                sortIndex = (repo.observeCards().first().minOfOrNull { it.sortIndex } ?: 0.0) - 1.0,
                widthMode = d.widthMode,
                showDate = d.showDate,
                imageLayout = d.imageLayout,
                autoPin = d.autoPin,
            )
            repo.replaceCardWithRelations(copy, emptyList(), listOfNotNull(d.tagId))
            onDone(newId)
        }
    }
    /** 新建标签：挂到 [parentId] 下（null = 顶层），排序键追加到同组末尾。 */
    fun createTag(name: String, parentId: String?) {
        viewModelScope.launch {
            val tags = repo.observeTags().first()
            val sortIndex = nextTagSortIndex(tags.filter { it.parentId == parentId }.map { it.sortIndex })
            repo.upsertTag(BoardTag(id = Ids.newId(), name = name, parentId = parentId, sortIndex = sortIndex))
        }
    }

    fun archive(onDone: () -> Unit) {
        viewModelScope.launch {
            cardId?.let { repo.setCardArchived(it, true) }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteCard(workingId)
            cleanAttachments(workingId)
            itemRepository.getById(alarmIdOf(workingId))?.let { itemRepository.deleteSubtree(it.id) }
            reminderRepository.deleteOf(alarmIdOf(workingId))
            onDone()
        }
    }

    /**
     * 关闭新建卡片：按设置决定「暂存草稿」还是丢弃。
     *
     * 暂存（开关开启且已写内容）：只把草稿**存起来**，**不**写入白板卡片墙——
     * 它还是一张没写完的卡片；下次新建时回填。
     * 丢弃（开关关闭或内容为空）：清理已复制进来的附件文件与草稿提醒，不留孤儿；
     * 同时把上一份旧草稿也清掉（用户已经明确不想要这张卡了）。
     *
     * 本方法同时被左上 X 按钮与系统返回键调用（见屏幕层的 BackHandler），
     * 因此开关对所有退出路径都生效。
     */
    /**
     * 关闭草稿并离开（X / 系统返回键）。
     *
     * **先导航、后收尾**（用户 2026-09-19：“不要让我等”）：
     * 旧写法把 `onDone()` 放在清理附件、删提醒载体、写草稿之后，
     * 这些都是 Room/IO 往返——用户必须等它们跑完才能看到页面开始退场。
     * 现在立即返回（导航马上开始），收尾工作在后台继续跑（viewModelScope，页面销毁后仍能完成）。
     * 注意：收尾必须用当前快照 `d`，不能在协程里再读 `_draft`（页面已退场、值可能已变）。
     */
    fun closeDraft(onDone: () -> Unit) {
        val d = _draft.value
        if (!d.isNew) {
            onDone()
            return
        }
        // 先把下一次“新建”会用到的状态重置掉，避免退场期间反复重置。
        onDone()
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val hasContent = d.title.isNotBlank() || d.body.isNotBlank()
            // 两种结果都要先清掉本次已复制进来的附件与草稿提醒：
            // 草稿只保留文字字段（附件不跨会话保留），丢弃则更不该留孤儿。
            cleanAttachments(workingId)
            itemRepository.getById(alarmIdOf(workingId))?.let { itemRepository.deleteSubtree(it.id) }
            reminderRepository.deleteOf(alarmIdOf(workingId))
            if (settings.boardAutoDraft && hasContent) {
                boardDraftStore.save(
                    BoardDraftPayload(
                        typeKey = d.type.key,
                        title = d.title,
                        body = d.body,
                        color = d.color,
                        pinned = d.pinned,
                        secret = d.secret,
                        secretHint = d.secretHint,
                        tagId = d.tagId,
                        widthMode = d.widthMode,
                        imageLayout = d.imageLayout,
                        showDate = d.showDate,
                        reminderAtMillis = d.reminderAtMillis,
                        autoPin = d.autoPin,
                    ),
                )
            } else {
                boardDraftStore.clear()
            }
            // （不再调 onDone()：导航已在本方法开头立即发出。）
        }
    }

    private suspend fun cleanAttachments(ownerId: String) {
        attachmentRepository.getOf(ownerId).forEach { a ->
            attachmentRepository.delete(a.id)
            attachmentStorage.delete(a)
        }
    }

    /** 批量导入（多选）。逐个导入并一次性刷新草稿，避免多次重组。 */
    fun addAttachments(uris: List<Uri>) {
        viewModelScope.launch {
            val added = uris.mapNotNull { uri ->
                val att = attachmentStorage.importAttachment(uri, AttachmentOwner.BOARD_CARD, workingId)
                    ?: return@mapNotNull null
                attachmentRepository.upsert(att)
                att
            }
            if (added.isNotEmpty()) {
                _draft.value = _draft.value.copy(attachments = _draft.value.attachments + added)
            }
        }
    }

    fun removeAttachment(att: Attachment) {
        viewModelScope.launch {
            attachmentRepository.delete(att.id)
            attachmentStorage.delete(att)
            _draft.value = _draft.value.copy(attachments = _draft.value.attachments.filterNot { it.id == att.id })
        }
    }

    fun setLinkedCards(ids: List<String>) = update { it.copy(linkedCardIds = ids) }

    fun setReminderAt(millis: Long?) = update { it.copy(reminderAtMillis = millis) }
}

/**
 * 新建 / 编辑卡片（V2）—— 设计稿 B0ZXj / CfTBN / L23fGq。
 * 同一页按 cardId 有无切换：新建标题「新建卡片」，编辑标题「编辑卡片」。
 */
@Composable
fun BoardCardScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    /** 打开后是否自动聚焦正文输入框（从卡片墙双击进入时为 true）。 */
    focusBodyOnLoad: Boolean = false,
    viewModel: BoardCardScreenV2ViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val allCards by viewModel.allCards.collectAsStateWithLifecycle()

    // 系统返回键/手势也必须走「关闭草稿」逻辑，否则开关只在点 X 时生效：
    // 按返回就直接 pop，未写完的内容无声无息地丢了。
    // 用 enabled 门控「草稿未加载完」的时刻，避免加载完成前按返回误触发一次暂存。
    //
    // 用 closeFromSettings：与左上 X **同一语义**（用户 2026-09-28）——
    // 若本次是「新建长按进更多设置」，返回键也要切回全屏编辑页。
    // 注：全屏编辑层显示时它自己的 BackHandler（后注册）优先，不会走到这里。
    androidx.activity.compose.BackHandler(enabled = draft.loaded) {
        viewModel.closeFromSettings(onBack)
    }

    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var tagMenuOpen by remember { mutableStateOf(false) }
    // 「本次打开」签到，交给 TagMenuPresenter 的 resetKey。
    //
    // 不传它会怎样：presenter 内部靠 key(resetKey) 重建位置动画状态，
    // 让菜单以当前锚点为初值（即原地出现）。resetKey 恒为 0 时 key 不重建，
    // 菜单会从**上一次的位置**平滑滑到本次锚点——即「从上次消失的地方被拉过来」。
    // 用 remember(open) 派生、由「上次可见状态」守卫，无副作用、无竞态。
    var tagMenuEpoch by remember { mutableIntStateOf(0) }
    var tagMenuShown by remember { mutableStateOf(false) }
    if (tagMenuOpen != tagMenuShown) {
        tagMenuShown = tagMenuOpen
        if (tagMenuOpen) tagMenuEpoch++
    }
    var tagMenuParentId by remember { mutableStateOf<String?>(null) }
    var tagMenuAnchorKey by remember { mutableStateOf<String?>(null) }
    var tagChipsBottomPx by remember { mutableFloatStateOf(0f) }
    val tagPillLeftPx = remember { mutableStateMapOf<String, Float>() }
    var createTagDialogOpen by remember { mutableStateOf(false) }
    var createTagName by remember { mutableStateOf("") }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var secretHintOpen by remember { mutableStateOf(false) }
    // （本页不再有文本输入框：标题/正文一律在全屏页里编辑，
    //   所以这里没有焦点请求器、也不自动弹键盘。用户 2026-09-28。）
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var linkPickerOpen by remember { mutableStateOf(false) }
    var reminderDateOpen by remember { mutableStateOf(false) }
    // 动态置顶配置面板是否打开。
    var autoPinPanelOpen by remember { mutableStateOf(false) }
    var reminderTimeOpen by remember { mutableStateOf<java.time.LocalDate?>(null) }
    // —— 三点菜单（用户 2026-09-28：复制 / 导出 / 分享 / 更多）——
    val menuScope = androidx.compose.runtime.rememberCoroutineScope()
    var shareDraft by remember { mutableStateOf(false) }

    // 全屏图片查看：待查看的全部图片路径 + 起始下标（null = 关闭）。
    var fullscreenImages by remember { mutableStateOf<List<String>?>(null) }
    var fullscreenStart by remember { mutableStateOf(0) }
    // 全屏文本编辑（用户 2026-09-19）：把卡片全屏展示界面复制一份，
    // 标题/正文换成可编辑输入，去掉设置按钮；关闭后回到本编辑页（内容已写回草稿）。
    // 全屏文本编辑开关：**只读 ViewModel**（唯一真值来源）。
    // 上一版用本地 remember 开关 + 另一条 effect 去关，两者互相触发导致白屏（详见 ViewModel 注释）。
    val fullscreenTextEdit by viewModel.fullscreenTextEdit.collectAsStateWithLifecycle()
    val autoOpenPending by viewModel.autoOpenPending.collectAsStateWithLifecycle()
    // 是否应当**直接满不透明出场**（自动进入），而非从 0 淡淡入。
    // 详见 ViewModel.fullscreenInstant 的说明：它是首启闪烁的真正解法。
    val fullscreenInstant by viewModel.fullscreenInstant.collectAsStateWithLifecycle()
    // 卡片字号档（与主页卡片、全屏展示页同一来源）。
    // 全屏编辑页必须用它，不能写硬编码字号——否则字号设置不是「中号」时
    // 两页的文字大小就不同（用户 2026-09-22：「还是不一样」的真因之一）。
    val cardFontSizeKey by viewModel.cardFontSizeKey.collectAsStateWithLifecycle()
    val boardCompact by viewModel.boardCompact.collectAsStateWithLifecycle()
    // 全屏层的「在场进度」（0=不在，1=完全在场）——**进出都必须连续**（用户 2026-09-19 强调）。
    //
    // 为什么需要它：`fullscreenTextEdit` 是个布尔量，直接 `if (它)` 让全屏层出现/消失
    // 必然是硬切（节点一次性挂上/摘掉，没有中间帧）。这正是「从全屏回新建页是硬切」的根因。
    //
    //
    // **自动进入不能依赖 animationSpec**（0.1.87 真因）：
    // `animateFloatAsState` 在首次以某状态组合时，第一帧总是从**当前值（0）**开始，
    // `snap()` 只决定「要不要动画」、**不改变起始值**。
    // 日志实测：`pending=false` 的那一帧 `instant=true` 但 `fsProgress=0.0`，
    // 编辑页 alpha=1−0=1 → 满不透明地露一帧，下一帧才变 1。
    // 所以 instant 时必须**绕过动画**，直接让进度等于目标值。
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (fullscreenTextEdit) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (fullscreenTextEdit) 200 else 160,
            easing = if (fullscreenTextEdit) VMotion.Glide else VMotion.Accelerate,
        ),
        label = "fullscreenTextEditProgress",
    )
    // 自动进入：**直接取目标值**（不取动画值）；其余情形用动画值。
    val fsProgress = if (fullscreenInstant && fullscreenTextEdit) {
        1f
    } else {
        animatedProgress.coerceIn(0f, 1f)
    }
    // 挂载条件用「进度未归零」而不是布尔量：退场期间仍然渲染，才能把淡出播完。
    val fullscreenMounted = fsProgress > 0.001f
    // 卡片正文的富文本编辑状态。
    //
    // **页面级单一真值**：输入框与键盘上方的工具栏共用同一个对象。
    //
    // 旧写法是由 BodyInput 在**组合期**通过回调把它传回父层
    //（`onMarkdownState?.invoke(mdState)`），父层存成 nullable 再交给工具栏。
    // 那条链有结构性风险：工具栏可能拿到一个已被替换的旧实例，
    // 于是「点击改的是一个孤儿对象、输入框读的是另一个」——表现就是按钮点了没反应。
    // 现在由父层创建并同时下发给两处，二者**必然是同一个对象**，
    // 从结构上排除「改了一个、另一个没变」的可能。
    val bodyMdState = remember { RichEditState.of(draft.body) }
    // 双击卡片（`focusBodyOnLoad`）进来 = 用户想直接写字：
    // 本页已经没有文本输入框了，所以**直接打开全屏编辑页**（用户 2026-09-28）。
    //
    // ## `focusDoneFor` 为什么从 `remember` 改成「握在 ViewModel 手里」
    //
    // 原来这里是 `var focusDoneFor by remember { mutableStateOf<String?>(null) }`，
    // 门控「每张卡只自动进一次」——是为了拦住「从全屏退回来时本 effect 重跑、
    // 又立刻弹回全屏、等于退不出去」。
    //
    // 但 `remember` 的生命周期是**本页这一次停留**，它带来一个用户实测到的 bug：
    //   详情 → 编辑 → 更多设置 → 退出回到详情
    // `focusDoneFor` 已经被置成本卡 id 且**再也不会复位**，于是这一次停留期间
    // **再也无法通过双击进入编辑**（effect 每次都提前 return）。
    // 用户 2026-09-30 报的就是这个。
    //
    // 正确做法：把这个门闩交给 ViewModel，并在**真正离开本页 / 关闭全屏层**时清掉。
    // 这样它只拦「同一次自动进入的重跑」，不会污染后续的每一次双击。
    var focusDoneFor = viewModel.autoFocusConsumedCardId.collectAsStateWithLifecycle().value
    LaunchedEffect(draft.loaded, focusBodyOnLoad) {
        if (!draft.loaded || !focusBodyOnLoad) return@LaunchedEffect
        // 已经开着全屏（新建时自动进全屏）就不再进一次。
        if (viewModel.fullscreenTextEdit.value) return@LaunchedEffect
        if (focusDoneFor == viewModel.workingCardId) return@LaunchedEffect
        viewModel.markAutoFocusConsumed(viewModel.workingCardId)
        viewModel.openFullscreenTextEdit()
        viewModel.markSettingsBacksToEditor()
    }
    // 「使用全屏视图新建」：新建卡片时**直接**打开全屏文本编辑，不先闪一下编辑页。
    //
    // 判定在 ViewModel（`init` 里的并行等待 + `fullscreenTextEdit`）完成：
    // 它能等到设置的**首条真实值**（不是 stateIn 的初值 false），也能等到草稿加载完成。
    // 这里不再做任何判定，也不再另写“回写”的 effect——上一版正是因为
    // “开”与“关”分属两处、互相触发而白屏。

    val attachmentLauncher = rememberLauncherForActivityResult(
        // 多选：一次可选多张图片/文件。返回列表，逐个导入。
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.addAttachments(uris) }

    Box(Modifier.fillMaxSize()) {
        // 数据未就绪前不渲染编辑界面：
        //
        // 这个页面的草稿是 init 里异步加载的（Room 查询）。若直接渲染，会先画出
        // 一个初始空草稿（空标题/空正文、底部也没有保存条，因为保存条门控在
        // draft.loaded），等数据到了再填上——表现为进页先闪一下空状态、
        // 卡片数据延迟、保存按钮慢半拍。
        //
        // 这里直接门控整个界面：未加载完只铺背景色，与上一页同色，
        // 数据到位后一次性画出完整页面，达到「无缝直接进入」的观感。
        // 编辑页与全屏层是**两个独立分支**（2026-09-19 第五次修的结构约定）：
        //  · 本分支：草稿就绪且未开全屏 → 编辑页；
        //  · `else`：只有“草稿未就绪”才铺空白底。
        // 全屏层**必须放在这个 if/else 之外、Box 的最后**（见下方）——
        // 上一版把它塞在这个分支**里面**，而分支条件是
        // `!fullscreenTextEdit`，于是全屏开的时候分支为假 → 走 `else` 只画白底，
        // 全屏层根本没机会渲染 = **白屏**。
        // 编辑页**始终挂载**（只要草稿就绪），可见度由「1 − 全屏进度」驱动。
        //
        // 这是消除硬切的关键：若仍写成 `if (!fullscreenTextEdit)`，
        // 全屏层淡出时编辑页会**瞬间**满不透明地冒出来 —— 就是用户看到的硬切。
        // 现在两页共用同一条时间轴：全屏进度 1→0 的同时，编辑页 0→1 淡入，交替连续。
        //
        // 用 alpha 而非 if：节点始终在树上，不会重建子树（也避免输入框内容被重置）。
        // 编辑页**在“自动全屏判定出结果前”不渲染**（用户 2026-09-19：首启会先闪一下编辑页）。
        // 原因：判定要等 `settingsStore.settings.first()`，而首启时那是**首次读库**，
        // 会晚好几帧；这几帧里编辑页已经画出来了 → 闪一下。
        // 用 autoOpenPending（判定进行中）门控，判定完成后再决定画编辑页还是全屏层。
        if (draft.loaded && !autoOpenPending) {
        Column(
            Modifier
                .fillMaxSize()
                .background(VColors.bg)
                // 全屏层完全在场时（fsProgress=1）编辑页不可见；退出时随进度淡入。
                .graphicsLayer { alpha = (1f - fsProgress).coerceIn(0f, 1f) }
                // 被全屏层盖住时不要参与交互（否则会与全屏层的输入框争点击/焦点）。
                .then(if (fsProgress > 0.001f) Modifier.pointerInput(Unit) {} else Modifier)
                .statusBarsPadding(),
        ) {
            VTopBarClose(
                title = if (draft.isNew) "新建卡片" else "更多设置",
                // X 与系统返回同一语义：若是「新建长按进来的」，切回全屏编辑页
                //（用户 2026-09-28）；否则仍是「离开」。
                onClose = { viewModel.closeFromSettings(onBack) },
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    // （本页没有输入框，不需要为键盘工具栏预留高度了。用户 2026-09-28。）
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 20.dp,
                        bottom = 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // （卡片类型选择已移除：卡片不再分类型，标签承担分类职责。）

                // （原来这里是「卡片预览」块：日期行 + 宽度切换 + 标题/正文输入 +
                //   标签行 + 右下"进全屏文本编辑"按钮。用户 2026-09-28 大改：
                //   本页只做「更多设置」，文本编辑一律走全屏页 —— 整块删除；
                //   宽度切换挪到下面「显示」区顶部。）

                // 标签（单标签；面包屑 + 逐级下拉，对齐设计稿 B0ZXj）
                FieldLabel("标签")
                val tagChain = tagPathChain(tags, draft.tagId)
                // 与白板主页标签栏**完全同一套结构 + 箭头规则**（直接对照主页实现抄）：
                //   - 「全部」永远在最前，始终存在（它就是“未选标签”这个状态本身）；
                //   - 后面跟当前标签链（面包屑）。
                //   箭头：仅“当前级”（未选时=「全部」，已选时=链尾标签）有箭头，
                //         菜单未开时向右、已开时向下；其余上级胶囊无箭头。
                val tagPills = buildList {
                    add(
                        TagPillSpec(
                            key = TAG_PILL_ROOT_KEY,
                            label = "全部",
                            chevron = if (tagChain.isEmpty()) {
                                if (tagMenuOpen) TagPillChevron.Down else TagPillChevron.Right
                            } else {
                                TagPillChevron.None
                            },
                        ),
                    )
                    tagChain.forEach { tag ->
                        add(
                            TagPillSpec(
                                key = tag.id,
                                label = tag.name,
                                chevron = if (tag.id == draft.tagId) {
                                    if (tagMenuOpen) TagPillChevron.Down else TagPillChevron.Right
                                } else {
                                    TagPillChevron.None
                                },
                            ),
                        )
                    }
                }
                // 与白板主页共用同一份退场维护，保证两处标签栏表现一致。
                val renderedTagPills = rememberPillsWithExiting(tagPills)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.weight(1f, fill = false)) {
                        TagPillRow(
                            pills = renderedTagPills,
                            onPillClick = { index ->
                                // 与主页标签栏**完全同一套逻辑**：
                                //   - 点末尾（当前级）→ 切换菜单开关；
                                //   - 点前面的上级胶囊 → 回到那一级（不展开菜单）。
                                val pill = tagPills[index]
                                if (index == tagPills.lastIndex) {
                                    if (tagMenuOpen) {
                                        tagMenuOpen = false
                                    } else {
                                        // 菜单列出「当前所在层级」的子标签（与主页一致）。
                                        // 当前级就是链尾：未选标签时是「全部」→ 顶级（parentId = null）。
                                        tagMenuParentId = draft.tagId
                                        tagMenuAnchorKey = pill.key
                                        tagMenuOpen = true
                                    }
                                } else {
                                    // 点上级胶囊：回到那一级（该胶囊成为当前标签），
                                    // 并展开该级的菜单——菜单列出这个标签的子级（与主页一致）。
                                    val backId = if (pill.key == TAG_PILL_ROOT_KEY) null else pill.key
                                    viewModel.update { it.copy(tagId = backId) }
                                    tagMenuParentId = backId
                                    tagMenuAnchorKey = pill.key
                                    tagMenuOpen = true
                                }
                            },
                            onPillLeft = { key, left -> if (tagPillLeftPx[key] != left) tagPillLeftPx[key] = left },
                            onBottom = { bottom -> if (abs(bottom - tagChipsBottomPx) > 0.5f) tagChipsBottomPx = bottom },
                        )
                    }
                    // 清除已选标签：只在确实选了标签时出现。
                    if (draft.tagId != null) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .background(VColors.surface2, CircleShape)
                                .vPressable(scaleDown = 0.9f) {
                                    viewModel.update { it.copy(tagId = null) }
                                    // 同时收起菜单并清掉锚点：
                                    // 否则菜单会悬在原地（它的锚点胶囊已消失），
                                    // 且因锚点失效连“点空白关闭”的蒙层也对不上。
                                    tagMenuOpen = false
                                    tagMenuParentId = null
                                    tagMenuAnchorKey = null
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.X, contentDescription = "清除标签", modifier = Modifier.size(13.dp), tint = VColors.ink3)
                        }
                    }
                }

                // 卡片颜色
                FieldLabel("卡片颜色")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorSwatch(VColors.surface, draft.color == BoardColors.WHITE, onClick = { viewModel.update { it.copy(color = BoardColors.WHITE) } })
                    ColorSwatch(VColors.accentSoft, draft.color == BoardColors.ACCENT, onClick = { viewModel.update { it.copy(color = BoardColors.ACCENT) } })
                    ColorSwatch(VColors.amberSoft, draft.color == BoardColors.AMBER, onClick = { viewModel.update { it.copy(color = BoardColors.AMBER) } })
                    ColorSwatch(VColors.lilacSoft, draft.color == BoardColors.LILAC, onClick = { viewModel.update { it.copy(color = BoardColors.LILAC) } })
                    ColorSwatch(VColors.roseSoft, draft.color == BoardColors.ROSE, onClick = { viewModel.update { it.copy(color = BoardColors.ROSE) } })
                    CustomSwatch(
                        selected = BoardColors.isCustom(draft.color),
                        onClick = { colorPickerOpen = true },
                    )
                }

                // 图片展示样式（每张卡片自己的设置）。
                // **只在插入了图片后才出现**：没有图片时这个选项无意义，
                // 常显会让编辑页变冗长（用户 2026-09-18 要求）。
                if (draft.attachments.any { it.isImage }) {
                    FieldLabel("图片展示样式")
                    ImageLayoutToggle(current = draft.imageLayout) { next ->
                        viewModel.update { it.copy(imageLayout = next) }
                    }
                }

                // 快捷添加：附件 / 关联 / 提醒
                FieldLabel("快捷添加")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(Lucide.Paperclip, "附件", Modifier.weight(1f)) {
                        attachmentLauncher.launch(arrayOf("*/*"))
                    }
                    QuickAction(Lucide.Link2, "关联", Modifier.weight(1f)) { linkPickerOpen = true }
                    QuickAction(Lucide.Bell, "提醒", Modifier.weight(1f)) { reminderDateOpen = true }
                }
                // 附件区：
                //  - 图片：一排 3 个的缩略网格（1:1），超出转行；点击放大。
                //  - 非图片：每张一行「图标 + 文件名」。
                // 编辑页是管理图片的地方，所以固定用缩略网格，不受卡片自身的
                // imageLayout 影响（那个只决定卡片在主页/聚焦怎么画）。
                val imageAttachments = draft.attachments.filter { it.isImage }
                val otherAttachments = draft.attachments.filterNot { it.isImage }
                // 图片区**改用共用组件 CardImageBlock**（展示页与编辑页同一画法）。
                //
                // 用户 2026-09-29：这里曾是手写的「固定 84dp 方块、整排靠左」，
                // 导致同一张卡片「展示页三列铺满、进编辑缩到左边」（0.1 后期回归）。
                // CardImageBlock 的 grid 模式用 weight(1f) 三列**横向占满** —— 即用户要的效果；
                // 且它本就为「两页共用」而抽取（见其 KDoc），这里改用它是回到既有约定。
                // 移除按钮通过 overlay 挂点保留（共用画法不等于砍功能）。
                if (draft.attachments.isNotEmpty()) VDividerFull()
                CardImageBlock(
                    images = imageAttachments,
                    storage = viewModel.attachmentStorage,
                    // 编辑页是管理图片的地方：固定用缩略网格，不受卡片自身 imageLayout 影响。
                    imageLayout = "grid",
                    onOpen = { att ->
                        val imgs = draft.attachments.filter { it.isImage }.map { it.storedPath }
                        fullscreenImages = imgs
                        fullscreenStart = imgs.indexOf(att.storedPath).coerceAtLeast(0)
                    },
                    gap = 8.dp,
                    overlay = { att ->
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(22.dp)
                                .background(VColors.surface.copy(alpha = 0.9f), CircleShape)
                                .vPressable(scaleDown = 0.85f) { viewModel.removeAttachment(att) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.X, "移除附件", Modifier.size(12.dp), tint = VColors.ink2)
                        }
                    },
                )
                otherAttachments.forEachIndexed { index, att ->
                    // 图片与第一个文件之间、文件与文件之间都要有线（设计稿的 Card Divider）。
                    if (index > 0 || imageAttachments.isNotEmpty()) VDividerFull()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (att.isAudio) Lucide.Music else Lucide.FileText,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = VColors.ink2,
                        )
                        VText(att.displayName, VTypo.caption12, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
                        // 类型 · 大小（设计稿的 File Meta 行，例如「PDF · 2.4 MB」）。
                        VText(
                            attachmentMetaText(att.sizeBytes, att.mimeType),
                            VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                            color = VColors.ink3,
                        )
                        Box(
                            Modifier.size(24.dp).vPressable(scaleDown = 0.85f) { viewModel.removeAttachment(att) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.X, "移除附件", Modifier.size(13.dp), tint = VColors.ink3)
                        }
                    }
                }
                if (draft.linkedCardIds.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(VColors.accentSoft, RoundedCornerShape(12.dp))
                            .vPressable(scaleDown = 0.98f) { linkPickerOpen = true }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Lucide.Link2, null, Modifier.size(14.dp), tint = VColors.accent)
                        VText("已关联 ${draft.linkedCardIds.size} 张卡片", VTypo.caption12, color = VColors.accent, maxLines = 1)
                    }
                }
                if (draft.reminderAtMillis != null) {
                    val z = java.time.Instant.ofEpochMilli(draft.reminderAtMillis!!).atZone(java.time.ZoneId.systemDefault())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(VColors.accentSoft, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Lucide.AlarmClock, null, Modifier.size(14.dp), tint = VColors.accent)
                        VText(
                            "提醒：${z.monthValue}月${z.dayOfMonth}日 %02d:%02d".format(z.hour, z.minute),
                            VTypo.caption12,
                            color = VColors.accent,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier.size(24.dp).vPressable(scaleDown = 0.85f) { viewModel.setReminderAt(null) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.X, "清除提醒", Modifier.size(13.dp), tint = VColors.accent)
                        }
                    }
                }

                // 显示
                FieldLabel("显示")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(VColors.surface, RoundedCornerShape(16.dp))
                        .border(1.dp, VColors.line, RoundedCornerShape(16.dp))
                        .padding(bottom = 8.dp),
                ) {
                    // 卡片宽度：原来挂在预览块日期行右侧，预览块删除后按用户 2026-09-28
                    // 要求挪到「显示」区顶部。副标题把当前模式写清楚，
                    // 不用点开就能看出现在是自动还是手动。
                    VRow(
                        title = "卡片宽度",
                        subtitle = when (draft.widthMode) {
                            "half" -> "固定半宽"
                            "full" -> "固定整行"
                            else -> "自动（字数多或有图则整行）"
                        },
                        leading = { RowBadge(Lucide.ChevronsRightLeft) },
                        trailing = {
                            WidthToggle(draft.widthMode) { next ->
                                viewModel.update { it.copy(widthMode = next) }
                            }
                        },
                    )
                    VDivider()
                    VRow(
                        title = "固定到白板顶部",
                        subtitle = "始终显示在最上方",
                        leading = { RowBadge(Lucide.Pin) },
                        trailing = { VSwitch(draft.pinned) { v -> viewModel.update { it.copy(pinned = v) } } },
                    )
                    VDivider()
                    // 动态置顶：规则驱动的临时浮起，与上面的手动置顶独立并存。
                    // 排在手动静止的下一行——两者是同一类概念（「浮到顶部」），
                    // 放在一起用户才能看出它们的区别是「永久 vs 按时段」。
                    VRow(
                        title = "动态置顶",
                        subtitle = autoPinSubtitle(draft.autoPin),
                        leading = { RowBadge(Lucide.Clock) },
                        trailing = { VChevron() },
                        onClick = { autoPinPanelOpen = true },
                    )
                    VDivider()
                    VRow(
                        title = "显示日期",
                        subtitle = "在卡片底部显示创建时间",
                        leading = { RowBadge(Lucide.Calendar) },
                        trailing = { VSwitch(draft.showDate) { v -> viewModel.update { it.copy(showDate = v) } } },
                    )
                    VDivider()
                    VRow(
                        title = "保密",
                        subtitle = "在主页面模糊该卡片内容",
                        leading = { RowBadge(Lucide.Lock) },
                        trailing = { VSwitch(draft.secret) { v -> viewModel.update { it.copy(secret = v) } } },
                    )
                    // 暗号 = 该卡片自己的「模糊块文案」，写给自己的一句提醒。
                    // 不是解锁密码：没有「输入暗号才显示」这回事。
                    //
                    // 用 AnimatedVisibility 而不是裸 if：
                    // 任何元素的出现/消失都必须有过渡，硬切会显得突兀。
                    // 这里做高度展开/收起 + 淡入淡出，分隔线一起参与，不会闪现。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = draft.secret,
                        enter = androidx.compose.animation.expandVertically(
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 220,
                                easing = VMotion.Emphasized,
                            ),
                        ) + androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 180,
                                easing = VMotion.Emphasized,
                            ),
                        ),
                        exit = androidx.compose.animation.shrinkVertically(
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 180,
                                easing = VMotion.Accelerate,
                            ),
                        ) + androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 140,
                                easing = VMotion.Accelerate,
                            ),
                        ),
                    ) {
                        Column {
                            VDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .padding(horizontal = 14.dp)
                                    .vPressable(scaleDown = 0.985f) { secretHintOpen = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                VText("暗号设置", VTypo.body, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
                                VText(
                                    draft.secretHint.takeIf { it.isNotBlank() } ?: "未设置 · 默认显示「已隐藏」",
                                    VTypo.caption,
                                    color = VColors.ink3,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = VColors.ink3)
                            }
                        }
                    }
                }

                // 操作（仅编辑态）
                if (!draft.isNew) {
                    FieldLabel("操作")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.surface, RoundedCornerShape(16.dp))
                            .border(1.dp, VColors.line, RoundedCornerShape(16.dp)),
                    ) {
                        VRow(
                            title = "复制卡片",
                            subtitle = "在新卡片中继续编辑",
                            leading = { RowBadge(Lucide.Copy) },
                            trailing = { Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = VColors.ink3) },
                            onClick = { viewModel.duplicate { newId -> navigate(V2Routes.boardCard(cardId = newId)) } },
                        )
                        VDivider()
                        VRow(
                            title = "归档卡片",
                            subtitle = "移出白板，内容保留",
                            leading = { RowBadge(Lucide.Archive) },
                            trailing = { Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = VColors.ink3) },
                            onClick = { confirmArchive = true },
                        )
                        VDivider()
                        VRow(
                            title = "删除卡片",
                            subtitle = "删除后不可恢复",
                            leading = { RowBadge(Lucide.Trash2, tint = VColors.rose) },
                            trailing = { Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = VColors.rose) },
                            onClick = { confirmDelete = true },
                        )
                    }
                }

                // 保存按钮改为底部浮动条（见下方 Box 内），这里留出等高的空间，
                // 免得最后一行内容被浮动条压住。
                Spacer(Modifier.height(if (draft.isNew) 108.dp else 84.dp))
            }
        }

        // ---------------------------------------------------------------- 底部固定操作条
        // 与底部 Tab 栏同款：上方渐隐遮罩 + 下方实底，滚动内容淡出到背景色而非硬边切断。
        //
        // （原来这里还有一条「编辑页的键盘工具栏」：只有本页还有正文输入框时才需要。
        //   现在文本编辑只在全屏页进行、那边自带工具栏，所以整块删除。用户 2026-09-28。）

        // 底部保存条同样属于编辑页：全屏层在场时一起淡出（否则会露在全屏页下方）。
        if (draft.loaded && fsProgress < 0.999f) {
            VBottomActionBar(
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = (1f - fsProgress).coerceIn(0f, 1f) },
            ) {
                // 用户 2026-09-30 拍板：**空内容不能保存，但是空标题可以**。
                //
                // ⚠️ 但这条规则**只该管"用内容创建卡片"，不该管"保存设置"**
                //（用户 2026-09-30 指出：更多设置页里没有任何必填项，变灰不合理）。
                //
                // 本页（更多设置）里的全部是**可选设置**：标签/颜色/宽度/置顶/显示日期/保密…
                // 正文与标题的编辑早已移到全屏编辑层，**本页没有可"必填"的字段**。
                // 所以：
                //   · 新建卡片（"添加到白板"）= 拿内容建一张卡 ⇒ 正文为空则该拦（应用规则）；
                //   · 已有卡片（"保存修改"）= 只是保存这些设置 ⇒ **永远可用**。
                VSaveButton(
                    text = if (draft.isNew) "添加到白板" else "保存修改",
                    enabled = !draft.isNew || draft.body.isNotBlank(),
                    // **保存更改 → 回全屏展示页**（用户 2026-09-30 规则）。
                    // 新建卡片没有"展示页"可回，仍是落库后回白板（save 内部按 isNew 分支）。
                    onClick = { viewModel.saveFromSettingsToDisplay(onBack) },
                )
                if (draft.isNew) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            // 「保存并再记一笔」与主保存同一条校验：正文为空也不该能落库。
                            .vPressable(
                                scaleDown = 0.98f,
                                enabled = draft.body.isNotBlank(),
                            ) { viewModel.saveAndAnother() },
                        contentAlignment = Alignment.Center,
                    ) {
                        VText("保存并再记一笔", VTypo.caption12, color = VColors.accent)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 全屏文本编辑浮层
        //
        // 位置：面板 Box 内、**排在底部操作条与键盘工具栏之后**。
        // 同层级时后置的节点画在上层：全屏页是“整页”，必须盖住它们，
        // 否则下端会露出一条「添加到白板」与一条 Markdown 工具栏。
        //
        // 与展示形态不同：这里是编辑页自己的全屏子页，不用拆到舍累外；
        // 同时保持「浮层打开时编辑页本体不渲染」。
        val tagMenuChildren = tags.filter { it.parentId == tagMenuParentId }.sortedBy { it.sortIndex }
        TagMenuPresenter(
            visible = tagMenuOpen,
            topPx = tagChipsBottomPx + with(density) { 16.dp.toPx() },
            leftPx = tagPillLeftPx[tagMenuAnchorKey ?: ""] ?: with(density) { 20.dp.toPx() },
            onDismiss = { tagMenuOpen = false },
            menuWidthPx = with(density) { 210.dp.toPx() },
            resetKey = tagMenuEpoch,
            // 遮罩从标签栏底边开始，不盖住标签栏：菜单打开时仍可直接点其他胶囊切级。
            scrimTopPx = tagChipsBottomPx,
        ) {
            TagDrillMenu(
                children = tagMenuChildren,
                onPick = { tag ->
                    viewModel.update { it.copy(tagId = tag.id) }
                    if (tags.none { it.parentId == tag.id }) {
                        tagMenuOpen = false
                    } else {
                        tagMenuParentId = tag.id
                        tagMenuAnchorKey = tag.id
                    }
                },
                onCreate = {
                    createTagName = ""
                    createTagDialogOpen = true
                },
            )
        }
        } else {
        // 仅“草稿未就绪”：只铺背景色（与上一页同色），避免闪出空状态。
        // 注意：这里**不能**再包含“开了全屏”的情形（上一版就是这么白屏的）。
        Box(Modifier.fillMaxSize().background(VColors.bg))
        }

        if (fullscreenMounted && draft.loaded) {
            BoardCardFullscreenTextEdit(
                // 外部进度：进场/退场的 alpha 由它统一驱动（组件内部不再自持 Animatable）。
                progress = fsProgress,
                // 图片区与展示页同一画法（用这份草稿自己的 imageLayout）。
                imageLayout = draft.imageLayout,
                // 三点下拉菜单：复制 / 导出 / 分享 / 更多。
                // 新建态按用户要求「四项都显示、点了先按当前草稿提交再执行」。
                menu = run {
                    val sizes = cardFontSizes(cardFontSizeKey, boardCompact)
                    com.phonlynn.oreplan.v2.components.CardMenuActions(
                        // 「复制」= 复制卡片文本到剪贴板（不是生成副本卡片）。
                        // 新建态尚未落库，直接复制当前草稿的标题+正文即可。
                        onCopy = {
                            com.phonlynn.oreplan.platform.export.CardLongImage.copyText(context, draft.title, draft.body)
                        },
                        onExport = {
                            com.phonlynn.oreplan.v2.components.exportCardLongImage(
                                context = context, scope = menuScope,
                                storage = viewModel.attachmentStorage,
                                title = draft.title, body = draft.body, attachments = draft.attachments,
                                background = boardCardColor(draft.color),
                                ink = VColors.ink, ink3 = VColors.ink3,
                                titleSp = sizes.title.value, bodySp = sizes.body.value,
                            )
                        },
                        onShare = { shareDraft = true },
                        onMore = {
                            // 已经压着「更多设置」页：新建态先落库，已有卡片直接关掉全屏层。
                            if (draft.isNew) {
                                viewModel.save { navigate(V2Routes.boardCard(cardId = viewModel.workingCardId)) }
                            } else {
                                viewModel.closeFullscreenTextEdit()
                            }
                        },
                    )
                },
                title = draft.title,
                body = draft.body,
                // 与编辑页共用同一个富文本状态：两份各自持有 doc 必然分叉，
                // 会出现「同样的操作在一处生效、在另一处不生效」。
                mdState = bodyMdState,
                color = boardCardColor(draft.color),
                tags = tags,
                tagId = draft.tagId,
                attachments = draft.attachments,
                attachmentStorage = viewModel.attachmentStorage,
                showDate = draft.showDate,
                // 顶部栏右侧标记与展示页逐项对应。
                pinned = draft.pinned,
                secret = draft.secret,
                autoPin = draft.autoPin,
                autoPinResolvedAt = draft.autoPinResolvedAt,
                // 与全屏展示页取同一档字号：两页永久同步。
                fontSizes = cardFontSizes(cardFontSizeKey, boardCompact),
                onTitleChange = { t -> viewModel.update { it.copy(title = t) } },
                onBodyChange = { b -> viewModel.update { it.copy(body = b) } },
                onOpenAttachment = { att ->
                    if (att.isImage) {
                        val imgs = draft.attachments.filter { it.isImage }.map { it.storedPath }
                        fullscreenImages = imgs
                        fullscreenStart = imgs.indexOf(att.storedPath).coerceAtLeast(0)
                    } else {
                        openAttachmentV2(context, viewModel.attachmentStorage, att) {}
                    }
                },
                // 对勾（短按）：
                //  · 已有卡片：**只关全屏层**，回到下面这页「更多设置」。
                //    标题/正文在编辑期间已实时写回草稿（onTitleChange/onBodyChange），
                //    落库仍由本页的保存条决定 —— 这是原本的设计。
                //  · 新建卡片（用户 2026-09-28：新建一律走全屏新建页）：
                //    直接**创建并回白板**。否则写完还得再点一次「添加到白板」，
                //    「全屏新建」就名不副实了。
                // ✓（短按）：**提交**并离开全屏层。
                //  · 新建卡片：**真正落库**（save）并回白板 ⇒ **必须校验"空内容不能保存"**。
                //    用户 2026-09-30 报的正是这里：新建卡片什么都不输入，✓ 仍是绿的，
                //    点下去会落一张空卡片。此前校验只加在了「更多设置」底部保存条上，
                //    漏掉了用户**最先看到**的这一颗 ✓。
                //  · 已有卡片：只关层，不落库（落库由下面的保存条负责）⇒ 永远可点。
                saveEnabled = !draft.isNew || draft.body.isNotBlank(),
                onSaveText = {
                    if (draft.isNew) viewModel.save(onBack) else viewModel.closeFullscreenTextEdit()
                },
                // 对勾（长按）= 提交 + 进「更多设置」：
                //  · 已有卡片：本层就压在那页上面，关掉即到。
                //  · 新建卡片：**就地切换** —— 提交这笔卡，然后让同一页变成「更多设置」
                //    （不导航，否则栈上会多一层，保存后回不到白板 —— 2026-09-28 修）。
                onSaveLongPress = {
                    if (draft.isNew) viewModel.commitAndOpenSettings() else viewModel.closeFullscreenTextEdit()
                },
                // X：直接退回首页（白板），内容走自动保存草稿规则。
                onExitHome = {
                    viewModel.closeFullscreenTextEdit()
                    viewModel.closeDraft(onBack)
                },
            )
        }
    }

    if (createTagDialogOpen) {
        VFloatingInputDialog(
            icon = Lucide.Hash,
            value = createTagName,
            onValueChange = { createTagName = it },
            onConfirm = {
                val name = createTagName.trim()
                if (name.isNotEmpty()) viewModel.createTag(name, tagMenuParentId)
                createTagName = ""
                createTagDialogOpen = false
            },
            onDismiss = { createTagDialogOpen = false },
            placeholder = if (tagMenuParentId == null) "标签名称" else "子标签名称",
            confirmText = "创建",
        )
    }

    if (colorPickerOpen) {
        ColorPickerDialog(
            current = draft.color,
            onPick = { hex -> viewModel.update { it.copy(color = hex) }; colorPickerOpen = false },
            onDismiss = { colorPickerOpen = false },
        )
    }

    if (secretHintOpen) {
        SecretHintDialog(
            initial = draft.secretHint,
            onConfirm = { value ->
                viewModel.update { it.copy(secretHint = value) }
                secretHintOpen = false
            },
            onDismiss = { secretHintOpen = false },
        )
    }

    if (linkPickerOpen) {
        LinkPickerDialog(
            cards = allCards,
            selfId = viewModel.workingCardId,
            selected = draft.linkedCardIds.toSet(),
            onConfirm = { viewModel.setLinkedCards(it); linkPickerOpen = false },
            onDismiss = { linkPickerOpen = false },
        )
    }

    if (autoPinPanelOpen) {
        AutoPinConfigDialog(
            initial = draft.autoPin,
            onConfirm = { rule ->
                viewModel.update { it.copy(autoPin = rule) }
                autoPinPanelOpen = false
            },
            onDismiss = { autoPinPanelOpen = false },
        )
    }
    if (reminderDateOpen) {
        VDatePickerDialog(
            initial = java.time.LocalDate.now(),
            onConfirm = { date ->
                reminderDateOpen = false
                reminderTimeOpen = date
            },
            onDismiss = { reminderDateOpen = false },
        )
    }
    reminderTimeOpen?.let { date ->
        VTimePickerDialog(
            initialMinute = 9 * 60,
            onConfirm = { minute ->
                val millis = date.atTime(minute / 60, minute % 60)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                viewModel.setReminderAt(millis)
                reminderTimeOpen = null
            },
            onDismiss = { reminderTimeOpen = null },
        )
    }

    fullscreenImages?.let { imgs ->
        VFullscreenImageViewer(
            paths = imgs,
            startIndex = fullscreenStart,
            storage = viewModel.attachmentStorage,
            onDismiss = { fullscreenImages = null },
        )
    }


    if (shareDraft) {
        val sizes = cardFontSizes(cardFontSizeKey, boardCompact)
        com.phonlynn.oreplan.v2.components.ShareChoiceDialog(
            onDismiss = { shareDraft = false },
            onShareImage = {
                shareDraft = false
                com.phonlynn.oreplan.v2.components.shareCardImage(
                    context = context, scope = menuScope,
                    storage = viewModel.attachmentStorage,
                    title = draft.title, body = draft.body, attachments = draft.attachments,
                    background = boardCardColor(draft.color),
                    ink = VColors.ink, ink3 = VColors.ink3,
                    titleSp = sizes.title.value, bodySp = sizes.body.value,
                )
            },
            onShareText = {
                shareDraft = false
                com.phonlynn.oreplan.platform.export.CardLongImage.shareText(context, draft.title, draft.body)
            },
        )
    }

    if (confirmArchive) {
        com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog(
            title = "归档卡片？",
            message = "卡片会从白板移除，内容保留，可在「白板设置 → 已归档」中恢复。",
            objectName = draft.title.ifBlank { "未命名卡片" },
            confirmText = "归档",
            onConfirm = { viewModel.archive(onBack); confirmArchive = false },
            onDismiss = { confirmArchive = false },
        )
    }

    if (confirmDelete) {
        com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog(
            title = "确认删除？",
            message = "删除后无法撤销，卡片内容将被永久移除。",
            objectName = draft.title.ifBlank { "未命名卡片" },
            onConfirm = { viewModel.delete(onBack); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}



// ---------------------------------------------------------------- 子组件

@Composable
private fun FieldLabel(text: String) {
    VText(text, VTypo.caption, color = VColors.ink3, maxLines = 1)
}

@Composable
private fun RowBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = VColors.ink2) {
    Box(
        Modifier.size(30.dp).background(VColors.bg, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(color, RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) VColors.accent else VColors.line, RoundedCornerShape(14.dp))
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = VColors.accent)
    }
}

@Composable
private fun CustomSwatch(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(
                Brush.linearGradient(
                    listOf(VColors.accentSoft, VColors.amberSoft, VColors.roseSoft, VColors.lilacSoft),
                ),
                RoundedCornerShape(14.dp),
            )
            .border(if (selected) 2.dp else 1.dp, if (selected) VColors.accent else VColors.line, RoundedCornerShape(14.dp))
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Lucide.Pencil, contentDescription = null, modifier = Modifier.size(16.dp), tint = VColors.ink2)
    }
}

// 保存按钮已收敛到共用件 `v2/components/VSaveButton.kt`（问题 #25）。
// 这里原先有一份私有 `SaveButton`（无禁用态），已删除。

// ---------------------------------------------------------------- 快捷添加 / 宽度

/** 卡片宽度切换（自动 → 半宽 → 整行），照设计稿的 28 高按钮。 */
@Composable
private fun WidthToggle(current: String?, onCycle: (String?) -> Unit) {
    val label = when (current) {
        "half" -> "半宽"
        "full" -> "整行"
        else -> "自动"
    }
    Row(
        Modifier
            // 现在只用在「显示」区的设置行里（底色是白容器），
            // 所以底色用 surface2（原来挂在彩色预览块上时用的半透明白）。
            .background(VColors.surface2, RoundedCornerShape(10.dp))
            .vPressable(scaleDown = 0.97f) {
                onCycle(
                    when (current) {
                        null -> "half"
                        "half" -> "full"
                        else -> null
                    },
                )
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Lucide.ChevronsRightLeft, null, Modifier.size(14.dp), tint = VColors.ink2)
        VText(label, VTypo.caption12, color = VColors.ink, maxLines = 1)
    }
}

/**
 * 图片展示样式切换：默认 → 横向填充 → 缩略网格 → 默认（循环）。
 * 「默认」= 跟白板全局设置里的默认图片样式。
 */
@Composable
private fun ImageLayoutToggle(current: String?, onCycle: (String?) -> Unit) {
    val label = when (current) {
        "grid" -> "始终网格"
        "fill" -> "始终填充"
        else -> "自动"
    }
    Row(
        Modifier
            .background(VColors.surface2, RoundedCornerShape(10.dp))
            .vPressable(scaleDown = 0.97f) {
                onCycle(
                    when (current) {
                        null -> "grid"
                        "grid" -> "fill"
                        else -> null
                    },
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.LayoutDashboard, null, Modifier.size(14.dp), tint = VColors.ink2)
        VText(label, VTypo.caption12, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
        VText(
            when (current) {
                "grid" -> "三栏平铺全部图片"
                "fill" -> "单张横向铺满"
                else -> "单图填充 / 多图网格"
            },
            VTypo.micro, color = VColors.ink3, maxLines = 1,
        )
    }
}

/** 快捷添加按钮：图标 + 文案的方块。 */
@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(64.dp)
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(14.dp))
            .vPressable(scaleDown = 0.96f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = VColors.ink2)
        Spacer(Modifier.height(5.dp))
        VText(label, VTypo.caption, color = VColors.ink2, maxLines = 1)
    }
}

/** 关联卡片选择弹窗：多选当前白板上的其他卡片。 */
@Composable
private fun LinkPickerDialog(
    cards: List<BoardCard>,
    selfId: String,
    selected: Set<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    // 搜索：卡片多时靠翻列表找太慢，这里按标题/正文/类型做即时过滤。
    var query by remember { mutableStateOf("") }
    val allCandidates = cards.filter { it.id != selfId }

    // 已选的排在最前，方便随时核对与取消；其余保持原顺序。
    val candidates = remember(allCandidates, query, chosen) {
        val q = query.trim()
        val matched = if (q.isBlank()) {
            allCandidates
        } else {
            allCandidates.filter { card ->
                val hay = buildString {
                    append(card.title.orEmpty())
                    append(' ')
                    // 正文取纯文字：落库是 JSON，直接拼会把结构字段也当成搜索内容。
                    append(plainTextOf(card.body))
                    append(' ')
                    append(card.type.label)
                }
                hay.contains(q, ignoreCase = true)
            }
        }
        matched.sortedByDescending { it.id in chosen }
    }

    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            VText("关联卡片", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(6.dp))
            VText("选择与这张卡片相关的内容，在聚焦页可以互相跳转。", VTypo.caption, color = VColors.ink3)
            Spacer(Modifier.height(12.dp))

            // 搜索框：**始终显示**。
            // 早先写成「候选多时才出现」（>6 张），但这样功能时有时无，
            // 用户体验不一致；现在恒显。
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(VColors.surface2, RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Lucide.Search, contentDescription = null, modifier = Modifier.size(15.dp), tint = VColors.ink3)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        VText("搜索卡片…", VTypo.caption12, color = VColors.ink3, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = VTypo.caption12.copy(color = VColors.ink, fontFamily = BodyFont),
                        singleLine = true,
                        cursorBrush = SolidColor(VColors.accent),
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(18.dp).vPressable(scaleDown = 0.9f) { query = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.X, contentDescription = "清除搜索", modifier = Modifier.size(13.dp), tint = VColors.ink3)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (chosen.isNotEmpty()) {
                VText(
                    "已选 ${chosen.size} 张",
                    VTypo.numMini.copy(fontSize = 11.sp),
                    color = VColors.accent,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
            }

            if (allCandidates.isEmpty()) {
                VText("白板上还没有其它卡片", VTypo.body, color = VColors.ink3)
            } else if (candidates.isEmpty()) {
                VText("没有匹配的卡片", VTypo.body, color = VColors.ink3)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(candidates, key = { it.id }) { card ->
                        val active = card.id in chosen
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .background(if (active) VColors.accentSoft else VColors.surface2, RoundedCornerShape(11.dp))
                                .vPressable(scaleDown = 0.97f) {
                                    chosen = if (active) chosen - card.id else chosen + card.id
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 类型气泡已移除。
                            VText(
                                card.title?.takeIf { it.isNotBlank() } ?: plainPreviewOf(card.body).ifBlank { "未命名卡片" },
                                VTypo.body,
                                color = VColors.ink,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            if (active) Icon(Lucide.Check, null, Modifier.size(15.dp), tint = VColors.accent)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(44.dp).background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) { close(onDismiss) },
                    contentAlignment = Alignment.Center,
                ) { VText("取消", VTypo.button, color = VColors.ink) }
                Box(
                    Modifier.weight(1f).height(44.dp).background(VColors.accent, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) { close { onConfirm(chosen.toList()) } },
                    contentAlignment = Alignment.Center,
                ) { VText("确定", VTypo.button, color = Color.White) }
            }
        }
    }
}

/**
 * 全屏文本编辑浮层（用户 2026-09-19）。
 *
 * 就是卡片全屏展示界面（BoardFocusOverlay 的 "fullscreen" 形态）的副本：
 *  - 白底铺满、顶部栏在日期前放一个卡片背景色小圆点；
 *  - 顶部栏只有**关闭**按钮，**没有设置（⋯）按钮**（用户明确要求去掉）；
 *  - 中间标题/正文换成可编辑输入，改动实时写回编辑页草稿；
 *  - 附件仍按只读展示（点图片可全屏查看）；标签只读展示。
 *
 * 关闭只退回本编辑页，不落库（保存仍走编辑页底部的「保存修改」）。
 *
 * 与展示形态的差别仅在「文本可编辑」与「无设置按钮」；
 * 结构/边距/配色全部对齐，保证两处观感一致。
 */
@Composable
internal fun BoardCardFullscreenTextEdit(
    /**
     * 在场进度 0..1，由**外部**（调用方）统一驱动。
     * 进场淡入、退场淡出都用它，确保两头都是连续过渡、不出现硬切。
     *
     * 为什么不让组件自己管：自持 Animatable 时，父层把它从树上旖掉的那一刻
     * 淡出就随之消失（没机会播完），表现就是硬切。
     * 现在父层以“进度未归零”为挂载条件，退场动画能完整跑完。
     */
    progress: Float,
    /**
     * 底色是否也跟着 [progress] 淡入。
     *
     * 卡片编辑页里必须是 false：底色要**首帧就不透明**，否则会闪出下面的编辑页。
     * 但从**全屏展示页**双击进入时正好相反 —— 两层底色相同、内容也几乎同位，
     * 让底色一起淡入才能形成真正的交叉淡化，不会出现「中间闪一下底色」。
     */
    fadeBackground: Boolean = false,
    title: String,
    body: String,
    /** 富文本编辑状态，由调用方持有并与编辑页共用。 */
    mdState: RichEditState,
    color: Color,
    tags: List<BoardTag>,
    tagId: String?,
    attachments: List<Attachment>,
    attachmentStorage: com.phonlynn.oreplan.platform.attachment.AttachmentStorage,
    showDate: Boolean,
    /** 是否已钉住（顶部栏标记，与展示页一致）。 */
    pinned: Boolean = false,
    /** 是否保密卡（顶部栏锁标记，与展示页一致）。 */
    secret: Boolean = false,
    /** 动态置顶规则与归位时刻（顶部栏时钟标记，与展示页一致）。 */
    autoPin: com.phonlynn.oreplan.domain.model.AutoPinRule? = null,
    autoPinResolvedAt: java.time.Instant? = null,
    /**
     * 卡片字号档。**必须与全屏展示页取同一值**（都由 `cardFontSizes` 派生）。
     * 默认值只是兼容旧调用的兑底，正常调用方应显式传入——
     * 硬编码字号会让字号设置不是该档时两页文字大小不一致。
     */
    fontSizes: CardFontSizes = CardFontSizes(16.sp, 15.sp),
    /** 卡片自身的图片样式（grid/fill/null）——图片区与展示页保持同一画法。 */
    imageLayout: String? = null,
    /** 三点下拉菜单的四个动作（复制/导出/分享/更多）；null = 不显示三点。 */
    menu: com.phonlynn.oreplan.v2.components.CardMenuActions? = null,
    /** 进页时把光标放到正文里的某一点（双击哪里放哪里）；坐标以正文首行顶边为 0。 */
    initialCursorAt: androidx.compose.ui.geometry.Offset? = null,
    /** 落位完成回调一次，调用方据此清掉请求。 */
    onInitialCursorPlaced: (() -> Unit)? = null,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    /**
     * ✓ 是否可点。
     *
     * **默认 true = 不改变任何现有调用方的行为**（护栏 A10：改共用组件一律用带默认值的
     * 可选参数，避免顺手改掉没被点名的页面）。
     *
     * 调用方按**提交语义**决定：
     *  · 「新建卡片」的 ✓ 会**真正落库** ⇒ 传 `body.isNotBlank()`（空内容不能建卡，用户口径）；
     *  · 「已有卡片」的 ✓ 只是提交改动 ⇒ 不传（永远可点）。
     */
    saveEnabled: Boolean = true,
    /** 对勾：保存**标题与正文**并回到调用方那一处（用户 2026-09-19）。 */
    onSaveText: () -> Unit,
    /**
     * 对勾**长按**：提交之后进「更多设置」页（用户 2026-09-28）。
     * 短按 = 只提交；长按 = 提交 + 进更多设置。
     *
     * 由它**自己**负责提交 —— 组件不会先调 [onSaveText] 再调它，
     * 否则新建卡片会被提交两次（一次回白板、一次进设置页）。
     */
    onSaveLongPress: (() -> Unit)? = null,
    /** 右上 X：直接退回首页（内容按「自动保存草稿」规则处理）。 */
    onExitHome: () -> Unit,
) {
    // 整页淡入淡出（与展示形态一致：不做位移/缩放）。
    // alpha 直接用外部传入的进度 —— 进场/退场同一个值驱动，不会出现两套口径。
    var closing by remember { mutableStateOf(false) }
    // 对勾的保存中转：先播退场动画，再保存并退回首页。
    var saving by remember { mutableStateOf(false) }
    // 本次保存是不是长按触发的（长按 = 提交后还要进更多设置）。
    var saveLongPress by remember { mutableStateOf(false) }
    // 悬浮信息栏的实测高度（px）：滚动内容的**顶部内衬**。
    // 内容铺满整页、内衬算在滚动内容里，文字才能从悬浮栏下面穿过。
    // 初值给估算（≈ 栏的可见高度）：否则第一帧内衬为 0，
    // 内容会先跳到顶、下一帧才落回栏下方 —— 看起来就是"闪一下"。
    val uiDensity = androidx.compose.ui.platform.LocalDensity.current
    var headerHeightPx by remember { mutableStateOf(with(uiDensity) { 96.dp.toPx() }) }
    // 打开即聚焦正文（这是「文本编辑」界面，用户点进来就是为了写字）。
    val bodyFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 正文的富文本编辑状态**由调用方传入**（与编辑页共用同一个对象），
    // 不在这里重新 remember——否则两份 doc 会各自演化。

    // 进场淡入已由外部 `progress` 驱动，这里**只管焦点与键盘**，
    // 不再自己播一次淡入（两套口径会互相叠加）。
    // 焦点与淡入并行：不能等淡入播完才聚焦，否则就是「进全屏后键盘迟迟不弹」。
    LaunchedEffect(Unit) {
        // 等到输入框真正布局完，再请求焦点：
        // 同一帧里 requestFocus() 对尚未完成布局的节点无效（焦点会被静默丢弃）。
        // 用 withFrameNanos 等一帧，实测比 yield() 可靠（这是 Compose 里等布局的标准做法）。
        androidx.compose.runtime.withFrameNanos { }
        bodyFocus.requestFocus()
        // 再等一帧后补一次 show()：部分设备上 IME 需要焦点稳定后才响应。
        androidx.compose.runtime.withFrameNanos { }
        keyboard?.show()
    }
    // 系统返回键 = X 的语义：退回首页。
    androidx.activity.compose.BackHandler { closing = true }
    // X / 系统返回键：发出退场请求。
    //
    // **退场动画不在这里播**（更不能硬切）：
    // 置位全屏开关→父层的 fsProgress 连续下降到 0，
    // 这期间本组件保持挂载（父层以“进度未归零”为条件），所以淡出能完整播完。
    // 同时卡片编辑页会随进度反向淡入，两层在同一个时间轴上交替。
    // 保存（对勾）也走同一条路：先提交文本，再由进度退场回到编辑页。
    LaunchedEffect(closing) {
        if (closing) onExitHome()
    }
    LaunchedEffect(saving) {
        if (saving) {
            // **只走一条路**：长按回调给了就交给它（它自己负责提交 + 进更多设置），
            // 否则退化成短按语义。两条都跑会出现「提交两次 + 白板与设置页来回跳」。
            if (saveLongPress && onSaveLongPress != null) onSaveLongPress.invoke() else onSaveText()
        }
    }

    val tagChain = tagPathChain(tags, tagId)
    // 该卡此刻是否处于动态浮起（与展示页/主页卡片同一判定）。
    val autoPinActive = remember(autoPin, autoPinResolvedAt) {
        com.phonlynn.oreplan.domain.model.AutoPinLogic.isActive(
            rule = autoPin,
            resolvedAt = autoPinResolvedAt,
            now = java.time.Instant.now(),
        )
    }

    // 键盘实际是否弹出（用未被消费的 IME inset，与 VKeyboardToolbar 同一判据）。
    // 不能用 `mdState.focused` 代替：输入框聚焦但键盘未弹时它也为 true，
    // 那会让按钮在没有键盘时也无故悬空。
    val overlayImePx = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    val keyboardUp = overlayImePx > 0
    // 上浮/下沉走动画：不得突变（用户明确要求：任何元素的位置变化必须连续过渡）。
    val followBottom by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (keyboardUp) 56.dp else 12.dp,
        animationSpec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.Dp>(
            durationMillis = 220,
            easing = VMotion.Emphasized,
        ),
        label = "fullscreenDoneButton",
    )

    // 底色与内容分开淡入（2026-09-19 第四次修）：
    // 上一版是整块（含底色）从 alpha=0 淡入 180ms，而编辑页是在
    // 同一帧被卸下的；两者存在一帧的空窗（编辑页已畫、全屏层还透明），
    // 表现就是「底下的保存按钮异常地闪一下」。
    // 现在底色**首帧就不透明**（立即遮住下层），只有内容走 alpha 淡入。
    // （从全屏展示页双击进入时走 fadeBackground=true：底色也一起淡入，与展示页交叉淡化。）
    Box(
        Modifier.fillMaxSize().background(
            if (fadeBackground) VColors.surface.copy(alpha = progress.coerceIn(0f, 1f)) else VColors.surface,
        ),
    ) {
      // **屏幕层**：裁剪到屏幕边界 —— 悬浮信息栏上缘拉出屏幕后要被屏幕顶边切平；
      // 内衬不在这里（否则裁剪边界只到状态栏下沿，会露出一条白边）。
      Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = progress }
            .clipToBounds(),
      ) {
        // **内容层**：状态栏 / 导航栏 / 键盘的内衬都在这里。
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {

            // —— 中间可编辑区 ——
            //
            // 顶部补 4dp 内边距：外层 Column 是 spacedBy(8dp)，
            // 而全屏**展示**页的「顶部栏区块（含标签栏）→ 中间区」是 12dp
            //（展示页里它们同为父 Column 的子项，父 Column 给的是 spacedBy(12dp)）。
            // 不补这 4dp，编辑页的「标签栏 → 标题」就是 8dp，比展示页紧。
            //
            // 为什么不动外层 spacedBy：它还同时控制「日期行 → 标签栏」那段，
            // 那里两边都应该是 8dp（与展示页一致）。改外层会把那段一起弄错。
            // 「确认更改」按钮占掉的高度：按钮直径 52 + 它距容器底的 followBottom + 呼吸 12。
            //
            // ⚠️ 这个留白加在 verticalScroll **之后**（= 属于滚动内容），**不能**加在前面。
            // 加在前面等于缩小可视区，会把「确认按钮下方的内容整段截掉」——用户明确不要
            //（2026-09-28）：内容照旧可以滚到按钮下面，要约束的只是**光标**不许低于按钮上界。
            // 留白放在内容里的作用只是「让最后一行也能被滚到按钮上方」（提供滚动余量）。
            // 尾部余量 = ✓ 按钮让出的那一套。三个数字取自共用常量，
            // 与展示页的 ContentBottomBlank（#18）同源 —— 两页"底部空白一样多"是算出来的。
            val doneButtonArea = followBottom +
                FullscreenCardLayout.DoneButtonSize +
                FullscreenCardLayout.DoneButtonGap
            val editScroll = rememberScrollState()
            // 光标行 / 可视区边界：**存在普通持有物里，不放进组合状态**（用户 2026-09-28）。
            //
            // 旧写法把它们放进 mutableStateOf，再用
            // `snapshotFlow { 光标底边 to 可视区底边 }` 触发滚动 —— 于是**每滚动一帧**
            // （手指滚动、框架 bringIntoView、拖手柄时的自动滚都会改可视区边界）
            // 都会再触发一次我们自己的 scrollTo，和它们抢同一个 ScrollState。
            // 表现就是用户报的「选区手柄在滚动时抽动」「选区范围乱跳」。
            //
            // 现在：只有**光标行变化**（输入框的 onCursorRect 回调）才可能触发跟随，
            // 滚动本身什么都不触发。
            class CaretMetrics {
                var cursorTop = Float.NaN
                var cursorBottom = Float.NaN
                var viewTop = Float.NaN
                var viewBottom = Float.NaN
                /** 「双击落位」是否已完成。落位前不跟随（那时光标还在文末，会先滚到底）。 */
                var placed = false
                var job: kotlinx.coroutines.Job? = null
            }
            val metrics = remember { CaretMetrics() }
            if (initialCursorAt == null) metrics.placed = true
            val followScope = rememberCoroutineScope()
            val editDensity = androidx.compose.ui.platform.LocalDensity.current
            // 按钮上界相对可视区底边的距离：followBottom + 52，再扣掉外层 Column 的 8dp 底衬。
            val cursorLimitDp = followBottom + 44.dp
            // 同一个坐标系（root）里比：光标行底边 vs 「容器底边 − 光标界限」。
            // 越过多少就滚多少，滚完光标行停在按钮上方 12dp。
            fun scrollBy(delta: Float) {
                metrics.job?.cancel()
                metrics.job = followScope.launch {
                    editScroll.scrollTo(
                        (editScroll.value + delta)
                            .coerceIn(0f, editScroll.maxValue.toFloat())
                            .toInt(),
                    )
                }
            }
            // 光标越界就主动上滚：让光标行停在确认按钮上界之上 12dp。
            // 这是本页**自己**完成的滚动，不依赖框架的 bringIntoView ——
            // 后者只认整个可视区（下边到键盘），看不见浮在上面的确认按钮。
            fun onCaretMoved() {
                if (!metrics.placed) return
                val bottom = metrics.cursorBottom
                val viewBottom = metrics.viewBottom
                if (bottom.isNaN() || viewBottom.isNaN()) return
                val limit = viewBottom - with(editDensity) { cursorLimitDp.toPx() }
                if (bottom <= limit) return
                scrollBy(bottom - limit + with(editDensity) { 12.dp.toPx() })
            }
            // 落位后把光标滚到**视觉中心**（用户要求）：
            // 双击屏幕中间还是靠下，进编辑后看到的都是同一处内容。
            fun centerCaret(): Boolean {
                if (editScroll.viewportSize <= 0) return false
                val top = metrics.cursorTop
                val bottom = metrics.cursorBottom
                val vTop = metrics.viewTop
                val vBottom = metrics.viewBottom
                if (top.isNaN() || bottom.isNaN() || vTop.isNaN() || vBottom.isNaN()) return false
                scrollBy((top + bottom) / 2f - (vTop + vBottom) / 2f)
                return true
            }

            Column(
                Modifier
                    .fillMaxSize()
                    // 底部 8dp：原来由外层 Column 给，现在移到内容自己身上。
                    // 底部内衬：与展示页同一常量（展示页在容器上给 top=4/bottom=8，
                    // 编辑页把 top 4 补在滚动内容上、bottom 8 给在本列）。
                    .padding(bottom = FullscreenCardLayout.ContentBottomPadding)
                    .onGloballyPositioned { coords ->
                        // 写进普通持有物：这里每次放置/滚动都会被调用，
                        // 放进 State 会每帧触发重组（见上面 CaretMetrics 的说明）。
                        metrics.viewTop = coords.positionInRoot().y
                        metrics.viewBottom = coords.positionInRoot().y + coords.size.height
                    }
                    .verticalScroll(editScroll)
                    // 顶部内衬 = 信息栏实测高度：加在 verticalScroll **之后**，
                    // 所以它属于**滚动内容** —— 滚到顶时标题正好在栏下方，
                    // 继续滚动则文字从栏下面穿过（没有截断线）。
                    // 公式与全屏展示页**共用一份**（fullscreenScrollTopPadding），
                    // 免得改一处忘另一处 —— 那正是这两页历史上返工的根因。
                    .padding(
                        top = fullscreenScrollTopPadding(headerHeightPx),
                    )
                    // 正文左右内衬（**两层，合计 = TextHorizontalPadding 16dp**）：
                    // 第一层 = 容器层 HorizontalPadding（与展示页容器上那一层对应，
                    //          本页没有带内衬的容器，所以在这里一并给出）；
                    // 第二层 = 内容层 ContentHorizontalPadding。
                    // 抽成共用常量：全屏展示页的正文宽度必须与它一致，否则双击定位会错行。
                    //
                    // ⚠️ 历史坑（用户 2026-09-28：「看起来可完全不一样」）：两页都写着
                    // `ContentHorizontalPadding`，看代码"一样"，但展示页容器另有一层 8dp、
                    // 本页没有 → 展示页实际 16dp、本页只有 8dp。
                    // 教训：**光看常量名相同不足以判定一致，必须把整条 padding 链加起来。**
                    .padding(horizontal = FullscreenCardLayout.TextHorizontalPadding)
                    // 顶部额外 4dp：展示页容器上有 padding(top = 4.dp)，编辑页原先没有，
                    // 现补上（两页骨架同源）。真正的大内衬是上面那条「栏高 − 拉出量 − 状态栏高」。
                    .padding(top = FullscreenCardLayout.ContentTopPadding)
                    // 滚动余量（见上）：只在内容末尾留，可视区不受影响。
                    .padding(bottom = doneButtonArea),
                verticalArrangement = Arrangement.spacedBy(FullscreenCardLayout.ContentSpacing),
            ) {
                // 标题（可编辑）：字号/行高**与全屏展示页同源**。
                // 展示页用 fontSizes.title / 1.3 倍行高（FocusCardContent），
                // 这里必须取同一值，不能写硬编码 16sp。
                val titleSize = fontSizes.title
                val titleStyle = CardTextStyles.titleStyle(titleSize)
                Box(Modifier.fillMaxWidth()) {
                    if (title.isEmpty()) VText("标题", titleStyle, color = VColors.ink3)
                    BasicTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = titleStyle,
                        cursorBrush = SolidColor(VColors.accent),
                    )
                }
                // 正文（可编辑）：接入带浮动工具栏的 Markdown 编辑器。
                // 工具栏不内联，改为钉在键盘上方（与编辑页一致）。
                // 行高取展示页内容分支的 1.5 倍（FocusCardContent 正文分支）。
                val bodySize = fontSizes.body
                // 与展示端**同一个** bodyStyle（字号 + 行高 + 字体族完全一致）。
                // 行距与**全屏展示页**同源（fullscreenBodyStyle）：全屏场景统一加大一档。
                val bodyStyle = CardTextStyles.fullscreenBodyStyle(bodySize).copy(
                    // 全屏编辑是长文阅读场景，用 ink（比卡片上的 cardBody 更深）。
                    color = VColors.ink,
                )
                VRichTextField(
                    value = body,
                    state = mdState,
                    onValueChange = onBodyChange,
                    textStyle = bodyStyle,
                    placeholder = "写点什么…",
                    focusRequester = bodyFocus,
                    // 上报光标行位置：本页据此在光标低于确认按钮上界时主动上滚。
                    // 只在**纯光标**时上报（输入框侧已保证）：
                    // 有选区时不跟随，避免和框架自己的滚动互相打架。
                    onCursorRect = { top, bottom ->
                        metrics.cursorTop = top
                        metrics.cursorBottom = bottom
                        onCaretMoved()
                    },
                    // 「双击哪里，光标就放哪里」：按双击点把光标落到对应字符。
                    placeCursorAt = initialCursorAt,
                    onPlacedCursor = {
                        metrics.placed = true
                        // 「把点击位置滚到视觉中心」要**等度量就绪**：
                        // 落位发生在第一帧布局里，此时可视区边界可能还没上报（NaN），
                        // 一次性调用会静默什么都不做（用户 2026-09-28：长文里点了不动）。
                        // 这里最多重试几帧，成功后立刻停。
                        followScope.launch {
                            repeat(6) {
                                androidx.compose.runtime.withFrameNanos { }
                                if (centerCaret()) return@launch
                            }
                        }
                        onInitialCursorPlaced?.invoke()
                    },
                )

                // 附件（只读展示，点图片进全屏查看）。
                val imageAtts = attachments.filter { it.isImage }
                val otherAtts = attachments.filterNot { it.isImage }
                // 与编辑页同一套附件规范（设计稿「日程详情」）：84×84、gap=8。
                // 与展示页共用同一套画法（原来这里是固定 84dp 方块，整排靠左、
                // 右侧空出一块，用户 2026-09-28 反馈"缩到左边"）。
                CardImageBlock(
                    images = imageAtts,
                    storage = attachmentStorage,
                    imageLayout = imageLayout,
                    onOpen = onOpenAttachment,
                )
                otherAtts.forEachIndexed { index, att ->
                    if (index > 0 || imageAtts.isNotEmpty()) VDividerFull()
                    // 非图片附件行：行高 / 左右内衬 / 文件名包色全部取展示页值
                    //（原先这里是 40dp / 左右 14dp / ink，与展示页的 36dp / 无内衬 / ink2 不一致）。
                    Row(
                        Modifier.fillMaxWidth().height(FullscreenCardLayout.AttachmentRowHeight)
                            .vPressable(scaleDown = 0.97f) { onOpenAttachment(att) }
                            .padding(horizontal = FullscreenCardLayout.AttachmentRowExtraHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(if (att.isAudio) Lucide.Music else Lucide.FileText, null, Modifier.size(14.dp), tint = VColors.ink2)
                        VText(att.displayName, VTypo.caption12, color = VColors.ink2, maxLines = 1, modifier = Modifier.weight(1f))
                        VText(
                            attachmentMetaText(att.sizeBytes, att.mimeType),
                            VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                            color = VColors.ink3,
                        )
                        Icon(Lucide.ChevronRight, null, Modifier.size(14.dp), tint = VColors.ink3)
                    }
                }
            }

            // ⚠️ 这里**不能**关闭上层那个「状态栏/导航栏/键盘内衬」的 Box（22xx 行那个
            // `Box(... .imePadding())`）！用户 2026-09-28 报「编辑页没有富文本工具栏和
            // 保存更改按钮」的真因就是它提前关了：
            // 那个 Box 一旦在此闭合，下面的**信息栏 / 确认按钮 / 键盘工具栏**就都跑到了
            // IME 内衬之外 —— 按钮与工具栏以整屏为基准定位，于是落到键盘**后面**被遮住
            // （信息栏在 TopCenter，所以看起来正常）。
            // 正确结构：信息栏 / 确认按钮 / 工具栏都在该 Box **内部**，
            // 它的闭合花括号已移到工具栏之后（本组件末尾）。

            // —— 顶部信息栏：**悬浮圆角容器**（与全屏展示页同一个组件）——
            // 用户 2026-09-28：日期 / 字数在左、竖线后的标签在右，右上角是标记组 + 三点 + 关闭。
            //
            // 所有数值取自 FullscreenCardLayout —— 与全屏展示页**同一份常量**。
            // 用户 2026-09-28 铁律：两页是一套东西，要改就一起改。
            // 本行原先各自写 12dp / 多减一个状态栏高，与展示页不一致，现已纠正：
            //  · 左右内衬 12dp → InfoBarHorizontalPadding（= 8 + 16 = 24dp，展示页原值）；
            //  · 上缘偏移原先额外减了状态栏高。那个说法（"栏在内容层里、原点在状态栏下沿"）
            //    是**错的**：本 Row 与展示页一样，父节点是那个 clip Box，原点是屏幕顶边，
            //    内容层（statusBarsPadding）才是它的兄弟。多减状态栏高会把整条栏再推高
            //    ≈28dp —— 正是用户看到的"两页差很多"。现与展示页同为 -PullOut。
            Row(
                Modifier
                    .fillMaxWidth()
                    // 悬浮层要画在文字之上（同一 Box 里，后画的在上面；这里显式提一下，
                    // 免得以后调整顺序时被内容盖住）。
                    .zIndex(1f)
                    .align(Alignment.TopCenter)
                    // **入场**：从上方滑入（alpha 由外层图层按 progress 统一驱动，
                    // 退场时同一条进度反向播完，不会硬消失）。
                    //
                    // ⚠️ **从全屏展示页双击进入时不做滑入**（`fadeBackground` 标记，
                    // 用户 2026-09-28："进入编辑页不要再给一个沉入动画了，这里就需要无缝衔接"）。
                    // 原因：那条路径下展示页的栏已经**停好位**（translationY = 0），
                    // 而编辑页的栏若从 -20dp 滑下，同一个元素就会"跳一下"——
                    // 两页本来是在交叉淡化，位置就不该变。
                    // 其余进入路径（编辑页右下放大按钮、新建自动进全屏）保持原滑入。
                    .graphicsLayer {
                        translationY = if (fadeBackground) {
                            0f
                        } else {
                            -20.dp.toPx() * (1f - progress.coerceIn(0f, 1f))
                        }
                    }
                    // **上边缘拉出屏幕**：整条栏上移，上圆角与上内衬被屏幕顶边切掉，
                    // 看起来像一张卡片半截插在屏幕外（用户 2026-09-28 要求的设计）。
                    //
                    // ⚠️ **这里必须多减一个状态栏高度，而展示页不减** —— 差别来自**嵌套层级**，
                    // 不是两页写法不一致（用户 2026-09-28 报「编辑模式没有工具栏和保存按钮」
                    // 的那个真因修复后，本条才成立）：
                    //  · 展示页的栏是 `clip Box`（原点 = 屏幕顶边）的**直接子节点** → 只减拉出量；
                    //  · 编辑页的栏在本组件里已经移进了「状态栏/键盘内衬」那个 Box
                    //    （`.statusBarsPadding()`），原点 = **状态栏下沿** → 只需再减状态栏高。
                    // 两页在**屏幕坐标**里的结果相同（栏顶 = 屏幕顶 − 12dp）。
                    .offset(
                        y = -CardInfoBarPullOut - with(
                            androidx.compose.ui.platform.LocalDensity.current,
                        ) {
                            androidx.compose.foundation.layout.WindowInsets.statusBars
                                .getTop(this).toDp()
                        },
                    )
                    // 实测高度（含被切掉的部分）→ 内容顶部内衬要减去切掉的量。
                    .onGloballyPositioned { headerHeightPx = it.size.height.toFloat() }
                    // 左右内衬：与展示页共用同一常量（= 8dp + 16dp = 24dp）。
                    .padding(horizontal = FullscreenCardLayout.InfoBarHorizontalPadding)
                    .padding(bottom = FullscreenCardLayout.InfoBarBottomPadding),
            ) {
                CardInfoBar(
                    dateText = if (showDate) boardFullDateWeekText(java.time.Instant.now()) else null,
                    wordCount = cardWordCount(title, body),
                    color = color,
                    tagChain = tagChain,
                    // 上内衬 = 拉出量 + 8（补偿被切掉的部分）+ **内容下沉量**。
                    // 用户 2026-09-28 晚：栏要整体下移 24dp（原位置避不开状态栏），
                    // 且顶部仍伸出屏幕 —— 所以栏变高 24dp、内容下沉 24dp，栏顶仍在屏幕外。
                    // 详见 CardInfoBarTopDownShift 的说明（为何不能靠改拉出量实现）。
                    extraTopPadding = CardInfoBarPullOut + 8.dp + CardInfoBarTopDownShift,
                    trailing = {
                        if (attachments.isNotEmpty()) AttachmentCountBadge(attachments.size)
                        if (pinned) Icon(Lucide.Pin, null, Modifier.size(13.dp), tint = VColors.ink3)
                        if (autoPinActive) {
                            Icon(Lucide.Clock, "动态浮起中", Modifier.size(13.dp), tint = VColors.ink3)
                        }
                        if (secret) Icon(Lucide.Lock, "保密", Modifier.size(13.dp), tint = VColors.ink3)
                        // 三点下拉菜单（复制 / 导出 / 分享 / 更多）——用户 2026-09-28 重构。
                        menu?.let { ThreeDotMenuButton(it) }
                        Box(
                            Modifier.size(28.dp).vPressable(scaleDown = 0.88f) { closing = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Lucide.X, "退回首页", Modifier.size(18.dp), tint = VColors.ink2)
                        }
                    },
                )
            }

        // 工具栏钉在键盘上方（键盘不在则不显示，出现/隐藏带动画）。
        // 这个浮层的根节点已经有 imePadding，所以关掉内部的，避免双倍内边距。
        // 完成（对勾）悬浮按钮（用户 2026-09-19）：
        // 始终在可占用空间的右下角。
        //
        // 为什么能自动跟随键盘：它的容器（上面那个 Box）已经带了
        // `imePadding()`，所以键盘弹出时容器的可用区域会自动上缩、边界上移；
        // 按钮 `align(BottomEnd)` 钉在该区域的右下角 → 自然随键盘上浮，
        // 键盘收起则落回屏幕最右下角。
        // 不要自己读 IME 高度做 offset：那会与 imePadding() 重复叠加。
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                // 右移 8dp（20 → 12）：与顶部栏关闭按钮、滚动区右边界观感对齐（用户 2026-09-28）。
                .padding(end = 12.dp)
                // 避开键盘上方的 Markdown 工具栏（高约 44dp）：
                // 键盘弹出时它在按钮下方，按钮需上移一段避免被遮住；
                // 键盘收起时工具栏不在，按钮落到最下。
                .padding(bottom = followBottom),
            contentAlignment = Alignment.Center,
        ) {
            // 颜色过渡（用户 2026-09-30：变色必须有过渡动画，不能硬切）。
            // 底与 ✓ 同一条 spec，颜色同步渐变。
            val fabBg by androidx.compose.animation.animateColorAsState(
                if (saveEnabled) VColors.accent else VColors.surface2,
                VMotion.color(),
                label = "fsSaveBg",
            )
            val fabFg by androidx.compose.animation.animateColorAsState(
                if (saveEnabled) Color.White else VColors.ink3,
                VMotion.color(),
                label = "fsSaveFg",
            )
            Box(
                Modifier
                    .size(52.dp)
                    .shadow(
                        elevation = 10.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color(0x591C6B58),
                        spotColor = Color(0x591C6B58),
                    )
                    // 不可保存时**也变灰**：与底部保存条同一套禁用语义。
                    // 用户 2026-09-30 报的就是「新建卡片什么都不输入，✓ 还是绿的」——
                    // 而那颗 ✓ 会**真的落库**一张空卡片，所以必须给出可见的禁用态。
                    .background(fabBg, CircleShape)
                    // 短按 = 提交；长按 = 提交 + 进「更多设置」（用户 2026-09-28）。
                    // combinedClickable 保证两者互斥，长按松手不会再触发一次短按。
                    .vPressable(
                        scaleDown = 0.92f,
                        enabled = saveEnabled,
                        onClick = {
                            saveLongPress = false
                            saving = true
                        },
                        onLongPress = {
                            saveLongPress = true
                            saving = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Check,
                    contentDescription = "保存修改",
                    modifier = Modifier.size(24.dp),
                    tint = fabFg,
                )
            }
        }

        VKeyboardToolbar(
            // 只看「键盘是否弹出」，不再叠加 state.focused：
            // focused 是输入框写进状态对象的普通字段，跨对象传递时可能读到过期值，
            // 那会让工具栏整条消失。键盘状态是本层直接测出的，更可靠。
            visible = keyboardUp,
            applyImePadding = false,
            // 父层已 imePadding()，IME inset 可能被消费；用本地算好的 keyboardUp 判定。
            gateOnImeInsets = false,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VRichToolbar(state = mdState, onValueChange = onBodyChange)
        }
        // 这里才是「状态栏 / 导航栏 / 键盘内衬」那个 Box 的闭合 ——
        // 信息栏、确认按钮、键盘工具栏都在它内部，所以能正确避开键盘。
        }
      }
    }
}


// ---------------------------------------------------------------- 动态置顶配置

/**
 * 「动态置顶」设置行的副标题。
 *
 * 用户必须能在**不进面板**的情况下看出当前配了什么——否则这一行就成了
 * 一个纯入口，每次都要点进去才能确认，等于把信息藏起来。
 */
private fun autoPinSubtitle(rule: com.phonlynn.oreplan.domain.model.AutoPinRule?): String {
    if (rule == null) return "按日期或周期自动浮到顶部"
    val now = java.time.Instant.now()
    return when (rule) {
        is com.phonlynn.oreplan.domain.model.AutoPinRule.OnDate -> {
            val z = rule.at.atZone(java.time.ZoneId.systemDefault())
            val start = z.hour * 60 + z.minute
            "${z.monthValue}月${z.dayOfMonth}日 " +
                com.phonlynn.oreplan.core.time.AutoPinTime.formatSinkTime(start, rule.durationMinutes)
        }

        is com.phonlynn.oreplan.domain.model.AutoPinRule.Recurring -> {
            val freq = com.phonlynn.oreplan.domain.expansion.RecurrenceEngine.describe(rule.rule)
            "%s · %02d:%02d 起".format(
                freq,
                rule.minuteOfDay / 60,
                rule.minuteOfDay % 60,
            )
        }
    }
}

/**
 * 动态置顶配置面板。
 *
 * 结构与白板设置页完全一致（`VDialog` + `VDialogPanel` + `VRow` + `VDivider`），
 * 时间部分沿用编辑器「开始 / 结束 / 快捷时长」的行式布局，
 * 因此视觉上不会与项目其余部分脱节。
 *
 * ## 三项时间联动
 *
 * 「浮起时间」「持续时长」「下沉时间」三者双向绑定：改任意一项，另两项跟着算。
 * 换算全部交给 `AutoPinTime`（纯函数、已单测覆盖），这里只负责把结果画出来。
 *
 * 「下沉时刻」不入库——数据层只存时长，下沉时刻是派生值。
 */
@androidx.compose.runtime.Composable
private fun AutoPinConfigDialog(
    initial: com.phonlynn.oreplan.domain.model.AutoPinRule?,
    onConfirm: (com.phonlynn.oreplan.domain.model.AutoPinRule?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 面板内的临时草稿：只有点「确定」才写回，避免中途状态入库。
    var enabled by remember { mutableStateOf(initial != null) }
    var isRecurring by remember {
        mutableStateOf(initial is com.phonlynn.oreplan.domain.model.AutoPinRule.Recurring)
    }
    // 浮起日期（仅日期型用）。
    var date by remember {
        mutableStateOf(
            when (initial) {
                is com.phonlynn.oreplan.domain.model.AutoPinRule.OnDate ->
                    initial.at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                else -> java.time.LocalDate.now()
            },
        )
    }
    // 浮起时刻（当天分钟）。
    var startMinute by remember {
        mutableStateOf(
            when (initial) {
                is com.phonlynn.oreplan.domain.model.AutoPinRule.OnDate -> {
                    val z = initial.at.atZone(java.time.ZoneId.systemDefault())
                    z.hour * 60 + z.minute
                }
                is com.phonlynn.oreplan.domain.model.AutoPinRule.Recurring -> initial.minuteOfDay
                else -> 8 * 60
            },
        )
    }
    var durationMinutes by remember {
        mutableStateOf(
            initial?.durationMinutes
                ?: com.phonlynn.oreplan.domain.model.AutoPinRule.DEFAULT_DURATION_MINUTES,
        )
    }
    // 周期：先用「每周一」作默认，与设计上最常见的用法一致。
    var weekday by remember {
        mutableStateOf(
            (initial as? com.phonlynn.oreplan.domain.model.AutoPinRule.Recurring)
                ?.rule?.byDay?.firstOrNull() ?: java.time.DayOfWeek.MONDAY,
        )
    }

    var datePickerOpen by remember { mutableStateOf(false) }
    // null = 未打开；非 null = 正在编辑哪一项（浮起 / 下沉）。
    var timePickerTarget by remember { mutableStateOf<TimeFieldTarget?>(null) }

    val nextDay = com.phonlynn.oreplan.core.time.AutoPinTime.sinkIsNextDay(startMinute, durationMinutes)
    val sinkText = com.phonlynn.oreplan.core.time.AutoPinTime.formatSinkTime(startMinute, durationMinutes)

    VDialog(onDismissRequest = onDismiss, maxWidth = 340.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel {
            VText("动态置顶", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(4.dp))
            VText(
                "到期自动浮到顶部，超过时长后自动归位",
                VTypo.caption,
                color = VColors.ink3,
            )
            Spacer(Modifier.height(12.dp))

            VRow(
                title = "启用",
                leading = { RowBadge(Lucide.Clock) },
                trailing = { VSwitch(enabled) { enabled = it } },
            )

            androidx.compose.animation.AnimatedVisibility(visible = enabled) {
                Column {
                    VDivider()
                    // 模式二选一：与项目其余分段控件同形。
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        VSegmented(
                            options = listOf("指定日期", "周期"),
                            selectedIndex = if (isRecurring) 1 else 0,
                            onSelect = { isRecurring = it == 1 },
                        )
                    }

                    if (isRecurring) {
                        VDivider()
                        VRow(
                            title = "重复",
                            subtitle = "每周${
                                when (weekday) {
                                    java.time.DayOfWeek.MONDAY -> "一"
                                    java.time.DayOfWeek.TUESDAY -> "二"
                                    java.time.DayOfWeek.WEDNESDAY -> "三"
                                    java.time.DayOfWeek.THURSDAY -> "四"
                                    java.time.DayOfWeek.FRIDAY -> "五"
                                    java.time.DayOfWeek.SATURDAY -> "六"
                                    java.time.DayOfWeek.SUNDAY -> "日"
                                }
                            }",
                            leading = { RowBadge(Lucide.RefreshCw) },
                            trailing = { VChevron() },
                            onClick = {
                                // 循环切换星期几，最简单的可用交互（避免再套一层选择器）。
                                val all = java.time.DayOfWeek.entries
                                weekday = all[(all.indexOf(weekday) + 1) % all.size]
                            },
                        )
                    } else {
                        VDivider()
                        VRow(
                            title = "浮起日期",
                            subtitle = formatDateOnly(date),
                            leading = { RowBadge(Lucide.Calendar) },
                            trailing = { VChevron() },
                            onClick = { datePickerOpen = true },
                        )
                    }

                    VDivider()
                    VRow(
                        title = "浮起时间",
                        subtitle = "%02d:%02d".format(startMinute / 60, startMinute % 60),
                        leading = { RowBadge(Lucide.Clock3) },
                        trailing = { VChevron() },
                        onClick = { timePickerTarget = TimeFieldTarget.START },
                    )
                    VDivider()
                    VRow(
                        title = "下沉时间",
                        // 跨日必须标出「次日」，否则用户会以为当天下沉。
                        subtitle = if (nextDay) sinkText else sinkText,
                        leading = { RowBadge(Lucide.Timer) },
                        trailing = {
                            VChipSmall(
                                com.phonlynn.oreplan.core.time.AutoPinTime.durationText(durationMinutes),
                            )
                        },
                        onClick = { timePickerTarget = TimeFieldTarget.SINK },
                    )

                    Spacer(Modifier.height(10.dp))
                    VText(
                        "快捷时长",
                        VTypo.caption,
                        color = VColors.ink3,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        QuickDurationRow(
                            durations = AUTO_PIN_DURATIONS,
                            selectedMinutes = durationMinutes.takeIf { it in AUTO_PIN_DURATIONS },
                            onSelect = { durationMinutes = it },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) { close(onDismiss) },
                    contentAlignment = Alignment.Center,
                ) {
                    VText("取消", VTypo.button, color = VColors.ink)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(VColors.accent, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) {
                            close { onConfirm(buildAutoPinRule(enabled, isRecurring, date, startMinute, durationMinutes, weekday)) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    VText("确定", VTypo.button, color = Color.White)
                }
            }
        }
    }

    if (datePickerOpen) {
        VDatePickerDialog(
            initial = date,
            onConfirm = {
                date = it
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false },
        )
    }

    timePickerTarget?.let { target ->
        VTimePickerDialog(
            initialMinute = when (target) {
                TimeFieldTarget.START -> startMinute
                // 下沉：打开时定位到当前算出的下沉时刻。
                TimeFieldTarget.SINK -> com.phonlynn.oreplan.core.time.AutoPinTime
                    .sinkMinuteOfDay(startMinute, durationMinutes)
            },
            onConfirm = { minute ->
                when (target) {
                    TimeFieldTarget.START -> startMinute = minute
                    // 改下沉 = 反算时长（跨日自动 +1440）。
                    // 「下沉时刻」不入库，只有时长是真值。
                    TimeFieldTarget.SINK ->
                        durationMinutes = com.phonlynn.oreplan.core.time.AutoPinTime
                            .durationBetween(startMinute, minute)
                }
                timePickerTarget = null
            },
            onDismiss = { timePickerTarget = null },
        )
    }
}

/** 时间编辑的目标字段。 */
private enum class TimeFieldTarget { START, SINK }

/** 动态置顶的快捷时长档位（分钟）：6h / 12h / 24h / 2d / 3d。 */
private val AUTO_PIN_DURATIONS: List<Int> = listOf(360, 720, 1440, 2880, 4320)

/**
 * 由面板的临时状态组装出规则；未启用时返回 null。
 *
 * 组装放在这里而不是内联，是因为「未启用」与「哪一种是周期型」两个判断
 * 都影响结果，内联写容易在确定按钮那一行里绕不清。
 */
private fun buildAutoPinRule(
    enabled: Boolean,
    isRecurring: Boolean,
    date: java.time.LocalDate,
    startMinute: Int,
    durationMinutes: Int,
    weekday: java.time.DayOfWeek,
): com.phonlynn.oreplan.domain.model.AutoPinRule? {
    if (!enabled) return null
    val duration = com.phonlynn.oreplan.domain.model.AutoPinLogic.normalizeDurationMinutes(durationMinutes)
    return if (isRecurring) {
        com.phonlynn.oreplan.domain.model.AutoPinRule.Recurring(
            rule = com.phonlynn.oreplan.domain.model.RecurrenceRule(
                frequency = com.phonlynn.oreplan.domain.model.Frequency.WEEKLY,
                byDay = setOf(weekday),
            ),
            minuteOfDay = startMinute.coerceIn(0, 1439),
            durationMinutes = duration,
        )
    } else {
        com.phonlynn.oreplan.domain.model.AutoPinRule.OnDate(
            at = date.atTime(startMinute / 60, startMinute % 60)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant(),
            durationMinutes = duration,
        )
    }
}
