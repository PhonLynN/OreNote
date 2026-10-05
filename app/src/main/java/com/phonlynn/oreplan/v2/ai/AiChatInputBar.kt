package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * AI 对话的输入区。
 *
 * ## 结构（照设计稿实测）
 *
 * ```
 * Input Area   padding 上 8 / 左右 14 / 下 18，gap 8
 *   Field      圆角 22，padding 14，gap 12，底色 surface
 *     占位文案 / 输入文本
 *     Field Row   高 36，两端对齐
 *       Field Left   gap 8：[聊天|助手] + [深度思考]
 *       Field Right  gap 14：[发送]
 * ```
 *
 * ## ⚠️ 键盘处理（我第一版做错了）
 *
 * 用户反馈「开始输入后输入框的上跳幅度非常夸张，顶栏也被挤没了」。
 *
 * 根因：我把 `imePadding()` 加在了**整块内容 Column** 上，
 * 键盘弹出时整列被抬高，而 `Column` 里的消息区是 `weight(1f)` ——
 * 它被压缩到接近 0，同时把顶栏也顶出了屏幕。
 *
 * 正确做法：
 *  · `imePadding()` 只加在**输入区**（它是唯一需要避让键盘的部分）
 *  · 顶栏与输入区**固定高度**，消息区 `weight(1f)` 吸收剩余空间
 *  · 消息区自己滚动，不把压力传给外层
 */
@Composable
fun AiChatInputBar(
    placeholder: String,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    /**
     * 正在生成（用户 2026-10-03 的「停止生成按钮」）。
     *
     * true 时发送按钮变成**停止**按钮，点它调 [onStop]。
     * 位置就定在**原地**（各家 AI App 的统一做法），详见 `SendButton` 的注释。
     */
    generating: Boolean = false,
    /** 点「停止」时调。只在 [generating] 为 true 时会被用到。 */
    onStop: () -> Unit = {},
    mode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    deepThinking: Boolean,
    onDeepThinkingChange: (Boolean) -> Unit,
    /**
     * 点「添加附件」。
     *
     * 设计稿 `Hekf8` 里这个按钮（`circle-plus`）一直画着，但实现漏了 ——
     * 用户反馈「目前没有附件导入功能」。
     */
    onAttach: () -> Unit = {},
    enabled: Boolean,
    /**
     * 输入框**是否显示**。
     *
     * ## 为什么需要它（用户 2026-10-03）
     *
     * > 「如果编辑对象是对话内容（用户发送和 ai 回复的），悬浮输入框应该停留在
     * > 下方，而不是和键盘一起上浮」
     *
     * 就地编辑时真正在输入的是**消息原位那个编辑框**（`InlineEditor`），
     * 键盘是为它弹的。此时底部这个悬浮输入框再跟着键盘上浮，
     * 会正好盖住正在编辑的那一条 —— 屏幕上两套输入框同时有光标，观感很乱。
     *
     * 所以编辑期间**整块收起**（`AnimatedVisibility` 播完退场再卸载，
     * 不留"看不见但仍吃触摸"的窗口）。
     */
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(
            androidx.compose.animation.core.tween(VMotion.RevealMillis, easing = VMotion.Expressive),
        ) + androidx.compose.animation.slideInVertically(
            animationSpec = androidx.compose.animation.core.tween(
                VMotion.RevealMillis,
                easing = VMotion.Expressive,
            ),
            // 从下方一点点浮回来（它本来就在底部，位移要小）
            initialOffsetY = { it / 3 },
        ),
        exit = androidx.compose.animation.fadeOut(
            androidx.compose.animation.core.tween(VMotion.ExitMillis, easing = VMotion.Accelerate),
        ) + androidx.compose.animation.slideOutVertically(
            animationSpec = androidx.compose.animation.core.tween(
                VMotion.ExitMillis,
                easing = VMotion.Accelerate,
            ),
            targetOffsetY = { it / 3 },
        ),
        modifier = modifier,
    ) {
        InputBarBody(
            placeholder = placeholder,
            input = input,
            onInputChange = onInputChange,
            onSend = onSend,
            generating = generating,
            onStop = onStop,
            mode = mode,
            onModeChange = onModeChange,
            deepThinking = deepThinking,
            onDeepThinkingChange = onDeepThinkingChange,
            onAttach = onAttach,
            enabled = enabled,
        )
    }
}

/**
 * 输入区的**本体**（去掉显隐包装）。
 *
 * 拆出来是因为 [AiChatInputBar] 现在外面套了一层 `AnimatedVisibility` ——
 * 那是"什么时候显示"的问题，与"长什么样"无关。混在一个函数里，
 * 后面想改版式就得先翻过一大段动画代码。
 */
@Composable
private fun InputBarBody(
    placeholder: String,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    generating: Boolean,
    onStop: () -> Unit,
    mode: ChatMode,
    onModeChange: (ChatMode) -> Unit,
    deepThinking: Boolean,
    onDeepThinkingChange: (Boolean) -> Unit,
    onAttach: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(VColors.surface)
                .border(1.dp, VColors.line, RoundedCornerShape(22.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    /*
                     * 高度上限要**跟着行高走**。
                     *
                     * `maxLines = 5`，而输入行高从 17.6dp 提到 21.75dp
                     *（见 `AiTypo.input`）之后：
                     *
                     * ```
                     * 改前：5 × 17.6 = 88dp    < 110 ✓
                     * 改后：5 × 21.75 = 108.75dp  ≈ 110 ⚠️ 卡在边界上
                     * ```
                     *
                     * 108.75 贴着 110 —— 第 5 行刚好放得下，但一点余量都没有，
                     * 稍微换个字体或字号就会开始滚动。取 **120dp** 留出余量，
                     * 让"能写满 5 行"这件事是确定的。
                     *
                     * min 保持 22dp 不变（那是单行的基线高度，与行距无关）。
                     */
                    .heightIn(min = 22.dp, max = 120.dp),
                /*
                 * ⚠️ 这里原来**手搓**了一个 TextStyle，只抄了 fontSize 与 fontFamily：
                 *
                 * ```kotlin
                 * textStyle = TextStyle(
                 *     fontSize = AiTypo.input.fontSize,   // 只拿了字号
                 *     fontFamily = BodyFont,
                 *     color = VColors.ink,
                 * )                                        // ← lineHeight 丢在这
                 * ```
                 *
                 * 于是用户在 `AiTypo.input` 上设的行高**根本到不了输入框** ——
                 * 表现为「悬浮输入框内行距过小」，而改 token 怎么改都没反应。
                 *
                 * 现在直接复用整个 token，只覆盖颜色（token 不该管颜色，
                 * 那是使用处的语义）。这样以后再调字号/字重/行高，
                 * 输入框会**自动跟着走**，不用回来改第二处。
                 */
                textStyle = AiTypo.input.copy(color = VColors.ink),
                cursorBrush = SolidColor(VColors.accent),
                enabled = enabled,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                decorationBox = { inner ->
                    // 占位文案：设计稿里它就在 Field 的顶部
                    if (input.isEmpty()) {
                        VText(placeholder, AiTypo.input, color = VColors.ink3)
                    }
                    inner()
                },
            )

            // Field Row：高 36，两端对齐
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeToggle(mode = mode, onModeChange = onModeChange)
                    ThinkChip(checked = deepThinking, onCheckedChange = onDeepThinkingChange)
                }

                /*
                 * Field Right：gap 14，[添加附件] + [发送]。
                 *
                 * 设计稿 `Hekf8 / d6bUKU`：gap 14，`circle-plus` 24x24 fill=$ink，
                 * 发送按钮 36x36 圆形 $accent。
                 *
                 * ## ⚠️ 附件按钮要**贴着发送按钮**（用户 2026-07-04 报的定位错误）
                 *
                 * 用户原话：
                 *
                 * > 「附件添加按钮定位错误，根据设计稿，应该放在发送按钮旁边，
                 * > **在空隙中偏右**，而不是居中于空隙」
                 *
                 * 根因是**热区与图标不等宽**造成的视觉偏移：
                 * `AttachButton` 的点击热区是 40dp（可用性要求，24dp 点不准），
                 * 而图标只有 24dp。热区多出来的 16dp 全在**右侧**，
                 * 于是图标看起来离发送按钮远了一截：
                 *
                 * ```
                 * 想要：[ ⊕ ] 14 [ ↑ ]
                 *       └24┘    └36┘
                 *
                 * 实际：[ ⊕    ] 14 [ ↑ ]      ← 40 的热区把图标推到了左边
                 *       └─40──┘    └36┘
                 * ```
                 *
                 * 修法不是把热区改小（那会点不准），而是**让热区溢出方向朝左**：
                 * 热区比图标宽出来的部分全部放在左边，图标本身紧邻发送按钮。
                 * 见 [AttachButton] 里 `offset` 的用法。
                 */
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachButton(onClick = onAttach)
                    SendButton(
                    enabled = input.isNotBlank() && enabled,
                    generating = generating,
                    onClick = if (generating) onStop else onSend,
                )
                }
            }
        }
    }
}

/**
 * 聊天 / 助手 模式切换。
 *
 * 设计稿：容器圆角 14、padding 3、gap 2、底色 surface2；
 * 每段圆角 11、padding 竖直 5 / 水平 10；选中段底色 surface、字色 ink。
 *
 * ⚠️ 第一批两个模式**行为相同**（用户要求「先确保有能用的 chat」）。
 * 助手模式的工具能力在第二批接上。
 */
@Composable
private fun ModeToggle(mode: ChatMode, onModeChange: (ChatMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(VColors.surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ChatMode.entries.forEach { option ->
            val selected = option == mode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .then(if (selected) Modifier.background(VColors.surface) else Modifier)
                    .vPressable(onClick = { onModeChange(option) })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                VText(
                    option.label,
                    if (selected) AiTypo.segSelected else AiTypo.segNormal,
                    color = if (selected) VColors.ink else VColors.ink3,
                )
            }
        }
    }
}

/**
 * 深度思考开关。
 *
 * 设计稿：圆角 16、padding 竖直 7 / 水平 12、gap 6、底色 bg、字色 ink-2。
 */
@Composable
private fun ThinkChip(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (checked) VColors.accentSoft else VColors.bg)
            .vPressable(onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Lucide.Lightbulb,
            contentDescription = null,
            tint = if (checked) VColors.accent else VColors.ink2,
            modifier = Modifier.size(14.dp),
        )
        VText(
            "深度思考",
            AiTypo.chip,
            color = if (checked) VColors.accent else VColors.ink2,
        )
    }
}

/**
 * 添加附件按钮。
 *
 * ## 尺寸来源：设计稿 `Hekf8 / Field Right`
 *
 * | | 值 |
 * |---|---|
 * | 图标 | `circle-plus` 24×24，fill `$ink` |
 * | 与发送按钮的间距 | 14（父级 `gap` 给，这里不管） |
 *
 * ## ⚠️ 热区要比图标大，**但多出来的部分全放左边**
 *
 * 图标 24dp，但**点击热区做成 40dp** —— 24dp 在手机上手指点不准
 *（Material 的最小建议是 48dp，这里受输入框高度限制取 40）。
 * 图标本身仍然按设计稿画 24dp，多出来的是**透明的可点区域**。
 *
 * ## ⚠️ 为什么不能直接 `size(40.dp)`（用户 2026-07-04 报的定位错误）
 *
 * 直接给 40dp 的盒子会把图标**居中**，于是热区多出来的 16dp 平分到两侧 ——
 * 图标因此离发送按钮远了 8dp，看起来"没有贴着发送按钮"。
 * 用户的原话是「应该放在发送按钮旁边，**在空隙中偏右**，而不是居中于空隙」。
 *
 * 所以这里算清楚：
 *
 * ```
 * Box  width = 图标 24 + 左侧溢出的 16 = 40
 *  └ Icon 用 offset 推到**右边缘**  →  图标距发送按钮正好是父级的 14dp
 * ```
 *
 * **视觉位置听设计稿（14dp），热区听可用性（40dp）** ——
 * 两者不一致时，让多出来的热区朝**不挤压布局的那一侧**溢出。
 */
@Composable
private fun AttachButton(onClick: () -> Unit) {
    // 热区比图标宽出来的量，**全部放到左边**
    val iconSize = 24.dp
    val hitSize = 40.dp

    Box(
        modifier = Modifier
            .size(hitSize)
            .clip(CircleShape)
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        /*
         * `CenterEnd` 而不是 `Center` —— 图标贴着**右**边缘，
         * 富余的 16dp 全部落在左边。这一行就是这个 bug 的修复点。
         */
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Lucide.CirclePlus,
            contentDescription = "添加附件",
            tint = VColors.ink,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 发送 / 停止按钮：36dp 圆形。
 *
 * ## 两种形态（用户 2026-10-03 提的「停止生成按钮」）
 *
 * | 状态 | 外观 | 动作 |
 * |---|---|---|
 * | 空闲 | accent 底 + 向上箭头，没内容时置灰 | 发送 |
 * | **正在生成** | **ink 底 + 方块**（停止图标） | **中断生成** |
 *
 * ## 为什么复用同一个按钮
 *
 * `stopGenerating()` 早就写好了，但**没有任何调用点** ——
 * 于是"一旦开始生成就只能等它跑完"（长回复时尤其难受）。
 *
 * 位置的选择：**原地变成停止**是各家 AI App 的统一做法
 *（ChatGPT / DeepSeek / Claude 都是这样），用户不需要学新位置。
 * 另外长出一个按钮反而会挤动下面的布局。
 *
 * ## ⚠️ 停止态**不置灰**
 *
 * 空闲态没内容时置灰（设计稿的状态区分）。但生成中**永远可点** ——
 * 那正是用户最需要它的时刻，置灰等于把唯一的中断手段藏起来。
 *
 * @param generating 正在生成。true 时按钮变成「停止」。
 */
@Composable
private fun SendButton(
    enabled: Boolean,
    generating: Boolean = false,
    onClick: () -> Unit,
) {
    // 生成中：用 ink（深色）而不是 accent —— 与"发送"在视觉上区分开，
    // 避免用户以为点下去还会再发一条
    val active = generating || enabled
    val background = when {
        generating -> VColors.ink
        enabled -> VColors.accent
        else -> VColors.surface2
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(background)
            .vPressable(enabled = active, scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (generating) {
            /*
             * 停止图标：一个**实心方块**（不做成方形按钮）。
             *
             * 用 `Box` 画而不是加一个 lucide 图标 —— 方块是最简单的几何形状，
             * 加一个只为一处使用的图标资源不划算，而且尺寸要跟按钮
             * 一起调（14dp 方块在 36dp 圆里视觉重量刚好）。
             */
            Box(
                Modifier
                    .size(14.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(VColors.surface),
            )
        } else {
            Icon(
                Lucide.ArrowUp,
                contentDescription = "发送",
                tint = if (enabled) VColors.surface else VColors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
