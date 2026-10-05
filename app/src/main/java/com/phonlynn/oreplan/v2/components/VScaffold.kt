package com.phonlynn.oreplan.v2.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.vPressable

/** 五个一级 Tab。顺序即底栏顺序。 */
enum class VTab(val route: String, val label: String, val icon: ImageVector) {
    Today("today", "今日", Lucide.LayoutDashboard),
    Calendar("calendar", "日程", Lucide.CalendarDays),
    Timetable("timetable", "课表", Lucide.GraduationCap),
    Board("board", "白板", Lucide.Layers),
    Plan("plan", "规划", Lucide.Target),
}

/** 底栏高度（不含导航条安全区）：16 + 56 + 16。 */
val VTabBarAreaHeight = 88.dp
val VTabScrimHeight = 124.dp

/** 滚动内容底部需要预留的空间（底栏 + 渐隐区）。 */
val VTabBottomPadding = 128.dp

/**
 * **页面左右内衬 = 12dp**（全站唯一口径，2026-10-02 定）。
 *
 * ## 为什么要有这个常量
 *
 * 此前每个页面各写各的 `12.dp`（今日 / 日程 / 课表 / 白板 / 设置 都是 12），
 * 而规划模块按 pen.dev 设计稿写成了 **20**，于是「规划页比别的页窄一圈」。
 * 用户 2026-10-02 指出设计稿那一侧还没同步过来、仍是大边距，要求按实际软件统一。
 *
 * 收敛成一个常量而不是继续散写：以后要调（比如再收紧到 10）只改这里一处，
 * 不会再出现「改了四个页面、漏了一个」。
 */
val VPageHorizontal = 12.dp

/**
 * 悬浮添加按钮（FAB）会让出的额外底部留白（用户 2026-09-27：滚到最底时最后一条被按钮压住）。
 *
 * 算法：FAB 容器底部 padding = [VTabBottomPadding]（128），其外层阴影余量 18dp，
 * 圆形本体 56dp —— 所以**圆面顶边**距屏底 = 128 + 18 = 146dp。
 * 内容底部只留 128dp 时，最后一条正好落在按钮下面被遮住；这里再补 40dp
 * （146 + 22 的呼吸空间），保证滚到最底时所有内容完整可见。
 */
val VFabClearance = 40.dp

/**
 * 一级 Tab 页面脚手架：内容铺满，底部悬浮胶囊 Tab Bar + 渐隐遮罩，可选 FAB。
 * 所有 Tab 页统一走这个，保证底栏几何完全一致。
 */
@Composable
fun VTabScaffold(
    active: VTab,
    onSelect: (VTab) -> Unit,
    fab: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(VColors.bg)) {
        content()

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to VColors.scrimTop,
                        ),
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(VColors.scrimTop)
                    .padding(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 16.dp)
                    .navigationBarsPadding(),
            ) {
                VTabBar(active = active, onSelect = onSelect)
            }
        }

        if (fab != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp)
                    .padding(bottom = VTabBottomPadding)
                    .navigationBarsPadding(),
            ) {
                fab()
            }
        }
    }
}

/** 白色胶囊底栏（358x56, r28）。 */
@Composable
fun VTabBar(
    active: VTab,
    onSelect: (VTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x22101613),
                spotColor = Color(0x22101613),
            )
            .background(VColors.surface, RoundedCornerShape(28.dp))
            .border(1.dp, VColors.line, RoundedCornerShape(28.dp))
            .padding(6.dp),
    ) {
        VTab.entries.forEach { tab ->
            VTabItem(
                tab = tab,
                active = tab == active,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun VTabItem(
    tab: VTab,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconColor by animateColorAsState(
        targetValue = if (active) VColors.accent else VColors.ink3,
        animationSpec = VMotion.select(),
        label = "tabIconColor",
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = VMotion.select(),
        label = "tabPill",
    )
    Box(
        modifier = modifier.vPressable(scaleDown = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .alpha(pillAlpha)
                .background(VColors.accentSoft, RoundedCornerShape(22.dp)),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(tab.icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = iconColor)
            Spacer(Modifier.height(3.dp))
            VText(
                text = tab.label,
                style = VTypo.micro.copy(
                    fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Medium
                    else androidx.compose.ui.text.font.FontWeight.Normal,
                ),
                color = iconColor,
                maxLines = 1,
            )
        }
    }
}

/** 右下角悬浮按钮：56 圆，绿色投影。 */
@Composable
fun VFAB(onClick: () -> Unit, icon: ImageVector = Lucide.Plus, modifier: Modifier = Modifier) {
    // vShadowRoom 在最外：强制按 56+36=92dp 测量内容、对外只报 56dp。
    // 中间那层 fillMaxSize 是**透明**的，唯一作用是让动画/绘制发生在一个 92dp 的
    // 节点上（阴影需要这么大的缓冲才不被裁）。
    // **shadow + background 必须在同一个 56dp 圆节点上** —— 拆成两层会画出
    // 92dp 的方形阴影且丢掉底色（上一版就是这个错误）。
    Box(
        modifier = modifier.vShadowRoom(contentSize = 56.dp, slack = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        // clip = false：默认值 = elevation > 0（开启），会把阴影裁到圆边界内。
                        clip = false,
                        ambientColor = Color(0x591C6B58),
                        spotColor = Color(0x591C6B58),
                    )
                    .background(VColors.accent, CircleShape)
                    .vPressable(scaleDown = 0.92f, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White)
            }
        }
    }
}

/** 圆形小按钮：36x36 白底、描边（返回、更多、关闭）。 */
@Composable
fun VIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    tint: Color = VColors.ink,
    filled: Boolean = true,
    onClickEnabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(if (filled) VColors.surface else Color.Transparent, CircleShape)
            .border(1.dp, VColors.line, CircleShape)
            .vPressable(scaleDown = 0.9f, enabled = onClickEnabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
    }
}

/** 侧栏按钮的图标尺寸与点击热区尺寸。 */
private val VSidebarIconSize = 22.dp

/** 点击热区尺寸。**只作溢出点击面积，不参与排版**（参与排版会把标题推开）。 */
private val VSidebarHitSize = 40.dp

/**
 * 汉堡图标与标题文字之间的间距（布局值：图标盒右缘 → 标题左缘）。
 *
 * ## 截图实测定案（用户 2026-10-02 让我"看截图"，这是唯一可信的依据）
 *
 * 1080px / 390dp ⇒ 2.769 px·dp⁻¹。历次实测：
 *
 * | 构建 | 笔画左缘 | 标题左缘 | 真实间隙 |
 * | --- | --- | --- | --- |
 * | box 40 + CenterStart | 10.5 dp ✓ 贴页边距 | 52.4 dp | 26.4 dp |
 * | box 22 + 图标负偏移 | 1.8 dp ✗ 跑出页边距 | 52.4 dp | 2.2 dp |
 * | box 40 + gap 8 | 14.1 dp ✓ | 57.8 dp | 28.2 dp |
 *
 * **真相**：图标位置一直是好的（≈ 页边距）；标题左缘却总在 52~58dp。
 * 原因不是图标、也不是这个 Spacer，而是**图标盒宽度**：
 * `Box(40dp)` 里图标只占 22dp，`CenterStart` 让图标靠左，**右侧白白空了 18dp**，
 * 再加 8dp Spacer ⇒ 标题被推出去 26dp，看起来就是"间隙很大"。
 *
 * 所以修法是**把盒子缩到图标尺寸**（见 [VSidebarButton]），
 * 让排版宽度 = 22dp，间距才是这个 Spacer 的真实值。
 */
private val VSidebarTitleGap = 8.dp

/**
 * 侧栏按钮（Tab 页大标题左侧）：**无外框**，汉堡图标 + 透明点击区。
 *
 * 用户 2026-10-02 四次修正这里：
 *  1. 设置从右上角圆钮 ⇒ 挪到大标题左侧；
 *  2. 去掉圆形外框，与标题共行；
 *  3. 左端对齐页边距；
 *  4. 「标题要和汉堡图标**紧贴**」。
 *
 * ## 截图实测定案（唯一可信的依据）
 *
 * 见 [VSidebarTitleGap] 的表格：`Box(40dp) + CenterStart` 时笔画左缘 = 10.5dp，
 * 与页边距 12dp 只差 1.5dp（抗锯齿），**本来就是对的**。
 * 我后来一度改成 `box 22dp + offset(-3.67)` 想"抵消图标盒空白"，
 * 结果把笔画推到 1.8dp（跑出页边距外 10dp）——**那是破坏，不是修复**。
 *
 * 所以：**图标位置不再动**。标题离得远是 [VSidebarTitleGap] 的事，减小它即可。
 */
@Composable
fun VSidebarButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(VSidebarIconSize)
            .vPressable(scaleDown = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Lucide.Menu,
            contentDescription = "打开侧栏",
            modifier = Modifier.size(VSidebarIconSize),
            tint = VColors.ink,
        )
    }
}

/**
 * Tab 页标题区：`[侧栏按钮] overline + 大标题`。
 *
 * ## 布局口径（用户 2026-10-02 改版）
 *
 * **右上角不再有任何按钮** —— 设置入口统一挪到**大标题左侧的侧栏按钮**，
 * 而且那个按钮是**无外框**的（见 [VSidebarButton]）。
 *
 * 对齐要点：标题区是 `overline + 大标题` 两行，侧栏按钮要**和「大标题」这一行对齐**。
 * 做法：把文字块拆成「overline」与「大标题」两行单独摆放，
 * 侧栏按钮与**大标题**同处一个 `Row`（这个 Row 就是"标题那一行"），
 * 于是按钮天然与大标题共线 —— 不依赖任何手算偏移。
 *
 * 注意：标签栏那类**属于本页内容**的按钮（如白板的「新建卡片 +」）
 * 仍然留在标题行右侧，不受此规则影响 —— 这条只管"进设置"。
 */
@Composable
fun VScreenTitle(
    title: String,
    overline: String? = null,
    overlineLetterSpacing: TextUnit = 0.sp,
    onSidebar: (() -> Unit)? = null,
    /** 标题行**右侧**的页内动作（如白板的「新建卡片」）。不是设置入口。 */
    trailing: @Composable (() -> Unit)? = null,
    /**
     * AI 入口（右上角圆形按钮，全主 tab 统一位置）。
     *
     * 与 [trailing] 分成两个槽而不是合成一个，是为了**保证位置固定**：
     * AI 入口永远在最右，而页内动作（白板的「新建卡片」）紧邻其左。
     * 合成一个槽的话，两个调用点很容易把顺序写反，
     * 而"AI 按钮在所有 tab 的同一位置"正是肌肉记忆依赖的东西。
     */
    onAi: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (overline != null) {
                VText(
                    overline,
                    VTypo.caption12.copy(letterSpacing = overlineLetterSpacing),
                    color = VColors.ink3,
                    maxLines = 1,
                )
            }
            // 「标题那一行」：侧栏按钮 + 大标题。两者同 Row ⇒ 自动共线。
            //
            // ⚠️ **左端对齐页边距**（用户 2026-10-02）：汉堡图标的左边缘、标题文字的左边缘、
            // 下方卡片的左边缘必须是同一条竖线。
            // 做法见 [VSidebarButton]：40dp 只是点击热区，图标用 `CenterStart`
            // 贴在热区左缘 = 行首 = 页边距；标题紧随其后，间距由 [VSidebarTitleGap] 固定。
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSidebar != null) {
                    VSidebarButton(onClick = onSidebar)
                    Spacer(Modifier.width(VSidebarTitleGap))
                }
                VText(title, VTypo.pageTitle, color = VColors.ink, maxLines = 1)
            }
        }

        if (trailing != null || onAi != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                trailing?.invoke()
                onAi?.let { callback ->
                    com.phonlynn.oreplan.v2.ai.AiEntryButton(onClick = callback)
                }
            }
        }
    }
}

/** 详情/全屏页导航条：返回 + 标题 + 动作（48 高）。 */
@Composable
fun VNavBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = VPageHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIconButton(icon = Lucide.ChevronLeft, onClick = onBack)
        Spacer(Modifier.width(12.dp))
        VText(title, VTypo.navTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * **二级页脚手架**：根容器（页面底色 + 状态栏安全区）+ 返回导航条 + 内容槽。
 *
 * ## 为什么需要它
 *
 * 此前「已归档」这类二级页在**每个页面各写一遍**同一串根容器：
 * `Column(Modifier.fillMaxSize().background(VColors.bg).statusBarsPadding()) { VNavBar(...) ... }`。
 * 这种「外观一致、实现各异」是本项目可扩展性差的主要来源之一（见
 * `.context/extensibility-audit.md` 的 P-3）：统一改底色/安全区时要逐页手改，漏一页就分叉。
 *
 * ## 边界（重要）
 *
 * 本脚手架**只统一「外壳」**：根容器 + 导航条。**内容槽完全由调用方决定**——
 * 各页的 LazyColumn 内衬 / 间距 / 空态形态**有意各不相同**（如归档页 top 8dp vs 规划归档 16dp），
 * 不在本组件里统一，以免改变既有设计稿口径。
 *
 * 用法：
 * ```
 * VPageScaffold(title = "已归档", onBack = onBack) {
 *     // 内容（LazyColumn / 空态 / …），自行决定内衬与间距
 * }
 * ```
 */
@Composable
fun VPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    navActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(VColors.bg)
            .statusBarsPadding(),
    ) {
        VNavBar(title = title, onBack = onBack, actions = navActions)
        content()
    }
}

/** 编辑页顶栏：关闭 + 大标题 +（可选）右侧动作。 */
@Composable
fun VTopBarClose(
    title: String,
    onClose: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = VPageHorizontal, end = VPageHorizontal, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(VColors.surface, CircleShape)
                .border(1.dp, VColors.line, CircleShape)
                .vPressable(scaleDown = 0.9f, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, contentDescription = "关闭", modifier = Modifier.size(18.dp), tint = VColors.ink)
        }
        Spacer(Modifier.width(12.dp))
        VText(title, VTypo.pageTitle, color = VColors.ink, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 状态栏安全区 + 设计留白（Tab 页通用顶部）。 */
fun Modifier.vStatusTop(): Modifier = this.statusBarsPadding()

/**
 * 底部固定操作条（用于各页面的主按钮）。
 *
 * 与底部 Tab 栏同样的处理方式：
 *  - 上方一条 36dp 的**渐隐遮罩**，让滚动内容淡出到背景色，而不是被硬边切断；
 *  - 下方是实底区域，承载主按钮与可选附加操作，并处理导航栏安全区。
 *
 * 用法：放在页面根 Box 内，传入 `Modifier.align(Alignment.BottomCenter)`。
 * 内容区记得留出等高的底部内边距，避免最后一行被遮住。
 */
@Composable
fun VBottomActionBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth(),
    ) {
        // 渐隐遮罩：与 VTabScaffold 完全一致，保证两种底栏观感统一。
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to VColors.scrimTop,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(VColors.scrimTop)
                .padding(start = VPageHorizontal, end = VPageHorizontal, top = 0.dp, bottom = 14.dp)
                .navigationBarsPadding(),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}
