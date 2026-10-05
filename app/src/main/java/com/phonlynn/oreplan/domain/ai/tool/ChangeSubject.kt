package com.phonlynn.oreplan.domain.ai.tool

/**
 * 变更记录里**「这条改的是谁」**那行字怎么取。
 *
 * ## 为什么要有统一规则
 *
 * 用户报过：「这几十个任务卡片没有半点索引，我都不知道自己改了啥，
 * 至少显示个标题，没有标题显示个前十个字也行啊，总之要写明操作对象」。
 *
 * 各工具自己写取名逻辑的话，会出现"日程取标题、卡片取正文、待办取 id"
 * 这种不一致 —— 而**一致性正是这份索引的意义**：用户扫一眼列表，
 * 每行都该是同一类信息，才能快速定位。
 *
 * ## 规则
 *
 * ```
 * 有标题        → 用标题
 * 没标题有正文  → 用正文前 12 个字（多了截断加省略号）
 * 都没有        → 空串（界面会退回显示 id）
 * ```
 *
 * 12 个字是**一行的容量**：16sp 字号下一行大约放得下 14 个中文字，
 * 留一点余量给右侧的勾选框。
 */
internal object ChangeSubject {

    /** 正文最多取几个字。 */
    private const val MAX_BODY_CHARS = 12

    /**
     * 取操作对象的名字。
     *
     * @param title 标题（可空）
     * @param body 正文（可空）—— 标题为空时用它兜底
     */
    fun of(title: String?, body: String? = null): String {
        title?.trim()?.takeIf { it.isNotBlank() }?.let { return it.limit() }
        body?.trim()?.takeIf { it.isNotBlank() }?.let { return it.limit() }
        return ""
    }

    /**
     * 截到一行放得下。
     *
     * ⚠️ 换行符要先换成空格 —— 多行正文直接截会带出换行，
     * 界面上一行标题变成两行，列表立刻参差不齐。
     */
    private fun String.limit(): String {
        val oneLine = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= MAX_BODY_CHARS) {
            oneLine
        } else {
            oneLine.take(MAX_BODY_CHARS) + "…"
        }
    }
}
