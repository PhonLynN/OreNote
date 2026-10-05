package com.phonlynn.oreplan.domain.ai

import java.util.Calendar

/**
 * 输入框的占位文案池。
 *
 * ## 为什么写死在代码里
 *
 * 用户明确说「文案这些东西不需要任何显式设置」。所以：
 *  · 没有设置界面
 *  · 不改 schema、不进数据库
 *  · 改文案 = 改这个文件（用户接受，因为他说"等我用一阵子再说"）
 *
 * ## 为什么分时段
 *
 * 用户要求「时段类需要有判断逻辑」。判断**不调 AI** ——
 * 只看当前钟点，纯代码。这既避免了"AI 猜错情绪"，也不会产生 API 成本。
 *
 * ## 为什么要排除上一条
 *
 * 25 条听起来不少，但若随机到同一条连续出现两次，观感很差
 * （用户会以为文案是坏的）。所以记住上一条、下次从剩下的里取。
 */
object ConversationPlaceholder {

    /** 凌晨 00:00–05:00。 */
    private val LATE_NIGHT = listOf(
        "这么晚还没睡？",
        "睡前想聊两句吗？",
    )

    /** 早晨 05:00–11:00。 */
    private val MORNING = listOf(
        "这么早，今天打算做什么？",
        "上午好，今天状态怎么样？",
    )

    /** 下午 11:00–18:00。 */
    private val AFTERNOON = listOf(
        "下午了，还撑得住吗？",
    )

    /** 晚上 18:00–24:00。 */
    private val EVENING = listOf(
        "晚上了，今天过得如何？",
    )

    /** 任何时候都能用。 */
    private val ANYTIME = listOf(
        // 关心的
        "最近过得怎样？",
        "在意的事情有进展了吗？",
        "有心事就和我聊聊吧",
        "我一直在你身边",
        "需要我的帮助吗？",
        "有什么想说的，都可以",
        "今天还顺利吗？",
        "想聊点什么都可以",
        // 推动的
        "让今天也效率满满",
        "想想明天做点什么？",
        "今天想推进哪件事？",
        "要不要把想法记下来？",
        "有什么计划想理一理？",
        "今天先做哪一件？",
        // 轻一点的
        "在呢，随时找我",
        "有什么新鲜事？",
        "今天有什么想记的？",
        "想到什么就说什么",
        "随便聊两句也行",
    )

    /**
     * 当前时段可用的全部文案。
     *
     * 做法是**当前时段 + 通用池合并**，而不是"只显示时段专属的"。
     * 理由：凌晨只有 2 条，只从它们里取会让重复率极高 ——
     * 用户连续几次进对话都看到同一句，会以为文案坏了。
     */
    fun poolFor(now: Long = System.currentTimeMillis()): List<String> =
        ANYTIME + slotFor(now)

    private fun slotFor(now: Long): List<String> {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 0..4 -> LATE_NIGHT
            in 5..10 -> MORNING
            in 11..17 -> AFTERNOON
            else -> EVENING
        }
    }

    /**
     * 随机取一条，尽量避开 [previous]。
     *
     * 池子只有 1 条时（理论上不会发生，通用池就 19 条）直接返回它，
     * 不能因为"排除后没有候选"而返回空串 —— 那会让输入框没有占位符。
     */
    fun pick(
        previous: String? = null,
        now: Long = System.currentTimeMillis(),
        random: java.util.Random = java.util.Random(),
    ): String {
        val pool = poolFor(now)
        if (pool.isEmpty()) return ""
        val candidates = pool.filter { it != previous }.ifEmpty { pool }
        return candidates[random.nextInt(candidates.size)]
    }

    /** 全部文案条数（供调试与文档引用）。 */
    val totalCount: Int get() = ANYTIME.size + LATE_NIGHT.size + MORNING.size + AFTERNOON.size + EVENING.size
}
