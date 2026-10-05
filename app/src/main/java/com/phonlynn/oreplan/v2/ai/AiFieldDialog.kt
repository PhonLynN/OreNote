package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VRadius
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 编辑一个设置字段的对话框。
 *
 * `API Key` 用密码遮罩显示 —— 不是因为担心安全（用户明确不在意），
 * 而是**肩窥与截图**：设置页常被截图，明文 key 露出来容易被别人拿去用。
 */
@Composable
fun AiFieldDialog(
    field: Field,
    initial: String,
    onConfirm: (Field, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(field) { mutableStateOf(initial) }
    val isSecret = field == Field.ApiKey

    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(VRadius.dialog))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText(field.label, VTypo.dialogTitle, color = VColors.ink)

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (isSecret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field == Field.Temperature || field == Field.MaxTokens) {
                        KeyboardType.Decimal
                    } else {
                        KeyboardType.Text
                    },
                ),
                placeholder = { Text(placeholderOf(field), style = VTypo.caption) },
            )

            hintOf(field)?.let { VText(it, VTypo.caption, color = VColors.ink3) }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(VRadius.button))
                        .vPressable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    VText("取消", VTypo.body, color = VColors.ink3)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(VRadius.button))
                        .vPressable { onConfirm(field, text) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    VText("确定", VTypo.bodyMed, color = VColors.accent)
                }
            }
        }
    }
}

private fun placeholderOf(field: Field): String = when (field) {
    Field.BaseUrl -> "https://api.deepseek.com"
    Field.ApiKey -> "sk-…"
    Field.Model -> "deepseek-flash"
    Field.Temperature -> "0.7"
    Field.MaxTokens -> "2048"
    Field.TopP -> "0.90"
    Field.FrequencyPenalty -> "1.05"
}

/**
 * 每个字段的说明。
 *
 * 刻意写清「填什么」而不是只给标签 ——
 * base URL 与模型名是最容易填错的两处（多写 `/v1`、用了旧模型名），
 * 而填错的报错是 404，看起来像"服务挂了"。
 */
private fun hintOf(field: Field): String? = when (field) {
    Field.BaseUrl -> "不要带 /chat/completions。带了 /v1 就保留它"
    Field.ApiKey -> "在模型服务商的控制台创建"
    Field.Model -> "如 deepseek-flash；填错会返回 404"
    Field.Temperature -> "0 更确定，1 更发散"
    Field.MaxTokens -> "单次回复的最大长度"
    Field.TopP -> "采样范围；越小越保守"
    Field.FrequencyPenalty -> "越大越不容易重复用词"
}
