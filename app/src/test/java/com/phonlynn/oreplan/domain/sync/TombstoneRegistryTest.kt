package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 墓碑登记处 + 过滤层。
 *
 * 这一层的失效模式是**「删掉的东西在某个界面复活」** ——
 * 而且只在"删过这类数据"的设备上复现。所以这里把每条纪律都钉住。
 */
class TombstoneRegistryTest {

    /** 内存版抽屉，只存不读外部世界。 */
    private class FakeExtRepo : EntityExtRepository {
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
        override suspend fun update(
            owner: ExtOwner,
            ownerId: String,
            block: (ExtMap) -> ExtMap,
        ): ExtMap = block(get(owner, ownerId)).also { set(owner, ownerId, it) }
        override suspend fun remove(owner: ExtOwner, ownerId: String) {
            rows.remove(owner to ownerId)
        }
    }

    private fun tombstoneMap(): ExtMap = SyncMeta(
        rev = 2,
        hlc = Hlc(1000L, 0, "dev"),
        deviceId = "dev",
        deletedAt = 12345L,
    ).toExt()

    @Test
    fun `load 后能认出被软删的实体`() = runTest {
        val repo = FakeExtRepo()
        repo.set(ExtOwner.ITEM, "alive", SyncMeta(rev = 1).toExt())
        repo.set(ExtOwner.ITEM, "dead", tombstoneMap())
        val registry = TombstoneRegistry(repo)

        registry.load()

        assertFalse("活着的实体不该被认成已删", registry.isDeleted(SyncEntity.ITEM, "alive"))
        assertTrue("打了墓碑的应被认成已删", registry.isDeleted(SyncEntity.ITEM, "dead"))
    }

    @Test
    fun `墓碑按表隔离`() = runTest {
        val repo = FakeExtRepo()
        repo.set(ExtOwner.ITEM, "x", tombstoneMap())
        val registry = TombstoneRegistry(repo)
        registry.load()

        assertTrue(registry.isDeleted(SyncEntity.ITEM, "x"))
        // 同名 id 在别的表里不该被牵连（跨表 id 恰好相同是可能的）
        assertFalse(registry.isDeleted(SyncEntity.BOARD_CARD, "x"))
    }

    /**
     * **最重要的纪律**：装载完成前不做过滤。
     *
     * 理由：启动瞬间墓碑还没读完，若此时按"空集合 = 没有已删项"去过滤，
     * 结果与"不过滤"一致；但若反过来（错误实现成"没装载 = 全部已删"），
     * 所有列表会先空一帧再恢复 —— 那是明显的界面闪烁。
     */
    @Test
    fun `装载前不过滤`() = runTest {
        val repo = FakeExtRepo()
        repo.set(ExtOwner.ITEM, "dead", tombstoneMap())
        val registry = TombstoneRegistry(repo)

        // 故意不调 load()
        val rows = listOf("dead", "alive")
        assertEquals(
            "未装载时应原样返回（宁可显示，不可整列表空掉）",
            rows,
            registry.filter(SyncEntity.ITEM, rows) { it },
        )
    }

    @Test
    fun `filter 按 id 摘掉已删项`() = runTest {
        val repo = FakeExtRepo()
        repo.set(ExtOwner.ITEM, "dead", tombstoneMap())
        val registry = TombstoneRegistry(repo)
        registry.load()

        val rows = listOf("a", "dead", "b")
        assertEquals(listOf("a", "b"), registry.filter(SyncEntity.ITEM, rows) { it })
    }

    @Test
    fun `filterOne 对已删项返回 null`() = runTest {
        val repo = FakeExtRepo()
        repo.set(ExtOwner.ITEM, "dead", tombstoneMap())
        val registry = TombstoneRegistry(repo)
        registry.load()

        assertNull(registry.filterOne(SyncEntity.ITEM, "dead", "dead"))
        assertEquals("alive", registry.filterOne(SyncEntity.ITEM, "alive", "alive"))
        assertNull("传 null 仍返回 null", registry.filterOne(SyncEntity.ITEM, null, "x"))
    }

    @Test
    fun `markDeleted 立即生效（不必重新 load）`() = runTest {
        val repo = FakeExtRepo()
        val registry = TombstoneRegistry(repo)
        registry.load()

        assertFalse(registry.isDeleted(SyncEntity.ITEM, "x"))
        registry.markDeleted(SyncEntity.ITEM, "x")
        assertTrue("删除后同一进程内必须立刻不可见", registry.isDeleted(SyncEntity.ITEM, "x"))
    }

    @Test
    fun `markDeletedAll 批量生效`() = runTest {
        val repo = FakeExtRepo()
        val registry = TombstoneRegistry(repo)
        registry.load()

        registry.markDeletedAll(SyncEntity.ITEM, listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), registry.deletedIds(SyncEntity.ITEM))
    }

    @Test
    fun `markDeletedAll 传空集合是安全的`() = runTest {
        val repo = FakeExtRepo()
        val registry = TombstoneRegistry(repo)
        registry.load()
        registry.markDeletedAll(SyncEntity.ITEM, emptyList())
        assertEquals(emptySet<String>(), registry.deletedIds(SyncEntity.ITEM))
    }

    @Test
    fun `unmarkDeleted 可恢复`() = runTest {
        val repo = FakeExtRepo()
        val registry = TombstoneRegistry(repo)
        registry.load()

        registry.markDeleted(SyncEntity.ITEM, "x")
        registry.unmarkDeleted(SyncEntity.ITEM, "x")
        assertFalse(registry.isDeleted(SyncEntity.ITEM, "x"))
    }

    /**
     * 抽屉里 `sync.deleted` 是**快速判断位**，`sync.deletedAt` 承载时间。
     * 只有 Flag 没有 At 时仍必须被认成墓碑（否则 90 天清理的计数会偏）。
     */
    @Test
    fun `只有 deleted 标志位也算墓碑`() = runTest {
        val repo = FakeExtRepo()
        repo.set(
            ExtOwner.ITEM,
            "x",
            ExtMap.EMPTY.putFlag(SyncMeta.KEY_DELETED, true),
        )
        val registry = TombstoneRegistry(repo)
        registry.load()
        assertTrue(registry.isDeleted(SyncEntity.ITEM, "x"))
    }

    /** 业务字段不得被同步逻辑误伤：清理元数据要保持其它键原样。 */
    @Test
    fun `clearedFrom 只摘同步键保留业务键`() {
        val base = ExtMap.EMPTY
            .putText("plan.dailyMinutes", "30")
            .putText(SyncMeta.KEY_HLC, "00000000000003e8-0000-dev")
            .putNum(SyncMeta.KEY_REV, 5.0)
            .putFlag(SyncMeta.KEY_DELETED, true)

        val cleared = SyncMeta.EMPTY.clearedFrom(base)

        assertEquals("业务键必须保留", "30", cleared.text("plan.dailyMinutes"))
        SyncMeta.ALL_KEYS.forEach { key ->
            assertNull("同步键 $key 应被摘掉", cleared[key])
        }
    }
}
