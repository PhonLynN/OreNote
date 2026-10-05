package com.phonlynn.oreplan.v2.components

import com.phonlynn.oreplan.v2.theme.BodyFont
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VRadius
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

// ---------------------------------------------------------------- 卡片 / 分割线

/** 白色卡片：r16 描边，无投影（设计里卡片一律只有描边）。 */
@Composable
fun VCard(
    modifier: Modifier = Modifier,
    radius: Dp = VRadius.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VColors.surface, RoundedCornerShape(radius))
            .border(1.dp, VColors.line, RoundedCornerShape(radius)),
        content = content,
    )
}

/** 卡内分割线：1px，左右 14 内缩。 */
@Composable
fun VDivider(padding: Dp = 14.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = padding)
            .height(1.dp)
            .background(VColors.line),
    )
}

/** 整宽分割线（无内缩）。 */
@Composable
fun VDividerFull() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(VColors.line))
}

// ---------------------------------------------------------------- 徽章 / 胶囊

/** 30x30 圆角方块徽章（行首图标、字母）。 */
@Composable
fun VBadge(
    size: Dp = 30.dp,
    radius: Dp = VRadius.rowBadge,
    background: Color = VColors.bg,
    icon: ImageVector? = null,
    iconSize: Dp = 16.dp,
    iconTint: Color = VColors.ink2,
    letter: String? = null,
    letterColor: Color = VColors.ink2,
) {
    Box(
        modifier = Modifier.size(size).background(background, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = iconTint)
        } else if (letter != null) {
            VText(letter, VTypo.bodyMed, color = letterColor, maxLines = 1, align = TextAlign.Center)
        }
    }
}

/** 小标签胶囊：pad [3,8]，r9。 */
@Composable
fun VChip(
    text: String,
    background: Color,
    foreground: Color,
    fontSize: TextUnit = 10.sp,
    fontWeight: androidx.compose.ui.text.font.FontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
) {
    Box(
        Modifier
            .background(background, RoundedCornerShape(VRadius.chip))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        VText(text, VTypo.micro.copy(fontSize = fontSize, fontWeight = fontWeight), color = foreground, maxLines = 1)
    }
}

/** 过滤器胶囊（h32，r11）：全部/进行中/已完成。 */
@Composable
fun VFilterChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (active) VColors.accent else VColors.surface2, VMotion.select(), label = "fchipBg")
    val fg by animateColorAsState(if (active) Color.White else VColors.ink2, VMotion.select(), label = "fchipFg")
    Box(
        Modifier
            .height(32.dp)
            .background(bg, RoundedCornerShape(VRadius.filterChip))
            .padding(horizontal = 12.dp)
            .vPressable(scaleDown = 0.95f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(text, VTypo.caption12.copy(fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal), color = fg, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 分段控件

/** 分段控件（月/周/日，或 按顺序解锁/自由完成 等）。 */
@Composable
fun VSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 34.dp,
    fontSize: TextUnit = 13.sp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(VColors.surface2, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            val fg by animateColorAsState(if (active) VColors.ink else VColors.ink2, VMotion.select(), label = "segFg")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(itemHeight)
                    .then(
                        if (active) {
                            Modifier
                                .shadow(4.dp, RoundedCornerShape(10.dp), spotColor = Color(0x1F101613), ambientColor = Color(0x1F101613))
                                .background(VColors.surface, RoundedCornerShape(10.dp))
                        } else {
                            Modifier
                        },
                    )
                    .vPressable(scaleDown = 0.96f, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                VText(
                    label,
                    VTypo.body.copy(
                        fontSize = fontSize,
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                    ),
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 开关

/** 44x26 开关，旋钮 20，阻尼弹簧。 */
@Composable
fun VSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val track by animateColorAsState(if (checked) VColors.accent else VColors.surface2, VMotion.select(), label = "switchTrack")
    val knobX by animateFloatAsState(if (checked) 18f else 0f, VMotion.snappy(), label = "switchKnob")
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .background(track, RoundedCornerShape(13.dp))
            .vPressable(scaleDown = 0.94f, onClick = { onCheckedChange(!checked) }),
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .offset(x = knobX.dp)
                .size(20.dp)
                .shadow(2.dp, CircleShape, spotColor = Color(0x33101613), ambientColor = Color(0x33101613))
                .background(Color.White, CircleShape),
        )
    }
}

// ---------------------------------------------------------------- 按钮

/** 主按钮：绿色填充，白字，50 高。 */
@Composable
fun VPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    // 颜色过渡（用户 2026-09-30：变色必须有过渡动画，不能硬切）。
    // 底/字/图标三处走同一条 spec，颜色才同步渐变。
    val bg by animateColorAsState(
        if (enabled) VColors.accent else VColors.surface2,
        VMotion.color(),
        label = "primaryBtnBg",
    )
    val fg by animateColorAsState(
        if (enabled) Color.White else VColors.ink3,
        VMotion.color(),
        label = "primaryBtnFg",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .shadow(
                elevation = if (enabled) 12.dp else 0.dp,
                shape = RoundedCornerShape(VRadius.button),
                ambientColor = Color(0x3D101613),
                spotColor = Color(0x3D101613),
            )
            .background(bg, RoundedCornerShape(VRadius.button))
            .vPressable(scaleDown = 0.97f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = fg)
            }
            VText(text, VTypo.button, color = fg, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- 进度

/** 线性进度：track + 填充（宽度随比例动画）。 */
/**
 * 线性进度：track + 填充。
 *
 * [animated] = false 时**不做宽度动画**，直接按 `fraction` 落位。
 * 为什么需要这个开关：细进度条（6dp，如规划页的目标卡）从左往右「长出来」
 * 看起来像在加载，而不是在表达一个静态比例；反过来，设置页那种大进度条动画是对的。
 * 与其在调用方再写一份「不带动画的进度条」，不如把开关放在这里 ——
 * 一处实现、两种用法。
 */
@Composable
fun VProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    color: Color = VColors.accent,
    trackColor: Color = VColors.surface2,
    animated: Boolean = true,
) {
    val target = fraction.coerceIn(0f, 1f)
    val animatedFraction = animateFloatAsState(target, VMotion.settle(), label = "progress")
    val f = if (animated) animatedFraction.value else target
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColor, RoundedCornerShape(height / 2)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .background(color, RoundedCornerShape(height / 2)),
        )
    }
}

/** 分段步骤条：n 段，gap 3，h6 r3。 */
@Composable
fun VStepStrip(
    total: Int,
    done: Int,
    modifier: Modifier = Modifier,
    color: Color = VColors.accent,
    segmentHeight: Dp = 6.dp,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total.coerceAtLeast(0)) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(segmentHeight)
                    .background(if (index < done) color else VColors.surface2, RoundedCornerShape(segmentHeight / 2)),
            )
        }
    }
}

// ---------------------------------------------------------------- 勾选

enum class VCheckStyle { Soft, Outline, Round }

/** 待办勾选圈。Soft=今日列表（小方块+点），Outline=日程待办（描边方框），Round=白板圆勾。 */
@Composable
fun VCheckbox(
    checked: Boolean,
    onToggle: (() -> Unit)? = null,
    style: VCheckStyle = VCheckStyle.Soft,
    size: Dp = 24.dp,
) {
    val shape: Shape = when (style) {
        VCheckStyle.Round -> CircleShape
        else -> RoundedCornerShape(8.dp)
    }
    val bg by animateColorAsState(
        targetValue = when {
            checked && style == VCheckStyle.Soft -> VColors.accentSoft
            checked -> VColors.accent
            style == VCheckStyle.Soft -> VColors.bg
            else -> Color.Transparent
        },
        animationSpec = VMotion.select(),
        label = "checkBg",
    )
    val borderColor by animateColorAsState(
        if (checked) Color.Transparent else if (style == VCheckStyle.Round) VColors.ink3 else VColors.line,
        VMotion.select(),
        label = "checkBorder",
    )
    Box(
        modifier = Modifier
            .size(size)
            .background(bg, shape)
            .border(1.5.dp, borderColor, shape)
            .then(if (onToggle != null) Modifier.vPressable(scaleDown = 0.85f, onClick = onToggle) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                modifier = Modifier.size(size * 0.62f),
                tint = if (style == VCheckStyle.Soft) VColors.accent else Color.White,
            )
        } else if (style == VCheckStyle.Soft) {
            Box(Modifier.size(6.dp).background(VColors.ink3, CircleShape))
        }
    }
}

// ---------------------------------------------------------------- 通用行

/**
 * 通用列表行：行首可选徽章 + 标题/副标题 + 右侧内容。
 * 高度默认 56，左右内边距 14。
 */
/**
 * 行尾小胶囊（强调色浅底）。
 *
 * 原先只在 `EditorScreenV2` 里有一份 private 实现，动态置顶也要用同一套。
 * 提升到公用组件而不是复制一份：**公用组件只能有一份实现**——
 * 复制出来的两份会各自演化，以后改样式只改一处、另一处不一致。
 *
 * 视觉与提升前完全一致（accentSoft 底、r9、水平 8 / 垂直 3、micro 字）。
 */
@Composable
fun VChipSmall(text: String) {
    Box(
        Modifier
            .background(VColors.accentSoft, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        VText(text, VTypo.micro, color = VColors.accent)
    }
}

/**
 * 搜索输入框（白板页 / 各类选择弹层共用）。
 *
 * 外观与交互**完全照搬白板页的搜索栏**（原 `BoardScreenV2.BoardSearchBar`，
 * 用户 2026-09-23 要求「关联事项」也用同一套）：
 * 左侧 accent 软底放大镜图标块、占位文案、输入有内容时右侧淡入圆形清除按钮。
 *
 * [placeholder] 只在输入为空时显示（它不是 TextField 的 placeholder，
 * 而是叠在下面的假文字 —— 与原实现一致）。
 */
@Composable
fun VSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(VColors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(26.dp).background(VColors.accentSoft, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Search, contentDescription = null, modifier = Modifier.size(15.dp), tint = VColors.accent)
            }
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    VText(placeholder, VTypo.caption12, color = VColors.ink3, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = VTypo.caption12.copy(color = VColors.ink, fontFamily = BodyFont),
                    singleLine = true,
                    cursorBrush = SolidColor(VColors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            // 一键清除：有内容时才显示。用 AnimatedVisibility 而不是硬切 ——
            // 元素的出现/消失必须有过渡（与白板页一致）。
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(tween(160, easing = VMotion.Emphasized)) +
                    scaleIn(initialScale = 0.7f, animationSpec = tween(180, easing = VMotion.Emphasized)),
                exit = fadeOut(tween(120, easing = VMotion.Accelerate)) +
                    scaleOut(targetScale = 0.7f, animationSpec = tween(120, easing = VMotion.Accelerate)),
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(VColors.surface2, CircleShape)
                        .vPressable(scaleDown = 0.85f) { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = "清除搜索", modifier = Modifier.size(13.dp), tint = VColors.ink3)
                }
            }
        }
    }
}

/**
 * 快捷时长的**单格**（h32、r11、等宽）。选中 = accent 实底白字，未选 = 白底描边。
 *
 * 抽出来的原因：日程编辑器（默认描边格）与规划页的专注时长行（设计稿是
 * 无描边的 `surface` 浅格）**是同一套几何**，但用色不同。此前规划页把这一格
 * 又写了一遍，于是「改时长格的圆角」要改两处。这里把几何与选中态收敛成一份，
 * 用色由 [idleBackground] / [showBorder] 给默认值 + 少量可调项。
 */
@Composable
fun RowScope.VDurationCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    weight: Float = 1f,
    enabled: Boolean = true,
    idleBackground: Color = VColors.surface,
    showBorder: Boolean = true,
) {
    Box(
        Modifier
            .weight(weight)
            .height(32.dp)
            .background(if (selected) VColors.accent else idleBackground, RoundedCornerShape(11.dp))
            .then(
                if (showBorder) {
                    Modifier.border(1.dp, if (selected) VColors.accent else VColors.line, RoundedCornerShape(11.dp))
                } else {
                    Modifier
                },
            )
            .vPressable(enabled = enabled, scaleDown = 0.95f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            label,
            VTypo.caption,
            color = when {
                selected -> Color.White
                enabled -> VColors.ink2
                else -> VColors.ink3
            },
            maxLines = 1,
        )
    }
}

/**
 * 快捷时长胶囊行。
 *
 * 原先在 `EditorScreenV2` 里是 private 且写死了档位，现提升为公用并把
 * [durations]（分钟列表）参数化：日程编辑传 30/60/90/120/180，
 * 动态置顶传更贴合置顶场景的档位。
 *
 * [selectedMinutes] 为当前选中时长（分钟）。选中项 accent 实底白字，
 * 其余白底描边——与提升前完全一致。
 *
 * [onSelect] 传回被点档位的分钟数。
 */
@Composable
fun QuickDurationRow(
    durations: List<Int>,
    selectedMinutes: Int?,
    onSelect: (Int) -> Unit,
    /** 档位文案的格式化（默认渲染成「1 小时 30 分钟」这类）。 */
    labelOf: (Int) -> String = { com.phonlynn.oreplan.core.time.AutoPinTime.durationText(it) },
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        durations.forEach { minutes ->
            VDurationCell(
                label = labelOf(minutes),
                selected = selectedMinutes == minutes,
                onClick = { onSelect(minutes) },
            )
        }
    }
}

@Composable
fun VRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(if (onClick != null) Modifier.vPressable(scaleDown = 0.985f, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, VTypo.body, color = VColors.ink, maxLines = 1)
            if (subtitle != null) {
                VText(subtitle, VTypo.caption, color = VColors.ink3, maxLines = 1)
            }
        }
        if (trailing != null) trailing()
    }
}

/**
 * **设置「普通行」** —— 左 label、右 value + chevron。严格照 pen.dev 设计稿
 * `50_Z4CtP_设置` / `38_q9zeVF_白板设置` / `09_LKlts_课表设置` 的规格：
 *
 * ```
 * [frame] xxx h=50 lay=horizontal pad=[0,14] jc=space_between al=center
 *   [text] Label fs=13 fill=$ink
 *   [frame] Value lay=horizontal gap=6
 *     [text] Value Text fs=12 fill=$ink-2
 *     [icon] chevron-right fill=$ink-3
 * ```
 *
 * ## 为什么单独抽出来（而不是复用 [VRow]）
 *
 * 设计稿里**设置区有两种行，不能混同**：
 *  - **入口行**（label + 副标题 + 40/30 图标，如主设置页的「课表设置」）＝ `h=56` → 用 [VRow]；
 *  - **普通行**（label + 右侧值 + chevron，如「外观」「显示周末」）＝ `h=50` → 用本组件。
 *
 * 此前各设置页把这个 50dp 的普通行**各写一遍**（PlainRow / SettingValueRow / SettingRow…），
 * 是本项目「外观一致、实现各异」的典型（见 `.context/extensibility-audit.md` P-2）。
 * 收敛到这里后，改设置行样式只改一处。
 *
 * @param value 右侧的说明文字；null 表示不显示（只剩 chevron）。
 * @param tint 整行文字色；危险项（如「清空全部卡片」）传 [VColors.rose]。
 * @param showChevron 是否画右侧 chevron。默认 true；
 *   只读行（如规划里的「起始值」）传 false —— 那里没有下一级可进，
 *   画一个箭头等于承诺了一件点不动的事。
 */
@Composable
fun VSettingRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = VColors.ink,
    valueColor: Color = VColors.ink2,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(VSettingRowHeight)
            .then(if (onClick != null) Modifier.vPressable(scaleDown = 0.985f, onClick = onClick) else Modifier)
            .padding(horizontal = VSettingRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        VText(label, VTypo.body, color = tint, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            value?.let { VText(it, VTypo.caption12, color = valueColor, maxLines = 1) }
            if (showChevron) VChevron(tint = tint)
        }
    }
}

/**
 * **设置「开关行」** —— 左 label（可选副标题）、右 [VSwitch]。设计稿 `h=50`。
 *
 * 与 [VSettingRow] 同一高度与内衬，只是右侧换成开关；两者并排在一张卡里视觉连续。
 *
 * [badgeIcon] 非空时在文字前加一个 30x30 圆角徽章（规划的目标设置页用）——
 * 那个页面按设计稿有徽章，但**行高与内衬必须和其他设置页一致**，
 * 所以是给本组件加一个可选前缀，而不是在规划层另写一个开关行。
 */
@Composable
fun VSettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeIcon: ImageVector? = null,
    badgeBackground: Color = VColors.bg,
    badgeTint: Color = VColors.ink2,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(VSettingRowHeight)
            // 整行可点：44x26 的开关单独点很容易点空。
            .vPressable(scaleDown = 0.99f) { onCheckedChange(!checked) }
            .padding(horizontal = VSettingRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (badgeIcon != null) {
            Box(
                Modifier.size(30.dp).background(badgeBackground, RoundedCornerShape(VRadius.rowBadge)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badgeIcon, contentDescription = null, modifier = Modifier.size(16.dp), tint = badgeTint)
            }
            Spacer(Modifier.width(11.dp))
        }
        if (subtitle == null) {
            VText(label, VTypo.body, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VText(label, VTypo.body, color = VColors.ink, maxLines = 1)
                VText(subtitle, VTypo.caption, color = VColors.ink3, maxLines = 1)
            }
        }
        VSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 设置行高度（pen.dev 设计稿：普通行/开关行 = 50dp）。 */
val VSettingRowHeight = 50.dp

/** 设置行左右内衬（设计稿 pad=[0,14]）。 */
val VSettingRowPadding = 14.dp

/**
 * **一张独立的危险操作卡**：整宽、白底 r16、居中一行 `$rose` 文案。
 *
 * 设计稿里的规格（`Dl2iy` / `g13Ial` / `LWz7k` 的 `Delete Card`）：
 * `350×49`、`pad [15,14]`、`fill $surface`、`r 16`、文字 `13/500 $rose`。
 *
 * ## 为什么不是 [VSettingRow]
 *
 * 设置行是「左标签 + 右值 + chevron」的横排；危险卡是**整行居中一句话**、
 * 不带箭头（它不是"进入下一级"，而是"立刻执行一个危险动作"）。
 * 两者形状不同，所以是**两个**公共件，而不是给 [VSettingRow] 加开关。
 *
 * ## 为什么单独成卡
 *
 * 它必须与上方的设置卡拉开距离，视觉上"独立一格" ——
 * 混在设置列表里就会和普通项长得一样，误点代价很大。
 * 白板设置里的「清空全部卡片」用的是行内 `DestructiveRow`（那边设计稿是行），
 * 两者都是各自设计稿的原样，不要互相"统一"。
 */
@Composable
fun VDangerCard(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {    Box(
        modifier
            .fillMaxWidth()
            .height(49.dp)
            .background(VColors.surface, RoundedCornerShape(VRadius.card))
            .vPressable(scaleDown = 0.98f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText(text, VTypo.body.copy(fontWeight = FontWeight.Medium), color = VColors.rose, maxLines = 1)
    }
}

/** 行尾 chevron。[size] 默认 16（通用行），今日页按设计稿用 14。 */
@Composable
fun VChevron(tint: Color = VColors.ink3, size: Dp = 16.dp) {
    Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(size), tint = tint)
}

/** Section 标题行：左标题 +（可选）右侧说明或自定义尾部。[noteSize] 默认 12，今日页按设计稿用 11。 */
@Composable
fun VSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteColor: Color = VColors.ink3,
    noteSize: TextUnit = 12.sp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(title, VTypo.section, color = VColors.ink, maxLines = 1)
        if (trailing != null) {
            trailing()
        } else if (note != null) {
            VText(note, VTypo.caption12.copy(fontSize = noteSize), color = noteColor, maxLines = 1)
        }
    }
}

/** 琥珀色注释盒（目标详情底部说明）。 */
@Composable
fun VNoteBox(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(VColors.amberSoft, RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = VColors.amber)
        VText(text, VTypo.caption12.copy(lineHeight = 12.sp * 1.55f), color = VColors.amber, modifier = Modifier.weight(1f))
    }
}

/** 居中空状态。 */
@Composable
fun VEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(56.dp).background(VColors.accentSoft, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = VColors.accent)
        }
        VText(title, VTypo.bodyMed, color = VColors.ink)
        VText(description, VTypo.caption12, color = VColors.ink3, align = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(2.dp))
            action()
        }
    }
}

