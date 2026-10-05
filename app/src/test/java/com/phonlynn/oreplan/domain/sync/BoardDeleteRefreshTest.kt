package com.phonlynn.oreplan.domain.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
 * 模拟白板卡片的**完整删除链路**，看界面会不会刷新。
 *
 * ## 为什么单独做这一个
 *
 * 用户在我修完「删除后不刷新」之后又报：
 *
 * > 「现在（至少）白板卡片还是没法删除后立刻刷新」
 *
 * 所以要么修法不完整，要么**白板这条路和 items 那条不一样**。
 * 这里把链路按真实形状搭出来（DAO 流 → observing → filter → 界面订阅），
 * 逐步断言，定位到底断在哪一环。
 *
 * 白板的实际链路：
 *
 * ```
 * cardDao.observeActive()                    ← 只监听 board_cards 表
 *   → tombstones.observing(BOARD_CARD, …)    ← 应该跟着墓碑重算
 *   → .map { it.visibleCards() }             ← 按墓碑过滤
 *   → 界面 combine(...)
 * ```
 *
 * 而删除是：
 *
 * ```
 * deleteCard → board_cards **一行不动** → tombstones.markDeleted(BOARD_CARD, id)
 * ```
 */
class BoardDeleteRefreshTest {

    /** 冒充 cardDao.observeActive()。 */
    private val cardDaoFlow = MutableStateFlow(listOf("c1", "c2", "c3"))

    /** 冒充 TombstoneRegistry.deleted。 */
    private val deleted = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** 冒充 TombstoneRegistry.observing —— 与真实实现逐字一致。 */
    private fun <T> observing(source: kotlinx.coroutines.flow.Flow<List<T>>) =
        combine(source, deleted) { rows, _ -> rows }

    /** 冒充 visibleCards()。 */
    private fun visible(rows: List<String>): List<String> =
        rows.filterNot { it in deleted.value["board_cards"].orEmpty() }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * ⚠️ **核心**：整条链路串起来，删除后界面要收到新列表。
     *
     * 这条如果过了，说明机制没问题 —— 那白板页的实际问题在别处
     *（比如它没用 observeCards，或者页面上还有别的缓存）。
     */
    @Test
    fun `白板删除后应当刷新`() = runTest {
        val seen = mutableListOf<List<String>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observing(cardDaoFlow).map { visible(it) }.collect { seen += it }
        }
        yield()

        assertEquals("初始应当是三张卡", listOf("c1", "c2", "c3"), seen.last())

        // 删 c2：board_cards 一行没动，只写墓碑
        deleted.value = mapOf("board_cards" to setOf("c2"))
        yield()

        assertEquals("删除后应当再收到一次", 2, seen.size)
        assertEquals("c2 应当消失", listOf("c1", "c3"), seen.last())

        job.cancel()
    }

    /**
     * ⚠️ 如果**没有** observing 这一层（即修复前的写法），就不会刷新。
     *
     * 这条是"复现 bug"的对照 —— 它证明了 observing 是必需的，
     * 而不是可有可无的一层包装。
     */
    @Test
    fun `没有 observing 时不会刷新`() = runTest {
        val seen = mutableListOf<List<String>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            cardDaoFlow.map { visible(it) }.collect { seen += it }
        }
        yield()

        deleted.value = mapOf("board_cards" to setOf("c2"))
        yield()

        assertEquals("没有 observing 就不该有新发射（这就是 bug 本身）", 1, seen.size)
        job.cancel()
    }

    /**
     * ⚠️ 另一个可能的坑：**`combine` 的 `_deleted` 是 StateFlow，
     * 初始值会让它立刻发射一次**。如果 `distinctUntilChanged` 之类被加在
     * 中间，墓碑变化可能被当成"值没变"而被吞掉。
     *
     * 这里确认当前实现没有这层，以及将来若有人加 `distinctUntilChanged`
     * 会怎样 —— 这是**知情的取舍**，不是断言它错。
     */
    @Test
    fun `墓碑变化不会被去重吞掉`() = runTest {
        val seen = mutableListOf<List<String>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            observing(cardDaoFlow).map { visible(it) }.collect { seen += it }
        }
        yield()

        // 连删两张：两次都要反映出来
        deleted.value = mapOf("board_cards" to setOf("c1"))
        yield()
        deleted.value = mapOf("board_cards" to setOf("c1", "c3"))
        yield()

        assertEquals("两次删除都应当刷出来", 3, seen.size)
        assertEquals(listOf("c2"), seen.last())
        job.cancel()
    }
}
