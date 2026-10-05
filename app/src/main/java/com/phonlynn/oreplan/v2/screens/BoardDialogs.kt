package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

// ---------------------------------------------------------------- 暗号设置

/**
 * 设置「暗号」：保密卡片在主页模糊块上显示的那行字。
 *
 * 注意语义：暗号**不是解锁密码**，而是你写给自己的一句提醒
 * （例如「面试准备」「给妈妈挑礼物」）。
 * 主页直接显示它，不存在「输入暗号才显示内容」这回事。
 */
@Composable
fun SecretHintDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    VDialog(onDismissRequest = onDismiss, maxWidth = 310.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            VText("暗号设置", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(4.dp))
            VText(
                "保密卡片在主页会模糊显示，并在中央显示这行字——写给自己的一句提醒即可，它不是密码。留空则显示「已隐藏」。",
                VTypo.caption12,
                color = VColors.ink3,
            )
            Spacer(Modifier.height(14.dp))
            HintField(value = value, onChange = { value = it })
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton("取消", VColors.surface2, VColors.ink, { close(onDismiss) }, Modifier.weight(1f))
                DialogButton("确定", VColors.accent, Color.White, {
                    close { onConfirm(value.trim()) }
                }, Modifier.weight(1f))
            }
        }
    }
}

/** 单行输入框（暗号设置用）。 */
@Composable
private fun HintField(value: String, onChange: (String) -> Unit) {
    // 自动唤起键盘：这个弹窗就是要用户填暗号文案。
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(VColors.bg, RoundedCornerShape(12.dp))
            .border(1.5.dp, VColors.accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            VText("例如：面试准备", VTypo.button, color = VColors.ink3, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = VTypo.button.copy(color = VColors.ink, fontFamily = BodyFont),
            singleLine = true,
            cursorBrush = SolidColor(VColors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
}

@Composable
private fun DialogButton(text: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(46.dp)
            .background(bg, RoundedCornerShape(13.dp))
            .vPressable(scaleDown = 0.96f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(text, VTypo.buttonBold, color = fg)
    }
}

// ---------------------------------------------------------------- 自定义颜色

private val customPalette: List<String> = listOf(
    "#F7EBD7", "#F7E3E3", "#E8E5F8", "#DCEBE4", "#E9EFEA",
    "#FFF3D6", "#FFE8E8", "#E3E7FF", "#D8F0E4", "#F0E9F6",
    "#E6F4EC", "#FBEEE0", "#EAF0FA", "#F4E8E8", "#E9F0E6", "#EFEAF7",
)

/** 自定义取色弹窗：一组浅色，选中的写入 #RRGGBB。 */
@Composable
fun ColorPickerDialog(
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss, maxWidth = 310.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            VText("自定义颜色", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                customPalette.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { hex ->
                            val selected = current == hex
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(boardCardColor(hex), RoundedCornerShape(14.dp))
                                    .border(1.dp, VColors.line, RoundedCornerShape(14.dp))
                                    .vPressable(scaleDown = 0.92f) { close { onPick(hex) } },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = VColors.accent)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
