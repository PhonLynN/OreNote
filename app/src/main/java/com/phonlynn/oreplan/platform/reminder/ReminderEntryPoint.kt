package com.phonlynn.oreplan.platform.reminder

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 让 BroadcastReceiver 拿到依赖注入的协调器。
 *
 * Receiver 由系统实例化，不能直接 `@Inject`，所以走 Hilt 的 EntryPoint ——
 * 这比在 Receiver 里手写一个静态单例要干净，也不会绕过 DI 图。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {

    fun reminderCoordinator(): ReminderCoordinator

    companion object {
        fun resolve(context: Context): ReminderCoordinator =
            EntryPointAccessors
                .fromApplication(context, ReminderEntryPoint::class.java)
                .reminderCoordinator()
    }
}
