package com.phonlynn.oreplan.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLC 的定序正确性。
 *
 * 这一层错了的表现是「用户改了东西，过一会儿又变回去」——偶发、难复现、
 * 而且在真机上几乎不可能定位。所以这里把边界全部钉死。
 */
class HlcTest {

    @Test
    fun `编码后字典序等于时间序`() {
        val a = Hlc(1000L, 0, "devA")
        val b = Hlc(1001L, 0, "devA")
        val c = Hlc(1001L, 1, "devA")
        // 字符串比较（模拟在某些存储里只能按字符串排序）
        assertTrue("物理时间大者应排在后面", a.encode() < b.encode())
        assertTrue("同毫秒计数大者应排在后面", b.encode() < c.encode())
        // 与 compareTo 结论一致
        assertTrue(a < b)
        assertTrue(b < c)
    }

    @Test
    fun `编码解码往返一致`() {
        val original = Hlc(1786343581193L, 7, "9f3a-4b2c")
        assertEquals(original, Hlc.decode(original.encode()))
    }

    @Test
    fun `解码非法输入返回 null 而不是抛异常`() {
        assertEquals(null, Hlc.decode(""))
        assertEquals(null, Hlc.decode("not-a-clock"))
        assertEquals(null, Hlc.decode("zzzz-0001-dev"))
    }

    @Test
    fun `同毫秒内计数递增`() {
        val fixed = 1_000_000L
        val first = Hlc.tick(fixed, Hlc.ZERO, "devA")
        val second = Hlc.tick(fixed, first, "devA")
        assertEquals(fixed, first.physical)
        assertEquals(0, first.counter)
        assertEquals(fixed, second.physical)
        assertEquals(1, second.counter)
        assertTrue(second > first)
    }

    /**
     * 墙钟回退（用户手动改时间、NTP 校正）时，
     * 新事件**不得**比旧事件小 —— 否则新修改会被判为旧。
     */
    @Test
    fun `墙钟回退时物理时间不跟随`() {
        val last = Hlc(2_000_000L, 3, "devA")
        // 墙钟被调回到更早的时刻
        val next = Hlc.tick(1_000_000L, last, "devA")
        assertTrue("新时钟必须仍大于旧时钟", next > last)
        assertEquals("物理时间应保持单调", 2_000_000L, next.physical)
        assertEquals("同一物理时间下计数递增", 4, next.counter)
    }

    @Test
    fun `合并远端后本机时钟严格大于两者`() {
        val local = Hlc(1000L, 0, "devA")
        val remote = Hlc(2000L, 5, "devB")
        val merged = Hlc.merge(now = 1500L, local = local, remote = remote, deviceId = "devA")
        assertTrue("必须大于本地", merged > local)
        assertTrue("必须大于远端", merged > remote)
    }

    @Test
    fun `合并时墙钟前瞻到远端之后`() {
        // 本机墙钟慢，远端时间戳更靠前
        val local = Hlc(1000L, 0, "devA")
        val remote = Hlc(5000L, 2, "devB")
        val merged = Hlc.merge(now = 1200L, local = local, remote = remote, deviceId = "devA")
        assertEquals(5000L, merged.physical)
        assertEquals("应取远端计数 +1", 3, merged.counter)
    }

    @Test
    fun `物理时间都相同时取最大计数加一`() {
        val local = Hlc(1000L, 3, "devA")
        val remote = Hlc(1000L, 7, "devB")
        val merged = Hlc.merge(now = 1000L, local = local, remote = remote, deviceId = "devA")
        assertEquals(1000L, merged.physical)
        assertEquals(8, merged.counter)
    }

    /**
     * 全序性：任意两个**不同**的 HLC 必须可比较且不相等。
     *
     * 若 deviceId 不参与 tie-break，两台设备在同一毫秒产生的并发事件会被判为
     * "相等"，合并结果就依赖到达顺序 —— 那是不可复现的。
     */
    @Test
    fun `设备 id 参与 tie-break 保证全序`() {
        val a = Hlc(1000L, 0, "devA")
        val b = Hlc(1000L, 0, "devB")
        assertNotEquals("同时间同计数但不同设备不得相等", a, b)
        assertTrue("必须能分出先后", a < b || b < a)
    }

    @Test
    fun `compareTo 与 equals 一致`() {
        val a = Hlc(1000L, 0, "devA")
        val b = Hlc(1000L, 0, "devA")
        assertEquals(0, a.compareTo(b))
        assertEquals(a, b)
        assertEquals("相等对象编码也必须相同", a.encode(), b.encode())
    }

    @Test
    fun `时钟类在真实时间下单调递增`() {
        val clock = SyncClock()
        clock.restore("testDevice")
        var previous = clock.tick()
        repeat(50) {
            val next = clock.tick()
            assertTrue("第 $it 次 tick 必须严格递增", next > previous)
            previous = next
        }
    }

    @Test
    fun `时钟类 observe 后自身事件仍大于远端`() {
        val clock = SyncClock()
        clock.restore("local")
        val remote = Hlc(System.currentTimeMillis() + 60_000L, 3, "remote")
        clock.observe(remote)
        assertTrue("observe 之后本机产生的事件必须大于远端", clock.tick() > remote)
    }

    @Test
    fun `observe null 不改变时钟`() {
        val clock = SyncClock()
        clock.restore("local")
        clock.observe(null)
        assertEquals(Hlc.ZERO, clock.peek())
    }
}
