package com.phonlynn.oreplan.domain.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ⚠️ **这个测试守的是一个真实回归**（用户报的"删除后不刷新"）。
 *
 * ## 问题的形状
 *
 * 云同步 S1 把删除从硬删改成了软删：
 *
 * | | 主表 | 墓碑表 |
 * |---|---|---|
 * | 硬删（S1 之前） | **变了** | 无 |
 * | 软删（S1 之后） | 没变 | **变了** |
 *
 * 而仓储的读出口写的是 `dao.observeXxx().map { it.visible() }` ——
 * `observeXxx()` 只监听**主表**。软删之后主表不动，这个流**再也不发射**，
 * 包在 `map` 里的墓碑过滤也就永远没机会重跑。
 *
 * 所以：**删除后界面不更新，切一下页面才好**（重进时重新订阅，过滤重跑了一遍）。
 *
 * ## 这里测的是机制本身
 *
 * 不去跑 Room（JVM 测试里没有 Room），而是用一个 `MutableStateFlow` 冒充
 * DAO 的流，验证 **`combine(源, 墓碑)` 在墓碑变化时会重新发射**。
 * 这正是修法成立的全部依据。
 *
 * ⚠️ 收集协程必须跑在 [UnconfinedTestDispatcher] 上：默认的调度器下
 * `combine` 要好几轮调度才真正开始收集，`yield()` 一两次是不够的 ——
 * 我第一版就是这么写的，结果收到 0 次发射，看起来像修法失效。
 */
class TombstoneObservingTest {

    /** 冒充 DAO 的流：可以手动推新值，模拟"主表变了"。 */
    private val daoFlow = MutableStateFlow(listOf("a", "b", "c"))

    /** 冒充 `TombstoneRegistry.deleted`。 */
    private val tombstones = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    @Before
    fun setUp() {
        // 让 launch 出去的收集立刻开始跑，不必靠 yield 猜
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 修好之后的写法：跟着墓碑一起重算。 */
    private fun fixed(): kotlinx.coroutines.flow.Flow<List<String>> =
        combine(daoFlow, tombstones) { rows, dead ->
            rows.filterNot { it in dead["items"].orEmpty() }
        }

    /** 出问题的写法：只监听 DAO。 */
    private fun broken(): kotlinx.coroutines.flow.Flow<List<String>> =
        daoFlow.map { rows -> rows.filterNot { it in tombstones.value["items"].orEmpty() } }

    /**
     * ⚠️ **核心断言**：墓碑变化时修好的写法要重新发射，坏掉的不会。
     *
     * 这条同时证明"问题确实存在"和"修法确实有效" ——
     * 只断言前者的话，改法对不对没人知道。
     */
    @Test
    fun `墓碑变化时只有combine会重新发射`() = runTest {
        val fixedSeen = mutableListOf<List<String>>()
        val brokenSeen = mutableListOf<List<String>>()

        val a = launch(UnconfinedTestDispatcher(testScheduler)) { fixed().collect { fixedSeen += it } }
        val b = launch(UnconfinedTestDispatcher(testScheduler)) { broken().collect { brokenSeen += it } }
        yield()

        assertEquals("初始应当各收到一次", 1, fixedSeen.size)
        assertEquals(1, brokenSeen.size)

        // 软删 "b"：只动墓碑，**主表一行没动**
        tombstones.value = mapOf("items" to setOf("b"))
        yield()

        assertEquals(
            "墓碑变了却没重新发射 —— 这正是用户报的『删除后不刷新』",
            2,
            fixedSeen.size,
        )
        assertEquals(listOf("a", "c"), fixedSeen.last())

        assertEquals(
            "坏掉的写法不该有反应（这条若断了，说明它其实会发射，那 bug 另有原因）",
            1,
            brokenSeen.size,
        )

        a.cancel()
        b.cancel()
    }

    /** 主表变化时仍然要刷新（不能为了修 bug 把原有的刷新弄丢）。 */
    @Test
    fun `主表变化时仍然刷新`() = runTest {
        val seen = mutableListOf<List<String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { fixed().collect { seen += it } }
        yield()

        daoFlow.value = listOf("a", "b", "c", "d")
        yield()

        assertEquals(2, seen.size)
        assertEquals(listOf("a", "b", "c", "d"), seen.last())
        job.cancel()
    }

    /** 结果始终与"两边当前值"一致，不会出现中间态。 */
    @Test
    fun `结果与两边的当前值一致`() = runTest {
        tombstones.value = mapOf("items" to setOf("a"))
        daoFlow.value = listOf("a", "b")
        yield()

        assertEquals(listOf("b"), fixed().first())
    }

    /** 边界：墓碑为空时不能把内容吃掉。 */
    @Test
    fun `没有墓碑时原样返回`() = runTest {
        val result = combine(
            flowOf(listOf("a", "b")),
            flowOf(emptyMap<String, Set<String>>()),
        ) { r, _ -> r }.first()

        assertEquals(listOf("a", "b"), result)
    }
}
