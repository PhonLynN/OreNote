package com.phonlynn.oreplan.data.settings

import com.phonlynn.oreplan.data.local.dao.AppMetaDao
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import com.phonlynn.oreplan.domain.routine.BigBreak
import com.phonlynn.oreplan.domain.routine.RoutineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 应用设置（作息、显示、提醒、白板暗号）。 */
data class AppSettings(
    val routine: RoutineConfig = RoutineConfig.Default,
    /** 课表是否显示周末列/周末安排。 */
    val showWeekend: Boolean = true,
    /** 是否显示不在当前周的课程（灰显）。 */
    val showNonCurrentWeekCourses: Boolean = true,
    /** 课表网格里是否显示节次时间。 */
    val showPeriodTimes: Boolean = true,
    val remindersEnabled: Boolean = true,
    /** 默认提前提醒（分钟）。 */
    val reminderLeadMinutes: Int = 15,
    /** 全天事件提醒时间（HH:mm）。 */
    val allDayReminderTime: String = "09:00",
    /** 新日程默认时长（分钟）。 */
    val defaultEventMinutes: Int = 60,
    /** 日程页的显示记忆：上次退出时的视图、模式、选中日期（用户 2026-09-24）。 */
    val calendarView: String = "Month",
    val calendarMode: String = "Schedule",
    /** ISO 日期（yyyy-MM-dd）；null 表示用今天。 */
    val calendarDate: String? = null,
    // 「白板暗号 boardPasscode」已于 2026-09-30 删除（历史遗留的死设置）：
    // 它曾被当成"保密卡片的查看密码"，但保密卡片从来不是查看门槛 ——
    // 所谓"暗号"是**写在卡片上的暗示文本**，即 BoardCard.secretHint（卡片自身的字段）。
    // 全项目没有任何地方消费过 boardPasscode，设置页却写着"可输入暗号查看内容"（假承诺）。
    // 老用户存过的 JSON 值会被这里的解析逻辑忽略（读不到就丢），无需迁移。
    // ---- 白板设置（38 号设计稿）----
    /** 新建卡片的默认类型键（QUICK/TODO/QUOTE/GOAL）。 */
    val boardDefaultType: String = "QUICK",
    /** 新建卡片插到列表顶部还是底部。 */
    val boardNewCardPosition: String = "TOP",
    /** 新建卡片默认是否显示创建日期。 */
    val boardShowCreatedTime: Boolean = true,
    /** 放弃编辑时是否把未写完的新卡片暂存为草稿（下次新建自动回填）。 */
    val boardAutoDraft: Boolean = false,
    /** 新建卡片默认宽度：auto / half / full。 */
    val boardDefaultWidth: String = "auto",
    /**
     * 新建卡片默认图片样式：
     * auto（自动：单图填充 / 多图网格）/ fill（横向填充）/ grid（缩略网格）。
     */
    val boardDefaultImageLayout: String = "auto",
    /** 紧凑模式（卡片内边距与字号收紧）。 */
    val boardCompact: Boolean = false,
    /**
     * 卡片**标题与正文**的字号档：small / medium / large。
     *
     * 只影响卡片标题与正文（用户 2026-09-19 明确范围），不动日期/标签/待办等。
     * 这里存档位名而不是具体 sp：具体字号在 UI 层集中定义，便于以后调档。
     */
    val boardCardFontSize: String = "medium",
    /**
     * 卡片间距：normal（默认）/ compact（更紧凑）。
     *
     * 同时控制三处：卡片之间的列间距、行间距，以及卡片与屏幕左右边缘的间隙。
     * 三者必须**一起**变，否则列宽会与实际留白不一致（布局按（总宽−列间距）/2 算列宽）。
     * 这个值会同时交给布局（MasonryBoard）与拖放落点模拟（MasonryDrop），
     * 保证两边口径一致。
     */
    val boardSpacing: String = "normal",
    /**
     * 进入卡片聚焦后的展示形态：
     *  - card（默认）：从卡片墙原位置飞到屏幕中间，保留卡片底色/圆角/阴影；
     *  - fullscreen：淡入一个全屏窗口，**不用卡片底色**（就是页面背景）、无圆角/阴影，
     *    顶部栏放一个与背景同色的圆点作为颜色标记。
     */
    val boardFocusStyle: String = "card",
    /** 新建卡片入口：fab（右下悬浮）/ header（标题栏）。 */
    val boardNewCardEntry: String = "fab",
    /** 双击卡片进入编辑。 */
    val boardDoubleTapEdit: Boolean = true,
    /** 长按拖动调整卡片顺序。 */
    val boardDragReorder: Boolean = true,
    /** 完成待办后移至清单末尾。 */
    val boardDoneToEnd: Boolean = true,
    /**
     * 搜索多关键词的匹配方式。
     * true = 交集（每个词都要命中，AND）；false = 并集（任一命中即可，OR）。
     */
    val boardSearchMatchAll: Boolean = true,
    /** 卡片是否带随机轻微的倾斜（关闭后卡片严格水平摆放）。 */
    val boardCardTilt: Boolean = true,
    /** 新建卡片是否随机分配一个预设颜色（关闭后默认无底色）。 */
    val boardRandomColor: Boolean = true,
    /**
     * 新建卡片时是否**自动打开全屏文本编辑浮层**（用户 2026-09-19）。
     * 开启：进入编辑页后立即展开全屏文本编辑，直接开始写；
     * 关闭（默认）：与原先一致，停在普通编辑页。
     */
    val boardNewCardFullscreen: Boolean = false,
    /**
     * 「卡片默认宽度 = 自动」时，判定为整行卡片的字数阈值。
     * 标题 + 正文的总字数（不含空白）达到该值即视为宽卡。
     */
    val boardAutoWidthChars: Int = 24,
)

/**
 * 设置存储。落库在 app_meta 的一张键值行上（JSON），进程内用 StateFlow 缓存 ——
 * 单进程应用，没有第二个写入方，缓存与库不会分歧。
 */
@Singleton
class AppSettingsStore @Inject constructor(
    private val appMetaDao: AppMetaDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(AppSettings())
    private var loaded = false

    /**
     * 设置是否**已从库里读回真实值**。
     *
     * 为什么需要它：`settings` 的初值是全部默认值，而读库是异步的。
     * 首次进应用时直接读 `settings.value` 拿到的是**默认值**而不是用户设置。
     *
     * 典型坑（就是「首进先显卡片编辑页再跳全屏」的根源）：
     * 「使用全屏视图新建」的默认值是 `false`，而真实值是 `true`；
     * 首次进页时读到了默认的 `false` → 判定为不进全屏 → 先显示编辑页，
     * 之后设置读完才又变成全屏。第二次进应用时设置早已读完，所以正常。
     *
     * 需要可靠读到用户设置的地方，必须 `awaitLoaded()` 后再读。
     */
    private val loadedFlow = MutableStateFlow(false)

    /** 挂起直到真实设置已从库读回（已加载则立即返回）。 */
    suspend fun awaitLoaded() {
        ensureLoaded()
        loadedFlow.first { it }
    }

    /** 真实设置是否已从库读回。供需要**同步**判断的调用方做前置检查。 */
    val isLoaded: Boolean get() = loadedFlow.value

    val settings: StateFlow<AppSettings> get() {
        ensureLoaded()
        return state
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scope.launch {
            runCatching {
                val raw = appMetaDao.find(KEY)?.value
                if (!raw.isNullOrBlank()) state.value = decode(raw)
            }
            // 无论成功失败都要放行等待方，否则会永久挂起。
            loadedFlow.value = true
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        state.value = next
        runCatching { appMetaDao.upsert(AppMetaEntity(KEY, encode(next))) }
    }

    // ---------------------------------------------------------------- 编解码

    private fun encode(s: AppSettings): String {
        val routine = JSONObject().apply {
            put("startMinute", s.routine.startMinute)
            put("periodMinutes", s.routine.periodMinutes)
            put("smallBreakMinutes", s.routine.smallBreakMinutes)
            put("lunchBreakMinutes", s.routine.lunchBreakMinutes)
            put("dinnerBreakMinutes", s.routine.dinnerBreakMinutes)
            put("morningCount", s.routine.morningCount)
            put("afternoonCount", s.routine.afternoonCount)
            put("eveningCount", s.routine.eveningCount)
            put(
                "bigBreaks",
                JSONArray().apply {
                    s.routine.bigBreaks.forEach { b ->
                        put(JSONObject().apply { put("afterPeriod", b.afterPeriod); put("minutes", b.minutes) })
                    }
                },
            )
        }
        return JSONObject().apply {
            put("routine", routine)
            put("showWeekend", s.showWeekend)
            put("showNonCurrentWeekCourses", s.showNonCurrentWeekCourses)
            put("showPeriodTimes", s.showPeriodTimes)
            put("remindersEnabled", s.remindersEnabled)
            put("reminderLeadMinutes", s.reminderLeadMinutes)
            put("allDayReminderTime", s.allDayReminderTime)
            put("defaultEventMinutes", s.defaultEventMinutes)
            put("boardDefaultType", s.boardDefaultType)
            put("boardNewCardPosition", s.boardNewCardPosition)
            put("boardShowCreatedTime", s.boardShowCreatedTime)
            put("boardAutoDraft", s.boardAutoDraft)
            put("boardDefaultWidth", s.boardDefaultWidth)
            put("boardDefaultImageLayout", s.boardDefaultImageLayout)
            put("boardCompact", s.boardCompact)
            put("boardCardFontSize", s.boardCardFontSize)
            put("boardSpacing", s.boardSpacing)
            put("boardFocusStyle", s.boardFocusStyle)
            put("boardNewCardEntry", s.boardNewCardEntry)
            put("boardDoubleTapEdit", s.boardDoubleTapEdit)
            put("boardDragReorder", s.boardDragReorder)
            put("boardDoneToEnd", s.boardDoneToEnd)
            put("boardSearchMatchAll", s.boardSearchMatchAll)
            put("boardCardTilt", s.boardCardTilt)
            put("boardRandomColor", s.boardRandomColor)
            put("boardNewCardFullscreen", s.boardNewCardFullscreen)
            put("boardAutoWidthChars", s.boardAutoWidthChars)
        }.toString()
    }

    private fun decode(raw: String): AppSettings {
        val root = runCatching { JSONObject(raw) }.getOrElse { return AppSettings() }
        val routineObj = root.optJSONObject("routine")
        val routine = if (routineObj == null) {
            RoutineConfig.Default
        } else {
            val breaks = mutableListOf<BigBreak>()
            routineObj.optJSONArray("bigBreaks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    breaks += BigBreak(o.optInt("afterPeriod", 2), o.optInt("minutes", 15))
                }
            }
            RoutineConfig(
                startMinute = routineObj.optInt("startMinute", 8 * 60),
                periodMinutes = routineObj.optInt("periodMinutes", 45),
                smallBreakMinutes = routineObj.optInt("smallBreakMinutes", 5),
                lunchBreakMinutes = routineObj.optInt("lunchBreakMinutes", 75),
                dinnerBreakMinutes = routineObj.optInt("dinnerBreakMinutes", 45),
                morningCount = routineObj.optInt("morningCount", 5),
                afternoonCount = routineObj.optInt("afternoonCount", 5),
                eveningCount = routineObj.optInt("eveningCount", 4),
                bigBreaks = breaks.ifEmpty { RoutineConfig.DEFAULT_BIG_BREAKS },
            )
        }
        // 老 JSON 里可能还有 "boardPasscode"（已删除的死设置）—— 有意不读，读到即弃。
        return AppSettings(
            routine = routine,
            showWeekend = root.optBoolean("showWeekend", true),
            showNonCurrentWeekCourses = root.optBoolean("showNonCurrentWeekCourses", true),
            showPeriodTimes = root.optBoolean("showPeriodTimes", true),
            remindersEnabled = root.optBoolean("remindersEnabled", true),
            reminderLeadMinutes = root.optInt("reminderLeadMinutes", 15),
            allDayReminderTime = root.optString("allDayReminderTime", "09:00"),
            defaultEventMinutes = root.optInt("defaultEventMinutes", 60),
            boardDefaultType = root.optString("boardDefaultType", "QUICK"),
            boardNewCardPosition = root.optString("boardNewCardPosition", "TOP"),
            boardShowCreatedTime = root.optBoolean("boardShowCreatedTime", true),
            boardAutoDraft = root.optBoolean("boardAutoDraft", false),
            boardDefaultWidth = root.optString("boardDefaultWidth", "auto"),
            boardDefaultImageLayout = root.optString("boardDefaultImageLayout", "auto"),
            boardCompact = root.optBoolean("boardCompact", false),
            boardCardFontSize = root.optString("boardCardFontSize", "medium"),
            boardSpacing = root.optString("boardSpacing", "normal"),
            boardFocusStyle = root.optString("boardFocusStyle", "card"),
            boardNewCardEntry = root.optString("boardNewCardEntry", "fab"),
            boardDoubleTapEdit = root.optBoolean("boardDoubleTapEdit", true),
            boardDragReorder = root.optBoolean("boardDragReorder", true),
            boardDoneToEnd = root.optBoolean("boardDoneToEnd", true),
            boardSearchMatchAll = root.optBoolean("boardSearchMatchAll", true),
            boardCardTilt = root.optBoolean("boardCardTilt", true),
            boardRandomColor = root.optBoolean("boardRandomColor", true),
            boardNewCardFullscreen = root.optBoolean("boardNewCardFullscreen", false),
            boardAutoWidthChars = root.optInt("boardAutoWidthChars", 24),
        )
    }

    private companion object {
        const val KEY = "app_settings_v2"
    }
}
