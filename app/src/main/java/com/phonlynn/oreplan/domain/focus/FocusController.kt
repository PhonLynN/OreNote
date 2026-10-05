package com.phonlynn.oreplan.domain.focus

import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.FocusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 专注计时控制器 —— **全局单例**。
 *
 * ## 为什么必须是单例（而不是某个页面的 ViewModel）
 *
 * 设计稿里同一次专注要出现在**三个地方**：专注中页（大环）、规划页的悬浮窗，
 * 以及「返回规划页后还能点回去」的入口。如果计时状态挂在某个页面的 ViewModel 上，
 * 一离开那个页面状态就没了 —— 用户从专注页返回规划页，专注就断了。
 *
 * ## 计时为什么不落库
 *
 * 见 [ActiveFocus] 的注释：只在结束时写一条记录。中间态留在内存，
 * 进程被杀就从头开始 —— 这比留下一条「永远停在 12:30」的脏记录要好。
 *
 * ## 为什么按墙钟算而不是累加 tick
 *
 * [tick] 只是「**请求重绘**」的信号，真正的时间永远用
 * `startedAt` / `accumulatedSeconds` 现算（[ActiveFocus.elapsedSeconds]）。
 * 这样应用被挂到后台、系统把协程冻住再唤醒，时间仍然是对的 ——
 * 累加 tick 的写法在后台会漏掉几千次 tick，回来就少了十几分钟。
 *
 * ## 倒计时自动结束放在这里，而不是放在页面里
 *
 * 用户可能在规划首页看着悬浮窗等倒计时走完；如果「到点结束」只写在专注页里，
 * 那个页面前台化之前计时永远不会结束。控制器的 [engine] 循环负责这件事。
 */
@Singleton
class FocusController @Inject constructor(
    private val focusRepository: FocusRepository,
    private val extRepository: EntityExtRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow<ActiveFocus?>(null)
    val active: StateFlow<ActiveFocus?> = _active.asStateFlow()

    private val _result = MutableStateFlow<FocusResult?>(null)

    /** 结束但**还没保存**的那次专注（「专注完成」页读它）。 */
    val result: StateFlow<FocusResult?> = _result.asStateFlow()

    private val _events = MutableSharedFlow<FocusEvent>(extraBufferCapacity = 8)

    /** 番茄钟换段之类的一次性提示，由界面弹 snackbar。 */
    val events: Flow<FocusEvent> = _events.asSharedFlow()

    /**
     * 心跳：只在有活动专注、且没暂停时每 250ms 发一次。
     * 界面接它来触发重绘 —— **不要用 tick 的数值算时间**。
     */
    val tick: Flow<Long> = flow {
        while (true) {
            val now = _active.value
            if (now != null && !now.isPaused) emit(System.currentTimeMillis())
            delay(TICK_MILLIS)
        }
    }

    init {
        scope.launch { engine() }
    }

    /**
     * 引擎：把「到点」这件事集中在一处判断。
     * - 倒计时走完 → 自动 [finish]（用户随后看到「专注完成」页）；
     * - 番茄钟一段走完 → 发一个 [FocusEvent] 提示休息，并进入下一段（不结束）。
     */
    private suspend fun engine() {
        while (true) {
            delay(ENGINE_MILLIS)
            val current = _active.value ?: continue
            if (current.isPaused) continue
            val now = Instant.now()
            if (!current.isElapsed(now)) continue
            when (current.mode) {
                FocusMode.COUNT_DOWN -> finish(now)
                FocusMode.POMODORO -> {
                    _events.tryEmit(FocusEvent.PomodoroCycleDone(current.cycle))
                    nextPomodoroCycle(now)
                }
                FocusMode.COUNT_UP -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- 操作

    /** 开始一次专注。已经在专注中时**覆盖**（设计稿的「开始专注」是单入口）。 */
    fun start(
        target: FocusTarget,
        mode: FocusMode,
        plannedMinutes: Int?,
        now: Instant = Instant.now(),
    ) {
        _result.value = null
        _active.value = ActiveFocus(
            target = target,
            mode = mode,
            plannedMinutes = if (mode.hasPlan) (plannedMinutes ?: DEFAULT_MINUTES) else null,
            startedAt = now,
            accumulatedSeconds = 0,
            segmentStartedAt = now,
            cycleStartedAt = now,
            cycle = 1,
        )
    }

    /** 暂停：把当前这一段结算进 [ActiveFocus.accumulatedSeconds]，并停住本段锚点。 */
    fun pause(now: Instant = Instant.now()) {
        val current = _active.value ?: return
        if (current.isPaused) return
        _active.value = current.copy(
            accumulatedSeconds = current.elapsedSeconds(now),
            cycleStartedAt = now,
            segmentStartedAt = null,
        )
    }

    /** 继续。 */
    fun resume(now: Instant = Instant.now()) {
        val current = _active.value ?: return
        if (!current.isPaused) return
        _active.value = current.copy(
            cycleStartedAt = now,
            segmentStartedAt = now,
        )
    }

    fun togglePause(now: Instant = Instant.now()) {
        if (_active.value?.isPaused == true) resume(now) else pause(now)
    }

    /** 忘记停止（正计时专用）：补记一分钟。 */
    fun addMinute(now: Instant = Instant.now()) {
        val current = _active.value ?: return
        _active.value = current.copy(
            accumulatedSeconds = current.elapsedSeconds(now) + 60,
            cycleStartedAt = now,
            segmentStartedAt = if (current.isPaused) null else now,
        )
    }

    /** 番茄钟：一段走完，进入下一段。 */
    fun nextPomodoroCycle(now: Instant = Instant.now()) {
        val current = _active.value ?: return
        if (current.mode != FocusMode.POMODORO) return
        _active.value = current.copy(
            // 总时长到目前为止压进累计，本段锚点挪到此刻、段号 +1。
            // 不能写成 `elapsedSeconds(now)`：那会把**当前这一段**也算进累计，
            // 而新段的锚点又设成 now，那段时间就被计两次（每段多算整整一个周期）。
            accumulatedSeconds = current.elapsedSeconds(now),
            cycleStartedAt = now,
            segmentStartedAt = if (current.isPaused) null else now,
            cycle = current.cycle + 1,
        )
    }

    /**
     * 结束专注。**不会落库** —— 交给用户在「专注完成」页按保存。
     * 中途结束记 `completed=false`，走完计划时长记 `true`。
     */
    fun finish(now: Instant = Instant.now()) {
        val current = _active.value ?: return
        _result.value = FocusResult(
            target = current.target,
            mode = current.mode,
            plannedMinutes = current.plannedMinutes,
            elapsedSeconds = current.elapsedSeconds(now),
            startedAt = current.startedAt,
            endedAt = now,
        )
        _active.value = null
    }

    /** 「专注完成」页按了保存：写这条记录。返回是否保存成功。 */
    suspend fun saveResult(): Boolean {
        val result = _result.value ?: return false
        focusRepository.save(newSession(result))
        _result.value = null
        return true
    }

    /** 放弃这份结果（退出「专注完成」页且不保存）。 */
    fun discardResult() {
        _result.value = null
    }

    // ---------------------------------------------------------------- 读

    /**
     * 某条记录是不是计时器产生的。
     * 抽屉里没写就按「是」处理 —— 0.3.0 之前没有别的入口。
     */
    suspend fun sourceOf(sessionId: String): FocusSource {
        val map = extRepository.get(ExtOwner.FOCUS_SESSION, sessionId)
        return FocusSource.fromKey(map.text(KEY_SOURCE))
    }

    private suspend fun newSession(result: FocusResult): FocusSession {
        val session = FocusSession(
            id = Ids.newId(),
            startedAt = result.startedAt,
            endedAt = result.recordedEndAt,
            minutes = result.minutes,
            kind = FocusKind.fromKey(result.mode.key),
            plannedMinutes = result.plannedMinutes,
            completed = result.reachedPlan,
            label = result.target.titleOrNull,
            itemId = result.target.itemIdOrNull,
            createdAt = Instant.now(),
        )
        extRepository.update(ExtOwner.FOCUS_SESSION, session.id) {
            it.putText(KEY_SOURCE, FocusSource.TIMER.key)
        }
        return session
    }

    /** 一次性事件。 */
    sealed interface FocusEvent {
        /** 番茄钟第 [cycle] 段走完了，该休息一下。 */
        data class PomodoroCycleDone(val cycle: Int) : FocusEvent
    }

    private companion object {
        const val TICK_MILLIS = 250L
        const val ENGINE_MILLIS = 500L
        const val DEFAULT_MINUTES = 25
        const val KEY_SOURCE = "plan.source"
    }
}
