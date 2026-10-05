package com.phonlynn.oreplan.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有序 / 无序列表**解析**的鲁棒性。
 *
 * ## 为什么单独立一个测试类（用户报的 bug）
 *
 * 用户原话：
 *
 * > 「AI 输出的部分有序列表和无序列表无法正常显示，不转行，堆积在一起」
 *
 * 「堆积在一起」这个描述很关键 —— 它不是"样式不好看"，而是**解析阶段就把
 * 多条清单并成了一条**。所以问题只能在 `parseBlocks` 这一层查，
 * 渲染层（`BulletRow`）再怎么调都没用。
 *
 * ## 模型实际会怎么写（这几条都是真实见过的写法）
 *
 * | 写法 | 例子 |
 * |---|---|
 * | 标记后**没有空格** | `-流体速度场` / `1.第一步` |
 * | 多条**挤在同一行** | `- A - B - C` |
 * | 有序标记**带括号** | `1) 甲` / `（1）甲` |
 * | 序号**超过一位** | `10. 第十条` |
 * | 列表项**本身折行**（续行有缩进） | `- 很长的一段…\n  继续` |
 * | 前一条**没有空行**直接跟下一条 | `- A\n- B`（正常） |
 */
class ListParseTest {

    private fun bullets(markdown: String): List<Block.Bullet> =
        parseBlocks(markdown).filterIsInstance<Block.Bullet>()

    private fun ordered(markdown: String): List<Block.Ordered> =
        parseBlocks(markdown).filterIsInstance<Block.Ordered>()

    // ---------------------------------------------------------------- 基本形状

    /** 标准写法：每行一条，不能并成一条。 */
    @Test
    fun `标准无序列表逐行成条`() {
        val list = bullets("- 甲\n- 乙\n- 丙")
        assertEquals(listOf("甲", "乙", "丙"), list.map { it.text })
    }

    @Test
    fun `标准有序列表逐行成条`() {
        val list = ordered("1. 甲\n2. 乙\n3. 丙")
        assertEquals(listOf("甲", "乙", "丙"), list.map { it.text })
        assertEquals(listOf("1.", "2.", "3."), list.map { it.marker })
    }

    /** 序号超过一位时标记要完整带出来（`10.` 不能只剩 `1`）。 */
    @Test
    fun `两位序号完整保留`() {
        val list = ordered("9. 甲\n10. 乙\n11. 丙")
        assertEquals(listOf("9.", "10.", "11."), list.map { it.marker })
        assertEquals(listOf("甲", "乙", "丙"), list.map { it.text })
    }

    /** 标记后没有空格 —— 模型很常这么写（`-内容`）。 */
    @Test
    fun `标记后无空格也认`() {
        val list = bullets("-甲\n-乙")
        assertEquals(listOf("甲", "乙"), list.map { it.text })
    }

    /** 有序列表的 `)` 写法。 */
    @Test
    fun `右括号序号也认`() {
        val list = ordered("1) 甲\n2) 乙")
        assertEquals(listOf("甲", "乙"), list.map { it.text })
    }

    // ---------------------------------------------------------------- 挤在一行

    /**
     * 多条清单被挤在**同一行**里（模型常在公式场景这么写）。
     *
     * 判据见扫描器的 `gluedBulletAt`：连字符紧贴前一个非空白字符 + 后面是空格。
     * 这两条**不再靠预处理补丁** —— 条目正文读到"下一个块从这里开始"为止，
     * 而那个位置可以在行中间。
     */
    @Test
    fun `挤在一行的无序列表要拆开`() {
        val list = bullets("- 流体速度场- 密度- 压强")
        assertEquals(listOf("流体速度场", "密度", "压强"), list.map { it.text })
    }

    /**
     * ⚠️ **连字符后面没有空格**时也要拆（用户 2026-10-04 第二次报的同一个 bug）。
     *
     * ## 这一条是从用户的真实截图里抄下来的
     *
     * 用户第二次报：「一个无序列表内的某几项会紧接在上一项后面，而不是转行，
     * 但又不是每行都有 bug」。
     *
     * 截图里那些"没拆开"的行长这样：
     *
     * ```
     * 两个实验报告-第一节人工智能导论：下下节（第三节）带电脑
     * 大一两次体测，大二大三各一次-第一节思政课：法治部分占考核30%分值
     * 课堂笔记计入平时成绩-第二节英语课：10月15日前完成「5+5」计划作业
     * ```
     *
     * 注意连字符**两侧**：
     *
     * | | 前一字符 | 后一字符 |
     * |---|---|---|
     * | 我原来测的样本 `速度场- ρ` | `场` 非空 ✓ | **空格** ✓ |
     * | 用户截图里 `报告-第一节` | `告` 非空 ✓ | **`第`，不是空格** ✗ |
     *
     * ## 为什么"不是每行都有 bug"
     *
     * 因为**只有后跟空格的那些才拆开了**。同一份回复里两种写法混着出现，
     * 于是看起来"有的行好、有的行坏" —— 这正是用户说的"很奇怪"。
     * 根因不是随机的，是**我的判据只覆盖了两种写法中的一种**。
     */
    @Test
    fun `连字符后无空格也要拆开`() {
        val list = bullets("- 两个实验报告-第一节人工智能导论：带电脑")
        assertEquals(
            listOf("两个实验报告", "第一节人工智能导论：带电脑"),
            list.map { it.text },
        )
    }

    /** 用户截图里的多行原样回放（一次回复里混着两种写法）。 */
    @Test
    fun `用户截图里的真实混排`() {
        val list = bullets(
            """
            - 第一节计算机课：PPT未更新；64学时（45理论）；两个实验报告-第一节人工智能导论：下下节（第三节）带电脑
            - 第一节体育课：武术、空竹、跳绳三选一；大一两次体测，大二大三各一次-第一节思政课：法治部分占考核30%分值
            - 第一节英语课：课堂检测，课堂笔记计入平时成绩-第二节英语课：10月15日前完成「5+5」计划作业
            """.trimIndent(),
        )

        // 三行原文，各自还内含一个连字符 → 拆完应该是 6 条
        assertEquals(
            listOf(
                "第一节计算机课：PPT未更新；64学时（45理论）；两个实验报告",
                "第一节人工智能导论：下下节（第三节）带电脑",
                "第一节体育课：武术、空竹、跳绳三选一；大一两次体测，大二大三各一次",
                "第一节思政课：法治部分占考核30%分值",
                "第一节英语课：课堂检测，课堂笔记计入平时成绩",
                "第二节英语课：10月15日前完成「5+5」计划作业",
            ),
            list.map { it.text },
        )
    }

    /**
     * ⚠️ 放宽判据后**不能误伤**这些写法（放开"后面不必是空格"最容易踩的坑）。
     */
    @Test
    fun `破折号与连接号不能被误拆`() {
        // 前后都有空格 = 破折号
        assertEquals(listOf("甲 - 乙"), bullets("- 甲 - 乙").map { it.text })
        // 后面不是空格且不是中文/字母（连字符连接两个词）
        assertEquals(listOf("纳维-斯托克斯方程"), bullets("- 纳维-斯托克斯方程").map { it.text })
        // 行首负数
        assertEquals(listOf("-5 度"), bullets("- -5 度").map { it.text })
    }

    // ---------------------------------------------------------------- 用户第三张截图

    /**
     * ⚠️ **第三张截图暴露的那一类**（用户 2026-10-04：「出现了多处渲染错误」）。
     *
     * ## 截图里的原文形状
     *
     * ```
     * 为什么它这么有名-**动量守恒**：它其实是动量守恒方程…
     *   …流体力学方程组- **非线性带来的麻烦**：$\mathbf{u}\cdot\nabla\mathbf{u}$…
     *   …都由此而来-**千禧年难题**：…至今未解##常见简化- **欧拉方程**：令 $\mu=0$…
     * ```
     *
     * ## 三个各自独立的缺陷
     *
     * | 现象 | 根因 |
     * |---|---|
     * | `-**动量守恒**` 没拆成两条 | 连字符后面是 **`*`**，不在我的白名单里 |
     * | `##常见简化` 原样露出 | **标题标记粘在正文里**（前面没有换行），`startsWith("#")` 认不出来 |
     * | `$\mathbf{u}…$` 原样露出 | 行内公式被**拆到了两行**（`$` 与收尾 `$` 不在同一段） |
     *
     * 这一条先钉住**第一种**（最容易确认、也是"堆积"的主因）。
     */
    @Test
    fun `连字符后紧跟星号也要拆开`() {
        val list = bullets("- 为什么它这么有名-**动量守恒**：它是动量守恒方程")
        assertEquals(
            listOf("为什么它这么有名", "**动量守恒**：它是动量守恒方程"),
            list.map { it.text },
        )
    }

    /**
     * 多段真实混排（第三张截图的尾部那一大段）。
     *
     * 这一段在截图里**整块糊成一段**，既没有拆开、`##` 也露在外面。
     *
     * ⚠️ `##常见简化` **不在这个列表里** —— 它现在被正确地识别成**标题**
     *（由行级预处理拆成独立一行后交给主循环），所以 `bullets` 里看不到它。
     * 这正是想要的：原先它作为一条「文案里带 `##` 的条目」露出来。
     * 标题那半边由 `粘在正文里的标题标记要断开` 单独钉住。
     */
    @Test
    fun `第三张截图的混排`() {
        val text = "流体力学方程组- **非线性带来的麻烦**：让方程难以求解" +
            "-**千禧年难题**：至今未解##常见简化- **欧拉方程**：令 \$\\mu=0\$"

        val list = bullets("- $text")

        assertEquals(
            listOf(
                "流体力学方程组",
                "**非线性带来的麻烦**：让方程难以求解",
                "**千禧年难题**：至今未解",
                "**欧拉方程**：令 \$\\mu=0\$",
            ),
            list.map { it.text },
        )

        // 那个 `##常见简化` 必须变成标题，而不是留在条目文字里
        val headings = parseBlocks("- $text").filterIsInstance<Block.Heading>()
        assertTrue(
            "`##常见简化` 没变成标题；headings=${headings.map { it.text }}",
            headings.any { it.text.contains("常见简化") },
        )
    }

    /**
     * 粘在正文里的 `##` 要被**断开成独立的一行**，从而成为一个真标题。
     *
     * 用户第三张截图里的 `至今未解##常见简化` —— `##` 前面没有换行，
     * 于是 `startsWith("#")` 认不出来，`##` 原样渲染出来（用户看到的"渲染错误"）。
     */
    @Test
    fun `粘在正文里的标题标记要断开`() {
        val blocks = parseBlocks("…至今未解##常见简化")

        // `##常见简化` 必须成为**独立的标题块**，而不是段落里的一串文字
        val heading = blocks.filterIsInstance<Block.Heading>().single()
        assertEquals(2, heading.level)
        assertEquals("常见简化", heading.text)
    }

    /**
     * ⚠️ **写在句子中间的 `$$…$$`** 不能原样露出（用户第三张截图的"渲染错误"之一）。
     *
     * ## 截图里的原文
     *
     * ```
     * **非线性带来的麻烦**： $\mathbf{u}\cdot\nabla\mathbf{u}$让方程难以解析求解
     * ```
     *
     * 用户看到的是**一串裸的 LaTeX**。
     *
     * ## 当时的根因（两条路都走不到）
     *
     * | 路径 | 为什么没接住 |
     * |---|---|
     * | 块级公式 | 判据只看行首的两个字符 —— 它在句子中间 |
     * | 行内公式 | 分支条件**主动排除了 `$$`**（怕块级公式被行内抢走） |
     *
     * 现在两边的分隔符是同一套（`$$` / `\[` / `\(` / `$` 在行内都认），
     * 所以不会再出现"哪条路都不接"的空档。
     * 行内与块级怎么分工见 `appendInlineMarkup` 的注释。
     *
     * ## 这条测的是"渲染后的文字里不该有裸的 LaTeX 命令"
     */
    @Test
    fun `句子中间的块级公式标记不能原样露出`() {
        val rendered = renderInline(
            "麻烦：\$\$\\mathbf{u}\\cdot\\nabla\\mathbf{u}\$\$让方程难以求解",
            androidx.compose.ui.graphics.Color.Black,
            androidx.compose.ui.unit.TextUnit.Unspecified,
        ).text

        // `$$` 分隔符不该留在结果里
        assertTrue("`\$\$` 仍然露在外面：$rendered", !rendered.contains("$$"))
        // LaTeX 命令也不该原样出现
        assertTrue("裸 LaTeX 命令仍然露出：$rendered", !rendered.contains("\\mathbf"))
        // 正文的其余部分必须还在（不能把内容吃掉）
        assertTrue("正文丢了：$rendered", rendered.contains("让方程难以求解"))
    }

    /**
     * 有序列表挤在一行。
     *
     * ⚠️ 这一条**曾经是"当前实现做不到"的那类**（老实现只在行首认序号，
     * 行内的 `2.` `3.` 会跟正文糊成一条）—— 现在由扫描器的
     * `orderedBoundaryAt` 处理，序号**原样保留**（`2.` / `3.` 不重新编号）。
     */
    @Test
    fun `挤在一行的有序列表要拆开`() {
        val blocks = parseBlocks("1. 甲 2. 乙 3. 丙")
        val texts = blocks.filterIsInstance<Block.Ordered>().map { it.text }
        assertEquals(listOf("甲", "乙", "丙"), texts)
        // 标记用的是**原文里那个**：模型写的序号本身是用户的阅读线索
        assertEquals(
            listOf("1.", "2.", "3."),
            blocks.filterIsInstance<Block.Ordered>().map { it.marker },
        )
    }

    // ---------------------------------------------------------------- 续行

    /**
     * 列表项**自己折行**（第二行有缩进）时，那段应算**同一条**的内容，
     * 而不是变成一个新的普通段落或新的一条清单。
     *
     * 这是"不转行"这个词最贴近技术含义的一种：内容本来该在一条里换行显示。
     */
    @Test
    fun `缩进续行并回上一条`() {
        val list = bullets("- 第一句很长\n  第二句接着写")
        assertEquals(1, list.size)
        assertTrue("续行没并回来：${list[0].text}", list[0].text.contains("第二句"))
    }

    /** 普通段落（不是列表）不该被续行逻辑吃掉。 */
    @Test
    fun `普通段落不受续行逻辑影响`() {
        val blocks = parseBlocks("第一段\n第二段")
        val paragraphs = blocks.filterIsInstance<Block.Paragraph>()
        // 同一段内的换行合并成一条段落（既有行为，用空格连接）
        assertEquals(1, paragraphs.size)
        assertEquals("第一段 第二段", paragraphs[0].text)
    }

    /** 空行要真的断开两段。 */
    @Test
    fun `空行断开两段`() {
        val paragraphs = parseBlocks("甲\n\n乙").filterIsInstance<Block.Paragraph>()
        assertEquals(listOf("甲", "乙"), paragraphs.map { it.text })
    }

    // ---------------------------------------------------------------- 与其它块共存

    /** 列表后面紧跟表格：两者都要认出来。 */
    @Test
    fun `列表与表格共存`() {
        val blocks = parseBlocks(
            """
            - 甲
            - 乙

            | 项目 | 分数 |
            | --- | --- |
            | 数学 | 90 |
            """.trimIndent(),
        )
        assertEquals(2, blocks.filterIsInstance<Block.Bullet>().size)
        assertEquals(1, blocks.filterIsInstance<Block.Table>().size)
    }

    /**
     * 代码块里的 `-` 与 `1.` **不能**被当成列表。
     *
     * 这是渲染层最容易被"顺手改坏"的一处：列表解析一旦放宽，
     * 代码块里的减号就会开始变成圆点。
     */
    @Test
    fun `代码块内的标记不当列表`() {
        val blocks = parseBlocks("```\n- 这行是代码\n1. 这也是代码\n```")
        assertTrue(blocks.filterIsInstance<Block.Bullet>().isEmpty())
        assertTrue(blocks.filterIsInstance<Block.Ordered>().isEmpty())
        assertEquals(1, blocks.filterIsInstance<Block.Code>().size)
    }
}
