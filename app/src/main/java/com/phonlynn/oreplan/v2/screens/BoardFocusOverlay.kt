package com.phonlynn.oreplan.v2.screens

import androidx.activity.compose.BackHandler
import com.phonlynn.oreplan.v2.components.VDividerFull
import androidx.compose.foundation.layout.statusBars
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import com.phonlynn.oreplan.v2.components.CardInfoBarPullOut
import com.phonlynn.oreplan.v2.components.CardInfoBarTopDownShift
import com.phonlynn.oreplan.v2.components.CardInfoBarToTitleGap
import com.phonlynn.oreplan.v2.components.CardInfoBarToBodyGapNoTitle
import com.phonlynn.oreplan.v2.components.FullscreenCardLayout
import com.phonlynn.oreplan.v2.components.fullscreenScrollTopPadding
import com.phonlynn.oreplan.v2.components.attachmentMetaText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.domain.model.Attachment
import com.phonlynn.oreplan.platform.attachment.AttachmentStorage
import com.phonlynn.oreplan.v2.components.VAttachmentThumb
import com.phonlynn.oreplan.v2.richtext.VRichText
import com.phonlynn.oreplan.v2.components.attachmentAspectRatio
import androidx.compose.ui.layout.ContentScale
import com.phonlynn.oreplan.domain.model.AutoPinLogic
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VConfirmDeleteDialog
import com.phonlynn.oreplan.v2.components.VIconButton
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.components.CardInfoBar
import com.phonlynn.oreplan.v2.components.ThreeDotMenuButton
import com.phonlynn.oreplan.v2.components.cardWordCount
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 卡片聚焦浮层（V2）—— 设计稿 STPNe / ekHVr / L9qnJ1 / x6NZV。
 *
 * 直接叠加在白板页之上（对齐设计稿的 Blur Backdrop + Focused Card）：
 *  - 背景：卡片墙轻度模糊 + 白色半透明蒙层（随卡片进度淡入淡出）；
 *  - 卡片：从它原来在卡片墙里的位置（[startBounds]，root 坐标 px）飞到屏幕中间（慢-快-慢，VMotion.Glide，120ms）；
 *  - 收起：点卡片外 / 左上角关闭 / 返回键，卡片先飞回原位置再摘掉浮层。
 *
 * [startBounds] 为空（例如从关联卡片跳进来）时退化为轻微放大淡入。
 * 全屏形态的布局参数（边距 / 内衬 / 间距）**全部**在 `FullscreenCardLayout`，
 * 与全屏编辑页共用同一份 —— 两页共用的值不要写在这里。
 */

@Composable
fun BoardFocusOverlay(
    card: BoardCard,
    todos: List<BoardTodoItem>,
    tagChain: List<BoardTag>,
    attachments: List<Attachment>,    /** 附件存储（图片缩略图按路径解码需要它）。 */
    attachmentStorage: AttachmentStorage,
    linkedCards: List<Pair<String, String>>,
    reminderAt: Long?,
    doneToEnd: Boolean,
    startBounds: Rect?,
    cardTilt: Boolean = true,
    /**
     * 展示形态：
     *  - "card"：从卡片墙原位置飞到屏幕中间，保留卡片底色/圆角/阴影（默认）；
     *  - "fullscreen"：淡入一个全屏窗口，不用卡片底色（就是页面背景）、无圆角/阴影，
     *    顶栏左侧放一个与背景同色的圆点作颜色标记。
     * 两种形态共用同一份内容（顶部栏 / 滚动区 / 底部标签）。
     */
    style: String = "card",
    /** 卡片字号档（与主页卡片共用「卡片字号」设置）。 */
    fontSizes: CardFontSizes = CardFontSizes(16.sp, 15.sp),
    onToggleTodo: (String, Boolean) -> Unit,
    /** 正文内复选框勾选后回写正文（不传则正文复选框不可点）。 */
    onToggleBody: ((String) -> Unit)? = null,
    onOpenAttachment: (Attachment) -> Unit,
    onOpenLinked: (String) -> Unit,
    /** 三点下拉菜单的四个动作（复制/导出/分享/更多）；null = 不显示三点。 */
    menu: com.phonlynn.oreplan.v2.components.CardMenuActions? = null,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    /**
     * 把本次动态浮起提前结束（「沉下」）。
     * 仅在卡片确实处于动态浮起时由界面呈现对应入口。
     */
    onSink: () -> Unit = {},
    onDismiss: () -> Unit,
    /**
     * 收起动画开始的信号（比 [onDismiss] 早，早于浮层真正摘掉）。
     * 父层用它把背景模糊的淡出与浮层退场对齐——
     * 否则模糊只能等 [onDismiss] 之后才撤，会出现「浮层已没了、背景还糊着」的迟到感。
     */
    onDismissStart: () -> Unit = {},
    /**
     * 双击（点在**卡片外的空白**上）。传了才启用。
     *
     * 为什么必须挂在这里、而不是父层再套一个手势：这里同时有「单击空白 = 收起浮层」。
     * 父层的手势拦不住它 —— 双击的第一下会先把浮层收掉（白板主页闪一下）。
     * 挂在同一个 `detectTapGestures` 上，框架会**推迟单击判定**去做双击消歧，
     * 双击时不会触发 `requestDismiss`。
     */
    onDoubleTap: (() -> Unit)? = null,
    /**
     * 正文文字块的窗口范围（root 坐标）。
     *
     * 「双击哪里，光标就放哪里」需要它：展示页与编辑页的正文**起点不同**
     * （顶部栏高度、滚动位置都不一样），所以只能先算出「点在正文文字里的相对位置」，
     * 再交给编辑页换算成字符偏移。
     */
    onBodyBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
) {
    var confirmArchive by remember(card.id) { mutableStateOf(false) }
    var confirmDelete by remember(card.id) { mutableStateOf(false) }
    // 该卡此刻是否处于动态浮起。决定顶栏时钟标记与「沉下」入口是否出现。
    val autoPinActive = remember(card.id, card.autoPin, card.autoPinResolvedAt) {
        AutoPinLogic.isActive(card, java.time.Instant.now())
    }

    // 动画进度：0 = 卡片墙里的原始位置，1 = 屏幕中间。
    val progress = remember(card.id) { Animatable(0f) }
    // 终点尺寸（实时更新）。
    //
    // 为什么不只记一次就冻结（2026-09-18 修）：聚焦卡的内容是**异步变高**的——
    // 图片宽度比例要查文件头、缩略图要解码，都会在首帧之后才到；内容一变高，
    // 卡片的实际尺寸就变了。若动画仍按旧的冻结尺寸算缩放/位移（start/end 比例），
    // 实际位置与动画位置对不上，看着就是卡片「大幅弹动」。
    // 现在每帧都用最新的实测尺寸参与插值，内容变化会被连续吸收。
    var endBounds by remember(card.id) { mutableStateOf<Rect?>(null) }
    var dismissing by remember(card.id) { mutableStateOf(false) }
    val isFullscreen = style == "fullscreen"
    // 全屏形态不飞入（无起止矩形），因此一律走「淡入」那条分支。
    val hasStart = startBounds != null && !isFullscreen

    // 退出淡出进度：1 = 完全显示，0 = 已淡尽。
    // 退出时不再把卡片飞回卡片墙原位置，而是在当前位置原地淡出。
    val exitAlpha = remember(card.id) { Animatable(1f) }

    // 首帧布局完成后开跑。
    LaunchedEffect(card.id, endBounds != null) {
        if (endBounds != null && !progress.isRunning) {
            progress.snapTo(if (hasStart) 0f else 0.7f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = if (hasStart) {
                    // 轻微弹性：收尾时小幅过冲后回稳。
                    VMotion.springy()
                } else if (isFullscreen) {
                    // 全屏：整页淡入。面积大，比卡片稍长一点才不显突兀（也无缩放）。
                    tween(durationMillis = 180, easing = VMotion.Glide)
                } else {
                    tween(durationMillis = 85, easing = VMotion.Glide)
                },
            )
        }
    }

    // 收起：保持在当前位置原地淡出（不飞回原位置），淡尽后通知父层摘掉浮层。
    LaunchedEffect(dismissing) {
        if (dismissing) {
            // 先告诉父层「开始收起了」，让背景模糊与浮层退场同时开跑，
            // 而不是等浮层消失后才补一段单独的模糊淡出。
            onDismissStart()
            exitAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    // 全屏窗口面积大，淡出稍长一点才不显突兀。
                    durationMillis = if (isFullscreen) 180 else 130,
                    easing = VMotion.Accelerate,
                ),
            )
            onDismiss()
        }
    }

    fun requestDismiss() {
        dismissing = true
    }

    fun washAlpha(): Float =
        if (hasStart) progress.value.coerceIn(0f, 1f) else ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)

    BackHandler { requestDismiss() }

    val color = boardCardColor(card.color)

    // 悬浮信息栏实测高度（px）：全屏时作为滚动内容的顶部内衬。
    // 初值给估算，避免第一帧内容先跳到顶部再回落（用户 2026-09-28：两页同一处理）。
    val uiDensity = androidx.compose.ui.platform.LocalDensity.current
    var focusHeaderHeightPx by remember { mutableStateOf(with(uiDensity) { 96.dp.toPx() }) }
    val soft = boardIsSoftColor(card.color)
    val start = startBounds
    val end = endBounds

    // **屏幕层**：裁剪到屏幕边界 —— 悬浮信息栏上缘拉出屏幕后要被切平。
    // 外层本来就是这个整屏 Box，只需要补一个 clip。
    Box(Modifier.fillMaxSize().clipToBounds()) {
        // 白色蒙层（设计稿 #ffffff80）：**仅卡片形态需要**——它让卡片从卡片墙里「浮」出来，
        // 同时兼作「点空白处收起」的接收区。
        // 全屏形态本身就是一整页（下面的容器铺满全屏），不需要蒙层；
        // 它依靠顶部栏的关闭按钮与系统返回键收起。
        if (!isFullscreen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.5f * washAlpha() * exitAlpha.value }
                    .background(Color.White)
                    .pointerInput(onDoubleTap) {
                        if (onDoubleTap == null) {
                            detectTapGestures { requestDismiss() }
                        } else {
                            detectTapGestures(
                                onTap = { requestDismiss() },
                                onDoubleTap = { onDoubleTap() },
                            )
                        }
                    },
            )
        }

        // 左上关闭按钮：**仅卡片形态**。
        // 全屏形态的关闭按钮放在它自己的顶部栏里（与日期/圆点同一行）。
        if (!isFullscreen) {
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, top = 12.dp)
                    .graphicsLayer { alpha = washAlpha() * exitAlpha.value },
            ) {
                VIconButton(icon = Lucide.X, onClick = { requestDismiss() })
            }
        }

        // 右侧标记组（两形态共用）：附件数 / 置顶 / 动态浮起 / 保密 / 三点 / 关闭。
        //
        // **两页必须逐项一致**（用户 2026-09-28 铁律）。原先这里缺「保密」一项，
        // 而全屏编辑页有——两页同一个位置少了一个图标（旧清单 U1-c），现补上。
        val markerRow: @Composable () -> Unit = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 附件数量气泡（聚焦页仍在右上角）。
                if (attachments.isNotEmpty()) AttachmentCountBadge(attachments.size)
                if (card.pinned) Icon(Lucide.Pin, null, Modifier.size(13.dp), tint = VColors.ink3)
                // 动态浮起标记：同尺寸、同位置、同色系，只换图标。
                if (autoPinActive) {
                    Icon(Lucide.Clock, "动态浮起中", Modifier.size(13.dp), tint = VColors.ink3)
                }
                // 保密标记（与全屏编辑页同一位置、同尺寸、同色）。
                if (card.secret) Icon(Lucide.Lock, "保密", Modifier.size(13.dp), tint = VColors.ink3)
                // 三点 = **下拉卡片菜单**（复制 / 导出 / 分享 / 更多）——用户 2026-09-28 重构。
                // 以前点三点直接进编辑页：复制/导出/分享这三个动作不该逼用户先进一次编辑页。
                menu?.let { ThreeDotMenuButton(it) }
                // 全屏形态没有外部的左上关闭按钮，所以关闭放在顶部栏最右侧。
                if (isFullscreen) {
                    Box(
                        Modifier.size(28.dp).vPressable(scaleDown = 0.88f) { requestDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.X, "关闭", Modifier.size(18.dp), tint = VColors.ink2)
                    }
                }
            }
        }

        // 容器：两种形态只在这里分叉，内部内容完全相同。
        //  - card      ：居中、圆角、阴影、卡片底色；从原位置飞入（位移+缩放插值）；
        //  - fullscreen：全屏铺满、无圆角/阴影、**用页面背景色**；
        //                不做飞入，直接整页淡入。
        val containerModifier = if (isFullscreen) {
            Modifier
                .fillMaxSize()
                // alpha 图层必须在 background **之前**：Modifier 链是「外→内」，
                // alpha 在外才能让背景一起淡入淡出。
                // （若放在 background 之后，背景由外层节点画、不受内层 alpha 影响，
                //   就会出现「整页不淡、内容才淡」的怪异效果。）
                .graphicsLayer {
                    // 全屏不做位移/缩放，只做透明度淡入淡出（用户明确要求：直接淡入全屏窗口）。
                    alpha = washAlpha() * exitAlpha.value
                }
                // **全屏窗口自己的背景**：它是一整页，必须自己铺底色，
                // 否则下面的卡片墙会直接透出来（用户反馈：进入全屏没有背景）。
                //
                // 用 VColors.surface（纯白）：用户 2026-09-19 要求全屏的**文本区更白一些**，
                // 页面底色（VColors.bg）是淡灰绿，白底更利于长文阅读。
                .background(VColors.surface)
                // 全屏是一整页：顶部避开状态栏、底部避开导航栏，键盘弹出时也缩。
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    if (endBounds != r) endBounds = r
                }
                .vPressable(scaleDown = 1f, onClick = {})
                // 用户 2026-09-19（第二次）：全屏边距太宽、空间利用率低，
                // 左右收到 8dp（原 24dp），上下也相应收紧。
                //
                // 这里是**容器层那一档**；它与下面内容列的 ContentHorizontalPadding 相加
                // = TextHorizontalPadding（16dp）= 文本距屏幕边缘的最终位置。
                // 全屏编辑页没有带内衬的容器，所以那边直接给 TextHorizontalPadding。
                .padding(horizontal = FullscreenCardLayout.HorizontalPadding)
                .padding(
                    top = FullscreenCardLayout.ContentTopPadding,
                    bottom = FullscreenCardLayout.ContentBottomPadding,
                )
        } else {
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .widthIn(max = 310.dp)
                .fillMaxWidth()
                // 高度上限放在外层：卡片整体不超过屏幕可用高度。
                // 注意：纵向滚动**不能**加在这个节点上——外层同时承担飞入的位移/缩放
                // （graphicsLayer），而 verticalScroll 会改变测量与布局，两者叠加会让
                // 位移基准（endBounds）在滚动中漂移，表现为聚焦卡片位置错乱。
                // 滚动因此下沉到内层容器（见下方 Column）。
                .heightIn(max = 560.dp)
                .onGloballyPositioned { coords ->
                    // 实时记录实际尺寸（不是只记一次）：内容异步变高时，
                    // 动画基准必须跟着变，否则卡片会因尺寸不一致而弹动。
                    val r = coords.boundsInRoot()
                    if (endBounds != r) endBounds = r
                }
                .graphicsLayer {
                    // 与卡片墙共用「卡片倾斜」开关：关掉后聚焦浮层也严格水平。
                    rotationZ = if (cardTilt) -1f else 0f
                    if (start != null) {
                        if (end != null) {
                            // 入场：从原位置飞到中间（位移 + 等比缩放按进度插值）。
                            // 退出：progress 保持在 1，卡片停在中间，只靠 exitAlpha 原地淡出。
                            val t = 1f - progress.value
                            translationX = (start.center.x - end.center.x) * t
                            translationY = (start.center.y - end.center.y) * t
                            scaleX = 1f + (start.width / end.width - 1f) * t
                            scaleY = 1f + (start.height / end.height - 1f) * t
                            alpha = exitAlpha.value
                        } else {
                            alpha = 0f
                        }
                    } else {
                        val k = washAlpha()
                        alpha = k * exitAlpha.value
                        scaleX = 0.96f + 0.04f * k
                        scaleY = 0.96f + 0.04f * k
                    }
                }
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(22.dp),
                    ambientColor = Color(0x66101613),
                    spotColor = Color(0x66101613),
                )
                .background(color, RoundedCornerShape(22.dp))
                .border(
                    1.dp,
                    if (boardColorNeedsBorder(card.color)) VColors.line else Color.Transparent,
                    RoundedCornerShape(22.dp),
                )
                .vPressable(scaleDown = 0.99f, onClick = {})
                .padding(16.dp)
        }

        Column(
            containerModifier,
            verticalArrangement = Arrangement.spacedBy(FullscreenCardLayout.ContentSpacing),
        ) {
            // 顶部固定区。
            //
            // 用户 2026-09-19（第二次）：全屏上方栏**不用卡片底色**，
            // 统一用白色背景（与页面底色一致），只在日期前放一个**卡片背景色小圆点**作为颜色标记。
            // 早先那条「整条卡片色带」的方案已废（形态很丑）。
            //  · 卡片形态：不带底色（就是卡片本身，白底即卡片底色）；
            //  · 全屏形态：也用白色（页面底色就是 VColors.surface），
            //    色带内边距仍需纵向保留（只求上下留白合理）。
            // 旧顶栏（日期 + 标记组）：**只在卡片形态**下渲染。
            // 全屏形态的日期/字数/标签已由悬浮信息栏承担 —— 留着就是重复一条。
            if (!isFullscreen) {
            val topBandModifier = if (isFullscreen) {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FullscreenCardLayout.HorizontalPadding)
                    .padding(top = 12.dp, bottom = 4.dp)
            } else {
                Modifier.fillMaxWidth()
            }
            // 卡片形态：保持原样（日期在卡片内，标签在卡片底部）。
                Column(topBandModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            if (card.showDate) {
                                VText(
                                    boardFullDateWeekText(card.updatedAt),
                                    VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                                    color = VColors.ink3,
                                    maxLines = 1,
                                )
                            }
                        }
                        markerRow()
                    }
                }
            }

            // 中间滚动区：只负责滚动，不承担任何位置变换。
            // 上下两栏（日期/状态 + 标签）固定在它外侧，不随内容滚动。
            //
            // weight 的 fill 参数分两种形态：
            //  · 卡片形态：fill = false —— 卡片高度随内容收缩（不撑满），
            //    否则短内容也会把卡片拉到 560dp，看起来像空白卡片。
            //  · 全屏形态：fill = true —— 一整页，滚动区占满剩余空间
            //    （标签已在顶部栏下方，不再贴在底部）。
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isFullscreen) {
                            // 内容层那一档。与容器层的 HorizontalPadding 相加 = TextHorizontalPadding。
                            Modifier.padding(horizontal = FullscreenCardLayout.ContentHorizontalPadding)
                        } else {
                            Modifier
                        },
                    )
                    .weight(1f, fill = isFullscreen)
                    .verticalScroll(rememberScrollState())
                    .then(
                        if (isFullscreen) {
                            // 顶部内衬必须加在 verticalScroll **之后**（= 属于滚动内容）：
                            // 滚到顶时标题正好在栏下方，继续滚则文字从栏下面穿过。
                            // 公式与全屏编辑页**共用一份**（fullscreenScrollTopPadding），
                            // 免得改一处忘另一处 —— 那正是这两页历史上返工的根因。
                            //
                            // 底部同样加一块呼吸空白（用户 2026-09-30 / #18）：
                            // 取值 = 编辑页那颗 ✓ 按钮让出的那一套（ContentBottomBlank），
                            // 于是长文滚到底时最后一行不会贴着屏幕下缘，两页观感一致。
                            // 也放在 verticalScroll 之后 ⇒ 属于滚动内容（是尾部余量，
                            // 不是把可视区压小）。
                            Modifier
                                .padding(top = fullscreenScrollTopPadding(focusHeaderHeightPx))
                                .padding(bottom = FullscreenCardLayout.ContentBottomBlank)
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(FullscreenCardLayout.ContentSpacing),
            ) {
            FocusCardContent(
                card, todos, doneToEnd, onToggleTodo, onToggleBody,
                fullscreen = isFullscreen, fontSizes = fontSizes,
                onBodyBounds = onBodyBounds,
            )
            // 图片附件：聚焦页是**样式设置生效的地方**。
            //   imageLayout = "grid" → 始终网格（三栏正方形，全部图片）；
            //   imageLayout = "fill" → 始终填充（1 张占满宽，维持比例）；
            //   imageLayout = null   → 自动：单图用填充、多图用网格。
            // 与主页不同：聚焦页是卡片内容的全量视图，**不截断**（不留 +n）。
            val imageAtts = attachments.filter { it.isImage }
            val otherAtts = attachments.filterNot { it.isImage }
            // 图片区：与编辑页共用同一个组件（画法见 CardImageBlock）。
            CardImageBlock(
                images = imageAtts,
                storage = attachmentStorage,
                imageLayout = card.imageLayout,
                onOpen = onOpenAttachment,
            )
            // 非图片附件仍是「图标 + 文件名」行；行与行之间要有分割线
            // （与详情页、编辑页同一规范，用户 2026-09-23）。
            otherAtts.forEachIndexed { index, att ->
                if (index > 0 || imageAtts.isNotEmpty()) VDividerFull()
                // 非图片附件行：行高 / 左右内衬 取共用常量。
                // （编辑页原先各写 40dp / 左右 14dp，已改为取同一常量。）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(FullscreenCardLayout.AttachmentRowHeight)
                        .vPressable(scaleDown = 0.97f) { onOpenAttachment(att) }
                        .padding(horizontal = FullscreenCardLayout.AttachmentRowExtraHorizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (att.isAudio) Lucide.Music else Lucide.FileText,
                        null,
                        Modifier.size(14.dp),
                        tint = VColors.ink2,
                    )
                    VText(att.displayName, VTypo.caption12, color = VColors.ink2, maxLines = 1, modifier = Modifier.weight(1f))
                    // 「类型 · 大小」与右侧箭头：原先只有编辑页有，两页不一致。
                    // 按铁律统一 —— 展示页也补上（附件行信息以更全的那一侧为准，不删信息）。
                    VText(
                        attachmentMetaText(att.sizeBytes, att.mimeType),
                        VTypo.numMini.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                        color = VColors.ink3,
                    )
                    Icon(Lucide.ChevronRight, null, Modifier.size(14.dp), tint = VColors.ink3)
                }
            }

            // 分割线 + 底部标签**移到滚动区之外**（固定底栏），
            // 因此这里不再渲染；详见下面滚动区结束后的固定底栏。

            if (linkedCards.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.dividerWhite))
                linkedCards.forEach { (id, title) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .vPressable(scaleDown = 0.97f) { onOpenLinked(id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Lucide.Link2, null, Modifier.size(14.dp), tint = VColors.accent)
                        VText(title, VTypo.caption12, color = VColors.ink2, maxLines = 1, modifier = Modifier.weight(1f))
                        Icon(Lucide.ChevronRight, null, Modifier.size(13.dp), tint = VColors.ink3)
                    }
                }
            }

            if (reminderAt != null) {
                val z = java.time.Instant.ofEpochMilli(reminderAt).atZone(java.time.ZoneId.systemDefault())
                Row(
                    Modifier.fillMaxWidth().height(30.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Lucide.AlarmClock, null, Modifier.size(14.dp), tint = VColors.accent)
                    VText(
                        "提醒：%d月%d日 %02d:%02d".format(z.monthValue, z.dayOfMonth, z.hour, z.minute),
                        VTypo.micro.copy(fontSize = 11.sp),
                        color = VColors.ink2,
                        maxLines = 1,
                    )
                }
            }

            // 动态浮起时给出「沉下」入口。
            //
            // 放在**卡片内部**而不是某个隐藏菜单里：用户看到浮起的卡时，
            // 处置它应当是眼前就能完成的事，不该再点一层菜单去找。
            // 形制沿用上面的关联卡片行（同高 34、同图标尺寸、同色阶），
            // 不引入新的控件样式。
            if (autoPinActive) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.dividerWhite))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .vPressable(scaleDown = 0.97f) { onSink() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Lucide.ChevronDown, null, Modifier.size(14.dp), tint = VColors.accent)
                    VText(
                        "沉下（结束本次浮起）",
                        VTypo.caption12,
                        color = VColors.ink2,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            }  // end 中间滚动区

            // 底部固定栏：分割线 + 标签（不随内容滚动）。
            // **仅卡片形态**：全屏形态的标签已经放到顶部栏下方（见上）。
            // 注意：外层 Column 已有 Arrangement.spacedBy(12dp)，这里不再另加 Spacer，
            // 否则分割线与标签之间的总间距会累成 20dp+（显得很空）。
            if (!isFullscreen && tagChain.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(VColors.dividerWhite),
                )
                CardTagFooter(
                    chain = tagChain,
                    showDivider = false,
                )
            }
        }

        // 悬浮信息栏（**仅全屏**）：与全屏编辑页同一套 ——
        // 上缘拉出屏幕被切平（半截侵入）；栏内补同样多的上内衬，
        // 所以切掉的只有内衬与圆角，日期行完整可见。
        if (isFullscreen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // 与编辑页同一写法：显式声明悬浮层画在内容之上。
                    .zIndex(1f)
                    .align(Alignment.TopCenter)
                    // **入场/退场时间线**：与内容层同一条（washAlpha 进场、exitAlpha 退场）。
                    // 这层原来在 alpha 图层之外，所以栏是硬出现/硬消失（用户 2026-09-28 报）。
                    // 位置上从**上方**滑入 —— 与"卡片插进屏幕"的来向一致。
                    .graphicsLayer {
                        val k = washAlpha()
                        alpha = k * exitAlpha.value
                        translationY = -20.dp.toPx() * (1f - k)
                    }
                    .offset(y = -CardInfoBarPullOut)
                    .onGloballyPositioned { focusHeaderHeightPx = it.size.height.toFloat() }
                    // 左右 = 外层 8dp + 卡片内衬 16dp = 24dp：**维持原值**，
                    // 即栏的左右边缘与原来的卡片内容区一致（用户 2026-09-28：不要放大）。
                    // 改用共用常量：全屏编辑页必须取同一个值，否则两页信息栏不在同一竖线上。
                    .padding(horizontal = FullscreenCardLayout.InfoBarHorizontalPadding)
                    .padding(bottom = FullscreenCardLayout.InfoBarBottomPadding),
            ) {
                CardInfoBar(
                    dateText = if (card.showDate) boardFullDateWeekText(card.updatedAt) else null,
                    wordCount = cardWordCount(card.title, card.body),
                    color = color,
                    tagChain = tagChain,
                    // 上内衬 = 拉出量 + 8（补偿被切掉的部分）+ **内容下沉量**。
                    // 用户 2026-09-28 晚：栏要整体下移 24dp（原位置避不开状态栏），
                    // 且顶部仍伸出屏幕 —— 所以栏变高 24dp、内容下沉 24dp，栏顶仍在屏幕外。
                    // 详见 CardInfoBarTopDownShift 的说明（为何不能靠改拉出量实现）。
                    extraTopPadding = CardInfoBarPullOut + 8.dp + CardInfoBarTopDownShift,
                    trailing = { markerRow() },
                )
            }
        }
    }


    if (confirmArchive) {
        VConfirmDeleteDialog(
            title = "归档卡片？",
            message = "卡片会从白板移除，内容保留，可在「白板设置 → 已归档」中恢复。",
            objectName = card.title?.takeIf { it.isNotBlank() } ?: "未命名卡片",
            confirmText = "归档",
            onConfirm = onArchive,
            onDismiss = { confirmArchive = false },
        )
    }

    if (confirmDelete) {
        VConfirmDeleteDialog(
            title = "确认删除？",
            message = "删除后无法撤销，卡片内容将被永久移除。",
            objectName = card.title?.takeIf { it.isNotBlank() } ?: "未命名卡片",
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun FocusCardContent(
    card: BoardCard,
    todos: List<BoardTodoItem>,
    doneToEnd: Boolean,
    onToggleTodo: (String, Boolean) -> Unit,
    /** 正文内复选框的勾选回调（回写正文）。不传则正文复选框不可点。 */
    onToggleBody: ((String) -> Unit)? = null,
    /**
     * 是否全屏形态。
     *
     * 字号**与主页卡片完全一致**（用户 2026-09-19 明确）：
     * 早先全屏会再乘 1.25 倍，导致同样一档字号在全屏里比主页大一圈，
     * 切换形态时字号跳变；现在两者共用同一档字号，不再有形态系数。
     */
    fullscreen: Boolean = false,
    /** 卡片字号档（与主页卡片共用同一套设置）。 */
    fontSizes: CardFontSizes = CardFontSizes(16.sp, 15.sp),
    /** 正文文字块的窗口范围（root 坐标）；见 BoardFocusOverlay 上同名参数。 */
    onBodyBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
) {
    // 正文块范围上报：挂到正文的 VRichText 上，随滚动实时更新。
    val bodyBoundsMod = if (onBodyBounds != null) {
        Modifier.onGloballyPositioned { onBodyBounds(it.boundsInRoot()) }
    } else {
        Modifier
    }
    // 待办勾选行的缩放：与字号一致，两形态都用 1（字号已对齐）。
    val k = 1f
    // 正文颜色：两处都加深 —— 普通形态用 cardBody（近黑）、全屏用 ink。
    // 原来是 ink2（偏浅），用户 2026-09-19 反馈「首页与聚焦的字体颜色深度还不够深」。
    val bodyColor = if (fullscreen) VColors.ink else VColors.cardBody
    // 标题/正文的实际字号 = 设置档位（全屏与主页共用同一档，不再有形态系数）。
    val titleSize = fontSizes.title
    val bodySize = fontSizes.body
    // 正文样式：**全屏形态行距加大一档**（用户 2026-09-28）。
    // 必须与全屏编辑页取同一个值（`fullscreenBodyStyle`），否则两页行数不同。
    val bodyStyle = if (fullscreen) {
        CardTextStyles.fullscreenBodyStyle(bodySize)
    } else {
        CardTextStyles.bodyStyle(bodySize)
    }

    // 「无标题时正文到顶栏的间距」——单独一份，与标题的区分开。
    //
    // 为什么要在正文上加而不是改顶部内衬：顶部内衬加在滚动内容顶端，
    // 它**不知道第一块是标题还是正文**。展示页在**没有标题时**不渲染标题行，
    // 于是正文成了第一块，其到栏的距离就完全等于顶部内衬（= 标题那一份）。
    // 用户 2026-09-28 要求「标题→栏」与「无标题时正文→栏」**各增大 8dp**，
    // 且两者可分别调 —— 所以这里给无标题分支叠上**两者的差值**：
    //     正文到栏 = 顶部内衬（= CardInfoBarToTitleGap）+ 本差值
    //   要让它等于 CardInfoBarToBodyGapNoTitle，差值 = 后者 − 前者。
    // （当前两者都是 12dp，差值为 0；保留两份常量是为了以后能单独调。）
    //
    // 注：仅全屏形态需要——卡片形态没有悬浮信息栏，正文上方本来就没有栏。
    val noTitleBodyGapMod = if (fullscreen && card.title.isNullOrBlank()) {
        Modifier.padding(top = CardInfoBarToBodyGapNoTitle - CardInfoBarToTitleGap)
    } else {
        Modifier
    }
    // 正文最终使用的修饰符：范围上报 + （无标题时）额外上间距。
    val bodyMod = bodyBoundsMod.then(noTitleBodyGapMod)

    when (card.type) {
        BoardCardType.QUICK -> {
            card.title?.takeIf { it.isNotBlank() }?.let {
                VText(it, CardTextStyles.titleStyle(titleSize), color = VColors.ink)
            }
            card.body?.takeIf { it.isNotBlank() }?.let {
                VRichText(it, bodyStyle, onTextChange = onToggleBody, color = bodyColor, modifier = bodyMod)
            }
            if (card.title.isNullOrBlank() && card.body.isNullOrBlank()) {
                VText("（空白卡片）", VTypo.body, color = VColors.ink3)
            }
        }

        BoardCardType.TODO -> {
            card.title?.takeIf { it.isNotBlank() }?.let {
                VText(it, CardTextStyles.titleStyle(titleSize), color = VColors.ink)
            }
            card.body?.takeIf { it.isNotBlank() }?.let {
                VRichText(it, bodyStyle, onTextChange = onToggleBody, color = bodyColor, modifier = bodyMod)
            }
            if (todos.isEmpty()) {
                VText("暂无待办项，可返回编辑卡片补充内容。", VTypo.caption12, color = VColors.ink3)
            } else {
                val ordered = if (doneToEnd) {
                    todos.sortedWith(compareBy({ it.done }, { it.sortIndex }))
                } else {
                    todos
                }
                ordered.forEach { item ->
                    BoardCheckItem(
                        item.text,
                        item.done,
                        onToggle = { onToggleTodo(item.id, !item.done) },
                        scale = k,
                        textColor = if (fullscreen) VColors.ink else null,
                    )
                }
            }
        }

        BoardCardType.QUOTE -> {
            card.body?.takeIf { it.isNotBlank() }?.let {
                VRichText(it, bodyStyle, onTextChange = onToggleBody, color = VColors.ink, modifier = bodyMod)
            }
            card.title?.takeIf { it.isNotBlank() }?.let {
                VText("—— $it", VTypo.caption.copy(fontSize = 11.sp * k), color = VColors.cardBody)
            }
            if (card.title.isNullOrBlank() && card.body.isNullOrBlank()) {
                VText("（空白摘抄）", VTypo.body, color = VColors.ink3)
            }
        }

        BoardCardType.GOAL -> {
            card.title?.takeIf { it.isNotBlank() }?.let {
                VText(it, CardTextStyles.titleStyle(titleSize), color = VColors.ink)
            }
            card.body?.takeIf { it.isNotBlank() }?.let {
                VRichText(it, bodyStyle, onTextChange = onToggleBody, color = bodyColor, modifier = bodyMod)
            }
            if (card.title.isNullOrBlank() && card.body.isNullOrBlank()) {
                VText("（空白目标）", VTypo.body, color = VColors.ink3)
            }
        }
    }
}

@Composable
private fun FocusActionPill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .background(bg, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .vPressable(scaleDown = 0.92f, onClick = onClick),
    ) {
        VText(text, VTypo.micro, color = fg, maxLines = 1)
    }
}

@Composable
private fun FocusMoreDialog(
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    VDialog(onDismissRequest = onDismiss, maxWidth = 240.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel(horizontalPadding = 0.dp, verticalPadding = 6.dp) {
            MoreRow("编辑卡片", Lucide.Pencil, VColors.ink) { close(onEdit) }
            MoreRow("归档卡片", Lucide.Archive, VColors.ink) { close(onArchive) }
            MoreRow("删除卡片", Lucide.Trash2, VColors.rose) { close(onDelete) }
        }
    }
}

@Composable
private fun MoreRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = 10.dp)
            .vPressable(scaleDown = 0.985f, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tint)
        VText(label, VTypo.body, color = tint, maxLines = 1)
    }
}
