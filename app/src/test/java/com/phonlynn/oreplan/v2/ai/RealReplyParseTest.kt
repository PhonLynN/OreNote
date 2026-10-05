package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.v2.ai.math.MathParser
import com.phonlynn.oreplan.v2.ai.math.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**真机截图里的那段真实回复**跑一遍解析。
 *
 * ## 为什么要用真实文本而不是构造的样例
 *
 * 用户报「历史对话不崩但一片空白、流式一崩」。空白意味着解析器**把内容吃掉了** ——
 * 而吃掉的触发条件藏在模型真实输出的写法里（`$$` 与 `\[` 混用、列表项里塞行内公式、
 * `---` 紧贴列表、引用不写空格…），构造的干净样例一个都碰不到。
 *
 * 所以这里照抄截图里的原文（NS 方程那次回复），断言：
 *  ① 不能抛异常
 *  ② 解析出来的块数要和肉眼看到的段落数量级相当（不能被某一条分支整段吞掉）
 *  ③ 关键文字必须存活到某个块里
 */
class RealReplyParseTest {

    private val nsReply = """
当然可以，下面我尽量用温柔、清晰的方式，把 **Navier-Stokes 方程（NS方程）** 讲明白。

## 一、NS方程是什么？

**Navier-Stokes方程**是描述流体运动的一组偏微分方程。
它本质上是在说：
> 流体的运动，遵守牛顿第二定律。
也就是：
        动量变化=外力+压力+粘性力
它可以描述空气、水、油、血液、烟气、海浪、天气、气流等几乎所有连续流体的运动。

---

## 二、最核心的形式不可压缩流体的 NS 方程通常写成：

$$
\rho\left(\frac{\partial \mathbf{u}}{\partial t} + (\mathbf{u} \cdot \nabla)\mathbf{u}\right) = -\nabla p + \mu \nabla^2 \mathbf{u} + \mathbf{f}
$$

再配上连续性方程：

$$
\nabla \cdot \mathbf{u} = 0
$$

其中：
- \(\mathbf{u}\): 速度场
- \(p\)：压强
- \(\rho\)：密度
- \(\mu\)：动力粘度
- \(\mathbf{f}\): 外力（如重力）
- \(\nabla\): 空间梯度
- \(\nabla^2\): 拉普拉斯算子
---
## 三、每一项是什么意思？

1.左边: 惯性项\[\rho \left( \frac{\partial \mathbf{u}}{\partial t} + (\mathbf{u} \cdot \nabla)\mathbf{u} \right)\]
表示流体微团的动量变化。
它分成两部分：
- \(\frac{\partial \mathbf{u}}{\partial t}\): 局部加速度，某一点速度随时间变化
- \((\mathbf{u} \cdot \nabla)\mathbf{u}\): 对流加速度，流体从一个地方运动到另一个地方导致的速度变化
这一项是 NS 方程里最"难"的部分，因为它非线性。

2.右边第一项：压力梯度\[-\nabla p\]
流体总是从高压区流向低压区。
所以压力梯度会推动流体运动。

3.右边第二项：粘性力\[\mu \nabla^2 \mathbf{u}\]
    """.trimIndent()

    @Test
    fun `真实回复不能被解析器吞掉`() {
        val blocks = parseBlocks(nsReply)

        // ② 量与肉眼可见的段落数相当（原文约 20 个块）
        assertTrue("块数太少，说明有分支把内容吞了：$blocks", blocks.size >= 15)

        // ③ 关键文字必须存活
        val all = blocks.joinToString("\u0001") { block -> block.rawText() }
        for (probe in listOf(
            "Navier-Stokes方程",
            "遵守牛顿第二定律",
            "动量变化",
            "其中",
            "速度场",
            "动力粘度",
            "空间梯度",
            "每一项是什么意思",
            "局部加速度",
            "压力梯度",
            "粘性力",
        )) {
            assertTrue("「$probe」被吞掉了", all.contains(probe))
        }

        // 两个 `$$` 公式都应当被认成 Math
        assertEquals("应当有 2 个块级公式", 2, blocks.filterIsInstance<Block.Math>().size)
    }

    /** 行内公式渲染不能抛异常，也不能把反斜杠漏到界面上。 */
    @Test
    fun `行内公式解析不抛异常且不留反斜杠`() {
        val line = """- \(\mathbf{u}\): 速度场"""
        val text = renderInline(line, androidx.compose.ui.graphics.Color.Black, 14.sp)

        assertTrue("行内公式没被认出来：${text.text}", text.text.contains("u"))
        assertFalse("漏了反斜杠：${text.text}", text.text.contains("\\("))
        assertFalse("漏了反斜杠：${text.text}", text.text.contains("\\)"))
    }

    /** 列表项里的 `\[…\]`（行内位置出现 display math）不能把整行弄没。 */
    @Test
    fun `列表项里的方括号公式不丢内容`() {
        val blocks = parseBlocks("""1.左边: 惯性项\[\rho \left( \frac{\partial \mathbf{u}}{\partial t} \right)\]""")
        val text = blocks.joinToString("") { it.rawText() }
        assertTrue("整行被吃掉了：$blocks", text.contains("惯性项"))
    }

    /**
     * **把整条回复的每一行都过一遍 `renderInline`** —— 闪退就藏在这条路径上。
     *
     * 这是在"最后一个正常版本(565) → 第一个闪退版本(566)"的差异里做二分：
     * 566 相对 565 新增了 `\(…\)` 行内公式识别，而真实回复里满是 `\(\mathbf{u}\)`、
     * `\(\nabla^2\)`、`\(\frac{\partial \mathbf{u}}{\partial t}\)` 这类写法。
     * 只要其中任何一种让 `renderInline` 抛异常，这里就会红。
     */
    @Test
    fun `整条回复的每一行渲染都不抛异常`() {
        for (block in parseBlocks(nsReply)) {
            val text = block.rawText()
            if (text.isEmpty()) continue
            // 不 catch：抛出来就是我们要找的东西
            renderInline(text, androidx.compose.ui.graphics.Color.Black, 14.sp)
        }
        // 单独再点名跑一遍所有含行内公式的行
        nsReply.lines()
            .filter { it.contains("\\(") || it.contains("$") || it.contains("\\[") }
            .forEach { renderInline(it, androidx.compose.ui.graphics.Color.Black, 14.sp) }
    }

    /**
     * 段落间距用到的 `TextUnit.toDp()` —— 它在**每条消息**上都会执行。
     *
     * 这是 566 相对 565 唯一"每条消息都跑"的新增代码，所以要单独确认它
     * 既不会抛异常、也不会算出离谱的数值（离谱的间距会把内容挤没）。
     */
    @Test
    fun `字号转 dp 不会抛异常且数值合理`() {
        // 真实设备常见的密度档：2.75（xxhdpi）/ 3.5（xxxhdpi）
        for (d in listOf(1f, 2.75f, 3.5f)) {
            val density = androidx.compose.ui.unit.Density(density = d, fontScale = 1f)
            val unit = with(density) { 16.sp.toDp() }
            assertTrue("density=$d 时算出的间距基准是 $unit，超出合理范围", unit.value in 4f..64f)
        }
    }

    /**
     * 模型常把列表标记写得**不带空格**（`-内容`），而 CommonMark 要求 `- 内容`。
     *
     * 上一版按规范卡空格，于是这些行退化成普通段落、几条清单被合并成一大段，
     * 横杠还留在文字里 —— 用户截图里就是
     * 「-流体怎样流动-速度如何随时间空间变化-压强、黏性、外力如何影响流动」。
     *
     * 这和之前 `>` 引用那个 bug 是同一个毛病，所以这次连边界一起钉住。
     */
    @Test
    fun `列表标记不带空格也要认`() {
        val blocks = parseBlocks(
            """
            -流体怎样流动
            -速度如何随时间空间变化
            -压强、黏性、外力如何影响流动
            """.trimIndent(),
        )
        val bullets = blocks.filterIsInstance<Block.Bullet>()
        assertEquals("三条都该是列表项，实际：$blocks", 3, bullets.size)
        assertEquals("流体怎样流动", bullets[0].text)
        assertEquals("压强、黏性、外力如何影响流动", bullets[2].text)
    }

    /** 但底线要守住，否则会误伤正文。 */
    @Test
    fun `不是列表的情况不能被误判`() {
        // 行首负数
        assertTrue(parseBlocks("-5 度很冷").none { it is Block.Bullet })
        // 破折号 / 分隔线
        assertTrue(parseBlocks("--").none { it is Block.Bullet })
        // 星号强调：`*` 不带空格一律不当标记
        assertTrue(parseBlocks("*斜体*").none { it is Block.Bullet })
        // 词中的连字符（纳维-斯托克斯）本来就不在行首，不该被拆
        val hyphenated = parseBlocks("纳维-斯托克斯方程")
        assertEquals("纳维-斯托克斯方程", hyphenated.filterIsInstance<Block.Paragraph>().single().text)
    }

    /** 行尾粘着的分隔线要拆出来，而不是跟着文字显示。 */
    @Test
    fun `行尾粘着的分隔线被拆出来`() {
        val blocks = parseBlocks("-数值仿真与工程计算---")

        val bullet = blocks.filterIsInstance<Block.Bullet>().single()
        assertEquals("数值仿真与工程计算", bullet.text)
        assertEquals("拆出来之后应当跟着一条分隔线：$blocks", 1, blocks.count { it is Block.Rule })
    }

    /** 代码块里的 `---` 不能被当成行尾分隔线拆掉。 */
    @Test
    fun `代码块里的分隔线不受影响`() {
        val blocks = parseBlocks("```\nkey: value---\n```")
        val code = blocks.filterIsInstance<Block.Code>().single()
        assertTrue("代码内容被改了：${code.text}", code.text.contains("value---"))
        assertTrue(blocks.none { it is Block.Rule })
    }

    /**
     * 单独成行的分隔线要认。
     *
     * ⚠️ 这里**只测整行同一种字符**的写法。我曾经顺手放宽过
     * 「标记之间夹空格」（`- - -`）和「中文破折号」（`——`），
     * 但用户明确说那不是他遇到的问题 —— 已撤回，所以这里也不再测那些。
     *
     * 用户真正遇到的是**粘在文字行尾**的 `---`，由下面那条测试覆盖。
     */
    @Test
    fun `单独成行的分隔线都认`() {
        for (line in listOf("---", "----", "***", "___")) {
            val blocks = parseBlocks("上面\n\n$line\n\n下面")
            assertEquals(
                "「$line」应当被认成分隔线，实际 $blocks",
                1,
                blocks.count { it is Block.Rule },
            )
        }
    }

    /** 底线：不能被正文里的连字符误伤。 */
    @Test
    fun `正文里的连字符不会被误认成分隔线`() {
        for (line in listOf("--", "纳维-斯托克斯方程", "-5 度", "a - b", "C# 教程")) {
            val blocks = parseBlocks(line)
            assertTrue(
                "「$line」不该是分隔线，实际 $blocks",
                blocks.none { it is Block.Rule },
            )
        }
    }

    /**
     * 模型把**多条清单挤在一行**里时，要拆成多条。
     *
     * 用户截图里的实际内容（「其中：」那一段）：只有一个圆点，
     * 后面拖着 `- ρ：密度- p：压强- …` 一大段。
     * 参照图（DeepSeek App）里同一条回复是 5 条独立清单。
     */
    @Test
    fun `挤在一行的清单被拆开`() {
        val blocks = parseBlocks(
            """- \(u\): 流体速度场- \(\rho\): 密度- \(p\): 压强- \(\mu\): 动力粘度""",
        )
        val bullets = blocks.filterIsInstance<Block.Bullet>()
        assertEquals("应当拆成 4 条，实际 $blocks", 4, bullets.size)
        assertEquals("""\(u\): 流体速度场""", bullets[0].text)
        assertEquals("""\(\mu\): 动力粘度""", bullets[3].text)
    }

    /**
     * ⚠️ 但不能误伤 —— 用户同一张截图里就有反例。
     *
     * 判据的边界全在这里钉死，改动判据必须让这些继续通过。
     */
    @Test
    fun `拆分判据不误伤正常文本`() {
        // 中文连接号：连字符后面不是空格
        run {
            val b = parseBlocks("- 纳维-斯托克斯方程是核心").filterIsInstance<Block.Bullet>()
            assertEquals(1, b.size)
            assertEquals("纳维-斯托克斯方程是核心", b[0].text)
        }
        // 正文里的破折号：连字符前面有空格
        run {
            val b = parseBlocks("- 甲 - 乙").filterIsInstance<Block.Bullet>()
            assertEquals("「- 甲 - 乙」不该被拆开，实际 $b", 1, b.size)
            assertEquals("甲 - 乙", b[0].text)
        }
        // 没有挤行的正常多行清单：不能被多拆也不能被少拆
        run {
            val b = parseBlocks("- 甲\n- 乙\n- 丙").filterIsInstance<Block.Bullet>()
            assertEquals(3, b.size)
        }
    }

    /**
     * 连续的引用行要**合并成一个块**，否则每行各画一条短竖线，
     * 看起来是好几段独立引用。
     */
    @Test
    fun `连续引用合并成一个块`() {
        val blocks = parseBlocks("> 第一行\n> 第二行\n> 第三行")

        val quotes = blocks.filterIsInstance<Block.Quote>()
        assertEquals("三行应当合并成一块，实际 $blocks", 1, quotes.size)
        assertEquals("第一行\n第二行\n第三行", quotes.single().text)
    }

    /** 中间隔了空行的引用是**两段**，不该合并。 */
    @Test
    fun `空行隔开的引用不合并`() {
        val blocks = parseBlocks("> 甲\n\n> 乙")
        assertEquals(2, blocks.filterIsInstance<Block.Quote>().size)
    }

    /**
     * `\nabla` 不能变成 `abla`。
     *
     * DeepSeek 官方 App 就在这里翻车：它把 `\n` 当成换行吃掉了，
     * 于是 `(u · \nabla)u` 渲染成 `(u · abla)u`。这是用户给的参考图里实测到的。
     * 我们的解析器按 `\命令` 整体取词，不该有这个问题 —— 钉一下防止以后改坏。
     */
    @Test
    fun `反斜杠命令不会被当成转义换行`() {
        fun math(src: String) = MathParser.parse(src)

        assertEquals("∇", math("""\nabla""")!!.flatten())
        assertEquals("(u·∇)u", math("""(\mathbf{u} \cdot \nabla)\mathbf{u}""")!!.flatten())
        assertEquals("∇·u=0", math("""\nabla \cdot \mathbf{u} = 0""")!!.flatten())
    }

    /** 表格的对齐标记要读出来，不能全丢成左对齐。 */
    @Test
    fun `表格认冒号对齐`() {
        val blocks = parseBlocks(
            """
            | 项目 | 分数 | 备注 |
            | :--- | :--: | ---: |
            | 数学 | 90 | 优秀 |
            """.trimIndent(),
        )
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(
            listOf(TableAlign.START, TableAlign.CENTER, TableAlign.END),
            table.alignments,
        )
    }

    /** 没写冒号时一律左对齐。 */
    @Test
    fun `表格缺省左对齐`() {
        val blocks = parseBlocks("| a | b |\n| --- | --- |\n| 1 | 2 |")
        val table = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(listOf(TableAlign.START, TableAlign.START), table.alignments)
    }

    /**
     * HTML 噪音要清掉：模型很爱写 `<br>`、`<b>`，原样显示会露出尖括号。
     *
     * ⚠️ 但不能误伤正文里的小于号 —— `a < b`、`a<b` 都得原样保留。
     */
    @Test
    fun `HTML 标签被清掉而小于号保留`() {
        assertEquals("第一行\n第二行", stripHtmlTags("第一行<br>第二行"))
        assertEquals("加粗", stripHtmlTags("<b>加粗</b>"))
        assertEquals("带下标的 H2O", stripHtmlTags("带下标的 H<sub>2</sub>O"))
        // 小于号不能被吃掉
        assertEquals("a < b", stripHtmlTags("a < b"))
        assertEquals("a<b", stripHtmlTags("a<b"))
        assertEquals("x <3 且 y >2", stripHtmlTags("x <3 且 y >2"))
    }

    /** 图片语法不能把 `!` 和地址裸露出来。 */
    @Test
    fun `图片只显示说明文字`() {
        val withAlt = renderInline(
            "看图 ![流程图](https://example.com/a.png) 结束",
            androidx.compose.ui.graphics.Color.Black,
            12.sp,
        )
        assertTrue("露出了感叹号或地址：${withAlt.text}", !withAlt.text.contains("!"))
        assertTrue("露出了地址：${withAlt.text}", !withAlt.text.contains("example.com"))
        assertTrue("应当保留说明文字：${withAlt.text}", withAlt.text.contains("流程图"))

        val noAlt = renderInline("![](x.png)", androidx.compose.ui.graphics.Color.Black, 12.sp)
        assertEquals("［图片］", noAlt.text)
    }
}

/** 取一个块里的全部可见文字，用于断言"内容没被吞"。 */
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
