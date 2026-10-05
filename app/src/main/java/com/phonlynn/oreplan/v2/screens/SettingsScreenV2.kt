package com.phonlynn.oreplan.v2.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.VBadge
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VPageScaffold
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import com.phonlynn.oreplan.v2.components.VRow
import com.phonlynn.oreplan.v2.components.VSettingRow

/** 设置（V2）—— 设计稿 Z4CtP。 */
@Composable
fun SettingsScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: SettingsV2ViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settingsStore.settings.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val syncConfig by viewModel.syncConfig.collectAsStateWithLifecycle()

    // 入口行显示**真实状态**而不是写死的文案：用户开过同步之后，
    // 一行写着"仅本机"的标签会立刻让人怀疑同步到底有没有生效。
    val syncSubtitle = when {
        !syncConfig.isConfigured -> "未配置"
        !syncConfig.enabled -> "已关闭"
        syncConfig.lastSyncAt == null -> "已开启 · 尚未同步"
        else -> "已开启 · 上次同步 ${formatSyncAgo(syncConfig.lastSyncAt!!)}"
    }

    var dialog by remember { mutableStateOf<String?>(null) }
    var confirmOverwrite by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(feedback) {
        feedback?.let {
            Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            viewModel.consumeFeedback()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            // 恢复 = 整体替换，先落到确认弹窗
            confirmOverwrite = it.toString()
        }
    }

    VPageScaffold(title = "设置", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VSectionHead("模块设置")
            VCard {
                SettingsRow(
                    icon = Lucide.GraduationCap,
                    title = "课表设置",
                    value = "作息时间、节次安排",
                    onClick = { navigate(V2Routes.TT_SETTINGS) },
                )
                VDivider()
                SettingsRow(
                    icon = Lucide.Layers,
                    title = "白板设置",
                    value = "卡片外观、查看已归档",
                    onClick = { navigate(V2Routes.BOARD_SETTINGS) },
                )
                VDivider()
                SettingsRow(
                    icon = Lucide.Target,
                    title = "规划设置",
                    value = "目标类型、提醒",
                    onClick = { navigate(V2Routes.PLAN_SETTINGS) },
                )
                VDivider()
                SettingsRow(
                    icon = Lucide.CalendarDays,
                    title = "日程设置",
                    value = "提醒、默认时长",
                    onClick = { navigate(V2Routes.SCHEDULE_SETTINGS) },
                )
                VDivider()
                SettingsRow(
                    icon = Lucide.Archive,
                    title = "已归档的待办",
                    value = "查看与恢复归档的待办",
                    onClick = { navigate(V2Routes.TASK_ARCHIVE) },
                )
            }

            VSectionHead("通用")
            VCard {
                PlainRow("外观", "浅色") { dialog = "appearance" }
                VDivider()
                PlainRow("提醒与通知", if (settings.remindersEnabled) "已开启" else "已关闭") { dialog = "notify" }
                VDivider()
                PlainRow("数据与备份", "仅本机") { dialog = "backup" }
            }

            // 同步单独一节，不并进「通用」：
            // 它涉及的是一整块独立能力（凭据、加密密钥、云端空间），
            // 混在通用里会让「数据与备份 仅本机」与「云同步 已开启」看起来自相矛盾。
            VSectionHead("同步")
            VCard {
                SettingsRow(
                    icon = Lucide.Cloud,
                    title = "云同步",
                    value = syncSubtitle,
                    onClick = { navigate(V2Routes.CLOUD_SYNC_SETTINGS) },
                )
            }

            VSectionHead("关于")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VColors.surface, RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppMark()
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        VText("拓记 · OreNote", VTypo.dialogTitle, color = VColors.ink)
                        VText("版本 1.0.0", VTypo.caption, color = VColors.ink3)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.line))
                VText(
                    "把今日、日程、课表、白板、规划收在一处，拓记每天的节奏。",
                    VTypo.caption.copy(lineHeight = 11.sp * 1.4f),
                    color = VColors.ink3,
                )
            }
        }
    }

    when (dialog) {
        "appearance" -> InfoDialog(
            title = "外观",
            message = "当前版本提供浅色主题；深色主题在后续版本中提供。",
            onDismiss = { dialog = null },
        )
        "notify" -> NotifyDialog(
            enabled = settings.remindersEnabled,
            onOpenSystem = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                runCatching { context.startActivity(intent) }
            },
            onManage = {
                dialog = null
                navigate(V2Routes.SCHEDULE_SETTINGS)
            },
            onDismiss = { dialog = null },
        )
        "backup" -> BackupDialog(
            onExport = {
                dialog = null
                exportLauncher.launch(viewModel.suggestedFileName())
            },
            onImport = {
                dialog = null
                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            },
            onDismiss = { dialog = null },
        )
        // 「隐私与安全 / 暗号」弹窗已于 2026-09-30 整体删除（历史遗留的死设置）：
        // 那个"暗号"曾被当成保密卡片的解锁密码，但保密卡片没有查看门槛 ——
        // 真正的"暗号"是写在卡片上的**暗示文本**（BoardCard.secretHint，在卡片「更多设置」里编辑）。
        // 弹窗原文案「设置暗号后，可输入暗号查看内容」是假承诺，且设了也没有任何效果。
    }

    confirmOverwrite?.let { uriString ->
        VConfirmDeleteDialog(
            title = "确认恢复备份？",
            message = "恢复会用备份内容整体替换当前数据，无法撤销。",
            objectName = null,
            confirmText = "确认恢复",
            onConfirm = {
                confirmOverwrite = null
                android.net.Uri.parse(uriString).let(viewModel::importFrom)
            },
            onDismiss = { confirmOverwrite = null },
        )
    }
}

// ---------------------------------------------------------------- 行

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    // 入口行（56dp，图标 + 标题/副标题 + 箭头）—— 与设计稿一致，统一走共用件 VRow。
    VRow(
        title = title,
        subtitle = value,
        leading = { VBadge(icon = icon, iconSize = 15.dp) },
        trailing = { VChevron() },
        onClick = onClick,
    )
}

@Composable
private fun PlainRow(label: String, value: String?, onClick: () -> Unit) {
    // 普通行（50dp）—— 统一走共用件 VSettingRow。
    VSettingRow(label = label, value = value, onClick = onClick)
}

/** 应用标识：绿底 + 白色对切菱形（与桌面图标一致）。 */
@Composable
private fun AppMark() {
    Box(
        Modifier
            .size(52.dp)
            .background(VColors.accent, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2
            val gap = w * 0.045f
            val left = Path().apply {
                moveTo(cx - gap, 0f)
                lineTo(0f, h / 2)
                lineTo(cx - gap, h)
                close()
            }
            val right = Path().apply {
                moveTo(cx + gap, 0f)
                lineTo(w, h / 2)
                lineTo(cx + gap, h)
                close()
            }
            drawPath(left, Color.White)
            drawPath(right, Color.White)
        }
    }
}

// ---------------------------------------------------------------- 弹窗

@Composable
private fun InfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText(title, VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(10.dp))
            VText(message, VTypo.body.copy(lineHeight = 20.sp), color = VColors.ink2, align = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(VColors.accent, RoundedCornerShape(14.dp))
                    .vPressable(scaleDown = 0.97f, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                VText("好的", VTypo.buttonBold, color = Color.White)
            }
        }
    }
}

@Composable
private fun NotifyDialog(
    enabled: Boolean,
    onOpenSystem: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText("提醒与通知", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(10.dp))
            VText(
                if (enabled) "提醒已开启：到点后由系统通知推送。若收不到通知，请在系统设置中允许本应用发送通知。"
                else "提醒当前已关闭，可在「日程设置」中重新开启。",
                VTypo.body.copy(lineHeight = 20.sp),
                color = VColors.ink2,
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            DialogButton("打开系统通知设置", primary = false, onClick = onOpenSystem)
            Spacer(Modifier.height(10.dp))
            DialogButton("去日程设置", primary = false, onClick = onManage)
            Spacer(Modifier.height(10.dp))
            DialogButton("好的", primary = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun BackupDialog(onExport: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    VDialog(onDismissRequest = onDismiss) {
        VDialogPanel() {
            VText("数据与备份", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(10.dp))
            VText(
                "数据仅保存在本机。导出会生成一个 JSON 备份文件；恢复会用备份内容整体替换当前数据。",
                VTypo.body.copy(lineHeight = 20.sp),
                color = VColors.ink2,
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            DialogButton("导出备份", primary = true, onClick = onExport)
            Spacer(Modifier.height(10.dp))
            DialogButton("恢复备份", primary = false, onClick = onImport)
            Spacer(Modifier.height(10.dp))
            DialogButton("取消", primary = false, onClick = onDismiss)
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                when {
                    danger -> VColors.roseSoft
                    primary -> VColors.accent
                    else -> VColors.surface2
                },
                RoundedCornerShape(14.dp),
            )
            .vPressable(scaleDown = 0.97f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            text,
            VTypo.buttonBold,
            color = when {
                danger -> VColors.rose
                primary -> Color.White
                else -> VColors.ink
            },
        )
    }
}

/** 把"上次同步"的毫秒时间翻成一句人话。 */
private fun formatSyncAgo(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 1440 -> "${minutes / 60} 小时前"
        else -> "${minutes / 1440} 天前"
    }
}