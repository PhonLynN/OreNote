package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 对话模型配置页。
 *
 * 结构照设计稿 `AI · 对话模型配置`：
 * ```
 * Header   [返回] 对话模型 ………………… [保存]      高 60，水平 pad 20
 * Content  ── 基本信息 ──                        高 22
 *          卡片（圆角 16）
 *            供应商类型    OpenAI 兼容格式          行高 54
 *            ──────────
 *            供应商名称    deepseek
 *            ──────────
 *            Base URL      https://api.deepseek.com
 *            ──────────
 *            API Key       ••••••     [测试连接]
 *            ──────────
 *            启用此配置                    [开关]   行高 64
 *          ── 已启用模型 ──  1 个
 *          卡片   ✓ deepseek-flash            [移除]
 *          ── 可用模型 ──  2 个
 *          卡片   ＋ deepseek-v4-pro
 *                 ＋ DeepSeek V4 Flash Vision Exp
 *                 [模型 ID] [显示名称（可选）] [＋]
 * ```
 *
 * 尺寸见 `verification/plan030/ai_spec.py` 与设计稿实测。
 *
 * ## 与「AI 设置」页的分工
 *
 * `AI · 设置` 里的「对话模型 / 向量模型」两行是**入口**，
 * 点进来才是这个配置页。我第一版把两者混在一起做成了三个输入框，
 * 那既不符合设计稿，也丢了「多模型管理」这层结构。
 */
@Composable
fun ChatModelConfigScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Header：高 60，水平 pad 20
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
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
                        Icon(Lucide.ChevronLeft, contentDescription = "返回", tint = VColors.ink, modifier = Modifier.size(18.dp))
                    }
                    VText("对话模型", VTypo.hero, color = VColors.ink)
                }

                Spacer(Modifier.weight(1f))

                /*
                 * 「保存」按钮。
                 *
                 * 设计稿有它。但**本版字段是改完即存**，再加保存会变成第二个真相来源：
                 * 用户改了字段却没点保存 → 以为没生效；或者点了保存但什么都没变。
                 *
                 * 这一条与设计稿的差异是刻意的，属于「不改不行」之外的实现选择 ——
                 * 如果你要严格保留这个按钮，告诉我，我改成显式保存语义
                 *（即改动先进草稿，点保存才落库）。
                 */
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(VColors.accentSoft)
                        .vPressable(onClick = onBack)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    VText("完成", VTypo.caption12, color = VColors.accent)
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SectionLabel("基本信息")

                ConfigCard {
                    ValueRow("供应商类型", "OpenAI 兼容格式")
                    InsetLine()
                    ValueRow("供应商名称", state.providerName)
                    InsetLine()
                    ValueRow("Base URL", state.baseUrl.ifBlank { "未填写" }) { viewModel.edit(Field.BaseUrl) }
                    InsetLine()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VText("API Key", VTypo.body, color = VColors.ink2, modifier = Modifier.weight(1f))
                        VText(state.maskedKey, VTypo.caption, color = VColors.ink3)
                        Spacer(Modifier.size(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(VColors.accentSoft)
                                .vPressable(
                                    enabled = state.canTest && !state.testing,
                                    onClick = viewModel::testConnection,
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            VText(
                                if (state.testing) "测试中…" else "测试连接",
                                VTypo.caption,
                                color = if (state.canTest) VColors.accent else VColors.ink3,
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .vPressable(onClick = { viewModel.edit(Field.ApiKey) })
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            VText("修改", VTypo.caption, color = VColors.accent)
                        }
                    }
                    InsetLine()
                    ToggleRow(
                        title = "启用此配置",
                        subtitle = "关闭后该配置的模型不会在选择列表中出现",
                        checked = state.enabled,
                        onCheckedChange = viewModel::setEnabled,
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

                SectionLabel("已启用模型", note = "1 个")

                ConfigCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Lucide.CircleCheck, contentDescription = null, tint = VColors.accent, modifier = Modifier.size(18.dp))
                        VText(
                            state.model.ifBlank { "未选择" },
                            VTypo.caption12,
                            color = VColors.ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                SectionLabel("可用模型", note = AVAILABLE_MODELS.size.toString() + " 个")

                ConfigCard {
                    AVAILABLE_MODELS.forEachIndexed { index, model ->
                        if (index > 0) InsetLine()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .vPressable { viewModel.commit(Field.Model, model) }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Lucide.Plus, contentDescription = null, tint = VColors.ink2, modifier = Modifier.size(16.dp))
                            VText(model, VTypo.caption12, color = VColors.ink, modifier = Modifier.weight(1f))
                        }
                    }
                    InsetLine()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(35.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(VColors.bg)
                                .vPressable { viewModel.edit(Field.Model) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            VText(state.model.ifBlank { "模型 ID" }, VTypo.caption, color = VColors.ink3)
                        }
                        Spacer(Modifier.size(2.dp))
                        VText("手填上方字段", VTypo.caption, color = VColors.ink3)
                    }
                }

                Spacer(Modifier.size(28.dp))
            }
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

/** 可选的模型清单。 */
private val AVAILABLE_MODELS = listOf(
    "deepseek-flash",
    "deepseek-v4-pro",
)

@Composable
private fun SectionLabel(title: String, note: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(title, VTypo.section, color = VColors.ink)
        note?.let {
            Spacer(Modifier.weight(1f))
            VText(it, VTypo.caption, color = VColors.ink3)
        }
    }
}

@Composable
private fun ConfigCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(VColors.surface),
    ) {
        content()
    }
}

/** 只读的值行（高 54）。 */
@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .then(if (onClick != null) Modifier.vPressable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(label, VTypo.body, color = VColors.ink2, modifier = Modifier.weight(1f))
        VText(value, VTypo.caption, color = VColors.ink3, maxLines = 1)
    }
}

/** 开关行（高 64）。 */
@Composable
private fun ToggleRow(
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
            VText(title, VTypo.body, color = VColors.ink)
            VText(subtitle, VTypo.caption, color = VColors.ink3)
        }
        // 开关：46x28，圆角 14，pad 3
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
}

@Composable
private fun InsetLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(VColors.line),
    )
}
