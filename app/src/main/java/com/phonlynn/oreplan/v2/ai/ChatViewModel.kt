package com.phonlynn.oreplan.v2.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.domain.repository.AttachmentRepository
import com.phonlynn.oreplan.domain.model.AttachmentOwner
import android.net.Uri
import com.phonlynn.oreplan.domain.ai.AiClient
import com.phonlynn.oreplan.domain.ai.AiException
import com.phonlynn.oreplan.domain.ai.AiProviderConfig
import com.phonlynn.oreplan.domain.ai.AiSettings
import com.phonlynn.oreplan.domain.ai.AiSettingsStore
import com.phonlynn.oreplan.domain.ai.ChatTurn
import com.phonlynn.oreplan.domain.ai.Conversation
import com.phonlynn.oreplan.domain.ai.ConversationPlaceholder
import com.phonlynn.oreplan.domain.ai.ConversationStore
import com.phonlynn.oreplan.domain.ai.Rounds
import com.phonlynn.oreplan.domain.ai.SendChatMessage
import com.phonlynn.oreplan.domain.ai.ToolCallRecord
import com.phonlynn.oreplan.domain.ai.tool.AskAnswer
import com.phonlynn.oreplan.domain.ai.tool.AskQuestion
import com.phonlynn.oreplan.domain.ai.tool.AskingTool
import com.phonlynn.oreplan.domain.ai.tool.ChangeRecord
import com.phonlynn.oreplan.domain.ai.tool.ConfirmableTool
import com.phonlynn.oreplan.domain.ai.tool.ToolDetail
import com.phonlynn.oreplan.domain.ai.tool.ToolOutcome
import com.phonlynn.oreplan.domain.ai.tool.ToolRegistry
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.usecase.LoadItemDetailUseCase
import com.phonlynn.oreplan.v2.theme.VMotion
import java.time.Instant
import java.time.ZoneId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * 输入框「＋」菜单的页面（用户 2026-10-04 定的形态）。
 *
 * ```
 * ＋  →  [ 导入文件 ]
 *       [ 会话文件 ]
 *       [ 关联项目 ]
 * ```
 *
 * 三项各自展开一页（而不是直接执行）—— 因为后两项都要**先让用户选东西**。
 */
enum class PlusMenuPage {
    /** 三项的总入口。 */
    Root,

    /** 本会话已导入的文件（含"待整理"的 `UNASSIGNED`）。 */
    Files,

    /** 从 orenote 内部挑对象，把它的内容附给 AI 当上下文。 */
    Link,
}

/**
 * 「关联项目」里的一个候选/已选对象。
 *
 * ## 为什么带 `detail`（正文）
 *
 * 用户口径已确认：**不是只做归属标记，而是把选中对象的完整内容带进这一轮上下文**。
 * 所以选中之后要有东西可发 —— 那就是 [detail]，由 `GetItemDetailTool` 同一套
 * 语义层的取数逻辑填。
 *
 * ⚠️ 这里**只存一份快照**，不存"对象 id 然后等发送时再查"。
 * 用户在挑的时候看到的就是这份内容；发送时再查一次可能已经变了
 *（那条日程被改了），于是"我选的那个"和"AI 看到的"不是同一个东西。
 */
data class LinkedItem(
    /** 条目 id（或卡片 id）。 */
    val id: String,
    /** 显示用：`日程` / `待办` / `目标` / `笔记` / `卡片`。 */
    val kindLabel: String,
    /** 显示用标题。 */
    val title: String,
    /**
     * 附给 AI 的正文（选中的那一刻取好）。
     *
     * 为空表示"还没取"或"取不到" —— 界面据此提示，而不是发一段空上下文。
     */
    val detail: String = "",
)

/**
 * 对话页的状态。
 */
data class ChatUiState(
    val conversation: Conversation? = null,
    /** **全量**消息（包含同一问的多份回答）。界面与请求都走 [visibleTurns]。 */
    val turns: List<ChatTurn> = emptyList(),
    val input: String = "",
    val placeholder: String = "",
    val mode: ChatMode = ChatMode.CHAT,
    val deepThinking: Boolean = false,
    /**
     * 要不要在生成状态行右端显示 token 消耗。
     *
     * 来自 AI 设置里的开关（`AiSettings.showTokens`）。放在 ui 状态里而不是
     * 让界面去读设置：界面已经拿 `state` 了，再多一条数据源只会多一处不一致。
     */
    val showTokens: Boolean = true,
    /**
     * 输入框「＋」弹出的菜单当前展开的是哪一页（用户 2026-10-04 定的形态）。
     *
     * 用户口径：
     *
     * > 「可以合成在悬浮输入框里的加号按钮里，点击加号按钮，弹出一个菜单列表，
     * > 包含"导入文件 / 会话文件 / 关联项目"」
     *
     * `null` = 菜单关着。
     */
    val plusMenu: PlusMenuPage? = null,
    /**
     * 本会话/待整理的附件（「会话文件」那一页要显示的东西）。
     *
     * 它是 `attachments.observeAll()` 的一个**视图**，在 ViewModel 里算好 ——
     * 界面只负责画，不再自己过滤（那样"哪些算本会话的"会有两处定义）。
     */
    val files: List<Attachment> = emptyList(),
    /**
     * 「关联项目」**已选中**的对象（用户口径：把内容附给 AI 当这一轮上下文）。
     *
     * 选中即可，不立刻发出去 —— 用户可能选好几个再一起发。
     */
    val linkedItems: List<LinkedItem> = emptyList(),
    /**
     * 「关联项目」面板里可选的条目（日程/待办/目标/笔记，最近的若干条）。
     *
     * 与 [files] 一样在 ViewModel 里算好 —— 它要读库，界面不能直接调。
     */
    val linkCandidates: List<LinkedItem> = emptyList(),
    /** 正在等模型返回。 */
    val sending: Boolean = false,
    /** 流式输出的**未落库**文本（逐字显示用）。 */
    val streaming: String? = null,
    /** 已收到的思考内容（流式中，**逐字累积**）。 */
    val streamingReasoning: String? = null,
    /**
     * **生成中的这一回合**（未落库的快照）。
     *
     * 接了工具之后，一轮回复底下可能发好几次请求、穿插好几张工具卡，
     * 顺序是有意义的（正文 → 卡片 → 正文）。所以生成过程中推的不是
     * 一段拼接的文本，而是一整个回合 —— 界面拿它走**和正式回合相同的渲染路径**，
     * 于是"生成中看到的"和"生成完看到的"必然一致。
     *
     * `null` = 没有正在生成的回合。
     */
    val liveTurn: ChatTurn? = null,
    /**
     * 待确认写操作的**预览**：`callId → 记录列表`。
     *
     * 在这里而不是在界面里算，有两个原因：
     *
     * · `preview()` 是 `suspend` 的（要读库），界面不能直接调
     * · 算一次就够 —— 每帧重算会把数据库查穿
     */
    val pendingPreviews: Map<String, List<ChangeRecord>> = emptyMap(),
    /**
     * 提问工具的**问题**：`callId → 问题列表`。与 [pendingPreviews] 同样是懒算的。
     */
    val askQuestions: Map<String, List<AskQuestion>> = emptyMap(),
    /**
     * 提问工具的**作答**：`callId → (questionId → 作答)`。
     *
     * 只在内存里 —— 用户答到一半退出 App，回来是默认选项（设计稿的"默认选第一个"），
     * 那比留下半份答案更可预期。
     */
    val askAnswers: Map<String, Map<String, AskAnswer>> = emptyMap(),
    /**
     * 每条记录的**勾选状态**：`callId → 保留执行的 record id`。
     *
     * 放在状态里而不是界面的 `remember`：用户切到别的对话再回来，
     * 勾选不该被清掉 —— 那会让人以为自己的选择丢了。
     *
     * 缺省（没有这个 key）时表示**全选**，见 [acceptedFor]。
     */
    val pendingAccepted: Map<String, Set<String>> = emptyMap(),
    /** 可读的错误；null = 无错误。 */
    val error: String? = null,
    /** 还没配置模型 —— 界面要给出可操作的提示，而不是发一次必然失败的请求。 */
    val needsSetup: Boolean = false,
    /** 提问 id → 当前显示的是第几轮回答（设计稿的 `‹ 2 / 2 ›`）。 */
    val activeRounds: Map<String, Int> = emptyMap(),
    /**
     * **分支的下文暂存区**：`某条回答的 id → 它名下的那些消息`。
     *
     * ## 为什么需要它（用户 2026-10-04 的分支需求）
     *
     * 用户口径：
     *
     * > 「重新生成中间消息时，将原有内容保留为同一对话内的分支，**保留下方所有消息**。
     * > 通过消息选择器（`<n/m>`）来切换分支，重新生成中部消息后，
     * > **在这个新分支内清空下方的消息**」
     *
     * 所以"保留下方消息"的准确含义是 —— 下方消息**归它所属的那条分支**：
     *
     * ```
     * 分支 1（旧回答）  甲 → 乙 → 丙 → 丁
     *                        └─ 重新生成这里 ─┐
     * 分支 2（新回答）  甲 → 乙′          ← 下方是空的（"该消息作为该分支的最后一条"）
     *                        └─ 切回分支 1 → 丙、丁回来
     * ```
     *
     * 没有这个暂存区的话，丙、丁会直接接在 `乙′` 后面 ——
     * 两条分支共用同一段下文，切回去也分不出哪条是哪条。
     *
     * ## 只在内存里
     *
     * 它与 [activeRounds] 一样是**会话内的视图状态**。落库的是
     * "当前选中的那一轮 + 它名下的下文"（`replaceTurns` 已经把下文写回去了），
     * 所以退出重进看到的就是最后那个状态 —— 不需要把整张暂存表也存下来。
     */
    val branchTails: Map<String, List<ChatTurn>> = emptyMap(),
    /**
     * 正在**原位编辑**的那条消息 id。
     *
     * 用户口径（2026-10-03）：
     *  · AI 回复下方的铅笔 → 改的是**AI 回复的正文**
     *  · 点自己的消息本身 → 在**原位置**打开编辑窗
     *
     * 两者是同一套机制，只是入口不同。
     */
    val editingTurnId: String? = null,
    /**
     * 正在**就地重新生成**的那条回答 id。
     *
     * ## 为什么需要它（用户报的交互问题）
     *
     * 「重新生成不应该先在下面额外生成一段再合并到上方，应该直接在原位置开始
     * 又一次生成」—— 流式内容原本**永远**追加在列表末尾，于是重新生成时
     * 新回复先长在底部，完成后才"跳"回原来那条的位置，观感很跳。
     *
     * 现在把"该在原地替换谁"记下来：非空时 `ChatScreen` 会在**那条消息的位置**
     * 渲染流式内容，而不是在末尾另起一段。
     *
     * `null` = 普通发送 —— 那时流式内容确实该追加在末尾。
     */
    val replaceTurnId: String? = null,
    /**
     * 流式阶段：**思考中** 还是 **已经在写正文**。
     *
     * ## 为什么需要它（用户报的滚动问题）
     *
     * 用户口径：
     *
     * > 「流式输出的时候，如果开启思考就把最新内容放在底部，如果开始生成正文，
     * > 就停在正文刚好占满一页的位置，而不吸附在底部」
     *
     * 两个阶段的期望**正好相反**，所以必须能分辨它们：
     *
     * | 阶段 | 期望 |
     * |---|---|
     * | 思考中 | 贴着底部（思考是一行行长出来的，贴底才看得到最新的） |
     * | 正文中 | **不贴底** —— 让正文首行停在列表顶部，一屏正好是这段正文 |
     *
     * ⚠️ 判据是「**正文出现了没有**」，不是「思考结束了没有」：
     * 有些厂商会边想边写，那时正文已经在长，就该按正文处理。
     */
    val streamingInBody: Boolean = false,
) {
    val canSend: Boolean get() = input.isNotBlank() && !sending

    /**
     * 每问只保留当前选中那一轮之后的消息列表。
     *
     * 界面渲染与「发给模型的历史」**共用这一份** —— 后者尤其重要：
     * 若把同一问的多份回答都发过去，模型会看到自己对同一个问题答了两次。
     */
    val visibleTurns: List<ChatTurn> get() = Rounds.visible(turns, activeRounds)
}

/**
 * 对话页的 ViewModel。
 *
 * ## 「每次进入都是新对话」
 *
 * 用户明确要求（并说后面可能做成设置）。所以 [startNew] 在进入时被调用 ——
 * 它会**先落库当前对话**（若有内容），再开一个新的。
 *
 * ⚠️ 一个必须处理的边界：**空对话不该留在列表里**。
 * 若用户进来什么也没说就退出，那条空会话会污染列表（点几次就有几个空的）。
 * 所以 [startNew] 只在"当前对话有消息"时才真正新建。
 *
 * ## 三个动作的区别（设计稿操作行的四个图标）
 *
 * | 动作 | 做法 | 旧回答 |
 * |---|---|---|
 * | 重新生成 | 对同一问再要一份回答 | **留着**，成为可切换的下一轮 |
 * | 编辑提问 | 把提问填回输入框，改完发送时替换提问文字 | **留着**，成为可切换的轮次 |
 * | 分叉 | 以这条回答为终点，复制成一段新对话 | 原对话不动 |
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val store: ConversationStore,
    private val settingsStore: AiSettingsStore,
    private val send: SendChatMessage,
    private val client: AiClient,
    private val tools: ToolRegistry,
    /** 附件的磁盘存储（复制文件进来）。 */
    private val attachmentStorage: AttachmentStorage,
    /** 附件的索引行。 */
    private val attachments: AttachmentRepository,
    /**
     * 条目仓储 + 详情用例 —— 「关联项目」要用。
     *
     * 注入 `LoadItemDetailUseCase` 而不是自己拼 `Item` 的字段：
     * 与 `get_item_detail` 工具**同一套取数**，否则用户关联进来的内容
     * 和 AI 自己查到的会不一样。
     */
    private val items: ItemRepository,
    private val loadItemDetail: LoadItemDetailUseCase,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = store.conversations

    /**
     * 列表页要显示的会话 = 库里说过话的 ＋ **当前这条还没落库的空草稿**。
     *
     * 用户口径：「不要留下任何空对话，空对话只要有一个新对话就行」。
     * 空会话现在**根本不进数据库**（见 `ConversationStore.create`），
     * 所以这里手动把当前那条草稿补进列表 —— 界面上始终**至多一条**「新对话」，
     * 而且它就是用户此刻正在说话的那一条。
     */
    val conversationList: StateFlow<List<Conversation>> =
        combine(store.conversations, _ui) { stored, ui -> withDraft(stored, ui) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var sendJob: Job? = null

    /**
     * 正在新建会话。
     *
     * ⚠️ 为什么需要它：[startNew] 的守卫读的是 `_ui.value`，而 `store.create()`
     * 在协程里 —— 连续调用两次会**双双通过守卫**，一次开两条空会话。
     * 用这个标志把窗口堵上。
     */
    private var creatingNew = false

    /** 上一次用过的占位文案 —— 避免连续两次显示同一句。 */
    private var lastPlaceholder: String? = null

    init {
        viewModelScope.launch {
            settingsStore.refresh()
            store.refresh()
            startNew()
        }
        /*
         * 附件列表跟着库走（「会话文件」那一页显示它）。
         *
         * 用 `observeAll` 而不是"进菜单时查一次"：用户导入文件后
         * 那一页要**立刻**出现新文件，而导入是在另一个协程里完成的。
         */
        viewModelScope.launch {
            attachments.observeAll().collect { all ->
                _ui.value = _ui.value.copy(files = all.sortedByDescending { it.createdAt })
            }
        }
    }

    /**
     * 开一段新对话。
     *
     * 行为与「点入口按钮」一致（用户要求两者等效）。
     *
     * ## 两道闸，防的是同一件事：攒出一堆空会话
     *
     * 1. **当前已经是空会话** → 直接返回。否则点两次「＋」就多两条空会话。
     * 2. **正在新建中** → 直接返回。`store.create()` 是挂起的，连续两次调用
     *    会双双通过第 1 道闸（读到的都是旧状态），于是并发建出两条。
     *
     * 而且从这一版起，新建出来的会话**根本不会落库**（见 `ConversationStore.create`），
     * 所以即便这里漏了，用户没说话就走也不会留下痕迹。
     */
    fun startNew() {
        val current = _ui.value
        // 空对话不落库：进来什么都没说就退出，不该在列表里留一条空的
        if (current.conversation != null && current.turns.isEmpty()) {
            return
        }
        if (creatingNew) return
        creatingNew = true

        viewModelScope.launch {
            try {
                val settings = settingsStore.state.value
                val conversation = store.create()
                lastPlaceholder = ConversationPlaceholder.pick(lastPlaceholder)
                _ui.value = ChatUiState(
                    conversation = conversation,
                    placeholder = lastPlaceholder.orEmpty(),
                    deepThinking = settings.deepThinking,
                    showTokens = settings.showTokens,
                    needsSetup = !settings.hasChat,
                )
            } finally {
                creatingNew = false
            }
        }
    }

    /** 把当前这条还没落库的空草稿补进列表（至多一条，排在最前）。 */
    private fun withDraft(stored: List<Conversation>, ui: ChatUiState): List<Conversation> {
        val draft = ui.conversation ?: return stored
        // 已经有内容了 → 它一定在库里，以库为准
        if (ui.turns.isNotEmpty()) return stored
        if (stored.any { it.id == draft.id }) return stored
        return listOf(draft) + stored
    }

    fun open(conversationId: String) {
        viewModelScope.launch {
            val conversation = store.conversations.value.firstOrNull { it.id == conversationId } ?: return@launch
            val settings = settingsStore.state.value
            lastPlaceholder = ConversationPlaceholder.pick(lastPlaceholder)
            _ui.value = ChatUiState(
                conversation = conversation,
                turns = store.turnsOf(conversationId),
                placeholder = lastPlaceholder.orEmpty(),
                deepThinking = settings.deepThinking,
                showTokens = settings.showTokens,
                /*
                 * 恢复**分支选中态**（用户 2026-10-04 的分支需求）。
                 *
                 * 它决定了这条对话里当前显示哪些消息 —— 不恢复的话，
                 * 用户切到分支 1、退出、再进来会被"默认选最后一轮"重算，
                 * 看到的消息与他离开时的不一样。
                 */
                activeRounds = conversation.activeRounds,
                needsSetup = !settings.hasChat,
            )
        }
    }

    // ---------------------------------------------------------------- 输入

    fun setInput(text: String) {
        _ui.value = _ui.value.copy(input = text)
    }

    /**
     * 把用户选中的文件收进来。
     *
     * ## ⚠️ 收进来**不等于**发给模型（用户口径）
     *
     * > 「模型能读取的文件类型很有限，遇到这种，就不发给 ai，而是放入和
     * > 其他附件在一起的数据库，给 ai 文件索引，让 ai 根据用户指令安排
     * > 这些附件的位置。」
     *
     * 所以这里只做两件事：**复制文件 + 落一条索引**（归属 = `UNASSIGNED`）。
     * 文件内容不进对话 —— 那是下一步（用户说"放到哪儿"）才发生的事。
     *
     * ## 为什么归属是 UNASSIGNED 而不是"当前对话"
     *
     * 挂在对话上的话，对话一删文件就失去入口（而文件真的在磁盘上）。
     * `UNASSIGNED` 表示"还没有归属"，语义诚实。（详见 `AttachmentOwner`）
     *
     * ## 失败不打断
     *
     * `importAttachment` 读不到内容时返回 null（文件被删、权限没了…）。
     * 那种文件跳过即可，不该让整批导入失败 —— 用户选了 5 个，
     * 有 1 个坏了，另外 4 个照收。
     */
    fun attachFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            uris.forEach { uri ->
                val attachment = attachmentStorage.importAttachment(
                    uri = uri,
                    ownerType = AttachmentOwner.UNASSIGNED,
                    ownerId = "",
                ) ?: return@forEach
                attachments.upsert(attachment)
            }
            // 导入完直接把用户带到「会话文件」那一页 —— 他刚选完文件，
            // 下一步自然是确认"进来了没有"。停在根菜单上等于多按一次。
            _ui.value = _ui.value.copy(plusMenu = PlusMenuPage.Files)
        }
    }

    // ---------------------------------------------------------------- 加号菜单

    /** 打开「＋」菜单（用户 2026-10-04 定的形态）。 */
    fun openPlusMenu() {
        _ui.value = _ui.value.copy(plusMenu = PlusMenuPage.Root)
    }

    /** 在菜单里切到某一页。 */
    fun setPlusMenuPage(page: PlusMenuPage) {
        _ui.value = _ui.value.copy(plusMenu = page)
        if (page == PlusMenuPage.Link) refreshLinkCandidates()
    }

    /** 关掉菜单。 */
    fun closePlusMenu() {
        _ui.value = _ui.value.copy(plusMenu = null)
    }

    /**
     * 刷新「关联项目」的候选列表。
     *
     * ## 取最近的若干条，而不是全部
     *
     * 用户要关联的几乎总是"刚提到的那条"。全量列表在手机上是几千行，
     * 滑不到底反而找不到。取最近 [LINK_CANDIDATE_LIMIT] 条
     * （按 `updatedAt` 倒序）覆盖绝大多数场景。
     *
     * ⚠️ 要**跳过内部载体**（`HABIT_ALARM`）—— 那是习惯提醒的载体条目，
     * 界面上从来不显示它们（见 `AgendaBuilder` 的同一处理）。
     */
    private fun refreshLinkCandidates() {
        viewModelScope.launch {
            val all = items.getAll()
            val candidates = all
                // 内部载体不进候选（与时间轴/待办列表同一条纪律）
                .filter { it.kind != ItemKind.HABIT_ALARM }
                .sortedByDescending { it.updatedAt }
                .take(LINK_CANDIDATE_LIMIT)
                .map { item ->
                    LinkedItem(
                        id = item.id,
                        kindLabel = kindLabelOf(item.kind),
                        title = item.title.ifBlank { "（无标题）" },
                    )
                }
            _ui.value = _ui.value.copy(linkCandidates = candidates)
        }
    }

    /**
     * 选中 / 取消一个「关联项目」对象。
     *
     * ## ⚠️ 选中时就把正文取好（不等到发送）
     *
     * 用户在挑的时候看到的就是这份内容；发送时再查一次可能已经变了
     *（那条日程被改了），于是"我选的那个"和"AI 看到的"不是同一个东西。
     * 详见 [LinkedItem.detail] 的注释。
     *
     * 取不到正文时**仍然允许选中**（`detail` 为空）—— 由界面提示
     * "这条读不到内容"，而不是静默地发一段空上下文。
     */
    fun toggleLinkedItem(candidate: LinkedItem) {
        val current = _ui.value.linkedItems
        if (current.any { it.id == candidate.id }) {
            _ui.value = _ui.value.copy(linkedItems = current.filterNot { it.id == candidate.id })
            return
        }
        viewModelScope.launch {
            val detail = runCatching { loadLinkedDetail(candidate.id) }.getOrDefault("")
            _ui.value = _ui.value.copy(
                linkedItems = _ui.value.linkedItems + candidate.copy(detail = detail),
            )
        }
    }

    /** 清掉全部已关联对象。 */
    fun clearLinkedItems() {
        _ui.value = _ui.value.copy(linkedItems = emptyList())
    }

    /**
     * 取一条条目要附给 AI 的正文。
     *
     * 走 `LoadItemDetailUseCase`（与 `get_item_detail` 工具同一套取数）——
     * **不自己拼字段**，否则"AI 从工具里读到的"和"用户关联进来的"
     * 会是两份不一样的内容，那种不一致极难排查。
     */
    private suspend fun loadLinkedDetail(id: String): String {
        val detail = loadItemDetail(id) ?: return ""
        val item = detail.item
        return buildString {
            appendLine("【${kindLabelOf(item.kind)}】${item.title}")
            item.startAt?.let {
                appendLine("开始：${formatInstant(it)}")
            }
            item.endAt?.let {
                appendLine("结束：${formatInstant(it)}")
            }
            item.note?.takeIf { note -> note.isNotBlank() }?.let {
                appendLine("备注：$it")
            }
            if (detail.checklist.isNotEmpty()) {
                appendLine("清单：")
                detail.checklist.sortedBy { entry -> entry.orderIndex }.forEach { entry ->
                    appendLine("  [${if (entry.done) "x" else " "}] ${entry.title}")
                }
            }
            if (detail.attachments.isNotEmpty()) {
                appendLine("附件：" + detail.attachments.joinToString("、") { it.displayName })
            }
        }.trim()
    }

    /**
     * 条目类型的中文名（「关联项目」列表与附给 AI 的正文都用它）。
     *
     * ⚠️ 用 `else` 兜底而不是穷尽枚举：`ItemKind` 里有几个**内部类型**
     *（`HABIT_ALARM` / `WORKSPACE` / `TODO_GROUP`），它们不进候选列表，
     * 但枚举以后还会加值 —— 让编译期强制处理每一个新值不值得，
     * 兜底成"条目"即可。
     */
    private fun kindLabelOf(kind: ItemKind): String = when (kind) {
        ItemKind.EVENT -> "日程"
        ItemKind.TASK -> "待办"
        ItemKind.GOAL -> "目标"
        ItemKind.STEP -> "步骤"
        else -> "条目"
    }

    /** `Instant` → `MM-dd HH:mm`（附给 AI 的正文里，年份是噪音）。 */
    private fun formatInstant(at: Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(at)

    fun setMode(mode: ChatMode) {
        _ui.value = _ui.value.copy(mode = mode)
    }

    fun setDeepThinking(value: Boolean) {
        _ui.value = _ui.value.copy(deepThinking = value)
        // 持久化：用户不希望每次进来都重新打开它
        viewModelScope.launch { settingsStore.update { it.copy(deepThinking = value) } }
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    // ---------------------------------------------------------------- 会话管理

    /**
     * 重命名一段对话。
     *
     * 不为空校验：清空标题是合法的（列表会回落显示「新对话」），
     * 而"改完发现不生效"比"允许为空"更让人困惑。
     */
    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            store.rename(id, title.trim().take(TITLE_MAX))
            // 正在看的这段被改名了 → 顶栏标题要跟着变
            val current = _ui.value.conversation
            if (current?.id == id) {
                _ui.value = _ui.value.copy(
                    conversation = current.copy(title = title.trim().take(TITLE_MAX)),
                )
            }
        }
    }

    /** 置顶 / 取消置顶。 */
    fun togglePin(id: String) {
        viewModelScope.launch { store.togglePin(id) }
    }

    /**
     * 删除一段对话。
     *
     * 删掉的正好是当前这段时，**先把引用清掉再开一条新的** ——
     * 不能直接调 [startNew]：它的守卫会看到"当前会话为空"而提前返回，
     * 结果是 `conversation` 仍指向一条已经被删掉的会话，界面停在虚空上。
     */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (_ui.value.conversation?.id == id) {
                _ui.value = _ui.value.copy(conversation = null, turns = emptyList())
                startNew()
            }
        }
    }

    // ---------------------------------------------------------------- 批量（多选）

    /**
     * 批量删除对话（用户 2026-10-04 的「对话列表加入多选」）。
     *
     * ## 两个必须处理的边界
     *
     * 1. **删掉的包含当前正在看的那条** → 先清引用再开新对话。
     *    与 [deleteConversation] 同一个理由：`startNew` 的守卫会看到
     *    "当前会话为空"而提前返回，结果 `conversation` 仍指向一条已删掉的会话。
     * 2. 一次交给 `store.deleteAll` —— 循环调单条删除会造成写放大
     *    （见那边注释）。
     */
    fun deleteConversations(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            store.deleteAll(ids)
            val current = _ui.value.conversation
            if (current != null && current.id in ids) {
                _ui.value = _ui.value.copy(conversation = null, turns = emptyList())
                startNew()
            }
        }
    }

    /** 批量置顶 / 取消置顶。`pinned` 是**目标值**，不是"取反"。 */
    fun setConversationsPinned(ids: Collection<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch { store.setPinnedAll(ids, pinned) }
    }

    // ---------------------------------------------------------------- 轮次

    /** 这条回答属于哪一问（找不到就返回它自己 —— 旧数据没有归属）。 */
    private fun questionIdOf(turn: ChatTurn): String? =
        turn.answerTo ?: _ui.value.turns.lastOrNull { it.isUser && it.createdAt <= turn.createdAt }?.id

    /**
     * 这条回答所在的那一问，以及它当前是第几轮。
     *
     * 界面用它渲染 `‹ 2 / 2 ›`。
     *
     * ## ⚠️ 分组看的是**全部消息**，不是 `turns`
     *
     * 同 [showRound] 的注释：分支切换会把非当前分支的下文从 `turns` 里摘走，
     * 而"这条对话一共有几轮回答"不该因为切了分支就变少
     *（那会让切换器在切走之后**凭空消失**，用户再也切不回来）。
     */
    fun roundSwitchFor(turn: ChatTurn): RoundSwitch? {
        val state = _ui.value
        val questionId = turn.answerTo ?: return null
        val allTurns = state.turns + state.branchTails.values.flatten()
        val group = Rounds.groups(allTurns)[questionId] ?: return null
        // 只有一轮时不画 —— 那会是一个永远点不动的「‹ 1 / 1 ›」
        if (group.size < 2) return null
        val index = Rounds.selectedIndex(state.activeRounds[questionId], group.size)
        return RoundSwitch(
            index = index,
            total = group.size,
            onPrev = { showRound(questionId, index - 1) },
            onNext = { showRound(questionId, index + 1) },
        )
    }

    // ---------------------------------------------------------------- 消息动作

    /**
     * 开始**原位编辑**一条消息（AI 回复走铅笔，自己的消息走点气泡）。
     *
     * 只是把"哪条在编辑"记下来，编辑框由 [ChatTurnRow] 就地渲染 ——
     * 不再借用底部输入框。上一版借输入框改的是**提问**，
     * 与用户要的"改 AI 回复"不是一回事。
     */
    fun beginEdit(turn: ChatTurn) {
        _ui.value = _ui.value.copy(editingTurnId = turn.id, error = null)
    }

    fun cancelEdit() {
        _ui.value = _ui.value.copy(editingTurnId = null)
    }

    /**
     * 保存原位编辑的结果。
     *
     * 空文本 = 删除这条消息。这是与"清空后点保存"最一致的语义；
     * 留一条空消息在对话里没有意义（而且它会被 [SendChatMessage.buildMessages]
     * 当成空内容跳过，历史里凭空少一轮，更难解释）。
     */
    fun commitEdit(turnId: String, text: String) {
        val conversation = _ui.value.conversation ?: return
        val trimmed = text.trim()
        val nextTurns = _ui.value.turns
            .filterNot { it.id == turnId && trimmed.isEmpty() }
            .map { if (it.id == turnId) it.withEditedContent(trimmed) else it }

        _ui.value = _ui.value.copy(editingTurnId = null, turns = nextTurns)
        viewModelScope.launch { store.replaceTurns(conversation.id, nextTurns) }
    }

    /**
     * 把改后的正文写进回合 —— **连 [ChatTurn.rounds] 一起改**。
     *
     * ## ⚠️ 只改 `content` 是不够的（会看起来毫无反应）
     *
     * 界面渲染的是 `rounds`，`content` 只是"最后一段正文"的副本
     *（复制/搜索/导出用）。接了工具之后，一条回合的正文分散在 `rounds` 里，
     * 所以只改 `content` 的话，用户点完保存会发现**界面上的字一个都没变**。
     *
     * 编辑框里显示的是 `content`（= 最后一段），所以这里也改**最后一段有正文的**
     * 那一轮；若整条回合还没有任何正文，就把它放进最后一轮。
     */
    private fun ChatTurn.withEditedContent(text: String): ChatTurn {
        if (rounds.isEmpty()) return copy(content = text)

        val lastWithText = rounds.indexOfLast { !it.content.isNullOrBlank() }
        val target = if (lastWithText >= 0) lastWithText else rounds.lastIndex
        val nextRounds = rounds.mapIndexed { index, round ->
            if (index == target) round.copy(content = text.ifBlank { null }) else round
        }
        return copy(content = text, rounds = nextRounds)
    }

    /**
     * 复制：正文取出来交给界面写剪贴板。
     *
     * ⚠️ **不能只取 `content`** —— 接了工具之后 `content` 只是**最后一段**正文
     *（界面上「已思考」块与正文是按 `rounds` 分段渲染的）。
     * 只取它的话，复制出来的内容会**丢掉工具调用之前说的那几句**，
     * 比如「好的，我来创建一个日程。」。
     */
    fun textToCopy(turn: ChatTurn): String = turn.fullText

    /**
     * 重新生成：对同一问再要一份回答（旧回答成为可切换的上一轮）。
     *
     * ⚠️ 传 `replaceTurnId` —— 让流式内容**就地**长在这条回答的位置上，
     * 而不是在列表末尾另起一段（用户明确要求："应该直接在原位置开始又一次生成"）。
     *
     * ## ⚠️ 重新生成**中间**消息 = 开一条分支（用户 2026-10-04 需求）
     *
     * 用户原话：
     *
     * > 「重新生成中间消息（前后都有消息）时，将原有内容保留为同一对话内的分支，
     * > **保留下方所有消息**。通过消息选择器（`<n/m>`）来切换分支，
     * > 重新生成中部消息后，**在这个新分支内清空下方的消息**，
     * > 该消息作为该分支的最后一条消息」
     *
     * ## 这段话的准确含义（我拆成四条）
     *
     * 1. 旧回答**留在这个对话里**（不删、不复制成新对话）→ 它就是"分支 1"
     * 2. 新回答是"分支 2"，用已有的 `‹ n / m ›` 切换器翻
     * 3. **两条分支各自记着自己下方有哪些消息** → 切回分支 1 时，
     *    原来在它下面的那几条要**回来**
     * 4. 在分支 2 里，该回答**下方是空的**（它暂时是这条分支的最后一条）
     *
     * ## ⚠️ 第 3 条最容易做漏，也是"保留下方所有消息"的真正意思
     *
     * 直觉会以为"保留下方消息"= 什么都别动。但那会导致分支 2 一生成出来，
     * 下面的消息就接在**新回答**后面 —— 于是分支 1 的下文被"借"给了分支 2，
     * 两条分支的下文混在一起，切回去也分不出哪条是哪条了。
     *
     * 所以下方消息必须**按分支归属**：它们属于"当时选中的那一轮"。
     * 实现见 [BranchStash]：把"某一轮的下文"暂存起来，
     * 切到某轮时把它自己那份下文接回去。
     */
    fun regenerate(turn: ChatTurn) {
        val state = _ui.value
        if (state.sending) return
        val questionId = questionIdOf(turn) ?: return
        val question = state.turns.firstOrNull { it.id == questionId } ?: return

        /*
         * 只有**中间**的助手回答才走分支逻辑。
         *
         * 最后一条回答重新生成时，它下面本来就没有消息 —— 分支与不分支
         * 结果完全一样，那就**不要**走这条路径（多存一份 stash 是白占空间，
         * 而且会让"有没有分支"的判断在没有分支时也成立）。
         */
        val isMiddle = state.turns.lastOrNull()?.id != turn.id

        if (isMiddle) {
            stashTailBelow(turn.id)
        }

        ask(question, replaceTurnId = turn.id)
    }

    /**
     * 把**某条回答之下**的消息从当前列表里摘下来，暂存到它名下。
     *
     * 见 [regenerate] 的长注释：这是"分支各自带着自己的下文"的实现。
     *
     * 只摘**可见列表**里排在它后面的部分（`visibleTurns`）——
     * 别的分支的下文本来就不在可见列表里，不该被顺手摘走。
     */
    private fun stashTailBelow(turnId: String) {
        val state = _ui.value
        val visible = state.visibleTurns
        val index = visible.indexOfFirst { it.id == turnId }
        if (index < 0) return

        val tail = visible.drop(index + 1)
        if (tail.isEmpty()) return

        val tailIds = tail.map { it.id }.toSet()
        _ui.value = state.copy(
            // 从全量里摘掉（不只是可见列表）—— 它们是"这一条分支的下文"，
            // 不该留在主列表里被别的分支看到
            turns = state.turns.filterNot { it.id in tailIds },
            branchTails = state.branchTails + (turnId to tail),
        )
    }

    /**
     * 切换某一问显示第几轮回答。
     *
     * ## 除了切下标，还要**换下文**（分支功能的核心）
     *
     * 见 [regenerate] 的长注释。切到某一轮时：
     *
     * ```
     * 1. 把当前这一轮的"下文"暂存起来（它属于它）
     * 2. 换到目标轮
     * 3. 把目标轮自己那份下文接回列表末尾
     * ```
     *
     * 不换下文的话，两条分支会共用同一段下文 —— 切来切去看起来一样，
     * 用户会以为切换器坏了。
     */
    fun showRound(questionId: String, index: Int) {
        val state = _ui.value

        /*
         * ⚠️ 分组必须基于**全部消息**（主列表 ∪ 所有分支暂存的下文），
         * 而不是只看 `state.turns`。
         *
         * 因为分支切换会**把非当前分支的下文从 `turns` 里摘走**
         *（见 [stashTailBelow]）。只看 `turns` 的话：
         *
         * · 被摘走的那些回答**不在分组里** → `group.size` 变小
         * · 于是「2 / 2」可能变成「1 / 1」，切换器凭空消失
         * · 更糟的是 `group.at(target)` 取到 null → 切不过去
         *
         * 一句话：**`turns` 是"当前显示的那一份"，而轮次分组看的是"这条对话有哪些"**。
         * 这两者是不同的集合，混淆了就会出现"分支越切越少"。
         */
        val allTurns = state.turns + state.branchTails.values.flatten()
        val group = Rounds.groups(allTurns)[questionId] ?: return
        val target = Rounds.selectedIndex(index, group.size)
        if (target < 0) return

        // ---- 1. 摘掉当前这一轮的下文（如果它下面有别的东西）
        val currentIndex = Rounds.selectedIndex(state.activeRounds[questionId], group.size)
        val currentAnswer = group.at(currentIndex)

        var workingTurns = state.turns
        var workingTails = state.branchTails
        var currentTailIds: Set<String> = emptySet()

        if (currentAnswer != null) {
            val visible = Rounds.visible(workingTurns, state.activeRounds)
            val at = visible.indexOfFirst { it.id == currentAnswer.id }
            if (at >= 0) {
                val tail = visible.drop(at + 1)
                if (tail.isNotEmpty()) {
                    currentTailIds = tail.map { it.id }.toSet()
                    workingTurns = workingTurns.filterNot { it.id in currentTailIds }
                    workingTails = workingTails + (currentAnswer.id to tail)
                }
            }
        }

        // ---- 2. 换到目标轮
        val nextActive = state.activeRounds + (questionId to target)

        // ---- 3. 把目标轮自己那份下文接回去
        val targetAnswer = group.at(target)
        val restored = targetAnswer?.let { workingTails[it.id] }.orEmpty()
        if (restored.isNotEmpty()) {
            workingTurns = workingTurns + restored
            workingTails = workingTails - targetAnswer!!.id
        }

        _ui.value = state.copy(
            turns = workingTurns,
            activeRounds = nextActive,
            branchTails = workingTails,
        )

        /*
         * ⚠️ 落库。
         *
         * `turns` 的这一增一删是**真实的对话内容变化**（分支切换决定了
         * 这条对话里现在有哪些消息），不落库的话退出再进来就回到切换前的样子。
         * 而且它必须与 `activeRounds` 一起落 —— 只落消息不落"选中哪一轮"，
         * 下次进来会按"默认选最后一轮"重算，与用户刚才看到的对不上。
         */
        val conversation = state.conversation ?: return
        viewModelScope.launch {
            store.saveBranchState(
                conversationId = conversation.id,
                branchTails = workingTails,
                activeRounds = nextActive,
            )
        }
    }

    /**
     * 分叉：以这条回答为终点，复制出一段**新对话**。
     *
     * ## 为什么是"复制成新对话"而不是"在原对话里分支"
     *
     * 会话是这个 App 的一等概念（有列表、能置顶、能删）。
     * 在原对话里维护一棵分支树需要一整套树形 UI，而用户还没有对那套 UI 表过态；
     * 复制成新对话是**当下就能用**的语义：原对话不动，新对话从这儿继续聊。
     */
    fun branchFrom(turn: ChatTurn) {
        val state = _ui.value
        val conversation = state.conversation ?: return
        val visible = state.visibleTurns
        val cutIndex = visible.indexOfFirst { it.id == turn.id }
        if (cutIndex < 0) return

        viewModelScope.launch {
            val branched = store.create()
            val slice = visible.take(cutIndex + 1)

            // 复制到新对话时**全部换新 id**，并顺手把 `answerTo` 重指向新 id ——
            // 不重指向的话，新对话里的轮次切换器会指向一段不存在的提问。
            val idMap = slice.associate { it.id to java.util.UUID.randomUUID().toString() }
            val copied = slice.map { source ->
                source.copy(
                    id = idMap.getValue(source.id),
                    conversationId = branched.id,
                    answerTo = source.answerTo?.let { idMap[it] },
                )
            }

            store.replaceTurns(branched.id, copied)
            copied.firstOrNull { it.isUser }?.content?.trim()?.take(20)?.takeIf { it.isNotEmpty() }
                ?.let { store.rename(branched.id, it) }

            val current = _ui.value
            lastPlaceholder = ConversationPlaceholder.pick(lastPlaceholder)
            _ui.value = ChatUiState(
                conversation = branched,
                turns = store.turnsOf(branched.id),
                placeholder = lastPlaceholder.orEmpty(),
                deepThinking = current.deepThinking,
                showTokens = current.showTokens,
                needsSetup = current.needsSetup,
            )
        }
    }

    // ---------------------------------------------------------------- 发送

    /**
     * 发一条消息并流式接收回复。
     *
     * ## 两条消息的落库时机不同
     *
     * · **用户消息**：立刻落库。否则用户切走再回来会发现自己说的话没了。
     * · **AI 回复**：等流式**结束**才落库。中途落库会让历史里留下半句话，
     *   而重新进入对话时那半句会被当成完整回复。
     *   流式期间用 `streaming` 字段单独显示，不走 `turns`。
     */
    fun sendMessage() {
        val state = _ui.value
        if (!state.canSend) return
        val conversation = state.conversation ?: return

        if (!hasUsableModel()) return

        val text = state.input.trim()

        /*
         * 「关联项目」的内容**并进这一条用户消息**（用户口径：
         * 「把选中对象的完整内容带进这一轮上下文，AI 直接能引用」）。
         *
         * 拼接规则抽成 [buildPromptWithLinks]（顶层纯函数）—— 见那边的注释。
         */
        val withLinks = buildPromptWithLinks(text, state.linkedItems)

        val userTurn = ChatTurn(
            conversationId = conversation.id,
            role = ChatTurn.Role.USER,
            // ⚠️ 气泡里显示的是**用户自己打的字**，不含拼进去的资料 ——
            // 那一大段资料塞进气泡会让对话看起来乱七八糟。
            // 但它**必须**进模型的上下文，所以两者分开：
            // 气泡用 text，发给模型的用 withLinks（见 ask 的 override）
            content = text,
            createdAt = System.currentTimeMillis(),
        )

        _ui.value = state.copy(
            input = "",
            error = null,
            turns = state.turns + userTurn,
            // 关联是一次性的：发出去就清掉
            linkedItems = emptyList(),
            plusMenu = null,
        )

        viewModelScope.launch {
            store.append(userTurn)
        }

        ask(userTurn, promptOverride = withLinks)
    }

    /** 有没有可用的对话模型；没有就给出可读提示并返回 false。 */
    private fun hasUsableModel(): Boolean {
        val settings = settingsStore.state.value
        val config = settings.chat()
        if (config == null || !config.isUsable) {
            _ui.value = _ui.value.copy(
                needsSetup = true,
                error = "还没有可用的对话模型，请先到 AI 设置里配置",
            )
            return false
        }
        return true
    }

    /**
     * 针对某条**提问**要一份回答。
     *
     * 普通发送与重新生成都走这里 —— 区别只在"要不要原地替换某条旧回答"。
     *
     * @param question 要回答的那条用户消息
     * @param replaceTurnId 非空 = **就地**重新生成：流式内容显示在这条旧回答的位置上
     *   （`null` = 普通发送，流式内容追加在列表末尾）
     */
    private fun ask(
        question: ChatTurn,
        replaceTurnId: String? = null,
        /**
         * 发给模型的**这一问的正文**覆盖值（null = 用 `question.content`）。
         *
         * ## 为什么需要它
         *
         * 用户「关联项目」时，气泡里显示的是他自己打的字，
         * 而发给模型的要把关联的资料一起带上 —— 两者**必须分开**：
         *
         * | | 内容 |
         * |---|---|
         * | 气泡（`question.content`） | 用户打的那句话 |
         * | 请求（本参数） | 用户那句话 + `---` + 关联资料 |
         *
         * 把资料塞进 `content` 会让气泡变成一大坨；
         * 只发 `content` 则关联功能完全无效（用户选了却没起作用）。
         */
        promptOverride: String? = null,
    ) {
        val conversation = _ui.value.conversation ?: return
        val settings = settingsStore.state.value
        val config = settings.chat() ?: return

        // 历史 = 该提问**之前**的消息 + 该提问自己。
        // 之后的消息不发（那些还没发生），同一问的其它轮次也不发。
        val history = _ui.value.visibleTurns.let { visible ->
            val index = visible.indexOfFirst { it.id == question.id }
            val head = if (index >= 0) visible.take(index + 1) else visible
            /*
             * 把即将发出去的那一条换成 `promptOverride`。
             *
             * ⚠️ **必须在这里换**（而不是改 `question` 本身）——
             * `question` 已经落库、也已经在气泡里渲染了，
             * 改它会把用户自己的话也改掉。
             */
            if (promptOverride != null) {
                head.map { if (it.id == question.id) it.copy(content = promptOverride) else it }
            } else {
                head
            }
        }

        _ui.value = _ui.value.copy(
            sending = true,
            error = null,
            streaming = "",
            streamingReasoning = null,
            streamingInBody = false,
            replaceTurnId = replaceTurnId,
        )

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()

            val result = send(
                config = config,
                settings = settings,
                history = history,
                answerTo = question.id,
                // 逐次对话的开关：跟着输入框那颗 chip 走，而不是设置页的默认值 ——
                // 用户在输入框上拨动的就是这一次的意图。
                deepThinking = _ui.value.deepThinking,
                // 聊天模式**不带任何工具**（机制上做不到，不是靠提示词约束）
                toolsEnabled = _ui.value.mode == ChatMode.ASSISTANT,
                onDelta = { delta ->
                    /*
                     * 正文一到就把阶段切成「正文中」。
                     *
                     * ⚠️ 用 `+` 累积而不是直接赋值：`onDelta` 是**逐片**回调的。
                     * 而阶段标记一旦置 true 就不再回落 —— 有些厂商会先写半句正文、
                     * 再回头补思考，那时不该退回"贴底"模式（会让页面弹一下）。
                     */
                    _ui.value = _ui.value.copy(
                        streaming = (_ui.value.streaming ?: "") + delta,
                        streamingInBody = true,
                    )
                },
                onReasoning = { reasoning ->
                    // 增量累积：AiClient 现在**逐片**回调推理内容
                    _ui.value = _ui.value.copy(
                        streamingReasoning = (_ui.value.streamingReasoning ?: "") + reasoning,
                    )
                },
                onProgress = { live ->
                    // 回合快照：界面用它渲染正文与工具卡，顺序与生成完一致
                    _ui.value = _ui.value.copy(liveTurn = live)
                },
            )

            result.fold(
                onSuccess = { reply ->
                    val finished = reply.copy(
                        conversationId = conversation.id,
                        answerTo = question.id,
                        reasoningMillis = System.currentTimeMillis() - startedAt,
                        reasoning = reply.reasoning ?: _ui.value.streamingReasoning,
                    )
                    // 落库：AI 回复等流式结束才写，避免历史里留下半句话
                    store.append(finished)

                    // 新回答成为选中轮（否则用户重新生成后还看到旧的那一份）
                    val group = Rounds.groups(_ui.value.turns + finished)[question.id]
                    val nextIndex = ((group?.size ?: 1) - 1).coerceAtLeast(0)
                    val nextActive = _ui.value.activeRounds + (question.id to nextIndex)
                    val nextTurns = _ui.value.turns + finished

                    _ui.value = _ui.value.copy(
                        sending = false,
                        streaming = null,
                        streamingReasoning = null,
                        streamingInBody = false,
                        // 快照让位给落库后的真实回合，否则界面上会出现两条一样的回复
                        liveTurn = null,
                        // 结束时就地替换的标记也要清掉，否则这条还会被当成"正在生成"
                        replaceTurnId = null,
                        turns = nextTurns,
                        activeRounds = nextActive,
                    )

                    /*
                     * 把"选中了新的这一轮"落库（分支功能）。
                     *
                     * 重新生成**中间**消息之后，新分支成为选中 —— 不落库的话
                     * 用户下次进来会按"默认选最后一轮"重算，而那时最后一轮
                     * 已经**不是**这条新分支了（它是被重新生成的那条，
                     * 位置在中间），于是看到的是另一条分支。
                     *
                     * `branchTails` 一并写回：当前分支的下文（若有）
                     * 也要保持在库里。
                     */
                    store.saveBranchState(
                        conversationId = conversation.id,
                        branchTails = _ui.value.branchTails,
                        activeRounds = nextActive,
                    )
                },
                onFailure = { e ->
                    // 失败时也要清 replaceTurnId —— 旧回答仍在 turns 里，会照常显示出来
                    _ui.value = _ui.value.copy(
                        sending = false,
                        streaming = null,
                        streamingReasoning = null,
                        streamingInBody = false,
                        liveTurn = null,
                        replaceTurnId = null,
                        error = describe(e),
                    )
                },
            )
        }
    }

    /** 停止正在进行的生成。 */
    fun stopGenerating() {
        sendJob?.cancel()
        sendJob = null
        _ui.value = _ui.value.copy(
            sending = false,
            streaming = null,
            streamingReasoning = null,
            streamingInBody = false,
            liveTurn = null,
        )
    }

    // ---------------------------------------------------------------- 待确认的写操作

    /**
     * 某条待确认记录当前**勾选了哪些**。
     *
     * 没有显式选择过时返回**全部**（设计稿的默认全选）。
     * 所以"没勾任何一项"只能通过显式取消得到，不会和"还没算出来"混淆。
     */
    fun acceptedFor(callId: String): Set<String> =
        _ui.value.pendingAccepted[callId]
            ?: _ui.value.pendingPreviews[callId].orEmpty().map { it.id }.toSet()

    /**
     * 确保某条待处理记录的**预览**已经算好（写操作算变更清单，提问算问题列表）。
     *
     * 界面在渲染待确认卡时调它；已经算过就直接返回（幂等）。
     * 算不出来时留一个**空列表**占位 —— 空列表与"还没算"是两回事，
     * 前者会让界面显示只读卡而不是一直转圈。
     */
    fun ensurePendingPreview(callId: String) {
        if (_ui.value.pendingPreviews.containsKey(callId)) return
        if (_ui.value.askQuestions.containsKey(callId)) return

        val state = _ui.value
        val record = state.turns.asSequence()
            .flatMap { it.toolCalls.asSequence() }
            .firstOrNull { it.id == callId } ?: return
        val args = record.argumentsJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return

        when (val tool = tools.find(record.name)) {
            is ConfirmableTool -> viewModelScope.launch {
                val records = runCatching { tool.preview(args) }.getOrDefault(emptyList())
                _ui.value = _ui.value.copy(
                    pendingPreviews = _ui.value.pendingPreviews + (callId to records),
                    // 默认全选；用户随后可以逐条取消
                    pendingAccepted = _ui.value.pendingAccepted + (callId to records.map { it.id }.toSet()),
                )
            }

            is AskingTool -> viewModelScope.launch {
                val questions = runCatching { tool.questions(args) }.getOrDefault(emptyList())
                _ui.value = _ui.value.copy(
                    askQuestions = _ui.value.askQuestions + (callId to questions),
                    // 默认选中每题的第一个选项（用户口径）
                    askAnswers = _ui.value.askAnswers + (
                        callId to questions.associate { q -> q.id to AskAnswer(chosen = setOf(0)) }
                        ),
                )
            }

            else -> Unit
        }
    }

    /** 勾选/取消某条记录。 */
    fun togglePending(callId: String, recordId: String) {
        val current = acceptedFor(callId)
        val next = if (recordId in current) current - recordId else current + recordId
        _ui.value = _ui.value.copy(pendingAccepted = _ui.value.pendingAccepted + (callId to next))
    }

    /** 更新某道题的作答（点选项、"其他"输入框的改动都走这里）。 */
    fun setAskAnswer(callId: String, questionId: String, answer: AskAnswer) {
        val current = _ui.value.askAnswers[callId].orEmpty()
        _ui.value = _ui.value.copy(
            askAnswers = _ui.value.askAnswers + (callId to (current + (questionId to answer))),
        )
    }

    /**
     * 用户点了「应用选中的 N 项」。
     *
     * ⚠️ 收**列表** —— 合并卡会把一次确认里的所有调用一起交过来，
     * 而它们必须一次处理完再继续（否则 N 张卡 = N 次 AI 请求）。
     */
    fun applyPendingChanges(callIds: List<String>) =
        settlePendingBatch(callIds, acceptedFor = { acceptedFor(it) }) { record, keep ->
            val tool = tools.find(record.name) as? ConfirmableTool
            if (tool == null) {
                ToolOutcome(forModel = "无法执行 `${record.name}`：工具不存在。请重新发起这次操作。")
            } else {
                val args = JSONObject(record.argumentsJson.orEmpty())
                runCatching { tool.apply(args, keep) }.getOrElse { e ->
                    ToolOutcome(forModel = "执行 `${record.name}` 失败：${e.message ?: e::class.simpleName}")
                }
            }
        }

    /** 用户点了提问卡的「提交」。 */
    fun submitAsk(callId: String) = resolvePendingAsk(callId)

    /** 用户点了「驳回」——**什么都不执行**，并如实告诉模型。 */
    fun rejectPendingChanges(callIds: List<String>) =
        resolvePendingBatch(callIds, accepted = null)

    /**
     * 处理一次待确认的写操作，然后**带着结果继续这轮对话**。
     *
     * ## 为什么"继续"不需要专门的恢复机制
     *
     * 待确认的记录落库时就已经在那条助手回合里了（`status = PENDING`）。
     * 这里只做三件事：执行（或驳回）→ 把结果补进那条记录 → **再调一次 [send]**。
     *
     * 第二次调用时 `buildMessages` 会从 `rounds` 重建出完整的协议结构
     *（`assistant(tool_calls)` + 对应的 `role="tool"` 结果），
     * 模型看到工具结果后自然接着往下说。所以**没有"挂起中的会话"这种状态**，
     * 也不需要它 —— 那会引入一堆"什么时候算过期"的问题。
     *
     * ## 失败也要补结果
     *
     * 无论成功、驳回还是执行出错，都必须往记录里写一条 `forModel` ——
     * 协议要求声明过的 `tool_calls` 每条都有结果，缺一条整个请求都会被拒。
     */
    private fun resolvePending(callId: String, accepted: Set<String>?) =
        resolvePendingBatch(listOf(callId), accepted)

    /**
     * 驳回**一批**调用。
     *
     * 与 [applyPendingChanges] 的区别只有一个：`accepted = null`，
     * 于是 [settlePendingBatch] 给每条调用产出一句"用户驳回了"。
     * 走同一个入口是刻意的 —— 两条路都必须在**全部处理完之后只继续一次**。
     */
    private fun resolvePendingBatch(callIds: List<String>, accepted: Set<String>?) =
        settlePendingBatch(callIds, acceptedFor = { accepted }) { record, _ ->
            ToolOutcome(
                forModel = "用户驳回了这次改动，**没有执行任何写入**。" +
                    "不要重试同一改动，除非用户明确要求。",
                forUser = ToolDetail(arguments = record.argumentsSummary, result = "已驳回"),
            )
        }

    /**
     * 用户提交了提问卡的作答。
     *
     * 与写操作的流程完全一样（挂起 → 出卡 → 补结果 → 再发一次），
     * 只是"产生结果"那一步换成把答案组织成给模型的文本。
     */
    private fun resolvePendingAsk(callId: String) {
        val answers = _ui.value.askAnswers[callId].orEmpty()
        settlePendingBatch(callIds = listOf(callId), acceptedFor = { emptySet() }) { record, _ ->
            val tool = tools.find(record.name) as? AskingTool
            if (tool == null) {
                ToolOutcome(forModel = "无法提交对 `${record.name}` 的作答：工具不存在。")
            } else {
                val args = JSONObject(record.argumentsJson.orEmpty())
                runCatching { tool.answer(args, answers) }.getOrElse { e ->
                    ToolOutcome(
                        forModel = "提交作答失败：${e.message ?: e::class.simpleName}",
                    )
                }
            }
        }
    }

    /**
     * 一次处理**一批**待确认的调用。
     *
     * ## ⚠️ 为什么必须批量（用户报的「40 次确认」）
     *
     * 模型一条回复里可能发 N 个 `tool_calls`（批量改 40 张卡就是 40 个）。
     * 界面上它们已经合并成**一张卡**了，但如果这里还是逐个处理：
     *
     * · 每次调用都会走一遍「执行 → 落库 → **再发一次请求**」
     * · 40 张卡 = 40 次 AI 请求 —— 比 40 次点击更糟
     *
     * 所以批量执行完**所有**调用之后，**只继续一次**。
     *
     * ## 执行失败也要继续
     *
     * 某一条失败不影响其余各条（它们本来就是独立的对象）。
     * 全部执行完，把每一组结果分别写回记录，再让模型接着说。
     *
     * @param callIds 一次确认里的全部调用
     * @param acceptedFor 每条调用各自"保留了哪些记录"。返回 null = **驳回**
     *   （整批一起驳回）。
     */
    private fun settlePendingBatch(
        callIds: List<String>,
        acceptedFor: (String) -> Set<String>?,
        produce: suspend (record: ToolCallRecord, accepted: Set<String>) -> ToolOutcome,
    ) {
        val state = _ui.value
        if (state.sending) return
        if (callIds.isEmpty()) return

        val conversation = state.conversation ?: return
        val turn = state.turns.lastOrNull { t ->
            t.toolCalls.any { it.id in callIds && it.status == ToolCallRecord.Status.PENDING }
        } ?: return

        sendJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(sending = true, error = null)

            /** 逐条算出结果 —— 一条失败不影响别的。 */
            val resolvedById = callIds.associateWith { callId ->
                val record = turn.toolCalls.first { it.id == callId }
                val accepted = acceptedFor(callId)
                val args = record.argumentsJson?.let { runCatching { JSONObject(it) }.getOrNull() }

                val outcome = when {
                    accepted == null -> ToolOutcome(
                        forModel = "用户驳回了这次改动，**没有执行任何写入**。" +
                            "不要重试同一改动，除非用户明确要求。",
                        forUser = ToolDetail(arguments = record.argumentsSummary, result = "已驳回"),
                    )

                    args == null -> ToolOutcome(
                        forModel = "无法处理 `${record.name}`：参数已丢失。请重新发起这次操作。",
                    )

                    else -> produce(record, accepted)
                }

                record.copy(
                    status = if (accepted == null) {
                        ToolCallRecord.Status.FAILED
                    } else {
                        ToolCallRecord.Status.DONE
                    },
                    resultForModel = outcome.forModel,
                    resultSummary = outcome.forUser?.result ?: outcome.forModel.take(120),
                )
            }

            /*
             * 先把**所有**结果写回那条回合并落库，再继续 —— 顺序反了的话，
             * 万一继续失败，用户会看到一批"永远待确认"的卡。
             */
            val updatedTurn = turn.copy(
                toolCalls = turn.toolCalls.map { resolvedById[it.id] ?: it },
            )
            val nextTurns = state.turns.map { if (it.id == turn.id) updatedTurn else it }
            store.replaceTurns(conversation.id, nextTurns)

            /*
             * ## ⚠️ 状态**延迟一步**清掉（用户报的"点完没有动画就消失"）
             *
             * 用户原话：
             *
             * > 「提问窗口和修改确认窗口在操作后**没有动画就消失**」
             *
             * 根因是这里的顺序：`turns` 一更新，`status` 就从 `PENDING` 翻成
             * `DONE`，`ToolCallCard` 的 `body` 立刻算成 `ToolBody.Summary`
             * —— 提问卡／变更预览卡**当场被换掉**，连一帧的退场动画都没有，
             * 因为需要播动画的那个可见元素**已经不在组合里了**。
             *
             * `AnimatedContent` 只能对"新目标已经出现、旧目标还在组合里"的
             * 情况播退场；这里旧目标是被**同一次状态更新**抹掉的，它无能为力。
             *
             * ## 修法：先翻状态，**隔一小段**再清预览/作答
             *
             * ```
             * t=0      turns 更新（status → DONE）   ⇒ 卡片开始播收起动画
             * t=ExitMillis  清掉 previews/questions  ⇒ 动画已经播完了
             * ```
             *
             * 用 `VMotion.ExitMillis`（220ms）而不是随手一个数：
             * 那正是 `AnimatedContent` 退场所用的时长，两者对齐才刚好接得上 ——
             * 清早了会截断动画，清晚了会在界面上留下一段"卡片已经收好了、
             * 但数据还在"的空窗（虽然看不见，但会让 `onEnsure` 觉得"已经算过"）。
             */
            _ui.value = _ui.value.copy(turns = nextTurns)

            viewModelScope.launch {
                kotlinx.coroutines.delay(VMotion.ExitMillis.toLong())
                _ui.value = _ui.value.copy(
                    pendingPreviews = _ui.value.pendingPreviews - callIds.toSet(),
                    pendingAccepted = _ui.value.pendingAccepted - callIds.toSet(),
                    askQuestions = _ui.value.askQuestions - callIds.toSet(),
                    askAnswers = _ui.value.askAnswers - callIds.toSet(),
                )
            }

            continueAfterPending(conversation, nextTurns)
        }
    }

    /**
     * 工具结果补好之后，再发一次请求让模型接着说。
     *
     * ## ⚠️ 续写要**并回原来那一轮**，不能新开一条助手回合
     *
     * 一开始这里是 `store.append(finished)` —— 那会造出**两条 `answerTo` 相同**的
     * 助手回合，而 [Rounds.groups] 会把它们当成"同一问的两份回答"：
     *
     * · [Rounds.visible] 只显示**最后一轮** → 带工具卡的那条**从界面上消失**
     * · 轮次切换器冒出「2 / 2」，而用户根本没点过"重新生成"
     *
     * 这在协议上也讲不通：暂停前后本来就是**同一轮回复**，
     * 只是中间等了一次用户操作 —— `rounds` 里就是一段接一段。
     *
     * 所以这里把续写的轮次与工具记录**追加到原来那条回合**上。
     *
     * ## 与普通发送的另一处区别
     *
     * 不新增用户消息，且复用已有的 `answerTo`（这条回答仍然是在回答当初那个问题）。
     */
    private suspend fun continueAfterPending(conversation: Conversation, turns: List<ChatTurn>) {
        val settings = settingsStore.state.value
        val config = settings.chat() ?: run {
            _ui.value = _ui.value.copy(sending = false, needsSetup = true)
            return
        }

        // 暂停前的那条助手回合 —— 续写要并到它身上
        val host = turns.lastOrNull { it.isAssistant } ?: return
        val questionId = host.answerTo ?: turns.lastOrNull { it.isUser }?.id
        val history = _ui.value.visibleTurns.ifEmpty { turns }

        val result = send(
            config = config,
            settings = settings,
            history = history,
            answerTo = questionId,
            deepThinking = _ui.value.deepThinking,
            // 续写走的还是同一轮，模式沿用当前值（中途切换对已在跑的这一轮不生效）
            toolsEnabled = _ui.value.mode == ChatMode.ASSISTANT,
            onDelta = { delta ->
                _ui.value = _ui.value.copy(
                    streaming = (_ui.value.streaming ?: "") + delta,
                    streamingInBody = true,
                )
            },
            onReasoning = { reasoning ->
                _ui.value = _ui.value.copy(
                    streamingReasoning = (_ui.value.streamingReasoning ?: "") + reasoning,
                )
            },
            onProgress = { live -> _ui.value = _ui.value.copy(liveTurn = live) },
        )

        result.fold(
            onSuccess = { reply ->
                /*
                 * 合并规则：正文与推理取**续写的那份**（界面上「已思考」块显示的是
                 * 最后一次思考）；轮次与工具记录**追加**，因为它们是有序序列。
                 */
                val merged = host.copy(
                    content = reply.content ?: host.content,
                    reasoning = reply.reasoning ?: host.reasoning,
                    reasoningMillis = reply.reasoningMillis ?: host.reasoningMillis,
                    toolCalls = host.toolCalls + reply.toolCalls,
                    rounds = host.rounds + reply.rounds,
                    /*
                     * token 用量**相加**，不是覆盖。
                     *
                     * 「确认后继续」这一轮是**同一轮的下一段**（工具往返之后
                     * 模型接着说话），续写那次请求自己也有用量。覆盖的话
                     * 用户看到的数字会漏掉暂停之前的那部分。
                     *
                     * ⚠️ 两边都可能为 null（厂商不返回）。规则：
                     *  · 都有 → 相加
                     *  · 只有一个有 → 用那个（另一个是"没有数据"，不是 0）
                     *  · 都没有 → null
                     */
                    totalTokens = when {
                        host.totalTokens != null && reply.totalTokens != null ->
                            host.totalTokens + reply.totalTokens
                        else -> host.totalTokens ?: reply.totalTokens
                    },
                )
                val nextTurns = turns.map { if (it.id == host.id) merged else it }
                // 用 replaceTurns 而不是 append —— 这一轮是**被改写**，不是新增
                store.replaceTurns(conversation.id, nextTurns)

                _ui.value = _ui.value.copy(
                    sending = false,
                    streaming = null,
                    streamingReasoning = null,
                    streamingInBody = false,
                    liveTurn = null,
                    turns = nextTurns,
                    // 回答的轮次数没变（还是同一轮），所以 activeRounds 不用动
                )
            },
            onFailure = { e ->
                _ui.value = _ui.value.copy(
                    sending = false,
                    streaming = null,
                    streamingReasoning = null,
                    streamingInBody = false,
                    liveTurn = null,
                    error = describe(e),
                )
            },
        )
    }

    private fun describe(e: Throwable): String = when (e) {
        is AiException -> e.message ?: "请求失败"
        else -> e.message ?: "请求失败"
    }

    private companion object {
        /** 与 [ConversationStore] 的标题截断保持一致。 */
        const val TITLE_MAX = 20

        /**
         * 「关联项目」最多列多少条候选。
         *
         * 取最近的 —— 用户要关联的几乎总是"刚提到的那条"。
         * 全量列表在手机上是几千行，滑不到底反而找不到。
         */
        const val LINK_CANDIDATE_LIMIT = 60
    }
}

/**
 * 把「关联项目」的资料并进要发给模型的那段文本。
 *
 * ## ⚠️ 为什么并进消息正文，而不是当成附件或别的通道
 *
 * 这是**唯一一条不需要新增协议**的做法：用户消息本来就是一段文本，
 * 模型天然会读它。做成"附件"的话要引入多模态 `content` 数组
 *（`content: [{type:"text"},{type:"image_url"}]`）—— 那会破坏
 * "换一家 API 只改配置"的兼容性承诺（各家对多模态的支持不一致）。
 *
 * ## 为什么要标注来源
 *
 * 拼进去的正文如果不加标记，模型会把它当成**用户说的话** ——
 * 于是可能回答"你说你的日程在 3 点"而不是"我看到你的日程在 3 点"。
 * 加一句明确的说明，让它知道这是**用户提供的资料**。
 *
 * ## 顺序：用户原话在前，资料在后
 *
 * 反过来的话模型先读到一大段资料再看问题，容易抓错重点。
 *
 * ## 取不到正文时如实说明
 *
 * 只发一个标题而不说"内容读不到"，模型会不知道**为什么**只有标题，
 * 于是可能自己编一个内容出来。所以明确写上"内容读取失败"。
 *
 * ## 抽成顶层纯函数是为了可测
 *
 * 它有三个"错了也不报错"的判断（拼没拼进去 / 顺序 / 空正文），
 * 见 `LinkedPromptTest`。放在 ViewModel 里就没法直接测了。
 */
internal fun buildPromptWithLinks(question: String, links: List<LinkedItem>): String {
    val text = question.trim()
    // 没有关联 → 原样返回（不引入任何多余字符）
    if (links.isEmpty()) return text

    return buildString {
        if (text.isNotEmpty()) {
            appendLine(text)
            appendLine()
        }
        appendLine("---")
        appendLine("以下是用户主动关联的资料，请结合它们回答：")
        links.forEach { item ->
            appendLine()
            if (item.detail.isNotBlank()) {
                appendLine(item.detail)
            } else {
                appendLine("【${item.kindLabel}】${item.title}（内容读取失败，仅有标题）")
            }
        }
    }.trim()
}
