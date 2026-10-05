package com.phonlynn.oreplan.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多轮回答的挑选。
 *
 * 这段逻辑同时被界面渲染与「发给模型的历史」用到 ——
 * 后者出了错不会报错，只会让模型看到自己对同一个问题答了两次，
 * 行为悄悄变怪。所以这个纯函数值得钉一下。
 */
class RoundsTest {

    private fun user(id: String, text: String = "问") = ChatTurn(
        id = id,
        conversationId = "c",
        role = ChatTurn.Role.USER,
        content = text,
        createdAt = 0,
    )

    private fun answer(id: String, questionId: String?, text: String = "答") = ChatTurn(
        id = id,
        conversationId = "c",
        role = ChatTurn.Role.ASSISTANT,
        content = text,
        answerTo = questionId,
        createdAt = 0,
    )

    /** 没有多轮时原样返回 —— 旧数据（answerTo 为 null）不能被动到。 */
    @Test
    fun `无归属的旧数据原样保留`() {
        val turns = listOf(user("q1"), answer("a1", questionId = null))

        assertEquals(turns, Rounds.visible(turns))
    }

    /**
     * ⚠️ **两条 `answerTo` 相同的助手回合会被当成"同一问的两份回答"。**
     *
     * ## 这条断言是给 `ChatViewModel.continueAfterPending` 看的
     *
     * 工具调用挂起再恢复时，续写的内容**必须并回原来那一轮**，
     * 不能 `append` 成一条新的助手回合 —— 否则：
     *
     * · [Rounds.visible] 只留最后一条 → **带工具卡的那条从界面上消失**
     * · 轮次切换器冒出「2 / 2」，而用户根本没点过"重新生成"
     *
     * 也就是说 `append` 在这里不是"多了一条消息"，而是"少了一条 + 多了一个假切换器"。
     * 哪天真要改回 append，这条测试会先炸。
     */
    @Test
    fun `续写若另起一条回合会被当成两份回答`() {
        val userTurn = user("q1")
        // 挂起前的那条（带工具卡）
        val host = answer("a1", questionId = "q1", text = "好的，我来创建")
        // 恢复后若 append 出来的那条
        val continuation = answer("a2", questionId = "q1", text = "已创建")

        val asAppend = Rounds.visible(listOf(userTurn, host, continuation))
        assertEquals("append 会让带工具卡的那条消失", listOf(userTurn, continuation), asAppend)
        assertEquals(2, Rounds.groups(listOf(userTurn, host, continuation))["q1"]?.size)

        // 正确做法：并回一轮（一条回合，rounds 里两段）
        val merged = host.copy(
            content = "已创建",
            rounds = host.rounds + listOf(TurnRound(content = "已创建")),
        )
        val asMerge = Rounds.visible(listOf(userTurn, merged))
        assertEquals(listOf(userTurn, merged), asMerge)
        assertEquals(1, Rounds.groups(listOf(userTurn, merged))["q1"]?.size)
    }

    /**
     * **`fullText` 要给出全部正文**，不能只有最后一段。
     *
     * 复制、导出都靠它。接了工具之后正文分散在 `rounds` 里，
     * 用 `content` 会**丢掉工具调用之前说的那几句**。
     */
    @Test
    fun `全文包含各轮正文`() {
        val turn = answer("a1", questionId = "q1", text = "已创建").copy(
            rounds = listOf(
                TurnRound(content = "好的，我来创建一个日程。", callIds = listOf("c1")),
                TurnRound(content = "已创建"),
            ),
        )

        assertEquals("content 只是最后一段", "已创建", turn.content)
        assertEquals(
            "fullText 要拼上前面各轮",
            "好的，我来创建一个日程。\n\n已创建",
            turn.fullText,
        )
    }

    /** 没有 rounds 的老数据：fullText 就是 content。 */
    @Test
    fun `没有分段时全文等于正文`() {
        assertEquals("答", answer("a1", questionId = null, text = "答").fullText)
    }

    /** 默认显示**最后一轮**：重新生成完，用户要看到的是新的那一份。 */
    @Test
    fun `默认显示最后一轮`() {
        val turns = listOf(user("q1"), answer("a1", "q1"), answer("a2", "q1"))

        val visible = Rounds.visible(turns)
        assertEquals(listOf("q1", "a2"), visible.map { it.id })
    }

    /** 显式指定轮次时按指定的来。 */
    @Test
    fun `可以切回上一轮`() {
        val turns = listOf(user("q1"), answer("a1", "q1"), answer("a2", "q1"))

        val visible = Rounds.visible(turns, active = mapOf("q1" to 0))
        assertEquals(listOf("q1", "a1"), visible.map { it.id })
    }

    /** 越界或负数回落到最后一轮，而不是崩或显示空白。 */
    @Test
    fun `越界回落到最后一轮`() {
        assertEquals(1, Rounds.selectedIndex(9, count = 2))
        assertEquals(1, Rounds.selectedIndex(-3, count = 2))
        assertEquals(1, Rounds.selectedIndex(null, count = 2))
    }

    /** 两问各自的多轮互不影响。 */
    @Test
    fun `两问的多轮互不干扰`() {
        val turns = listOf(
            user("q1"), answer("a1", "q1"), answer("a2", "q1"),
            user("q2"), answer("b1", "q2"), answer("b2", "q2"),
        )

        val visible = Rounds.visible(turns, active = mapOf("q1" to 0, "q2" to 1))
        assertEquals(listOf("q1", "a1", "q2", "b2"), visible.map { it.id })
    }

    /** 归组只收助手消息：用户消息不该被算成一轮回答。 */
    @Test
    fun `归组只收助手消息`() {
        val turns = listOf(user("q1"), answer("a1", "q1"))
        assertEquals(1, Rounds.groups(turns).getValue("q1").size)
    }
}
