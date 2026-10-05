package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.flatten
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **随机标记串的健壮性**（模糊测试）。
 *
 * ## 它是为一次真实崩溃写的，而且现在**渲染器已经退回旧版**，它依然有用
 *
 * 2026-10-04 真机崩溃：
 *
 * ```
 * java.lang.StringIndexOutOfBoundsException: length=404; index=-2
 *     at java.lang.String.charAt(Native Method)
 *     at g4.l(...)        ← R8 映射还原后是 isInlineMathSpan
 * ```
 *
 * 根因是行内公式扫描把 `indexOf` 找不到时的 **-1 当成了下标**。
 * 崩溃发生在**重写版**的渲染器里，所以那次修复随重写一起被回退了；
 * 但旧渲染器里同样手写扫描、同样读 `text[i ± k]`，
 * **这类"下标算错就崩整个界面"的风险是共通的**。
 *
 * 所以这个测试留在仓库里：它守的不是某一份实现，而是
 * "模型吐出来的畸形 Markdown 绝不能让界面崩掉"这条底线。
 *
 * ## 为什么 JVM 单测能抓到这类问题
 *
 * 下标越界**与引擎无关**：`String[-2]` 在 ART 和 JVM 上都抛异常。
 * 会漏的只有"ICU 与 JVM 正则差异"那一类，那个由 [SourceRegexAuditTest] 守着。
 *
 * ⚠️ 断言信息里带上**原始输入**：随机串自己看不出是什么形状触发的。
 */
class MarkdownFuzzTest {

    /** 标记类字符 + 少量普通字符。刻意包含所有"有歧义"的组合。 */
    private val alphabet = listOf(
        // 块标记
        "#", "##", "######", "-", "--", "---", "___", ">", "|", "1.", "2)", "10. ", "- [ ]", "- [x]",
        // 行内标记
        "*", "**", "***", "~~", "`", "```", "~~~", "[", "]", "(", ")", "![", "](", "\\", "\\(", "\\)",
        // 公式
        "$", "$$", "\\[", "\\]", "\\begin{", "}", "\\frac", "\\mathbf", "\\left", "^", "_", "{",
        // HTML 噪音与空白
        "<br>", "<b>", "</b>", "<", ">", " ", "\t", "\n", "\n\n",
        // 普通内容
        "a", "中", "=", ":", "：", "，", "。", "、", "!", "5", "0",
    )

    private fun Block.rawText(): String = when (this) {
        is Block.Heading -> text
        is Block.Bullet -> text
        is Block.Ordered -> text
        is Block.Task -> text
        is Block.Code -> text
        is Block.Quote -> text
        is Block.Paragraph -> text
        is Block.Table -> (header + rows.flatten()).joinToString(" ")
        is Block.Math -> latex
        Block.Rule -> ""
    }

    /**
     * 整条流水线（分块 → 行内渲染）在随机输入下都不能抛异常。
     *
     * ⚠️ 只给**非空**的块文字跑行内渲染 —— 那正是真实调用点做的事
     *（`InlineText` 只在有内容时才被调用）。
     */
    @Test
    fun `随机标记串不会把渲染器搞崩`() {
        val rnd = Random(20261004)
        repeat(6000) { case ->
            val source = buildString {
                repeat(rnd.nextInt(1, 40)) { append(alphabet[rnd.nextInt(alphabet.size)]) }
            }

            val blocks = try {
                parseBlocks(source)
            } catch (t: Throwable) {
                throw AssertionError("parseBlocks 抛异常（第 $case 例）：<<<$source>>>", t)
            }

            for (block in blocks) {
                val text = block.rawText()
                if (text.isEmpty()) continue
                try {
                    renderInline(text, Color.Black, 12.sp)
                } catch (t: Throwable) {
                    throw AssertionError(
                        "renderInline 抛异常（第 $case 例）：<<<$source>>>\n块文字：<<<$text>>>",
                        t,
                    )
                }
            }

            // 这两条也是真实路径：HTML 清理 + 公式解析/压平
            try {
                stripHtmlTags(source)
                MathParser.parse(source)?.flatten()
            } catch (t: Throwable) {
                throw AssertionError("辅助解析抛异常（第 $case 例）：<<<$source>>>", t)
            }
        }
    }

    /**
     * 更"聚焦"的一轮：只拼块级标记，且**强制换行**。
     *
     * 上面那一轮的随机串大多挤在一行里，而"行首判定"的分支只有多行输入才走得到。
     */
    @Test
    fun `多行随机输入也不会把渲染器搞崩`() {
        val lineTokens = listOf(
            "# 标题", "##标题正文", "###", "#####a", "- 甲", "-甲", "-", "--", "---", "***", "___",
            "1. 甲", "2)乙", "- [ ] 甲", "- [x]甲", "> 引用", ">甲", "| a | b |", "|---|", "| 1 |",
            "```", "```kotlin", "~~~", "${'$'}${'$'}", "${'$'}${'$'}x${'$'}${'$'}",
            "\\[", "\\]", "\\begin{equation}", "\\end{equation}",
            "文字##标题", "文字- 条目", "文字---", "**标题**", "**甲**和**乙**", "文字**粗**尾",
            "\\[\\rho\\left(\\frac{a}{b}\\right)\\]", "${'$'}a-b${'$'}", "\\(", "\\)", "", "   ", "\t tab",
        )
        val rnd = Random(77)
        repeat(4000) { case ->
            val source = (0 until rnd.nextInt(1, 8))
                .joinToString("\n") { lineTokens[rnd.nextInt(lineTokens.size)] }

            val blocks = try {
                parseBlocks(source)
            } catch (t: Throwable) {
                throw AssertionError("parseBlocks 抛异常（第 $case 例）：<<<$source>>>", t)
            }
            for (block in blocks) {
                val text = block.rawText()
                if (text.isEmpty()) continue
                try {
                    renderInline(text, Color.Black, 12.sp)
                } catch (t: Throwable) {
                    throw AssertionError(
                        "renderInline 抛异常（第 $case 例）：<<<$source>>>\n块文字：<<<$text>>>",
                        t,
                    )
                }
            }
        }
    }

    /**
     * ⚠️ **用户真机崩溃过的那一类形状**，逐个钉住。
     *
     * ## 症状
     *
     * ```
     * java.lang.StringIndexOutOfBoundsException: length=404; index=-2
     *     at java.lang.String.charAt(Native Method)
     * ```
     *
     * ## 根因
     *
     * 内容里有一个**没有收尾**的 `$`，而它**不是内容的首字符**
     *（首字符在扫描时会被跳过，所以碰不到）。找收尾用的 `indexOf` 返回 `-1`，
     * 那句 `-1 >= 行尾` 挡不住，于是 `-1` 被当成了下标 ——
     * 读 `src[-2]` 直接崩掉整个界面。
     *
     * 所以这一条测的不是"渲染得对不对"，而是"**绝不能抛异常**"。
     */
    @Test
    fun `没有收尾的美元号不能把下标算成负数`() {
        val inputs = listOf(
            "甲\$a",                    // 最小形状：内容中间一个没闭合的 `$`
            "-甲\n\$a-b\$",             // 用户真机上的那一条
            "文字 \$100 元",             // 钱数（走到"不是公式"那道闸）
            "甲\$",                     // `$` 正好是内容的最后一个字符
            "甲\$\$",                   // 没闭合的 `$$`
            "甲\$\$x",                  // 闭合标记在下一行 → 行内找不到
            "甲\\(",                    // 没闭合的 `\(`
            "甲\\[",                    // 没闭合的 `\[`
            "甲`",                      // 没闭合的行内代码
            "甲\$\$x\n\$y",              // 跨行的一对 `$`（行内不该跨行配对）
        )

        for (input in inputs) {
            val blocks = try {
                parseBlocks(input)
            } catch (t: Throwable) {
                throw AssertionError("parseBlocks 抛异常：<<<$input>>>", t)
            }
            val texts = blocks.map { it.rawText() }.filter { it.isNotEmpty() }
            for (text in texts) {
                try {
                    renderInline(text, Color.Black, 12.sp)
                } catch (t: Throwable) {
                    throw AssertionError("renderInline 抛异常：<<<$input>>>", t)
                }
            }
        }
    }
}
