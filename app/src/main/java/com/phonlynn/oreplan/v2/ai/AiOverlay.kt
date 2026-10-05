package com.phonlynn.oreplan.v2.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion

/**
 * AI 浮层：盖在整棵界面树之上的全屏层。
 *
 * ## 为什么是浮层而不是导航跳转
 *
 * 用户的要求：
 *  · 「无论在哪里都能唤起」—— 包括二级页、编辑弹窗、白板拖动中
 *  · 「退出去以后还得回到原来的位置」
 *
 * 导航跳转会**销毁**底层页面 —— 滚动位置、未保存的输入、
 * 白板上的临时状态全都会丢。所以必须是叠加：底层 Composable 保持存活。
 *
 * ## 内部页面切换（对话 / 列表 / 设置）
 *
 * 用一个内部状态而不是接进主导航栈 —— 浮层的生命周期不该影响主返回栈。
 *
 * ## ⚠️ 这里踩过一个坑，值得记下来
 *
 * 第一版写成：
 * ```
 * var page by remember { mutableStateOf(Chat) }
 * if (visible && page != Chat) {
 *     LaunchedEffect(visible) { if (visible) page = Chat }
 * }
 * ```
 * 意图是「每次唤起都回到对话页」。实际效果是：
 * **点「列表」→ page 变成 List → 这段立即把它重置回 Chat → 页面闪一帧就没了。**
 *
 * 根因是把「唤起时重置」写成了「只要不在对话页就重置」。
 * `LaunchedEffect(visible)` 的 key 只有 `visible`，而 `visible` 一直是 true ——
 * 于是它不会重新启动，但 `if` 外层让这段代码在每次重组时都参与判定，
 * 配合状态变化就形成了「设了就改回去」的循环。
 *
 * 正确做法：**在 `visible` 的上升沿重置一次**，用 `LaunchedEffect(visible)` 直接
 * 放在最外层、不带条件包裹。这样它只在真正的「打开」那次跑一遍。
 */
@Composable
fun AiOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(OverlayPage.Chat) }

    /*
     * 每次**打开**时回到对话页（上一次可能停在设置页，但再次唤起想看的是对话）。
     *
     * 关键：这个 effect 的 key 是 `visible`，只在它翻转时跑一次。
     * **不要**把它包在 `if (page != Chat)` 里 —— 那样会在用户切页时把它重置回去
     *（详见类注释里记的那个 bug）。
     */
    LaunchedEffect(visible) {
        if (visible) page = OverlayPage.Chat
    }

    /*
     * 返回键：先退内部页面，再关浮层。
     *
     * `enabled = visible`：浮层不可见时**不拦截**返回键，
     * 否则会把整个 App 的返回行为吃掉。
     */
    BackHandler(enabled = visible) {
        when (page) {
            OverlayPage.Chat -> onDismiss()
            OverlayPage.List -> page = OverlayPage.Chat
            OverlayPage.Settings -> page = OverlayPage.Chat
            OverlayPage.ChatModel -> page = OverlayPage.Settings
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(VMotion.EnterMillis)) +
            slideInVertically(tween(VMotion.EnterMillis, easing = VMotion.Expressive)) { it / 12 },
        exit = fadeOut(tween(VMotion.ExitMillis)) +
            slideOutVertically(tween(VMotion.ExitMillis, easing = VMotion.Accelerate)) { it / 14 },
        modifier = modifier.zIndex(100f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(VColors.bg),
        ) {
            when (page) {
                OverlayPage.Chat -> ChatScreen(
                    onBack = onDismiss,
                    onOpenList = { page = OverlayPage.List },
                    onOpenSettings = { page = OverlayPage.Settings },
                )

                OverlayPage.List -> ConversationListScreen(
                    onBack = { page = OverlayPage.Chat },
                    // 列表页自己调 viewModel.open(id)，这里只负责翻页 ——
                    // 上一版这里接的是一个丢掉 id 的 lambda，点哪条都进不去。
                    onOpen = { page = OverlayPage.Chat },
                    onNew = { page = OverlayPage.Chat },
                )

                OverlayPage.Settings -> AiSettingsScreen(
                    onBack = { page = OverlayPage.Chat },
                    onOpenChatModel = { page = OverlayPage.ChatModel },
                )

                OverlayPage.ChatModel -> ChatModelConfigScreen(
                    onBack = { page = OverlayPage.Settings },
                )
            }
        }
    }
}

/** 浮层内部的页面。 */
private enum class OverlayPage { Chat, List, Settings, ChatModel }
