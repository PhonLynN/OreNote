package com.phonlynn.oreplan.v2.ai

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.domain.ai.tool.ChangeField
import com.phonlynn.oreplan.domain.ai.tool.ChangeOperation
import com.phonlynn.oreplan.domain.ai.tool.ChangeRecord
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 待确认写操作的**界面状态与回调**，打包成一个参数往下传。
 *
 * 为什么打成一包：`ChatTurnRow` / `StreamingTurn` 的参数本来就不少，
 * 再散着加六七个会让每个调用点变成一堵墙。而这些字段**总是一起出现** ——
 * 有预览才有勾选，有勾选才有应用。
 *
 * 不加 `@Immutable`：里面装着 lambda，Compose 无法保证它们的稳定性，
 * 硬加注解等于对编译器撒谎。这点重组开销在这个界面上可以忽略。
 */
data class PendingActions(
    /** `callId → 记录列表`；浅色 = 还没算出来。 */
    val previews: Map<String, List<ChangeRecord>> = emptyMap(),
    /** `callId → 勾选保留的 record id`。缺省表示全选。 */
    val accepted: Map<String, Set<String>> = emptyMap(),
    /** `callId → 提问工具的问题列表`；与 [previews] 一样是懒算的。 */
    val questions: Map<String, List<com.phonlynn.oreplan.domain.ai.tool.AskQuestion>> = emptyMap(),
    /** `callId → (questionId → 作答)`。 */
    val answers: Map<String, Map<String, com.phonlynn.oreplan.domain.ai.tool.AskAnswer>> = emptyMap(),
    /** 界面第一次渲染待确认卡时调，让 ViewModel 把预览/问题算出来（幂等）。 */
    val onEnsure: (callId: String) -> Unit = {},
    val onToggle: (callId: String, recordId: String) -> Unit = { _, _ -> },
    /**
     * 应用**一批**调用。
     *
     * ## ⚠️ 为什么是列表而不是单个 callId
     *
     * 模型一条回复里可能发 N 个 `tool_calls`（批量改 40 张卡就是 40 个）。
     * 界面上它们合并成一张卡，**必须一次交给 ViewModel** ——
     * 若还是逐个调 `onApply(it)`，每次都会走一遍"执行 → 落库 → 再发一次请求"，
     * 40 张卡就是 40 次 AI 请求，比 40 次点击还糟。
     */
    val onApply: (callIds: List<String>) -> Unit = {},
    val onReject: (callIds: List<String>) -> Unit = {},
    /** 某道题的作答变了。 */
    val onAnswer: (callId: String, questionId: String, answer: com.phonlynn.oreplan.domain.ai.tool.AskAnswer) -> Unit =
        { _, _, _ -> },
    /** 提问卡的「提交」。 */
    val onSubmitAsk: (callId: String) -> Unit = {},
) {
    /** 某条记录当前勾选了哪些；没算出来时返回空集。 */
    fun acceptedFor(callId: String): Set<String> =
        accepted[callId] ?: previews[callId].orEmpty().map { it.id }.toSet()
}

/**
 * 变更预览卡（设计稿 `h6z5Uh`）。
 *
 * ## 用户为什么能放心点「应用」
 *
 * 这张卡的全部价值在于**用户在写入之前看得见要改什么**：
 *
 * · 每个字段列出**旧值 → 新值**（旧值玫红、新值绿），没变的字段也列出来但不着色 ——
 *   那是刻意的：一眼就能看出"只有时间和提醒改了"，不用自己逐个对比
 * · 每条记录一个勾选框，**取消了就不执行那一条**
 * · 顶部写着这次一共几条/几处改动
 *
 * ## 与工具卡的分工
 *
 * 待确认时渲染本卡（可交互）；用户做完决定之后，同一个位置换成
 * [ToolCallCard][com.phonlynn.oreplan.v2.ai.ToolCallCard]（只读的状态卡）。
 * 所以这里**不需要**画"已应用"的形态 —— 那种状态根本不会走到这里。
 *
 * ## ⚠️ 所有数值取自设计稿实测，不要凭印象调
 *
 * 见组件内各处的注释。要改先去量 `h6z5Uh`。
 *
 * @param accepted 已勾选保留的记录 id 集合（默认全选，见调用方）
 * @param onToggle 勾选/取消某条记录
 * @param onReject 驳回：**什么都不执行**
 * @param onConfirm 应用：只执行勾选的那些
 */
@Composable
fun ChangePreviewCard(
    records: List<ChangeRecord>,
    accepted: Set<String>,
    onToggle: (String) -> Unit,
    onReject: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 卡片标题。默认「变更预览」（设计稿 `h6z5Uh` 的原文）。
     *
     * 批量改动的合并卡会传「批量改动（40 项）」—— 那让用户一眼知道
     * 这次不是改一条，而是一批。
     */
    title: String = "变更预览",
    /**
     * 记录区的高度上限。null = 不限（按内容自然撑开）。
     *
     * 超过约 3 条时传一个值，记录区**内部滚动** —— 否则 40 条记录会把
     * 底部的「应用」按钮推到几屏之外，用户根本点不到。
     */
    maxRecordsHeight: androidx.compose.ui.unit.Dp? = null,
    /**
     * 每条记录前面加序号（`1.` `2.` …）。
     *
     * 批量改动时开 —— 40 条记录光看标题仍然难定位，有序号至少能数。
     * 单条变更时不开（一条记录还标个「1.」是多余的）。
     */
    showIndex: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(VColors.surface)
            .border(1.dp, VColors.line, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Head(records, title)
        CardDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (maxRecordsHeight != null) {
                        Modifier
                            .heightIn(max = maxRecordsHeight)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            records.forEachIndexed { index, record ->
                if (index > 0) CardDivider()
                RecordRow(
                    record = record,
                    checked = record.id in accepted,
                    onToggle = { onToggle(record.id) },
                    index = if (showIndex) index + 1 else null,
                )
            }
        }

        CardDivider()
        Footer(
            count = records.count { it.id in accepted },
            onReject = onReject,
            onConfirm = onConfirm,
        )
    }
}

/** 卡片头：徽标 + 标题 + 右侧状态。设计稿 `Diff Head 322x26`。 */
@Composable
private fun Head(records: List<ChangeRecord>, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Badge 26x26 $amber-soft r9 —— 琥珀而不是绿：这一卡还没生效
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(VColors.amberSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.GitBranch, null, Modifier.size(14.dp), tint = VColors.amber)
            }
            // 标题：单条变更时是「变更预览」，批量合并时是「批量改动（40 项）」
            VText(title, VTypo.body.copy(fontWeight = FontWeight.Medium), color = VColors.ink)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(Lucide.Clock, null, Modifier.size(14.dp), tint = VColors.amber)
            VText(
                "待确认",
                VTypo.caption.copy(fontWeight = FontWeight.Medium),
                color = VColors.amber,
            )
        }
    }
    // 记录数放在头下面单独一行 —— 设计稿把汇总写在 Head 与 Records 之间
    if (records.isNotEmpty()) {
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * 一条记录：头（对象名 + 两个胶囊 + 勾选框）＋ 若干字段。设计稿 `Record gap10`。
 *
 * ## ⚠️ 头部的**对象名**是用户报出来的缺失
 *
 * 原话：「这几十个任务卡片没有半点索引，我都不知道自己改了啥，
 * 至少显示个标题，没有标题显示个前十个字也行啊，总之要写明操作对象」。
 *
 * 原来头部只有两个胶囊：
 *
 * ```
 * [卡片] [修改]                    ☑
 * ```
 *
 * 一屏十几条这样的记录，**没有任何办法分辨哪条是哪张卡** ✗
 *
 * ## 版式
 *
 * ```
 * [卡片] [修改]  纳维-斯托克斯方程          ☑
 * └─ 类型 + 操作 ─┘└──── 操作对象 ────┘
 * ```
 *
 * 对象名放在**两个胶囊之后**（而不是之前）：胶囊是分类标签，位置固定；
 * 对象名长短不一，放在后面才能从左往右对齐地扫。
 * 它用 `weight(1f)` 占住中间，超长时自动省略 —— 勾选框始终在右边缘。
 */
@Composable
private fun RecordRow(
    record: ChangeRecord,
    checked: Boolean,
    onToggle: () -> Unit,
    /** 序号（从 1 起）。null = 不显示 —— 单条变更加序号是多余的。 */
    index: Int? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /*
             * 序号：批量改动时开。
             *
             * 用户口径：「这几十个任务卡片没有半点索引……至少显示个标题」。
             * 标题由 `subject` 提供，序号解决另一半 —— 40 条记录光看标题
             * 仍然难定位，有序号至少能数到"第 12 条"。
             */
            if (index != null) {
                Box(
                    modifier = Modifier.width(22.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    VText(
                        "$index",
                        VTypo.caption.copy(fontWeight = FontWeight.Medium),
                        color = VColors.ink3,
                    )
                }
            }

            CardPill(record.typeLabel, VColors.surface2, VColors.ink2)
            Spacer(Modifier.width(8.dp))
            val (label, soft, strong) = record.operation.colors()
            CardPill(record.operation.label, soft, strong)

            if (record.subject.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                /*
                 * 对象名：**中等字重**，比正文稍重一点 —— 它是这一行的"主语"，
                 * 扫列表时眼睛先落在它上面。超长自动省略（一行放不下就砍尾巴），
                 * 不换行 —— 换行会让整个列表参差不齐。
                 */
                VText(
                    record.subject,
                    VTypo.caption.copy(fontWeight = FontWeight.Medium),
                    color = VColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.width(8.dp))
            Check(checked = checked, onToggle = onToggle)
        }

        record.fields.forEach { field ->
            FieldRow(field)
        }
    }
}

/** 操作胶囊的配色。设计稿：修改=琥珀、新增=绿、删除=玫红。 */
private fun ChangeOperation.colors(): Triple<String, Color, Color> = when (this) {
    ChangeOperation.UPDATE -> Triple(label, VColors.amberSoft, VColors.amber)
    ChangeOperation.CREATE -> Triple(label, VColors.accentSoft, VColors.accent)
    ChangeOperation.DELETE -> Triple(label, VColors.roseSoft, VColors.rose)
}

/**
 * 勾选框。设计稿 `22x22 r8`，选中是 accent 底 + 白勾。
 *
 * ⚠️ **未选中态设计稿没画**（三处都是选中态）。这里用白底 + `$line` 描边 ——
 * 白底在 `$surface` 上会消失，所以描边是必需的，不是装饰。
 *
 * 整条记录头都可以点，而不是只点那 22px —— 手指点不准那么小的目标。
 */
@Composable
private fun Check(checked: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) VColors.accent else VColors.surface)
            .border(
                width = if (checked) 0.dp else 1.dp,
                color = if (checked) Color.Transparent else VColors.line,
                shape = RoundedCornerShape(8.dp),
            )
            .vPressable(scaleDown = 0.9f, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Lucide.Check, null, Modifier.size(14.dp), tint = Color.White)
        }
    }
}

/**
 * 一个字段：左侧标签（固定 52 宽），右侧一列值。
 *
 * 设计稿 `Field gap10`，`Label Wrap 52x24 pad[7,0,0,0]`，`Values gap5`。
 */
@Composable
private fun FieldRow(field: ChangeField) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .padding(top = 7.dp),
        ) {
            VText(field.label, VTypo.caption, color = VColors.ink3)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when {
                /*
                 * 值没变：无色底、无图标。
                 *
                 * 设计稿刻意**也把它列出来** —— 用户一眼就能看出"只有时间和提醒改了"，
                 * 不用自己逐字段对比。这是这张卡的可用性关键，不要"优化"掉。
                 */
                field.unchanged -> ValueChip(field.old.orEmpty(), VColors.bg, VColors.ink, null)

                // 新增的字段：没有旧值
                field.old == null -> ValueChip(
                    field.new.orEmpty(), VColors.accentSoft, VColors.accent, Lucide.Plus,
                )

                // 删除的字段：没有新值
                field.new == null -> ValueChip(
                    field.old, VColors.roseSoft, VColors.rose, Lucide.Minus,
                )

                else -> {
                    ValueChip(field.old, VColors.roseSoft, VColors.rose, Lucide.Minus)
                    ValueChip(
                        field.new, VColors.accentSoft, VColors.accent, Lucide.Plus,
                        emphasize = true,
                    )
                }
            }
        }
    }
}

/**
 * 一格值。设计稿 `260x30 r9 pad[6,9]`，左侧一个 14x18 的标记位（图标居中）。
 *
 * 新值用 500 字重、旧值用 normal —— 一眼分清"要从什么变成什么"。
 */
@Composable
private fun ValueChip(
    text: String,
    background: Color,
    content: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标记位即使没有图标也**占住宽度**，否则同一字段的旧值/新值会左右错开
        Box(Modifier.size(width = 14.dp, height = 18.dp), contentAlignment = Alignment.Center) {
            icon?.let { Icon(it, null, Modifier.size(12.dp), tint = content) }
        }
        VText(
            text,
            VTypo.caption.copy(
                fontSize = 13.sp,
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
            ),
            color = content,
        )
    }
}

/**
 * 底部：驳回 + 应用。
 *
 * 设计稿 `Footer 322x46 gap10`：`Reject 81x46 $bg r13`、`Confirm 231x46 $accent r13`。
 * 主按钮更宽是对的 —— 它是绝大多数情况下要按的那个。
 *
 * ## ⚠️ 主按钮**不随"含删除"变色**
 *
 * 我一度想"选中项里有删除就把按钮变玫红"，用户明确否了：
 * 「含删除时的主按钮不需要，就按照现在的设计来」。删除的危险性由那条记录上
 * 玫红的「删除」胶囊表达，不靠改主按钮颜色。
 */
@Composable
private fun Footer(count: Int, onReject: () -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .width(81.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(VColors.bg)
                .vPressable(scaleDown = 0.97f, onClick = onReject),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Lucide.X, null, Modifier.size(15.dp), tint = VColors.ink3)
                VText("驳回", VTypo.body.copy(fontWeight = FontWeight.SemiBold), color = VColors.ink2)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (count > 0) VColors.accent else VColors.surface2)
                // 一项都没勾时按钮变灰但**仍然可点**：点下去等于全部驳回，
                // 与「驳回」结果一致，不需要单独的禁用态
                .vPressable(scaleDown = 0.97f, onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) {
            VText(
                if (count > 0) "应用选中的 $count 项" else "没有选中项",
                VTypo.body.copy(fontWeight = FontWeight.Bold),
                color = if (count > 0) Color.White else VColors.ink3,
            )
        }
    }
}

/** 一条细分割线。设计稿里两张卡的内部线都是 `1px $line`。 */
@Composable
internal fun CardDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(VColors.line),
    )
}

/**
 * 小胶囊。设计稿 `38x24 r7 pad[4,8]`，文字 `fs11/600`。
 *
 * 变更预览卡（数据种类 / 操作类型）与提问卡（题号）都用它。
 */
@Composable
internal fun CardPill(text: String, background: Color, content: Color) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            text,
            VTypo.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = content,
        )
    }
}
