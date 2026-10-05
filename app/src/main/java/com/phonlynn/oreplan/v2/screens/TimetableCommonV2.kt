package com.phonlynn.oreplan.v2.screens

import androidx.compose.ui.graphics.Color
import com.phonlynn.oreplan.core.time.WeekParity
import com.phonlynn.oreplan.domain.backup.BackupCodec
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.routine.RoutineConfig
import com.phonlynn.oreplan.domain.routine.RoutineSchedule
import com.phonlynn.oreplan.v2.theme.VColors
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

/**
 * 课表模块共用的纯函数与小工具。
 *
 * 周几标签、配色映射、节次换算都只依赖领域模型与设计令牌，不碰 Compose 状态，
 * 方便单独测试，也避免五个屏幕各写一份。
 */
internal val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 一门课在网格/列表里用的「浅底 + 深字」配色，来自 V2 设计令牌。 */
internal data class CourseTint(val soft: Color, val strong: Color)

internal object CoursePalette {
    /** 用户可选的五个颜色，hex 对应 VColors 里的令牌值，落库后能稳定反查回配色。 */
    val keys: List<String> = listOf(
        "#9C6516", // 琥珀
        "#BC5F63", // 玫红
        "#6A61BE", // 紫
        "#1C6B58", // 主绿
        "#6F7A73", // 中性灰
    )

    private val FIXED = mapOf(
        "#9C6516" to CourseTint(VColors.amberSoft, VColors.amber),
        "#BC5F63" to CourseTint(VColors.roseSoft, VColors.rose),
        "#6A61BE" to CourseTint(VColors.lilacSoft, VColors.lilac),
        "#1C6B58" to CourseTint(VColors.accentSoft, VColors.accent),
        "#6F7A73" to CourseTint(VColors.surface2, VColors.ink3),
    )

    private val CYCLE = listOf(
        CourseTint(VColors.amberSoft, VColors.amber),
        CourseTint(VColors.roseSoft, VColors.rose),
        CourseTint(VColors.lilacSoft, VColors.lilac),
        CourseTint(VColors.accentSoft, VColors.accent),
    )

    /**
     * 按课程自选颜色取配色；没有颜色或颜色不认识时，按 token 稳定地循环到一个色，
     * 保证同一门课每次进来都是同一个颜色。
     */
    fun tintFor(colorHex: String?, token: String): CourseTint {
        colorHex?.let { hex -> FIXED[hex]?.let { return it } }
        return CYCLE[((token.hashCode() % CYCLE.size) + CYCLE.size) % CYCLE.size]
    }
}

/**
 * 网格里的一块课程。直接从 [CourseSession] 模板构建，不经过日期展开 ——
 * 课表是「星期几 + 节次」的模板视图，展示周切换时只需要重算 [active] 这一个布尔。
 */
data class GridBlock(
    val sessionId: String,
    val courseId: String,
    val name: String,
    val location: String?,
    val teacher: String?,
    val weeks: String,
    val startPeriod: Int,
    val endPeriod: Int,
    val colorHex: String?,
    /** 该时段在「正在展示的周」是否真的上课（起止周 + 单双周都命中）。 */
    val active: Boolean,
)

/** 把一个上课时段的分钟数映射到节次区间（1 起）。时段横跨课间很正常，取首尾节次。 */
internal fun periodRangeOf(config: RoutineConfig, startMinute: Int, endMinute: Int): IntRange? {
    val periods = RoutineSchedule.compute(config)
    if (periods.isEmpty()) return null
    val startP = periods.firstOrNull { it.endMinute > startMinute }?.period ?: return null
    val endP = periods.lastOrNull { it.startMinute < endMinute }?.period ?: return null
    if (endP < startP) return null
    return startP..endP
}

/** 起止周显示文本："1-16 周"；结束周到学期末时用学期总周数替代。 */
internal fun weeksLabel(session: CourseSession, totalWeeks: Int): String {
    val end = if (session.endWeek == Int.MAX_VALUE) totalWeeks else session.endWeek
    return if (session.startWeek == end) "${session.startWeek} 周" else "${session.startWeek}-$end 周"
}

/** "第 3 节" / "第 1-4 节"。 */
internal fun periodSpanLabel(startPeriod: Int, endPeriod: Int): String =
    if (startPeriod == endPeriod) "第 $startPeriod 节" else "第 $startPeriod-$endPeriod 节"

internal val WEEK_RANGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
internal val DATE_FORMAT_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

/** 分钟数 → 节次区间（编辑器对话框里用节次步进，落库仍用分钟数）。 */
internal fun minutesToPeriods(config: RoutineConfig, startMinute: Int, endMinute: Int): Pair<Int, Int> {
    val periods = RoutineSchedule.compute(config)
    if (periods.isEmpty()) return 1 to 1
    val sp = periods.firstOrNull { it.endMinute > startMinute }?.period ?: 1
    val ep = periods.lastOrNull { it.startMinute < endMinute }?.period ?: sp
    return sp to ep
}

/** 节次区间 → 分钟数（开始取开始节次的起点，结束取结束节次的终点）。 */
internal fun periodsToMinutes(config: RoutineConfig, startPeriod: Int, endPeriod: Int): Pair<Int, Int> {
    val periods = RoutineSchedule.compute(config)
    val sp = periods.getOrNull(startPeriod - 1)?.startMinute ?: config.startMinute
    val ep = periods.getOrNull(endPeriod - 1)?.endMinute ?: (sp + config.periodMinutes)
    return sp to ep
}

/**
 * **导入专用**：节次区间 → 分钟数，越界返回 null。
 *
 * 与 [periodsToMinutes] 的区别：后者对越界节次做**兜底夹取**（界面上步进时不会越界，
 * 兜底只是防御）；而导入的数据来自外部文件，越界意味着**文件写错了**——
 * 这时夹取会让用户以为导入成功、但课表是错的，所以必须让它整条被丢弃。
 *
 * 判据：`startPeriod` 与 `endPeriod` 都要落在 `1..总节数`，且 `endPeriod >= startPeriod`。
 */
internal fun importPeriodsToMinutes(
    config: RoutineConfig,
    startPeriod: Int,
    endPeriod: Int,
): Pair<Int, Int>? {
    val periods = RoutineSchedule.compute(config)
    if (periods.isEmpty()) return null
    val total = periods.size
    if (startPeriod !in 1..total) return null
    if (endPeriod !in 1..total) return null
    if (endPeriod < startPeriod) return null
    val sp = periods[startPeriod - 1].startMinute
    val ep = periods[endPeriod - 1].endMinute
    if (ep <= sp) return null
    return sp to ep
}

/**
 * 解析一个 session 的时间区间。支持两种写法：
 *
 *  · **节次**：`startPeriod` / `endPeriod`（推荐）——跟随作息设置；
 *  · **绝对分钟**：`startMinute` / `endMinute`（向后兼容）。
 *
 * 两种都给时**以节次为准**（更明确，也更不易错）。
 * 解析不出有效区间时返回 null，调用方应**丢弃该 session**
 * （不夹取、不默认成 1 节——那会让错误的文件看起来导入成功）。
 *
 * @param startPeriod 节次写法的开始节；null 表示没写
 * @param endPeriod   节次写法的结束节；null 表示没写（单节时与 startPeriod 相同）
 * @param startMinute 分钟写法的开始；-1 表示没写
 * @param endMinute   分钟写法的结束；-1 表示没写
 */
internal fun resolveImportRange(
    config: RoutineConfig,
    startPeriod: Int?,
    endPeriod: Int?,
    startMinute: Int,
    endMinute: Int,
): Pair<Int, Int>? {
    if (startPeriod != null) {
        // 节次写法：没写 endPeriod 视作单节。
        return importPeriodsToMinutes(config, startPeriod, endPeriod ?: startPeriod)
    }
    if (startMinute < 0 || endMinute <= startMinute) return null
    return startMinute to endMinute
}

// ---------------------------------------------------------------- 课表格子分段

/**
 * 课表格子的一段：连续的节次区间 + 落在这一段里的课程（可能不止一门）。
 *
 * [blocks] 为空 = 空白段（可点击新建）。
 * 长度 = `endPeriod - startPeriod + 1` 节。
 */
internal data class DayCell(
    val startPeriod: Int,
    val endPeriod: Int,
    val blocks: List<GridBlock>,
) {
    val span: Int get() = endPeriod - startPeriod + 1
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/**
 * 把某一天的课程切成格子序列。
 *
 * ## 为什么不能像以前那样「单向前进 cursor」
 *
 * 旧实现：
 * ```
 * var cursor = 1
 * for (block in blocks) {
 *     val start = maxOf(block.startPeriod, cursor)   // 重叠 → 截断
 *     if (end < start) continue                       // 完全重叠 → 跳过
 *     cursor = end + 1
 * }
 * ```
 * 它**假设同一天的课不重叠**。一旦重叠，被跳过/截断的课**不再占高度**，
 * 该列累计高度就比其他列短 —— 表现为「有些列的课往上挤」。
 *
 * ## 现在的做法：按节次占位
 *
 * 1. 建立 `1..totalPeriods` 的槽位；
 * 2. 每个 block 把它覆盖的槽位标记为「已占用」；
 * 3. 相邻且都为空 / 都是同一门课 → 合并为一段；
 * 4. 落在同一节次的多门课 → 合并进同一段的 [DayCell.blocks]（由界面决定怎么排）。
 *
 * **不变量**：所有段的总长度恒等于 `totalPeriods`。
 * 也就是说，一天的格子高度只与节次数有关，**与内容无关** ——
 * 这是「五列永远对齐」的保证，也是本函数最重要的性质（有单测钉住）。
 *
 * 越界的 block（`startPeriod`/`endPeriod` 超出 `1..totalPeriods`）会被裁到范围内；
 * 完全落在范围外的直接忽略（依然不改变总长度）。
 *
 * @param blocks 该天的全部课程（顺序无关，函数内部会排序）
 * @param totalPeriods 作息总节数
 */
internal fun buildDayCells(blocks: List<GridBlock>, totalPeriods: Int): List<DayCell> {
    if (totalPeriods <= 0) return emptyList()

    // 槽位 → 落在该节次的课程
    val slots = Array(totalPeriods + 1) { mutableListOf<GridBlock>() }
    blocks.forEach { b ->
        // **完全落在范围外的直接忽略**，不做夹取 ——
        // 夹取会把 `20..25` 变成「第 14 节有课」，凭空造出一节不存在的课。
        if (b.endPeriod < 1 || b.startPeriod > totalPeriods) return@forEach
        // 只把「与范围相交的部分」裁进来。
        val from = maxOf(b.startPeriod, 1)
        val to = minOf(b.endPeriod, totalPeriods)
        if (to < from) return@forEach
        for (p in from..to) slots[p].add(b)
    }

    val cells = mutableListOf<DayCell>()
    var p = 1
    while (p <= totalPeriods) {
        val here = slots[p]
        // 向后延伸：下一节次若「覆盖的**课程**完全相同」就并入本段。
        //
        // ⚠️ 必须比较 `sessionId`，**不能比较 GridBlock 对象本身**。
        // 同一门课可以有多条 session（例如「概预算正课」同时有 3-13 周与 14-15 周两段），
        // 它们是不同的 GridBlock 实例、但用户看到的**是同一门课**。
        // 若用对象集合比较，3-5 节里「3-4 有两段、5 只有一段」就会被判为不同 →
        // 把一门三节连排的课从中间切成两段（用户 2026-09-22 实测的 bug）。
        //
        // 用 sessionId 归一后：
        //  · 单课连排 → 同一个 sessionId → 合并为一段；
        //  · 同名课的多段 → 仍然合并（它们占的是同一门课的连续节次）；
        //  · 不同课部分重叠 → 重叠处集合不同 → 正确切开。
        val hereKey = here.map { it.sessionId }.toSet()
        var q = p
        while (q + 1 <= totalPeriods &&
            slots[q + 1].map { it.sessionId }.toSet() == hereKey
        ) {
            q++
        }
        cells += DayCell(startPeriod = p, endPeriod = q, blocks = here.toList())
        p = q + 1
    }
    return cells
}

/** 一天的格子总长度（节数）。用于单测断言「与内容无关」。 */
internal fun totalCellSpan(cells: List<DayCell>): Int = cells.sumOf { it.span }

// ---------------------------------------------------------------- 导入课表

/** 导入解析出的课程（未落库，ID 在写入时生成）。 */
internal data class ImportedCourse(
    val name: String,
    val teacher: String?,
    val location: String?,
    val colorHex: String?,
    val sessions: List<ImportedSession>,
)

internal data class ImportedSession(
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val startWeek: Int,
    val endWeek: Int,
    val parity: WeekParity,
    val location: String?,
)

/**
 * 解析课表 JSON。两种格式都认：
 * 1. 本应用的完整备份（BackupCodec 格式）；
 * 2. 简化的 `{"courses":[...]}` 结构。
 * 解析失败抛 [IllegalArgumentException]，调用方转成用户可读提示。
 */
internal fun parseCourseJson(text: String, routine: RoutineConfig = RoutineConfig.Default): List<ImportedCourse> {
    val root = runCatching { JSONObject(text) }.getOrNull()
        ?: throw IllegalArgumentException("文件不是有效的 JSON")

    // 完整备份
    if (root.optInt("formatVersion", -1) > 0) {
        val snapshot = BackupCodec.decode(text)
        val byCourse = snapshot.courseSessions.groupBy { it.courseId }
        return snapshot.courses.map { course ->
            ImportedCourse(
                name = course.name,
                teacher = course.teacher,
                location = course.defaultLocation,
                colorHex = course.colorHex,
                sessions = byCourse[course.id].orEmpty().map { session ->
                    ImportedSession(
                        dayOfWeek = session.dayOfWeek,
                        startMinute = session.startMinuteOfDay,
                        endMinute = session.endMinuteOfDay,
                        startWeek = session.startWeek,
                        endWeek = if (session.endWeek == Int.MAX_VALUE) 0 else session.endWeek,
                        parity = session.parity,
                        location = session.location,
                    )
                },
            )
        }
    }

    // 简化结构
    val arr = root.optJSONArray("courses") ?: throw IllegalArgumentException("JSON 里缺少 courses 数组")
    return (0 until arr.length()).mapNotNull { i ->
        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
        val name = obj.optString("name").trim()
        if (name.isEmpty()) return@mapNotNull null
        val sessions = obj.optJSONArray("sessions") ?: JSONArray()
        ImportedCourse(
            name = name,
            teacher = obj.optString("teacher").ifBlank { null },
            location = obj.optString("location").ifBlank { null },
            colorHex = obj.optString("colorHex").ifBlank { null },
            sessions = (0 until sessions.length()).mapNotNull { si ->
                val s = sessions.optJSONObject(si) ?: return@mapNotNull null
                // 两种时间写法：节次（推荐，跟随作息）或绝对分钟（兼容旧文件）。
                // 节次优先；两者都无效时**丢弃该条**（不夹取，避免错误的文件看起来导入成功）。
                val startPeriod = s.optInt("startPeriod", -1).takeIf { it > 0 }
                val endPeriod = s.optInt("endPeriod", -1).takeIf { it > 0 }
                val range = resolveImportRange(
                    config = routine,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    startMinute = s.optInt("startMinute", -1),
                    endMinute = s.optInt("endMinute", -1),
                ) ?: return@mapNotNull null
                ImportedSession(
                    dayOfWeek = s.optInt("dayOfWeek", 1).coerceIn(1, 7),
                    startMinute = range.first,
                    endMinute = range.second,
                    startWeek = s.optInt("startWeek", 1).coerceAtLeast(1),
                    endWeek = s.optInt("endWeek", 0),
                    parity = parseParity(s.optString("parity")),
                    location = s.optString("location").ifBlank { null },
                )
            },
        )
    }
}

/**
 * 解析 CSV。列：
 * `name,teacher,location,colorHex,dayOfWeek,startMinute,endMinute,startWeek,endWeek,parity`
 * 或把 `startMinute,endMinute` 换成 `startPeriod,endPeriod`（节次，推荐）。
 */
internal fun parseCourseCsv(text: String, routine: RoutineConfig = RoutineConfig.Default): List<ImportedCourse> {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) throw IllegalArgumentException("CSV 文件为空")

    val header = lines.first().split(',').map { it.trim().lowercase() }
    val hasHeader = header.contains("name") || header.contains("dayofweek")
    val dataRows = if (hasHeader) lines.drop(1) else lines

    fun cell(row: List<String>, col: String): String {
        val idx = header.indexOf(col.lowercase())
        return if (idx >= 0 && idx < row.size) row[idx].trim() else ""
    }
    fun intCell(row: List<String>, col: String, default: Int): Int =
        cell(row, col).toIntOrNull() ?: default

    // 按「课程名」分组，同名课程合并时段。
    val grouped = LinkedHashMap<String, MutableList<ImportedSession>>()
    val meta = HashMap<String, Triple<String?, String?, String?>>()

    dataRows.forEach { line ->
        val row = line.split(',').map { it.trim() }
        val name = if (hasHeader) cell(row, "name") else row.getOrNull(0).orEmpty()
        if (name.isEmpty()) return@forEach
        // 表头两种写法：节次（startPeriod/endPeriod）或绝对分钟（startMinute/endMinute）。
        val startPeriod = if (hasHeader) intCell(row, "startperiod", -1).takeIf { it > 0 } else null
        val endPeriod = if (hasHeader) intCell(row, "endperiod", -1).takeIf { it > 0 } else null
        val range = resolveImportRange(
            config = routine,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            startMinute = if (hasHeader) intCell(row, "startminute", -1) else row.getOrNull(5)?.toIntOrNull() ?: -1,
            endMinute = if (hasHeader) intCell(row, "endminute", -1) else row.getOrNull(6)?.toIntOrNull() ?: -1,
        ) ?: return@forEach
        val day = (if (hasHeader) intCell(row, "dayofweek", 1) else row.getOrNull(4)?.toIntOrNull() ?: 1).coerceIn(1, 7)

        grouped.getOrPut(name) { mutableListOf() }.add(
            ImportedSession(
                dayOfWeek = day,
                startMinute = range.first,
                endMinute = range.second,
                startWeek = if (hasHeader) intCell(row, "startweek", 1) else row.getOrNull(7)?.toIntOrNull() ?: 1,
                endWeek = if (hasHeader) intCell(row, "endweek", 0) else row.getOrNull(8)?.toIntOrNull() ?: 0,
                parity = parseParity(if (hasHeader) cell(row, "parity") else row.getOrNull(9).orEmpty()),
                location = (if (hasHeader) cell(row, "location") else row.getOrNull(2).orEmpty()).ifBlank { null },
            ),
        )
        meta.getOrPut(name) {
            Triple(
                (if (hasHeader) cell(row, "teacher") else row.getOrNull(1).orEmpty()).ifBlank { null },
                (if (hasHeader) cell(row, "location") else row.getOrNull(2).orEmpty()).ifBlank { null },
                (if (hasHeader) cell(row, "colorhex") else row.getOrNull(3).orEmpty()).ifBlank { null },
            )
        }
    }

    if (grouped.isEmpty()) throw IllegalArgumentException("CSV 里没有可识别的课程行")
    return grouped.map { (name, sessions) ->
        val m = meta[name] ?: Triple(null, null, null)
        ImportedCourse(name, m.first, m.second, m.third, sessions)
    }
}

private fun parseParity(raw: String): WeekParity = when (raw.trim().uppercase()) {
    "ODD", "单周" -> WeekParity.ODD
    "EVEN", "双周" -> WeekParity.EVEN
    else -> WeekParity.ALL
}
