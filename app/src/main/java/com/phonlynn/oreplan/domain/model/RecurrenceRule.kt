package com.phonlynn.oreplan.domain.model

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class Frequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

/**
 * iCal RRULE 的**子集**，只用 `FREQ` / `INTERVAL` / `BYDAY` / `COUNT` / `UNTIL`。
 *
 * 刻意不支持 `BYSETPOS`、`BYYEARDAY`、`BYMONTHDAY` 等完整规则：大学生场景真正会用到的
 * 就是「每天 / 每周几 / 每两周 / 每月某日 / 每年」，把完整 RRULE 引进来会让展开逻辑和
 * 边界情况成倍膨胀，而收益接近于零。
 *
 * 存成字符串（[toRRule]）是为了让数据库那一列保持中立 —— 以后要换成完整 RRULE，
 * 不需要改表结构。
 */
@Immutable
data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    /** 仅 [Frequency.WEEKLY] 有意义；为空表示「与起始日同一星期几」。 */
    val byDay: Set<DayOfWeek> = emptySet(),
    val count: Int? = null,
    val until: Instant? = null,
) {
    init {
        require(interval >= 1) { "INTERVAL 至少为 1，实际 $interval" }
        require(count == null || count >= 1) { "COUNT 至少为 1，实际 $count" }
        require(count == null || until == null) { "COUNT 与 UNTIL 不能同时出现" }
    }

    fun toRRule(): String {
        val parts = mutableListOf("FREQ=${frequency.name}")
        if (interval != 1) parts += "INTERVAL=$interval"
        if (byDay.isNotEmpty()) {
            parts += "BYDAY=" + byDay.sortedBy { it.value }.joinToString(",") { dayCode(it) }
        }
        when {
            count != null -> parts += "COUNT=$count"
            until != null -> parts += "UNTIL=${UNTIL_FORMAT.format(until.truncatedTo(ChronoUnit.SECONDS))}"
        }
        return parts.joinToString(";")
    }

    companion object {
        private val UNTIL_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private val BASIC_DATE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /**
         * 解析 RRULE 字符串。**解析不出来就返回 null（当作不重复）而不是抛异常** ——
         * 这个值来自数据库或未来的导入文件，脏数据的正确反应是降级，不是崩溃。
         */
        fun parse(text: String?): RecurrenceRule? {
            if (text.isNullOrBlank()) return null
            val body = text.trim().removePrefix("RRULE:").removePrefix("rrule:").trim()
            if (body.isEmpty()) return null

            val fields = mutableMapOf<String, String>()
            body.split(';').forEach { segment ->
                val separator = segment.indexOf('=')
                if (separator > 0) {
                    fields[segment.substring(0, separator).trim().uppercase()] =
                        segment.substring(separator + 1).trim()
                }
            }

            val frequency = Frequency.entries.firstOrNull {
                it.name == fields["FREQ"]?.uppercase()
            } ?: return null

            val interval = fields["INTERVAL"]?.toIntOrNull()?.takeIf { it >= 1 } ?: 1

            val byDay = if (frequency == Frequency.WEEKLY) {
                fields["BYDAY"]
                    ?.split(',')
                    ?.mapNotNull { parseDayCode(it) }
                    ?.toSet()
                    .orEmpty()
            } else {
                emptySet()
            }

            val count = fields["COUNT"]?.toIntOrNull()?.takeIf { it >= 1 }
            val until = fields["UNTIL"]?.let { parseUntil(it) }
            if (count != null && until != null) return null

            return RecurrenceRule(
                frequency = frequency,
                interval = interval,
                byDay = byDay,
                count = count,
                until = until,
            )
        }

        private fun parseUntil(raw: String): Instant? {
            val value = raw.trim()
            return runCatching {
                when {
                    value.endsWith("Z") -> LocalDateTime
                        .parse(value.dropLast(1), BASIC_DATE_TIME)
                        .toInstant(ZoneOffset.UTC)

                    else -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                }
            }.getOrNull()
        }

        private fun parseDayCode(token: String): DayOfWeek? = when (token.trim().uppercase()) {
            "MO" -> DayOfWeek.MONDAY
            "TU" -> DayOfWeek.TUESDAY
            "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY
            "FR" -> DayOfWeek.FRIDAY
            "SA" -> DayOfWeek.SATURDAY
            "SU" -> DayOfWeek.SUNDAY
            else -> null
        }

        fun dayCode(day: DayOfWeek): String = when (day) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }
    }
}
