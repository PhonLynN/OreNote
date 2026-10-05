package com.phonlynn.oreplan.v2.ai

import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.ai.ChatTurn
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AI 对话页（浮层里的主界面）。
 *
 * ## 布局纪律（我在这里错过四次，写清楚）
 *
 * ```
 * Box(fillMaxSize)                          根，**唯一**消费 window insets 的地方
 *   ├─ Column(fillMaxSize)                  **不给输入框留位置**
 *   │    ├─ AiChatHeader   固定 56           ← 任何情况下都在
 *   │    └─ LazyColumn     weight(1f)        ← 占满到屏幕底部（输入框之下也归它）
 *   └─ AiChatInputBar      悬浮，align(BottomCenter)
 *                                           ← 盖在列表之上，不占布局高度
 * ```
 *
 * ## ⚠️ 输入框**必须悬浮**，不能当 Column 的兄弟节点（第四次才改对）
 *
 * 我前三版都把它放成列表的兄弟，那必然切出一条**背景色的空带**：
 * 列表的可视区到那条带子的上沿就结束了，文字在那里被硬裁。
 * 用户连着报了三次，最后一句说得最清楚：
 *
 * > 「输入框是悬浮的，所以不存在边距，文字不应该存在任何截断」
 *
 * 所以现在列表用满整个高度、输入框浮在上面。列表底部留白取**输入框的实测高度**
 *（`onSizeChanged` 量出来的，含输入框自身内边距）—— 那不是"给输入框占位"，
 * 而是保证**任何一条消息都能被滚到输入框上方完整读到**；没有它，
 * 最后一条会永远压在悬浮框底下，那才叫截断。
 *
 * 输入框**不铺任何背景/遮罩**：要的就是列表从它背后穿过。
 *
 * ## ⚠️ 「顶栏被挤没 + 输入框上跳一大截」的真根因（第三次才找对）
 *
 * 前两版都只改了 Compose 这一侧（先"输入区加 imePadding"，后"整列加 imePadding
 * ＋ consumeWindowInsets"），但症状一直在。因为**真正的根因在窗口层，不在 Compose**：
 *
 * `AndroidManifest` 里没声明 `windowSoftInputMode`，系统就用默认模式去
 * **平移（pan）整个窗口**来露出被聚焦的输入框；与此同时 Compose 又按 IME inset
 * 让内容上移一次。**两边各抬一次**，于是：
 *
 *  · 位移翻倍 → 输入框被顶到远高于键盘的位置（用户："向上移动了特别大的距离"）
 *  · 平移把顶栏推出屏幕上方（用户："顶栏被挤到屏幕上方消失"）
 *  · 开始打字后 Compose 重新测量、系统撤掉平移 → 又跳回正常位置
 *
 * ## ⚠️ 而且**不能**用 adjustResize 去修（第二次修错了）
 *
 * 我上一版把 Manifest 改成 `adjustResize` 来禁止平移 —— 平移确实没了，
 * 但 `adjustResize` 的语义是**缩小窗口**。于是键盘高度被减了**两遍**：
 * 窗口先短一截，`safeDrawing` 再按 IME inset 缩一次。
 * 表现是输入框下方留出一条**与背景同色的矩形空带**，列表被提前截断
 *（用户：「输入框外侧有一个和背景同色的边框，文字在输入框上方提前截断」）。
 *
 * 正确取值是 **`adjustNothing`**：系统既不平移也不缩放，键盘高度
 * **只由 Compose 的 inset 消费一次**。详见 AndroidManifest 里那张对照表。
 *
 * 这里只消费**一次** insets，用 [WindowInsets.safeDrawing]。
 *
 * ## 为什么是 safeDrawing 而不是 statusBarsPadding + imePadding
 *
 * `safeDrawing` 一次给出「状态栏上边距 ＋ max(导航栏, 键盘) 下边距」。
 * 分开写两个修饰符时，键盘弹出后导航栏 inset 通常仍非零，两者相加就会
 * **多顶出一条导航栏的高度**；`safeDrawing` 取的是并集，不会重复。
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val turns = remember(state.turns, state.activeRounds) { state.visibleTurns }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    /*
     * 「已复制」提示 + 震动（用户 2026-10-03）。
     *
     * 两件事必须一起做：**震动**是手指还按着时的即时反馈，
     * **提示**是事后确认。缺一半用户就会反复长按确认到底成没成。
     */
    val copyToast = rememberCopiedToastState()
    val haptics = LocalHapticFeedback.current

    var expandedReasoning by remember { mutableStateOf<Set<String>>(emptySet()) }
    var seenTurnCount by remember { mutableStateOf(0) }

    /*
     * 添加附件。
     *
     * ⚠️ 用 `OpenMultipleDocuments` 而不是相册选择器 —— 与笔记 / 课程 / 白板
     * 三处的做法一致（都走 `PICKER_MIME_TYPES`）。用户的口径是「从文件选文档」，
     * 而 `OpenMultipleDocuments` 本来就覆盖图片（它就是系统文件选择器）。
     *
     * ⚠️ 选完**不发送**：文件先以 `UNASSIGNED` 落库，等用户说"放到哪儿"，
     * AI 再改它的归属。详见 `AttachmentOwner.UNASSIGNED` 的说明。
     */
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.attachFiles(uris)
    }

    /*
     * 需要用户操作的卡的状态与回调，打包一次给两处渲染点用
     *（历史消息里的卡、以及生成中的快照里的卡）。
     *
     * 预览与问题都是 `suspend` 算的（要读库），所以界面只负责在**第一次遇到**
     * 那张卡时调 `onEnsure` 触发计算，算好的结果从状态里读。
     */
    val pendingActions = PendingActions(
        previews = state.pendingPreviews,
        accepted = state.pendingAccepted,
        questions = state.askQuestions,
        answers = state.askAnswers,
        onEnsure = viewModel::ensurePendingPreview,
        onToggle = viewModel::togglePending,
        onApply = viewModel::applyPendingChanges,
        onReject = viewModel::rejectPendingChanges,
        onAnswer = viewModel::setAskAnswer,
        onSubmitAsk = viewModel::submitAsk,
    )

    /*
     * 复制一段文字 + 反馈。
     *
     * ## 为什么收成一个局部函数（两处调用：长按气泡、AI 回复的复制图标）
     *
     * 反馈的三件事（写剪贴板、震动、弹提示）**必须成套出现**。
     * 分散在两处写的话，迟早会出现"一处震了、另一处没震"这种不一致 ——
     * 而用户对"同样的动作反馈不一样"非常敏感。
     *
     * ## clipboard 是 suspend 的，但震动与提示**不等它**
     *
     * `setClipEntry` 要跨进程（系统剪贴板服务）。若先 await 再震，
     * 手感会慢半拍。所以顺序是：**先震动 → 立刻弹提示 → 再写剪贴板**。
     * 写失败的概率极低（Android 10+ 前台应用写剪贴板不需要权限），
     * 真失败了也只是复制内容不对，不至于误导用户"没复制成功"。
     */
    val copyWithFeedback: (String, String) -> Unit = remember(clipboard) {
        { text, label ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            copyToast.show("已复制")
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(android.content.ClipData.newPlainText(label, text)),
                )
            }
        }
    }

    /*
     * 是否跟随底部。
     *
     * 用户往上拖离开底部之后**不再**自动跟随 —— 否则每来一个字就把他拽回去，
     * 结果就是"拖不到底"（用户报过）。拖回底部后自动恢复跟随。
     *
     * ⚠️ 用户 2026-10-03 又报了一条相关的：「底部吸附力强，我没有办法通过上滑
     * 一点就轻易地脱离吸附」。根因是这里只在**滚动完全停下**时才更新
     * `followBottom`，而流式期间每个 token 都在把列表往底部拽 ——
     * 手指上滑的过程中又被拽回去，于是"滑不出来"。
     *
     * 现在改成**两个信号一起看**：
     *  · 手指一按下（`isScrollInProgress`）就立刻停止跟随 —— 用户正在接管
     *  · 松手后按落点判断：还贴着底就恢复跟随
     *
     * 于是"上滑一点"在按下的一瞬间就生效了，不会和自动滚动抢。
     */
    var followBottom by remember { mutableStateOf(true) }

    /*
     * 列表是不是**已经离开底部** —— 决定「回到最新」按钮的显隐。
     *
     * ⚠️ 它和 `followBottom` 是**两件事**，不能合并：
     *  · `followBottom` = "要不要自动跟随" —— 手指一按下就变 false（用户接管了）
     *  · `isScrolledUp` = "现在离底有多远" —— 只看位置，不看意图
     *
     * 合并的话，手指刚按下（还没滑动、仍在底部）就会冒出按钮，很跳。
     */
    var isScrolledUp by remember { mutableStateOf(false) }

    /*
     * 首次进入 / 切换对话时**定位到底部**（用户口径：
     * 「进入 AI 聊天时定位在莫名其妙的位置，应该定位到对话底部」）。
     *
     * 用 `conversation.id` 当 key：换一段对话就重新贴一次底。
     * ⚠️ **不能**加 `turns.size` 当 key —— 那会让每来一条消息都强制跳到底，
     * 等于把"用户手动上滑"这件事又抹掉了。
     */
    val conversationId = state.conversation?.id

    /*
     * 列表是不是**已经完成过一次"进入时贴底"** —— 决定「回到最新」按钮
     * 什么时候才允许出现。
     *
     * ⚠️ 刚进来时列表还没测量、也没滚到底，`canScrollForward` 会是 true ——
     * 于是按钮会在进入对话的瞬间闪一下，而用户根本没滚动过。
     */
    var initialScrolled by remember(conversationId) { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        if (conversationId == null) return@LaunchedEffect
        followBottom = true
        initialScrolled = false

        /*
         * 定位到底部。
         *
         * ⚠️ 必须**等列表测量完** —— `scrollToItem` / `scrollBy` 在没有任何
         * item 被测量时是空操作，会把"我贴过底了"这件事变成假的。
         * 所以先等至少一项出现。
         */
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(0, Int.MAX_VALUE / 2)

        // 贴底已完成 → 开闸，按钮从这一刻起才可能显示
        initialScrolled = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { dragging ->
                if (dragging) {
                    // 手指一落下就交出控制权 —— 这是"能轻松脱离吸附"的关键
                    followBottom = false
                } else {
                    // 拖动结束后按落点判断：还贴着底就继续跟随，被拖走了就停
                    followBottom = !listState.canScrollForward
                }
            }
    }

    /*
     * 「回到最新」按钮的显隐：**只看位置**，拖动与流式增长都会让它更新。
     *
     * 判据是 `canScrollForward`（还能往下滚 = 不在底部）。
     * 用 `snapshotFlow` 而不是只订阅 `isScrollInProgress` —— 流式增长时
     * 列表也会"离开底部"，而那不是用户拖的，同样该出按钮。
     *
     * `initialScrolled` 是"首次贴底已完成"的闸门（见上面）。
     */
    LaunchedEffect(listState, conversationId) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward ->
                isScrolledUp = initialScrolled && canScrollForward
            }
    }

    /*
     * 跟随到底部。推理内容的长度也要参与：思考常常在正文之前就开始长，
     * 只看正文长度的话，思考阶段列表不会跟着滚。
     *
     * ## ⚠️ 这里**不能**用 `animateScrollToItem(最后一项)`（用户报的 bug）
     *
     * 那个 API 的语义是「把该项的**顶部**对齐到视口顶部」。
     * 流式的那条消息很长时（长思考过程尤其明显），它的顶部被钉在屏幕顶端、
     * 新内容全都在屏幕外 —— 视觉上就是**页面离奇地往上滚**。
     * 而且它在每个 token 上都重新触发一次动画，会一直和用户的手指抢，
     * 于是**怎么拖都到不了底部**。
     *
     * `scrollBy(正向一大段)` 的语义则是明确的"往下滚"，
     * 内部按最大可滚距离截断，给足量就是**真正的最底部**。
     *
     * ## ⚠️ 正文阶段「停手」的**精确条件**（用户 2026-10-03 两次口径）
     *
     * 第一次：
     *
     * > 「如果开启思考就把最新内容放在底部，如果开始生成正文，就停在正文刚好
     * > 占满一页的位置，而不吸附在底部」
     *
     * 第二次（我第一版做错了，这是修正）：
     *
     * > 「如果中间出现工具调用卡片，会打断底部吸附，**按理说底部吸附就应该一直
     * > 在最下方，无论 ai 生成的是什么内容**」
     *
     * ## 我第一版错在哪
     *
     * 我原来写的是「只要 `streamingInBody` 为真就停手」。而助手模式下
     * `streamingInBody` 一旦置上就**再也不会回落**（正文先出现过），
     * 于是后面每一次工具调用（卡片长出来、结果填回来）**都不再跟随** ——
     * 卡片在屏幕外一张张长出来，用户根本看不到。这正是用户报的
     * 「工具调用卡片打断底部吸附」。
     *
     * ## 正确的判据：**看"正在变长的是什么"**
     *
     * | 正在长的东西 | 期望 |
     * |---|---|
     * | 思考文字 / 正文**文字** | 贴底（文字是一行行长出来的，贴底才看得到最新） |
     * | **工具卡**（新卡出现、转圈、结果填回） | 贴底（用户要看到卡片一张张出现） |
     * | 用户往上滚过 | **不贴底**（用户的意图优先） |
     *
     * 所以「正文阶段停手」的真正含义不是"不准滚"，而是 ——
     * **不要在正文逐字增长时每个 token 都把视口拽一下**（那才是抖动源）。
     *
     * ## 实现：只在**纯文字增长**时停手，且**卡片变化时补一次贴底**
     *
     * ```
     * 这一帧有工具卡变化（张数/状态变了）  → 贴底   ← 修正用户刚报的问题
     * 否则如果是正文文字在长              → 停手
     * 否则（思考在长 / 刚发出）            → 贴底
     * ```
     *
     * 用**卡片的"形状"**（每张卡的 id + 状态）当 key 的一部分，
     * 而不是用文字长度 —— 卡片状态一变（转圈 → 已完成）就说明
     * 有新的视觉内容落地了，那时补一次贴底是正确的。
     */
    LaunchedEffect(
        turns.size,
        state.streaming?.length,
        state.streamingReasoning?.length,
        state.streamingInBody,
        /*
         * 工具卡的**形状**：张数 + 每张的 id + 状态。
         *
         * 只用 `toolCalls.size` 是不够的 —— 卡片**已经存在但状态翻了**
         * （转圈 → 已完成，或者"待确认"那张卡展开了预览）时张数没变，
         * 可界面高度变了，那时也要补一次贴底。
         *
         * ⚠️ 映射成 `Pair` 列表是为了让 key 的 `equals` 有意义：
         * 直接放 `List<ToolCallRecord>` 会把 `resultSummary` 之类的
         * 字段也纳入比较，那些每帧都可能不同 → effect 疯狂重启。
         */
        state.liveTurn?.toolCalls?.map { it.id to it.status },
        state.liveTurn?.rounds?.size,
    ) {
        /*
         * ⚠️ 就地重新生成时**不要**往底部拽。
         *
         * 那时内容长在列表中间，每次 token 都滚到底会把用户从正在生成的那条
         * 硬拉到最下面。只有当被重新生成的那条**正好是最后一条**时才跟随 ——
         * 那种情况下它的增长本来就在底部，跟随才是对的。
         */
        val regeneratingInPlace = state.replaceTurnId != null &&
            turns.lastOrNull()?.id != state.replaceTurnId

        /*
         * 这一轮里有没有**工具卡**在参与。
         *
         * 有的话一律贴底 —— 卡片是"块级"内容（一张张出现、状态会翻），
         * 不像文字那样逐字长；用户必须能看到它们。
         */
        val hasToolCalls = state.liveTurn?.toolCalls?.isNotEmpty() == true ||
            state.turns.lastOrNull()?.toolCalls?.isNotEmpty() == true

        /*
         * 正文阶段停手。判据用 `streamingInBody` 而不是"正文非空"——
         * 后者在**落库后**也非空，会把"生成完"那一帧也卷进来。
         *
         * ⚠️ `&& !hasToolCalls`：这一条是上面那个修正的核心 ——
         * 有工具卡在跑时**不适用**"正文阶段停手"，一律贴底。
         */
        val bodyPhase = state.sending && state.streamingInBody && !hasToolCalls

        if (followBottom && !regeneratingInPlace && !bodyPhase && !listState.isScrollInProgress) {
            listState.scrollBy(BOTTOM_SCROLL_STEP)
        }
    }

    /*
     * 刚生成完的那条默认展开思考过程。
     *
     * 流式期间推理是展开着逐字显示的，落库时若按"默认折叠"渲染，
     * 用户会看到思考内容**在回答完成的瞬间被折起来** —— 一次明显的跳动。
     */
    LaunchedEffect(turns.size) {
        if (turns.size != seenTurnCount) {
            seenTurnCount = turns.size
            turns.lastOrNull()
                ?.takeIf { it.isAssistant && !it.reasoning.isNullOrBlank() }
                ?.let { expandedReasoning = expandedReasoning + it.id }
        }
    }

    /*
     * 输入框的实测高度（含它自己的内边距）。
     *
     * 用它当列表的底部留白，而不是写死一个数：输入框是**多行会长高**的
     *（最多 5 行），写死的话输入到第三行就会把最后一条消息压到它下面去。
     */
    var inputBarHeightPx by remember { mutableIntStateOf(0) }
    val inputBarHeight = with(LocalDensity.current) { inputBarHeightPx.toDp() }

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            // 全页**唯一**的 inset 消费点：上=状态栏，下=max(导航栏, 键盘)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        /*
         * 消息列表占满整个可用高度 —— **不给输入框留位置**。
         *
         * 输入框是悬浮在它上面的（见下面的 align(BottomCenter)），
         * 所以这里没有任何"预留区"。
         *
         * ⚠️ 我前两版把输入框放成 Column 的**兄弟节点**，那必然切出一条
         * 背景色的空带（= 用户看到的"和背景同色的边框"），
         * 而列表就在那条带子的上沿被硬裁 —— 用户的原话：
         * 「输入框是悬浮的，所以不存在边距，文字不应该存在任何截断」。
         */
        Column(Modifier.fillMaxSize()) {
            AiChatHeader(
                title = state.conversation?.title?.takeIf { it.isNotBlank() } ?: "AI",
                onBack = onBack,
                onNew = viewModel::startNew,
                onList = onOpenList,
                onSettings = onOpenSettings,
            )

            // 未配置模型：可操作的一句话（不是设计稿里的元素，但缺了它会让人
            // 发出必然失败的消息却不知为什么 —— 这一条是功能必需，见下方注释）
            if (state.needsSetup) {
                SetupNotice(onOpenSettings)
            }

            state.error?.let { ErrorBanner(it, viewModel::dismissError) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                /*
                 * 底部留白 = **悬浮输入框的实测高度 + 一段呼吸空间**。
                 *
                 * 这不叫"给输入框预留位置"，而是保证**任何一条消息都能被滚到
                 * 输入框上方完整读到**。没有它的话，最后一条永远压在悬浮框底下，
                 * 那才叫真的截断。
                 */
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 6.dp,
                    bottom = inputBarHeight + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(turns, key = { it.id }) { turn ->
                    /*
                     * 就地重新生成：**这条回答的位置**直接渲染流式内容。
                     *
                     * 用户口径：「重新生成不应该先在下面额外生成一段再合并到上方，
                     * 应该直接在原位置开始又一次生成」。
                     * 旧回答此刻仍在 `turns` 里（它是当前选中的那一轮），
                     * 所以要在**它这个位置**把它换掉，而不是在末尾另起一段。
                     */
                    if (state.sending && state.replaceTurnId == turn.id) {
                        StickyReasoningHost(
                            turnId = turn.id,
                            expanded = expandedReasoning.contains(turn.id),
                            onCollapse = { expandedReasoning = expandedReasoning - turn.id },
                        ) {
                            StreamingTurn(
                                reasoning = state.streamingReasoning,
                                content = state.streaming,
                                deepThinking = state.deepThinking,
                                liveTurn = state.liveTurn,
                                pending = pendingActions,
                            )
                        }
                        return@items
                    }

                    StickyReasoningHost(
                        turnId = turn.id,
                        expanded = expandedReasoning.contains(turn.id),
                        onCollapse = { expandedReasoning = expandedReasoning - turn.id },
                    ) {
                        ChatTurnRow(
                            turn = turn,
                            reasoningExpanded = expandedReasoning.contains(turn.id),
                            onToggleReasoning = {
                                expandedReasoning = if (expandedReasoning.contains(turn.id)) {
                                    expandedReasoning - turn.id
                                } else {
                                    expandedReasoning + turn.id
                                }
                            },
                            roundSwitch = viewModel.roundSwitchFor(turn),
                            editing = state.editingTurnId == turn.id,
                            onBeginEdit = { viewModel.beginEdit(turn) },
                            onCommitEdit = { text -> viewModel.commitEdit(turn.id, text) },
                            onCancelEdit = viewModel::cancelEdit,
                            // 长按用户气泡 = 复制（用户 2026-10-03）
                            onCopyText = { text -> copyWithFeedback(text, "消息") },
                            // 状态行右端的 token 消耗（设置里有开关）
                            showTokens = state.showTokens,
                            // 落库的待确认卡也要能点（重进对话后仍然可作答）
                            pending = pendingActions,
                            onAction = { action ->
                                when (action) {
                                    ChatAction.Copy -> copyWithFeedback(
                                        viewModel.textToCopy(turn),
                                        "AI 回复",
                                    )

                                    // 铅笔改的是 **AI 回复的正文**，在原位打开编辑框
                                    ChatAction.Edit -> viewModel.beginEdit(turn)

                                    ChatAction.Branch -> viewModel.branchFrom(turn)

                                    ChatAction.Regenerate -> viewModel.regenerate(turn)
                                }
                            },
                        )
                    }
                }

                /*
                 * 末尾的流式区：**只在普通发送时**出现。
                 *
                 * 就地重新生成时走上面那条分支（在那条消息的位置渲染），
                 * 这里再画一份就会变成"底部额外生成一段"—— 正是用户抱怨的现象。
                 */
                if (state.sending && state.replaceTurnId == null) {
                    item(key = "__streaming__") {
                        StreamingTurn(
                            reasoning = state.streamingReasoning,
                            content = state.streaming,
                            // 没开思考时提示说「生成中」，别说「思考中」
                            deepThinking = state.deepThinking,
                            liveTurn = state.liveTurn,
                            pending = pendingActions,
                        )
                    }
                }
            }
        }

        /*
         * 「回到最新」浮动按钮（用户 2026-10-03 需求）。
         *
         * > 「加入一键返回底部按钮，该按钮在滚动到底部后消失，非底部位置出现，
         * > 出现和消失之间需要有过渡动画」
         *
         * ## 位置：右下角、**压在悬浮输入框上方**
         *
         * 输入框是悬浮的（见类注释），所以按钮不能贴容器底 ——
         * 那会正好落在输入框里。用 `inputBarHeight` 把它顶到输入框上沿之上。
         *
         * ## 为什么用 `AnimatedVisibility` 而不是手搓 alpha
         *
         * 项目 `VMotion` 的纪律是"任何元素的出现/消失都必须有过渡"。
         * `AnimatedVisibility` 自带 enter/exit 的挂载管理（退场播完才卸载），
         * 这正是 README 里记过的那套可靠机制 —— 手搓容易留下"看不见但仍拦截触摸"
         * 的窗口（`ConversationListScreen` 的历史 bug 就是这个）。
         *
         * 出现用 `Expressive`（快起慢收）+ 轻微上浮，消失用 `Accelerate`（缓起快收）
         * —— 与项目其余动效同一套曲线，且**没有 LinearEasing**。
         */
        androidx.compose.animation.AnimatedVisibility(
            visible = isScrolledUp,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(VMotion.RevealMillis, easing = VMotion.Expressive),
            ) + androidx.compose.animation.slideInVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    VMotion.RevealMillis,
                    easing = VMotion.Expressive,
                ),
                initialOffsetY = { it / 2 },
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(VMotion.ExitMillis, easing = VMotion.Accelerate),
            ) + androidx.compose.animation.slideOutVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    VMotion.ExitMillis,
                    easing = VMotion.Accelerate,
                ),
                targetOffsetY = { it / 2 },
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                /*
                 * 位置：右下角，**浮在输入框上沿之上**。
                 *
                 * · `end = 20dp` 与列表的水平内边距（`contentPadding` 的 20dp）对齐，
                 *   所以按钮右缘与正文右缘在同一条竖线上 —— 不齐会显得是"贴边掉出来的"
                 * · `bottom = inputBarHeight + 12dp`：输入框是悬浮的（见类注释），
                 *   贴容器底会正好落进输入框里
                 *
                 * ⚠️ 12dp 这个间隙**不要再加大**。用户 2026-10-04 的截图里
                 * 按钮是压着最后一行文字的 —— 那是**按钮太大（40dp）**造成的，
                 * 不是间隙不够。间隙一味加大只会让它离输入框越来越远、
                 * 看起来像"悬在半空"。现在改成 32dp 后，它落进正文与输入框之间
                 * 那段自然的留白里。
                 */
                .padding(end = 20.dp, bottom = inputBarHeight + 12.dp),
        ) {
            ScrollToBottomButton {
                followBottom = true
                scope.launch { listState.scrollBy(BOTTOM_SCROLL_STEP) }
            }
        }

        /*
         * 「已复制」提示：**页面中央**（用户口径）。
         *
         * 放中央而不是底部：底部正是悬浮输入框与键盘的地盘，
         * 提示会被压住或顶走。中央没有这些干扰。
         *
         * 它是**叠加层**（在 Box 的最后），不参与列表布局 ——
         * 所以出现/消失都不会把消息顶来顶去。
         */
        CopiedToastHost(
            state = copyToast,
            modifier = Modifier.align(Alignment.Center),
        )

        /*
         * 「＋」菜单（用户 2026-10-04 定的形态）。
         *
         * ## 位置：贴在输入框**上方**
         *
         * 用 `BottomCenter` + 把输入框的实测高度当底部内衬 ——
         * 于是它正好落在输入框上沿，而不是盖住输入框。
         *
         * ⚠️ **不铺全屏遮罩**：用户在挑文件/挑条目时还想看到对话内容
         *（那正是他判断"该关联哪条"的依据）。点别处关掉由下面那个
         * 透明点击层负责。
         */
        PlusMenu(
            page = state.plusMenu,
            files = state.files,
            linkCandidates = state.linkCandidates,
            linkedItems = state.linkedItems,
            onPage = viewModel::setPlusMenuPage,
            onImport = {
                viewModel.closePlusMenu()
                attachmentLauncher.launch(AttachmentStorage.PICKER_MIME_TYPES)
            },
            onToggleLink = viewModel::toggleLinkedItem,
            onClearLinks = viewModel::clearLinkedItems,
            onClose = viewModel::closePlusMenu,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                /*
                 * 底部内衬 = 输入框实测高度 + 8dp 间隙。
                 *
                 * ⚠️ `padding` 只能写**一次**（连着写两次会有重载歧义）。
                 * `bottom` 用 `inputBarHeight + 8.dp` 一步算出来。
                 */
                .padding(horizontal = 14.dp, vertical = 0.dp)
                .padding(bottom = inputBarHeight + 8.dp),
        )

        /*
         * 点菜单以外的地方关掉它。
         *
         * 放在菜单**之前**（因此菜单在它上层）—— 顺序反了的话这一层会
         * 盖住菜单，用户点菜单项时先被它吃掉，菜单看起来"点不动"。
         *
         * ⚠️ 只在菜单开着时才存在，否则它会吃掉整页的点击。
         */
        if (state.plusMenu != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .vPressable(scaleDown = 1f, onClick = viewModel::closePlusMenu),
            )
        }

        /*
         * 悬浮输入框：**盖在列表之上**，不是列表的兄弟。
         *
         * · 透明背景：四周不铺任何遮罩，列表从它背后穿过（用户明确不要那块矩形遮罩）
         * · 对齐到容器底部 —— 容器已被 safeDrawing 缩到键盘上方，
         *   所以键盘弹出时它自动落在键盘上面
         * · onSizeChanged 把实测高度报给列表当底部留白
         */
        AiChatInputBar(
            placeholder = state.placeholder,
            input = state.input,
            onInputChange = viewModel::setInput,
            onSend = viewModel::sendMessage,
            /*
             * 生成中：发送按钮变成「停止」（用户 2026-10-03 的「停止生成按钮」）。
             *
             * `stopGenerating()` 早就实现好了，但一直没有调用点 ——
             * 于是长回复只能干等。位置定在**原地**（各家 AI App 的统一做法）。
             */
            generating = state.sending,
            onStop = viewModel::stopGenerating,
            mode = state.mode,
            onModeChange = viewModel::setMode,
            deepThinking = state.deepThinking,
            onDeepThinkingChange = viewModel::setDeepThinking,
            /*
             * 「＋」现在**打开菜单**，不再直接拉系统选择器
             *（用户 2026-10-04：「可以合成在悬浮输入框里的加号按钮里」）。
             * 真正拉起选择器的是菜单里的「导入文件」。
             */
            onAttach = viewModel::openPlusMenu,
            enabled = !state.sending,
            /*
             * ⚠️ 就地编辑时输入框**停在下方、不跟键盘上浮**（用户 2026-10-03）。
             *
             * 编辑框是**长在消息原位**的（`InlineEditor`），键盘弹出来是为了给
             * 它用；此时底部的悬浮输入框再跟着上浮，会正好盖住正在编辑的那一条 ——
             * 而且屏幕下方同时有两套输入框输入光标，观感很乱。
             * 所以编辑期间让悬浮输入框**收起**（消失，不是半透明留在那里）。
             */
            visible = state.editingTurnId == null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { inputBarHeightPx = it.height },
            // ⚠️ 这里**不加** imePadding / navigationBarsPadding ——
            // 两者已由根节点的 safeDrawing 统一处理。
            // 再加会与父层叠加，就是"上浮夸张"的来源。
        )
    }
}

/**
 * 「回到最新」按钮。
 *
 * 只在**离开底部**时出现。
 *
 * ## 尺寸：32dp 圆 + 16dp 图标（用户 2026-10-04 要求改小）
 *
 * 用户原话：
 *
 * > 「回到底部按钮做的太大了，**阴影的设计也有问题**，参考 ds app 的设计，让它小一点」
 *
 * 我第一版是 **40dp 圆 + 20dp 图标 + 6dp 投影**，对着参考图（DeepSeek App）看确实偏大：
 *
 * | | 我的第一版 | 参考图（DS） | 现在 |
 * |---|---|---|---|
 * | 圆直径 | 40dp | ≈ 32dp | **32dp** |
 * | 图标 | 20dp | ≈ 16dp | **16dp** |
 * | 投影 | `shadow(6.dp)` | **看不出投影** | **去掉** |
 *
 * ## ⚠️ 为什么去掉投影（不是随手改的）
 *
 * 用户点名说了"阴影的设计也有问题"。真正的问题是：
 *
 * · `Modifier.shadow` 画的是**黑色半透明**的软边，叠在浅色底上会显出一圈**脏灰**
 * · 而这个按钮是**浮在正文之上**的 —— 它周围全是文字，一圈灰边看起来像是
 *   "文字没渲染干净"，而不是"这里有个按钮"
 * · 参考图用的是**细描边**（1dp）来界定边界，在浅底上干净得多
 *
 * 所以现在只有「白底 + 1dp 细描边」。这也更贴合项目既有语言
 *（卡片风格就是"白底 r16 描边、无投影"，见 README 的视觉规则）。
 *
 * ## 为什么仍然是圆形 + 白底
 *
 * 正文会从它背后滚过，所以必须**不透明**（半透明会让文字和图标糊在一起）。
 */
@Composable
private fun ScrollToBottomButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(VColors.surface)
            .border(1.dp, VColors.line, CircleShape)
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.ArrowDown,
            contentDescription = "回到最新",
            tint = VColors.ink2,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 把「收起思考」做成**吸顶**的容器（用户 2026-10-03 需求）。
 *
 * > 「思考内容的收起按钮在页面向下滚动直到该按钮被遮挡后，应该停靠在页面上方，
 * > 便于用户快速收起思考内容」
 *
 * ## 为什么不用 `LazyColumn` 的 `stickyHeader`
 *
 * `stickyHeader` 需要把内容**从 item 里拆出来**变成一个独立的 header 条目。
 * 但这里要吸顶的不是一条独立内容 —— 它是 `ChatTurnRow` 内部的「已思考」那一行，
 * 拆出来意味着要把整个 `ChatTurnRow` 拆成两半，两边的状态（展开、编辑、
 * 待确认卡…）都要跟着重排。那是一次大改造，而收益只有"吸顶"这一件事。
 *
 * ## 改用「位置上报 + 顶上叠一层」的做法
 *
 * ```
 * Box
 *   ├─ content()                 ← 原样渲染（里面那一行照常在原位）
 *   └─ 吸顶条（只有被滚过去时才画，且 alpha 从 0 渐入）
 * ```
 *
 * 判据：这个 Box 的**顶边在视口里的位置** `y`。
 *  · `y >= 0`      → 还没滚过去，不画吸顶条
 *  · `y < 0`       → 那一行已经被滚到视口上方了，画吸顶条
 *
 * ## 没有"跳一下"
 *
 * 原位那一行**照常存在**（只是被滚上去了，看不见），吸顶条叠在顶上渐入 ——
 * 所以不存在"元素从列表里飞出来"的观感。
 *
 * ## 只在**展开着**的时候吸顶
 *
 * 收起状态下那一行右边是「展开」的箭头，吸顶过去挡住正文没有意义
 * （用户要的是"快速收起"，收起之后就没有这个动作了）。
 *
 * @param turnId 这一轮里被展开的是不是**本容器**的思考 —— 不是就不吸顶
 * @param onCollapse 点吸顶条 = 收起
 * @param content 原样的内容（含它自己那一行「已思考」）
 */
@Composable
private fun StickyReasoningHost(
    turnId: String,
    expanded: Boolean,
    onCollapse: () -> Unit,
    content: @Composable () -> Unit,
) {
    /*
     * 本容器顶边相对**视口顶**的位置（单位 px）。
     * 初始给一个正数 = "还没滚过去"，避免首帧误亮。
     */
    var topInViewport by remember(turnId) { mutableFloatStateOf(1f) }

    /*
     * 吸顶条的高度必须**固定**，不能跟着内容的实测高度走 ——
     * 那样在动画期间会自我驱动（高度变 → 位置变 → 又触发判定）。
     * 「已思考（用时 N 秒）」那一行在设计稿里就是 19dp，这里多留一点内衬。
     */
    val barHeight = 30.dp
    val showSticky = expanded && topInViewport < 0f

    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    /*
                     * `positionInRoot()` 的 y 是相对**根**的，而根之上还有
                     * 顶栏（56dp）与状态栏内衬。要判断"是否被顶栏遮住"，
                     * 得减掉它们 —— 但那些值在这一层拿不到。
                     *
                     * 所以改用**这个 Box 自身是否还在视口内**来判：
                     * `boundsInWindow().top < 0` 是"被滚到窗口上方之外"，
                     * 那必然也已经被顶栏遮住了（顶栏在窗口更下方）。
                     * 这个判据不需要知道顶栏多高，天然正确。
                     */
                    topInViewport = coords.boundsInWindow().top
                },
        ) {
            content()
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showSticky,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(VMotion.RevealMillis, easing = VMotion.Expressive),
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(VMotion.ExitMillis, easing = VMotion.Accelerate),
            ),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            StickyReasoningBar(height = barHeight, onClick = onCollapse)
        }
    }
}

/**
 * 吸顶的那一条：**只有**「收起思考」这一个动作。
 *
 * ## 为什么做得这么克制
 *
 * 它是**叠在正文之上**的 —— 任何多余的装饰都会挡住正在读的字。
 * 所以只有一行小字 + 一个向上箭头，而且底色不透明（否则文字会和下面的正文
 * 叠在一起糊成一团）。
 *
 * ## 用 `ink3` 而不是主色
 *
 * 它是"收起"这种**辅助动作**，不是这一屏的主角。主色（accent）会把它变成
 * 视觉焦点，而用户此刻想读的是正文。
 */
@Composable
private fun StickyReasoningBar(height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    // 只有内容区有水平内衬（20dp），吸顶条要与之对齐才不会比正文突出一截
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(VColors.bg.copy(alpha = 0.96f))
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .vPressable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VText("收起思考", AiTypo.thinkingLabel, color = VColors.ink3)
            Icon(
                Lucide.ChevronUp,
                contentDescription = "收起思考过程",
                tint = VColors.ink3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * 未配置模型的提示。
 *
 * ## 为什么保留它（设计稿里没有）
 *
 * 这是**功能必需**，不是装饰：没有可用的模型时，用户发消息只会得到一次
 * 失败的请求，而他不知道为什么。这一条把「为什么不能用」和「去哪里配」
 * 直接说出来。
 *
 * 样式上刻意做得**低调**（一行浅底文字，不是大横幅）——
 * 第一版做成了橙色横幅，视觉上过重。
 */
@Composable
private fun SetupNotice(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(VColors.surface2, RoundedCornerShape(12.dp))
            .vPressable(onClick = onOpenSettings)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        VText("还没有配置模型，点这里去设置", AiTypo.settingValue, color = VColors.ink2)
    }
}

/** 错误条：点一下消掉。 */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .background(VColors.roseSoft, RoundedCornerShape(12.dp))
            .vPressable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        VText(message, AiTypo.settingValue, color = VColors.roseDeep)
    }
}

/**
 * "滚到底部"一次推进的距离。
 *
 * 给一个远超任何单屏内容的高度，让 `scrollBy` 内部按最大可滚距离截断 ——
 * 效果就是"无论当前在哪，一步到底"。用 float 不会溢出。
 */
private const val BOTTOM_SCROLL_STEP = 100_000f
