package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VEmptyState
import com.phonlynn.oreplan.v2.components.VPageScaffold
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
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class TaskArchiveViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    /** 删除条目（连提醒/重复例外/清单一并清）。 */
    private val deleteItemUseCase: com.phonlynn.oreplan.domain.usecase.DeleteItemUseCase,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * 已归档的待办 = 待办 + `status == CANCELLED`。
     *
     * 归档**没有独立字段**：项目既有的归档约定就是这个 status
     * （目标归档见 PlanArchiveViewModel，同样筛 CANCELLED），
     * 待办列表 / 今日页本来就会过滤掉它，归档后自然从列表消失。
     */
    val items: StateFlow<List<Item>> = itemRepository.observeAll()
        .map { list ->
            list.filter { it.kind == ItemKind.TASK && it.status == ItemStatus.CANCELLED }
                .sortedByDescending { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 归档时间文案。时间戳用的是 `updatedAt` —— 归档时会被刷新，
     * 与目标归档页取 `updatedAt` 当归档日期的既有做法一致。
     */
    fun archivedAtText(item: Item): String {
        val t = item.updatedAt.atZone(zone)
        return "归档于 ${t.monthValue}月${t.dayOfMonth}日 ${"%02d:%02d".format(t.hour, t.minute)}"
    }

    fun restore(id: String) {
        viewModelScope.launch {
            val item = itemRepository.getById(id) ?: return@launch
            itemRepository.update(item.copy(status = ItemStatus.TODO, updatedAt = Instant.now()))
        }
    }

    /** 彻底删除（走用例，提醒与重复例外一并清）。 */
    fun delete(id: String) {
        viewModelScope.launch { deleteItemUseCase(id) }
    }
}

/**
 * 已归档的待办（V2）—— 入口在设置页。
 *
 * 已完成的待办在详情里点「归档」后进这里；未完成的待办详情里仍是「删除」。
 */
@Composable
fun TaskArchiveScreenV2(
    onBack: () -> Unit,
    viewModel: TaskArchiveViewModel = hiltViewModel(),
) {
    val todos by viewModel.items.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<Item?>(null) }

    VPageScaffold(title = "已归档的待办", onBack = onBack) {
        if (todos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VEmptyState(
                    icon = Lucide.Archive,
                    title = "还没有归档的待办",
                    description = "已完成的待办在详情里点「归档」后，会出现在这里。",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                todos.forEach { todo ->
                    item(key = todo.id) {
                        ArchivedTodoRow(
                            title = todo.title,
                            meta = viewModel.archivedAtText(todo),
                            onRestore = { viewModel.restore(todo.id) },
                            onDelete = { deleteTarget = todo },
                        )
                    }
                }
            }
        }
    }

    val target = deleteTarget
    if (target != null) {
        VConfirmDeleteDialog(
            title = "彻底删除？",
            message = "删除后无法撤销，这条待办会被永久移除。",
            objectName = target.title,
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ArchivedTodoRow(
    title: String,
    meta: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    VCard {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VText(
                title,
                VTypo.bodyMed.copy(fontSize = 15.sp, lineHeight = 15.sp * 1.3f),
                color = VColors.ink,
                maxLines = 2,
            )
            VText(
                meta,
                VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                color = VColors.ink3,
                maxLines = 1,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(VColors.accentSoft, RoundedCornerShape(12.dp))
                        .vPressable(scaleDown = 0.97f, onClick = onRestore),
                    contentAlignment = Alignment.Center,
                ) {
                    VText("恢复", VTypo.bodyMed.copy(fontSize = 13.sp), color = VColors.accent)
                }
                Box(
                    Modifier
                        .size(38.dp)
                        .background(VColors.surface2, RoundedCornerShape(12.dp))
                        .vPressable(scaleDown = 0.94f, onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Trash2, "删除", Modifier.size(16.dp), tint = VColors.rose)
                }
            }
        }
    }
}
