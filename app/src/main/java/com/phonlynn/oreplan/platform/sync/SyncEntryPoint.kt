package com.phonlynn.oreplan.platform.sync

import android.content.Context
import com.phonlynn.oreplan.domain.sync.SyncRunner
import com.phonlynn.oreplan.domain.sync.SyncSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 让 BroadcastReceiver / 非 Compose 代码拿到同步依赖。
 *
 * 与 `ReminderEntryPoint` 同一套写法：Receiver 由系统实例化，不能直接 `@Inject`，
 * 所以走 Hilt 的 EntryPoint，而不是在 Receiver 里手写静态单例（那会绕过 DI 图）。
 *
 * `SyncAlarmReceiver` 本身是 `@AndroidEntryPoint`，可以直接注入；
 * 这个 EntryPoint 是给 `BootReceiver` 用的（它属提醒模块，不该为了同步
 * 去引同步的注入点）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {

    fun syncRunner(): SyncRunner

    fun syncSettings(): SyncSettings

    companion object {
        fun resolve(context: Context): SyncEntryPoint =
            EntryPointAccessors.fromApplication(context, SyncEntryPoint::class.java)
    }
}
