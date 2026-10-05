package com.phonlynn.oreplan.data.settings

import com.phonlynn.oreplan.data.local.dao.AppMetaDao
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **设置加载时序测试**（2026-09-20）。
 *
 * 背景（「首次进入先显卡片编辑页、再跳全屏」的根源）：
 *
 * `AppSettingsStore.settings` 的初值是**全部默认值**，而读库是异步的。
 * 首次进应用时直接读 `settings.value` 拿到的是默认值，
 * 而「使用全屏视图新建」的默认值是 `false`——把已开启的用户误判为关闭，
 * 于是先显示编辑页、之后设置读完才又跳全屏。
 * 第二次进应用时设置已在缓存里，所以「第二次之后就好了」。
 *
 * 这里把「未加载时不能当成真实值」与「awaitLoaded 能等到真实值」钉死。
 */
class AppSettingsStoreLoadTest {

    /** 可控延迟的假 DAO，用来模拟「首次进应用时读库还没回来」。 */
    private class FakeDao(
        private var stored: String? = null,
        private val delayMs: Long = 0,
    ) : AppMetaDao {
        var findCount = 0
            private set

        override suspend fun count(): Int = if (stored == null) 0 else 1

        override suspend fun find(key: String): AppMetaEntity? {
            findCount++
            if (delayMs > 0) delay(delayMs)
            return stored?.let { AppMetaEntity(key, it) }
        }

        override suspend fun upsert(entry: AppMetaEntity) {
            stored = entry.value
        }
    }

    /** 未加载完成时，`isLoaded` 为 false（调用方据此不读默认值）。 */
    @Test
    fun `未加载完成时 isLoaded 为 false`() {
        val dao = FakeDao(delayMs = 50)
        val store = AppSettingsStore(dao)
        // 触发加载但不等它完成。
        store.settings
        assertFalse("刚触发加载时不应声称已加载", store.isLoaded)
    }

    /** 加载完成后 `isLoaded` 变 true，且能读到真实值。 */
    @Test
    fun `加载完成后能读到真实设置`() = runBlocking {
        val dao = FakeDao(delayMs = 20)
        val store = AppSettingsStore(dao)
        // 先写入一个「开启全屏新建」的真实设置。
        store.update { it.copy(boardNewCardFullscreen = true) }
        // 新建一个 store 读回（同一份底层数据）。
        val store2 = AppSettingsStore(dao)
        store2.awaitLoaded()
        assertTrue(store2.isLoaded)
        assertTrue(
            "awaitLoaded 之后必须读到真实值，而不是默认 false",
            store2.settings.value.boardNewCardFullscreen,
        )
    }

    /**
     * **核心回归**：未加载完就读 `settings.value` 会拿到默认的 false。
     *
     * 这是旧实现的错误用法（判定里直接读 `.value`）。
     * 该断言把「默认值不可信」这个事实固定下来——
     * 若以后有人又把默认值改成 true 并依赖它，这里会失败并提醒。
     */
    @Test
    fun `未加载完时 value 是默认值而非用户设置`() {
        val dao = FakeDao(delayMs = 100)
        val store = AppSettingsStore(dao)
        // 触发加载，但立刻读 value（此时读库尚未完成）。
        val immediate = store.settings.value
        assertFalse(
            "未加载完时读到的是默认值 false；调用方必须先 awaitLoaded",
            immediate.boardNewCardFullscreen,
        )
    }

    /** awaitLoaded 在设置已加载后应立即返回，不重复读库。 */
    @Test
    fun `awaitLoaded 已加载时立即返回`() = runBlocking {
        val dao = FakeDao(delayMs = 10)
        val store = AppSettingsStore(dao)
        store.awaitLoaded()
        val countAfterFirst = dao.findCount
        store.awaitLoaded()
        assertEquals("已加载后不应重复读库", countAfterFirst, dao.findCount)
    }

    /** 读库失败也必须放行等待方，否则会永久挂起（白屏）。 */
    @Test
    fun `读库异常时 awaitLoaded 仍会放行`() = runBlocking {
        val dao = object : AppMetaDao {
            override suspend fun count(): Int = 0
            override suspend fun find(key: String): AppMetaEntity? = error("模拟读库失败")
            override suspend fun upsert(entry: AppMetaEntity) = Unit
        }
        val store = AppSettingsStore(dao)
        // 不抛异常、也不永久挂起。
        store.awaitLoaded()
        assertTrue(store.isLoaded)
    }
}
