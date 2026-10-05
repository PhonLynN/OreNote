package com.phonlynn.oreplan.domain.focus

import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 计时状态机。
 *
 * 这些用例全部用**显式传入的 now** 驱动（[ActiveFocus.elapsedSeconds] 接受时间参数），
 * 不读系统时钟 —— 否则测试会在「午夜整点」或「慢机器」上随机失败。
 */
class ActiveFocusTest {

    private val t0: Instant = Instant.parse("2025-05-14T09:00:00Z")

    private fun active(mode: FocusMode, planned: Int? = 25, paused: Boolean = false) = ActiveFocus(
        target = FocusTarget.None,
        mode = mode,
        plannedMinutes = planned,
        startedAt = t0,
        accumulatedSeconds = 0,
        segmentStartedAt = if (paused) null else t0,
        cycleStartedAt = t0,
        cycle = 1,
    )

    @Test
    fun `正计时按墙钟给出已用秒数`() {
        val state = active(FocusMode.COUNT_UP, planned = null)
        assertEquals(0, state.elapsedSeconds(t0))
        assertEquals(90, state.elapsedSeconds(t0.plusSeconds(90)))
    }

    @Test
    fun `暂停期间时间不再增长`() {
        var state = active(FocusMode.COUNT_UP, planned = null)
        // 跑了 60 秒后暂停（与 FocusController.pause 同一套写法）
        val pauseAt = t0.plusSeconds(60)
        state = state.copy(
            accumulatedSeconds = state.elapsedSeconds(pauseAt),
            cycleStartedAt = pauseAt,
            segmentStartedAt = null,
        )
        assertTrue(state.isPaused)
        assertEquals(60, state.elapsedSeconds(pauseAt))
        // 暂停 5 分钟后再看，仍然是 60
        assertEquals(60, state.elapsedSeconds(pauseAt.plusSeconds(300)))
        // 继续后从 60 接着走（与 FocusController.resume 同一套写法）
        val resumeAt = pauseAt.plusSeconds(300)
        state = state.copy(cycleStartedAt = resumeAt, segmentStartedAt = resumeAt)
        assertEquals(90, state.elapsedSeconds(resumeAt.plusSeconds(30)))
    }

    @Test
    fun `倒计时剩余秒数钳到零`() {
        val state = active(FocusMode.COUNT_DOWN, planned = 25)
        assertEquals(25 * 60L, state.remainingSeconds(t0))
        assertEquals(60L, state.remainingSeconds(t0.plusSeconds(24 * 60)))
        // 走过头也不给负数
        assertEquals(0L, state.remainingSeconds(t0.plusSeconds(30 * 60)))
    }

    @Test
    fun `正计时没有剩余时间也没有到点`() {
        val state = active(FocusMode.COUNT_UP, planned = null)
        assertNull(state.remainingSeconds(t0.plusSeconds(10_000)))
        assertFalse(state.isElapsed(t0.plusSeconds(10_000)))
    }

    @Test
    fun `到点判定只在倒计时与番茄钟生效`() {
        val countdown = active(FocusMode.COUNT_DOWN, planned = 1)
        assertFalse(countdown.isElapsed(t0.plusSeconds(59)))
        assertTrue(countdown.isElapsed(t0.plusSeconds(60)))

        val pomodoro = active(FocusMode.POMODORO, planned = 1)
        assertTrue(pomodoro.isElapsed(t0.plusSeconds(61)))
    }

    @Test
    fun `环形进度在一圈内`() {
        val state = active(FocusMode.COUNT_DOWN, planned = 1)
        assertEquals(0f, state.progress(t0), 0.001f)
        assertEquals(0.5f, state.progress(t0.plusSeconds(30)), 0.001f)
        assertEquals(1f, state.progress(t0.plusSeconds(60)), 0.001f)
        // 走过头不会超过 1（环不能绕回去）
        assertEquals(1f, state.progress(t0.plusSeconds(120)), 0.001f)
    }

    @Test
    fun `正计时的环按一分钟一圈循环`() {
        val state = active(FocusMode.COUNT_UP, planned = null)
        assertEquals(0f, state.progress(t0), 0.001f)
        assertEquals(0.5f, state.progress(t0.plusSeconds(30)), 0.001f)
        // 61 秒落在第二圈，环走到第 1 秒的位置（不是继续涨到 1.02 圈）
        assertEquals(1f / 60f, state.progress(t0.plusSeconds(61)), 0.001f)
    }

    @Test
    fun `模式决定了要不要计划时长`() {
        assertTrue(FocusMode.COUNT_DOWN.hasPlan)
        assertTrue(FocusMode.POMODORO.hasPlan)
        assertFalse(FocusMode.COUNT_UP.hasPlan)
        assertTrue(FocusMode.COUNT_DOWN.autoFinish)
        assertFalse(FocusMode.POMODORO.autoFinish)
    }

    @Test
    fun `结果把不足一分钟按一分钟算`() {
        val result = FocusResult(
            target = FocusTarget.None,
            mode = FocusMode.COUNT_UP,
            plannedMinutes = null,
            elapsedSeconds = 20,
            startedAt = t0,
            endedAt = t0.plusSeconds(20),
        )
        assertEquals(1, result.minutes)
        assertTrue(result.reachedPlan)
        assertNull(result.overMinutes)
    }

    @Test
    fun `结果给出超出与提前`() {
        val over = FocusResult(
            target = FocusTarget.None,
            mode = FocusMode.COUNT_DOWN,
            plannedMinutes = 25,
            elapsedSeconds = 30 * 60,
            startedAt = t0,
            endedAt = t0.plusSeconds(30 * 60),
        )
        assertEquals(30, over.minutes)
        assertEquals(5, over.overMinutes)
        assertTrue(over.reachedPlan)

        val under = over.copy(elapsedSeconds = 20 * 60)
        assertEquals(20, under.minutes)
        assertEquals(-5, under.overMinutes)
        assertFalse(under.reachedPlan)
    }

    @Test
    fun `记录来源认不出就按计时器处理`() {
        assertEquals(FocusSource.TIMER, FocusSource.fromKey(null))
        assertEquals(FocusSource.TIMER, FocusSource.fromKey("不认识的值"))
        assertEquals(FocusSource.MANUAL, FocusSource.fromKey("manual"))
    }

    @Test
    fun `计时方式键与旧枚举一致`() {
        // 落库用的是 FocusKind 的 key，两边必须对得上（历史数据靠它读回来）。
        assertEquals(FocusKind.COUNT_DOWN.key, FocusMode.COUNT_DOWN.key)
        assertEquals(FocusKind.COUNT_UP.key, FocusMode.COUNT_UP.key)
        assertEquals(FocusKind.POMODORO.key, FocusMode.POMODORO.key)
        assertEquals(FocusKind.COUNT_DOWN, FocusKind.fromKey(FocusMode.COUNT_DOWN.key))
        assertEquals(FocusKind.POMODORO, FocusKind.fromKey(FocusMode.POMODORO.key))
    }

    @Test
    fun `关联对象三态给出正确的条目与标题`() {
        assertEquals(null, FocusTarget.None.itemIdOrNull)
        assertEquals("g1", FocusTarget.Goal("g1", "期末复习").itemIdOrNull)
        assertEquals("期末复习", FocusTarget.Goal("g1", "期末复习").titleOrNull)
        assertEquals("e1", FocusTarget.Event("e1", "高等数学").itemIdOrNull)
        assertEquals("高等数学", FocusTarget.Event("e1", "高等数学").titleOrNull)
    }

    @Test
    fun `番茄钟换段后时间继续累加`() {
        // 与 FocusController.nextPomodoroCycle 同一套算法：把墙钟（扣暂停）压进累计、
        // 本段基准点挪到此刻。写成「累计 + 整段」会把这一段算两次。
        var state = active(FocusMode.POMODORO, planned = 25)
        val cycleEnd = t0.plusSeconds(25 * 60)
        state = state.copy(
            accumulatedSeconds = state.elapsedSeconds(cycleEnd),
            cycleStartedAt = cycleEnd,
            segmentStartedAt = cycleEnd,
            cycle = 2,
        )
        assertEquals(2, state.cycle)
        assertEquals(25 * 60L, state.elapsedSeconds(cycleEnd))
        // 第二段走 10 分钟 → 总时长 35 分钟
        assertEquals(35 * 60L, state.elapsedSeconds(cycleEnd.plusSeconds(600)))
        // 而「本段剩余」只看本段：25 - 10 = 15 分钟
        assertEquals(15 * 60L, state.remainingSeconds(cycleEnd.plusSeconds(600)))
        // 本段进度也是按本段算的
        assertEquals(10f / 25f, state.progress(cycleEnd.plusSeconds(600)), 0.001f)
    }

    @Test
    fun `暂停后累计口径不错乱`() {
        var state = active(FocusMode.POMODORO, planned = 25)
        // 第一段跑 25 分钟 → 换段（与 nextPomodoroCycle 同一套写法）
        val cycleEnd = t0.plusSeconds(25 * 60)
        state = state.copy(
            accumulatedSeconds = state.elapsedSeconds(cycleEnd),
            cycleStartedAt = cycleEnd,
            segmentStartedAt = cycleEnd,
            cycle = 2,
        )
        // 第二段跑 5 分钟后暂停
        val pauseAt = cycleEnd.plusSeconds(5 * 60)
        state = state.copy(
            accumulatedSeconds = state.elapsedSeconds(pauseAt),
            cycleStartedAt = pauseAt,
            segmentStartedAt = null,
        )
        assertEquals(30 * 60L, state.elapsedSeconds(pauseAt))
        // 暂停 10 分钟：总时长不动，本段也不往前走
        assertEquals(30 * 60L, state.elapsedSeconds(pauseAt.plusSeconds(600)))
        assertEquals(0L, state.segmentElapsedSeconds(pauseAt.plusSeconds(600)))
        // 继续后走 5 分钟 → 总时长 35 分钟，本段 5 分钟
        val resumeAt = pauseAt.plusSeconds(600)
        state = state.copy(cycleStartedAt = resumeAt, segmentStartedAt = resumeAt)
        assertEquals(35 * 60L, state.elapsedSeconds(resumeAt.plusSeconds(300)))
        assertEquals(5 * 60L, state.segmentElapsedSeconds(resumeAt.plusSeconds(300)))
    }

    @Test
    fun `落库的形状不变`() {
        // 这条记录就是 FocusController 落库时构造的形状，字段顺序/含义不能漂。
        val session = FocusSession(
            id = "s1",
            startedAt = t0,
            endedAt = t0.plusSeconds(1500),
            minutes = 25,
            kind = FocusKind.COUNT_DOWN,
            plannedMinutes = 25,
            completed = true,
            label = "期末复习计划",
            itemId = "g1",
            createdAt = t0,
        )
        assertEquals(25, session.minutes)
        assertTrue(session.completed)
        assertEquals(FocusKind.COUNT_DOWN, session.kind)
    }
}
