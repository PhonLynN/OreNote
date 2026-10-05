package com.phonlynn.oreplan.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关联项目」拼进用户消息的那段文本（用户 2026-10-04 定的功能）。
 *
 * ## 为什么单独测「拼文本」这一小步
 *
 * 这个功能的价值全在**模型能不能看到关联的内容**上。而它涉及的判断有三个，
 * 每一个错了都会静默失效（界面看起来完全正常）：
 *
 * 1. 资料**有没有拼进去** —— 没拼的话用户选了等于没选
 * 2. 气泡里显示的**是不是只有用户自己打的字** —— 拼进去会让气泡变成一大坨
 * 3. 取不到正文时**有没有如实说明** —— 静默发一段空上下文，模型会答得莫名其妙
 *
 * 三者都不会抛异常、不会报错，只能靠测。
 *
 * ## 这里测的是纯函数
 *
 * 拼接逻辑抽成了 `buildPromptWithLinks`（顶层纯函数），
 * 就是为了能这样直接测 —— 不必起 ViewModel、不必碰 Android。
 */
class LinkedPromptTest {

    private fun link(
        id: String,
        kind: String = "日程",
        title: String = "标题",
        detail: String = "正文",
    ) = LinkedItem(id = id, kindLabel = kind, title = title, detail = detail)

    /** 没关联任何东西时，**原样返回**（不能多出分隔线之类的噪音）。 */
    @Test
    fun `没有关联时原样返回`() {
        assertEquals("明天有什么安排", buildPromptWithLinks("明天有什么安排", emptyList()))
    }

    /** 有资料时要拼进去 —— 这是功能生效的**唯一判据**。 */
    @Test
    fun `有关联时把正文拼进去`() {
        val prompt = buildPromptWithLinks(
            "这个日程要准备什么",
            listOf(link("a", detail = "【日程】组会\n开始：10-05 14:00")),
        )

        // 用户原话在
        assertTrue("用户原话丢了：$prompt", prompt.contains("这个日程要准备什么"))
        // 资料的正文在（模型据此回答）
        assertTrue("关联正文没拼进去：$prompt", prompt.contains("10-05 14:00"))
        assertTrue("资料没标注来源：$prompt", prompt.contains("用户主动关联"))
    }

    /** 多条资料都要在（用户可能关联好几种）。 */
    @Test
    fun `多条关联都在`() {
        val prompt = buildPromptWithLinks(
            "一起看看",
            listOf(
                link("a", kind = "日程", detail = "【日程】组会"),
                link("b", kind = "待办", detail = "【待办】交作业"),
                link("c", kind = "目标", detail = "【目标】背单词"),
            ),
        )

        assertTrue(prompt.contains("组会"))
        assertTrue(prompt.contains("交作业"))
        assertTrue(prompt.contains("背单词"))
    }

    /**
     * ⚠️ **取不到正文时要如实说明**，不能静默发一段空上下文。
     *
     * 静默的话模型只会看到一个标题，却不知道"为什么只有标题" ——
     * 它可能编一个内容出来。
     */
    @Test
    fun `正文为空时如实说明`() {
        val prompt = buildPromptWithLinks(
            "看看这个",
            listOf(link("a", kind = "笔记", title = "高数笔记", detail = "")),
        )

        assertTrue("标题要在：$prompt", prompt.contains("高数笔记"))
        assertTrue(
            "没说明「内容读不到」：$prompt",
            prompt.contains("读取失败") || prompt.contains("仅有标题"),
        )
    }

    /**
     * ⚠️ 用户原话必须**排在资料之前**。
     *
     * 反过来的话，模型会先读到一大段资料再看问题，
     * 容易抓错重点。而且分隔线在中间更符合"先说我问的，再附上资料"的直觉。
     */
    @Test
    fun `用户原话在资料之前`() {
        val prompt = buildPromptWithLinks(
            "这题怎么做",
            listOf(link("a", detail = "【笔记】第三章")),
        )

        val questionAt = prompt.indexOf("这题怎么做")
        val detailAt = prompt.indexOf("第三章")
        assertTrue("找不到问题", questionAt >= 0)
        assertTrue("找不到资料", detailAt >= 0)
        assertTrue("资料排在了问题前面：$prompt", questionAt < detailAt)
    }

    /** 前面的空行/尾随空白要被清掉（否则聊天气泡里会多出空行）。 */
    @Test
    fun `结果首尾没有多余空白`() {
        val prompt = buildPromptWithLinks("  问题  ", listOf(link("a")))
        assertEquals(prompt.trim(), prompt)
    }

    /** 空输入也不该崩（用户可能只选了关联没打字）。 */
    @Test
    fun `空输入加关联不崩`() {
        val prompt = buildPromptWithLinks("", listOf(link("a", detail = "【日程】组会")))
        assertTrue("资料还是要在：$prompt", prompt.contains("组会"))
    }
}
