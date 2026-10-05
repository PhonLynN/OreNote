package com.phonlynn.oreplan.domain.ai.tool

import org.json.JSONObject

/**
 * 一个待回答的问题。设计稿 `tZDQ2` 的一道题。
 *
 * ## `options` 是**有序**的，第一个是模型认为最可能的
 *
 * 因为界面上**默认选中第一个**。顺序由模型给，不是我们排的 ——
 * 它知道用户大概率怎么答，我们不知道。
 */
data class AskQuestion(
    /** 稳定 id，作答时用来定位。 */
    val id: String,
    val text: String,
    /** true = 可多选（界面上画方块），false = 单选（画圆）。 */
    val multiSelect: Boolean,
    /** 候选答案，有序。**不含**「其他（请自行输入）」—— 那是界面固定加的。 */
    val options: List<String>,
)

/**
 * 用户对一道题的作答。
 *
 * ## 「跳过」怎么表达
 *
 * 用户口径：「可以通过选择其他右侧的复选框，不点击其他的输入实现跳过」。
 *
 * 所以 [other] 非空表示用户自己写了答案；[chosen] 为空且 [other] 为空，
 * 表示**这题跳过** —— 不是"没答"，是"明确不想答"。
 * 回给模型的文本必须把两者分开说，否则它会以为问卷没填完而反复追问。
 */
data class AskAnswer(
    /** 选中的选项**下标**（对应 [AskQuestion.options]）。 */
    val chosen: Set<Int> = emptySet(),
    /** 「其他」里用户输入的文字。 */
    val other: String? = null,
) {
    /** 跳过：什么都没选、也没输入。 */
    val skipped: Boolean get() = chosen.isEmpty() && other.isNullOrBlank()
}

/**
 * 需要**用户回答**的工具（目前只有 `ask_user`）。
 *
 * ## 为什么与 [ConfirmableTool] 分开
 *
 * 两者都需要"停下来等用户"，机制是共用的（循环挂起 → 界面出卡 → 补结果 → 再发一次）。
 * 但**用户要做的事完全不同**：
 *
 * · [ConfirmableTool]：看一份变更清单，勾选哪些照做 → 结果是"执行了/驳回了"
 * · 本接口：回答几个问题 → 结果是**用户给的信息**，不是一次写入
 *
 * 硬塞进 [ConfirmableTool] 的话，`accepted: Set<String>` 就得同时表达
 * "保留哪几条变更"和"选了哪几个选项"，而后者还需要承载自由文本。那是把
 * 两种语义挤进一个参数，迟早出错。
 *
 * ## 与写操作一样，`prompt` 必须是纯的
 *
 * 它会算两次（出题时、作答后），保证"用户看到的题"和"用户答的题"是同一份。
 */
interface AskingTool : AiTool {

    /**
     * 危险级别是 [ToolDanger.ASK] —— 它不写数据，但必须等用户。
     *
     * 界面据此决定画提问卡还是变更预览卡。
     */
    override val danger: ToolDanger get() = ToolDanger.ASK

    /**
     * 提问工具**永远不该被直接执行**。
     *
     * 调度循环遇到 [AskingTool] 会把它记成待确认而不是调这个方法
     *（见 `SendChatMessage` 里那个 `tool is ConfirmableTool || tool is AskingTool`）。
     *
     * 与 [ConfirmableTool.run] 同一个理由：给默认实现，免得每个实现各抄一遍。
     */
    override suspend fun run(args: JSONObject): ToolOutcome = ToolOutcome(
        forModel = "工具 `$name` 需要用户回答，不能直接执行。",
    )

    /** 要问的问题。纯函数，只读参数。 */
    suspend fun questions(args: JSONObject): List<AskQuestion>

    /**
     * 用户答完之后，把答案组织成回给模型的文本。
     *
     * 与 [ConfirmableTool.apply] 同一个道理：这里**不写库**，
     * 只把"用户说了什么"如实转述给模型。
     */
    suspend fun answer(args: JSONObject, answers: Map<String, AskAnswer>): ToolOutcome
}
