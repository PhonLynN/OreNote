package com.phonlynn.oreplan.platform.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 今日小组件。
 *
 * onUpdate 里用 `goAsync` 争一点时间读数据库 —— BroadcastReceiver 的生命周期很短，
 * 不 goAsync 会在数据读出来之前就被系统回收，小组件就一直是空的。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetEntryPoint.resolveUpdater(appContext).refreshOnce()
            } catch (_: Throwable) {
                // 小组件刷新失败不该影响任何东西：下一次 onUpdate 会再试
            } finally {
                pendingResult.finish()
            }
        }
    }
}
