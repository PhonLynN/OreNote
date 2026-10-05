package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.ai.PromptTarget
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * AI 设置页（**最小可用版**）。
 *
 * ## 与设计稿的关系
 *
 * 设计稿 `AI · 设置` 有 7 段：模型配置 / 生成参数 / 系统提示词 / 能力 /
 * 记忆 / 个人档案。**本版只做前两段**，理由写在第 3 段的位置上（见下）。
 *
 * 为什么不做其余五段：它们对应的功能（提示词分组、思考深度、流式开关、
 * 记忆生成、画像生成）**都还没实现**。做出来会是一堆拨了不生效的开关，
 * 而项目纪律是「不留假按钮」。等那些功能做出来再补对应的设置项。
 *
 * ## 尺寸
 *
 * 全部来自设计稿实测，见 `verification/plan030/ai_spec.py` 的 `SETTINGS`：
 * ```
 * Content        pad 水平 20 / 上 10，gap 20
 * Header         高 40；返回 40x40 圆角 20；保存 圆角 12 pad 8x14
 * Section Head   高 22
 * Card           圆角 16
 *   Entry        高 64，pad 13x14，gap 12；Badge 34x34 圆角 11；chevron 16
 *   Divider      高 1，水平 pad 14
 *   Row          高 58 / 54 / 56
 * ```
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    onOpenChatModel: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            /*
             * ⚠️ 必须用 safeDrawing，不能只用 statusBarsPadding。
             *
             * 用户报「提示词编辑框下方没有预留出滚动空间，输入框下半部分会被键盘遮挡」：
             * 这一页原来只避让了状态栏，键盘弹出时整页高度不减，而它是
             * `fillMaxSize().verticalScroll(...)` —— 可滚但可视区没变，
             * 于是 200dp 高的编辑框下半部分正好被键盘盖住，滚也滚不出来。
             *
             * safeDrawing 一次给到「上=状态栏，下=max(导航栏, 键盘)」，
             * 可视区随键盘收缩，编辑框就能顺着滚上来。
             */
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsHeader(onBack)

            SectionHead("模型配置")

            SettingsCard {
                EntryRow(
                    badge = Lucide.MessageCircle,
                    badgeBg = VColors.accentSoft,
                    badgeTint = VColors.accent,
                    title = "对话模型",
                    value = state.modelLine,
                    onClick = onOpenChatModel,
                )
                InsetDivider()
                EntryRow(
                    badge = Lucide.Search,
                    badgeBg = VColors.lilacSoft,
                    badgeTint = VColors.lilac,
                    title = "向量模型",
                    value = state.embeddingLine,
                    // 向量模型页（设计稿 iI6i8）第一批不做 —— 检索层还没实现。
                    // 这里不给 onClick，避免点了没反应。
                    onClick = null,
                )
            }

            state.testResult?.let { result ->
                val ok = result.startsWith("连接成功")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (ok) VColors.accentSoft else VColors.roseSoft,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    VText(result, VTypo.caption, color = if (ok) VColors.accent else VColors.roseDeep)
                }
            }

            SectionHead("生成参数")

            SettingsCard {
                PlainRow("温度", state.temperature) { viewModel.edit(Field.Temperature) }
                InsetDivider()
                PlainRow("最大长度", state.maxTokens) { viewModel.edit(Field.MaxTokens) }
                InsetDivider()
                PlainRow("Top P", state.topP) { viewModel.edit(Field.TopP) }
                /*
                 * ⚠️ **「重复惩罚」已删除**（2026-10-04）。
                 *
                 * 不是"暂时不做"，是**那个参数已经被官方废弃、传了也不生效**。
                 * 官方文档原文（`/api/create-chat-completion`）：
                 *
                 * > frequency_penalty **deprecated**
                 * > This parameter is no longer supported.
                 * > It will not take effect if you pass it to the API.
                 *
                 * 留着它就是一个**拨了不生效的开关** —— 用户会以为调了它
                 * 输出就变好了，实际上什么都没发生。项目纪律是「不留假按钮」。
                 *
                 * 同类情况：`presence_penalty` 也一起废弃了，但我们本来就
                 * 没有暴露它（`ChatProtocol` 里没有这个字段），所以无需处理。
                 *
                 * ⚠️ `ChatProtocol.ChatRequest.frequencyPenalty` 字段**保留着** ——
                 * 它默认 null（不发这个键），对别的兼容厂商（OpenAI / Qwen）
                 * 仍然有效。删界面行就够了，不必动协议层。
                 */
            }

            SectionHead("能力")

            SettingsCard {
                /*
                 * 「深度思考」是**真开关** —— 它真的会发到 API。
                 *
                 * 这里是**默认值**；输入框上那颗 chip 是**逐次对话**的开关，
                 * 两者是不同层级（与设计稿一致：设置页管默认，输入框管这一次）。
                 */
                AbilityRow(
                    title = "深度思考",
                    subtitle = "回答前先进行推理",
                    checked = state.deepThinking,
                    onCheckedChange = viewModel::setDeepThinking,
                )
                InsetDivider()
                ThinkingDepthRow(
                    depth = state.thinkingDepth,
                    onSelect = viewModel::setThinkingDepth,
                )
                InsetDivider()
                AbilityRow(
                    title = "流式输出",
                    subtitle = "逐字显示回复内容",
                    checked = state.streamOutput,
                    onCheckedChange = viewModel::setStreamOutput,
                )
                InsetDivider()
                /*
                 * token 消耗显示（用户 2026-10-04）。
                 *
                 * 副标题写清"显示在哪儿"，否则用户拨开开关后不知道该去哪儿找 ——
                 * 它是画在**每条回复的状态行右端**（「已生成」那一行的最右边）。
                 */
                AbilityRow(
                    title = "显示 token 消耗",
                    subtitle = "在每轮回复的状态行右端显示本次用量",
                    checked = state.showTokens,
                    onCheckedChange = viewModel::setShowTokens,
                )
                /*
                 * 「联网搜索」**不做**：那需要一整套搜索能力（检索、抓取、引用），
                 * 而它还没实现。做出来就是一个拨了不生效的开关 ——
                 * 项目纪律是「不留假按钮」。
                 */
            }

            /*
             * 提示词：**聊天与助手各一段**（用户口径）。
             *
             * > 「agent 的提示词和聊天的提示词不能放在一起，两个有很大区别」
             *
             * 两段的界面完全一样（同一组控件），只是指向不同的 [PromptTarget] ——
             * 所以这里抽成一个局部函数调两次，而不是复制两份 UI 代码。
             * 复制的话，以后改一处就会漏另一处。
             *
             * 单选一组的语义：切换即替换当前生效的那一组，不是叠加。
             *（设计稿那句「可叠加多组」与它画的 Radio 控件矛盾，已按用户澄清修正。）
             */
            PromptTarget.entries.forEach { target ->
                SectionHead(
                    title = "${target.label}提示词",
                    note = if (target == PromptTarget.CHAT) "纯对话，不带工具" else "能调工具，另加工具纪律",
                )
                PromptSection(
                    groups = state.promptGroupsFor(target),
                    selectedId = state.promptSet(target).activeId,
                    onSelect = { id -> viewModel.selectPrompt(target, id) },
                    onEdit = { id, text -> viewModel.editPromptText(target, id, text) },
                    onDelete = { id -> viewModel.deletePrompt(target, id) },
                    onAdd = { viewModel.addPrompt(target) },
                )
            }

            /*
             * 明确说明「其余设置为什么还没有」。
             *
             * 不写这句的话，用户会以为这里是残缺的。写清楚是"功能还没做"，
             * 而不是"设置项漏了"。
             */
            VText(
                "记忆与个人档案相关设置，会在记忆功能实现后补上。",
                AiTypo.settingValue,
                color = VColors.ink3,
                modifier = Modifier.padding(bottom = 28.dp),
            )
        }
    }

    state.editing?.let { field ->
        AiFieldDialog(
            field = field,
            initial = viewModel.currentValueOf(field),
            onConfirm = viewModel::commit,
            onDismiss = viewModel::cancelEdit,
        )
    }
}

/** Header：高 40；返回 40x40 圆角 20；**没有保存按钮**。 */
@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(VColors.surface)
                .vPressable(scaleDown = 0.9f, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ChevronLeft, contentDescription = "返回", tint = VColors.ink, modifier = Modifier.size(20.dp))
        }
        VText("AI 设置", VTypo.hero, color = VColors.ink)
        Spacer(Modifier.weight(1f))
        /*
         * 设计稿这里有一个「保存」按钮。
         *
         * **本版不做**：字段是「改完即存」的（每次 commit 就落库），
         * 再加一个保存按钮会是第二个真相来源 —— 用户改了字段后不点保存、
         * 以为没生效，或者点了保存却什么都没变。功能上冗余，语义上误导。
         */
    }
}

@Composable
private fun SectionHead(title: String, note: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(title, AiTypo.section, color = VColors.ink)
        // 右侧说明（设计稿的 Section Note，如「1 个」「单选一组」）
        note?.let {
            Spacer(Modifier.weight(1f))
            VText(it, AiTypo.settingValue, color = VColors.ink3)
        }
    }
}

/** 卡片：圆角 16、底色 surface。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(VColors.surface),
    ) {
        content()
    }
}

/**
 * 带徽章的行（设计稿的 Entry：高 64，pad 13x14，gap 12）。
 *
 * 徽章 34x34 圆角 11 —— 与 `BadgeIcon` 那种小徽章不同，
 * 设置页的徽章是设计稿里明确的大尺寸。
 */
@Composable
private fun EntryRow(
    badge: androidx.compose.ui.graphics.vector.ImageVector,
    badgeBg: androidx.compose.ui.graphics.Color,
    badgeTint: androidx.compose.ui.graphics.Color,
    title: String,
    value: String,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(if (onClick != null) Modifier.vPressable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(badgeBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(badge, contentDescription = null, tint = badgeTint, modifier = Modifier.size(17.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            VText(title, VTypo.bodyMed, color = VColors.ink)
            VText(value, VTypo.caption, color = VColors.ink3, maxLines = 1)
        }

        Icon(Lucide.ChevronRight, contentDescription = null, tint = VColors.ink3, modifier = Modifier.size(16.dp))
    }
}

/** 无徽章的行（高 58）：左标签、右值。 */
@Composable
private fun PlainRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .vPressable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(label, VTypo.body, color = VColors.ink2, modifier = Modifier.weight(1f))
        VText(value, VTypo.caption, color = VColors.ink3, maxLines = 1)
    }
}

/** 内缩分隔线：高 1，水平 pad 14（线宽 = 卡宽 - 28）。 */
@Composable
private fun InsetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(VColors.line),
    )
}

/**
 * 能力开关行：高 64，左标题+说明、右开关。
 *
 * 这些是**真开关** —— 拨了会真的改变请求（见 `SendChatMessage`）。
 * 不生效的开关不做（那违反「不留假按钮」）。
 */
@Composable
private fun AbilityRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, AiTypo.settingLabel, color = VColors.ink)
            VText(subtitle, AiTypo.settingValue, color = VColors.ink3)
        }
        AiSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 思考深度：低 / 中 / 高（设计稿的三段）。 */
@Composable
private fun ThinkingDepthRow(
    depth: com.phonlynn.oreplan.domain.ai.ThinkingDepth,
    onSelect: (com.phonlynn.oreplan.domain.ai.ThinkingDepth) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText("思考深度", AiTypo.settingLabel, color = VColors.ink2, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.phonlynn.oreplan.domain.ai.ThinkingDepth.entries.forEach { option ->
                val selected = option == depth
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) VColors.accentSoft else VColors.bg)
                        .vPressable { onSelect(option) }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                ) {
                    VText(
                        option.label,
                        if (selected) AiTypo.segSelected else AiTypo.segNormal,
                        color = if (selected) VColors.accent else VColors.ink3,
                    )
                }
            }
        }
    }
}

/** 提示词组的启用开关（多组可叠加）。 */
@Composable
private fun PromptGroupRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .vPressable(onClick = onToggle)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(name, AiTypo.settingLabel, color = VColors.ink, modifier = Modifier.weight(1f))
        AiSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 开关：46x28，圆角 14，pad 3。 */
@Composable
private fun AiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) VColors.accent else VColors.surface2)
            .vPressable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(VColors.surface),
        )
    }
}

/** 测试连接按钮。 */
@Composable
private fun TestButton(testing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled && !testing) VColors.accentSoft else VColors.surface2)
            .vPressable(enabled = enabled && !testing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            if (testing) "正在测试…" else "测试连接",
            VTypo.bodyMed,
            color = if (enabled && !testing) VColors.accent else VColors.ink3,
        )
    }
}
