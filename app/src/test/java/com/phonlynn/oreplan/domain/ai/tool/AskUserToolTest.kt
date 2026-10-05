package com.phonlynn.oreplan.domain.ai.tool

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提问工具。
 *
 * ## 为什么值得单独测
 *
 * 它本身不写数据，所以没有"数据被改坏"的风险 —— 风险在**回给模型的文本**。
 * 那段文本说错了，模型会：
 *
 * · 把「用户跳过了」当成「用户答了空」→ 反复追问同一件事
 * · 把用户自己输入的「其他」丢掉 → 拿一个它自己编的答案继续往下做
 *
 * 这两种错误都不会报错，只会让用户觉得"这 AI 听不懂人话"。
 */
class AskUserToolTest {

    private val tool = AskUserTool()

    private fun args(vararg questions: Pair<String, Pair<Boolean, List<String>>>) =
        JSONObject().put(
            "questions",
            JSONArray().apply {
                questions.forEach { (text, spec) ->
                    put(
                        JSONObject().apply {
                            put("text", text)
                            put("multi", spec.first)
                            put("options", JSONArray().apply { spec.second.forEach { put(it) } })
                        },
                    )
                }
            },
        )

    /** 选项顺序必须**原样保留** —— 界面默认选中第一个，顺序就是模型的推荐排序。 */
    @Test
    fun `选项顺序原样保留`() = runBlocking {
        val q = tool.questions(
            args("这周末优先安排哪些内容？" to (true to listOf("预习下周内容", "补齐落下的进度", "刷题巩固"))),
        ).single()

        assertEquals("预习下周内容", q.options.first())
        assertEquals(listOf("预习下周内容", "补齐落下的进度", "刷题巩固"), q.options)
        assertTrue(q.multiSelect)
    }

    /** 用户名下写了「其他」→ 必须原样带回去，不能被丢掉。 */
    @Test
    fun `其他输入会带上`() = runBlocking {
        val a = args("时长？" to (false to listOf("45 分钟", "60 分钟")))
        val outcome = tool.answer(a, mapOf("q0" to AskAnswer(chosen = setOf(1), other = "两小时")))

        assertTrue("用户自己写的答案必须出现：${outcome.forModel}", outcome.forModel.contains("两小时"))
        assertTrue("勾选的选项也要在：${outcome.forModel}", outcome.forModel.contains("60 分钟"))
    }

    /**
     * ⚠️ **跳过要说成"跳过"**，而且要让模型别再追问。
     *
     * 用户口径：勾「其他」但不输入 = 跳过这题。
     */
    @Test
    fun `跳过有明确标记且提示别再追问`() = runBlocking {
        val a = args("要不要提醒？" to (false to listOf("需要", "不需要")))
        // chosen 为空、other 也为空 = 跳过
        val outcome = tool.answer(a, mapOf("q0" to AskAnswer(chosen = emptySet(), other = null)))

        assertTrue("要有跳过标记：${outcome.forModel}", outcome.forModel.contains("跳过"))
        assertTrue(
            "必须明确告诉模型不要再追问同一件事：${outcome.forModel}",
            outcome.forModel.contains("不要再追问"),
        )
    }

    /** 「其他」里只敲了空格也算跳过 —— 否则用户以为跳过了、模型以为答了。 */
    @Test
    fun `其他只有空白也算跳过`() = runBlocking {
        val a = args("时长？" to (false to listOf("45 分钟")))
        val outcome = tool.answer(a, mapOf("q0" to AskAnswer(chosen = emptySet(), other = "   ")))
        assertTrue(outcome.forModel.contains("跳过"))
    }

    /** 多选题：多个选项都要出现。 */
    @Test
    fun `多选返回全部勾选项`() = runBlocking {
        val a = args("优先安排哪些？" to (true to listOf("甲", "乙", "丙")))
        val outcome = tool.answer(a, mapOf("q0" to AskAnswer(chosen = setOf(0, 2))))

        assertTrue(outcome.forModel.contains("甲"))
        assertTrue(outcome.forModel.contains("丙"))
        assertFalse("没勾的不该出现：${outcome.forModel}", outcome.forModel.contains("乙"))
    }

    /** 完全没作答（没算出来时的兜底）→ 当成跳过，不能崩。 */
    @Test
    fun `没有作答时按跳过处理`() = runBlocking {
        val a = args("问一" to (false to listOf("A")), "问二" to (false to listOf("B")))
        val outcome = tool.answer(a, emptyMap())

        assertTrue(outcome.forModel.contains("跳过"))
        assertTrue(outcome.forModel.contains("其中 2 题"))
    }

    /** 题数与选项数都要有上限 —— 20 道连排的题没人会填。 */
    @Test
    fun `题数与选项数都有上限`() = runBlocking {
        val many = tool.questions(
            args(*Array(9) { i -> "问$i" to (false to List(9) { j -> "选项$j" }) }),
        )
        assertEquals("最多 5 题", 5, many.size)
        assertEquals("每题最多 6 个候选", 6, many.first().options.size)
    }

    /** 题干为空、或没有任何选项的题要丢掉（画不出来）。 */
    @Test
    fun `无效的题被丢掉`() = runBlocking {
        val a = JSONObject().put(
            "questions",
            JSONArray().apply {
                put(JSONObject().put("text", "").put("options", JSONArray().put("A")))
                put(JSONObject().put("text", "有效题").put("options", JSONArray()))
                put(JSONObject().put("text", "正常题").put("options", JSONArray().put("A")))
            },
        )
        val q = tool.questions(a)
        assertEquals(1, q.size)
        assertEquals("正常题", q.single().text)
    }

    /** 参数坏了不能抛 —— 抛出去会让整轮对话以错误横幅结束。 */
    @Test
    fun `参数缺失时安全返回`() = runBlocking {
        assertTrue(tool.questions(JSONObject()).isEmpty())
        val outcome = tool.answer(JSONObject(), emptyMap())
        assertTrue(outcome.forModel.contains("参数错误"))
    }
}
