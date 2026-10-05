package com.phonlynn.oreplan.v2.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.data.settings.AppSettings
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardLink
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VBottomActionBar
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VNavBar
import com.phonlynn.oreplan.v2.components.VPrimaryButton
import com.phonlynn.oreplan.v2.components.VSectionHead
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import com.phonlynn.oreplan.v2.components.VSettingRow
import com.phonlynn.oreplan.v2.components.VSettingSwitchRow
import com.phonlynn.oreplan.v2.components.VSettingRowHeight
import com.phonlynn.oreplan.v2.components.VSettingRowPadding

@HiltViewModel
class BoardSettingsScreenV2ViewModel @Inject constructor(
    private val repo: BoardRepository,
    private val settingsStore: AppSettingsStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    /** 导出白板数据（卡片 / 清单 / 标签 / 关联）为 JSON 文本。 */
    suspend fun exportJson(): String {
        val cards = repo.observeCards().first()
        val todos = repo.observeTodoItems().first()
        val tags = repo.observeTags().first()
        val cardTags = repo.observeCardTags().first()
        val links = repo.observeCardLinks().first()

        fun cardJson(card: BoardCard) = JSONObject().apply {
            put("id", card.id)
            put("type", card.type.key)
            put("title", card.title ?: JSONObject.NULL)
            put("body", card.body ?: JSONObject.NULL)
            put("color", card.color ?: JSONObject.NULL)
            put("pinned", card.pinned)
            put("secret", card.secret)
            put("createdAt", card.createdAt.toEpochMilli())
            put("updatedAt", card.updatedAt.toEpochMilli())
        }

        val root = JSONObject().apply {
            put("format", "oren_note_board")
            put("exportedAt", System.currentTimeMillis())
            put("cards", JSONArray().apply { cards.forEach { put(cardJson(it)) } })
            put(
                "todoItems",
                JSONArray().apply {
                    todos.forEach { t: BoardTodoItem ->
                        put(JSONObject().apply {
                            put("id", t.id); put("cardId", t.cardId); put("text", t.text)
                            put("done", t.done); put("sortIndex", t.sortIndex)
                        })
                    }
                },
            )
            put(
                "tags",
                JSONArray().apply {
                    tags.forEach { t: BoardTag ->
                        put(JSONObject().apply {
                            put("id", t.id); put("name", t.name)
                            put("parentId", t.parentId ?: JSONObject.NULL); put("sortIndex", t.sortIndex)
                        })
                    }
                },
            )
            put(
                "cardTags",
                JSONArray().apply {
                    cardTags.forEach { l: BoardCardTag ->
                        put(JSONObject().apply { put("cardId", l.cardId); put("tagId", l.tagId) })
                    }
                },
            )
            put(
                "cardLinks",
                JSONArray().apply {
                    links.forEach { l: BoardCardLink ->
                        put(JSONObject().apply { put("cardId", l.cardId); put("linkedCardId", l.linkedCardId) })
                    }
                },
            )
        }
        return root.toString(2)
    }

    fun clearAllCards(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val cards = repo.observeCards().first() + repo.observeArchivedCards().first()
            cards.forEach { repo.deleteCard(it.id) }
            onDone(cards.size)
        }
    }

}

/** 白板设置（V2）—— 设计稿 q9zeVF：卡片 / 外观 / 交互 / 数据。 */
@Composable
fun BoardSettingsScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: BoardSettingsScreenV2ViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = runCatching {
                    val json = viewModel.exportJson()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("no stream")
                }.isSuccess
                Toast.makeText(context, if (ok) "已导出白板数据" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 根容器用 Box：下方要放一个「固定在底部」的操作条。
    Box(
        Modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
        VNavBar(title = "白板设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VSectionHead("卡片")
            VCard {
                SettingValueRow("默认卡片类型", boardTypeLabel(settings.boardDefaultType)) { dialog = "type" }
                VDivider()
                SettingValueRow("新建卡片位置", if (settings.boardNewCardPosition == "TOP") "列表顶部" else "列表底部") { dialog = "position" }
                VDivider()
                SettingSwitchRow("显示创建时间", "新建卡片默认显示日期", settings.boardShowCreatedTime) { v ->
                    viewModel.update { it.copy(boardShowCreatedTime = v) }
                }
                VDivider()
                SettingSwitchRow("自动保存草稿", "离开新建页时暂存未写完的内容，下次新建自动回填", settings.boardAutoDraft) { v ->
                    viewModel.update { it.copy(boardAutoDraft = v) }
                }
            }

            VSectionHead("外观")
            VCard {
                SettingValueRow("卡片默认宽度", widthLabel(settings.boardDefaultWidth)) { dialog = "width" }
                VDivider()
                SettingValueRow(
                    "卡片默认图片样式",
                    when (settings.boardDefaultImageLayout) {
                        "grid" -> "始终网格"
                        "fill" -> "始终填充"
                        else -> "自动"
                    },
                ) { dialog = "imageLayout" }
                VDivider()
                // 只在「自动」宽度下才有意义，所以跟随该设置显隐。
                if (settings.boardDefaultWidth == "auto") {
                    SettingValueRow(
                        "自动宽度阈值",
                        "${settings.boardAutoWidthChars} 字起算整行",
                    ) { dialog = "autoWidth" }
                    VDivider()
                }
                SettingValueRow(
                    "卡片字号",
                    when (settings.boardCardFontSize) {
                        "small" -> "小"
                        "large" -> "大"
                        else -> "中"
                    },
                ) { dialog = "cardFont" }
                VDivider()
                SettingSwitchRow("紧凑模式", "收紧卡片内边距与字号", settings.boardCompact) { v ->
                    viewModel.update { it.copy(boardCompact = v) }
                }
                VDivider()
                SettingValueRow(
                    "聚焦展示形态",
                    if (settings.boardFocusStyle == "fullscreen") "全屏" else "卡片",
                ) { dialog = "focusStyle" }
                VDivider()
                SettingSwitchRow("卡片倾斜", "卡片带轻微随机角度，关闭后严格水平", settings.boardCardTilt) { v ->
                    viewModel.update { it.copy(boardCardTilt = v) }
                }
                VDivider()
                SettingSwitchRow("卡片随机颜色", "新建卡片随机分配一个预设底色", settings.boardRandomColor) { v ->
                    viewModel.update { it.copy(boardRandomColor = v) }
                }
                VDivider()
                SettingSwitchRow("使用全屏视图新建", "新建卡片时直接打开全屏文本编辑", settings.boardNewCardFullscreen) { v ->
                    viewModel.update { it.copy(boardNewCardFullscreen = v) }
                }
            }

            VSectionHead("交互")
            VCard {
                SettingValueRow("新建卡片入口", if (settings.boardNewCardEntry == "header") "标题栏按钮" else "右下悬浮按钮") { dialog = "entry" }
                VDivider()
                SettingSwitchRow("双击卡片进入编辑", "关闭时单击进入聚焦页再编辑", settings.boardDoubleTapEdit) { v ->
                    viewModel.update { it.copy(boardDoubleTapEdit = v) }
                }
                VDivider()
                SettingSwitchRow("拖动卡片调整顺序", "长按卡片拖动排序", settings.boardDragReorder) { v ->
                    viewModel.update { it.copy(boardDragReorder = v) }
                }
                VDivider()
                SettingSwitchRow("完成待办后移至末尾", "清单里勾选完成自动沉底", settings.boardDoneToEnd) { v ->
                    viewModel.update { it.copy(boardDoneToEnd = v) }
                }
                VDivider()
                SettingSwitchRow(
                    "搜索需包含全部关键词",
                    if (settings.boardSearchMatchAll) "多个词之间为交集：全部命中才显示" else "多个词之间为并集：命中任一即显示",
                    settings.boardSearchMatchAll,
                ) { v ->
                    viewModel.update { it.copy(boardSearchMatchAll = v) }
                }
            }

            VSectionHead("数据")
            VCard {
                SettingValueRow("已归档", "查看与恢复归档卡片") { navigate(V2Routes.BOARD_ARCHIVE) }
                VDivider()
                SettingValueRow("导出白板数据", "JSON 文件") { exportLauncher.launch("拓记白板-${System.currentTimeMillis() / 86_400_000}.json") }
                VDivider()
                DestructiveRow("清空全部卡片", "不可恢复，谨慎操作") { confirmClear = true }
            }

        }

        }
        // 底部固定操作条（与 Tab 栏同款：渐隐遮罩 + 实底）。
        VBottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            VPrimaryButton(text = "保存设置", icon = Lucide.Check, onClick = {
                Toast.makeText(context, "设置已生效", Toast.LENGTH_SHORT).show()
                onBack()
            })
        }
    }

    when (dialog) {
        "type" -> OptionPicker(
            title = "默认卡片类型",
            options = listOf("QUICK" to "速记", "TODO" to "待办", "QUOTE" to "摘抄", "GOAL" to "目标"),
            selected = settings.boardDefaultType,
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardDefaultType = key) }
            dialog = null
        }
        "position" -> OptionPicker(
            title = "新建卡片位置",
            options = listOf("TOP" to "列表顶部", "BOTTOM" to "列表底部"),
            selected = settings.boardNewCardPosition,
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardNewCardPosition = key) }
            dialog = null
        }
        "width" -> OptionPicker(
            title = "卡片默认宽度",
            options = listOf("auto" to "自动", "half" to "半宽", "full" to "整行"),
            selected = settings.boardDefaultWidth,
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardDefaultWidth = key) }
            dialog = null
        }
        "imageLayout" -> OptionPicker(
            title = "卡片默认图片样式",
            options = listOf("auto" to "自动", "fill" to "始终填充", "grid" to "始终网格"),
            selected = settings.boardDefaultImageLayout,
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardDefaultImageLayout = key) }
            dialog = null
        }
        "cardFont" -> OptionPicker(
            title = "卡片字号",
            options = listOf("small" to "小", "medium" to "中", "large" to "大"),
            selected = settings.boardCardFontSize,
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardCardFontSize = key) }
            dialog = null
        }
        "focusStyle" -> OptionPicker(
            title = "聚焦展示形态",
            options = listOf(
                "card" to "卡片（从原位放大）",
                "fullscreen" to "全屏（整页淡入）",
            ),
            selected = settings.boardFocusStyle,
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardFocusStyle = key) }
            dialog = null
        }
        "autoWidth" -> OptionPicker(
            title = "自动宽度阈值",
            options = listOf(12, 16, 20, 24, 30, 40, 60).map { it.toString() to "$it 字" },
            selected = settings.boardAutoWidthChars.toString(),
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardAutoWidthChars = key.toIntOrNull() ?: 24) }
            dialog = null
        }
        "entry" -> OptionPicker(
            title = "新建卡片入口",
            options = listOf("fab" to "右下悬浮按钮", "header" to "标题栏按钮"),
            selected = settings.boardNewCardEntry,
            onDismiss = { dialog = null },
        ) { key ->
            viewModel.update { it.copy(boardNewCardEntry = key) }
            dialog = null
        }
        else -> Unit
    }

    if (confirmClear) {
        VConfirmDeleteDialog(
            title = "清空全部卡片？",
            message = "将删除白板里的所有卡片与清单，无法撤销。",
            objectName = null,
            confirmText = "确认清空",
            onConfirm = {
                confirmClear = false
                viewModel.clearAllCards { count ->
                    Toast.makeText(context, "已清空 $count 张卡片", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { confirmClear = false },
        )
    }
}

// ---------------------------------------------------------------- 行组件

@Composable
private fun SettingValueRow(label: String, value: String, onClick: () -> Unit) {
    // 统一走共用件（50dp 普通行）；与设计稿一致。
    VSettingRow(label = label, value = value, onClick = onClick)
}

@Composable
private fun SettingSwitchRow(label: String, subtitle: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    // 统一走共用件（50dp 开关行；设计稿开关行 = 50dp，原先 56dp 系偏离）。
    VSettingSwitchRow(
        label = label,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onChecked,
    )
}

@Composable
private fun DestructiveRow(label: String, subtitle: String, onClick: () -> Unit) {
    // 危险项：rose 文字 + 右侧说明。高度统一到设计稿的 50dp（原先 56dp 系偏离）。
    // ⚠️ 设计稿的「清空全部卡片」其实是 label+chevron、说明在卡片下方 Footer；
    //    当前实现的「右侧说明」属既有形态，本次**不动结构**，只对齐高度（见 do-not-touch.md 纪律）。
    Row(
        Modifier
            .fillMaxWidth()
            .height(VSettingRowHeight)
            .vPressable(scaleDown = 0.985f, onClick = onClick)
            .padding(horizontal = VSettingRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        VText(label, VTypo.body, color = VColors.rose)
        VText(subtitle, VTypo.caption, color = VColors.ink3)
    }
}

@Composable
private fun OptionPicker(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    VDialog(onDismissRequest = onDismiss) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            VText(title, VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (key, label) ->
                    val active = key == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .background(if (active) VColors.accentSoft else VColors.surface2, RoundedCornerShape(12.dp))
                            // 关键（2026-09-19 修）：先把选择结果**立即生效**（写设置），
                            // 再走弹窗退场动画。
                            // 之前写成 `close { onSelect(key) }`，选择要等退场动画撒完才执行；
                            // 一旦退场回调因任何原因没跑到（页面离开、重生、动画被中断），
                            // 就会出现「选项文字没变 / 设置与实际不同步」。
                            .vPressable(scaleDown = 0.97f) {
                                onSelect(key)
                                close {}
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        VText(label, VTypo.bodyMed, color = if (active) VColors.accent else VColors.ink)
                        if (active) Icon(Lucide.Check, null, Modifier.size(16.dp), tint = VColors.accent)
                    }
                }
            }
        }
    }
}

private fun boardTypeLabel(key: String): String = when (key) {
    "TODO" -> "待办"
    "QUOTE" -> "摘抄"
    "GOAL" -> "目标"
    else -> "速记"
}

private fun widthLabel(key: String): String = when (key) {
    "half" -> "半宽"
    "full" -> "整行"
    else -> "自动"
}
