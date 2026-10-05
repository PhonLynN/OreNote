package com.phonlynn.oreplan.v2.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.core.rt.plainTextOf
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.v2.screens.CardTagFooter
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo

/**
 * 卡片顶部信息栏（**悬浮圆角容器**）—— 全屏展示、全屏编辑、全屏新建三页共用。
 *
 * 用户 2026-09-28 定稿结构（两行）：
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ 2026-09-28 星期日            [标记组][菜单][X]│
 * │ 128 字  │  标签 › 子标签                      │
 * └──────────────────────────────────────────────┘
 * ```
 *  - 容器：**卡片底色** + **下方小范围阴影**（不是四周大阴影）；
 *  - **第一行**：左侧日期（比正文实、比原来的 11sp 大），右侧标记组（贴右端）；
 *  - **第二行**：左起是**字数**，紧接着一道**小竖线**，再往右是标签 ——
 *    没有标签时**不画竖线**，这一行就只剩字数；
 *  - 两行之间留出一档行距（原来两行贴太紧）。
 */
@Composable
internal fun CardInfoBar(
    /** 日期文字；null = 不显示（卡片关了「显示日期」）。 */
    dateText: String?,
    /** 字数（见 [cardWordCount]）。 */
    wordCount: Int,
    /** 卡片底色。 */
    color: Color,
    /** 标签链（从根到当前，`tagPathChain` 的结果）；空 = 没有标签。 */
    tagChain: List<BoardTag> = emptyList(),
    modifier: Modifier = Modifier,
    /**
     * 额外上内衬。
     *
     * 「上边缘拉出屏幕」的侵入效果用：调用方把整条栏往上偏移多少，
     * 这里就补多少上内衬 —— 被切掉的是内衬与圆角，**内容不会被切到**。
     *（不补的话，偏移量会直接吃掉日期行的上半截。）
     */
    extraTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    /** 右侧标记组（附件数 / 置顶 / 动态浮起 / 保密 / 三点 / 关闭）——各页自己给。 */
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(16.dp),
                clip = false,
                // 阴影色偏冷灰（与卡片阴影同族），小范围、只在下方可见。
                ambientColor = Color(0x2E101613),
                spotColor = Color(0x52101613),
            )
            .background(color, RoundedCornerShape(16.dp))
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 10.dp + extraTopPadding,
                bottom = 10.dp,
            ),
    ) {
        // 第一行：日期（左） + 标记组（**贴右端**）。
        //
        // 只留一个带权重的子项（Spacer）—— 上一版给左侧列与标签都加了
        // `weight(fill = false)`，两个权重一起分摊剩余空间，标记组被推到中间，
        // 看起来"严重偏左"（用户 2026-09-28 报的）。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dateText != null) {
                VText(
                    dateText,
                    // 日期：字号加大（11 → 13）、颜色加实（ink3 → ink2）、字重中档。
                    VTypo.numMini.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = VColors.ink2,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                content = trailing,
            )
        }
        // 两行之间的行距（原来贴太紧）。
        Spacer(Modifier.height(6.dp))
        // 第二行：字数 ｜ 标签。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VText(
                "$wordCount 字",
                VTypo.caption,
                color = VColors.ink3,
                maxLines = 1,
            )
            if (tagChain.isNotEmpty()) {
                // 小竖线：只在**真的有标签**时画（用户明确：没有标签不需要竖线）。
                //
                // 颜色用户 2026-09-28：「把丢失的分割竖线找回来」——
                // 原先用 VColors.line(#E3E8E4)，在浅紫/浅色栏底上几乎看不见（就是「丢失」的原因）。
                // 改用 ink3 算出的半透明灰：与旁边的文字同色系，但在任何栏底色上都能看清。
                // 宽度 1.5dp：避免高分屏下被蒙成半像素。
                //
                // 高度用户 2026-09-28：「竖线再短一些，中心位置不变」——14 → 10dp。
                // 中心不变是自然的：它在 Row 里靠 Alignment.CenterVertically 居中，
                // 改高度只会向两端收缩。
                Box(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.5.dp)
                        .height(10.dp)
                        .background(VColors.ink3.copy(alpha = 0.45f)),
                )
                Box(Modifier.widthIn(max = 190.dp)) {
                    CardTagFooter(
                        chain = tagChain,
                        showDivider = false,
                        // 字号与旁边的「n 字」**完全一致**（都是 11sp）——用户 2026-09-28：
                        // 「顶栏里标签的字体大小调成和旁边的字数统计一致」。
                        // 以前是 10sp × 0.92 ≈ 9.2sp，比字数小一档。
                        baseFontSp = 11f,
                        scale = 1f,
                        // 右箭头作为**后缀**放在标签文字后面（用户 2026-09-28：
                        // 「就用小箭头」—— 与标签同字号；中间由 CardTagFooter
                        // 自动加一个空格）。
                        suffix = "›",
                    )
                }
            }
        }
    }
}

/**
 * 顶部信息栏里的「字数」。
 *
 * 口径（用户 2026-09-28）：正文里**每个字符平等**，把所有**显示出来的**字符都算上
 *（列表符号不算内容、空白不计），**包含标题**；关键是**先转成纯文字再数** ——
 * 正文落库是 JSON，直接数字符会被结构字段撑爆（`{"v":2,"lin…`）。
 */
internal fun cardWordCount(title: String?, body: String?): Int {
    val text = title.orEmpty() + plainTextOf(body)
    return text.count { !it.isWhitespace() }
}


/**
 * 悬浮信息栏**上缘拉出屏幕**的量。
 *
 * ⚠️ **全屏展示页与全屏编辑页必须同时改、且共用这一个值** ——
 * 用户 2026-09-28 明确要求：两页是一套东西，不能只改一个。
 *
 * 让信息栏看起来像"一张卡片半截插在屏幕外"：上移这么多之后，
 * 上圆角与上内衬都跑出屏幕顶边，只留一条被切平的边。
 *
 * 2026-09-28：由 24dp 收到 **12dp** —— 用户反馈"日期到顶部的距离过小"，
 * 即整条栏要整体下拉 12dp（两页共用这一个值，改这里两页同时生效）。
 * 栏内用 CardInfoBar 的 extraTopPadding 补偿同样的量，所以内容不会被切到。
 *
 * 2026-09-28 晚：**保持 12dp 不变**（顶部仍伸出屏幕的量）。
 * 用户要求"顶栏的一切元素下降 24dp、避不开状态栏"，改的是
 * [CardInfoBarTopDownShift]（栏内容下沉量）而不是本值 ——
 * 因为本值与 extraTopPadding 相互抵消，**单独改本值不会移动内容**。
 */
internal val CardInfoBarPullOut: Dp = 12.dp

/**
 * **标题到悬浮信息栏的间距**（有标题时）—— 两页共用。
 *
 * 内容顶部内衬 = 实测栏高 − 拉出量 − 状态栏高 + **本值**。
 * 前三项是按实测/常量算出来的（保证内容正好落在栏下方），
 * 本值是额外的"呼吸间距"，**调它就是调标题离栏多远**。
 *
 * 2026-09-28 晚：由 4dp 增到 **12dp**（用户要求 +8dp）。
 */
internal val CardInfoBarToTitleGap: Dp = 12.dp

/**
 * **无标题时，正文到悬浮信息栏的额外间距** —— 两页共用。
 *
 * 为什么需要单独一个值：顶部内衬（[CardInfoBarToTitleGap]）加在滚动内容的**顶部**，
 * 它并不知道第一块内容是标题还是正文。而展示页在**没有标题时**不渲染标题行，
 * 于是正文成了第一块 —— 若只用一个值，"标题到栏"与"正文到栏"就必然相同。
 * 用户 2026-09-28 要求两者分别可调，所以给正文在"无标题"时再叠一份本值。
 *
 * ⚠️ 仅对**展示页**生效：编辑页总是在最上面渲染一个"标题"占位行，
 * 不存在"正文直接贴栏"的状态。
 *
 * 2026-09-28 晚：由 4dp 增到 **12dp**（用户要求 +8dp）。
 */
internal val CardInfoBarToBodyGapNoTitle: Dp = 12.dp

/**
 * 悬浮信息栏**内容整体下沉的量**（栏顶仍伸出屏幕时用）。
 *
 * ## 为什么需要单独一个量（2026-09-28 晚的实际几何）
 *
 * `CardInfoBar` 的上内衬是 `10dp + extraTopPadding`，而调用方传的
 * `extraTopPadding = CardInfoBarPullOut + 8dp`；同时栏的 offset = `-CardInfoBarPullOut`。
 * 两者相减后：
 * ```
 * 内容在屏幕上的 Y = offset + 10 + (PullOut + 8) = 8 + 10 = 18dp  ← 与 PullOut 无关
 * ```
 * 所以**单独改 [CardInfoBarPullOut] 只改变"伸出屏幕多少"，不会移动内容**。
 * 要让栏里的一切元素（日期 / 字数 / 标签 / 标记组）真正下落，
 * 只能加这一份**独立的**下沉量（栏会因此变高，栏顶仍在屏幕外）。
 *
 * 2026-09-28 晚：用户报"栏的位置避不开状态栏"，要求整体下移 24dp。
 * 取 24dp 后：内容 Y 由 18dp → 42dp（越过典型状态栏高 24~30dp）。
 */
internal val CardInfoBarTopDownShift: Dp = 20.dp