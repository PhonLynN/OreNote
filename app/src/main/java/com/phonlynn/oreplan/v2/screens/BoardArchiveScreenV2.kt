package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.v2.components.VCard
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VEmptyState
import com.phonlynn.oreplan.v2.components.VPageScaffold
import com.phonlynn.oreplan.v2.richtext.VRichText
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BoardArchiveState(
    val cards: List<BoardCard> = emptyList(),
    val tags: List<BoardTag> = emptyList(),
    val cardTags: List<BoardCardTag> = emptyList(),
)

@HiltViewModel
class BoardArchiveScreenV2ViewModel @Inject constructor(
    private val repo: BoardRepository,
) : ViewModel() {
    val state: StateFlow<BoardArchiveState> = combine(
        repo.observeArchivedCards(),
        repo.observeTags(),
        repo.observeCardTags(),
    ) { cards, tags, cardTags ->
        BoardArchiveState(
            cards = cards.sortedByDescending { it.updatedAt },
            tags = tags,
            cardTags = cardTags,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BoardArchiveState())

    fun restore(id: String) {
        viewModelScope.launch { repo.setCardArchived(id, false) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteCard(id) }
    }
}

/**
 * 白板归档（V2）。已归档卡片列表：恢复 / 彻底删除。
 */
@Composable
fun BoardArchiveScreenV2(
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    viewModel: BoardArchiveScreenV2ViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<BoardCard?>(null) }

    VPageScaffold(title = "已归档", onBack = onBack) {
        if (state.cards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                VEmptyState(
                    icon = Lucide.Archive,
                    title = "归档为空",
                    description = "从卡片编辑或聚焦页归档后，会出现在这里。",
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.cards.forEach { card ->
                    item(key = card.id) {
                        val tagPath = tagPathText(
                            state.cardTags.filter { it.cardId == card.id }.map { it.tagId },
                            state.tags,
                        )
                        ArchiveCardRow(
                            card = card,
                            tagPath = tagPath,
                            onRestore = { viewModel.restore(card.id) },
                            onDelete = { deleteTarget = card },
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
            message = "删除后无法撤销，这张归档卡片将被永久移除。",
            objectName = target.title?.takeIf { it.isNotBlank() } ?: "未命名卡片",
            onConfirm = { viewModel.delete(target.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ArchiveCardRow(
    card: BoardCard,
    tagPath: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    VCard {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 类型气泡已移除；无其他顶部内容时，这一行仅在有钉标时显示。
            if (card.pinned) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Lucide.Pin, null, Modifier.size(12.dp), tint = VColors.ink3)
                }
            }
            card.title?.takeIf { it.isNotBlank() }?.let {
                VText(it, VTypo.bodyMed.copy(fontSize = 15.sp, lineHeight = 15.sp * 1.3f), color = VColors.ink, maxLines = 2)
            }
            card.body?.takeIf { it.isNotBlank() }?.let {
                // 正文已转纯文字再渲染：落库是 JSON，直接渲染会显示出结构字符。
                VRichText(
                    text = it,
                    style = CardTextStyles.bodyStyle(14.sp),
                    color = VColors.cardBody,
                    maxLines = 2,
                )
            }
            val meta = buildString {
                append(boardDateText(card.updatedAt))
                if (tagPath.isNotBlank()) append(" · ").append(tagPath)
            }
            VText(meta, VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal), color = VColors.ink3, maxLines = 1)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(VColors.accentSoft, RoundedCornerShape(12.dp))
                        .vPressable(scaleDown = 0.96f, onClick = onRestore),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Lucide.RotateCcw, null, Modifier.size(15.dp), tint = VColors.accent)
                    Spacer(Modifier.size(6.dp))
                    VText("恢复", VTypo.bodyMed, color = VColors.accent)
                }
                Row(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(VColors.roseSoft, RoundedCornerShape(12.dp))
                        .vPressable(scaleDown = 0.96f, onClick = onDelete),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(Lucide.Trash2, null, Modifier.size(15.dp), tint = VColors.rose)
                    Spacer(Modifier.size(6.dp))
                    VText("彻底删除", VTypo.bodyMed, color = VColors.rose)
                }
            }
        }
    }
}
