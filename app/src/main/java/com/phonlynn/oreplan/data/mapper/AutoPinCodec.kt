package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.domain.model.AutoPinRule
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import org.json.JSONObject
import java.time.Instant

/**
 * 动态置顶规则的**落库编解码**。
 *
 * 单独成一个文件，是因为它有**三个消费方**：数据库映射、备份、草稿。
 * 三处必须共用同一份实现——各写一份必然会漂移，症状是
 * 「存进去能读出来，但备份还原后少了动态置顶」，而且极难定位。
 *
 * ## 存储形态
 *
 * 拆成三列存（kind / rule / durationMinutes），而不是把整个规则塞成一列：
 *  - `kind` 单独成列，便于将来按类型查询，也让「未知类型」的降级更直接；
 *  - `rule` 只存模式**特有**的参数（日期型存时刻；周期型存 RRULE + 浮起分钟）；
 *  - `durationMinutes` 两种模式共有，单独成列。
 *
 * JSON 用紧凑的短键以省空间（`at` / `rrule` / `min`）。
 *
 * ## 容错
 *
 * 解码遇到未知 kind、非法 JSON、缺失字段时**一律返回 null**（视为未启用），
 * 绝不抛异常。这与项目「枚举存字符串、读取脏值降级不崩溃」的约定一致。
 */
object AutoPinCodec {

    const val KIND_DATE = "DATE"
    const val KIND_RECUR = "RECUR"

    /**
     * 编码规则的特有参数。`durationMinutes` 不在这里——它单独成列。
     */
    fun encodeRule(rule: AutoPinRule): String = when (rule) {
        is AutoPinRule.OnDate -> JSONObject().apply {
            put("at", rule.at.toEpochMilli())
        }.toString()

        is AutoPinRule.Recurring -> JSONObject().apply {
            put("rrule", rule.rule.toRRule())
            put("min", rule.minuteOfDay)
        }.toString()
    }

    /** 规则类型键；null 表示不启用。 */
    fun kindOf(rule: AutoPinRule?): String? = when (rule) {
        null -> null
        is AutoPinRule.OnDate -> KIND_DATE
        is AutoPinRule.Recurring -> KIND_RECUR
    }

    /**
     * 解码。[durationMinutes] 会经 [normalizeDurationMinutes] 夹取到合法区间。
     * 任何一步失败都返回 null（不启用）。
     */
    fun decode(kind: String?, rule: String?, durationMinutes: Int?): AutoPinRule? {
        if (kind.isNullOrBlank()) return null
        val duration = normalizeDurationMinutes(
            durationMinutes ?: AutoPinRule.DEFAULT_DURATION_MINUTES,
        )
        return runCatching {
            val obj = JSONObject(rule.orEmpty())
            when (kind) {
                KIND_DATE -> {
                    val at = obj.optLong("at", Long.MIN_VALUE)
                    if (at == Long.MIN_VALUE) null
                    else AutoPinRule.OnDate(at = Instant.ofEpochMilli(at), durationMinutes = duration)
                }

                KIND_RECUR -> {
                    val parsed = RecurrenceRule.parse(obj.optString("rrule"))
                    if (parsed == null) null
                    else AutoPinRule.Recurring(
                        rule = parsed,
                        minuteOfDay = obj.optInt("min", 8 * 60).coerceIn(0, 1439),
                        durationMinutes = duration,
                    )
                }

                else -> null
            }
        }.getOrNull()
    }
}

/**
 * 把持续时长夹到合法区间：1 分钟 ~ 365 天。
 *
 * **必须在这里做，不能只在 UI 校验。** 备份导入、草稿恢复都是旁路，
 * 它们不经过 UI。只有数据层的夹取才能保证
 * 「动态置顶最长一年后必然归位」这个不变量真正成立。
 */
fun normalizeDurationMinutes(raw: Int): Int =
    raw.coerceIn(AutoPinRule.MIN_DURATION_MINUTES, AutoPinRule.MAX_DURATION_MINUTES)
