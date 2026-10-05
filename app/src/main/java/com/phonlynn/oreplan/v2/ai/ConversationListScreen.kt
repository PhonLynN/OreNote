package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.ai.Conversation
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VFloatingInputDialog
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.flow.map
import java.util.Calendar

/**
 * 对话列表页。
 *
 * 结构照设计稿 `AI · 对话列表`：
 * ```
 * Header   [返回] 对话记录 …………… [＋]      高 56，水平 pad 14
 * Content  搜索框                          高 52，圆角 14
 *          ── 置顶 ──                       高 41，左 pad 16
 *            期末复习安排                     高 48
 *          ─────────────                   高 1，水平 pad 14
 *          ── 今天 ──
 *            …
 * ```
 *
 * 数值见 `verification/plan030/ai_spec.py` 的 `LIST`。
 *
 * ## 分组是**算出来的**，不存字段
 *
 * `Conversation` 里只有 `updatedAt`，分组由它现算。
 * 存字段的问题是**时间过去后分组就过期了**（昨天存的"今天"，明天看就错了）。
 *
 * ## ⚠️ 「点了没反应」的真根因（用户 2026-10-03 报）
 *
 * 上一版这一页**能点但没用**：`onOpen` 的回调签名是 `(String) -> Unit`，
 * 而 `AiOverlay` 那边接的是 `{ page = OverlayPage.Chat }` —— **把 id 丢掉了**。
 * 于是点任何一条都只是"退回对话页"，当前对话根本没换。
 *
 * 现在这一页**自己**拿着 ViewModel 去 `open(id)`，切页由 [onOpen] 只负责翻页。
 * 这样"要打开哪一条"这件事不会再经过一层转手而丢掉。
 *
 * ## 会话管理走长按
 *
 * 设计稿的列表项**只有一个标题**，没有任何操作入口。所以增删这里不画常驻按钮
 *（那会偏离设计稿），改用**长按弹操作表**：置顶 / 重命名 / 删除。
 */
@Composable
fun ConversationListScreen(
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onNew: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversationList.collectAsStateWithLifecycle()
    /*
     * 当前正在对话的那一条 —— 用来在列表里**标出"你在这里"**。
     *
     * 用户口径：「当前身处的对话没有选框提示」。
     *
     * 从 `ui.conversation` 拿（那是 `ChatViewModel` 里"当前打开的对话"），
     * 而不是从 `conversationList` 推断 —— 后者还可能含一条**尚未落库的
     * 空草稿**（见 `conversationList` 的注释），它没有 id 可言。
     */
    val currentId by viewModel.ui
        .map { it.conversation?.id }
        .collectAsStateWithLifecycle(initialValue = null)

    var query by remember { mutableStateOf("") }

    /*
     * ## 多选模式（用户 2026-10-04 需求「给对话列表加入多选功能」）
     *
     * ```
     * 非多选：点头部右侧的 [☰]  → 进入多选
     * 多选中：头部变成 [取消] 已选 N  [全选] [置顶] [删除]
     *         点任意一行 = 勾选/取消（**不进入对话**）
     * ```
     *
     * ## 为什么多选时「点一行」不能进对话
     *
     * 那是多选的通用语义（相册、文件管理器都一样）。若还进对话，
     * 用户点一下就被带走了，根本选不了第二条。
     *
     * ## `selected` 用 **id 集合**而不是下标
     *
     * 列表会因为搜索、分组、置顶而**重排**。用下标的话，筛选一变
     * 选中的就变成了另一条 —— 那是最难查的一类 bug。
     */
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** 退出多选：模式与选择一起清掉（留一个"已选"是脏状态）。 */
    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    /*
     * 这一页同时只可能有**一个**弹窗 —— 用**单个**可空状态表达，而不是三个独立的
     * `actionTarget` / `renameTarget` / `deleteTarget`。
     *
     * ⚠️ 三个独立状态正是上一版的 bug（用户报「删除完成后黑色遮罩不消失、操作无响应」）：
     * 点操作表里的「删除」只把 `deleteTarget` 设上，**没清 `actionTarget`**，
     * 于是**两个 Dialog 窗口同时存在**（遮罩叠两层，所以格外黑）。
     * 确认删除后 `deleteTarget` 清空，但操作表那个窗口还在 ——
     * 它的退出动画早把面板透明度降到了 0，于是变成一层**看不见却仍然拦截触摸**的
     * 窗口：点哪都没反应。
     *
     * 合成一个状态之后，「同时两个弹窗」在类型上就不成立了，不会再有第二次。
     */
    var dialog by remember { mutableStateOf<ListDialog?>(null) }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter { it.title.contains(query, ignoreCase = true) }
        }
    }
    val groups = remember(filtered) { groupConversations(filtered) }

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (selectionMode) {
                SelectionHeader(
                    count = selected.size,
                    total = filtered.size,
                    onCancel = { exitSelection() },
                    onSelectAll = {
                        // 全选 = 当前**筛选后**的全部（不是全库）——
                        // 用户在搜索框里筛过的意思就是要对这批操作
                        selected = if (selected.size == filtered.size) {
                            emptySet()
                        } else {
                            filtered.map { it.id }.toSet()
                        }
                    },
                    onPin = {
                        viewModel.setConversationsPinned(selected, pinned = true)
                        exitSelection()
                    },
                    onUnpin = {
                        viewModel.setConversationsPinned(selected, pinned = false)
                        exitSelection()
                    },
                    onDelete = { dialog = ListDialog.BatchDelete(selected) },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .vPressable(scaleDown = 0.9f, onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.ChevronLeft,
                                contentDescription = "返回",
                                tint = VColors.ink,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        VText("对话记录", VTypo.navTitle, color = VColors.ink, maxLines = 1)
                    }

                    Spacer(Modifier.weight(1f))

                    /*
                     * 进入多选。
                     *
                     * ⚠️ 只在**有对话可管**时显示 —— 空列表上放一个"多选"按钮，
                     * 点进去什么也选不了，那是假按钮（项目纪律不允许）。
                     */
                    if (filtered.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .vPressable(scaleDown = 0.88f) { selectionMode = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Lucide.ListChecks,
                                contentDescription = "多选",
                                tint = VColors.ink,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .vPressable(scaleDown = 0.88f) {
                                viewModel.startNew()
                                onNew()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.Plus,
                            contentDescription = "新对话",
                            tint = VColors.ink,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp),
            ) {
                ListSearchField(query = query, onQueryChange = { query = it })

                if (filtered.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        VText(
                            if (query.isBlank()) "还没有对话" else "没有匹配的对话",
                            // 它站在"本该是列表行"的位置，所以跟行标题同档
                            AiTypo.listItem,
                            color = VColors.ink3,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        groups.forEachIndexed { groupIndex, (title, list) ->
                            item(key = "head_$title") { GroupTitle(title) }
                            conversationItems(
                                list = list,
                                showDividerAfter = groupIndex != groups.lastIndex,
                            ) { conversation ->
                                ConversationRow(
                                    conversation = conversation,
                                    /* 是不是"当前正待在里面"的那条 —— 用户口径：「当前身处的对话没有选框提示」 */
                                    isCurrent = conversation.id == currentId,
                                    /* 多选模式：行末画勾选框；多选时**点一行 = 勾选** */
                                    selectionMode = selectionMode,
                                    checked = conversation.id in selected,
                                    onOpen = {
                                        if (selectionMode) {
                                            selected = if (conversation.id in selected) {
                                                selected - conversation.id
                                            } else {
                                                selected + conversation.id
                                            }
                                        } else {
                                            // 关键：**先把这条设为当前对话**，再翻页
                                            viewModel.open(conversation.id)
                                            onOpen()
                                        }
                                    },
                                    onLongPress = {
                                        /*
                                         * 长按 = **进入多选并选中这一条**（用户最容易想到的入口）。
                                         *
                                         * 已经多选时，长按仍然只是"选中这一条"的补充手段 ——
                                         * 不弹操作表（那时用户在做批量操作，
                                         * 弹一个单条操作表会打断他）。
                                         */
                                        if (selectionMode) {
                                            selected = selected + conversation.id
                                        } else {
                                            selectionMode = true
                                            selected = setOf(conversation.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /*
     * 弹窗：**互斥**。
     *
     * 每个回调都在 `VDialog` 的**退场动画之后**才执行（`close(after)` 的语义），
     * 所以从操作表切到确认框时，是先看到操作表收起来、再弹出确认框，
     * 中间不存在两个窗口并存的时刻。
     */
    /*
     * 用 `key(dialog)` 把每个弹窗的**内部状态隔开**。
     *
     * `VDialog` 内部用 `remember { mutableStateOf(false) }` 记"正在退场"，
     * 切弹窗时若那份状态被复用，新弹窗会带着 `closingState = true` 出生 ——
     * 也就是刚画出来就立刻自己关掉。加上 key 之后每次切换都是全新的一份，
     * 这种事在结构上不可能发生。
     */
    key(dialog) {
        when (val current = dialog) {
            null -> Unit

            is ListDialog.Actions -> ConversationActionsDialog(
                conversation = current.conversation,
                onPin = {
                    dialog = null
                    viewModel.togglePin(current.conversation.id)
                },
                onRename = { dialog = ListDialog.Rename(current.conversation) },
                onDelete = { dialog = ListDialog.Delete(current.conversation) },
                onDismiss = { dialog = null },
            )

            is ListDialog.Rename -> {
                var draft by remember(current.conversation.id) {
                    mutableStateOf(current.conversation.title)
                }
                VFloatingInputDialog(
                    icon = Lucide.Pencil,
                    value = draft,
                    onValueChange = { draft = it },
                    onConfirm = {
                        viewModel.renameConversation(current.conversation.id, draft)
                        dialog = null
                    },
                    onDismiss = { dialog = null },
                    placeholder = "对话名称",
                    confirmText = "保存",
                )
            }

            is ListDialog.Delete -> VConfirmDeleteDialog(
                title = "删除这段对话？",
                message = "删除后无法撤销，全部轮次都会一并移除。",
                objectName = current.conversation.title.ifBlank { "新对话" },
                onConfirm = {
                    viewModel.deleteConversation(current.conversation.id)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )

            /*
             * 批量删除的二次确认。
             *
             * ⚠️ **必须确认**（与单条删除同一个理由）：这是不可撤销的，
             * 而且批量删一次可能干掉几十条。多选场景下误触更容易发生
             *（点一行就是勾选，手滑多勾一条毫无感觉）。
             */
            is ListDialog.BatchDelete -> VConfirmDeleteDialog(
                title = "删除这 ${current.ids.size} 段对话？",
                message = "删除后无法撤销，全部轮次都会一并移除。",
                objectName = "已选 ${current.ids.size} 段对话",
                onConfirm = {
                    viewModel.deleteConversations(current.ids)
                    dialog = null
                    exitSelection()
                },
                onDismiss = {
                    /*
                     * 取消删除**保留多选状态**（用户很可能只是想在删之前
                     * 再确认一下选对了没有），只关掉这个弹窗。
                     */
                    dialog = null
                },
            )
        }
    }
}

/**
 * 列表页同时只可能打开的一个弹窗。
 *
 * 用密封类而不是三个可空 `var`：后者允许"两个同时非空"，
 * 而那正是「黑色遮罩不消失、操作无响应」那个 bug 的来源。
 */
private sealed interface ListDialog {
    val conversation: Conversation

    /** 长按弹出的操作表：置顶 / 重命名 / 删除。 */
    data class Actions(override val conversation: Conversation) : ListDialog

    /** 重命名输入框。 */
    data class Rename(override val conversation: Conversation) : ListDialog

    /** 删除确认。 */
    data class Delete(override val conversation: Conversation) : ListDialog

    /**
     * 批量删除确认（多选模式）。
     *
     * 它没有单一的 `conversation`，所以 `conversation` 用列表里的一条占位
     * —— 这个字段只有单条那三个分支用得到，批量分支不读它。
     * 用"传一个假的"而不是把接口改成可空，是因为可空会让三个单条分支
     * 每处都要 `!!` 或多一次判断，而那三处才是绝大多数路径。
     */
    data class BatchDelete(
        val ids: Set<String>,
        override val conversation: Conversation = PLACEHOLDER,
    ) : ListDialog {
        companion object {
            /** 占位：批量确认框不显示单条信息，所以它永远用不到。 */
            private val PLACEHOLDER = Conversation(id = "", createdAt = 0, updatedAt = 0)
        }
    }
}

/**
 * 多选模式的头部（与普通头部**同高 56**，所以切换时不跳）。
 *
 * ```
 * [取消]   已选 3 条              [全选] [置顶] [取消置顶] [删除]
 * ```
 *
 * ## 为什么不复用普通头部再"多画几个按钮"
 *
 * 两者**在做不同的事**：普通头部的 [＋] 是"开新对话"，多选头部不需要它
 *（正在挑要删哪几条时，开新对话是无关动作）。硬塞在一起会变成
 * 一个按钮会根据模式变含义的头部 —— 那是用户最难预测的一种界面。
 *
 * ## 置顶与取消置顶**分开两个按钮**
 *
 * 合成一个"切换"按钮的话，选中项里有置顶的也有没置顶的时候，
 * 用户根本不知道点下去会变成什么。两个明确动作没有歧义，
 * 代价只是多一个图标（空间够：头部 56dp 高、图标 21dp）。
 */
@Composable
private fun SelectionHeader(
    count: Int,
    total: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 「取消」用**文字**而不是返回箭头：这一层不是"返回上一页"，
        // 而是"退出这个模式"，措辞要准确
        VText(
            "取消",
            AiTypo.listItem,
            color = VColors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .vPressable(scaleDown = 0.92f, onClick = onCancel)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )

        Spacer(Modifier.width(14.dp))

        VText(
            if (count == 0) "选择对话" else "已选 $count 条",
            AiTypo.listItem,
            color = VColors.ink,
            modifier = Modifier.weight(1f),
        )

        /*
         * 「全选」：`count == total` 时显示为"取消全选"。
         *
         * 用**当前筛选后**的总数比较（`filtered.size`）—— 搜索框里筛过的意思
         * 就是要对这批操作，全选不该把没显示的那些也选上。
         */
        if (total > 0) {
            VText(
                if (count == total) "取消全选" else "全选",
                AiTypo.settingValue,
                color = VColors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .vPressable(scaleDown = 0.92f, onClick = onSelectAll)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(6.dp))
        }

        /*
         * 批量动作：**没选中任何一条时置灰不可点**。
         *
         * ⚠️ 必须真的禁用（`enabled = false`）而不是只改颜色 ——
         * `vPressable(enabled = false)` 会同时拒绝点击。
         * 点一个"选中 0 条时删除"会走到批量删除的守卫上（它直接返回），
         * 表现为"点了没反应"，那是很差的手感。
         */
        val hasSelection = count > 0
        SelectionAction(Lucide.Pin, "置顶", hasSelection, onPin)
        SelectionAction(Lucide.PinOff, "取消置顶", hasSelection, onUnpin)
        SelectionAction(Lucide.Trash2, "删除", hasSelection, onDelete, danger = true)
    }
}

/** 多选头部里的一个图标动作。 */
@Composable
private fun SelectionAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .vPressable(enabled = enabled, scaleDown = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            // 禁用态用 `line`（最淡的一档）—— 与"未选中"的勾选框同一个语言
            tint = when {
                !enabled -> VColors.line
                danger -> VColors.rose
                else -> VColors.ink
            },
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * 长按后的操作表。
 *
 * 设计稿没有这一层，所以形态照项目既有的弹窗语言来：
 * `VDialog` 面板 + 若干等高行（图标 18 + 文案），删除那行用警示色。
 */
@Composable
private fun ConversationActionsDialog(
    conversation: Conversation,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss) {
        val close = LocalVDialogClose.current
        VDialogPanel(horizontalPadding = 8.dp, verticalPadding = 8.dp) {
            ActionSheetRow(
                icon = Lucide.Pin,
                label = if (conversation.pinned) "取消置顶" else "置顶",
                tint = VColors.ink,
            ) { close(onPin) }
            ActionSheetRow(icon = Lucide.Pencil, label = "重命名", tint = VColors.ink) { close(onRename) }
            ActionSheetRow(
                icon = Lucide.Trash2,
                label = "删除",
                tint = VColors.rose,
                danger = true,
            ) { close(onDelete) }
        }
    }
}

@Composable
private fun ActionSheetRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .vPressable(scaleDown = 0.97f, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        VText(label, AiTypo.listItem, color = if (danger) VColors.rose else VColors.ink)
    }
}

/**
 * 搜索框：高 52、圆角 14、水平 pad 14、间距 9、底色 surface。
 *
 * 不复用项目的 `VSearchField` —— 那个的尺寸与这里的设计稿不同。
 * 语义相近但尺寸不同的组件不该硬套（这正是我第一版犯的错）。
 */
@Composable
private fun ListSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VColors.surface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Lucide.Search, contentDescription = null, tint = VColors.ink3, modifier = Modifier.size(16.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                fontSize = AiTypo.searchPlaceholder.fontSize,
                fontFamily = BodyFont,
                color = VColors.ink,
            ),
            cursorBrush = SolidColor(VColors.accent),
            singleLine = true,
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    VText("搜索对话内容", AiTypo.searchPlaceholder, color = VColors.ink3)
                }
                inner()
            },
        )
    }
}

/** 分组标题：高 41，左 pad 16，字色 ink-3。 */
@Composable
private fun GroupTitle(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            /*
             * 上方多留一点，下方不动 —— 让标题和它**下面**那一组贴在一起，
             * 而不是和**上面**那一组贴在一起。这是分组标题该有的视觉归属。
             *
             * 用户报的：「距离上方太近了，应该和下方的间距一致」。
             */
            .padding(top = LIST_GROUP_TOP_PADDING)
            .height(41.dp)
            .padding(start = ListRowStartPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        VText(title, AiTypo.listGroup, color = VColors.ink3)
    }
}

/**
 * 列表行的**统一左边距**。
 *
 * ## ⚠️ 用户报的「时间标识位置发生了偏移，应该对齐左侧」
 *
 * 根因是**两处各写各的**：
 *
 * ```
 * GroupTitle       自己有 padding(start = 16.dp)   →  16dp
 * ConversationRow  完全没有 padding                →   0dp  ← 贴着屏幕边
 * ```
 *
 * 于是「今天」缩进 16dp，而它下面的对话标题**顶到屏幕左边缘** ——
 * 竖排下来左边界犬牙交错。
 *
 * ## 修法是让它们**读同一个常量**
 *
 * 不是"把 16 改成 14 就对齐了" —— 那样下次谁改一处又会漂。
 * 抽成一个常量、两处都引用，**对不齐这件事从结构上就不可能再发生**。
 *
 * 取 **16dp**（分组标题原来的值）：那是设计稿给的缩进，
 * 而且对话标题缩进 16dp 在视觉上比 0dp 更稳（不顶着边）。
 */
private val ListRowStartPadding = 16.dp

/**
 * 分组标题的上方留白。
 *
 * 用户口径：「距离上方太近了，应该和下方的间距一致」。
 *
 * 分组标题高 41dp（设计稿），文字垂直居中 → 文字上下各有约 11dp。
 * 再加 8dp 顶部留白，让"标题上方"（8 + 11 = 19dp）略大于
 * "标题到第一条对话"（11dp）—— **标题因此看起来属于下面那一组**，
 * 这正是分组标题该有的视觉归属。
 */
private val LIST_GROUP_TOP_PADDING = 8.dp

/** 一行对话：高 48。**点=进入，长按=操作表**；多选模式下点=勾选。 */
@Composable
private fun ConversationRow(
    conversation: Conversation,
    /** 是不是"当前正待在里面"的那条。 */
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    /** 多选模式：行末画勾选框，且底色不再表示"当前对话"。 */
    selectionMode: Boolean = false,
    checked: Boolean = false,
) {
    /*
     * 外层管**对齐**，内层管**色块** —— 两层职责分开，就不会互相干扰。
     *
     * ```
     * Box   左右各 ListRowStartPadding(16)   ← 保证文字与分组标题同一条竖线
     *   Row 左右各 -8（用负 margin 做不到，所以反过来做：）
     * ```
     *
     * Compose 没有负 padding，所以换个思路：
     *
     * ```
     * Box   左右各 (ListRowStartPadding - 8) = 8   ← 色块的外边界
     *   Row 左右各 8                                ← 文字的最终缩进 = 8 + 8 = 16 ✓
     * ```
     *
     * 于是**文字左边距 = 16**（与分组标题对齐），而**色块比文字左右各宽出 8dp**
     * —— 色块不顶到屏幕边，看起来是张卡片而不是一条色带。
     */
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ListRowStartPadding - 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                /*
                 * 当前对话的底色标记（用户口径：「当前身处的对话没有选框提示」）。
                 *
                 * ⚠️ **用 `accentSoft` 而不是自创新的选中样式** ——
                 * 那是项目里已有的惯例（今日页的"当前时段"就是这么标的，
                 * 见 `TodayScreenV2`）。新造一个颜色会让同一种语义在
                 * 两个页面长得不一样。
                 *
                 * ## 为什么染整行而不是加一个勾选框
                 *
                 * 用户原话是「没有选框提示」，但重点是**没有任何提示**，
                 * 不是"必须画个框"。整行染色：
                 *
                 * · 不占横向空间（列表行本来就窄，加个 22dp 的框会挤压标题）
                 * · 和今日页的"当前位置"是同一种语言 —— 用户已经认识它
                 * · 48dp 整行染，扫列表时比一个小框更容易看见
                 *
                 * ## ⚠️ 多选模式下**不染"当前"色**
                 *
                 * 多选时行末已经有勾选框了，再叠一层"当前对话"的底色会让
                 * 两套语义（"我在这儿" vs "我选中了这条"）在同一行上打架，
                 * 用户分不清那个绿色是"当前位置"还是"已勾选"。
                 * 所以多选时交出色块，只留勾选框这一种标记。
                 */
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        selectionMode -> Color.Transparent
                        isCurrent -> VColors.accentSoft
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = 8.dp)
                .vPressable(onLongPress = onLongPress, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /*
             * 多选勾选框：**放在标题左边**而不是右边。
             *
             * 放右边会和置顶图钉抢位置（两个都是行末的次要标记），
             * 而勾选框在多选模式下是**主要**控件 —— 它该在最显眼的位置。
             * 左边还符合"列表多选"的通用版式（相册、文件管理器都这样）。
             */
            if (selectionMode) {
                SelectionCheckbox(checked = checked)
            }

            VText(
                conversation.title.ifBlank { "新对话" },
                /*
                 * 当前那条**加重字重**：浅色主题下 `accentSoft` 本身很淡，
                 * 只靠底色在小屏上不够显眼。字重变化不占宽度，是免费的强调。
                 *
                 * ⚠️ 字重走 `style` —— `VText` 的签名是
                 * `(text, style, modifier, color, maxLines, align, overflow)`，
                 * **没有 `weight` 参数**（我第一版凭印象写了一个，编译报错）。
                 */
                AiTypo.listItem.copy(
                    fontWeight = if (isCurrent && !selectionMode) {
                        FontWeight.Medium
                    } else {
                        FontWeight.Normal
                    },
                ),
                color = VColors.ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (conversation.pinned) {
                Icon(Lucide.Pin, contentDescription = "已置顶", tint = VColors.ink3, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * 多选勾选框。圆形、20dp —— 与项目其余勾选控件同一量级。
 *
 * 它**只显示状态、不接点击**：点击由整行接管（`ConversationRow` 的
 * `onOpen` 在多选模式下就是"切换勾选"）。20dp 的圆点手指点不准，
 * 让整行可点才是对的 —— 这也是 `ChangePreviewCard` 里那个勾选框的做法。
 */
@Composable
private fun SelectionCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (checked) VColors.accent else Color.Transparent)
            .then(
                if (checked) {
                    Modifier
                } else {
                    Modifier.border(1.5.dp, VColors.ink3, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Lucide.Check, contentDescription = "已选中", tint = Color.White, modifier = Modifier.size(13.dp))
        }
    }
}

/**
 * 一个分组里的对话项；每项之后可选地画一条分隔线。
 *
 * 设计稿里分隔线出现在**组与组之间**（不是每一项后面）。
 */
private fun LazyListScope.conversationItems(
    list: List<Conversation>,
    showDividerAfter: Boolean,
    content: @Composable (Conversation) -> Unit,
) {
    items(list, key = { it.id }) { conversation ->
        Column {
            content(conversation)
            val isLast = list.lastOrNull()?.id == conversation.id
            if (isLast && showDividerAfter) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(VColors.line),
                )
            }
        }
    }
}

/**
 * 按时间分组（对齐设计稿：置顶 / 今天 / 昨天 / 7 天内 / 更早）。
 *
 * 用 `Calendar` 做"今天/昨天"的判断 —— 需要的是**本地日历日**的边界，
 * 而 `java.time` 要显式传时区才等价于"用户看到的今天"。
 */
internal fun groupConversations(
    list: List<Conversation>,
    now: Long = System.currentTimeMillis(),
): List<Pair<String, List<Conversation>>> {
    val pinned = list.filter { it.pinned }
    val rest = list.filterNot { it.pinned }

    val today = ArrayList<Conversation>()
    val yesterday = ArrayList<Conversation>()
    val withinWeek = ArrayList<Conversation>()
    val earlier = ArrayList<Conversation>()

    val startOfToday = startOfDay(now)
    val startOfYesterday = startOfToday - DAY_MILLIS
    val weekAgo = startOfToday - 6 * DAY_MILLIS

    rest.forEach { c ->
        when {
            c.updatedAt >= startOfToday -> today += c
            c.updatedAt >= startOfYesterday -> yesterday += c
            c.updatedAt >= weekAgo -> withinWeek += c
            else -> earlier += c
        }
    }

    return buildList {
        if (pinned.isNotEmpty()) add("置顶" to pinned)
        if (today.isNotEmpty()) add("今天" to today)
        if (yesterday.isNotEmpty()) add("昨天" to yesterday)
        if (withinWeek.isNotEmpty()) add("7 天内" to withinWeek)
        if (earlier.isNotEmpty()) add("更早" to earlier)
    }
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
    timeInMillis = at
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
