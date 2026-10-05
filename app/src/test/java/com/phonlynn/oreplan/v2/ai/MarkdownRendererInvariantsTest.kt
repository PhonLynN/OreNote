package com.phonlynn.oreplan.v2.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 渲染器的结构不变量 —— 守住几条已经付过学费的教训。
 *
 * ## 一、`AiMarkdownText.kt` 里不许出现正则
 *
 * 真实事故：`Regex("^\\begin\{([^}]*)}")` 让 `AiMarkdownTextKt.<clinit>` 抛
 * `PatternSyntaxException` —— **只要渲染任何 AI 回复就闪退**，连查四版才对上。
 *
 * 根因是**引擎不同**：Android 用 **ICU 正则**（把孤立的 `}` 视为语法错误），
 * JVM 的 `java.util.regex` 允许它当普通字符。于是
 * **单测全绿（跑在 JVM 上）、真机必崩（跑在 ICU 上）**。
 *
 * 结论：块 / 行内标记一律手写字符扫描。不要"就一条小正则" ——
 * 这一条就是那个代价。
 *
 * 全树的正则体检另见 [SourceRegexAuditTest]（那条查"模式串本身有没有雷"）；
 * 这条查的是**更前一步**：这个文件根本不该用正则。
 *
 * ## 二、块级与行内的分隔符必须是同一套
 *
 * 老实现里 `$$` 的块级只在行首认、行内又**主动排除**它，
 * 模型把 `$$…$$` 写在句子中间时两条路都走不到 —— 用户看到的是一串裸 LaTeX。
 *
 * ## 三、强调里面还要能再放行内标记
 *
 * `**$\mathbf{u}$**流速场` 在老实现里只消费掉 `**`，里面的 `$\mathbf{u}$`
 * 被**原样抄进结果** —— 用户看到裸的美元号和反斜杠命令。
 *
 * ⚠️ 第一条用**源码级**断言而不是运行时行为：属性是"代码里有没有这个东西"，
 * 运行时无论如何都测不出来。为了不让它变成永远绿的摆设，
 * 判据带**自检**（拿反例喂给同一个判据，必须报不合格）。
 */
class MarkdownRendererInvariantsTest {

    private val renderer: File = listOf(
        "src/main/java/com/phonlynn/oreplan/v2/ai/AiMarkdownText.kt",
        "app/src/main/java/com/phonlynn/oreplan/v2/ai/AiMarkdownText.kt",
        "../app/src/main/java/com/phonlynn/oreplan/v2/ai/AiMarkdownText.kt",
    ).map(::File).firstOrNull { it.exists() }
        ?: error("找不到渲染器源码。当前目录是 `${File(".").absolutePath}`")

    // ---------------------------------------------------------------- 不变量一

    @Test
    fun `渲染器源码里没有任何正则`() {
        val source = renderer.readText()

        // 自检：路径必须真的指到渲染器（否则这条会假绿）
        assertTrue(
            "读到的不是渲染器源码：${renderer.absolutePath}",
            source.contains("class MarkdownParser") &&
                source.contains("appendInlineMarkup"),
        )

        val offenders = regexLiterals(source)
        assertTrue(
            "`AiMarkdownText.kt` 里出现了正则。Android 的 ICU 引擎与 JVM 行为不同，" +
                "这里曾经因为一条正则让「只要渲染 AI 回复就闪退」。\n" +
                "请改写成字符扫描：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 判据本身必须有效 —— 拿一段**含正则**的假源码喂进来，必须报出来。 */
    @Test
    fun `判据能抓住正则字面量`() {
        val fake = "private val A = Regex(\"a\")\nprivate val B = \"b\".toRegex()\n"

        assertEquals(
            "判据漏了正则字面量：${regexLiterals(fake)}",
            2,
            regexLiterals(fake).size,
        )
    }

    /**
     * 抠出源码里的 `Regex(…)` / `.toRegex()`。
     *
     * 注释里的正则不算 —— 事故记录里常抄那条坏模式当反面教材。
     */
    private fun regexLiterals(source: String): List<String> {
        val out = ArrayList<String>()
        source.lines().forEachIndexed { index, raw ->
            val trimmed = raw.trimStart()
            // 整行注释 / KDoc 续行（`* …`）不算
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                return@forEachIndexed
            }
            val code = raw.substringBefore("//")
            // `.toRegex()` 三个字里也含 `Regex(`，所以**一行只报一次**
            if (code.contains("Regex(") || code.contains(".toRegex()")) {
                out += "第 ${index + 1} 行：${raw.trim()}"
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 不变量二

    /**
     * ⚠️ **行内公式的四种写法都要认** —— 块级认哪几种，行内就得认哪几种。
     *
     * 老实现里 `\(…\)` 认、`\[…\]` **不认**：模型（至少 DeepSeek）很爱用后者，
     * 于是同一段回复里 `$$` 渲染成功、`\[` 原样露出一串 `\frac`。
     */
    @Test
    fun `行内公式的四种写法都不会原样露出`() {
        val dollar = "${'$'}"
        val cases = listOf(
            "LaTeX 行内" to """速度场 \(u\) 结束""",
            "LaTeX 的 display 写法写在行内" to
                """惯性项\[\rho \left( \frac{\partial \mathbf{u}}{\partial t} \right)\]""",
            "Markdown 的美元写法" to
                """连续性方程 ${'$'}\nabla\cdot\mathbf{u}=0${'$'} 结束""",
            "句子中间的块级标记" to
                """麻烦：${'$'}${'$'}\mathbf{u}\cdot\nabla\mathbf{u}${'$'}${'$'}让方程难以求解""",
        )

        val leftovers = cases.mapNotNull { (name, src) ->
            val rendered = renderInline(src, Color.Black, 12.sp).text
            when {
                rendered.contains("\\") -> "$name 残留反斜杠命令：$rendered"
                rendered.contains(dollar) -> "$name 残留美元号：$rendered"
                else -> null
            }
        }

        assertTrue(
            "以下行内公式没被消费，用户会看到裸的 LaTeX：\n" + leftovers.joinToString("\n"),
            leftovers.isEmpty(),
        )
    }

    /** 正文不能被公式解析吃掉 —— "不漏标记"和"不丢内容"要同时成立。 */
    @Test
    fun `行内公式两侧的正文都在`() {
        val rendered = renderInline(
            """惯性项\[\rho \left( \frac{\partial \mathbf{u}}{\partial t} \right)\]表示动量变化""",
            Color.Black,
            12.sp,
        ).text

        assertTrue("前缀丢了：$rendered", rendered.contains("惯性项"))
        assertTrue("后缀丢了：$rendered", rendered.contains("表示动量变化"))
    }

    /**
     * ⚠️ **强调内部要递归解析**。
     *
     * 用户的第二张截图里就有一行：
     *
     * ```
     * - **$\mathbf{u}$**流速场，**$p$**压强
     * ```
     *
     * 老实现只吃掉 `**`、把里面的 `$\mathbf{u}$` **原样抄进结果**。
     */
    @Test
    fun `强调里面的行内标记也要被消费`() {
        val dollar = "${'$'}"
        val rendered = renderInline(
            """- **${'$'}\mathbf{u}${'$'}**流速场，**${'$'}p${'$'}**压强，**${'$'}\rho${'$'}**密度""",
            Color.Black,
            12.sp,
        ).text

        assertFalse("残留 `**`：$rendered", rendered.contains("**"))
        assertFalse("强调里的公式没解析（残留裸 LaTeX）：$rendered", rendered.contains("\\"))
        assertFalse("强调里的公式没解析（残留美元号）：$rendered", rendered.contains(dollar))
        listOf("流速场", "压强", "密度").forEach {
            assertTrue("正文丢了「$it」：$rendered", rendered.contains(it))
        }
    }

    /**
     * 递归必须有**上限** —— 模型偶尔会输出一长串 `**`，不设上限就是栈溢出，
     * 而这个函数在流式输出时每个 token 都会跑一遍。
     */
    @Test
    fun `强调嵌套再深也不会把界面搞崩`() {
        val deep = "**".repeat(80) + "甲" + "**".repeat(80)
        val rendered = renderInline(deep, Color.Black, 12.sp).text
        // 只要不抛异常、且内容还在，就算过
        assertTrue("内容丢了：$rendered", rendered.contains("甲"))
    }
}
