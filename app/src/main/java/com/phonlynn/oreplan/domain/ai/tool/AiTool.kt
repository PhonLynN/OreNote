package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.ai.protocol.ToolCall
import com.phonlynn.oreplan.domain.ai.protocol.ToolSpec
import org.json.JSONObject

/**
 * 工具的危险级别。
 *
 * ## 为什么是一个级别，而不是几个布尔开关
 *
 * 用户口径（2026-10-03，**推翻了更早的口径**）：
 * 「我觉得写部分不只是删除，如果修改/添加也需要确认」。
 *
 * | 级别 | 例子 | 要不要用户确认 |
 * |---|---|---|
 * | [READ] | 查日程、算空闲、读卡片 | **不确认**，直接执行 |
 * | [WRITE] | 新增 / 修改 / **删除** | **确认**，且在执行之前 |
 *
 * 所以确认逻辑由**级别**驱动，而不是每个工具里写一个 `if (needsConfirm)`。
 * 加新工具时只要声明级别，确认流程自动生效 —— 这正是「工具要能随意添加」的前提。
 *
 * 刻意**不做**「删除单独一级」：用户明确说了写操作一律确认，
 * 再加一级只会让「哪个按钮变色」这类判断散到各处。
 */
enum class ToolDanger {
    READ,
    WRITE,

    /**
     * 需要**用户回答**（提问工具）。
     *
     * 它不写任何数据，所以不是 [WRITE]；但它也不能像 [READ] 那样直接执行 ——
     * 答案只有用户知道。界面据此决定画提问卡（`tZDQ2`）而不是变更预览卡（`h6z5Uh`）。
     */
    ASK,
}

/**
 * 一个工具。
 *
 * ## 加一个工具要做什么
 *
 * 1. 写一个实现这个接口的类（`@Inject constructor`，依赖从 DI 拿）
 * 2. 在 [AiToolModule] 里加一行 `@IntoSet`
 *
 * 不需要改注册表、调度、协议层或界面 —— 那几处只认这个接口。
 *
 * ## 实现约定
 *
 * · [name] 用**下划线小写**（OpenAI 对函数名的要求），且要稳定 —— 改动等于让模型失忆
 * · [description] 是**写给模型看的**，要说明"什么时候该用它"，
 *   而不只是"它做什么"。模型的工具选择质量几乎完全取决于这句话
 * · [parameters] 是 JSON Schema，`type` 必须是 `object`
 * · [run] 里**不要**吞异常：抛出去会被上层转成 `role="tool"` 的错误文本回给模型，
 *   模型据此重试；吞掉就变成一个假的成功结果
 */
interface AiTool {

    /** 函数名（给小写+下划线）。 */
    val name: String

    /** 给用户看的名字，如「创建日程」（工具卡上那一行）。 */
    val displayName: String

    /** 给模型的说明。**要说清什么时候用**。 */
    val description: String

    /** JSON Schema 的 `parameters` 部分。 */
    val parameters: JSONObject

    /** 危险级别 —— 决定要不要用户确认。 */
    val danger: ToolDanger

    /**
     * 执行。
     *
     * ⚠️ **只有 [ToolDanger.READ] 的工具会直接走到这里。**
     * 写工具走 [ConfirmableTool.preview]，用户确认后才执行。
     *
     * @param args 模型给的参数（已解析；解析失败时上层不会调用本方法）
     */
    suspend fun run(args: JSONObject): ToolOutcome

    /** 组装成协议层的定义。 */
    fun spec(): ToolSpec = ToolSpec(name = name, description = description, parameters = parameters)
}

/**
 * 一次执行的结果。
 *
 * 分成两部分是因为它们**去处不同**：
 * · [forModel] 回给模型（`role="tool"` 的正文）
 * · [forUser] 显示在工具卡下方那一块（设计稿 `jTU7u` 的 `Tool Detail`）
 *
 * 合成一份会导致其中一个总是将就另一个：给模型看的要完整、精确；
 * 给人看的要短、可读。比如查询日程，模型需要全部字段，人只需要「明天 2 件事」。
 */
data class ToolOutcome(
    /** 回给模型的文本。可以长，可以结构化。 */
    val forModel: String,
    /**
     * 显示给用户的摘要（工具卡下方那块 `Tool Detail`）。
     *
     * `null` = 不显示下方那一块。**读取类工具默认用 [ToolDetail.of] 生成两行**
     *（`调用参数：…` / `返回结果：…`），与设计稿一致。
     */
    val forUser: ToolDetail? = null,
)

/**
 * 工具卡下方那一块的两行文字。
 *
 * 设计稿 `jTU7u` 的 `Tool Detail`：左侧 2px 竖线，右侧两行 `fs13 $ink-3`，
 * 内容分别是「调用参数：…」和「返回结果：…」——
 * 和推理块（`Reasoning`）是**完全同构**的，所以这里只存文本。
 *
 * 把参数显示出来是刻意的：用户能看到 AI 把「明天上午」理解成了什么，
 * 理解错了一眼就能发现。这是这套界面最重要的可验证性设计。
 */
data class ToolDetail(
    val arguments: String,
    val result: String?,
) {
    companion object {
        /** 从原始调用与结果生成，是绝大多数工具的默认做法。 */
        fun of(call: ToolCall, summaryForUser: String): ToolDetail =
            ToolDetail(arguments = call.readableArguments(), result = summaryForUser)
    }
}

/**
 * 把参数对象压成一行可读文字。
 *
 * 模型给的键名是英文下划线（`start_time`），直接显示会很难读，
 * 但这里**不做键名翻译** —— 翻译表维护不过来，而且加了工具就得同步改。
 * 只做三件事：拆成 `键 值` 对、去掉引号、用中文逗号连起来。
 */
internal fun ToolCall.readableArguments(): String {
    val keys = arguments.keys().asSequence().toList()
    if (keys.isEmpty()) return "无"
    return keys.joinToString("，") { key ->
        val value = arguments.opt(key)
        val text = when (value) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> value.toString()
        }
        if (text.isBlank()) key else "$key $text"
    }
}
