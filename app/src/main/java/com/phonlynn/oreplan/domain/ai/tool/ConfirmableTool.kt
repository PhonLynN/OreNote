package com.phonlynn.oreplan.domain.ai.tool

import org.json.JSONObject

/** 一条记录要做什么。设计稿 `h6z5Uh` 的 `Change Pill`（修改 / 新增 / 删除）。 */
enum class ChangeOperation(val label: String) {
    CREATE("新增"),
    UPDATE("修改"),
    DELETE("删除"),
}

/**
 * 一个字段的变更。设计稿里的 `old chip` / `new chip` / `same chip`。
 *
 * 三种状态用**两个可空值**表达，而不是一个枚举 ——
 * 因为它们本来就只有三种合法组合：
 *
 * | 状态 | old | new | 稿子里的画法 |
 * |---|---|---|---|
 * | 新增（原本没有这个字段） | `null` | 有值 | 只有 `new chip`（accent） |
 * | 修改 | 有值 | 有值 | `old chip`（rose）在上，`new chip` 在下 |
 * | 值没变 | 有值 | 有值（相同） | `same chip`（`$bg` 无色、无图标） |
 *
 * 「值没变」也要列出来是**刻意的**：用户一眼就能看出"只有时间和提醒改了"，
 * 不用自己逐个对比。设计稿的 `Field 标题` 就是这么画的。
 */
data class ChangeField(
    val label: String,
    val old: String?,
    val new: String?,
) {
    /** 值没变。 */
    val unchanged: Boolean get() = old != null && old == new

    /** 新增了一个原本没有的字段。 */
    val isNew: Boolean get() = old == null && new != null
}

/**
 * 一条待确认的记录 = 一个实体（一条日程 / 一张卡片 / 一个待办…）。
 *
 * [id] 必须是**稳定**的：用户在界面上取消勾选某一项时，靠它把那一项排除掉。
 */
data class ChangeRecord(
    val id: String,
    val typeLabel: String,
    val operation: ChangeOperation,
    val fields: List<ChangeField>,
    /**
     * **操作对象的名字** —— 这条记录改的是"什么"。
     *
     * ## ⚠️ 这一项是用户报出来的缺失
     *
     * 原话：
     *
     * > 「这几十个任务卡片没有半点索引，我都不知道自己改了啥，至少显示个标题，
     * > 没有标题显示个前十个字也行啊，总之要写明操作对象」
     *
     * 之前的记录头只有两个胶囊（类型 + 操作），看上去是：
     *
     * ```
     * [卡片] [修改]                    ☑
     * ```
     *
     * 一屏十几条这样的记录，**没有任何办法分辨哪条是哪张卡** ——
     * 用户只能靠读下面的字段内容去猜，而字段内容又长又像。
     *
     * 所以每条记录必须自带一个"我改的是谁"。
     *
     * ## 取值规则
     *
     * 优先用标题；没有标题就用正文的开头若干字；两者都没有才退回 id
     *（正常情况下不会走到那一步）。由各工具自己算 —— 它最清楚
     * "这条记录的主角是谁"。
     */
    val subject: String = "",
)

/**
 * 需要用户确认的工具（**所有写操作**）。
 *
 * ## 为什么预览与执行分成两步
 *
 * 用户口径：「确认必须发生在执行之前，不是执行完再问」。
 *
 * 所以 [preview] 必须是**纯的** —— 只算不写。它会被调用两次：
 *  ① 模型要求调用时，产出预览给用户看
 *  ② 用户确认后，再算一次拿到 [ChangeRecord.id]，按用户勾选的集合执行
 *
 * 第二次重算是刻意的（而不是把预览结果存起来复用）：
 * 存起来就要把整份变更序列化进会话记录里，而那会让"会话记录"和"真实数据"
 * 有机会不一致。重算的成本只是几条查询，换来的是**永远不会执行到过期数据**。
 */
interface ConfirmableTool : AiTool {

    /** 写操作一律要确认（用户 2026-10-03 的口径）。 */
    override val danger: ToolDanger get() = ToolDanger.WRITE

    /**
     * 写工具**永远不该被直接执行**。
     *
     * 调度循环遇到 [ConfirmableTool] 会把它记成待确认而不是调这个方法
     *（见 `SendChatMessage` 里那个 `if (tool is ConfirmableTool)`）。
     *
     * 这里给一个**默认的拒绝实现**，是为了让每个写工具不必各写一遍同样的
     * `error("不该被调用")` —— 那种样板代码抄到最后总会有人抄错。
     * 万一哪天调度改坏了真的调到这里，返回的这句也会如实告诉模型"没执行"，
     * 而不是悄悄写进数据库。
     */
    override suspend fun run(args: JSONObject): ToolOutcome = ToolOutcome(
        forModel = "工具 `$name` 是写操作，必须先经用户确认，不能直接执行。",
    )

    /**
     * 只计算变更，**不落库**。
     *
     * 返回空列表表示"这次调用其实不会改变任何东西" ——
     * 上层会把它当成一次普通读取直接回给模型，不打扰用户。
     */
    suspend fun preview(args: JSONObject): List<ChangeRecord>

    /**
     * 用户确认后真正执行。
     *
     * @param accepted 用户**勾选保留**的 [ChangeRecord.id] 集合。
     *   没勾的必须原样跳过 —— 用户取消勾选就是"这一项不要做"。
     */
    suspend fun apply(args: JSONObject, accepted: Set<String>): ToolOutcome
}
