package com.phonlynn.oreplan.core.rt

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/**
 * # 📌 列表排版参数 —— 想改排版就只看这个文件
 *
 * 这是全项目**唯一**的排版参数来源。改这里的任何一个值，
 * 主页卡片 / 保密卡 / 归档页 / 聚焦浮层 / 编辑页 / 全屏编辑
 * **六处同时生效**，不需要去别处同步任何东西。
 *
 * 改完直接编译安装即可，不需要动其它文件。
 *
 * ---
 *
 * ## 排版结构（先看懂这张图，改参数就不会错）
 *
 * ```
 * 容器左边
 * │
 * │←──── 缩进区（IndentEm）────→│
 * │                              │
 * │        ●                    甲乙丙丁……      ← 列表项第 1 行
 * │        ↑                    丁戊己庚……      ← 折行的第 2 行
 * │        │                                  （与上面正文左边缘对齐）
 * │        └─ 符号中心
 * │          （自动取缩进区中点，不需要调）
 * │                              ↑
 * │                          正文左边缘
 * │                    （第 1 行相对容器左移了半个缩进）
 * ```
 *
 * - **缩进区**：从容器左边到「列表项第 1 行的正文左边缘」这段距离。
 * - 第 1 行的正文左边缘 = `IndentEm / 2`（因为首行负缩进了半个缩进）。
 * - 折行的第 2 行回到容器左边 + `IndentEm`……**不对**，看下面第一条参数说明。
 * - **符号是自绘的**（不是字体字形），形状/大小/位置完全由本文件决定。
 *
 * ---
 *
 * ## 常用调整速查
 *
 * | 想达到的效果 | 改哪个 |
 * | --- | --- |
 * | 列表整体再往右挪一点 | [IndentEm] 调大 |
 * | 圆点太大 / 太小 | [BulletDiameterEm] |
 * | 圆点位置偏左 / 偏右 | 不用调：符号自动居中于缩进区（改 [IndentEm] 会跟着动） |
 * | 缩进的**单位**不是 0.5 的倍数 | 不支持：缩进由全角空格(1em)+EN SPACE(0.5em) 拼成 |
 * | 复选框太大 / 太小 | [CheckBoxSizeEm] |
 * | 复选框太方 / 太圆 | [CheckBoxCornerRatio] |
 * | 复选框边框太粗 / 太细 | [CheckBoxStrokeRatio] |
 * | 对勾太大 / 太小 | [CheckMarkInsetRatio]（**反向**：值小 = 勾大） |
 * | 对勾太粗 / 太细 | [CheckMarkStrokeRatio] |
 * | 编号比正文大 / 小 | [OrderFontScale] |
 */
object RichLayout {

    // ================================================================
    // 一、缩进 —— 列表项整体右移多少
    // ================================================================

    /**
     * ## 列表段的缩进量（单位：em，1.0 = 一个中文字符宽）
     *
     * ### 改它会怎样
     *
     * - **调大** → 列表项整体更往右，与上方普通段落的错位更明显。
     * - **调小** → 列表项更贴近左边。
     * - 折行的第 2 行**自动**跟着对齐，不需要另外调。
     *
     * ### 为什么用 em
     *
     * 中文是全角字符，1 个中文字符宽 = 1em。所以用 em 作单位时，
     * **字号一变缩进自动按比例缩放**，不需要按字号单独换算。
     *
     * ### 当前值 1.5 = 1.5 个中文字符（用户指定）
     */
    const val IndentEm = 1.50f

    // ================================================================
    // 二、无序列表的圆点（自绘）
    // ================================================================

    /**
     * ## 圆点直径（单位：em，相对正文字号）
     *
     * ### 改它会怎样
     *
     * - **调大** → 圆点更明显，超过 `IndentEm / 2`（= 0.75）时会顶到正文。
     * - **调小** → 圆点更含蓄，小于 0.15 时在高分屏上会显得脏。
     *
     * ### 背景
     *
     * 旧版用的是字体字形 `●`，它自带的实心圆接近 **0.9em**，
     * 用户反馈「真的太大」。自绘后这个值完全由我们决定，与字体无关。
     *
     * ### 建议范围
     *
     * `0.20 ~ 0.30`。当前 0.26。
     */
    /**
     * 复选框相对文字中心的**垂直下移量**（dp）。
     *
     * 用户实测（2026-09-24）：复选框比文字视觉中心略高，先试 2dp、再收到 1dp。
     */
    const val CheckBoxOffsetDp = 1f

    /**
     * 圆点相对文字中心的**垂直下移量**（dp）。
     *
     * 用户实测（2026-09-24）：圆点同样下移 1dp。编号不受影响。
     */
    const val DotOffsetDp = 1f

    const val BulletDiameterEm = 0.26f

    // ================================================================
    // 三、复选框（自绘）
    // ================================================================

    /**
     * ## 复选框方框边长（单位：em，相对正文字号）
     *
     * ### 改它会怎样
     *
     * - **调大** → 方框更醒目；超过 `IndentEm`（= 1.5）会与上一行发生视觉粘连。
     * - **调小** → 更精致，小于 0.6 时对勾会显得拥挤。
     *
     * ### 建议范围
     *
     * `0.70 ~ 0.95`。当前 0.80。
     */
    const val CheckBoxSizeEm = 0.80f

    /**
     * ## 复选框圆角半径（占边长的比例，0 = 直角，0.5 = 完全圆）
     *
     * ### 改它会怎样
     *
     * - **调大** → 更圆润、更「软」。
     * - **调小** → 更方正、更「硬」。
     *
     * ### 建议范围
     *
     * `0.20 ~ 0.35`。当前 0.28。
     */
    const val CheckBoxCornerRatio = 0.28f

    /**
     * ## 复选框**未勾选**时的描边粗细（占边长的比例）
     *
     * ### 改它会怎样
     *
     * - **调大** → 边框更粗更「重」。
     * - **调小** → 更轻；但低于 [CheckBoxMinStrokeDp] 时会被下限接管。
     *
     * ### 建议范围
     *
     * `0.08 ~ 0.16`。当前 0.115。
     */
    const val CheckBoxStrokeRatio = 0.115f

    /**
     * ## 描边的**最小**宽度（单位：dp）
     *
     * ### 为什么需要它
     *
     * 描边实际宽度 = `CheckBoxStrokeRatio × 方框边长`。
     * 字号很小时这个乘积会小于 1 像素，**在高分屏上会断线**（时有时无）。
     * 这个下限保证任何字号下都画得出来。
     *
     * ### 改它会怎样
     *
     * 一般不用改。觉得小字号下复选框「看不清」可以调到 1.5。
     */
    const val CheckBoxMinStrokeDp = 1.1f

    /**
     * ## 对勾相对方框的内缩比例（每侧）
     *
     * ### 改它会怎样（注意是**反向**的）
     *
     * - **调大** → 可用空间变小 → **对勾变小**、离边框更远。
     * - **调小** → **对勾变大**、更贴近边框；太小会溢出圆角。
     *
     * ### 建议范围
     *
     * `0.18 ~ 0.30`。当前 0.24。
     */
    const val CheckMarkInsetRatio = 0.24f

    /**
     * ## 对勾笔画粗细（占边长的比例）
     *
     * ### 改它会怎样
     *
     * - **调大** → 对勾更粗更实。
     * - **调小** → 更细更秀气；同一档位会与描边下限取较大者。
     *
     * ### 建议范围
     *
     * `0.10 ~ 0.18`。当前 0.125。
     */
    const val CheckMarkStrokeRatio = 0.125f

    // ---- 对勾的三个折点（占「内缩后可用宽度」的比例）----
    //
    // 一般不需要改。若想微调对勾形状：
    //   P1 = 左下起点  P2 = 中间转折点  P3 = 右上终点
    //   X 越大越靠右，Y 越大越靠下。
    //
    // 当前值画出来是一个略向右倾的标准对勾。

    /** 对勾起点 X（0 = 最左）。 */
    const val CheckMarkP1X = 0.02f

    /** 对勾起点 Y（1 = 最下）。 */
    const val CheckMarkP1Y = 0.52f

    /** 对勾中间转折点 X。 */
    const val CheckMarkP2X = 0.34f

    /** 对勾中间转折点 Y（应大于 P1Y，才是向下折）。 */
    const val CheckMarkP2Y = 0.86f

    /** 对勾终点 X（1 = 最右）。 */
    const val CheckMarkP3X = 0.98f

    /** 对勾终点 Y（应小于 P2Y，才是向右上收笔）。 */
    const val CheckMarkP3Y = 0.04f

    // ================================================================
    // 四、有序列表的编号
    // ================================================================

    /**
     * ## 有序编号的字号比例（相对正文字号）
     *
     * ### 改它会怎样
     *
     * - **调大** → 编号与正文一样大或更大，更醒目。
     * - **调小** → 编号更小更轻。
     *
     * ### 为什么默认略小于正文
     *
     * 编号是文字（`1.` `2.` `10.`），字号略小可以减轻右边缘的参差感；
     * 但太小会让「10.」这类两位数看起来不整齐。
     *
     * ### 建议范围
     *
     * `0.85 ~ 1.0`。当前 0.94。
     */
    const val OrderFontScale = 0.94f

    // ================================================================
    // 五、以下为派生量 / 工具，一般不需要改
    // ================================================================

    /** 缩进量换算成 Compose 的 `TextUnit`（供 `TextIndent` 使用）。 */
    val indentUnit: TextUnit = IndentEm.em

    /** 该段是否需要画符号。 */
    fun hasSymbol(kind: RichLineKind): Boolean = kind != RichLineKind.TEXT

    /**
     * 自检：参数是否都在合理范围。
     *
     * 改完参数如果排版看起来不对，可以先看这里有没有报警（单测会调用它）。
     */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (IndentEm <= 0f) problems.add("IndentEm 必须大于 0（当前 $IndentEm）")
        if (IndentEm < 1f) {
            problems.add("IndentEm 应 >= 1.0（缩进由全角空格提供，1.0 = 一个中文字符宽），当前 $IndentEm")
        }
        if (BulletDiameterEm <= 0f) problems.add("BulletDiameterEm 必须大于 0")
        if (BulletDiameterEm > IndentEm / 2f) {
            problems.add("BulletDiameterEm（$BulletDiameterEm）超过了缩进区的一半（${IndentEm / 2f}），圆点会顶到正文")
        }
        if (CheckBoxSizeEm <= 0f) problems.add("CheckBoxSizeEm 必须大于 0")
        if (CheckBoxSizeEm > IndentEm) {
            problems.add("CheckBoxSizeEm（$CheckBoxSizeEm）超过了缩进区（$IndentEm），方框会与上一行粘连")
        }
        if (CheckBoxCornerRatio !in 0f..0.5f) problems.add("CheckBoxCornerRatio 应在 [0, 0.5]")
        if (CheckBoxStrokeRatio !in 0f..0.5f) problems.add("CheckBoxStrokeRatio 应在 [0, 0.5]，且应小于方框边长的一半")
        if (CheckMarkInsetRatio !in 0f..0.5f) {
            problems.add("CheckMarkInsetRatio 应在 (0, 0.5)；>= 0.5 时可用宽度为负，对勾会溢出")
        }
        if (CheckMarkStrokeRatio !in 0f..0.5f) problems.add("CheckMarkStrokeRatio 应在 [0, 0.5]")
        if (OrderFontScale <= 0f) problems.add("OrderFontScale 必须大于 0")
        val marks = listOf(
            "CheckMarkP1X" to CheckMarkP1X, "CheckMarkP1Y" to CheckMarkP1Y,
            "CheckMarkP2X" to CheckMarkP2X, "CheckMarkP2Y" to CheckMarkP2Y,
            "CheckMarkP3X" to CheckMarkP3X, "CheckMarkP3Y" to CheckMarkP3Y,
        )
        marks.forEach { (name, v) ->
            if (v !in 0f..1f) problems.add("$name 应在 [0, 1]，当前 $v")
        }
        return problems
    }
}

/**
 * 与排版相关的**其它**可调项（不在 [RichLayout] 里，因为它们是「字号/行高」而不是「列表几何」）。
 *
 * 如果你要调的是**文字本身的疏密**（不是列表符号），改这里：
 */
object RichTextTypo {

    /**
     * ## 正文行高倍数
     *
     * ### 改它会怎样
     *
     * - **调大** → 行与行更松，列表看起来也更透气。
     * - **调小** → 更紧凑。
     *
     * ### 注意
     *
     * 这个值**同时作用于编辑页与所有展示页**，所以两端永远一致。
     * 不要在任何调用处再乘别的系数。
     *
     * 实际定义在 `v2/screens/CardTextStyles.kt` 的
     * `BODY_LINE_HEIGHT_MULTIPLIER`，这里是提示你位置。
     */
    const val BodyLineHeightNote = 1.5f

    /**
     * ## 卡片字号档
     *
     * 三档（小/中/大）定义在 `v2/screens/BoardUi.kt` 的 `CardFontSizes`。
     * 列表缩进与符号大小都按字号自动缩放，所以改字号不需要动 [RichLayout]。
     */
    val FontSizesNote: TextStyle = TextStyle.Default
}
