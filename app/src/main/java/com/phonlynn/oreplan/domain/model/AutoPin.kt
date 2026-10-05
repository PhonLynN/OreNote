package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import com.phonlynn.oreplan.domain.expansion.RecurrenceEngine
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 动态置顶的**激活判定**。
 *
 * ## 与手动置顶的关系
 *
 * [BoardCard.pinned]（手动）永久有效；这里判定的是「规则驱动的临时浮起」。
 * 两者**独立并存**：取消手动置顶不会碰到动态规则，反之亦然。
 *
 * ## 判定的三件事
 *
 * 1. 本次浮起的起始时刻（[startOf]）；
 * 2. 是否已被归位（`resolvedAt >= 起始时刻`）；
 * 3. 是否仍在持续时长窗口内。
 *
 * ## 最容易错的两点
 *
 * - **归位比较必须带时刻**，不能只判 `resolvedAt != null`。周期型每轮都会再浮一次，
 *   只判非空会让卡片第一次手动沉下后**永不再浮起**（要等一周才暴露，极难定位）。
 * - **周期型求「最近一次触发」必须走 [RecurrenceEngine]**，不能从 RRULE 锚点步进，
 *   否则「每周一浮起」的卡几年后会空转几千次（项目里已有此约定）。
 */
object AutoPinLogic {

    /**
     * 本次浮起的起始时刻；null 表示没有配置或无法确定。
     *
     * - 日期型：直接是配置的时刻；
     * - 周期型：不晚于 [now] 的**最近一次**触发，按其 `minuteOfDay` 落到具体钟点。
     *
     * [now] 早于周期的第一次触发时返回 null（还没轮到它浮起）。
     */
    fun startOf(card: BoardCard, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? =
        startOf(card.autoPin, card.autoPinResolvedAt, now, zone)

    /**
     * 参数化重载：只看规则（不依赖整张卡片）。
     *
     * [resolvedAt] 目前不参与起始时刻计算（归位判定在 [isActive] 里做），
     * 保留它只是为了让两个重载的参数形状一致、调用方不必区分。
     */
    fun startOf(
        rule: AutoPinRule?,
        @Suppress("UNUSED_PARAMETER") resolvedAt: Instant?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? = when (rule) {
        null -> null
        is AutoPinRule.OnDate -> rule.at
        is AutoPinRule.Recurring -> lastTriggerBefore(rule, now, zone)
    }

    /**
     * 周期型：不晚于 [now] 的最近一次触发。
     *
     * 做法是查询 `[now 前 400 天, now 当天]` 这个窗口内的所有触发并取最后一个。
     * 窗口取 400 天是为了覆盖 YEARLY 与「每 N 月」这类低频规则——
     * 它们相邻两次触发可能隔很久，窗口太窄会查不到而误判为「未触发」。
     */
    private fun lastTriggerBefore(
        rule: AutoPinRule.Recurring,
        now: Instant,
        zone: ZoneId,
    ): Instant? {
        val nowZoned = now.atZone(zone)
        val today = nowZoned.toLocalDate()
        // 锚点：用今天作为展开锚点，保证 firstRelevantStep 从窗口附近开始（不从头步进）。
        val anchor = today.atStartOfDay(zone)
        val from = today.minusDays(LOOKBACK_DAYS)

        val occurrences = RecurrenceEngine.occurrences(
            rule = rule.rule,
            anchor = anchor,
            from = from,
            to = today,
            zone = zone,
        )

        // 把「日期」配上配置的钟点，取不晚于 now 的最后一个。
        return occurrences
            .map { day -> day.toLocalDate().atTime(rule.minuteOfDay / 60, rule.minuteOfDay % 60).atZone(zone) }
            .map { it.toInstant() }
            .filter { !it.isAfter(now) }
            .maxOrNull()
    }

    /** 周期型的历史回溯窗口（天）。覆盖 YEARLY 与「每 N 月」。 */
    private const val LOOKBACK_DAYS = 400L

    /**
     * 这张卡此刻是否处于「动态浮起」状态。
     *
     * 三个条件同时成立：有规则、**已经到点**、未归位、仍在时长窗口内。
     */
    fun isActive(card: BoardCard, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        isActive(card.autoPin, card.autoPinResolvedAt, now, zone)

    /**
     * 同一判定的**参数化重载**：不依赖整张卡片。
     *
     * 用于「手上只有规则与归位时刻」的场景（例如全屏编辑页的草稿只有这两个字段，
     * 为调一个判定去凑一个完整 BoardCard 是多余的）。
     */
    fun isActive(
        rule: AutoPinRule?,
        resolvedAt: Instant?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val r = rule ?: return false
        val start = startOf(r, resolvedAt, now, zone) ?: return false

        // 必须显式判「已到点」：只判 `now < end` 不够——
        // 起始时刻在未来时，`now < start + duration` 同样成立，
        // 卡片会提前浮起（用户设的是以后某个日期，结果现在就顶上去了）。
        if (now.isBefore(start)) return false

        // 归位判定必须与**本次**起始时刻比较：
        // 上一次浮起的归位记录不应该影响这一次（周期型）。
        if (resolvedAt != null && !resolvedAt.isBefore(start)) return false

        val end = start.plus(r.durationMinutes.toLong(), ChronoUnit.MINUTES)
        return now.isBefore(end)
    }

    /**
     * 本次浮起的结束时刻（用于界面提示「几点沉下」）；无规则时返回 null。
     */
    fun endOf(card: BoardCard, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val rule = card.autoPin ?: return null
        val start = startOf(card, now, zone) ?: return null
        return start.plus(rule.durationMinutes.toLong(), ChronoUnit.MINUTES)
    }

    /**
     * 把持续时长夹到合法区间：1 分钟 ~ 365 天。
     *
     * 转发到 `data.mapper.normalizeDurationMinutes`，让 domain 层也有一份可直接调用的入口
     * （UI 与逻辑层都不必依赖 data 包）。
     */
    fun normalizeDurationMinutes(raw: Int): Int =
        raw.coerceIn(AutoPinRule.MIN_DURATION_MINUTES, AutoPinRule.MAX_DURATION_MINUTES)
}
