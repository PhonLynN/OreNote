package com.phonlynn.oreplan.platform.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.phonlynn.oreplan.MainActivity
import com.phonlynn.oreplan.R
import com.phonlynn.oreplan.domain.model.Agenda
import com.phonlynn.oreplan.domain.model.AgendaEntry
import com.phonlynn.oreplan.domain.usecase.BuildAgendaUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** AppWidgetProvider 由系统实例化、无法直接注入，所以走 Hilt EntryPoint 取依赖。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {

    fun buildAgenda(): BuildAgendaUseCase

    fun widgetUpdater(): WidgetUpdater

    companion object {
        private fun access(context: Context): WidgetEntryPoint =
            EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)

        fun resolveUpdater(context: Context): WidgetUpdater = access(context).widgetUpdater()

        fun resolveAgenda(context: Context): BuildAgendaUseCase = access(context).buildAgenda()
    }
}

/**
 * 小组件刷新。
 *
 * 观察数据并在变化时刷新，而不是只靠 `updatePeriodMillis`：后者最短也要 30 分钟，
 * 用户改完一条日程回桌面看到还是旧的，会以为没保存。
 *
 * 用 RemoteViews 而不是 Glance：本项目的 Kotlin 被 AGP 锁在 2.2.x，Glance 属于新依赖，
 * 版本兼容需要额外验证；而小组件只显示四行文本，RemoteViews 完全够用，且零新增依赖。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buildAgenda: BuildAgendaUseCase,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 小组件一律按系统时区的「今天」取值，不要混用不带时区的 `LocalDate.now()`。 */
    private val zone: ZoneId = ZoneId.systemDefault()

    fun start() {
        scope.launch {
            // 「今天是哪天」本身也必须是流，而且议程区间要跟着它重新订阅。
            // 原先区间在 start() 里用 `LocalDate.now()` 定死，之后每小时只重发一个 Unit，
            // 跨过零点后 `timedOn(新的今天)` 在旧区间的议程里必然为空 ——
            // 表现是小组件从此一直显示「没有安排」，直到进程重启。
            todayFlow()
                .flatMapLatest { today ->
                    buildAgenda.observe(today, today, zone).map { today to it }
                }
                .collect { (today, agenda) ->
                    render(today, agenda)
                }
        }
    }

    /** 每天刚过零点发一次「今天」。不在零点前后醒着的话，也就是下次刷新时顺带纠正。 */
    private fun todayFlow(): Flow<LocalDate> = flow {
        while (true) {
            val now = ZonedDateTime.now(zone)
            emit(now.toLocalDate())
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
        }
    }.distinctUntilChanged()

    /** 一次性刷新。系统触发 onUpdate 时走这里。 */
    suspend fun refreshOnce() {
        val today = LocalDate.now(zone)
        render(today, buildAgenda.build(today, today, zone))
    }

    private fun render(today: LocalDate, entries: Agenda) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = buildViews(today, entries.timedOn(today) + entries.untimedOn(today))
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    private fun buildViews(today: LocalDate, entries: List<AgendaEntry>) = RemoteViews(
        context.packageName,
        R.layout.widget_today,
    ).apply {
        setTextViewText(R.id.widget_title, "今日")
        setTextViewText(
            R.id.widget_subtitle,
            "${today.monthValue}月${today.dayOfMonth}日 · " +
                if (entries.isEmpty()) "没有安排" else "${entries.size} 项",
        )

        removeAllViews(R.id.widget_rows)
        entries.take(MAX_ROWS).forEach { entry ->
            addView(
                R.id.widget_rows,
                RemoteViews(context.packageName, R.layout.widget_row).apply {
                    setTextViewText(R.id.row_time, timeLabelOf(entry))
                    setTextViewText(R.id.row_title, entry.title)
                },
            )
        }

        val overflow = entries.size - MAX_ROWS
        if (overflow > 0) {
            setViewVisibility(R.id.widget_more, View.VISIBLE)
            setTextViewText(R.id.widget_more, "还有 $overflow 项")
        } else {
            setViewVisibility(R.id.widget_more, View.GONE)
        }

        setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    /**
     * 时间列的文案。
     *
     * 没有具体时刻的条目一律是待办（全天日程已在数据迁移里转成待办，
     * 见 `AppDatabase` 的 v2→v3 迁移），所以这里标「待办」而不是「全天」——
     * 后者会让用户以为这是一件占一整天的事。
     */
    private fun timeLabelOf(entry: AgendaEntry): String {
        val start = entry.startMinute ?: return "待办"
        val end = entry.endMinute ?: (start + 30)
        return "${clock(start)}–${clock(end)}"
    }

    private fun clock(minute: Int): String =
        "%02d:%02d".format((minute / 60) % 24, minute % 60)

    private companion object {
        const val MAX_ROWS = 4
    }
}
