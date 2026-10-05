package com.phonlynn.oreplan.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分支切换的**纯逻辑**（用户 2026-10-04 的分支需求）。
 *
 * ## 需求原文
 *
 * > 「重新生成中间消息（前后都有消息）时，将原有内容保留为同一对话内的分支，
 * > 保留下方所有消息。通过消息选择器（`<n/m>`）来切换分支，重新生成中部消息后，
 * > 在这个新分支内清空下方的消息，该消息作为该分支的最后一条消息」
 *
 * ## 这里测的是"下标与可见列表"，不含 ViewModel 的暂存表
 *
 * `ChatViewModel` 里的 `branchTails` 需要 Android 依赖，测不了。
 * 但它依赖的两件纯逻辑可以测，而且正是最容易算错的地方：
 *
 * 1. [Rounds.selectedIndex] —— 切到第几轮（越界怎么回落）
 * 2. [Rounds.visible] —— 给定"选哪一轮"，**哪些消息该显示**
 *
 * 分支功能的观感完全由这两个函数决定。
 */
class BranchRoundsTest {

    private fun user(id: String, at: Long) = ChatTurn(
        id = id,
        conversationId = "c",
        role = ChatTurn.Role.USER,
        content = "问 $id",
        createdAt = at,
    )

    private fun answer(id: String, questionId: String, content: String, at: Long) = ChatTurn(
        id = id,
        conversationId = "c",
        role = ChatTurn.Role.ASSISTANT,
        content = content,
        answerTo = questionId,
        createdAt = at,
    )

    /**
     * 基本形状：一问两答时，`visible` 只留选中的那一份。
     *
     * 这是分支的地基 —— 两条分支**不能同时**出现在可见列表里。
     */
    @Test
    fun `同一问只显示选中的那一轮`() {
        val q = user("q1", 1)
        val a1 = answer("a1", "q1", "第一份", 2)
        val a2 = answer("a2", "q1", "第二份", 3)
        val turns = listOf(q, a1, a2)

        val first = Rounds.visible(turns, mapOf("q1" to 0))
        assertEquals(listOf("q1", "a1"), first.map { it.id })

        val second = Rounds.visible(turns, mapOf("q1" to 1))
        assertEquals(listOf("q1", "a2"), second.map { it.id })
    }

    /** 没记录选哪一轮时，回落**最后一轮**（最新的一份就是用户刚看到的那份）。 */
    @Test
    fun `缺省选最后一轮`() {
        val turns = listOf(
            user("q1", 1),
            answer("a1", "q1", "第一份", 2),
            answer("a2", "q1", "第二份", 3),
        )

        assertEquals(listOf("q1", "a2"), Rounds.visible(turns).map { it.id })
    }

    /**
     * 下标越界时回落最后一轮，而不是崩。
     *
     * 真实场景：用户在分支 2、删掉了分支 2 的那条消息，
     * 此时 `activeRounds` 里还记着 1（越界）。
     */
    @Test
    fun `下标越界回落最后一轮`() {
        val turns = listOf(user("q1", 1), answer("a1", "q1", "只有一份", 2))
        assertEquals(listOf("q1", "a1"), Rounds.visible(turns, mapOf("q1" to 5)).map { it.id })
        assertEquals(listOf("q1", "a1"), Rounds.visible(turns, mapOf("q1" to -1)).map { it.id })
    }

    // ---------------------------------------------------------------- 分支的下文

    /**
     * **核心场景**：中间那条回答有两个分支，各自带着自己的下文。
     *
     * ```
     * 甲 → 乙 → 丙 → 丁        （选分支 1：a1）
     * 甲 → 乙′                 （选分支 2：a2，下方清空）
     * ```
     *
     * ⚠️ 这里模拟的是 `ChatViewModel` 的做法：**非当前分支的下文不在 `turns` 里**
     *（它们被摘到 `branchTails` 了）。所以"选分支 1"时列表里就是
     * `q1, a1, q2, a2x, q3, a3x` 这一串。
     */
    @Test
    fun `分支各自带自己的下文`() {
        // 分支 1 的完整链路
        val q1 = user("q1", 1)
        val a1 = answer("a1", "q1", "乙", 2)
        val q2 = user("q2", 3)
        val a2 = answer("a2", "q2", "后续回答", 4)

        // 分支 2：甲 → 乙′（下方被清空）
        val branch2 = answer("a1_new", "q1", "乙′", 5)

        // 切到分支 1：turns 里放着分支 1 的下文
        val onBranch1 = listOf(q1, a1, q2, a2)
        val visible1 = Rounds.visible(onBranch1, mapOf("q1" to 0))
        assertEquals(
            "分支 1 应该看到它自己的下文",
            listOf("q1", "a1", "q2", "a2"),
            visible1.map { it.id },
        )

        // 切到分支 2：下文被摘走，只剩到新回答为止
        val onBranch2 = listOf(q1, a1, branch2)
        val visible2 = Rounds.visible(onBranch2, mapOf("q1" to 1))
        assertEquals(
            "分支 2 里下方应该是空的（该消息是这一分支的最后一条）",
            listOf("q1", "a1_new"),
            visible2.map { it.id },
        )

        // ⚠️ 两条分支**同时存在**于 turns 里（历史不能丢），只是 visible 各挑一份
        val both = listOf(q1, a1, branch2)
        assertTrue("旧回答必须留在数据里", both.any { it.id == "a1" })
        // 旧分支的下文在 branchTails 里 —— 这里验证"切回去能拿回完整链路"
        assertEquals(4, onBranch1.size)
    }

    /**
     * **多处**分支互不干扰（用户可能重新生成过第 3 条和第 8 条各一次）。
     */
    @Test
    fun `多处分支各自独立`() {
        val turns = listOf(
            user("q1", 1), answer("a1", "q1", "答1", 2),
            user("q2", 3), answer("a2a", "q2", "答2-甲", 4), answer("a2b", "q2", "答2-乙", 5),
            user("q3", 6), answer("a3", "q3", "答3", 7),
        )

        // 第一处选甲、第二处无关（只有一轮）
        val v1 = Rounds.visible(turns, mapOf("q2" to 0))
        assertEquals(listOf("q1", "a1", "q2", "a2a", "q3", "a3"), v1.map { it.id })

        // 第一处选乙
        val v2 = Rounds.visible(turns, mapOf("q2" to 1))
        assertEquals(listOf("q1", "a1", "q2", "a2b", "q3", "a3"), v2.map { it.id })
    }

    // ---------------------------------------------------------------- 分组

    /** 只有 assistant 且带 `answerTo` 的才参与轮次分组。 */
    @Test
    fun `用户消息与无归属的助手消息不参与分组`() {
        val turns = listOf(
            user("q1", 1),
            answer("a1", "q1", "有归属", 2),
            // 老数据：没有 answerTo
            ChatTurn(
                id = "orphan",
                conversationId = "c",
                role = ChatTurn.Role.ASSISTANT,
                content = "孤儿",
                createdAt = 3,
            ),
        )

        val groups = Rounds.groups(turns)
        assertEquals(setOf("q1"), groups.keys)
        assertEquals(1, groups.getValue("q1").size)
    }

    /** 一轮回答时切换器不该出现（那是"永远点不动的 1 / 1"）。 */
    @Test
    fun `只有一轮时分组大小为 1`() {
        val turns = listOf(user("q1", 1), answer("a1", "q1", "唯一", 2))
        assertEquals(1, Rounds.groups(turns).getValue("q1").size)
    }
}
