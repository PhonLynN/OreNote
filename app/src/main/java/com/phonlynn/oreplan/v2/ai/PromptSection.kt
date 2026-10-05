package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.BodyFont
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 系统提示词：**单选**一组 + 就地编辑 + 添加。
 *
 * ## 结构（逐项照设计稿 `EBDqu` 实测）
 *
 * ```
 * Prompt Groups          圆角 16，surface 底，line 描边 1，纵向
 *   Group  <名称>         内 pad 13×14，gap 12，圆角 12（高亮只覆盖这一块）
 *                          选中：底 accent-soft、名字 accent
 *                          未选：底透明、名字 ink
 *     Group Name           14sp / 500
 *     Radio                20×20 圆；选中 fill accent + 内含 8×8 白点
 *                          未选 fill 透明 + line 描边 1
 *   Prompt Editor         高 200  ← 只有选中的那一组展开
 *   Divider               高 1，水平 pad 14
 *   Group  <名称>         …
 *   Add Group             pad 14，gap 8：＋ 16 + 「添加提示词组」13sp / 500 accent
 * ```
 *
 * ## 两处必须说清的口径
 *
 * 1. **单选**。设计稿原 Note 写「可叠加多组」但控件是 Radio；
 *    用户澄清「提示词就是单选」。切换 = 替换，不是叠加。
 *
 * 2. **删除**。设计稿里**没有**删除入口，那是我自己加的（还只让自定义组可删）。
 *    用户要求：除「系统默认」外，**内置的另外两组也要能删**，且能自行添加。
 *    删除入口因此挂在非系统默认的每一行上，靠行尾、Radio 左侧。
 */
@Composable
fun PromptSection(
    groups: List<PromptGroup>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(VColors.surface)
            .border(1.dp, VColors.line, RoundedCornerShape(16.dp)),
    ) {
        groups.forEachIndexed { index, group ->
            if (index > 0) PromptDivider()

            PromptGroupRow(
                group = group,
                selected = group.id == selectedId,
                onSelect = { onSelect(group.id) },
                onDelete = { onDelete(group.id) },
            )

            // 只有选中的那一组展开编辑框（设计稿里编辑框就在选中行下方）
            if (group.id == selectedId) {
                PromptEditor(
                    text = group.text,
                    onTextChange = { onEdit(group.id, it) },
                )
            }
        }

        if (groups.isNotEmpty()) PromptDivider()

        AddGroupRow(onAdd)
    }
}

/**
 * 一组提示词。
 *
 * [builtIn] 只表示「内置三组」，**不再等于不可删** ——
 * 除「系统默认」外的每一组都可删（用户口径）。
 */
data class PromptGroup(
    val id: String,
    val name: String,
    val text: String,
    /** 内置组：不可改名，删除后也无法从界面恢复默认文本。 */
    val builtIn: Boolean = false,
) {
    /** 「系统默认」是唯一的兜底项，永远保留 —— 其余（含内置）都可删。 */
    val deletable: Boolean get() = id != com.phonlynn.oreplan.domain.ai.AiSettings.SYSTEM_DEFAULT
}

/**
 * 组行。
 *
 * 高度由 **pad 13×14 + 内容 20** 得出 46，不写死 `height(46.dp)` ——
 * 写死再叠 padding 会把内容挤到 20dp 以下。
 *
 * 顺序：先 pad 再 clip/background，这样 accent-soft 高亮块
 * 正好是「圆角 12 的内嵌块」，而不是整行铺满（设计稿就是内嵌块）。
 */
@Composable
private fun PromptGroupRow(
    group: PromptGroup,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) VColors.accentSoft else Color.Transparent)
            .vPressable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(
            group.name,
            AiTypo.promptGroupName,
            color = if (selected) VColors.accent else VColors.ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        /*
         * 删除入口：设计稿里没有，是用户要求加的。
         * 「系统默认」不给 —— 它是唯一的兜底项。
         */
        if (group.deletable) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .vPressable(scaleDown = 0.85f, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = "删除「${group.name}」",
                    tint = VColors.ink3,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        PromptRadio(selected)
    }
}

/** Radio：20×20。选中 = fill accent + 内含 8×8 白点；未选 = 透明 + line 描边 1。 */
@Composable
private fun PromptRadio(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(VColors.accent)
                } else {
                    Modifier.border(1.dp, VColors.line, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * 就地编辑框：高 200，**自成一个滚动区**。
 *
 * 设计稿 `z7Tix4`：`height=200 clip=true`，正文起点 `x=16 y=16`、宽 `310`，
 * 右侧另有一颗 `y6pJg` 滚动条（宽 4，位于 x=342）——
 * 也就是说这**不是**一个会跟着页面长的输入框，而是一块固定高、内部独立滚动的区域。
 *
 * ## 上一版的三个 bug（用户实测反馈）
 *
 * 1. **点进去光标定位错乱**：外层 `Box` 定高 200，但内层 `BasicTextField`
 *    没有高度约束，于是它按内容长高、被外层裁掉，光标落在"可见区之外"，
 *    视觉上就是定位漂了。正确做法是让**输入框本身**就是那个 200 高的容器。
 *
 * 2. **⚠️ 框里滚不动**（用户报「没有办法正常上下滚动文字」）——
 *    我加过一版 `nestedScroll`，它才是病根，已删。
 *
 *    当时写的是
 *    `onPreScroll { available -> if (还能滚) available else Zero }`，
 *    想把位移"抢"过来。但 `onPreScroll` 的语义是
 *    「**我在你（滚动方）之前吃掉这么多**」，返回非零 = 内侧拿到的剩余量是 0
 *    → **文字一下都滚不动**，同时外层也被"消费"了，两边都不动。
 *
 * 3. **⚠️ 拖到边缘会带着整页一起滚**（用户前后报过两次）——
 *    这是**真的**会发生的：Compose 的嵌套滚动在内侧滚到头之后，
 *    会把剩余位移交给外层滚动容器（也就是整页）。用户不想要这个交接。
 *
 * ## 正确做法：只吃"剩下的"，绝不吃"该给内侧的"
 *
 * 用 `onPostScroll` 而不是 `onPreScroll` —— 两者的区别是全部关键：
 *
 * ```
 * onPreScroll  (available)  →  内侧滚动之前。返回非零 = 从内侧嘴里抢食 → bug 2
 * onPostScroll (consumed, available) → 内侧滚完之后。available 是它吃不下的余量，
 *                                      返回它 = 页面拿到 0 → 修掉 bug 3，且不影响内侧
 * ```
 *
 * 于是行为变成：**框里能滚就滚文字，滚到头也不带动页面** —— 编辑框是个"手势孤岛"，
 * 在框里怎么拖都不会把设置页拽走。要滚页面就在框外面拖。
 *
 * ⚠️ 别再改回 `onPreScroll`。那正是把"文字滚不动"重新引入的方式。
 *
 * 正文 inset 取设计稿实测的 16（不是 14），右侧给它留出滚动条的位置。
 */
@Composable
private fun PromptEditor(text: String, onTextChange: (String) -> Unit) {
    val scrollState = rememberScrollState()
    var contentPx by remember { mutableIntStateOf(0) }
    var viewportPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            /*
             * 手势孤岛：内侧（输入框）滚完剩下的位移在这里被吃掉，页面拿不到。
             * **必须是 onPostScroll** —— 理由见上面的对照，改成 onPreScroll 会让
             * 框里的文字彻底滚不动。
             */
            .nestedScroll(object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = available
            })
            .clipToBounds(),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 24.dp, top = 16.dp, bottom = 12.dp)
                .verticalScroll(scrollState)
                .onSizeChanged { viewportPx = it.height },
            textStyle = TextStyle(
                fontSize = AiTypo.promptBody.fontSize,
                fontFamily = BodyFont,
                color = VColors.ink,
                lineHeight = AiTypo.promptBody.lineHeight ?: AiTypo.promptBody.fontSize,
            ),
            cursorBrush = SolidColor(VColors.accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().onSizeChanged { contentPx = it.height }) {
                    if (text.isEmpty()) {
                        VText("在这里写提示词…", AiTypo.promptBody, color = VColors.ink3)
                    }
                    inner()
                }
            },
        )

        Box(Modifier.align(Alignment.TopEnd).padding(top = 0.dp)) {
            EditorScrollbar(scrollState, contentPx, viewportPx)
        }
    }
}

/** 滚动条：宽 4，圆角 2。设计稿 `y6pJg`（x=342，即距卡片右缘 8）。 */
@Composable
private fun EditorScrollbar(scrollState: ScrollState, contentPx: Int, viewportPx: Int) {
    if (contentPx <= viewportPx || viewportPx <= 0) return

    val track = 172f // 200 - 16(上) - 12(下)
    val fraction = viewportPx.toFloat() / contentPx.toFloat()
    val thumb = (track * fraction).coerceIn(24f, track)
    val maxScroll = (contentPx - viewportPx).toFloat()
    val progress = if (maxScroll <= 0f) 0f else scrollState.value / maxScroll

    Box(
        Modifier
            .width(4.dp)
            .height(thumb.dp)
            .offset(y = (16f + (track - thumb) * progress).dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFFC2CCC6)),
    )
}

/**
 * 添加一组：pad 14，gap 8。
 *
 * **不设固定高度** —— 设计稿 `DRrGh` 只有 padding，高度由内容撑开。
 * 上一版写死 `height(47.dp)` 是我编的。
 */
@Composable
private fun AddGroupRow(onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .vPressable(onClick = onAdd)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.Plus, contentDescription = null, tint = VColors.accent, modifier = Modifier.size(16.dp))
        VText("添加提示词组", AiTypo.promptAddLabel, color = VColors.accent)
    }
}

/** 分隔线：高 1，水平 pad 14。 */
@Composable
private fun PromptDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(VColors.line),
    )
}
