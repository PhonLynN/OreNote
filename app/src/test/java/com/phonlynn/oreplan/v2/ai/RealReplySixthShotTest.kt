package com.phonlynn.oreplan.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户第六张截图的两段**逐字原文**。
 *
 * ## ⚠️ 先说清楚哪些**不是**渲染的锅
 *
 * 图一里的公式长这样（注意：这是普通文字，不是代码块）：
 *
 * ```
 * ρ(∂ u /∂t + ( u ·∇) u ) = −∇p + μ∇² u +  f
 * ```
 *
 * （上面把 `u` 两边的星号去掉了 —— 它们是 Markdown 粗体标记，
 * 但连续两个星号加斜杠会**提前关闭这段 KDoc 注释**，所以这里不照抄。）
 *
 * 这是**纯 Unicode 字符**（`∂` `∇²` `ρ` `μ`）配 Markdown 粗体，
 * **完全没有 LaTeX** —— 模型自己没用代码格式写公式。
 *
 * 对照图二，同一个方程它是这么写的（**正确**）：
 *
 * ```
 * $$
 * \rho\left(\frac{\partial \mathbf{u}}{\partial t} + …
 * $$
 * ```
 *
 * 于是图二渲染成**真正的分式**，图一只能是一行普通文字。
 *
 * **这是模型两次回复之间写法不一致，不是渲染器的缺陷** ——
 * 没有 LaTeX 就没有东西可排版，渲染器能做的只有"把它当普通文字显示"。
 * 用户 2026-10-04 也确认了这一点（「第一个是 ai 自己的问题，没有使用 latex 代码格式」）。
 *
 * 所以本测试类对图一只断言**渲染器该负责的那部分**：
 * 行内标记必须被吃掉、条目的换行结构必须正确。
 * **不断言"公式要渲染成分式"** —— 那个前提在输入里就不存在。
 *
 * ## 图二才是我要修的两件事
 *
 * ```
 * ###每项背后的物理|项 |对应物理 |     ← 标题粘着表格，整张表平铺了
 * ```
 */
class RealReplySixthShotTest {

    // ---------------------------------------------------------------- 图一

    private val shot1: String = listOf(
        "NS方程（Navier–Stokes方程，纳维–斯托克斯方程）是描述**黏性流体运动**的一组非线性偏微分方程，是流体力学里最核心的方程，动量守恒在流体上的具体表达。",
        "",
        "**它长什么样**",
        "",
        "以不可压缩牛顿流体为例，向量形式写作：",
        "",
        "ρ(∂**u**/∂t + (**u**·∇)**u**) = −∇p + μ∇²**u** + **f**",
        "",
        "逐项拆开看：",
        "",
        "- **∂u/∂t** —时间变化率（当地加速度）",
        "- **(u·∇)u** —对流项，流体从一个位置流到另一个位置时速度的变化- ρ(…)整体 —惯性项，质量乘加速度- −∇p —压力梯度力- μ∇²u —黏性力（μ是动力黏度）",
        "- f —体积力，常见的就是重力**物理含义**",
        "",
        "本质上就是牛顿第二定律 **F = ma**用在流体微团上：左边是\"质量 ×加速度\"，右边是各种作用力。它常常和连续性方程（质量守恒）∇·u =0联立求解。",
        "",
        "**为什么重要 /难**",
        "",
        "- **重要**：天气预报、飞机气动设计、管道流动、血液流动、湍流模拟，全都靠它。CFD（计算流体力学）主要就是在数值解这个方程。",
        "- **难**：对流项非线性，方程耦合，直接解析求解只在极少数简单情形可行（如泊肃叶流、库埃特流）。一般的流动只能靠数值方法或近似。",
        "",
        "**一大著名悬案**",
        "",
        "三维 NS方程解的存在性与光滑性，是克雷数学研究所的**千禧年七大难题**之一，悬赏百万美元，至今未解。",
        "",
        "你是在哪门课上碰到它的？如果是课程内容，我可以按你老师可能考的角度再帮你梳一下重点。",
    ).joinToString("\n")

    // ---------------------------------------------------------------- 图二

    private val shot2: String = listOf(
        "NS方程，全称 **Navier–Stokes方程**，是描述流体（液体、气体）运动的核心方程，也是整个流体力学的支柱。名字来自两位分别独立推导出它的学者：Claude-Louis Navier和 George Gabriel Stokes。",
        "",
        "##它想解决什么问题给定一个流体的初始状态和边界条件，能不能算出之后每一时刻、每一处的流速和压强？NS方程就是这个问题的数学表达。它本质上是**牛顿第二定律在流体上的应用**——把\"F = ma\"套到一个个流体微团身上。",
        "",
        "##方程长什么样不可压缩流体的常见形式：",
        "",
        "\$\$",
        "\\rho\\left(\\frac{\\partial \\mathbf{u}}{\\partial t} + \\mathbf{u}\\cdot\\nabla \\mathbf{u}\\right) = -\\nabla p + \\mu \\nabla^2 \\mathbf{u} + \\mathbf{f}",
        "\$\$",
        "",
        "配合连续性方程（质量守恒）：",
        "",
        "\$\$",
        "\\nabla \\cdot \\mathbf{u} =0\$\$",
        "",
        "各项的含义：",
        "- **\$\\mathbf{u}\$**流速场，**\$p\$**压强，**\$\\rho\$**密度，**\$\\mu\$**动力粘度-左边 \$\\rho(\\partial_t \\mathbf{u} + \\mathbf{u}\\cdot\\nabla\\mathbf{u})\$是单位体积的**惯性/动量变化**（含时间变化和对流项）",
        "-右边依次是：**压强梯度力**、**粘性力**（摩擦耗散）、**体积外力**（如重力）",
        "",
        "###每项背后的物理|项 |对应物理 |",
        "|---|---|",
        "| \$\\partial_t \\mathbf{u}\$ |当地加速度（某点流速随时间变） |",
        "| \$\\mathbf{u}\\cdot\\nabla\\mathbf{u}\$ |对流加速度（流体被自身携带搬运，**非线性**的来源） |",
        "| \$-\\nabla p\$ |压力驱动流动 |",
        "| \$\\mu\\nabla^2\\mathbf{u}\$ |粘性，把动能抹平成热 |",
        "| \$\\mathbf{f}\$ |外力，如重力、电磁力 |",
        "",
        "##为什么它这么重要又这么难**重要**：天气预报、飞机气动设计、管道输运、血流模拟、洋流与气候模型，全都靠它。",
        "",
        "**难**：",
        "- **非线性**（那个对流项）让方程几乎无法解析求解，只有极少数特殊情形（如泊肃叶流、库埃特流）有精确解，其余全靠数值计算（CFD）",
        "- **湍流问题**：高雷诺数下解会变得极其杂乱，至今没有普适的封闭描述- **千禧年难题**：三维不可压缩 NS方程解的存在性与光滑性，是克雷数学研究所悬赏百万美元的七大难题之一——即\"给定光滑初始条件，解是否始终光滑、不出现奇点\"，目前无人证明或推翻##一个直观类比可以把它想成\"流体的牛顿定律 +一大堆记账\"：每一小块流体，受力等于它的质量乘加速度；力来自压力推挤、粘性拉扯、外场吸引。妙就妙在这个\"每块流体都在动，又都被旁边的流体带动\"，自己影响别人、别人又反过来影响自己，纠缠出全部复杂性。",
    ).joinToString("\n")

    private fun blocks(md: String) = parseBlocks(md)

    // ================================================================ 图一

    /**
     * ⚠️ **这个用例的断言已经按规范改了**（2026-10-04，用户要求对齐 DS）。
     *
     * ## 原来断言什么
     *
     * 原文：
     * ```
     * - f —体积力，常见的就是重力**物理含义**
     * ```
     *
     * 原来要求：`**物理含义**` 从条目里**断开**、成为独立块（当时把它当"小节标题"）。
     *
     * ## 为什么改掉
     *
     * 那是**我们自己定的规则**（"整行/粘着的粗体 = 小节标题"），
     * **没有规范依据**：
     *
     * | | 行为 |
     * |---|---|
     * | CommonMark / GFM | 粗体就是**行内强调**，不产生新块 |
     * | DS（remark-gfm） | 按规范 —— `**物理含义**` 是上一条里的**粗体文字** |
     * | 我们（改前） | 切成独立"标题"块 |
     *
     * 用户 2026-10-04 明确报了这个方向的问题：
     * 「图二内**体积力**显示位置错误，明明是列表内容却被误解析为小标题」。
     *
     * ## 现在断言什么
     *
     * 与规范一致：`**物理含义**` **留在那一条里**（作为行内粗体），
     * 不再被切成标题。段落的块结构不会被这个粗体打断。
     */
    @Test
    fun `粘在条目尾部的粗体是行内强调而不是标题`() {
        val bullets = blocks(shot1).filterIsInstance<Block.Bullet>().map { it.text }

        // 「体积力」那条把 `**物理含义**` 一起带着（它们是同一句里的话）
        val volumeForce = bullets.firstOrNull { it.contains("体积力") }
        assertTrue("找不到「体积力」那条", volumeForce != null)
        assertTrue(
            "按规范，`**物理含义**` 应当留在这一条里：$volumeForce",
            volumeForce!!.contains("物理含义"),
        )

        // 且**不能**凭空多出一个 Heading
        val all = blocks(shot1)
        assertTrue(
            "`**物理含义**` 不该被当成标题；实际块=${all.map { it::class.simpleName }}",
            all.none { it is Block.Heading && it.text.contains("物理含义") },
        )
    }

    /**
     * 图一里那几条**粘在一行**的条目要拆开。
     *
     * 原文（一条里塞了 4 条）：
     * ```
     * - **(u·∇)u** —对流项…- ρ(…)整体 —惯性项…- −∇p —压力梯度力- μ∇²u —黏性力…
     * ```
     */
    @Test
    fun `图一里挤在一行的条目要拆开`() {
        val bullets = blocks(shot1).filterIsInstance<Block.Bullet>().map { it.text }

        listOf("对流项", "惯性项", "压力梯度力", "黏性力").forEach { needle ->
            assertTrue(
                "「$needle」没有成为独立的一条；bullets=$bullets",
                bullets.any { it.contains(needle) },
            )
        }
    }

    /**
     * 图一那一行公式里的 `**` 必须被**吃掉**。
     *
     * ## 这一条测的是**渲染结果**，不是解析结果
     *
     * ⚠️ 我一开始写成了"块文字里不该有 `**`" —— **那是错的**：
     * `Block.text` 存的是**原始 Markdown**，`**` 本来就该留着，
     * 它是在**渲染时**由 `renderInline` 消费掉的。
     *
     * | 层 | 该不该有 `**` |
     * |---|---|
     * | `Block.text`（解析结果） | **该有** —— 它是原始文字 |
     * | `renderInline(...).text`（渲染结果） | **不该有** —— 被消费了 |
     *
     * 所以这里对 `renderInline` 断言 —— 那才是用户看到的东西。
     */
    @Test
    fun `公式行里的粗体标记要被吃掉`() {
        val rendered = renderInline(
            "ρ(∂**u**/∂t + (**u**·∇)**u**) = −∇p + μ∇²**u** + **f**",
            androidx.compose.ui.graphics.Color.Black,
            androidx.compose.ui.unit.TextUnit.Unspecified,
        ).text

        assertTrue("残留 `**`：$rendered", !rendered.contains("**"))
        // 变量本身一个都不能少
        listOf("∂", "u", "∇", "p", "μ", "f").forEach { ch ->
            assertTrue("变量丢了 `$ch`：$rendered", rendered.contains(ch))
        }
    }

    /**
     * 图一里**每个承载文字的行内段落**渲染后都不该残留 `**`。
     *
     * 对每一块分别渲染，而不是看 `Block.toString()`（那个给的是原始 Markdown）。
     */
    @Test
    fun `图一每块渲染后都不残留粗体标记`() {
        val leftovers = blocks(shot1).mapNotNull { block ->
            val raw = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.text
                is Block.Bullet -> block.text
                is Block.Ordered -> block.text
                is Block.Quote -> block.text
                // 代码块**必须**保持原样（代码里的 `**` 是运算符）
                is Block.Code -> return@mapNotNull null
                else -> return@mapNotNull null
            }
            val rendered = renderInline(
                raw,
                androidx.compose.ui.graphics.Color.Black,
                androidx.compose.ui.unit.TextUnit.Unspecified,
            ).text
            if (rendered.contains("**")) "$raw  →  $rendered" else null
        }

        assertTrue(
            "以下块渲染后仍残留 `**`：\n" + leftovers.joinToString("\n"),
            leftovers.isEmpty(),
        )
    }

    // ================================================================ 图二

    /**
     * ⚠️ **标题粘着表格** —— `###每项背后的物理|项 |对应物理 |`
     *
     * 用户截图里那一整张表**平铺成了文字**（`|` 全露出来），
     * 因为整行被当成"标题"，表格头连在标题里。
     *
     * 期望：标题只取 `每项背后的物理`，**表格从 `|项 |对应物理 |` 开始**正常解析。
     */
    @Test
    fun `标题粘表格时表格要被识别出来`() {
        val tables = blocks(shot2).filterIsInstance<Block.Table>()

        assertTrue(
            "表格没被识别（用户截图里 `|` 全平铺出来了）；实际块=" +
                blocks(shot2).map { it::class.simpleName },
            tables.isNotEmpty(),
        )
        // 表头应当是「项 / 对应物理」
        val t = tables.first()
        assertTrue("表头不对：${t.header}", t.header.any { it.contains("项") })
        assertTrue("表头不对：${t.header}", t.header.any { it.contains("对应物理") })
    }

    /** 表格的**数据行**也要在（不能只认表头）。 */
    @Test
    fun `标题粘表格时数据行也要在`() {
        val t = blocks(shot2).filterIsInstance<Block.Table>().firstOrNull()
        assertTrue("没有表格", t != null)
        assertTrue(
            "表格数据行太少：${t!!.rows.size} 行；rows=${t.rows}",
            t.rows.size >= 4,
        )
    }

    /** `###每项背后的物理` 这个标题本身要出来（三级标题）。 */
    @Test
    fun `粘表格的三级标题要出来`() {
        val headings = blocks(shot2).filterIsInstance<Block.Heading>()
        assertTrue(
            "「每项背后的物理」没成为标题；headings=${headings.map { it.text }}",
            headings.any { it.text.contains("每项背后的物理") },
        )
    }

    /**
     * **多行 `$$…$$`** 必须是块级公式。
     *
     * 原文里两处都是**独占多行**的写法：
     * ```
     * $$
     * \rho\left(…
     * $$
     * ```
     *
     * ⚠️ 第二处更 tricky：开头 `$$` 独占一行，**收尾 `$$` 却粘在内容后面**
     *（`\nabla \cdot \mathbf{u} =0$$`）。
     */
    @Test
    fun `多行块级公式要被识别`() {
        val math = blocks(shot2).filterIsInstance<Block.Math>()
        assertTrue(
            "一个块级公式都没识别；实际块=${blocks(shot2).map { it::class.simpleName }}",
            math.isNotEmpty(),
        )
        assertTrue(
            "NS 方程那条没识别：${math.map { it.latex.take(40) }}",
            math.any { it.latex.contains("rho") || it.latex.contains("nabla") },
        )
    }

    /** 收尾 `$$` 粘在内容后的那一条（`\nabla \cdot \mathbf{u} =0$$`）也要收进公式。 */
    @Test
    fun `收尾符号粘在内容后的公式也要识别`() {
        val math = blocks(shot2).filterIsInstance<Block.Math>()
        assertTrue(
            "连续性方程那条没识别（收尾 \$\$ 粘在内容后）；math=${math.map { it.latex.take(30) }}",
            math.size >= 2,
        )
    }

    /** `##` 粘在标题文字后（`##它想解决什么问题给定一个流体…`）要切成标题 + 正文。 */
    @Test
    fun `粘着的二级标题要切开且不吞掉正文`() {
        val headings = blocks(shot2).filterIsInstance<Block.Heading>().map { it.text }

        // 「它想解决什么问题」应当是标题，而**不能**把后面整段话一起吞进来
        val target = headings.firstOrNull { it.contains("它想解决什么问题") }
        assertTrue("「它想解决什么问题」没成为标题；headings=$headings", target != null)
        assertTrue(
            "标题吞掉了后面的正文（整段话都成了标题）：$target",
            target!!.length < 30,
        )
    }

    /**
     * 图二里**每个承载文字的行内段落**渲染后都不该残留裸标记。
     *
     * ⚠️ 同样：只看 `renderInline` 的结果，不看 `Block.toString()`
     *（后者是原始 Markdown，`**` / `$` 本来就该在）。
     */
    @Test
    fun `图二每块渲染后都不残留裸标记`() {
        val leftovers = blocks(shot2).mapNotNull { block ->
            val raw = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.text
                is Block.Bullet -> block.text
                is Block.Ordered -> block.text
                is Block.Quote -> block.text
                is Block.Code -> return@mapNotNull null
                // 表格单元格自带 `InlineText`，逐格渲染
                is Block.Table -> return@mapNotNull null
                else -> return@mapNotNull null
            }
            val rendered = renderInline(
                raw,
                androidx.compose.ui.graphics.Color.Black,
                androidx.compose.ui.unit.TextUnit.Unspecified,
            ).text

            when {
                rendered.contains("**") -> "残留 `**`：$raw  →  $rendered"
                // `##` 与 `$$` 是**块级**标记，走到行内说明块判定漏了
                raw.contains("##") -> "残留 `##`：$raw"
                raw.contains("\$\$") -> "残留 `\$\$`：$raw"
                else -> null
            }
        }

        assertTrue(
            "以下块渲染后仍残留裸标记：\n" + leftovers.joinToString("\n"),
            leftovers.isEmpty(),
        )
    }

    /** 表格单元格里的行内标记也要被吃掉。 */
    @Test
    fun `表格单元格不残留粗体标记`() {
        val table = blocks(shot2).filterIsInstance<Block.Table>().firstOrNull()
        assertTrue("没有表格", table != null)

        val cells = table!!.header + table.rows.flatten()
        val bad = cells.mapNotNull { cell ->
            val rendered = renderInline(
                cell,
                androidx.compose.ui.graphics.Color.Black,
                androidx.compose.ui.unit.TextUnit.Unspecified,
            ).text
            if (rendered.contains("**")) "$cell → $rendered" else null
        }

        assertTrue("表格单元格残留 `**`：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** 两条原文的内容都不能被吃掉。 */
    @Test
    fun `两段原文的关键内容都在`() {
        listOf(
            shot1 to listOf("动量守恒", "向量形式写作", "牛顿第二定律", "千禧年", "泊肃叶流"),
            shot2 to listOf("Navier–Stokes", "连续性方程", "惯性/动量变化", "湍流", "直观类比"),
        ).forEach { (md, needles) ->
            val text = blocks(md).joinToString("\n") { it.toString() }
            needles.forEach { n ->
                assertTrue("内容丢了：「$n」", text.contains(n))
            }
        }
    }

    /**
     * 图一至少有这些结构（防止"整体退化成一大段"）。
     *
     * ## ⚠️ 断言已按规范改（2026-10-04，用户要求对齐 DS）
     *
     * 原来断言 **4 个 Heading** —— 那 4 个是模型用「整行粗体」写的
     *（`**它长什么样**` / `**物理含义**` / `**为什么重要 /难**` / `**一大著名悬案**`）。
     *
     * 但 CommonMark/GFM 里整行粗体**只是粗体段落**，不是标题；
     * DS（remark-gfm）也按规范渲染成**加粗段落**。
     * 用户 2026-10-04 报的「明明是列表内容却被误解析为小标题」正是这条规则造成的。
     *
     * 所以现在断言：
     *
     * · **小标题不为空**（`##` 认出来的那些要还在）
     * · **整行粗体不再产生 Heading**（`**它长什么样**` 变成普通段落）
     * · 条目的数量结构完整（这是"没退化成一大段"的护栏）
     */
    @Test
    fun `图一结构完整`() {
        val b = blocks(shot1)

        // 整行粗体不再被当成标题
        val boldAsHeading = b.filterIsInstance<Block.Heading>()
            .filter { it.text in setOf("它长什么样", "物理含义", "为什么重要 /难", "一大著名悬案") }
        assertTrue(
            "整行粗体不该再产生标题（按 GFM 规范它们是粗体段落）：${boldAsHeading.map { it.text }}",
            boldAsHeading.isEmpty(),
        )

        // 但内容要活着 —— 那 4 个短语必须还在某个块里
        val all = b.joinToString("\n") { it.toString() }
        listOf("它长什么样", "为什么重要", "一大著名悬案").forEach { needle ->
            assertTrue("内容丢了：「$needle」", all.contains(needle))
        }

        // 条目结构完整（"没退化成一大段"的护栏）
        assertTrue(
            "条目太少：${b.filterIsInstance<Block.Bullet>().size}",
            b.filterIsInstance<Block.Bullet>().size >= 6,
        )
    }
}
