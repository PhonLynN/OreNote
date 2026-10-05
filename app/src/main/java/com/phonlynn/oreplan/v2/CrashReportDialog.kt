package com.phonlynn.oreplan.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.platform.crash.CrashLog
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import kotlinx.coroutines.launch

/**
 * 启动时若发现上次是崩溃退出的，把堆栈显示出来并**自动复制到剪贴板**。
 *
 * ## 为什么要有这个
 *
 * 我改了三版都没修掉那个闪退，而**从开发这边拿不到任何日志**
 *（手机不在我这台机器上，`adb devices` 是空的）。与其继续猜，
 * 不如让 App 自己把证据交出来：崩溃 → 写文件（[CrashLog]）→ 下次启动弹这个 →
 * 用户粘一下发给我。
 *
 * 不做自动上传：日志里可能有对话片段，而用户明确说过不接受无谓的外发。
 * 剪贴板是最克制的方式 —— **数据只流向用户自己**。
 *
 * ## 只在崩溃后出现
 *
 * 没有崩溃记录时这个 Composable 什么都不画，正常用户永远看不到。
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // 只在首次组合时读一次文件：读出来之后立刻清掉，免得每次启动都弹
    LaunchedEffect(Unit) {
        val text = CrashLog.read(context)
        if (text != null) {
            CrashLog.clear(context)
            report = text
        }
    }

    val current = report ?: return

    // 一出现就复制好 —— 用户只需要粘，不用再点一次
    LaunchedEffect(current) {
        clipboard.setClipEntry(
            ClipEntry(android.content.ClipData.newPlainText("崩溃日志", current)),
        )
        copied = true
        // 弹窗内容很长时用户不一定看得出复制成功，这里顺带说一句
    }

    VDialog(onDismissRequest = { report = null }, maxWidth = 360.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            VText("上次崩溃了", VTypo.dialogTitle, color = VColors.ink)

            Box(Modifier.heightIn(min = 8.dp))

            VText(
                if (copied) "崩溃日志已复制到剪贴板 —— 发给开发者即可" else "正在准备崩溃日志…",
                VTypo.caption,
                color = VColors.accent,
            )

            Box(Modifier.heightIn(min = 12.dp))

            // 只显示前若干行：完整堆栈在剪贴板里，这里只为"看得见有东西"
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VColors.bg)
                    .padding(10.dp),
            ) {
                Text(
                    text = current.take(1200),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    color = VColors.ink2,
                    style = VTypo.caption.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    ),
                )
            }

            Box(Modifier.heightIn(min = 18.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(VColors.surface2)
                        .vPressable(scaleDown = 0.96f) { close { report = null } }
                        .padding(vertical = 14.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    VText("关闭", VTypo.buttonBold, color = VColors.ink)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(VColors.accent)
                        .vPressable(scaleDown = 0.96f) {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(android.content.ClipData.newPlainText("崩溃日志", current)),
                                )
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    VText("再复制一次", VTypo.buttonBold, color = Color.White)
                }
            }
        }
    }
}
