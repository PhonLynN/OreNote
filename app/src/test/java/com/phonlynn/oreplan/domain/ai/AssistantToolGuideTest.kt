package com.phonlynn.oreplan.domain.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手提示词要**告诉模型什么任务配什么工具**。
 *
 * ## 用户的问题
 *
 * > 「ai 怎么知道自己为了完成某一任务需要使用什么工具」
 *
 * 答案是：**它只知道"有哪些工具、各自什么时候该用"** —— 全靠在请求里
 * 读每个工具的 `description`，然后现场匹配。它没有别的途径。
 *
 * 所以**提示词里有没有一张"任务 → 工具"的映射表**，直接决定它选得准不准：
 *
 * · 有 → 用户说"我什么时候有空"，它一眼看到该用 `find_free_slots`
 * · 没有 → 它可能去调 `get_items` 拉一堆日程**自己心算**（而课程的单双周、
 *   重复日程的例外它算不对）
 *
 * ## 为什么值得测
 *
 * 提示词是**一段长文本**，改起来很容易顺手删掉某一段。
 * 而这组内容一旦丢了，模型的错误会以"AI 变笨了"的形式出现 ——
 * 很难联想到是提示词少了一张表。
 */
class AssistantToolGuideTest {

    private val assistant = AiSettings.BUILT_IN_PROMPTS
        .getValue(PromptTarget.ASSISTANT)
        .getValue(AiSettings.SYSTEM_DEFAULT)

    private val chat = AiSettings.BUILT_IN_PROMPTS
        .getValue(PromptTarget.CHAT)
        .getValue(AiSettings.SYSTEM_DEFAULT)

    // ---------------------------------------------------------------- 映射表

    /** 映射表本身要在。 */
    @Test
    fun `助手提示词里有任务与工具的映射`() {
        assertTrue("要有「什么任务配什么工具」这一节", assistant.contains("什么任务配什么工具"))
    }

    /**
     * ⚠️ **每一条映射都要覆盖**。
     *
     * 逐个点名 —— 只测"表在不在"太弱：表可能被删得只剩一行。
     */
    @Test
    fun `映射覆盖了关键任务与工具`() {
        val pairs = listOf(
            "get_items" to "看安排",
            "find_free_slots" to "问有没有空",
            "get_timetable" to "看课",
            "read_board" to "看卡片",
            "get_item_detail" to "看完整内容",
            "list_files" to "看待整理文件",
            "attach_file" to "安排文件位置",
            "get_current_time" to "问今天几号",
        )
        pairs.forEach { (tool, what) ->
            assertTrue(
                "映射表里应当提到 `$tool`（$what）：\n" +
                    assistant.lines().filter { it.contains("|") }.joinToString("\n"),
                assistant.contains(tool),
            )
        }
    }

    /** 容易混的三组边界要说清。 */
    @Test
    fun `写了容易混的工具边界`() {
        assertTrue(
            "要提醒「看课用 get_timetable、看日程用 get_items」",
            assistant.contains("看课") && assistant.contains("看日程待办"),
        )
        assertTrue(
            "要提醒卡片与条目是两套数据、id 不能互相传",
            assistant.contains("两套数据"),
        )
        assertTrue(
            "要提醒「有没有空」不该自己算",
            assistant.contains("不要") && assistant.contains("心算"),
        )
    }

    // ---------------------------------------------------------------- 分工

    /**
     * ⚠️ **聊天模式不该有这张表**。
     *
     * 聊天模式下请求里**根本不带 tools**（`SendChatMessage` 按 `allowTools` 组装），
     * 模型看不见任何工具。此时讲"该调哪个工具"只会让它以为能用工具 ——
     * 然后试图调用一个不存在的东西，或者编一段假的操作过程。
     */
    @Test
    fun `聊天提示词里没有工具映射`() {
        assertFalse(
            "聊天模式看不到工具，不该讲工具（用户口径：聊天就删掉工具调用相关的）",
            chat.contains("什么任务配什么工具"),
        )
        assertFalse(chat.contains("find_free_slots"))
        assertFalse(chat.contains("attach_file"))
    }

    /** 助手那套 = 聊天那套 + 追加段（结构约束，别被改散）。 */
    @Test
    fun `助手提示词以聊天提示词开头`() {
        assertTrue(
            "助手那套应当以聊天那套正文开头（用户口径：保留聊天提示词的前提下额外加）",
            assistant.startsWith(chat),
        )
    }
}
