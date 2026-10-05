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
 * [SyncStampWriter] 的行为契约。
 *
 * 这一层的正确性直接决定同步结果：
 *  · rev 不递增 ⇒ 远端认为"没更新"，改动传不出去；
 *  · 删除不盖时钟 ⇒ 另一端删不掉，或删了又复活；
 *  · 修改不清墓碑 ⇒ 一个被改活的实体仍被判为"已删"，界面上永远看不见。
 */
class SyncStampWriterTest {

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
        override suspend fun update(owner: ExtOwner, ownerId: String, block: (ExtMap) -> ExtMap): ExtMap =
            block(get(owner, ownerId)).also { set(owner, ownerId, it) }
        override suspend fun remove(owner: ExtOwner, ownerId: String) {
            rows.remove(owner to ownerId)
        }
    }

    private fun writer(repo: FakeExtRepo): Pair<SyncStampWriter, SyncClock> {
        val clock = SyncClock()
        clock.restore("devA")
        return SyncStampWriter(repo, clock) to clock
    }

    @Test
    fun `首次修改盖上 rev 1 与时钟`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        val meta = stamp.stampModified(SyncEntity.ITEM, "x")

        assertEquals(1L, meta.rev)
        assertEquals("devA", meta.deviceId)
        assertFalse("修改不该留下墓碑", meta.isDeleted)
        assertTrue("修改后就有时钟了（不再是从未同步）", !meta.isNeverSynced)
    }

    @Test
    fun `连续修改 rev 单调递增`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        val revs = (1..5).map { stamp.stampModified(SyncEntity.ITEM, "x").rev }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), revs)
    }

    @Test
    fun `删除盖上墓碑且 rev 仍递增`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        stamp.stampModified(SyncEntity.ITEM, "x")
        val deleted = stamp.stampDeleted(SyncEntity.ITEM, "x")

        assertEquals("删除也是一次修改", 2L, deleted.rev)
        assertTrue(deleted.isDeleted)
        assertTrue("墓碑必须带时间（90 天清理要用）", (deleted.deletedAt ?: 0L) > 0L)
    }

    /**
     * **修改要把墓碑清掉**。
     *
     * 若不清，一个"被软删之后又被改活"的实体仍然被判为已删：
     * 数据在库里、却没进内存登记处，界面上它永远不出现 —— 表现为"数据凭空消失"。
     */
    @Test
    fun `修改会清除墓碑`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        stamp.stampDeleted(SyncEntity.ITEM, "x")
        assertTrue(stamp.read(SyncEntity.ITEM, "x").isDeleted)

        val revived = stamp.stampModified(SyncEntity.ITEM, "x")

        assertFalse("改活之后不该还是墓碑", revived.isDeleted)
        assertFalse(stamp.read(SyncEntity.ITEM, "x").isDeleted)
    }

    @Test
    fun `批量删除给每个 id 都盖上墓碑`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        val result = stamp.stampDeletedAll(SyncEntity.ITEM, listOf("a", "b", "c"))

        assertEquals(setOf("a", "b", "c"), result.keys)
        assertTrue(result.values.all { it.isDeleted })
        assertTrue(stamp.read(SyncEntity.ITEM, "a").isDeleted)
        assertTrue(stamp.read(SyncEntity.ITEM, "c").isDeleted)
    }

    @Test
    fun `批量删除传空集合不写任何东西`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        val result = stamp.stampDeletedAll(SyncEntity.ITEM, emptyList())

        assertTrue(result.isEmpty())
        assertTrue("不该产生抽屉行", repo.rows.isEmpty())
    }

    /**
     * 同步元数据与**业务字段共存于同一个抽屉**。
     * 盖同步章时绝不能把业务键冲掉 —— 那等于同步功能一上线就清空所有规划参数。
     */
    @Test
    fun `盖章不会覆盖抽屉里的业务键`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        repo.set(
            ExtOwner.ITEM,
            "x",
            ExtMap.EMPTY.putText("plan.dailyMinutes", "45"),
        )

        stamp.stampModified(SyncEntity.ITEM, "x")
        stamp.stampDeleted(SyncEntity.ITEM, "x")

        assertEquals("业务键必须原样保留", "45", repo.get(ExtOwner.ITEM, "x").text("plan.dailyMinutes"))
    }

    /**
     * 承认远端元数据：rev 用远端的、并且本机时钟要**被推后**到不小于它。
     *
     * 若时钟不推后，本机下一次修改会用一个"早于已见远端"的时间戳，
     * 于是本机的新修改在另一台设备看来是旧的 —— 改动被丢弃。
     */
    @Test
    fun `写入远端元数据时本机时钟被推后`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, clock) = writer(repo)
        val farFuture = Hlc(System.currentTimeMillis() + 3_600_000L, 9, "devB")

        stamp.writeFromRemote(
            SyncEntity.ITEM,
            "x",
            SyncMeta(rev = 42, hlc = farFuture, deviceId = "devB"),
        )

        val stored = stamp.read(SyncEntity.ITEM, "x")
        assertEquals(42L, stored.rev)
        assertEquals("devB", stored.deviceId)
        assertTrue("本机时钟必须被推到远端之后", clock.tick() > farFuture)
    }

    @Test
    fun `从未同步过的实体被识别出来`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        assertTrue("新建即从未同步", stamp.wasNeverSynced(SyncEntity.ITEM, "brand-new"))
        stamp.stampModified(SyncEntity.ITEM, "brand-new")
        assertFalse("盖过章就不再是", stamp.wasNeverSynced(SyncEntity.ITEM, "brand-new"))
    }

    /** 不同表的同名 id 互不干扰（跨表 id 相同是可能的）。 */
    @Test
    fun `不同表的同名 id 各自独立`() = runTest {
        val repo = FakeExtRepo()
        val (stamp, _) = writer(repo)

        stamp.stampDeleted(SyncEntity.ITEM, "same")
        stamp.stampModified(SyncEntity.BOARD_CARD, "same")

        assertTrue(stamp.read(SyncEntity.ITEM, "same").isDeleted)
        assertFalse(stamp.read(SyncEntity.BOARD_CARD, "same").isDeleted)
    }
}
