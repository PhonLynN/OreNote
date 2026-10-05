package com.phonlynn.oreplan.domain.ai.tool

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 向用户提问。
 *
 * ## 什么时候该用
 *
 * 模型**信息不足**、且缺的信息只有用户知道时。比如"帮我安排这周末的学习"——
 * 优先级、时长、要不要提醒都不知道，凭猜写进日程比不写更糟。
 *
 * ## 一次问多个问题（设计稿 `tZDQ2` 是连排三题）
 *
 * 用户填完一起提交，而不是一题一卡。三题连排比点三次"下一题"省事得多，
 * 而且用户能回头看前面答了什么。
 *
 * ## ⚠️ `options` 的顺序是**有意义**的
 *
 * 界面上**默认选中第一个**（用户口径），所以约定：
 *
 * > **把你认为用户最可能选的那个放在第一位。**
 *
 * 这不是装饰性约定 —— 默认值会被用户当成"已经答过了"直接提交，
 * 随手排的顺序等于替用户做了个随机的决定。
 *
 * ## ⚠️ 不要问"你自己能查到"的事
 *
 * 日程、课表、待办、白板都能用别的工具查到。先查，查不到再问。
 * 把可查的东西拿去问用户，是最招人烦的一种工具用法。
 */
@Singleton
class AskUserTool @Inject constructor() : AskingTool {

    override val name = "ask_user"
    override val displayName = "提问"
    override val description =
        "向用户提 1~5 个选择题，请用户确认信息。**只在信息不足且用户才知道时用** ——" +
            "日程/课表/待办/白板的内容先用查询类工具查，查不到再问，不要把能查到的东西拿去问。" +
            "每题给 2~6 个候选，**把你认为用户最可能选的那个放在第一位**（它会被默认选中）。" +
            "不要用这个工具问开放性问题（那种直接在正文里问就行）。"

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "questions",
                    JSONObject().apply {
                        put("type", "array")
                        put("description", "要问的问题，1~5 个")
                        put(
                            "items",
                            JSONObject().apply {
                                put("type", "object")
                                put(
                                    "properties",
                                    JSONObject().apply {
                                        put(
                                            "text",
                                            JSONObject().put("type", "string")
                                                .put("description", "问题本身，一句话"),
                                        )
                                        put(
                                            "multi",
                                            JSONObject().put("type", "boolean")
                                                .put(
                                                    "description",
                                                    "是否可多选。默认 false（单选）。" +
                                                        "问「哪些」（可以同时成立）时用 true；" +
                                                        "问「哪个 / 多长 / 要不要」这类互斥的用 false",
                                                ),
                                        )
                                        put(
                                            "options",
                                            JSONObject().apply {
                                                put("type", "array")
                                                put("items", JSONObject().put("type", "string"))
                                                put(
                                                    "description",
                                                    "2~6 个候选答案，**最可能的放第一个**。" +
                                                        "不要包含「其他」—— 界面会自动加",
                                                )
                                            },
                                        )
                                    },
                                )
                                put("required", JSONArray().put("text").put("options"))
                            },
                        )
                    },
                )
            },
        )
        put("required", JSONArray().put("questions"))
    }

    override suspend fun questions(args: JSONObject): List<AskQuestion> {
        val arr = args.optJSONArray("questions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val text = obj.optString("text").trim()
            if (text.isBlank()) return@mapNotNull null
            val options = obj.optJSONArray("options").toStringList().take(MAX_OPTIONS)
            if (options.isEmpty()) return@mapNotNull null
            AskQuestion(
                // 下标就是稳定 id：同一份参数重算出来的 id 必然一致，
                // 而作答状态是按键存在界面状态里的，必须稳定
                id = "q$i",
                text = text,
                multiSelect = obj.optBoolean("multi", false),
                options = options,
            )
        }.take(MAX_QUESTIONS)
    }

    override suspend fun answer(args: JSONObject, answers: Map<String, AskAnswer>): ToolOutcome {
        val questions = questions(args)
        if (questions.isEmpty()) {
            return ToolOutcome(forModel = "参数错误：没有问题可问。")
        }

        val lines = ArrayList<String>(questions.size)
        var skipped = 0

        questions.forEach { q ->
            val a = answers[q.id] ?: AskAnswer()
            val picked = a.chosen.mapNotNull { q.options.getOrNull(it) }
            val other = a.other?.trim()?.takeIf { it.isNotBlank() }

            val answerText = when {
                // 勾了「其他」并写了字 → 那是用户自己给的答案
                other != null -> picked.joinToString("、").let {
                    if (it.isBlank()) "其他：$other" else "$it；其他：$other"
                }

                picked.isEmpty() -> {
                    skipped++
                    SKIP
                }

                else -> picked.joinToString("、")
            }
            lines += "· ${q.text} → $answerText"
        }

        val body = buildString {
            appendLine("用户的回答：")
            lines.forEach { appendLine(it) }
            if (skipped > 0) {
                appendLine(
                    "（其中 $skipped 题用户选择了跳过 —— 那是**明确的「不想答」**，" +
                        "不要再追问同一件事，按已有信息继续。）",
                )
            }
        }.trim()

        return ToolOutcome(
            forModel = body,
            forUser = ToolDetail(
                arguments = "${questions.size} 个问题",
                result = if (skipped == questions.size) "全部跳过" else "已回答",
            ),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { optString(it).trim().takeIf { s -> s.isNotBlank() } }
    }

    private companion object {
        const val MAX_QUESTIONS = 5
        const val MAX_OPTIONS = 6

        /** 回给模型的"跳过"标记。措辞要让它**别再追问**。 */
        const val SKIP = "（跳过）"
    }
}
