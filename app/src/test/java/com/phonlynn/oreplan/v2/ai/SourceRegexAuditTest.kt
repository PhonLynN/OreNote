package com.phonlynn.oreplan.v2.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **整棵源码树**的正则体检。
 *
 * ## 为什么不能只查渲染器那一个文件
 *
 * [MarkdownRendererInvariantsTest] 只守住"渲染器那个文件不许用正则" ——
 * 而"在 Android 上会崩的正则"并不挑场合。真实事故（`Regex("^\\begin\{([^}]*)}")`）
 * 之所以难查，
 * 是因为它在 JVM 上完全合法、只在 ICU 上抛 `PatternSyntaxException`，
 * 而**全部单测在 JVM 上是绿的**。任何一个新写的正则都可能重蹈覆辙。
 *
 * 所以这里把源码里**每一条** `Regex("…")` 字面量抠出来过一遍同一套规则。
 * 新加正则不需要记得来登记 —— 它在源码里就会被扫到。
 *
 * ## 与 [MarkdownRendererInvariantsTest] 的关系
 *
 * 那条是"渲染器那个文件根本不该出现正则"（更前一步的约束）；
 * 这条是"全树兜底"，防遗漏。两条都留着：前者查得准，后者查得全。
 */
class SourceRegexAuditTest {

    @Test
    fun `源码里的每条正则都不含未转义的括号`() {
        val files = sourceFiles()
        assertTrue("一个源文件都没找到，路径判断错了", files.size > 50)

        val offenders = ArrayList<String>()
        var checked = 0

        files.forEach { file ->
            extractRegexLiterals(file.readText()).forEach { (line, pattern) ->
                checked++
                scan(pattern)?.let { problem ->
                    offenders += "${file.name}:$line  `$pattern` —— $problem"
                }
            }
        }

        assertTrue("一条正则都没扫到，这个体检是空转的", checked >= 8)
        assertTrue(
            "有正则会在 Android 的 ICU 上抛 PatternSyntaxException（JVM 单测查不出来）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 自检：**体检必须能抓住真实事故里那条模式**。
     *
     * 抓不住的话，上面那条测试就是永远绿的摆设 —— 而它守护的正是
     * "只要渲染 AI 回复就闪退"那种级别的事故。
     */
    @Test
    fun `体检能抓住真实事故里的那条模式`() {
        // 事故原型：`\begin{` 转义了，结尾的 `}` 没转义
        assertNotNull("抓不住 ICU 会拒的孤立 }", scan("^\\\\begin\\{([^}]*)}"))
        // 反过来：正确写法不该被误报
        assertNull("误报了正确写法", scan("^\\\\begin\\{([^}]*)\\}"))
        // 量词是合法的，不能误报
        assertNull("误报了 {1,2} 这种量词", scan("^(\\\\d{1,2}):(\\\\d{2})$"))
    }

    // ---------------------------------------------------------------- 源码扫描

    /** 测试的工作目录是模块目录（`app/`），但也兼容从仓库根跑。 */
    private fun sourceFiles(): List<File> {
        val roots = listOf("src/main/java", "app/src/main/java", "../app/src/main/java")
        val root = roots.map(::File).firstOrNull { it.isDirectory }
            ?: error("找不到源码目录。当前目录是 `${File(".").absolutePath}`")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * 抠出 `Regex("…")` 里的模式串。
     *
     * 同时认 `Regex("…")` 与 `"…".toRegex()` 两种写法。返回 `(行号, 反转义后的模式)`。
     *
     * 反转义是必需的：源码里写的是 `"\\d"`，而真正交给正则引擎的是 `\d`。
     * 体检必须看**后者** —— 事故模式在源码里是 `"^\\\\begin\\{([^}]*)}"`，
     * 肉眼几乎看不出问题，反转义之后 `\{` 和 `}` 的不对称才现形。
     */
    private fun extractRegexLiterals(source: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val call = Regex("""Regex\(\s*"((?:[^"\\]|\\.)*)"""")

        source.lines().forEachIndexed { index, line ->
            // 注释里的正则不算 —— 事故记录里就抄了那条坏模式，别把它当成真代码
            val code = line.substringBefore("//").let {
                if (it.trimStart().startsWith("*")) "" else it
            }
            if (code.isBlank()) return@forEachIndexed

            call.findAll(code).forEach { m ->
                out += (index + 1) to unescape(m.groupValues[1])
            }
        }
        return out
    }

    /** Kotlin 字符串字面量 → 实际字符。只处理正则里会出现的转义。 */
    private fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.lastIndex) {
                sb.append(c)
                i++
                continue
            }
            when (val next = raw[i + 1]) {
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '$' -> sb.append('$')
                // 其余（\d \w \{ \} \[ …）在 Kotlin 里是**非法转义**，
                // 源码里必然写成 `\\d`，所以走上面那支。真出现 `\d` 时原样保留，
                // 反正体检只关心括号的配对。
                else -> sb.append('\\').append(next)
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * 返回第一个问题；没问题返回 null。
     *
     * 规则（与 [MarkdownRegexAuditTest] 同一套，刻意做得简单可验证）：
     *  · `\x` 是转义，整对跳过
     *  · 字符类 `[...]` 内部不参与花括号计数（`[^}]` 是合法的）
     *  · 字符类**外**，`{`/`}` 必须配对（`a{2,3}` 合法，孤立的 `}` 不合法）
     *  · 字符类必须正确开合
     */
    private fun scan(pattern: String): String? {
        var inClass = false
        var braces = 0
        var brackets = 0
        var i = 0

        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                i += 2
                continue
            }
            when {
                c == '[' && !inClass -> {
                    inClass = true
                    brackets++
                }
                c == ']' && inClass -> {
                    inClass = false
                    brackets--
                }
                c == ']' && !inClass -> return "字符类外出现了未转义的 ]"
                c == '{' && !inClass -> braces++
                c == '}' && !inClass -> braces--
            }
            if (braces < 0) return "出现了未转义的 }（ICU 正则会直接报语法错误）"
            if (brackets < 0) return "出现了未配对的 ["
            i++
        }

        if (inClass) return "字符类没有闭合"
        if (braces != 0) return "花括号没有配对"
        return null
    }
}
