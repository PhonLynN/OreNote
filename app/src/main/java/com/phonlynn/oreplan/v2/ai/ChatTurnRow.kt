package com.phonlynn.oreplan.v2.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.domain.ai.ChatTurn
import com.phonlynn.oreplan.domain.ai.ToolCallRecord
import com.phonlynn.oreplan.v2.components.VSpinner
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
/**
 * 消息流里的一条。
 *
 * 结构照设计稿 `AI · 对话`：
 * ```
 * 用户气泡（右对齐，圆角 16，内边距 9×13）。
 * 已思考（用时 N 秒）⌄ 可折叠，高 19，与下一块间距 18
 *   推理内容（左 2dp 竖线，竖线高度跟着正文长度）
 * AI 正文（左对齐，通栏宽）
 * 操作行（4 个图标 18，间距 22，右侧轮次切换器）
 * ```
 *
 * 所有数值来自设计稿实测 —— 见 `verification/plan030/ai_spec.py`。
 * 要改数值先去量设计稿，不要凭印象调。
 *
 * ## 块间距统一 18
 *
 * 设计稿里上面这些块**都是 Content 的直接子项**，而 Content 的 `gap = 18`。
 * 所以从「已思考」到「推理内容」、从「正文」到「操作行」全都是 18 ——
 * 我第一版把标签与推理内容收进一个 8dp 间距的小容器里，与设计稿不符。
 */
@Composable
fun ChatTurnRow(
    turn: ChatTurn,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    onAction: (ChatAction) -> Unit,
    roundSwitch: RoundSwitch? = null,
    /** 这条正在**原位编辑**（编辑框就渲染在它的位置上）。 */
    editing: Boolean = false,
    onBeginEdit: () -> Unit = {},
    onCommitEdit: (String) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    /**
     * **长按用户消息**要复制的那段文字（用户 2026-10-03）。
     *
     * 只有用户消息会调它 —— AI 回复的复制走操作行那个图标
     *（`ChatAction.Copy`），因为 AI 回复是通栏的、没有"气泡"这种明确的长按目标。
     */
    onCopyText: (String) -> Unit = {},
    /**
     * 要不要在状态行右端显示 token 消耗（来自 AI 设置里的开关）。
     *
     * 传 `false` 时那一格整体不画 —— 但**状态行本身照常**，
     * 所以拨开关不会让消息流跳一下。
     */
    showTokens: Boolean = true,
    /**
     * 处理这条回合里**待确认**的写操作（预览、勾选、应用、驳回）。
     *
     * 历史消息里也要传它 —— 用户口径：「重进的时候肯定还是可以继续回答的」。
     * 待确认状态是**落库**的（`status = PENDING`），所以关掉 App 再回来
     * 那张卡仍然可点。不传的话它就变成一张永远点不动的死卡。
     */
    pending: PendingActions = PendingActions(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (turn.isUser) {
            /*
             * 用户口径：点自己的消息本身**就在原位置打开编辑窗**。
             * 入口不放铅笔 —— 铅笔那条属于 AI 回复。
             */
            if (editing) {
                InlineEditor(
                    initial = turn.content.orEmpty(),
                    bubble = true,
                    onCommit = onCommitEdit,
                    onCancel = onCancelEdit,
                )
            } else {
                UserBubble(
                    text = turn.content.orEmpty(),
                    onClick = onBeginEdit,
                    onCopy = { onCopyText(turn.content.orEmpty()) },
                )
            }
        } else {
            /*
             * 状态行：「已思考」还是「已生成」**看有没有真的思考内容**。
             *
             * ⚠️ 这里踩过一个坑：原来判断的是 `reasoningMillis != null`，
             * 而那个值在**每条回复**上都会被写上（它是 `now - startedAt`，
             * 也就是整段生成耗时，跟有没有思考无关）。于是没开深度思考时
             * 也照样显示「已思考（用时 N 秒）」—— 用户报的正是这个。
             *
             * 现在以**推理正文是否为空**为准：
             *  · 有推理 → 「已思考（用时 N 秒）」＋可展开的箭头
             *  · 没推理（没开思考，或厂商没返回）→ 「已生成」，**不画箭头**
             *    箭头是"这里能展开"的暗示，没有内容可展开时画它是误导。
             *
             * ## ⚠️ 这一行**无条件渲染**（用户报的"状态信息消失又出现"）
             *
             * 原来外面套着 `if (hasReasoning || turn.reasoningMillis != null)`。
             * 那个条件与流式版（`StreamingTurn`）的判据不同，两者在"正文刚
             * 开始"与"刚落库"两个时刻分别翻转 —— 中间那段时间**两边都不画**，
             * 于是状态行消失、然后突然回来，把下面的文字顶上去又顶下来。
             *
             * 现在两边都是**永远画**，只有文案不同（见 `StreamingTurn` 的注释）。
             * 一条助手消息永远有状态行 —— 它本来就是这一轮的标题。
             */
            val hasReasoning = !turn.reasoning.isNullOrBlank()
            ThinkingRow(
                label = if (hasReasoning) thoughtLabel(turn.reasoningMillis) else "已生成",
                expanded = reasoningExpanded,
                onToggle = onToggleReasoning,
                expandable = hasReasoning,
                // 开关关掉、或厂商没返回用量 → null → 那一格不画
                tokens = turn.totalTokens
                    ?.takeIf { showTokens }
                    ?.let { "${it.withThousandsSeparator()} tokens" },
            )
            if (hasReasoning && reasoningExpanded) {
                ReasoningBlock(turn.reasoning!!)
            }

            if (editing) {
                // 原位编辑 AI 回复的正文（铅笔那条入口）
                InlineEditor(
                    initial = turn.content.orEmpty(),
                    bubble = false,
                    onCommit = onCommitEdit,
                    onCancel = onCancelEdit,
                )
            } else if (turn.rounds.isNotEmpty()) {
                /*
                 * 按**协议轮次**的顺序渲染：正文 → 工具卡 → 正文 …
                 *
                 * 一轮里模型可能边说边调工具，顺序是有意义的。设计稿 `jTU7u` 画的就是
                 * 「正文『好的，我来创建一个日程。』→ 工具卡 → 正文『已创建：…』」。
                 *
                 * ⚠️ 只渲染 `turn.content`（= 最后一轮）会**丢掉前面几轮说的话** ——
                 * 接工具之后那成了常态（模型先应一声，拿到结果再总结）。
                 *
                 * `rounds` 为空的按老路径渲染，见下面的 else。
                 */
                val byId = turn.toolCalls.associateBy { it.id }
                turn.rounds.forEach { round ->
                    round.content?.takeIf { it.isNotBlank() }?.let {
                        AiMarkdownText(it, baseStyle = AiTypo.body, color = VColors.ink)
                    }
                    RenderRoundCalls(round.callIds.mapNotNull { byId[it] }, pending)
                }
            } else {
                // 老数据（接工具之前存的）：工具卡在前、正文在后
                RenderRoundCalls(turn.toolCalls, pending)
                /*
                 * AI 正文走 Markdown 渲染。
                 *
                 * 模型返回的是 Markdown（`**粗体**`、`` `代码` ``、`- 列表`），
                 * 直接当纯文本显示的话用户看到的是**一堆星号和反引号**（用户反馈过）。
                 */
                turn.content?.takeIf { it.isNotBlank() }?.let {
                    AiMarkdownText(it, baseStyle = AiTypo.body, color = VColors.ink)
                }
            }

            // 编辑中不显示操作行 —— 那行图标此刻没有意义，只会挤位置
            if (!editing && (turn.content?.isNotBlank() == true || turn.toolCalls.isNotEmpty())) {
                ActionRow(onAction, roundSwitch)
            }
        }
    }
}

/**
 * 原位编辑框：**就长在消息原本的位置上**（用户口径）。
 *
 * · 用户消息 → 保持右对齐气泡的形态，只是换成可输入
 * · AI 回复 → 通栏，可输入
 *
 * 下面是「取消 / 保存」两个文字按钮。空内容保存 = 删除这条消息
 *（见 `ChatViewModel.commitEdit`）。
 *
 * 自动聚焦：点进来就是为了改字，再让用户点一次输入框是多余的一步。 */
@Composable
private fun InlineEditor(
    initial: String,
    bubble: Boolean,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // key 带 initial：切换到另一条消息编辑时状态要重来，不能带着上一条的草稿
    var text by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (bubble) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .then(if (bubble) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(16.dp))
                .background(if (bubble) VColors.surface2 else VColors.surface)
                .border(1.5.dp, VColors.accent, RoundedCornerShape(16.dp))
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                textStyle = (if (bubble) AiTypo.bubbleBody else AiTypo.body).copy(color = VColors.ink),
                cursorBrush = SolidColor(VColors.accent),
                maxLines = 12,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            VText(
                "取消",
                AiTypo.settingValue,
                color = VColors.ink3,
                modifier = Modifier.vPressable(scaleDown = 0.9f, onClick = onCancel),
            )
            VText(
                "保存",
                AiTypo.settingValue,
                color = VColors.accent,
                modifier = Modifier.vPressable(scaleDown = 0.9f) { onCommit(text) },
            )
        }
    }
}

// ---------------------------------------------------------------- 流式区
/**
 * 正在生成时的两块内容：思考块 + 正文。
 *
 * ## 为什么单独成一个组件
 *
 * 流式期间推理内容与正文**都还没落库**，它们不属于 [ChatTurn]。
 * 但显示形态要和落库后的完全一致（否则回复完成的一瞬间会跳一下）。
 *
 * ## 三个状态都要有反馈
 *
 * ```
 * 刚发出，什么都还没回来     → 「思考中」/「生成中」＋动态点    ← 我第一版这里**完全空白**
 * 推理在流                   → 「正在思考」＋逐字长出来的推理
 * 正文在流                   → 推理块（若有）＋逐字长出来的正文
 * ```
 *
 * 第一版只在 `streaming` 非空时才画东西，而首 token 之前 `streaming` 是空串 ——
 * 于是「按下发送」到「第一个字到达」这段**最长**的等待里屏幕上什么都没有。
 *
 * @param deepThinking 这一轮有没有开深度思考。没开时等待提示是「生成中」而不是
 *   「思考中」—— 跟 [ChatTurnRow] 里「已思考 / 已生成」的分叉同一个道理：
 *   文案必须跟**实际发生的事**一致。 */
@Composable
fun StreamingTurn(
    reasoning: String?,
    content: String?,
    deepThinking: Boolean = false,
    /**
     * **生成中的这一回合的快照**（`ChatViewModel.liveTurn`）。
     *
     * 接了工具之后，一轮回复底下可能夹着好几张工具卡，顺序有意义
     *（正文 → 卡片 → 正文）。所以这里不自己拼，而是**用和正式回合相同的渲染路径**
     * 渲染快照里的轮次 —— 两份实现迟早会漂移，而漂移的表现就是
     * "生成中看到的顺序"和"生成完看到的顺序"不一样。
     *
     * `reasoning` / `content` 仍然单独传：那是**当前这一轮还没结束**的增量文本，
     * 快照里只包含已经说完的轮次。
     */
    liveTurn: ChatTurn? = null,
    /** 生成过程中也可能出现待确认的写操作（模型调了写工具就停在这里等用户）。 */
    pending: PendingActions = PendingActions(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        /*
         * 状态行（「思考中」/「正在思考」/「生成中」）。
         *
         * ## ⚠️ 判据必须与**落库后**的那一版**逐字对齐**（用户报的"瞬移两次"）
         *
         * 用户原话：
         *
         * > 「在生成正文的时候，生成状态信息（生成中，正在思考之类的）会消失，
         * > 然后在生成完成后瞬间出现，让文字发生两次瞬移」
         *
         * ## 根因（`if` 条件与落库版不是同一个）
         *
         * | | 状态行什么时候画 |
         * |---|---|
         * | 流式中（这里） | `content.isNullOrEmpty() && liveTurn == null` |
         * | 落库后（[ChatTurnRow]） | `hasReasoning \|\| reasoningMillis != null` |
         *
         * 两个条件**在正文开始的瞬间会同时翻转**：
         *
         * ```
         * 思考结束、第一个正文字到达
         *   → content 不再为空  ⇒ 这里的状态行**立刻消失**   （第一次瞬移）
         *   → 但还没落库，所以落库版的状态行也还没出现
         * 流结束、落库
         *   → reasoningMillis 被写上 ⇒ 落库版的状态行**突然出现**（第二次瞬移）
         * ```
         *
         * 于是状态行"没了又冒出来"，上下两段文字各跳一次。
         *
         * ## 修法：状态行**从头到尾都在**，只是文案在变
         *
         * 它本来就是这一轮的"标题"，不该有消失这一说。三个阶段的文案：
         *
         * | 阶段 | 文案 | 右侧 |
         * |---|---|---|
         * | 刚开始 | 「思考中」/「生成中」 | 动态点 |
         * | 思考在流 | 「正在思考」 | 动态点 |
         * | 正文在流 | 「正在生成」 | 动态点 |
         * | 落库后 | 「已思考（用时 N 秒）」/「已生成」 | chevron |
         *
         * 从流式中切到落库版时，**行本身不消失**（只是文案换了一下），
         * 所以不会有"消失—出现"两次瞬移。
         */
        ThinkingRow(
            label = when {
                !reasoning.isNullOrEmpty() -> "正在思考"
                deepThinking -> "思考中"
                else -> "生成中"
            },
            expanded = true,
            onToggle = {},
            live = true,
        )
        if (!reasoning.isNullOrEmpty()) {
            ReasoningBlock(reasoning)
        }

        /*
         * 已经说完的轮次：正文 + 它们各自的工具卡，顺序按协议轮次来。
         * 这一段与 [ChatTurnRow] 里对 `rounds` 的渲染是同一套规则。
         */
        liveTurn?.let { live ->
            val byId = live.toolCalls.associateBy { it.id }
            live.rounds.forEach { round ->
                round.content?.takeIf { it.isNotBlank() }?.let {
                    AiMarkdownText(it, baseStyle = AiTypo.body, color = VColors.ink)
                }
                round.callIds.forEach { callId ->
                    byId[callId]?.let { ToolCallCard(it, pending) }
                }
            }
        }

        if (!content.isNullOrEmpty()) {
            AiMarkdownText(content, baseStyle = AiTypo.body, color = VColors.ink)
        }
    }
}

// ---------------------------------------------------------------- 组成块
/**
 * 用户气泡：右对齐，圆角 16，内边距 竖直 9 / 水平 13。
 *
 * ## 两种手势，两个不同的动作（用户口径）
 *
 * | 手势 | 动作 |
 * |---|---|
 * | **单击** | 在**原位置**打开编辑窗 |
 * | **长按** | **复制这段文字**（用户 2026-10-03：「长按用户发送的文字应该可以复制」） |
 *
 * ## 为什么长按给的是"复制"而不是"更多菜单"
 *
 * 用户明确要的就是复制，而且这个动作在手机上已经形成肌肉记忆
 *（微信、短信里长按气泡都是复制）。加一层菜单反而多一次点击。
 *
 * ## ⚠️ 长按之后松手**不能再触发单击**
 *
 * 这一条项目里踩过坑（白板卡片那次：长按拖动松手后又开了一次卡片）。
 * 修法是 `/` 的两条通道语义互斥 —— `vPressable(onLongPress = ...)` 内部走
 * `combinedClickable`，它保证"长按成立后抬手不算点击"。**不要**自己用
 * `detectTapGestures` 拼，那是当初出问题的地方。
 *
 * @param onCopy 长按复制。**由界面层做剪贴板与提示** —— 这一层不该知道
 *   `ClipboardManager` 与震动，那两件事是平台能力。
 */
@Composable
private fun UserBubble(text: String, onClick: () -> Unit, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(VColors.surface2)
                .vPressable(scaleDown = 0.98f, onLongPress = onCopy, onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            VText(text, AiTypo.bubbleBody, color = VColors.ink)
        }
    }
}

/**
 * 「已思考（用时 N 秒）」行 —— 设计稿的 `Thinking Row`。
 *
 * 高 19，图标与文字间距 6，右侧 14dp 的 chevron。
 * 默认折叠：推理内容通常比正文长，默认展开会把对话冲得很散。
 *
 * @param live 正在等待/接收。此时右侧换成动态点。
 * @param expandable 有没有**可展开的思考内容**。false 时右侧什么都不画，
 *   而且整行不可点 —— 箭头是"这里能展开"的暗示，没内容可展开时画它是误导。
 * @param tokens 这一轮的 token 消耗文案（用户 2026-10-04 要的）。
 *   **null = 不显示**（开关关了，或厂商没返回用量）。 */
@Composable
private fun ThinkingRow(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    live: Boolean = false,
    expandable: Boolean = true,
    tokens: String? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (!live && expandable) Modifier.vPressable(onClick = onToggle) else Modifier)
            .height(19.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VText(label, AiTypo.thinkingLabel, color = VColors.ink3)
        when {
            live -> ThinkingDots()
            expandable -> Icon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                tint = VColors.ink3,
                modifier = Modifier.size(14.dp),
            )
            // 没有思考内容：既没有动态点，也没有箭头，就是一行纯文字
            else -> Unit
        }

        /*
         * token 消耗：**推到最右端**（用户口径：「位置就放在生成状态行
         * （已生成，已思考那里）的**右端**」）。
         *
         * ⚠️ 它**不参与左侧那组的存在与否** —— 开关关掉时整块不画，
         * 左侧那行（含箭头）的位置一点不动，所以不会有"开关一拨、文字跳一下"。
         *
         * ⚠️ 用 `Spacer(weight(1f))` 而不是 `Arrangement.SpaceBetween`：
         * 后者会把左侧的标签与箭头也拉开（它们必须挨着）。
         */
        if (tokens != null) {
            Spacer(Modifier.weight(1f))
            VText(tokens, AiTypo.thinkingLabel, color = VColors.ink3)
        }
    }
}

/** 三个呼吸的点：表示「还在等模型返回」。 */
@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(520, easing = VMotion.Expressive, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(VColors.ink3.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * 渲染一个协议轮次里的所有工具调用。
 *
 * ## ⚠️ 这里是「40 次确认」那个 bug 的修复点
 *
 * 用户口径：
 *
 * > 「我刚刚让 ai 把我的所有卡片从半宽换到整行，他给我列了四十多个单独的
 * > 修改卡片，而没有做成一个多任务卡片，我需要确认四十多次才能做完这个任务，
 * > **这完全无法操作**」
 *
 * 根因：模型在**一条回复里发了 N 个 `tool_calls`**（每张卡一个），
 * 而这里原来逐个渲染成 N 张卡。
 *
 * ## 合并规则
 *
 * ```
 * 这一轮里待确认的写操作  ≥ 2 个  →  合成一张卡（内部逐条勾选）
 *                         = 1 个  →  保持单张形态
 * ```
 *
 * **只合并"待确认"的**：已经执行完 / 失败 / 正在跑的卡各自独立渲染 ——
 * 它们不占确认次数，合并了反而看不出哪一步做了什么。
 *
 * ## 勾选框仍然逐条
 *
 * 合并的是**确认次数**，不是记录粒度。40 张卡仍然各有勾选框，
 * 你依然可以排除其中几张 —— 变的是从 40 次确认变成 1 次。
 *
 * ## 知情取舍
 *
 * 合并之后没有"先应用一半、剩下的以后再说"了，一次处理完。
 * 对"批量改 N 张卡"这种任务这个取舍是划算的：40 次确认的体验
 * 比"必须一次处理完"糟得多。
 */
@Composable
private fun RenderRoundCalls(
    calls: List<ToolCallRecord>,
    pending: PendingActions,
) {
    val pendingCalls = calls.filter { it.status == ToolCallRecord.Status.PENDING }
    val others = calls.filter { it.status != ToolCallRecord.Status.PENDING }

    // 非待确认的照原样各自渲染（转圈 / 已完成 / 失败）
    others.forEach { ToolCallCard(it, pending) }

    if (pendingCalls.size >= 2) {
        MergedPendingCard(pendingCalls, pending)
        return
    }
    pendingCalls.forEach { ToolCallCard(it, pending) }
}

/**
 * 多个待确认写操作**合并成一张卡**。
 *
 * 复用 [ChangePreviewCard]（它已经有头部、逐条记录、勾选框、底部按钮），
 * 只多传两样：不同的标题、以及记录区的高度上限。
 *
 * **为什么不另写一张卡**：那张卡的版式（`Type Pill` / `Change Pill` /
 * 字段的旧值→新值 / 勾选框 22x22）是照设计稿 `h6z5Uh` 精确做的，
 * 抄一份出来两边就会漂移 —— 而"同一件事有两套画法"正是这个项目
 * 反复踩过的坑。
 */
@Composable
private fun MergedPendingCard(
    calls: List<ToolCallRecord>,
    pending: PendingActions,
) {
    val ids = calls.map { it.id }
    var ensured by remember(ids) { mutableStateOf(false) }

    // 每条的预览是各自算的，所以每一条都要触发
    if (!ensured) {
        LaunchedEffect(ids) {
            ids.forEach { pending.onEnsure(it) }
            ensured = true
        }
    }

    val records = calls.flatMap { pending.previews[it.id].orEmpty() }

    /*
     * 还没算出来时先画「正在执行」的卡（而不是空白），
     * 用户才知道正在准备，而不是"AI 没反应"。
     */
    if (records.isEmpty()) {
        ToolCallCard(
            calls.first().copy(status = ToolCallRecord.Status.RUNNING),
            pending,
        )
        return
    }

    /*
     * 勾选状态：各条调用的接受集合**取并集**。
     *
     * 记录 id 全局唯一，所以并集不会串 —— 而分开按调用查会让
     * "另一条调用的记录"永远查不到，表现为**取消勾选没反应**。
     */
    val checked = ids.flatMap { pending.acceptedFor(it) }.toSet()

    /** 每条记录属于哪个调用（勾选要写回它所属的那一条）。 */
    val ownerOf: (String) -> String = { recordId ->
        ids.firstOrNull { callId ->
            pending.previews[callId].orEmpty().any { it.id == recordId }
        } ?: ids.first()
    }

    ChangePreviewCard(
        records = records,
        accepted = checked,
        onToggle = { recordId -> pending.onToggle(ownerOf(recordId), recordId) },
        // 驳回 / 应用都是**一次处理全部调用** —— 那正是"合并成一张卡"的意义
        onReject = { pending.onReject(ids) },
        onConfirm = { pending.onApply(ids) },
        title = "批量改动（${records.size} 项）",
        maxRecordsHeight = MERGED_RECORDS_MAX_HEIGHT,
        showIndex = true,
    )
}

/**
 * 合并卡里记录区的高度上限 —— 约 **3 条**记录。
 *
 * 用户口径：「多任务卡片，只要超过三个就做成能上下滚动的形式会比较好」。
 *
 * 40 条记录全铺开会撑满好几屏，底部的「应用」按钮**根本滚不到**。
 * 所以限制记录区高度，超出部分卡内滚动 —— 底部按钮始终可见。
 */
private val MERGED_RECORDS_MAX_HEIGHT = 260.dp

/**
 * 推理内容：左侧 2dp 竖线 ＋ 正文。
 *
 * ## ⚠️ 竖线高度**不能写死**（用户报过）
 *
 * 设计稿里 `R Line` 是一个 `height: 46` 的固定值 —— 那是因为**设计稿里那段推理
 * 正好两行**。照抄这个数字就成了写死的 40/46：思考段落一长，竖线只顶到一半；
 * 一短，竖线又拖到框外。
 *
 * 正确做法是让竖线**跟随正文高度**：`IntrinsicSize.Min` 让 Row 的高度由文字决定，
 * 竖线再用 `fillMaxHeight()` 撑满。这样推理多长竖线就多长。 */
@Composable
private fun ReasoningBlock(reasoning: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 设计稿的 R Line：2dp 宽、圆角 1、高度自适应
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(VColors.line, RoundedCornerShape(1.dp)),
        )
        VText(reasoning, AiTypo.reasoning, color = VColors.ink3)
    }
}

/**
 * 工具卡。
 *
 * **把调用参数显示出来是刻意的**：用户能看到 AI 把「明天上午」理解成了什么，
 * 理解错了一眼就能发现。这是这套界面最重要的可验证性设计。
 *
 * ## 两块结构（设计稿 `jTU7u`）
 *
 * ```
 * Tool Card    350x54  $surface r16 pad14   ← 只有头部，14+26+14 = 54
 * Tool Detail  350x62  左侧 2dp 竖线 + 两行文字   ← 它的**兄弟节点**，不是卡内内容
 * ```
 *
 * 一开始我把详情画在卡片**里面**，那是错的：卡片高度会是 54+62，
 * 和设计稿的两个独立块对不上，而且收起时"卡片缩到 54"这个动作也就没了。
 *
 * ## 正文区：**自动**展开/收起 ＋ 完成后**可手动收起**（用户口径，两次）
 *
 * 第一次（自动那半边）：
 *
 * > 「不需要能够点开，执行完成自动收起，很多时候都不需要展开，
 * > 只有提问、写入需要展开。读取……结束以后下方思考过程写工具调用摘要」
 *
 * | 状态 | 正文区 |
 * |---|---|
 * | `RUNNING` | `Tool Detail`（调用参数已经有了，先显示它） |
 * | `PENDING`（提问/写入） | **展开**：提问卡 / 变更预览卡 |
 * | `DONE` / `FAILED` | 收起成 `Tool Detail`（调用参数 + 返回结果） |
 *
 * 第二次（用户 2026-10-03 的 bug 报告）：
 *
 * > 「**工具调用界面没有收起状态**」
 *
 * ## 这两条不矛盾，但第一版把第二条做漏了
 *
 * 「自动收起」说的是**从展开态回到摘要**（`PENDING` → `DONE` 那一下）。
 * 而用户要的「收起状态」是**摘要本身也能收掉、只剩标题那一行** ——
 *
 * ```
 * 全部收起：[图标] 查看日程与待办              ✓ 已完成
 * 展开摘要：[图标] 查看日程与待办              ✓ 已完成
 *           │ 调用参数：2026-10-04 至 2026-10-04
 *           │ 返回结果：3 项有时刻的安排
 * ```
 *
 * 一轮助手回复里可能夹着五六张工具卡，每张都占三行的话正文会被推得很远。
 * 用户要的是"我需要看的时候再展开"。
 *
 * ## 谁可以点
 *
 * · `DONE` / `FAILED` —— **可以点**（收起到只剩标题 / 展开摘要）
 * · `PENDING` —— **不给收**：提问卡与变更预览卡是**等用户操作**的，
 *   收起来等于把唯一入口藏了
 * · `RUNNING` —— 不给收（还在跑，摘要下一秒就要变成结果）
 *
 * @param pending 待处理时的预览/问题与回调。
 */
@Composable
private fun ToolCallCard(
    record: ToolCallRecord,
    pending: PendingActions = PendingActions(),
) {
    val asks = pending.questions[record.id]
    val changeRecords = pending.previews[record.id]

    /*
     * 用户手动收起的状态（**每条卡片自己记**）。
     *
     * ⚠️ key 用 `record.id`：卡片在列表里复用时不能带着上一条的收起状态。
     * 而且**要跟着 id 重置** —— 否则"上一轮我收起过"会传染给新卡片。
     */
    var manuallyCollapsed by remember(record.id) { mutableStateOf(false) }

    /*
     * 只有**已完成/失败**的卡才允许收起 —— 见函数注释的表。
     *
     * 状态从 PENDING 翻到 DONE 时，`manuallyCollapsed` 保持原值（false），
     * 于是它会按"自动收起成摘要"呈现，不会突然整条消失。
     */
    val collapsible = record.status == ToolCallRecord.Status.DONE ||
        record.status == ToolCallRecord.Status.FAILED

    /*
     * 待处理但预览还没算出来时，先**收起**（只显示头部的转圈/待确认）。
     * 一上来就撑开一个空白大块比等一下更难看。
     */
    val body = when {
        record.status != ToolCallRecord.Status.PENDING -> ToolBody.Summary
        asks != null && asks.isNotEmpty() -> ToolBody.Ask
        changeRecords != null && changeRecords.isNotEmpty() -> ToolBody.Preview
        else -> ToolBody.None
    }

    // 用户手动收起了 → 连摘要都不画，只剩标题那一行
    val shownBody = if (manuallyCollapsed) ToolBody.None else body

    if (body == ToolBody.None && record.status == ToolCallRecord.Status.PENDING) {
        // 只算一次；算完 body 会变成 Ask / Preview
        LaunchedEffect(record.id) { pending.onEnsure(record.id) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        CardHeader(
            record = record,
            // 可收起的卡才画箭头；不可收起的（提问/进行中）画箭头是误导
            collapsed = manuallyCollapsed,
            collapsible = collapsible,
            onToggle = { manuallyCollapsed = !manuallyCollapsed },
        )

        AnimatedContent(
            targetState = shownBody,
            transitionSpec = {
                /*
                 * 展开用 Expressive（快起慢收），收起用 Accelerate（缓起快收）——
                 * 和项目其余动效同一套曲线。
                 *
                 * 尺寸用 SizeTransform 而不是 expand/shrinkVertically：
                 * 这里的高度差可能接近一千像素，裁剪式收缩会把内容切得很难看。
                 */
                (fadeIn(tween(VMotion.RevealMillis, easing = VMotion.Expressive)) +
                    scaleIn(
                        initialScale = 0.98f,
                        animationSpec = tween(VMotion.RevealMillis, easing = VMotion.Expressive),
                    ))
                    .togetherWith(
                        fadeOut(tween(VMotion.ExitMillis, easing = VMotion.Accelerate)) +
                            scaleOut(
                                targetScale = 0.98f,
                                animationSpec = tween(VMotion.ExitMillis, easing = VMotion.Accelerate),
                            ),
                    ) using SizeTransform(clip = true)
            },
            label = "tool-body",
        ) { kind ->
            when (kind) {
                ToolBody.Summary -> ToolDetailBlock(record)

                ToolBody.Ask -> if (asks != null) {
                    AskCard(
                        questions = asks,
                        answers = pending.answers[record.id].orEmpty(),
                        onAnswer = { questionId, answer ->
                            pending.onAnswer(record.id, questionId, answer)
                        },
                        onSubmit = { pending.onSubmitAsk(record.id) },
                    )
                }

                ToolBody.Preview -> if (changeRecords != null) {
                    ChangePreviewCard(
                        records = changeRecords,
                        accepted = pending.acceptedFor(record.id),
                        onToggle = { recordId -> pending.onToggle(record.id, recordId) },
                        onReject = { pending.onReject(listOf(record.id)) },
                        onConfirm = { pending.onApply(listOf(record.id)) },
                    )
                }

                ToolBody.None -> Unit
            }
        }
    }
}

/** 正文区显示什么。由 [ToolCallRecord.status] 决定，用户点不了。 */
private enum class ToolBody { None, Summary, Ask, Preview }

/**
 * 卡片头部。设计稿 `Tool Card 350x54 $surface r16 pad14`，内部 `Tool Head 322x26`。
 *
 * 徽标 26x26、名称 `fs14/500` —— 和提问卡、变更预览卡的头部**完全同构**
 *（这就是"所有工具整合成一个小框"的含义）。
 *
 * ## 整行可点（用户 2026-10-03「工具调用界面没有收起状态」）
 *
 * 只有完成了的卡能收。热区做成**整行**而不是只点那个 16dp 的箭头 ——
 * 手指点不准那么小的目标（与 `ChangePreviewCard` 的勾选框同一个取舍）。
 *
 * 不可收起时（提问中 / 进行中）不画箭头、也不接点击 —— 箭头是"这里能点"
 * 的暗示，点不动的时候画它是误导（与「已生成」不画箭头同一条纪律）。
 */
@Composable
private fun CardHeader(
    record: ToolCallRecord,
    /** 现在是收起的吗（只影响箭头朝向）。 */
    collapsed: Boolean = false,
    /** 这张卡能不能收起。false 时不画箭头、不接点击。 */
    collapsible: Boolean = false,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(VColors.surface)
            .border(1.dp, VColors.line, RoundedCornerShape(16.dp))
            .then(if (collapsible) Modifier.vPressable(onClick = onToggle) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(VColors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Sparkles, null, Modifier.size(14.dp), tint = VColors.accent)
            }
            VText(
                record.displayName,
                AiTypo.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = VColors.ink,
            )
        }
        StatusPill(record.status)
        if (collapsible) {
            Spacer(Modifier.width(6.dp))
            Icon(
                if (collapsed) Lucide.ChevronRight else Lucide.ChevronDown,
                contentDescription = if (collapsed) "展开调用详情" else "收起调用详情",
                tint = VColors.ink3,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 卡片下方那一块。设计稿 `Tool Detail 350x62`：左侧 2dp 竖线 + 两行文字，
 * 与推理块（`Reasoning`）**完全同构**。
 *
 * 竖线用 `IntrinsicSize.Min` + `fillMaxHeight()` 跟随文字高度 ——
 * 和推理块同一个做法（那边写死过 46 收到过教训）。
 */
@Composable
private fun ToolDetailBlock(record: ToolCallRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(VColors.line, RoundedCornerShape(1.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VText("调用参数：${record.argumentsSummary}", AiTypo.chip, color = VColors.ink3)
            // 还没有结果时**不画那一行** —— 画一行空的会让人以为结果丢了
            record.resultSummary?.let {
                VText("返回结果：$it", AiTypo.chip, color = VColors.ink3)
            }
        }
    }
}

@Composable
private fun StatusPill(status: ToolCallRecord.Status) {
    val (text, color) = when (status) {
        ToolCallRecord.Status.RUNNING -> "" to VColors.ink3
        ToolCallRecord.Status.DONE -> "已完成" to VColors.accent
        ToolCallRecord.Status.PENDING -> "待确认" to VColors.amber
        ToolCallRecord.Status.FAILED -> "失败" to VColors.rose
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (status) {
            // 转圈：设计稿 `jTU7u` 的 `Tool Status` 那一格，执行中换成转圈
            ToolCallRecord.Status.RUNNING -> VSpinner(size = 14.dp, color = VColors.ink3)
            ToolCallRecord.Status.DONE -> Icon(
                Lucide.Check, null, Modifier.size(14.dp), tint = VColors.accent,
            )
            ToolCallRecord.Status.PENDING -> Icon(
                Lucide.Clock, null, Modifier.size(14.dp), tint = VColors.amber,
            )
            ToolCallRecord.Status.FAILED -> Icon(
                Lucide.X, null, Modifier.size(14.dp), tint = VColors.rose,
            )
        }
        if (text.isNotEmpty()) {
            VText(
                text,
                VTypo.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = color,
            )
        }
    }
}

// ---------------------------------------------------------------- 操作行
/**
 * 消息操作行 —— 照设计稿实测。
 *
 * ```
 * Actions    高 18，间距 22，垂直居中
 *   copy 18  pencil 18  git-branch 18  rotate-cw 18
 *   Spacer  fill_container，高 1（把切换器顶到右边）
 *   Round Switcher  间距 6：‹ 16  2 / 2  › 16
 * ```
 *
 * ## 五个图标为什么只做四个
 *
 * 设计稿还有 `share-2`，**不做** —— 笔记 App 里"分享到哪"没有明确语义，
 * 做出来就是一个点着没反应的按钮（项目纪律：不留假按钮）。
 *
 * ## 轮次切换器
 *
 * 只在**同一问确实有多份回答**时才出现（`roundSwitch != null`）。
 * 只有一轮时画出来会显示「1 / 1」—— 一个永远点不动的控件。 */
@Composable
private fun ActionRow(onAction: (ChatAction) -> Unit, roundSwitch: RoundSwitch?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ActionIcon(Lucide.Copy, "复制") { onAction(ChatAction.Copy) }
        ActionIcon(Lucide.Pencil, "编辑提问") { onAction(ChatAction.Edit) }
        ActionIcon(Lucide.GitBranch, "从此处分叉出新对话") { onAction(ChatAction.Branch) }
        ActionIcon(Lucide.RefreshCw, "重新生成") { onAction(ChatAction.Regenerate) }

        // Spacer 高 1：设计稿就是一个 1px 的隐形占位，只为把切换器推到右侧
        Spacer(Modifier.weight(1f).height(1.dp))

        roundSwitch?.let { RoundSwitcher(it) }
    }
}

/**
 * 轮次切换器：`‹ 2 / 2 ›`。
 *
 * 两端是 16dp 的 chevron（`ink-2`），中间是等宽数字（`roundText`）。
 * 到头的方向变淡且不可点 —— 否则用户会一直点一个没有反应的方向。 */
@Composable
private fun RoundSwitcher(switch: RoundSwitch) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RoundChevron(
            icon = Lucide.ChevronLeft,
            description = "上一轮回答",
            enabled = switch.index > 0,
            onClick = switch.onPrev,
        )
        VText(
            "${switch.index + 1} / ${switch.total}",
            AiTypo.roundText,
            color = VColors.ink3,
        )
        RoundChevron(
            icon = Lucide.ChevronRight,
            description = "下一轮回答",
            enabled = switch.index < switch.total - 1,
            onClick = switch.onNext,
        )
    }
}

@Composable
private fun RoundChevron(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = if (enabled) VColors.ink2 else VColors.line,
        modifier = Modifier
            .size(16.dp)
            .vPressable(enabled = enabled, scaleDown = 0.85f, onClick = onClick),
    )
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = VColors.ink3,
        modifier = Modifier
            .size(18.dp)
            .vPressable(scaleDown = 0.85f, onClick = onClick),
    )
}

/** 「已思考（用时 N 秒）」。只在**确实有思考内容**时用（见 [ChatTurnRow] 的注释）。 */
private fun thoughtLabel(millis: Long?): String = when {
    millis != null && millis > 0 -> "已思考（用时 ${formatSeconds(millis)}）"
    else -> "已思考"
}

private fun formatSeconds(millis: Long): String {
    val seconds = millis / 1000.0
    return if (seconds < 10) String.format("%.1f 秒", seconds) else "${millis / 1000} 秒"
}

/**
 * 千位分隔（`12345` → `12,345`）。
 *
 * 用户指定的格式是 **`a,bcd tokens`** —— 带千位分隔符。
 *
 * ⚠️ 不用 `String.format("%,d", …)`：那个走**默认 Locale**，
 * 某些地区用空格或点做千位分隔（`12 345` / `12.345`），而用户要的就是逗号。
 * 手写六行比引一个 locale 依赖划算，也不会随设备语言变化。
 *
 * ⚠️ 与 `Usage.label` 里那份是**同一套规则的两份实现** ——
 * 一份在领域层（`Usage`），一份在界面层。看起来该合并，
 * 但领域层不该为了界面格式去依赖 Compose，反之亦然。
 * 两处都只有几行，且各自有注释指回对方，真要改记得**一起改**。
 */
private fun Int.withThousandsSeparator(): String {
    val digits = toString()
    if (digits.length <= 3) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

/**
 * 轮次切换器的数据。
 *
 * @param index 0-based 当前轮次
 * @param total 这一问总共有几轮回答。 */
data class RoundSwitch(
    val index: Int,
    val total: Int,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
)

/**
 * 消息可执行的操作。
 *
 * 与设计稿的对应关系：`copy` / `pencil` / `git-branch` / `rotate-cw` 四个。
 * 设计稿里还有 `share-2`，本版不做（理由见 [ActionRow] 注释）。 */
enum class ChatAction { Copy, Edit, Branch, Regenerate }
