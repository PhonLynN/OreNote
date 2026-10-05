package com.phonlynn.oreplan.v2.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 输入框「＋」弹出的菜单（用户 2026-10-04 定的形态）。
 *
 * ## 用户口径
 *
 * > 「可以合成在悬浮输入框里的加号按钮里，点击加号按钮，弹出一个菜单列表，
 * > 包含"导入文件 / 会话文件 / 关联项目"，前两个都好理解，
 * > 关联项目是可以选择 orenote 内部的日程，待办，卡片，规划等内容」
 *
 * ## 三层结构（根菜单 → 子页）
 *
 * ```
 * Root    导入文件 / 会话文件 / 关联项目
 * Files   本会话的附件列表（含"待整理"）
 * Link    orenote 内部对象的多选
 * ```
 *
 * 后两项**不是点击即执行** —— 它们都要先让用户选东西，所以各自展开一页。
 * 只有「导入文件」直接拉起系统选择器。
 *
 * ## 位置：贴在输入框**上方**
 *
 * 菜单从下方滑入、停在输入框上面。不铺全屏遮罩 —— 用户在挑文件/挑条目时
 * 还想看到对话内容（那正是他判断"该关联哪条"的依据）。
 * 点菜单外的地方关掉（见 `ChatScreen` 的 `onDismiss`）。
 *
 * @param page 当前页；null = 关着
 */
@Composable
fun PlusMenu(
    page: PlusMenuPage?,
    files: List<Attachment>,
    linkCandidates: List<LinkedItem>,
    linkedItems: List<LinkedItem>,
    onPage: (PlusMenuPage) -> Unit,
    onImport: () -> Unit,
    onToggleLink: (LinkedItem) -> Unit,
    onClearLinks: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = page != null,
        enter = fadeIn(tween(VMotion.RevealMillis, easing = VMotion.Expressive)) +
            slideInVertically(
                animationSpec = tween(VMotion.RevealMillis, easing = VMotion.Expressive),
                // 从下方一点点浮上来（它贴在输入框上方，位移要小）
                initialOffsetY = { it / 3 },
            ),
        exit = fadeOut(tween(VMotion.ExitMillis, easing = VMotion.Accelerate)) +
            slideOutVertically(
                animationSpec = tween(VMotion.ExitMillis, easing = VMotion.Accelerate),
                targetOffsetY = { it / 3 },
            ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(VColors.surface)
                .border(1.dp, VColors.line, RoundedCornerShape(18.dp))
                // 高度上限：候选可能几十条，不限制会把输入框顶出屏幕
                .heightIn(max = PANEL_MAX_HEIGHT)
                .padding(vertical = 6.dp),
        ) {
            when (page) {
                PlusMenuPage.Files -> FilesPage(files, onPage, onImport)
                PlusMenuPage.Link -> LinkPage(
                    candidates = linkCandidates,
                    selected = linkedItems,
                    onToggle = onToggleLink,
                    onClear = onClearLinks,
                )
                // Root（含 null 的退场帧 —— 退场动画期间内容还在）
                else -> RootPage(onImport = onImport, onPage = onPage)
            }
        }
    }
}

/** 根菜单：三项。 */
@Composable
private fun RootPage(onImport: () -> Unit, onPage: (PlusMenuPage) -> Unit) {
    MenuItem(Lucide.Paperclip, "导入文件", "从手机里选文件") {
        onImport()
    }
    MenuItem(Lucide.FolderOpen, "会话文件", "看看导进来的文件") {
        onPage(PlusMenuPage.Files)
    }
    MenuItem(Lucide.Link2, "关联项目", "把日程/待办/卡片附给 AI") {
        onPage(PlusMenuPage.Link)
    }
}

/**
 * 「会话文件」页。
 *
 * ## 这一页同时解决了「待整理文件没有界面入口」
 *
 * 那些文件一直存在（AI 能通过 `list_files` 看到），但用户在 App 里看不到 ——
 * 文件是"隐形"的。现在从这一页进。
 *
 * ## 空态要给出**下一步动作**，不是一句"没有文件"
 *
 * 空列表本身就是"为什么是空的、我该做什么"的问题。所以空态直接给
 * 「导入文件」入口 —— 用户此刻想做的就是导入。
 */
@Composable
private fun FilesPage(
    files: List<Attachment>,
    onPage: (PlusMenuPage) -> Unit,
    onImport: () -> Unit,
) {
    PageHeader(title = "会话文件", onBack = { onPage(PlusMenuPage.Root) })

    if (files.isEmpty()) {
        EmptyHint("还没有导入过文件")
        MenuItem(Lucide.Paperclip, "导入文件", "从手机里选文件") { onImport() }
        return
    }

    LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
        items(files, key = { it.id }) { file ->
            FileRow(file)
        }
    }
}

/** 一个文件：图标 + 名字 + 大小。 */
@Composable
private fun FileRow(file: Attachment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            when {
                file.isImage -> Lucide.Image
                else -> Lucide.FileText
            },
            contentDescription = null,
            tint = VColors.ink3,
            modifier = Modifier.size(17.dp),
        )
        Column(Modifier.weight(1f)) {
            VText(
                file.displayName,
                AiTypo.settingValue,
                color = VColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VText(readableSize(file.sizeBytes), AiTypo.listGroup, color = VColors.ink3)
        }
    }
}

/**
 * 「关联项目」页：多选 orenote 内部对象。
 *
 * ## 选中的显示在**顶部**，候选在下面
 *
 * 用户挑的时候最关心"我选了哪几个"。放在顶部固定、并给一个「清空」，
 * 不必滚到列表里去找哪条高亮（几十条候选时那很难找）。
 */
@Composable
private fun LinkPage(
    candidates: List<LinkedItem>,
    selected: List<LinkedItem>,
    onToggle: (LinkedItem) -> Unit,
    onClear: () -> Unit,
) {
    PageHeader(
        title = if (selected.isEmpty()) "关联项目" else "已选 ${selected.size} 项",
        onBack = onClear,
        backLabel = if (selected.isEmpty()) "返回" else "清空",
    )

    if (candidates.isEmpty()) {
        EmptyHint("还没有日程、待办或目标")
        return
    }

    val selectedIds = selected.map { it.id }.toSet()
    LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
        items(candidates, key = { it.id }) { item ->
            LinkRow(
                item = item,
                checked = item.id in selectedIds,
                onToggle = { onToggle(item) },
            )
        }
    }
}

/** 一个候选项：类型徽章 + 标题 + 勾选框。 */
@Composable
private fun LinkRow(item: LinkedItem, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .vPressable(scaleDown = 0.99f, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 类型徽章：一眼分清日程/待办/目标
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(VColors.surface2)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            VText(item.kindLabel, AiTypo.listGroup, color = VColors.ink2)
        }
        VText(
            item.title,
            AiTypo.settingValue,
            color = VColors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 勾选框：20dp 圆，选中填充 accent
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .background(if (checked) VColors.accent else VColors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Lucide.Check, "已选中", Modifier.size(13.dp), tint = VColors.surface)
            }
        }
    }
}

// ---------------------------------------------------------------- 公共件

/** 子页的头部：返回 + 标题。 */
@Composable
private fun PageHeader(title: String, onBack: () -> Unit, backLabel: String = "返回") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .vPressable(scaleDown = 0.94f, onClick = onBack)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(Lucide.ChevronLeft, backLabel, Modifier.size(15.dp), tint = VColors.accent)
            VText(backLabel, AiTypo.settingValue, color = VColors.accent)
        }
        Spacer(Modifier.width(8.dp))
        VText(title, AiTypo.settingValue, color = VColors.ink2)
    }
}

/** 一行菜单项：图标 + 标题 + 副标题。 */
@Composable
private fun MenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .vPressable(scaleDown = 0.99f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = VColors.ink2)
        Column(Modifier.weight(1f)) {
            /*
             * 文案与描述的**字重必须反过来**：文案是主、描述是次。
             *
             * ⚠️ 原来两行分别是 `settingValue`（13sp / 400）与 `listGroup`（13sp / 600），
             * 而 `listGroup` 是**分组标题**的 token（设计稿 `fs13 w600`）——
             * 于是描述比文案**还粗**，主次颠倒。
             * 用户 2026-10-04 报的就是这个：「选项描述的自重大于选项文案本身」。
             *
             * 现在：文案 15sp/Medium(500)、描述 13sp/Normal(400) —— 层级正确，
             * 而且字号也有了大小的区分（原来两行同为 13sp，只靠颜色区分太弱）。
             */
            VText(title, AiTypo.listItem, color = VColors.ink)
            VText(subtitle, AiTypo.settingValue, color = VColors.ink3, maxLines = 1)
        }
    }
}

/** 空态提示：一句话，**不给动作**（动作由调用方另给一个 MenuItem）。 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        VText(text, AiTypo.listGroup, color = VColors.ink3)
    }
}

/**
 * 文件大小的人话（`1.2 MB` / `340 KB`）。
 *
 * 与 `attachmentMetaText` 同一个口径 —— 不直接复用是因为那个还带类型，
 * 而这里同一行已经有图标表示类型了。
 */
private fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}

/**
 * 面板最大高度。
 *
 * 320dp ≈ 手机可用高度的 40%。再高就会把输入框顶出屏幕，
 * 而输入框是**悬浮**的（见 `ChatScreen` 的布局纪律），
 * 顶出去之后用户连"关掉菜单"都找不到。
 */
private val PANEL_MAX_HEIGHT = 320.dp

/** 列表区的高度上限（要减去头部 38dp 与上下内衬）。 */
private val LIST_MAX_HEIGHT = 270.dp
