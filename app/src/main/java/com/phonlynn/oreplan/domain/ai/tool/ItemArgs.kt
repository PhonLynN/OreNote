package com.phonlynn.oreplan.domain.ai.tool

import com.phonlynn.oreplan.domain.model.ItemStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日程/待办写入工具共用的参数解析。
 *
 * ## 为什么必须共用一份
 *
 * 新建与修改如果各写一套时间解析，同一个时刻"建出来"和"改出来"可能不一致
 *（一个按设备时区、一个按 UTC），而那种偏差**不会报错**，
 * 只会让用户发现"改了时间之后差了几个小时"。
 *
 * ## 墙上时间
 *
 * 模型给的是 `2026-10-04T14:00` 这种**不带时区**的写法。用户说"明天下午两点"
 * 指的是他手表上的两点，所以按**设备时区**解释。用 UTC 会整体偏 8 小时，
 * 而且偏得毫无规律（取决于用户在哪个时区）。
 */
internal object ItemArgs {

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 解析墙上时间。三种写法都认（模型三种都会用）：
     *
     * · `2026-10-04T14:00` / `2026-10-04 14:00`
     * · `2026-10-04T14`（不带分钟）
     * · `2026-10-04`（只有日期 → 当天 09:00，比 00:00 更接近用户意图）
     */
    fun parseInstant(text: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val trimmed = text.trim().replace(' ', 'T')
        if (trimmed.isBlank()) return null

        runCatching { LocalDateTime.parse(trimmed) }.getOrNull()?.let {
            return it.atZone(zone).toInstant()
        }
        runCatching { LocalDate.parse(trimmed) }.getOrNull()?.let {
            return it.atTime(9, 0).atZone(zone).toInstant()
        }
        return null
    }

    /** 展示用：`10月4日 14:00–15:30`。 */
    fun rangeText(start: Instant?, end: Instant?, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = start?.atZone(zone) ?: return ""
        val e = end?.atZone(zone)
        val head = "%d月%d日 %02d:%02d".format(s.monthValue, s.dayOfMonth, s.hour, s.minute)
        return if (e == null) head else "$head–%02d:%02d".format(e.hour, e.minute)
    }

    /** 展示用：`2026-10-04 14:00`（变更预览里"旧值"那种带年份的完整写法）。 */
    fun fullText(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.atZone(zone)?.format(DATE_TIME) ?: ""

    /** `todo/doing/done/cancelled` 或中文。认不出来返回 null（= 没传）。 */
    fun parseStatus(text: String): ItemStatus? = when (text.trim().lowercase()) {
        "todo", "待办", "未开始", "未完成" -> ItemStatus.TODO
        "doing", "进行中", "在做" -> ItemStatus.DOING
        "done", "已完成", "完成" -> ItemStatus.DONE
        "cancelled", "canceled", "已取消", "取消" -> ItemStatus.CANCELLED
        else -> null
    }

    /** 展示用。 */
    fun statusLabel(status: ItemStatus): String = when (status) {
        ItemStatus.TODO -> "待办"
        ItemStatus.DOING -> "进行中"
        ItemStatus.DONE -> "已完成"
        ItemStatus.CANCELLED -> "已取消"
    }
}

/** 取一个可选字符串；缺失或空串都算"没传"。 */
internal fun org.json.JSONObject.optTextOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }

/**
 * 取一个可选布尔。
 *
 * 不能用 `optBoolean(key, false)` —— 那分不清"没传"和"传了 false"，
 * 而这里两者语义完全不同（不动 vs 取消置顶）。
 */
internal fun org.json.JSONObject.optBoolOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)
