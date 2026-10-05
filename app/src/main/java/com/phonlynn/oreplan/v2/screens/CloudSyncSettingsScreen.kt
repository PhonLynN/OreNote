package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.phonlynn.oreplan.platform.sync.SyncAlarms
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonlynn.oreplan.domain.sync.ConnectionState
import com.phonlynn.oreplan.v2.components.VBadge
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VChevron
import com.phonlynn.oreplan.v2.components.VDangerCard
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogButtons
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VFloatingInputDialog
import com.phonlynn.oreplan.v2.components.VNavBar
import com.phonlynn.oreplan.v2.components.VPrimaryButton
import com.phonlynn.oreplan.v2.components.VRow
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.components.VSwitch
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 云同步设置页。
 *
 * ## 分区顺序按「用户会怎么想」排，不按实现顺序
 *
 * 1. **连接** —— 没有凭据什么都做不了，放最前
 * 2. **安全** —— 主密钥。必须在用户开启同步**之前**看到"丢了就解不开"这件事
 * 3. **同步** —— 开关、仅 Wi-Fi、自动同步、立即同步
 * 4. **危险区** —— 解除本机同步（最后、独立一卡）
 *
 * ## 两个刻意的设计
 *
 * · 完整备份码**只展示一次**，关掉后只留指纹。理由是希望用户当场抄走，
 *   而不是"反正还能再看"然后一直不看；
 * · 「解除本机同步」的文案反复强调**云端数据不受影响** ——
 *   这两件事用户极易混淆，混淆的代价是数据没了。
 *
 * ## 组件选择（与项目约定一致）
 *
 * 凭据输入走 [VFloatingInputDialog]（整屏浮层输入）而不是自造内嵌输入框 ——
 * 设置类页面的输入一律是"点一行 → 弹输入"，与「保密暗号」「地点输入」同源。
 */
@Composable
fun CloudSyncSettingsScreen(
    onBack: () -> Unit,
    viewModel: CloudSyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * 排定/取消自动同步的闹钟。
     *
     * 为什么由**界面**来做这件事：调度需要 `Context`，而 ViewModel 不该依赖
     * Android 框架类（那会让它无法在纯 JVM 测试里跑）。
     * ViewModel 只发一个布尔事件出来，这里消费它。
     *
     * 用 LaunchedEffect 而不是在 onClick 里直接调：设置页可能在别处被打开
     * （例如从设置主页进入），那时也需要按当前配置对齐一次闹钟状态。
     */
    val scheduleRequest by viewModel.scheduleRequest.collectAsStateWithLifecycle()
    LaunchedEffect(scheduleRequest) {
        when (scheduleRequest) {
            true -> SyncAlarms.schedule(context)
            false -> SyncAlarms.cancel(context)
            null -> Unit
        }
        if (scheduleRequest != null) viewModel.consumeScheduleRequest()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            VNavBar(title = "云同步", onBack = onBack)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ConnectionSection(state, viewModel)
                SecuritySection(state, viewModel)
                BehaviorSection(state, viewModel)
                DangerSection(viewModel)
            }
        }

        VBottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            VPrimaryButton(
                text = "保存设置",
                icon = Lucide.Check,
                enabled = state.draft.isConfigured,
                onClick = { viewModel.save(onBack) },
            )
        }
    }

    // 就地编辑某个凭据字段
    state.editing?.let { field ->
        VFloatingInputDialog(
            icon = field.icon,
            value = field.current(state.draft),
            onValueChange = { viewModel.editField(field, it) },
            onConfirm = viewModel::finishEditing,
            onDismiss = viewModel::finishEditing,
            placeholder = field.placeholder,
            confirmText = "确定",
        )
    }

    // 导入备份码：走整屏浮层输入（与凭据输入同源）
    if (state.importingKey) {
        VFloatingInputDialog(
            icon = Lucide.Key,
            value = state.importCode,
            onValueChange = viewModel::editImportCode,
            onConfirm = { viewModel.importKey(state.importCode) },
            onDismiss = viewModel::cancelImportKey,
            placeholder = "XXXX-XXXX-XXXX-…",
            confirmText = "导入",
        )
    }

    // 备份码校验失败的提示：**留在界面上**而不是一闪而过的 Toast ——
    // 用户需要照着原文逐段核对，提示得一直在。
    state.importError?.let { message ->
        VDialog(onDismissRequest = viewModel::cancelImportKey, maxWidth = 320.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VColors.surface, RoundedCornerShape(24.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                VText("备份码不对", VTypo.dialogTitle, color = VColors.ink)
                VText(message, VTypo.caption12, color = VColors.rose)
                VText(
                    "请对照原文逐组检查。备份码只包含 A-Z 与 2-7，" +
                        "不含数字 0、1（避免与字母 O、I 混淆）。",
                    VTypo.caption,
                    color = VColors.ink3,
                )
                DialogAction("重新输入", VColors.accent) {
                    viewModel.cancelImportKey()
                    viewModel.showKeyDialog()
                }
            }
        }
    }

    state.dialog?.let { dialog ->
        when (dialog) {
            CloudSyncDialog.Key -> KeyDialog(state, viewModel)
            CloudSyncDialog.Reset -> ResetDialog(viewModel)
            CloudSyncDialog.RegenerateKey -> RegenerateKeyDialog(viewModel)
        }
    }
}

// ---------------------------------------------------------------- 连接

@Composable
private fun ConnectionSection(state: CloudSyncUiState, viewModel: CloudSyncSettingsViewModel) {
    VSectionHead("连接", note = if (state.draft.isConfigured) null else "还差 ${state.draft.missingFields.size} 项")
    VCard {
        state.fields.forEachIndexed { index, field ->
            if (index > 0) VDivider()
            VRow(
                title = field.label,
                subtitle = field.display(state.draft),
                leading = { VBadge(icon = field.icon, iconSize = 15.dp) },
                trailing = { VChevron() },
                onClick = { viewModel.startEditing(field) },
            )
        }
    }

    val connection = state.connection
    Box(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(16.dp))
            .vPressable(
                scaleDown = 0.98f,
                enabled = state.draft.isConfigured && !state.testing,
                onClick = viewModel::testConnection,
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (icon, tint, label) = when {
                !state.draft.isConfigured ->
                    Triple(Lucide.Link, VColors.ink3, "先填完上面 4 项")
                state.testing ->
                    Triple(Lucide.RefreshCw, VColors.accent, "正在连接…")
                connection is ConnectionState.Success ->
                    Triple(Lucide.Check, VColors.accent, "连接正常")
                connection is ConnectionState.Failure ->
                    Triple(Lucide.CircleAlert, VColors.rose, connection.message)
                else ->
                    Triple(Lucide.Link, VColors.accent, "测试连接")
            }
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
            VText(label, VTypo.bodyMed, color = tint, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------- 安全

@Composable
private fun SecuritySection(state: CloudSyncUiState, viewModel: CloudSyncSettingsViewModel) {
    VSectionHead("安全")
    VCard {
        VRow(
            title = if (state.hasKey) "主密钥已设置" else "主密钥未设置",
            subtitle = state.keyFingerprint
                ?.let { "指纹 $it · 点这里管理" }
                ?: "用来加密上传到云端的内容",
            leading = { VBadge(icon = Lucide.Key, iconSize = 15.dp) },
            trailing = { VChevron() },
            onClick = viewModel::showKeyDialog,
        )
    }
    // 这条警告常驻显示（不是只在弹窗里）——它是最需要用户理解的一件事
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Lucide.CircleAlert, contentDescription = null, modifier = Modifier.size(14.dp), tint = VColors.amber)
            VText(
                "主密钥丢失后，云端数据无法恢复",
                VTypo.caption.copy(fontWeight = FontWeight.Medium),
                color = VColors.amber,
            )
        }
        VText(
            "内容在你手机上加密后才上传，Cloudflare 也看不到明文。" +
                "代价是：只有你手上的备份码能解开它。",
            VTypo.caption,
            color = VColors.ink3,
        )
    }
}

// ---------------------------------------------------------------- 同步行为

@Composable
private fun BehaviorSection(state: CloudSyncUiState, viewModel: CloudSyncSettingsViewModel) {
    VSectionHead("同步")
    VCard {
        SwitchRow(
            title = "开启云同步",
            subtitle = when {
                !state.canEnable -> "需要先填完连接信息并生成主密钥"
                state.config.enabled -> "已开启"
                else -> "关闭时不会上传或下载任何数据"
            },
            icon = Lucide.Cloud,
            checked = state.config.enabled,
            onCheckedChange = viewModel::setEnabled,
        )
        VDivider()
        SwitchRow(
            title = "仅 Wi-Fi 上传附件",
            subtitle = "移动网络下只同步文字，附件等连上 Wi-Fi",
            icon = Lucide.Wifi,
            checked = state.config.wifiOnly,
            onCheckedChange = viewModel::setWifiOnly,
        )
        VDivider()
        SwitchRow(
            title = "自动同步",
            // 文案要说清**频率**：只写"自动同步"用户不知道多久一次，
            // 也就无法判断会不会耗流量、值不值得开着。
            subtitle = "每 6 小时一次；退到后台且有改动时也会同步",
            icon = Lucide.RefreshCw,
            checked = state.config.autoSync,
            onCheckedChange = viewModel::applyAutoSyncSetting,
        )
        VDivider()
        VRow(
            title = if (state.syncing) "正在同步…" else "立即同步",
            // 同步中显示**阶段进度**（如"正在同步附件… 3/12"）：
            // 附件多时可能几十秒，静止的"正在同步…"会让用户以为卡死了。
            subtitle = state.progress ?: state.statusText,
            leading = { VBadge(icon = Lucide.Play, iconSize = 15.dp) },
            trailing = { VChevron() },
            // 同步中禁用：重复点击会并发发起多次同步，两边互相覆盖
            onClick = { if (!state.syncing) viewModel.syncNow() },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        VBadge(icon = icon, iconSize = 15.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, VTypo.body, color = VColors.ink)
            VText(subtitle, VTypo.caption, color = VColors.ink3, maxLines = 2)
        }
        VSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------- 危险区

@Composable
private fun DangerSection(viewModel: CloudSyncSettingsViewModel) {
    VSectionHead("危险操作")
    VDangerCard(text = "解除本机同步", onClick = viewModel::askReset)
    Column(
        Modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        VText(
            "「解除本机同步」只清除这台手机上的凭据，不会删除云端数据。" +
                "重新填入相同的连接信息与备份码即可继续。",
            VTypo.caption,
            color = VColors.ink3,
        )
    }
}

// ---------------------------------------------------------------- 弹窗

@Composable
private fun KeyDialog(state: CloudSyncUiState, viewModel: CloudSyncSettingsViewModel) {
    VDialog(onDismissRequest = viewModel::dismissDialog, maxWidth = 340.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("主密钥", VTypo.dialogTitle, color = VColors.ink)

            when (val key = state.keyDisplay) {
                is KeyDisplay.Fresh -> {
                    VText(
                        "请现在就抄下这串字符。它是解开云端数据的唯一钥匙，" +
                            "关掉这个窗口后就看不到了（只保留指纹）。",
                        VTypo.caption12,
                        color = VColors.ink3,
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(VColors.bg, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        key.lines.forEach { line -> VText(line, VTypo.numMini, color = VColors.ink) }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Lucide.CircleAlert,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = VColors.amber,
                        )
                        VText("关掉后就看不到了", VTypo.caption, color = VColors.amber)
                    }
                    DialogAction("我抄好了", VColors.accent, viewModel::dismissDialog)
                }

                is KeyDisplay.Existing -> {
                    VText("指纹 ${key.fingerprint}", VTypo.bodyMed, color = VColors.ink)
                    VText(
                        "完整备份码只在你生成它的那一刻展示过一次 —— 这是刻意的：" +
                            "避免因为「反正还能再看」而一直没有真正保存。",
                        VTypo.caption,
                        color = VColors.ink3,
                    )
                    if (key.canReveal) {
                        DialogAction("再次显示备份码", VColors.ink) { viewModel.revealKey() }
                    }
                    DialogAction("重新生成密钥…", VColors.rose, viewModel::askRegenerate)
                    DialogAction("关闭", VColors.ink2, viewModel::dismissDialog)
                }

                KeyDisplay.None -> {
                    VText(
                        "还没有主密钥。生成后会展示一次完整备份码，请当场抄写。",
                        VTypo.caption12,
                        color = VColors.ink3,
                    )
                    DialogAction("生成主密钥", VColors.accent, viewModel::generateKey)
                    DialogAction("导入已有备份码…", VColors.ink, viewModel::startImportKey)
                    DialogAction("关闭", VColors.ink2, viewModel::dismissDialog)
                }
            }
        }
    }
}

@Composable
private fun RegenerateKeyDialog(viewModel: CloudSyncSettingsViewModel) {
    VDialog(onDismissRequest = viewModel::dismissDialog, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("重新生成主密钥？", VTypo.dialogTitle, color = VColors.ink)
            VText(
                "换一把钥匙之后，云端已有的密文将无法解密 —— " +
                    "旧数据会变成解不开的垃圾。只有在你确认云端没有需要保留的数据时才该这么做。",
                VTypo.caption12,
                color = VColors.rose,
            )
            VDialogButtons(
                onCancel = viewModel::dismissDialog,
                onConfirm = viewModel::generateKeyKeepingCloud,
                confirmText = "重新生成",
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}

@Composable
private fun ResetDialog(viewModel: CloudSyncSettingsViewModel) {
    VDialog(onDismissRequest = viewModel::dismissDialog, maxWidth = 320.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(VColors.surface, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VText("解除本机同步？", VTypo.dialogTitle, color = VColors.ink)
            VText(
                "会清除这台手机上的连接信息与主密钥。云端数据不受影响 —— " +
                    "下次填入相同的连接信息与备份码即可继续。",
                VTypo.caption12,
                color = VColors.ink3,
            )
            VDialogButtons(
                onCancel = viewModel::dismissDialog,
                onConfirm = viewModel::reset,
                confirmText = "解除",
                height = 46.dp,
                radius = 13.dp,
            )
        }
    }
}

@Composable
private fun DialogAction(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(VColors.bg, RoundedCornerShape(12.dp))
            .vPressable(scaleDown = 0.97f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(text, VTypo.bodyMed, color = color)
    }
}
