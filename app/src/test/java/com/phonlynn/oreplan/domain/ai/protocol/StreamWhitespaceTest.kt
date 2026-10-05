package com.phonlynn.oreplan.domain.ai.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **空白分片不能被丢掉** —— 这是渲染错乱的**真正源头**。
 *
 * ## 症状（用户 2026-10-04 的六张截图）
 *
 * OreNote 里渲染成：
 *
 * ```
 * ##它想回答什么问题给定一堆流体——水、空气、任何能流动的东西——在某时刻的状态…
 * -左边：流体微团受到的加速度（惯性项）
 * - $\rho$：密度- $p$：压强- $\mu$：动力粘度- $\mathbf{f}$：外力
 * ```
 *
 * 而 DS App 收到同一段回复时是**规范换行**的，所以它用标准 Markdown 解析器
 *（`react-markdown` + `remark-gfm`）就渲染得很好。
 *
 * ## 根因
 *
 * SSE 流里**换行经常单独成片**（模型在每个块之间吐一个 `"\n\n"`）。
 * 而收流时用的是：
 *
 * ```kotlin
 * val contentDelta = if (text.isNotBlank() && text != "null") { … } else { "" }
 * ```
 *
 * `"\n\n".isNotBlank()` 是 **false** —— 于是**只含空白的分片被整片丢弃**，
 * 换行就此消失，前后两行被直接拼在一起。
 *
 * `isNotBlank` 在这里是**用错了**：它的职责只是挡 JSON 的 `null`
 *（`optString` 会把 JSON null 变成字面量字符串 `"null"`），
 * 但顺带把"整片都是空白"的合法内容也一起挡掉了。
 *
 * ## 为什么这个测试能证明它是根因
 *
 * 把**同一段真实回复**按"每个块一片、换行单独一片"的方式喂进去，
 * 断言**拼接结果和原文逐字符相同**。修好前必然红，修好后绿。
 */
class StreamWhitespaceTest {

    /** 每个分片一次喂给累加器，返回拼出来的正文。 */
    private fun collect(chunks: List<String>): String {
        val acc = StreamAccumulator()
        val out = StringBuilder()
        for (c in chunks) {
            out.append(acc.feed("data: " + frame(c)).content)
        }
        // 最终结果取自 build()，两条路径都要对
        assertEquals(
            "feed() 拼出来的与 build() 的结果不一致",
            out.toString(),
            acc.build().content ?: "",
        )
        return out.toString()
    }

    private fun frame(content: String): String {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"choices":[{"delta":{"content":"$escaped"}}]}"""
    }

    // ---------------------------------------------------------------- 核心用例

    /**
     * ⚠️ **只含换行的分片必须保留**。
     *
     * 这是最小复现：`"甲"` + `"\n\n"` + `"乙"` 拼出来必须是 `"甲\n\n乙"`。
     * 修好前这里是 `"甲乙"` —— 段落分隔消失。
     */
    @Test
    fun `只含换行的分片不能被丢掉`() {
        val text = collect(listOf("甲", "\n\n", "乙"))
        assertEquals("换行分片被吞了：<${text.replace("\n", "\\n")}>", "甲\n\n乙", text)
    }

    /** 只含空格/Tab 的分片同理（列表缩进续行、代码块缩进都靠它）。 */
    @Test
    fun `只含空格或制表符的分片不能被丢掉`() {
        assertEquals("  ", collect(listOf("  ")))
        assertEquals("\t", collect(listOf("\t")))
        assertEquals("甲  乙", collect(listOf("甲", " ", " ", "乙")))
    }

    /**
     * ⚠️ **把用户那段真实回复按"块一片、换行一片"喂进去，必须逐字符还原**。
     *
     * 这是对症状的直接复现：标题、列表、粗体全部粘连，就是因为
     * 这些片之间的 `"\n"` 被吃掉了。
     */
    @Test
    fun `真实回复的分片拼接必须逐字符还原`() {
        val original = listOf(
            "NS方程，全称**纳维-斯托克斯方程**（Navier-Stokes equations），是描述流体运动的核心方程。",
            "\n\n",
            "##它想回答什么问题",
            "\n",
            "给定一堆流体——水、空气——在某时刻的状态，它接下来会怎么流？",
            "\n\n",
            "##核心思想",
            "\n",
            "它本质上是**牛顿第二定律（F = ma）用在流体微团上**：",
            "\n\n",
            "-左边：流体微团受到的加速度（惯性项）",
            "\n",
            "-右边：所有作用在它上面的力",
            "\n\n",
            "其中：",
            "\n",
            "- ${'$'}\\mathbf{u}${'$'}：速度场",
            "\n",
            "- ${'$'}\\rho${'$'}：密度",
            "\n",
            "- ${'$'}p${'$'}：压强",
        ).joinToString("")

        val chunks = original
            .split(Regex("(?<=\\n)"))   // 切成"行 + 换行"的片，模拟 SSE 的切法
            .filter { it.isNotEmpty() }

        val collected = collect(chunks)

        assertEquals(
            "分片拼接后与原文不一致 —— 空白分片被吞了：\n" +
                "原文：<${original.replace("\n", "\\n")}>\n" +
                "实际：<${collected.replace("\n", "\\n")}>",
            original,
            collected,
        )

        // 换行数也必须一致（粘连的直接指标）
        assertEquals(
            "换行数量对不上：原文 ${original.count { it == '\n' }} 个，" +
                "实际 ${collected.count { it == '\n' }} 个",
            original.count { it == '\n' },
            collected.count { it == '\n' },
        )
    }

    /**
     * 反例护栏：JSON `null` **仍然要挡住**。
     *
     * `optString` 对 JSON null 返回字面量 `"null"`，不挡的话它会进正文
     *（历史上真机出现过"回复结尾一大串 null"）。
     * 修空白分片时**不能把这个护栏一起拆了**。
     */
    @Test
    fun `JSON null 仍然不能进正文`() {
        val acc = StreamAccumulator()
        acc.feed("""data: {"choices":[{"delta":{"content":null}}]}""")
        assertEquals("JSON null 混进正文了", "", acc.build().content ?: "")

        acc.feed("""data: {"choices":[{"delta":{"content":"甲"}}]}""")
        acc.feed("""data: {"choices":[{"delta":{"content":null}}]}""")
        assertEquals("甲", acc.build().content)
    }

    /** 真的没有 content 字段（工具调用分片）也不能凭空多出内容。 */
    @Test
    fun `没有 content 字段的分片不产生内容`() {
        val acc = StreamAccumulator()
        acc.feed("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"create_item"}}]}}]}""")
        assertEquals("", acc.build().content ?: "")
    }

    /**
     * `data: [DONE]` 本身不产生内容。
     *
     * ⚠️ 我第一版把这条写成"DONE 之后的片不再计入"，**那是错的** ——
     * [StreamAccumulator.feed] 对 `[DONE]` 只是返回空增量，并没有"关闭"累加器。
     * 真实 SSE 里 `[DONE]` 就是最后一行，之后本来也不会再有片；
     * 而万一有，丢掉它反而有风险。所以这里只断言"它自己不贡献内容"。
     */
    @Test
    fun `DONE 本身不产生内容`() {
        val acc = StreamAccumulator()
        acc.feed("""data: {"choices":[{"delta":{"content":"甲"}}]}""")
        val delta = acc.feed("data: [DONE]")
        assertEquals("`[DONE]` 不该产生增量", "", delta.content)
        assertEquals("甲", acc.build().content ?: "")
    }

    /**
     * 渲染层面的收尾断言：**换行保住之后，粘连症状应当消失**。
     *
     * 这一条连到渲染器：同一段原文，规范换行时解析出的块数
     * 必须**明显多于**换行被吃掉时。
     */
    @Test
    fun `换行保住之后解析块数正常`() {
        val withNewlines = """
            ##它想回答什么问题
            给定一堆流体——水、空气——在某时刻的状态。
            -左边：流体微团受到的加速度
            - $\\mathbf{u}$：速度场
            - $\\rho$：密度
        """.trimIndent()

        val withoutNewlines = withNewlines.replace("\n", "")

        val goodBlocks = com.phonlynn.oreplan.v2.ai.parseBlocks(withNewlines).size
        val badBlocks = com.phonlynn.oreplan.v2.ai.parseBlocks(withoutNewlines).size

        assertTrue(
            "换行是结构信息的来源：有换行时有 $goodBlocks 个块，" +
                "粘连时只有 $badBlocks 个 —— 如果没有差别，说明渲染器在替收流层猜，那是错的",
            goodBlocks > badBlocks,
        )
    }
}
