package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 墓碑回收。
 *
 * ## 为什么这个测试重要
 *
 * 回收的 bug **不会立刻显形**：删早了，表现是几天后某台设备上
 * "删掉的东西又回来了" —— 那时已经很难追到是回收干的。
 * 所以两条边界必须锁死：
 *  · 没同步出去的墓碑**绝不能删**；
 *  · 没够 90 天的**绝不能删**。
 */
class TombstoneRecyclerTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    /** 内存版抽屉。 */
    private class FakeExt : EntityExtRepository {
        val rows = linkedMapOf<Pair<ExtOwner, String>, ExtMap>()

        override suspend fun get(owner: ExtOwner, ownerId: String): ExtMap =
            rows[owner to ownerId] ?: ExtMap.EMPTY

        override suspend fun listByTable(owner: ExtOwner): List<ExtRecord> =
            rows.filterKeys { it.first == owner }.map { (k, v) -> ExtRecord(owner, k.second, v) }

        override fun observeAll(): Flow<List<ExtRecord>> = flowOf(emptyList())
        override suspend fun getAll(): List<ExtRecord> =
            rows.map { (k, v) -> ExtRecord(k.first, k.second, v) }

        override suspend fun set(owner: ExtOwner, ownerId: String, map: ExtMap) {
            if (map.isEmpty) rows.remove(owner to ownerId) else rows[owner to ownerId] = map
        }

        override suspend fun update(owner: ExtOwner, ownerId: String, block: (ExtMap) -> ExtMap): ExtMap =
            block(get(owner, ownerId)).also { set(owner, ownerId, it) }

        override suspend fun remove(owner: ExtOwner, ownerId: String) {
            rows.remove(owner to ownerId)
        }
    }

    private fun tombstone(deletedAt: Long, hlc: Hlc?): ExtMap =
        SyncMeta(rev = 2, hlc = hlc, deviceId = "devA", deletedAt = deletedAt).toExt()

    private fun live(): ExtMap = SyncMeta(rev = 1, hlc = Hlc(1000L, 0, "devA"), deviceId = "devA").toExt()

    // ---------------------------------------------------------------- 能回收

    /** 正常情况：够老 + 已同步 ⇒ 回收。 */
    @Test
    fun `够老且已同步的墓碑被回收`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        ext.set(SyncEntity.ITEM.ext, "old", tombstone(now - 100 * day, Hlc(1L, 0, "devA")))
        registry.markDeleted(SyncEntity.ITEM, "old")

        val result = TombstoneRecycler(ext, registry).recycle(now)

        assertEquals(1, result.recycled)
        assertTrue("抽屉里的同步元数据应被清掉", ext.get(SyncEntity.ITEM.ext, "old").isEmpty)
        assertFalse("内存登记处也要解除", registry.isDeleted(SyncEntity.ITEM, "old"))
    }

    @Test
    fun `多个墓碑一起回收`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        repeat(5) { i ->
            ext.set(SyncEntity.ITEM.ext, "id$i", tombstone(now - 200 * day, Hlc(1L, 0, "devA")))
        }

        val result = TombstoneRecycler(ext, registry).recycle(now)
        assertEquals(5, result.recycled)
    }

    // ---------------------------------------------------------------- 不能回收（两条边界）

    /**
     * **没同步出去的墓碑绝不能删。**
     *
     * 删掉的后果：云端不知道这条要删 → 另一台设备上它**永远不会消失**，
     * 甚至会被当作"新增"再传回来。
     */
    @Test
    fun `没同步出去的墓碑不回收`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        // 很老，但没有 HLC（= 从没成功同步过）
        ext.set(SyncEntity.ITEM.ext, "unsynced", tombstone(now - 999 * day, hlc = null))
        registry.markDeleted(SyncEntity.ITEM, "unsynced")

        val result = TombstoneRecycler(ext, registry).recycle(now)

        assertEquals("绝不能回收", 0, result.recycled)
        assertEquals(1, result.keptUnsynced)
        assertFalse("元数据必须留着", ext.get(SyncEntity.ITEM.ext, "unsynced").isEmpty)
        assertTrue("登记处也必须留着", registry.isDeleted(SyncEntity.ITEM, "unsynced"))
    }

    /** 还没够 90 天 ⇒ 留着（别的设备可能还没见过它）。 */
    @Test
    fun `不够老的墓碑不回收`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        ext.set(SyncEntity.ITEM.ext, "fresh", tombstone(now - 10 * day, Hlc(1L, 0, "devA")))

        val result = TombstoneRecycler(ext, registry).recycle(now)

        assertEquals(0, result.recycled)
        assertEquals(1, result.keptTooYoung)
    }

    /** 90 天整的边界：刚好到期就回收（`>=` 而不是 `>`）。 */
    @Test
    fun `刚好 90 天时回收`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        val retention = TombstoneRecycler.DEFAULT_RETENTION_MILLIS
        ext.set(SyncEntity.ITEM.ext, "edge", tombstone(now - retention, Hlc(1L, 0, "devA")))

        assertEquals(1, TombstoneRecycler(ext, registry).recycle(now).recycled)
    }

    /** 差 1 毫秒就不回收。 */
    @Test
    fun `差一毫秒不回收`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        val retention = TombstoneRecycler.DEFAULT_RETENTION_MILLIS
        ext.set(SyncEntity.ITEM.ext, "edge", tombstone(now - retention + 1, Hlc(1L, 0, "devA")))

        assertEquals(0, TombstoneRecycler(ext, registry).recycle(now).recycled)
    }

    /** 活实体（没有墓碑标记）不该被碰 —— 回收只处理墓碑。 */
    @Test
    fun `活实体的同步元数据不受影响`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        ext.set(SyncEntity.ITEM.ext, "alive", live())

        val result = TombstoneRecycler(ext, registry).recycle(now)

        assertEquals("不该被计入扫描", 0, result.scanned)
        assertFalse("活实体的元数据必须原样保留", ext.get(SyncEntity.ITEM.ext, "alive").isEmpty)
    }

    /** 回收一个墓碑不能影响别的实体/别的行。 */
    @Test
    fun `只回收符合条件的那一条`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        ext.set(SyncEntity.ITEM.ext, "old", tombstone(now - 200 * day, Hlc(1L, 0, "devA")))
        ext.set(SyncEntity.ITEM.ext, "fresh", tombstone(now - 1 * day, Hlc(1L, 0, "devA")))
        ext.set(SyncEntity.ITEM.ext, "alive", live())

        val result = TombstoneRecycler(ext, registry).recycle(now)

        assertEquals(1, result.recycled)
        assertTrue("老的应被清掉", ext.get(SyncEntity.ITEM.ext, "old").isEmpty)
        assertFalse("新的必须留着", ext.get(SyncEntity.ITEM.ext, "fresh").isEmpty)
        assertFalse("活的必须留着", ext.get(SyncEntity.ITEM.ext, "alive").isEmpty)
    }

    /** 多张表：各自的墓碑都要被扫到（漏一张表就会让它无限增长）。 */
    @Test
    fun `多张表的墓碑都会被扫描`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        SyncEntity.entries.forEach { entity ->
            ext.set(entity.ext, "id-${entity.table}", tombstone(now - 200 * day, Hlc(1L, 0, "devA")))
        }

        val result = TombstoneRecycler(ext, registry).recycle(now)

        assertEquals("每张表都要被扫到", SyncEntity.entries.size, result.recycled)
    }

    // ---------------------------------------------------------------- dry run

    /** dryRun 只统计不删 —— 用来在真机上验证"会回收多少"而不冒险。 */
    @Test
    fun `dryRun 不删任何东西`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        ext.set(SyncEntity.ITEM.ext, "old", tombstone(now - 200 * day, Hlc(1L, 0, "devA")))
        registry.markDeleted(SyncEntity.ITEM, "old")

        val result = TombstoneRecycler(ext, registry).recycle(now, dryRun = true)

        assertEquals("要报告会回收多少", 1, result.recycled)
        assertFalse("但不该真的删", ext.get(SyncEntity.ITEM.ext, "old").isEmpty)
        assertTrue("登记处也不该动", registry.isDeleted(SyncEntity.ITEM, "old"))
    }

    // ---------------------------------------------------------------- 统计

    @Test
    fun `统计数字能对得上`() = runTest {
        val ext = FakeExt()
        val registry = TombstoneRegistry(ext)
        ext.set(SyncEntity.ITEM.ext, "a", tombstone(now - 200 * day, Hlc(1L, 0, "devA"))) // 回收
        ext.set(SyncEntity.ITEM.ext, "b", tombstone(now - 1 * day, Hlc(1L, 0, "devA")))   // 太新
        ext.set(SyncEntity.ITEM.ext, "c", tombstone(now - 200 * day, null))               // 未同步

        val result = TombstoneRecycler(ext, registry).recycle(now)

        assertEquals("扫描 3 条", 3, result.scanned)
        assertEquals("回收 1 条", 1, result.recycled)
        assertEquals("太新 1 条", 1, result.keptTooYoung)
        assertEquals("未同步 1 条", 1, result.keptUnsynced)
    }

    /** 空库不该出错。 */
    @Test
    fun `没有墓碑时是安全的空操作`() = runTest {
        val ext = FakeExt()
        val result = TombstoneRecycler(ext, TombstoneRegistry(ext)).recycle(now)

        assertEquals(0, result.recycled)
        assertEquals(0, result.scanned)
    }

    /** 保留窗口是 90 天 —— 这条锁住常量本身（改小会让离线设备复活已删数据）。 */
    @Test
    fun `保留窗口是九十天`() {
        assertEquals(90L * 24 * 60 * 60 * 1000, TombstoneRecycler.DEFAULT_RETENTION_MILLIS)
    }
}
