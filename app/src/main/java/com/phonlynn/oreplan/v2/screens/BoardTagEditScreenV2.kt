package com.phonlynn.oreplan.v2.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.v2.V2Routes
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.components.VDivider
import com.phonlynn.oreplan.v2.components.VFloatingInputDialog
import com.phonlynn.oreplan.v2.components.VPageScaffold
import com.phonlynn.oreplan.v2.components.VRow
import com.phonlynn.oreplan.v2.components.VSwitch
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BoardTagEditState(
    val tag: BoardTag? = null,
    val parentName: String? = null,
    val childCount: Int = 0,
    val topLevelTags: List<BoardTag> = emptyList(),
)

@HiltViewModel
class BoardTagEditScreenV2ViewModel @Inject constructor(
    private val repo: BoardRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val tagId: String = savedStateHandle.get<String>(V2Routes.ARG_TAG_ID).orEmpty()

    val state: StateFlow<BoardTagEditState> = repo.observeTags()
        .map { tags ->
            val tag = tags.firstOrNull { it.id == tagId }
            BoardTagEditState(
                tag = tag,
                parentName = tag?.parentId?.let { pid -> tags.firstOrNull { it.id == pid }?.name },
                childCount = tag?.let { t -> tags.count { it.parentId == t.id } } ?: 0,
                topLevelTags = tags.filter { it.parentId == null && it.id != tagId }.sortedBy { it.sortIndex },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardTagEditState())

    fun rename(name: String) {
        val t = state.value.tag ?: return
        viewModelScope.launch { repo.upsertTag(t.copy(name = name)) }
    }

    fun reparent(parentId: String?) {
        val t = state.value.tag ?: return
        viewModelScope.launch { repo.upsertTag(t.copy(parentId = parentId)) }
    }

    /** 切换「不在全部中显示」（含其子标签下的卡片）。 */
    fun setHideFromAll(hide: Boolean) {
        val t = state.value.tag ?: return
        viewModelScope.launch { repo.upsertTag(t.copy(hideFromAll = hide)) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteTag(tagId)
            onDone()
        }
    }
}

/**
 * 标签设置（V2）—— 设计稿 OJoea。
 * 重命名 / 改父级（移出分组）/ 删除（子标签升级、卡片保留）。
 */
@Composable
fun BoardTagEditScreenV2(
    onBack: () -> Unit,
    viewModel: BoardTagEditScreenV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var renameOpen by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var reparentOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    VPageScaffold(title = "标签设置", onBack = onBack) {

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FieldLabel("基本信息")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VColors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, VColors.line, RoundedCornerShape(16.dp))
                    .padding(vertical = 6.dp),
            ) {
                InfoRow("名称", state.tag?.name.orEmpty(), onClick = { renameValue = state.tag?.name.orEmpty(); renameOpen = true })
                VDivider()
                InfoRow(
                    "所属分组",
                    state.parentName ?: "全部标签",
                    onClick = { reparentOpen = true },
                )
                VDivider()
                InfoRow("子标签", "${state.childCount} 个", onClick = null)
            }

            FieldLabel("可见性")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VColors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, VColors.line, RoundedCornerShape(16.dp)),
            ) {
                // 「不在全部中显示」：开启后，本标签及其子标签下的卡片
                // 在「全部」筛选里不再出现（只在指定标签里看得到）。
                //
                // 与上下条目对齐（用户 2026-09-18）：高度 68dp（副标题两行不显挤）、
                // 前面加小图标、左间距与 VRow 一致（14dp 内边距 + 11dp 图标间距）。
                Row(
                    Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    ActionBadge(Lucide.Eye)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        VText("不在全部中显示", VTypo.body, color = VColors.ink)
                        VText(
                            "开启后，该标签及其子标签下的卡片不出现在「全部」里",
                            VTypo.caption,
                            color = VColors.ink3,
                            maxLines = 2,
                        )
                    }
                    VSwitch(
                        checked = state.tag?.hideFromAll == true,
                        onCheckedChange = { viewModel.setHideFromAll(it) },
                    )
                }
            }

            FieldLabel("操作")
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VColors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, VColors.line, RoundedCornerShape(16.dp)),
            ) {
                VRow(
                    title = "重命名标签",
                    subtitle = "修改名称或显示顺序",
                    leading = { ActionBadge(Lucide.PencilLine) },
                    trailing = { Chevron() },
                    onClick = { renameValue = state.tag?.name.orEmpty(); renameOpen = true },
                )
                VDivider()
                VRow(
                    title = "移出分组",
                    subtitle = "把「${state.tag?.name.orEmpty()}」移出当前分组",
                    leading = { ActionBadge(Lucide.FolderInput) },
                    trailing = { Chevron() },
                    onClick = { viewModel.reparent(null) },
                )
                VDivider()
                VRow(
                    title = "删除标签",
                    subtitle = "标签下的卡片会保留",
                    leading = { ActionBadge(Lucide.Trash2, tint = VColors.rose) },
                    trailing = { Chevron(VColors.rose) },
                    onClick = { confirmDelete = true },
                )
            }

            Spacer(Modifier.height(4.dp))
            DoneButton(onClick = onBack)
        }
    }

    if (renameOpen) {
        VFloatingInputDialog(
            icon = Lucide.PencilLine,
            value = renameValue,
            onValueChange = { renameValue = it },
            onConfirm = {
                val name = renameValue.trim()
                if (name.isNotEmpty()) viewModel.rename(name)
                renameOpen = false
            },
            onDismiss = { renameOpen = false },
            placeholder = "标签名称",
            confirmText = "保存",
        )
    }

    if (reparentOpen) {
        ReparentDialog(
            parentName = state.parentName,
            topLevelTags = state.topLevelTags,
            onSelect = { viewModel.reparent(it); reparentOpen = false },
            onDismiss = { reparentOpen = false },
        )
    }

    if (confirmDelete) {
        VConfirmDeleteDialog(
            title = "删除标签？",
            message = "子标签会升级到上一级，标签下的卡片会保留。",
            objectName = state.tag?.name ?: "标签",
            onConfirm = { viewModel.delete(onBack); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    VText(text, VTypo.caption, color = VColors.ink3, maxLines = 1)
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(if (onClick != null) Modifier.vPressable(scaleDown = 0.985f, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        VText(label, VTypo.body, color = VColors.ink, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VText(value, VTypo.caption12, color = VColors.ink2, maxLines = 1)
            if (onClick != null) Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = VColors.ink3)
        }
    }
}

@Composable
private fun ActionBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = VColors.ink2) {
    Box(Modifier.size(30.dp).background(VColors.bg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
    }
}

@Composable
private fun Chevron(tint: Color = VColors.ink3) {
    Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = tint)
}

@Composable
private fun ReparentDialog(
    parentName: String?,
    topLevelTags: List<BoardTag>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss, maxWidth = 260.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel(horizontalPadding = 0.dp, verticalPadding = 6.dp) {
            ReparentRow("全部标签（顶级）", parentName == null) { close { onSelect(null) } }
            topLevelTags.forEach { tag ->
                ReparentRow(tag.name, parentName == tag.name) { close { onSelect(tag.id) } }
            }
        }
    }
}

@Composable
private fun ReparentRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 14.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(label, VTypo.body, color = if (selected) VColors.accent else VColors.ink, maxLines = 1)
        if (selected) {
            Spacer(Modifier.weight(1f))
            Icon(Lucide.Check, null, Modifier.size(16.dp), tint = VColors.accent)
        }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(VColors.accent, RoundedCornerShape(15.dp))
            .vPressable(scaleDown = 0.97f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText("完成", VTypo.button, color = Color.White)
    }
}
