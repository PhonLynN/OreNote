package com.phonlynn.oreplan.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户第四张截图里那**一整段原文**的解析。
 *
 * ## 为什么单独立一个测试类
 *
 * 前三轮我都是"拿到一个样本 → 加一条规则 → 等下一个样本"。这次用户把
 * **完整原文**给了出来，所以这份测试是**逐行照抄**的，不是构造的。
 *
 * ## 原文里暴露的形状（前三轮都没覆盖到的）
 *
 * | 形状 | 例子 | 前三轮认不认 |
 * |---|---|---|
 * | `##` 后面**没有空格** | `##它从哪来流体里随便取一个小块` | ❌ 认不出标题 |
 * | `##标题` 与**正文粘在一起** | `##各项在说什么- $\partial…$：速度随时间变化` | ❌ 整行当一段 |
 * | 标题行里又夹着 `-` 条目 | 同上 | ❌ |
 * | `-` 条目**连续粘在一行** | `- $\rho$：密度-$p$：压力-$\mu$：动力黏度` | ⚠️ 部分 |
 *
 * 关键发现：**`##` 与标题文字之间没有空格**，所以
 * `trimmed.startsWith("#")` 那条路虽然能命中（它只看开头），
 * 但后面「取 level 再把 `##` 去掉」时，
 * `trimmed.drop(level)` 得到的是 `它从哪来流体里…` ——
 * 标题文字和正文**连成一串**，再也分不开。
 */
class RealReplyFourthShotTest {

    /**
     * 用户给的完整原文（逐字照抄，未做任何修改）。
     *
     * 用 `\n` 显式拼接而不是三引号：三引号会把缩进算进去，
     * 而缩进在这个解析器里是**有意义**的（续行判据）。
     */
    private val raw: String = listOf(
        "**Navier–Stokes方程**（简称 NS方程）是描述**黏性流体运动**的基本方程，可以理解为「牛顿第二定律用在流体上」。",
        "",
        "##它从哪来流体里随便取一个小块，它受力包括：",
        "- **压力**（周围流体挤它）",
        "- **黏性力**（内部摩擦，速度不同的层之间互相拖拽）",
        "- **体积力**（比如重力）",
        "",
        "把这些力加起来，等于这块流体的**质量 ×加速度**，就得到 NS方程。",
        "",
        "##常见形式（不可压缩、常密度）",
        "",
        "$$\\rho\\left(\\frac{\\partial \\mathbf{u}}{\\partial t} + (\\mathbf{u}\\cdot\\nabla)\\mathbf{u}\\right) = -\\nabla p + \\mu \\nabla^2 \\mathbf{u} + \\mathbf{f}$$",
        "",
        "- \$\\mathbf{u}\$：速度场（矢量，时空的函数）",
        "- \$\\rho\$：密度- \$p\$：压力- \$\\mu\$：动力黏度- \$\\mathbf{f}\$：体积力（如重力）",
        "",
        "它通常要和**连续性方程**一起用：$\\nabla\\cdot\\mathbf{u}=0$（不可压缩）。",
        "",
        "##各项在说什么- \$\\partial \\mathbf{u}/\\partial t\$：速度随时间变化- \$(\\mathbf{u}\\cdot\\nabla)\\mathbf{u}\$：**对流项**，流体自己带着自己跑，这项是非线性的来源- \$-\\nabla p\$：压力梯度推动流体- \$\\mu\\nabla^2\\mathbf{u}\$：黏性扩散，把速度差异抹平##为什么它这么有名难点全在**对流项**那个非线性上。由此带来两件事：",
        "",
        "1. **湍流**：高雷诺数下解会变得极其复杂、混沌，这就是湍流的核心方程。",
        "2. **千禧年大奖难题**：三维 NS方程在一般情况下**光滑解是否始终存在、会不会在有限时间内「爆掉」**，至今没人证明或证伪——这是克莱数学研究所悬赏百万美元的七个问题之一。",
        "",
        "##一些相关名词- **雷诺数** \$Re=\\rho U L/\\mu\$：判断层流还是湍流的关键无量纲数- **欧拉方程**：把 \$\\mu=0\$丢掉黏性项，就退化成理想流体的方程- **斯托克斯方程**：\$Re \\ll1\$、黏性主导时，忽略对流项顺带一提，你笔记里那门「工程信息基础概论」是全英文授课，如果课上有流体相关的英文材料，NS方程一般写作 **Navier–Stokes equations**，缩写 **N-S equations**。需要我直接从推导角度再讲一遍吗？",
    ).joinToString("\n")

    private fun blocks() = parseBlocks(raw)

    // ---------------------------------------------------------------- 标题

    /**
     * ⚠️ **核心缺陷**：`##标题` 后面没有空格时，标题**必须**在第一个
     * 「看起来像正文开始」的位置断开。
     *
     * ## 原文里的形状
     *
     * ```
     * ##它从哪来流体里随便取一个小块，它受力包括：
     * ```
     *
     * 模型想表达的是「小节标题『它从哪来』」+「正文『流体里随便取一个小块…』」，
     * 但它**中间没写任何分隔**。
     *
     * ## 为什么这条难
     *
     * 这是**语义**问题，不是格式问题 —— 光看字符串无法 100% 确定
     * 「它从哪来」在哪里结束。所以判据只能取"足够好"的启发式：
     * 取开头一段**短**文字当标题（标题通常很短），到标点或长度上限就断。
     *
     * 这里断言的是**退一步也必须成立**的性质：`##` 这两个井号
     * **绝不能原样出现在渲染结果里**（那正是用户看到的"渲染错误"）。
     */
    @Test
    fun `井号不能原样出现在任何块里`() {
        blocks().forEach { block ->
            val text = when (block) {
                is Block.Heading -> block.text
                is Block.Paragraph -> block.text
                is Block.Bullet -> block.text
                is Block.Ordered -> block.text
                is Block.Task -> block.text
                is Block.Quote -> block.text
                is Block.Code -> block.text
                else -> ""
            }
            assertTrue(
                "块里残留了 `##`：$block",
                !text.contains("##"),
            )
        }
    }

    /**
     * `##它从哪来…` 这一行必须产出一个**标题块**（而不是整行当段落）。
     */
    @Test
    fun `井号后无空格也认成二级标题`() {
        val headings = blocks().filterIsInstance<Block.Heading>()
        assertTrue(
            "一个标题都没解析出来；实际块=${blocks().map { it::class.simpleName }}",
            headings.isNotEmpty(),
        )
        assertEquals(
            "小节标题都应当是二级（原文写的是 `##`）",
            listOf(2),
            headings.map { it.level }.distinct(),
        )
    }

    /**
     * ⚠️ **带括号的标题不能被切开**（用户第五张截图：「为什么常密度的度转行了」）。
     *
     * ## 症状
     *
     * ```
     * 常见形式（不可压缩、常密
     * 度）
     * ```
     *
     * 「常密度」被从中间劈开，`度）` 单独占一行。
     *
     * ## 根因（我上一版引入的回归）
     *
     * `##常见形式（不可压缩、常密度）` 这一行**本来只有标题、没有正文**，
     * 理应整行当标题、一个字都不切。但我的 `sentenceCut` 出了两个错：
     *
     * 1. 标点表里**没有 `）`**，也**没有 `、`** —— 一个都没匹配上
     * 2. 于是掉到"长度兜底"，在第 12 个字硬切 ——
     *    正好切在「常密」与「度」之间
     *
     * ## 现在的判据：**括号配对优先**
     *
     * 标题里开了 `（` 且同行闭合 → 在闭合处收住（或整行不切）。
     * 括号**没闭合** → 也整行不切（硬切会把括号劈开，更刺眼）。
     */
    @Test
    fun `带括号的标题不能被切开`() {
        val headings = blocks().filterIsInstance<Block.Heading>()
        val target = headings.firstOrNull { it.text.contains("常见形式") }

        assertTrue("「常见形式…」这个标题没解析出来", target != null)
        assertEquals(
            "带括号的标题被切开了（「常密度」被劈成两半）",
            "常见形式（不可压缩、常密度）",
            target!!.text,
        )
    }

    /** 同一个标题**不能**被拆成"标题 + 正文"两块（那样 `度）` 会单独成段）。 */
    @Test
    fun `带括号的标题不产生多余段落`() {
        val blocks = blocks()
        val paragraphs = blocks.filterIsInstance<Block.Paragraph>()
            .filter { it.text.startsWith("度") }

        assertTrue(
            "多出了「度）」这样的段落，说明标题被切坏了：${paragraphs.map { it.text }}",
            paragraphs.isEmpty(),
        )
    }

    /**
     * 标题里**不能把整节内容吞进来**。
     *
     * ## ⚠️ 这一条的判据是"不能吞掉**下面整节**"，不是"标题必须很短"
     *
     * `##它从哪来流体里随便取一个小块，它受力包括：` 这一行，
     * 「它从哪来」与「流体里随便取一个小块…」之间**没有任何格式信号** ——
     * 边界信息在字符串里不存在，任何规则都只能猜。
     *
     * 所以这条断言放宽成：标题**不能长到把后面的小节也吞掉**
     *（原文里 `##各项在说什么- …##为什么它这么有名…` 那种才是真 bug ——
     * 它把**两个**小节连成了一串）。
     */
    @Test
    fun `标题不能吞掉后面的小节`() {
        val headings = blocks().filterIsInstance<Block.Heading>()

        // 原文里有 5 个小节标题
        assertEquals(
            "小节标题数量不对（说明有的被吞进上一个标题里了）：${headings.map { it.text }}",
            5,
            headings.size,
        )

        // 每个标题里不能再夹着另一个块标记（那说明它吞掉了后面的结构）
        headings.forEach { h ->
            assertTrue("标题里夹着 `-` 条目：${h.text}", !h.text.contains("- "))
            assertTrue("标题里夹着 `##`：${h.text}", !h.text.contains("##"))
            assertTrue("标题里夹着公式：${h.text}", !h.text.contains("$"))
        }
    }

    /**
     * 被切成标题的那部分文字，其**后半段正文必须还在**（不能丢内容）。
     *
     * 这是"宁可少切、不可丢字"的护栏 —— 切错只是排版难看，
     * 丢字是**内容缺失**，严重得多。
     */
    @Test
    fun `标题切分后正文内容不丢`() {
        val allText = buildString {
            blocks().forEach { append(it.toString()); append("\n") }
        }
        // 原文里这几段关键内容必须在
        listOf(
            "流体里随便取一个小块",
            "速度随时间变化",
            "对流项",
            "压力梯度推动流体",
            "黏性扩散",
            "为什么它这么有名",
            "一些相关名词",
            "雷诺数",
            "欧拉方程",
            "斯托克斯方程",
        ).forEach { needle ->
            assertTrue("内容丢了：「$needle」", allText.contains(needle))
        }
    }

    // ---------------------------------------------------------------- 列表

    /**
     * `- $\\rho$：密度- $p$：压力- $\\mu$：动力黏度- $\\mathbf{f}$：体积力`
     * 必须拆成 **4 条**，而不是糊成一条。
     */
    @Test
    fun `连续粘在一行的短条目要全部拆开`() {
        val bullets = blocks().filterIsInstance<Block.Bullet>().map { it.text }

        // 这一行应当贡献 4 条（以「密度」「压力」「动力黏度」「体积力」结尾）
        val fromThatLine = bullets.filter {
            it.contains("密度") || it.contains("压力") || it.contains("动力黏度") || it.contains("体积力")
        }
        assertTrue(
            "那一行没有拆成 4 条；实际 bullets=$bullets",
            fromThatLine.size >= 4,
        )
    }

    /** 三条受力条目（压力/黏性力/体积力）必须各自独立。 */
    @Test
    fun `受力三条各自独立`() {
        val bullets = blocks().filterIsInstance<Block.Bullet>().map { it.text }
        assertTrue(bullets.any { it.contains("压力") && it.contains("周围流体挤它") })
        assertTrue(bullets.any { it.contains("黏性力") && it.contains("内部摩擦") })
        assertTrue(bullets.any { it.contains("体积力") && it.contains("比如重力") })
    }

    /** `##各项在说什么- …` 那一段里的条目也要拆出来。 */
    @Test
    fun `小节标题后面的条目要拆出来`() {
        val bullets = blocks().filterIsInstance<Block.Bullet>().map { it.text }
        assertTrue(
            "「速度随时间变化」那条没拆出来；bullets=$bullets",
            bullets.any { it.contains("速度随时间变化") },
        )
    }

    // ---------------------------------------------------------------- 公式

    /** 独立的 `$$…$$` 必须是**块级公式**。 */
    @Test
    fun `块级公式被识别`() {
        val math = blocks().filterIsInstance<Block.Math>()
        assertTrue("块级公式没识别出来", math.isNotEmpty())
        assertTrue(math.any { it.latex.contains("nabla") })
    }

    /** 行内 `$…$` 不能把 `$` 留在渲染文字里。 */
    @Test
    fun `行内公式不露美元符号`() {
        val rendered = renderInline(
            "连续性方程：\$\\nabla\\cdot\\mathbf{u}=0\$（不可压缩）",
            androidx.compose.ui.graphics.Color.Black,
            androidx.compose.ui.unit.TextUnit.Unspecified,
        ).text

        assertTrue("残留 `\$`：$rendered", !rendered.contains("$"))
        assertTrue("正文丢了：$rendered", rendered.contains("不可压缩"))
    }

    // ---------------------------------------------------------------- 块与行内的边界

    /**
     * 支持行内的块类型，**渲染后**都不能残留标记。
     *
     * ## ⚠️ 我第一版把这条测错了（值得记下来）
     *
     * 我一开始断言的是 `Block.toString()` 里不该有 `**` —— 那是**错的**：
     * `Block` 存的是**原始 Markdown 文字**，`**` 本来就该留着，
     * 它是在**渲染时**由 `renderInline` 消费掉的。
     *
     * 解析层与渲染层是**两件事**：
     *
     * | 层 | 该不该有 `**` |
     * |---|---|
     * | `Block.text`（解析结果） | **该有** —— 它是原始文字 |
     * | `renderInline(...).text`（渲染结果） | **不该有** —— 标记被消费了 |
     *
     * 所以这条改成对 `renderInline` 断言 —— 那才是用户看到的东西。
     *
     * ## 代码块是唯一的例外
     *
     * 代码块走 `VText` 直接输出（不解析），**这是对的** ——
     * 代码里的 `**` 是运算符，不是粗体。所以它不在下面的用例里。
     */
    @Test
    fun `渲染后的行内标记都要被吃掉`() {
        val cases = listOf(
            "标题" to "标题里有**粗体**和\$x^2\$",
            "无序列表" to "列表里有**粗体**和\$x^2\$",
            "有序列表" to "有序里有**粗体**和\$x^2\$",
            "引用" to "引用里有**粗体**和\$x^2\$",
            "段落" to "段落里有**粗体**和\$x^2\$",
        )

        val failures = cases.mapNotNull { (name, src) ->
            val rendered = renderInline(
                src,
                androidx.compose.ui.graphics.Color.Black,
                androidx.compose.ui.unit.TextUnit.Unspecified,
            ).text
            when {
                rendered.contains("**") -> "$name 残留 `**`：$rendered"
                rendered.contains("$") -> "$name 残留 `\$`：$rendered"
                else -> null
            }
        }

        assertTrue(
            "以下块的行内标记没被消费：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * 直接钉住"标题渲染走的是能解析行内的那条路"。
     *
     * 上面那条测的是**渲染函数的行为**，这条测的是**调用点用了哪个组件**——
     * 两者缺一不可：`renderInline` 对了但渲染用了 `VText`，用户照样看到 `**`。
     *
     * ## 做法
     *
     * 从源码里截出 `is Block.Heading ->` 到下一个 `is Block.` 之间的片段，
     * 断言它含 `InlineText(`。
     *
     * ⚠️ 这是**源码级**断言（不是运行时行为）。看起来笨，但这是唯一能在
     * JVM 单测里守住"用了哪个 composable"的办法 —— 而这个差异
     * 已经在真机上暴露过一次（用户第五张截图）。
     *
     * ## 自检
     *
     * 下面同时验证了**判据本身有效**：拿一段用 `VText` 的假源码喂给
     * 同一个判定函数，它必须返回"不合格"。不这么测的话，
     * 万一我的片段截取逻辑有 bug（永远截到空串），这条测试会**假绿**。
     */
    @Test
    fun `标题分支必须使用 InlineText`() {
        val file = java.io.File(
            "src/main/java/com/phonlynn/oreplan/v2/ai/AiMarkdownText.kt",
        )
        assertTrue("找不到源文件：${file.absolutePath}", file.exists())

        // ---- 真实源码：必须合格
        assertTrue(
            "`Block.Heading` 分支没有用 `InlineText` —— 标题里的 `**粗体**` 会原样露出。\n" +
                "（用户第五张截图报过这个 bug）",
            headingBranchUsesInlineText(file.readText()),
        )

        // ---- 自检：拿一段用 VText 的假源码，判据必须报"不合格"
        val counterExample = """
            is Block.Heading -> VText(
                block.text,
                baseStyle,
                color = color,
            )

            is Block.Bullet -> BulletRow(0) { }
        """.trimIndent()
        assertTrue(
            "判据本身失效了：用 `VText` 的假源码也被判为合格",
            !headingBranchUsesInlineText(counterExample),
        )
    }

    /**
     * `源码` 里 `Block.Heading` 那个分支是否用了 `InlineText`。
     *
     * 抽成顶层函数是为了能被上面那条测试的**自检**部分复用
     *（拿反例喂进来，确认它真的会返回 false）。
     */
    private fun headingBranchUsesInlineText(source: String): Boolean {
        val marker = "is Block.Heading ->"
        val at = source.indexOf(marker)
        if (at < 0) return false

        // 截到下一个 `is Block.` 之前
        val next = source.indexOf("is Block.", at + marker.length)
        val segment = source.substring(at, if (next > 0) next else source.length)

        return segment.contains("InlineText(")
    }

    /** 有序列表两条各自独立。 */
    @Test
    fun `有序列表两条独立`() {
        val ordered = blocks().filterIsInstance<Block.Ordered>().map { it.text }
        assertTrue("湍流那条没出来", ordered.any { it.contains("湍流") })
        assertTrue("千禧年那条没出来", ordered.any { it.contains("千禧年") })
    }

    private companion object {
        /**
         * 标题最多多少字。
         *
         * 取 12：原文里真实的小节标题是「它从哪来」「常见形式」「各项在说什么」
         * 「为什么它这么有名」「一些相关名词」—— 最长 8 个字。
         * 12 给了一点余量，同时能挡住"整行被当成标题"。
         */
        const val TITLE_MAX_CHARS = 12
    }
}
