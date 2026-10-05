package com.phonlynn.oreplan.domain.sync

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备身份 + 本机 HLC 时钟状态。
 *
 * ## 为什么设备 id 必须持久
 *
 * HLC 的 tie-break 和"最后写入者"都依赖设备 id。
 * 若每次启动都随机生成，同一台设备的历史事件会散落在多个 id 上，
 * 冲突解决就失去了稳定依据。所以它存在 `app_meta` 里，只在首次生成。
 *
 * ## 时钟状态为什么要放内存 + 允许回退到 0
 *
 * HLC 的本质要求是"本机产生的时间戳单调递增"。进程重启后从 0 开始是**安全**的：
 * 因为 `tick()` 会取 `max(now, last.physical)`，而 `now` 是真实墙钟，
 * 新事件的物理时间必然大于上次运行时的值（除非墙钟被调回过去，
 * 那种情况下 [observe] 会在与远端交互时把时钟推回正确位置）。
 *
 * 这个类**不碰数据库** —— 持久化由 [SyncSettings] 负责，
 * 保持它可被单元测试直接构造（`SyncClock(deviceId = "test")`）。
 */
@Singleton
class SyncClock @Inject constructor() {

    /** 本机设备 id。由 DI 注入时替换为持久化的值。 */
    var deviceId: String = UUID.randomUUID().toString()
        private set
    private var last: Hlc = Hlc.ZERO

    /** 当前墙钟（毫秒）。抽成方法便于测试注入固定时间。 */
    private var nowProvider: () -> Long = { System.currentTimeMillis() }

    /** 测试用：注入固定时钟。 */
    internal fun setNowProvider(provider: () -> Long) {
        nowProvider = provider
    }

    /** 测试/启动用：恢复持久化的设备 id。 */
    fun restore(deviceId: String) {
        if (deviceId.isNotBlank()) this.deviceId = deviceId
    }

    fun now(): Long = nowProvider()

    /** 本机发生一次事件，返回新的逻辑时钟。 */
    fun tick(): Hlc {
        last = Hlc.tick(nowProvider(), last, deviceId)
        return last
    }

    /** 见到一个远端时钟后，把本机时钟推到不小于它。 */
    fun observe(remote: Hlc?) {
        if (remote == null) return
        last = Hlc.merge(nowProvider(), last, remote, deviceId)
    }

    /** 只读快照（诊断/显示用）。 */
    fun peek(): Hlc = last
}
