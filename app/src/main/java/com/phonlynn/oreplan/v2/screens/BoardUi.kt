package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.domain.model.AutoPinLogic
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.components.VAttachmentThumb
import androidx.compose.ui.text.style.TextOverflow
import com.phonlynn.oreplan.v2.richtext.RichTextFadeMask
import com.phonlynn.oreplan.v2.richtext.VRichText
import com.phonlynn.oreplan.core.rt.plainTextOf
import com.phonlynn.oreplan.v2.components.attachmentAspectRatio
import androidx.compose.ui.layout.ContentScale
import com.phonlynn.oreplan.domain.model.BoardColors
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.NumFont
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

// ---------------------------------------------------------------- 颜色映射

/** 卡片颜色键 → Compose 色。null/white=白卡，其余 pastel；自定义 #RRGGBB 直接解析。 */
/**
 * 卡片标题/正文字号档 → 实际字号。
 *
 * 只作用于**标题与正文**（用户 2026-09-19 明确的调节范围）；
 * 日期、标签、待办等沿用各自固定的字号，不随此档变化 ——
 * 它们属于卡片上的元信息，跟着放大反而会破坏卡片的层次。
 *
 * 基准按原实现：标题 15sp、正文 14sp（紧凑模式正文 13sp）。
 */
/**
 * 主页卡片正文最多显示几行。
 *
 * 用户 2026-09-27：至少六行。取 8：正文被截断时底部那块两行高的渐隐遮罩会压住最后
 * 两行，于是**清晰可读的仍有六行左右**，同时露出「下面还有」的提示。
 */
private const val CardBodyLineLimit = 8

data class CardFontSizes(val title: TextUnit, val body: TextUnit)

fun cardFontSizes(sizeKey: String?, compact: Boolean): CardFontSizes {
    // 基准 = 原实现的字号（标题 15sp、正文 14sp；紧凑正文 13sp）。
    // 但用户反馈原字号偏小，所以**默认档（medium）在原基础上各 +2sp**：
    //   小 = 原样（15 / 14）
    //   中 = 默认（17 / 16）
    //   大 = 再 +2（19 / 18）
    val bodySmall = if (compact) 13f else 14f
    return when (sizeKey) {
        // 用户 2026-09-19（第二次）：中号再小一点点（17/16 → 16/15）。
        // 小 = 原样（15 / 14）
        // 中 = 默认（16 / 15）
        // 大 = 再 +2（19 / 18）
        "small" -> CardFontSizes(title = 15.sp, body = bodySmall.sp)
        "large" -> CardFontSizes(title = 19.sp, body = (bodySmall + 4f).sp)
        // medium（默认）
        else -> CardFontSizes(title = 16.sp, body = (bodySmall + 1f).sp)
    }
}

fun boardCardColor(color: String?): Color = when (color) {
    null, BoardColors.WHITE -> VColors.surface
    BoardColors.ACCENT -> VColors.accentSoft
    BoardColors.AMBER -> VColors.amberSoft
    BoardColors.LILAC -> VColors.lilacSoft
    BoardColors.ROSE -> VColors.roseSoft
    BoardColors.GREY -> VColors.surface2
    else -> parseHexColor(color) ?: VColors.surface
}

private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank() || !hex.startsWith("#")) return null
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

/** 是否「浅色 pastel 底」——决定卡片上徽章的底色用白色半透明还是 bg。 */
fun boardIsSoftColor(color: String?): Boolean = when (color) {
    BoardColors.ACCENT, BoardColors.AMBER, BoardColors.LILAC, BoardColors.ROSE -> true
    null, BoardColors.WHITE, BoardColors.GREY -> false
    else -> true // 自定义色按浅色处理
}

/** 卡片颜色是否需要描边（白卡/灰卡带 line 描边）。 */
fun boardColorNeedsBorder(color: String?): Boolean = when (color) {
    null, BoardColors.WHITE, BoardColors.GREY -> true
    else -> false
}

// ---------------------------------------------------------------- 日期 / 标签

/**
 * 卡片颜色的中文名（用于搜索命中）。
 * 预设色返回对应中文；自定义色（#RRGGBB）返回「自定义」，让搜「自定义」能筛出它们。
 */
fun boardColorLabel(color: String?): String = when (color) {
    null -> "白色 默认"
    com.phonlynn.oreplan.domain.model.BoardColors.WHITE -> "白色 默认"
    com.phonlynn.oreplan.domain.model.BoardColors.ACCENT -> "绿色 强调"
    com.phonlynn.oreplan.domain.model.BoardColors.AMBER -> "琥珀 橙黄"
    com.phonlynn.oreplan.domain.model.BoardColors.LILAC -> "紫色 淡紫"
    com.phonlynn.oreplan.domain.model.BoardColors.ROSE -> "玫红 粉色"
    com.phonlynn.oreplan.domain.model.BoardColors.GREY -> "灰色"
    else -> "自定义"
}

fun boardDateText(instant: Instant): String {
    val z = instant.atZone(ZoneId.systemDefault())
    return "${z.monthValue}月${z.dayOfMonth}日"
}

fun boardDateWeekText(instant: Instant): String {
    val z = instant.atZone(ZoneId.systemDefault())
    return "${boardDateText(instant)} · ${weekdayLabel(z.dayOfWeek)}"
}

/** 卡片顶部用：带**年份**与**星期**的完整日期，如「2026年9月18日 · 周四」。 */
fun boardFullDateWeekText(instant: Instant): String {
    val z = instant.atZone(ZoneId.systemDefault())
    return "${z.year}年${z.monthValue}月${z.dayOfMonth}日 · ${weekdayLabel(z.dayOfWeek)}"
}

fun weekdayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/** 取卡片第一个标签的完整路径，如「学习 › 课程」；无标签返回空串。 */
fun tagPathText(tagIds: List<String>, tags: List<BoardTag>): String {
    val byId = tags.associateBy { it.id }
    val first = tagIds.firstOrNull()?.let { byId[it] } ?: return ""
    val parent = first.parentId?.let { byId[it]?.name }
    return if (parent.isNullOrBlank()) first.name else "$parent › ${first.name}"
}

/** 所有标签按 parentId 构建映射（父标签 id -> 子标签列表）。 */
fun tagChildrenMap(tags: List<BoardTag>): Map<String?, List<BoardTag>> =
    tags.groupBy { it.parentId }

/**
 * 从某标签沿 parentId 向上回到根，返回 [根 … 当前] 的链（用于标签栏的面包屑胶囊）。
 * 标签不存在或成环时安全返回已有部分。
 */
fun tagPathChain(tags: List<BoardTag>, tagId: String?): List<BoardTag> {
    if (tagId == null) return emptyList()
    val byId = tags.associateBy { it.id }
    val chain = ArrayDeque<BoardTag>()
    var cur = byId[tagId]
    var guard = 0
    while (cur != null && guard++ < 32) {
        chain.addFirst(cur)
        cur = cur.parentId?.let { byId[it] }
    }
    return chain.toList()
}

/** 某标签及其全部后代的 id 集合（用于「按标签筛选 + 计数」）。 */
fun tagWithDescendants(tagId: String, tags: List<BoardTag>): Set<String> {
    val children = tagChildrenMap(tags)
    val result = mutableSetOf(tagId)
    fun walk(id: String) {
        children[id].orEmpty().forEach { child ->
            if (result.add(child.id)) walk(child.id)
        }
    }
    walk(tagId)
    return result
}

/**
 * 所有开启了「不在全部中显示」的标签**及其全部后代**的 id 集合。
 * 用于「全部」筛选时排除这些标签下的卡片。
 */
fun tagWithHideFromAll(tags: List<BoardTag>): Set<String> {
    val result = mutableSetOf<String>()
    tags.filter { it.hideFromAll }.forEach { result += tagWithDescendants(it.id, tags) }
    return result
}

// ---------------------------------------------------------------- 附件 / 勾选 / 锁

/**
 * 附件数量气泡：「n 附件」。
 *
 * 曾经和「类型气泡」（`BoardTypeBadge`）并排，两者同形（r8、同内边距）。
 * 用户 2026-10-03 确认白板只有一种卡片、没有类型可选可显，
 * 于是类型徽章连同它的样式表一起删了（详见 `BoardCardType` 的说明）。
 */
@Composable
fun AttachmentCountBadge(count: Int) {
    Row(
        Modifier
            .background(VColors.bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Paperclip, contentDescription = null, modifier = Modifier.size(11.dp), tint = VColors.ink3)
        VText("$count 附件", VTypo.caption, color = VColors.ink3, maxLines = 1)
    }
}

/** 保密锁标（11 大小）。 */
@Composable
fun BoardLockMark() {
    Icon(Lucide.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = VColors.ink3)
}

/** 待办清单单行：13 圆角勾选框 + 文本。onToggle 为空时只读。 */
@Composable
fun BoardCheckItem(
    text: String,
    done: Boolean,
    onToggle: (() -> Unit)? = null,
    /** 字号/勾选框倍数。卡墙用 1；聚焦全屏形态传更大值（整页宽度下 12sp 偏小）。 */
    scale: Float = 1f,
    /** 未完成项的文字颜色覆盖（全屏形态用更深的 ink，避免整页下显得太浅）。 */
    textColor: Color? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
    ) {
        Box(
            Modifier
                .size(13.dp * scale)
                .background(if (done) VColors.accent else Color.Transparent, RoundedCornerShape(4.dp))
                .border(1.2.dp, if (done) Color.Transparent else VColors.ink3, RoundedCornerShape(4.dp))
                .then(if (onToggle != null) Modifier.vPressable(scaleDown = 0.8f, onClick = onToggle) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(9.dp * scale), tint = Color.White)
        }
        VText(
            text,
            VTypo.caption12.copy(fontSize = 12.sp * scale),
            color = if (done) VColors.ink3 else (textColor ?: VColors.ink2),
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- 卡片瓦片

/** 瀑布流里的卡片瓦片（白板主页共用）。 */
@Composable
fun BoardCardTile(
    card: BoardCard,
    todos: List<BoardTodoItem>,
    tagChain: List<BoardTag>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    compact: Boolean = false,
    /** 标题/正文字号档（由「卡片字号」设置决定）。只影响这两个文本。 */
    fontSizes: CardFontSizes = CardFontSizes(16.sp, 15.sp),
    onDoubleClick: (() -> Unit)? = null,
    /** 开始拖动：回传指针此刻在 root 坐标系里的位置，作为「抓取点」基准。 */
    onDragStart: ((pointerInRoot: androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    /** 拖动中回传指针在 root 坐标系里的位置（不是增量）：二维跟手要用绝对坐标。 */
    onDrag: ((pointerInRoot: androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    /**
     * 长按**没有移动**就松手时调用 —— 用户的意图是「取走这张卡的文本」，不是拖动。
     *
     * 为什么需要它：长按成立时卡片立刻被「抓起来」（不等手指移动，见下方手势注释），
     * 于是「只想复制」和「想拖动」在松手之前无法区分。松手时用位移是否超过系统
     * touchSlop 来判定：没超过就是复制。
     *
     * 为 null 时该手势什么也不做（但**仍然**会阻止误触发点击）——
     * 这比旧行为好：旧代码不消费抬手事件，于是长按不动松手会被旁边的 tap 通道
     * 当成一次点击，意外打开卡片（问题 #13）。
     */
    onCopyText: (() -> Unit)? = null,
    /**
     * 是否正在被拖动。只影响视觉（浮起放大/投影/倾斜归零），
     * **不影响位置** —— 位置由 `MasonryBoard` 的动画层统一驱动。
     * 它一次拖动只变两次，不会引起高频重组。
     */
    dragging: Boolean = false,
    /** 本卡片的图片附件（按时间排序）。为空时不渲染图片区。 */
    images: List<Attachment> = emptyList(),
    /** 该卡的**全部**附件数（含非图片）——用于类型气泡旁的「n 附件」气泡。 */
    attachmentCount: Int = images.size,
    /** 图片展示样式："fill"=横向填充（1 张大图）/ "grid"=缩略网格（一排 3 个）。 */
    /** 是否横向填充（已废弃：主页现在统一三栏最多 3 张）。保留参数仅为兼容调用方。 */
    imageLayout: String = "fill",
    /** 是否为整行（宽）卡片。宽卡 + 单张图时用「维持原比例、靠左缩小」的画法。 */
    isWideCard: Boolean = false,
    /** 附件存储（图片缩略图需要它按路径解码）。 */
    storage: AttachmentStorage? = null,
    /**
     * 正文变更回调（主页直接勾选正文里的复选框用）。
     * 为 null 或卡片保密时，复选框不可点。
     */
    onBodyChange: ((String) -> Unit)? = null,
) {
    val color = boardCardColor(card.color)
    val soft = boardIsSoftColor(card.color)
    // 本卡片左上角在 root 坐标系里的位置：拖动时用它把节点内坐标换算成绝对坐标。
    var nodeOffsetInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // 倾斜角走动画（2026-09-18 修）：拖动中角度归零、松手回原角度都必須连续过渡。
    // 之前是 `rotationZ = if (dragging) 0f else rotation` 硬切，
    // 松手那一刻角度瞬时跳回，看起来就是「闪了一下」。
    val animatedRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragging) 0f else rotation,
        animationSpec = VMotion.settle(),
        label = "cardRotation",
    )
    // 拖动中「浮起来」的放大也走动画，避免抓起的瞬间尺寸瞬变。
    val dragLift by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragging) 1.04f else 1f,
        animationSpec = VMotion.snappy(),
        label = "cardDragLift",
    )
    // 「浮起」时的触觉反馈（问题 #12）。取在组合层而不是手势里：
    // LocalHapticFeedback 是 CompositionLocal，只能在 @Composable 里读。
    val haptic = LocalHapticFeedback.current
    val tileModifier = modifier
        .onGloballyPositioned { coords -> nodeOffsetInRoot = coords.positionInRoot() }
        .graphicsLayer {
            rotationZ = animatedRotation
            scaleX = dragLift
            scaleY = dragLift
        }
        .then(if (dragging) Modifier.shadow(14.dp, RoundedCornerShape(16.dp), spotColor = Color(0x55101613), ambientColor = Color(0x55101613)) else Modifier)
        // 被拖起的卡片抬到最上层，否则会被后面的卡片盖住，不像「抓在手上」。
        .then(if (dragging) Modifier.zIndex(1f) else Modifier)
        .clip(RoundedCornerShape(16.dp))
        .background(color, RoundedCornerShape(16.dp))
        // 不再给卡片画外轮廓线（用户 2026-09-19）：
        // 白卡/浅色卡原先会描一圈 VColors.line，在卡片墙上显得像一个个“框”。
        .let { base ->
            if (onDragStart != null && onDrag != null && onDragEnd != null) {
                // 长按抓取用手写手势循环，而不是 detectDragGesturesAfterLongPress：
                //  1. 那条 API 把「长按」与「拖动」拆成两次判定，和 LazyColumn 的滚动手势
                //     叠在一起时容易被抢走，表现就是「长按后拖不动」；
                //  2. 我们需要「长按成立那一刻就把卡片抓起来」（不等手指移动），
                //     并且拖动期间完全接管事件（阻止列表滚动），这些都要自己控制时序。
                //
                // 流程：等按下 → 400ms 内若手指抬起或明显移动就作罢 → 否则长按成立
                //      → 抓起卡片、回调 onDragStart → 进入拖动循环（消费事件）
                //      → 抬手结束。
                // 手势分成两条独立通道，互不干扰，也各自独立存活：
                //  · "tap"  —— 点击（含长按不动时的取消判断由系统处理）
                //  · "drag" —— 长按后抓取拖动
                //
                // 早期版本把两者写在一个 awaitPointerEventScope 循环里，并用
                // return@awaitPointerEventScope 结束「滑动」分支——那会永久退出该手势协程，
                // 导致这张卡片之后再也不能响应任何手势（表现为「部分点击失效」）。
                base
                    .pointerInput(card.id, "tap") {
                        // onLongPress 必须给一个（哪怕是空的）：
                        // detectTapGestures 的语义是「没有长按处理器时，长按松手也算点击」。
                        // 不给它，长按不动松手就会走到 onTap → 意外打开卡片（问题 #13）。
                        // 拖动通道那边也会消费抬手事件，两处一起保证「长按永远不是点击」，
                        // 这样即使将来修饰符顺序变了，行为也不会退化。
                        detectTapGestures(
                            onTap = { onClick() },
                            onLongPress = { /* 长按由拖动通道处理，这里刻意什么都不做 */ },
                        )
                    }
                    .pointerInput(card.id, "drag") {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val pointerId = down.id
                                val downInRoot = down.position + nodeOffsetInRoot

                                // awaitLongPressOrCancellation：等到「长按成立」才返回；
                                // 期间若手指抬起或移动超过系统阈值（比如想滚动列表），返回 null。
                                // 正好满足需求：不动 → 抓起来；滑动 → 让给 LazyColumn。
                                val longPress = awaitLongPressOrCancellation(pointerId)
                                if (longPress == null) continue

                                // 长按成立：先给一次触觉反馈，再把卡片抓起来（问题 #12）。
                                // 「浮起」是没有任何视觉预告的动作 —— 用户不会预判卡片会飞起来，
                                // 震动是这一刻唯一的即时确认。
                                //
                                // ⚠️ 时机是「**进入长按状态**」这一下，**不是松手时**。
                                // 用户 2026-09-30 专门澄清过（此前文档误写成「长按抬起那一刻」，
                                // 字面意思变成松手时震动，与需求正好相反）。
                                // 位置就该在 awaitLongPressOrCancellation 返回非 null 之后、
                                // onDragStart 之前 —— 不要往拖动循环的收尾处挪。
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragStart(downInRoot)

                                // 长按后**一动不动就松手** = 只想复制文本，不是拖动（问题 #13）。
                                // 阈值用系统的 touchSlop：手指的自然抖动不该被算成拖动。
                                val touchSlop = viewConfiguration.touchSlop
                                var movedBeyondSlop = false
                                var dragging = true
                                while (dragging) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == pointerId }
                                    if (ch == null || !ch.pressed) {
                                        // 抬手事件**必须消费掉**。
                                        // 同一个节点上还有一条 tap 通道（detectTapGestures(onTap)），
                                        // 它在 Main 阶段排在后面、拿到的是已经被消费过的事件；
                                        // 不消费的话它会把这次长按松手当成普通点击 → 意外打开卡片。
                                        ch?.consume()
                                        dragging = false
                                    } else {
                                        if (!movedBeyondSlop &&
                                            (ch.position - down.position).getDistance() > touchSlop
                                        ) {
                                            movedBeyondSlop = true
                                        }
                                        ch.consume()
                                        onDrag(ch.position + nodeOffsetInRoot)
                                    }
                                }
                                // 收尾**必须**先做：即使这一下是复制，拖动状态也得清干净，
                                // 否则卡片会卡在「被抓住」的样子里。
                                onDragEnd()
                                if (!movedBeyondSlop) onCopyText?.invoke()
                            }
                        }
                    }
            } else if (onDoubleClick != null) {
                base.vPressable(scaleDown = 0.98f, onClick = onClick)
                    .pointerInput(card.id) {
                        detectTapGestures(
                            onDoubleTap = { onDoubleClick() },
                        )
                    }
            } else {
                base.vPressable(scaleDown = 0.98f, onClick = onClick)
            }
        }
        // 卡片内边距：底边比其余三边小。
        //
        // 用户 2026-09-22：底部标签要在「分割线」与「卡片下缘」之间**纵向居中** ——
        // 卡片底边到标签的距离应与标签到分割线的距离一致。
        // 原来四边都是 11dp：分割线→标签（CardTagFooter 内的 6dp）比
        // 标签→卡片底边（11dp + 行盒内下空隙）小很多，标签明显偏上、
        // 下方拖着一块多余空白。
        //
        // 取 6dp：与 CardTagFooter 的分割线间距相等，
        // 两者各自再加约 0.5dp 的行盒内空隙，视觉上基本对称
        //（11sp 行高 / 10sp 字号 → 上下各约 0.5dp，视觉不可辨）。
        .padding(
            start = if (compact) 9.dp else 11.dp,
            end = if (compact) 9.dp else 11.dp,
            top = if (compact) 9.dp else 11.dp,
            bottom = if (compact) 5.dp else 6.dp,
        )

    Column(
        modifier = tileModifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 7.dp),
    ) {
        // —— 顶部：日期（含年份 + 星期） + 钉/锁标记 ——
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                // 日期放顶部：带年份与星期，比只显示月日更有参照。
                if (card.showDate) {
                    VText(
                        boardFullDateWeekText(card.updatedAt),
                        VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                        color = VColors.ink3,
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                // 附件数量气泡放**最右侧**；**窄卡不显示**（窄卡空间不够）。
                if (isWideCard && attachmentCount > 0) AttachmentCountBadge(attachmentCount)
                if (card.pinned) Icon(Lucide.Pin, contentDescription = null, modifier = Modifier.size(12.dp), tint = VColors.ink3)
                // 动态浮起的标记：**同尺寸、同位置、同色系**，只换图标。
                // 刻意不做投影/底色/发光——用户需要能分清「我钉的」与
                // 「它自己浮上来的」，但这个区分应当是「换一个图标」级别的最小差异，
                // 不是一种新的气泡/徽章体系。
                if (card.autoPin != null && AutoPinLogic.isActive(card, java.time.Instant.now())) {
                    Icon(Lucide.Clock, contentDescription = "动态浮起中", modifier = Modifier.size(12.dp), tint = VColors.ink3)
                }
                if (card.secret) BoardLockMark()
            }
        }

        // 图片区放在**文字下方**（见下方非保密分支的末尾）；保密卡不展示图片。

        // 日期与标签分开渲染：标签过长时只截标签，日期保持完整。
        // （若拼成一个字符串再省略，省略号会吃掉日期，视觉上不准确。）
        // 底部标签：每个标签一个胶囊，按层级从左到右（与标签栏同形制）。
        val metaTag = tagChain.takeIf { it.isNotEmpty() }

        if (card.secret) {
            // 保密卡片：内容区整体模糊，中央叠一行文字。
            // 叠的这行是**卡片自己的暗号文案**（写给自己的一句提醒），
            // 不是解锁密码——没有「输入暗号才显示」这回事；
            // 没写暗号时就显示中性的「已隐藏」。
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .blur(14.dp, BlurredEdgeTreatment.Unbounded)
                        .background(Color.White.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                        // 纵向不加内边距：非保密分支的内容区没有这层上下留白，
                        // 加上之后保密卡会比普通卡高（「保密后突然变长」的原因）。
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 7.dp),
                ) {
                    card.title?.takeIf { it.isNotBlank() }?.let {
                        VText(it, CardTextStyles.titleStyle(fontSizes.title), color = VColors.ink, maxLines = 3)
                    }
                    card.body?.takeIf { it.isNotBlank() }?.let {
                        val bodySize = fontSizes.body
                        VRichText(
                            it,
                            // 与编辑端**同一个** bodyStyle（行高/字号/字体族完全一致）。
                            CardTextStyles.bodyStyle(bodySize),
                            color = VColors.cardBody,
                            // 保密卡内容整体模糊，不画渐隐遮罩（无意义），但要同样按行截断，
                            // 否则「保密后卡片突然变高」。
                            maxLines = Int.MAX_VALUE,
                            lineLimit = CardBodyLineLimit,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    if (card.type == BoardCardType.TODO && todos.isNotEmpty()) {
                        todos.take(4).forEach { item ->
                            BoardCheckItem(item.text, item.done)
                        }
                    }
                    // 保密卡内容整体模糊，这里标签也用胶囊行（模糊后不可辨，仅占位）。
                    CardTagFooter(chain = metaTag.orEmpty())
                }
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    // 显示暗号文案；没设置就显示「已隐藏」。
                    // 不再有点击解锁的行为——暗号只是提醒，不是密码。
                    val label = card.secretHint?.takeIf { it.isNotBlank() } ?: "已隐藏"
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        VText(label, VTypo.section, color = VColors.ink3, maxLines = 2)
                    }
                }
            }
        } else {
            card.title?.takeIf { it.isNotBlank() }?.let {
                VText(it, CardTextStyles.titleStyle(fontSizes.title), color = VColors.ink, maxLines = 3)
            }
            card.body?.takeIf { it.isNotBlank() }?.let {
                val bodySize = fontSizes.body
                // 正文按富文本渲染（行内属性 + 列表结构）；
                // 含复选框行时可点勾选（回写正文）。
                VRichText(
                    text = it,
                    // 与编辑端**同一个** bodyStyle。
                    style = CardTextStyles.bodyStyle(bodySize),
                    color = VColors.cardBody,
                    // 卡片预览按**视觉行**截断（用户 2026-09-27）：
                    // 原来是按「段落数」，一个没有换行的长段落永远截不掉 —— 多少字都全显示。
                    maxLines = Int.MAX_VALUE,
                    lineLimit = CardBodyLineLimit,
                    // 用裁剪而不是省略号：行尾不加「…」，由下面的渐隐遮罩表达「还有内容」。
                    overflow = TextOverflow.Clip,
                    // 底部一整块渐隐遮罩（像 Tab 栏下方那样，透明 → 卡片底色，两块行高）。
                    // 只在正文确实被截断时出现；画在文字块内部，所以永远不会盖到图片/标签。
                    fadeMask = RichTextFadeMask(color = color),
                    // 卡片正文的符号比编辑态浅一档，与旧版观感一致。
                    symbolColor = VColors.ink3,
                    onTextChange = onBodyChange,
                )
            }
            if (card.type == BoardCardType.TODO && todos.isNotEmpty()) {
                todos.take(4).forEach { item ->
                    BoardCheckItem(item.text, item.done)
                }
            }

            // 图片区：放在**正文/待办下方、日期标签上方**。
            //  窄卡 → 1 张正方形（边长=内宽）；宽卡+1 张 → 保比例靠左；
            //  宽卡+≥2 张 → 三列正方形。详见 CardImageArea。
            if (images.isNotEmpty()) {
                CardImageArea(
                    images = images,
                    storage = storage,
                    isWide = isWideCard,
                )
            }

            // 底部标签：每个标签一个胶囊，按层级从左到右。
            CardTagFooter(chain = metaTag.orEmpty())
        }
    }
}


// ---------------------------------------------------------------- 瀑布流行切分

/**
 * 自动宽度判定：标题 + 正文的总字数（不含空白）达到阈值即视为整行（全宽）。
 * 阈值由用户在「白板设置 → 外观 → 自动宽度阈值」里自定义。
 *
 * **正文必须先转成纯文字再数字数**：正文落库是 JSON（含 `v`/`lines`/`k`/`s`
 * 等结构字段与属性数值），直接对原始字符串数字符会被结构字符撑爆——
 * 任何卡片都会超过阈值，表现为「自动宽度失效、全部变宽卡」。
 */
fun BoardCard.isWide(autoWidthChars: Int = 24): Boolean {
    val text = title.orEmpty() + plainTextOf(body)
    val total = text.count { !it.isWhitespace() }
    return total >= autoWidthChars
}

/** 把过滤排序后的卡片切成「行」：整行单卡，否则两卡一行。 */
fun buildBoardRows(
    cards: List<BoardCard>,
    isFullWidth: (BoardCard) -> Boolean = { it.isWide() },
): List<List<BoardCard>> {
    val rows = mutableListOf<List<BoardCard>>()
    var i = 0
    while (i < cards.size) {
        if (isFullWidth(cards[i])) {
            rows += listOf(cards[i])
            i += 1
        } else {
            val count = minOf(2, cards.size - i)
            rows += cards.subList(i, i + count)
            i += count
        }
    }
    return rows
}

/**
 * 卡片是否整行。
 * 手动宽度优先（"full" / "half"）；自动模式按「标题 + 正文总字数 >= 阈值」判定。
 */
/**
 * 是否整行（宽）卡片。
 *
 * [hasImages]：该卡是否有图片附件。仅当宽度为「自动」时，有图卡片强制整行——
 * 因为窄卡放不下多张图。手动设了 half/full 则尊重设置（用户明确选择的优先）。
 */
fun cardIsFullWidth(
    card: BoardCard,
    autoWidthChars: Int = 24,
    hasImages: Boolean = false,
): Boolean = when (card.widthMode) {
    "full" -> true
    "half" -> false
    // 自动：有图就整行，否则按字数阈值。
    else -> hasImages || card.isWide(autoWidthChars)
}

/**
 * 稳定旋转角（同一张卡始终同角度，取值 -1.2° ~ 1°）。
 *
 * [isWide] 为整行卡片时，角度按比例缩小：宽卡片同角度下两端翘起更明显，
 * 放大到满宽时会显得歪，因此限幅到约一半。
 */
fun rotationFor(id: String, isWide: Boolean = false): Float {
    val angles = listOf(-1.2f, 1f, -1f, 0.8f, 0.5f, -0.8f, 0.3f, -0.5f, 0f, -0.3f)
    val base = angles[(id.hashCode() and Int.MAX_VALUE) % angles.size]
    return if (isWide) base * WIDE_ROTATION_SCALE else base
}

/** 宽卡片的倾斜缩比：宽卡片两端相距更远，同角度看上去更歪。 */
private const val WIDE_ROTATION_SCALE = 0.45f

/**
 * 卡片图片区（正文下方、日期标签上方）。
 *
 * 规则：
 *  - **窄卡**：只显示 1 张，正方形，边长 = 卡片内宽（占满窄卡）；多的写「+n」。
 *  - **宽卡 + 1 张**：维持原图比例，宽高上限都 = 内宽 × [SINGLE_IMAGE_MAX_RATIO]，
 *    **不填满**；靠左。比例超出 16:9~9:16 裁到边界，更接近正方形则维持原比例。
 *  - **宽卡 + ≥2 张**：一行 3 张，每格 = 内宽 ÷ 3 的正方形；超 3 张写「+n」。
 *
 * 格子必须用 `aspectRatio(1f)` 保证正方形；用「固定高度 + weight」会被拉成扁条。
 */
@Composable
private fun CardImageArea(
    images: List<Attachment>,
    storage: AttachmentStorage?,
    isWide: Boolean,
) {
    if (storage == null || images.isEmpty()) return

    // 宽卡单图：维持比例、靠左、不填满。
    if (isWide && images.size == 1) {
        SingleCardImage(
            att = images.first(),
            storage = storage,
            maxSideFraction = SINGLE_IMAGE_MAX_RATIO,
        )
        return
    }

    // 窄卡（任意张数，只显 1 张）与宽卡（≥ 2 张）都走格子。
    // 关键：格子宽度固定 = 内宽 ÷ 3（宽卡），**不拉伸填满**。
    //   宽卡 2 张 → 靠左两格（右边留空）；3 张以上 → 三格 + +n。
    //   窄卡只有一格 → 就是整个内宽（内宽÷1）。
    val columns = if (isWide) 3 else 1
    val shown = images.take(columns)
    val overflow = images.size - shown.size
    val gap = 6.dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 单格宽 = （内宽 - 间距）÷ 列数。宽卡固定在 1/3，不会被 2 张拉宽。
        val cell = (maxWidth - gap * (columns - 1)) / columns
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            shown.forEach { att ->
                Box(Modifier.width(cell).aspectRatio(1f)) {
                    VAttachmentThumb(
                        storage = storage,
                        relativePath = att.storedPath,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (att === shown.last() && overflow > 0) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            VText("+$overflow", VTypo.numMini.copy(fontSize = 10.sp), color = Color.White, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单张图片：维持原图比例，限制在「卡片内宽 × [maxSideFraction]」的正方形框内，
 * 靠左；比例超出 16:9 ~ 9:16 就裁切到边界比例（更接近正方形则维持原比例）。
 */
@Composable
private fun SingleCardImage(
    att: Attachment,
    storage: AttachmentStorage,
    maxSideFraction: Float,
) {
    val rawRatio by produceState<Float?>(initialValue = null, att.storedPath) {
        value = attachmentAspectRatio(storage, att.storedPath)
    }
    // 比例：宽/高。夹到 9:16 ~ 16:9 之间（超出即裁切到边界）。
    val ratio = (rawRatio ?: 1f).coerceIn(9f / 16f, 16f / 9f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 上限框：内宽 × maxSideFraction，正方形。
        val maxSide = maxWidth * maxSideFraction
        // 在该框内维持原比例：宽度受框宽与（框高 × 比例）共同约束。
        val wByRatio = maxSide
        val hByRatio = maxSide / ratio
        val width = minOf(wByRatio, maxSide)
        val height = minOf(hByRatio, maxSide, maxSide)
        // 靠左：Row + 固定宽度，右侧留空。
        Row(Modifier.fillMaxWidth()) {
            VAttachmentThumb(
                storage = storage,
                relativePath = att.storedPath,
                modifier = Modifier.width(width).height(height),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** 宽卡单图的上限：内宽（高同）= 此比例。 */
private const val SINGLE_IMAGE_MAX_RATIO = 2f / 3f

/**
 * 卡片底部的标签区：一条**分割线**隔出底部一条，线下显示标签。
 *
 * 标签用 `父 › 子` 连成一串（`tagPathText` 的形式），最前面加一个「·」。
 *
 * 分割线统一用**近白线**（用户 2026-09-18 定：暂时都用白线，
 * 白色每个 RGB 值减 15 → `#F0F0F0`），不再按卡片色区分。
 * 文字色仍按卡片色派生（软色卡上白字看得清，普通卡用 ink3）。
 */
@Composable
fun CardTagFooter(
    chain: List<BoardTag>,
    modifier: Modifier = Modifier,
    /** 是否自画上方分割线。聚焦页已有自己的分割线，传 false 避免双线。 */
    showDivider: Boolean = true,
    /** 文字缩放。卡墙用 1；聚焦全屏形态传更大值（整页宽度下 10sp 偏小）。 */
    scale: Float = 1f,
    /**
     * 字号基准（sp）。null = 用默认的 10sp。
     *
     * 悬浮信息栏（[CardInfoBar]）传 11sp 使标签与旁边的「n 字」一致（用户 2026-09-28）。
     */
    baseFontSp: Float? = null,
    /**
     * 末尾后缀字符（默认为空）。
     *
     * 悬浮信息栏传 `›` —— 用户 2026-09-28：标签后面放一个**小箭头**。
     * 渲染时会自动在前加一个空格（见 [suffixSpace]）。
     */
    suffix: String = "",
    /**
     * 后缀前面的空格宽度。
     *
     * 用户 2026-09-28 明确要求「文字与箭头之间加一个空格」，
     * 取当前字号下一个空格的视觉宽度（约 0.35em）。
     * 用 Spacer 而不是字符串里的空格：宽度可调、不依赖字体对空格的处理。
     */
    suffixSpace: androidx.compose.ui.unit.Dp = 4.dp,
) {
    if (chain.isEmpty()) return
    val text = chain.joinToString(" › ") { it.name }
    // 标签文字与卡片上的日期用**同一个颜色**（ink3）。
    // 卡片上的次要信息（日期 / 标签路径）应当同色，这是卡片内部的一致性；
    // 之前按卡片底色派生（彩色卡用白字）不但与日期不一致，还会让浅色卡看不清。
    Column(modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().height(1.dp).background(VColors.dividerWhite),
            )
            // 分割线与文字间距：这块与下方卡片内边距一起决定标签的垂直位置。
            // 用户 2026-09-19：标签要居中在「分割线」与「卡片下缘」之间。
            // 之前加底部 Spacer 反而把文字顶得更高（整个 Column 是顶部对齐，
            // 下方空隙只会撑高总高、把文字往上挤）——所以改成**直接调这道上间距**。
            Spacer(Modifier.height(6.dp))
        }
        // 标签文本 + 尾部箭头。
        //
        // ⚠️ 历史坑（2026-09-28）：曾用行内 `SpanStyle` 把箭头放大，
        // 结果**标签字样偏下** —— 行内 span 的字号会参与行盒计算，
        // 且同行共用基线，箭头一大就把小字主体挤到行内偏下。
        // 现在箭头回到**与标签同字号**（用户 2026-09-28："就用小箭头"），
        // 仍作为 Row 里的同级兄弟渲染，中间加一个空格。
        val base = baseFontSp ?: 10f
        val tagStyle = VTypo.micro.copy(
            fontSize = base.sp * scale,
            lineHeight = (base + 1f).sp * scale,
            fontWeight = FontWeight.Normal,
        )
        // 前缀点「·」——卡片底部标签行一直有；悬浮信息栏现在也要（用户 2026-09-28：
        // 「把原来左侧的小点加回来」）。它是**独立于后缀**的，两者可共存。
        val label = "· $text"
        Row(verticalAlignment = Alignment.CenterVertically) {
            VText(
                label,
                tagStyle,
                color = VColors.ink3,
                maxLines = 1,
            )
            if (suffix.isNotEmpty()) {
                // 与文字之间加一个空格的宽度（用户 2026-09-28 明确要求）。
                Spacer(Modifier.width(suffixSpace))
                // 箭头与标签**同字号**（"小箭头"）。
                VText(
                    suffix,
                    tagStyle,
                    color = VColors.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}
