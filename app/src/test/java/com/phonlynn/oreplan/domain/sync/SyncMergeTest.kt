package com.phonlynn.oreplan.domain.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冲突合并 —— 同步里最容易毁数据的一层。
 *
 * 这里把**每一条分支**都穷举，因为错的后果是"用户的数据没了"或"删了又回来"，
 * 而且只在两端并发时复现（真机上极难构造）。
 */
class SyncMergeTest {

    private fun envelope(
        id: String = "x",
        rev: Long = 1,
        hlc: Hlc? = Hlc(1000L, 0, "devA"),
        device: String = "devA",
        deletedAt: Long? = null,
        payload: Map<String, Any?> = mapOf("title" to "T"),
    ) = SyncEnvelope(
        table = "items",
        id = id,
        rev = rev,
        hlc = hlc?.encode(),
        deviceId = device,
        deletedAt = deletedAt,
        payload = JSONObject().apply { payload.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } },
    )

    // ---------------------------------------------------------------- 基本

    @Test
    fun `两边都没有时不产生结果`() {
        assertNull(SyncMerge.merge(null, null))
    }

    @Test
    fun `只有远端时采用远端`() {
        val remote = envelope()
        val result = SyncMerge.merge(null, remote)

        assertNotNull(result)
        assertEquals(remote.id, result!!.winner.id)
        assertFalse("新增不是冲突", result.conflict)
    }

    @Test
    fun `只有本机时采用本机`() {
        val local = envelope()
        val result = SyncMerge.merge(local, null)

        assertEquals(local.id, result!!.winner.id)
        assertFalse(result.conflict)
    }

    /** 时钟大的赢 —— 这是"后发生的修改不被旧数据覆盖"的基本保证。 */
    @Test
    fun `两边都活时时钟大者赢`() {
        val older = envelope(hlc = Hlc(1000L, 0, "devA"), payload = mapOf("title" to "旧"))
        val newer = envelope(hlc = Hlc(2000L, 0, "devB"), device = "devB", payload = mapOf("title" to "新"))

        assertEquals("新", SyncMerge.merge(older, newer)!!.winner.payload.getString("title"))
        assertEquals("新", SyncMerge.merge(newer, older)!!.winner.payload.getString("title"))
    }

    @Test
    fun `都没有时钟时退回 rev 比较`() {
        val low = envelope(rev = 1, hlc = null, payload = mapOf("title" to "旧"))
        val high = envelope(rev = 5, hlc = null, payload = mapOf("title" to "新"))

        assertEquals("新", SyncMerge.merge(low, high)!!.winner.payload.getString("title"))
    }

    /**
     * 有 HLC 的一方更新：**没有 HLC = 本机新建、还没上传过 = 一定更旧**。
     * 这条容易写反，写反的后果是本机一个未同步的新实体覆盖掉云端的成熟数据。
     */
    @Test
    fun `没有时钟的一方被视为更旧`() {
        val neverSynced = envelope(hlc = null, rev = 99, payload = mapOf("title" to "本机未同步"))
        val synced = envelope(hlc = Hlc(1000L, 0, "devB"), device = "devB", payload = mapOf("title" to "云端"))

        assertEquals(
            "云端那份应赢，尽管本机 rev 更大",
            "云端",
            SyncMerge.merge(neverSynced, synced)!!.winner.payload.getString("title"),
        )
    }

    // ---------------------------------------------------------------- 删除语义

    @Test
    fun `两边都是墓碑时取删除更晚的`() {
        val early = envelope(deletedAt = 1000L)
        val late = envelope(deletedAt = 2000L)

        assertEquals(2000L, SyncMerge.merge(early, late)!!.winner.deletedAt)
        assertEquals(2000L, SyncMerge.merge(late, early)!!.winner.deletedAt)
    }

    /**
     * **删除优先**：一边删、一边改 ⇒ 结果是被删。
     *
     * 若让"改"赢，用户在 A 删除的东西会因为在 B 上被改过而**复活** ——
     * 那是同步类产品最典型的 bad case。
     */
    @Test
    fun `一边删一边改时删除优先`() {
        val deleted = envelope(deletedAt = 5000L, hlc = Hlc(1000L, 0, "devA"))
        val edited = envelope(
            hlc = Hlc(9000L, 0, "devB"),
            device = "devB",
            payload = mapOf("title" to "在另一台改过"),
        )

        val result = SyncMerge.merge(deleted, edited)!!
        assertTrue("删除必须赢，即使改动的时钟更晚", result.winner.isTombstone)
        // 顺序无关
        assertTrue(SyncMerge.merge(edited, deleted)!!.winner.isTombstone)
    }

    @Test
    fun `删除优先的反方向也一样`() {
        val edited = envelope(hlc = Hlc(9000L, 0, "devB"), device = "devB")
        val deleted = envelope(deletedAt = 100L, hlc = Hlc(100L, 0, "devA"))

        assertTrue(SyncMerge.merge(edited, deleted)!!.winner.isTombstone)
    }

    // ---------------------------------------------------------------- 冲突判定

    @Test
    fun `时钟相同且内容相同时不算冲突`() {
        val a = envelope(hlc = Hlc(1000L, 0, "devA"), payload = mapOf("title" to "同"))
        val b = envelope(hlc = Hlc(1000L, 0, "devB"), device = "devB", payload = mapOf("title" to "同"))

        assertFalse(SyncMerge.merge(a, b)!!.conflict)
    }

    /**
     * **`cmp == 0` 分支的可达性**（这条测试本身就是一份文档）。
     *
     * 我原先写了一个"HLC 相同、deviceId 不同"的用例来触发冲突分支，跑挂了。
     * 原因：**HLC 的 deviceId 参与 tie-break**（见 [Hlc.compareTo]），
     * 所以两台设备在同一物理毫秒、同一计数下产生的 HLC **不相等** ——
     * 那正是"全序"的设计目的。
     *
     * 因此 `cmp == 0` 只可能是**完全相同的 HLC**（同一设备、同一时刻、同一计数），
     * 而同一设备的计数必然递增 ⇒ 它实际只在「两边都还没有 HLC」时可达，
     * 那时比较退回到 `rev`。
     *
     * 结论：**真·并发冲突在正常使用中几乎不会走到"时钟相等"这一步**，
     * 绝大多数情况由 HLC 全序直接裁决。这降低了冲突路径的风险，
     * 但**不表示冲突不存在** —— 用户看到的表现是"两边的修改有一边没生效"。
     */
    @Test
    fun `时钟相同分支只在都没有时钟时可达`() {
        // 都没有 HLC、rev 也相同 ⇒ 时钟比较返回 0
        val a = envelope(hlc = null, rev = 3, device = "devA", payload = mapOf("title" to "A"))
        val b = envelope(hlc = null, rev = 3, device = "devB", payload = mapOf("title" to "B"))

        val result = SyncMerge.merge(a, b)!!
        assertTrue("内容不同 ⇒ 判为冲突", result.conflict)
        // 关键：两端必须选出同一份，否则永不收敛
        val fromB = SyncMerge.merge(b, a)!!.winner
        assertEquals("两端结果必须一致", result.winner.deviceId, fromB.deviceId)
    }

    /** 不同设备的同物理同计数 HLC **不相等** —— 这是全序的保证。 */
    @Test
    fun `不同设备的同刻时钟不相等`() {
        val a = envelope(hlc = Hlc(1000L, 0, "devA"), device = "devA", payload = mapOf("title" to "A"))
        val b = envelope(hlc = Hlc(1000L, 0, "devB"), device = "devB", payload = mapOf("title" to "B"))

        val result = SyncMerge.merge(a, b)!!
        assertFalse("有 HLC 全序裁决，不判为冲突", result.conflict)
        assertEquals("devB 的时钟更大（字典序），它赢", "B", result.winner.payload.getString("title"))
    }

    /**
     * 冲突时**两端必须选出同一份**，否则两台设备各自留下不同结果，
     * 下次同步又会冲突 —— 数据永远收敛不了。
     */
    @Test
    fun `冲突时两端选出同一份`() {
        val a = envelope(hlc = Hlc(1000L, 0, "devA"), payload = mapOf("title" to "A"))
        val b = envelope(hlc = Hlc(1000L, 0, "devB"), device = "devB", payload = mapOf("title" to "B"))

        val fromA = SyncMerge.merge(a, b)!!.winner
        val fromB = SyncMerge.merge(b, a)!!.winner

        assertEquals("两端的结果必须一致（否则永不收敛）", fromA.deviceId, fromB.deviceId)
        assertEquals(fromA.payload.getString("title"), fromB.payload.getString("title"))
    }

    // ---------------------------------------------------------------- 逐字段合并

    /** 「改不同字段都保留」——这是选逐字段而不是整体 LWW 的兑现。 */
    @Test
    fun `逐字段合并保留两边独有的字段`() {
        val winner = envelope(payload = mapOf("title" to "新标题", "color" to "green"))
        val loser = envelope(payload = mapOf("title" to "旧标题", "note" to "输家独有的备注"))

        val merged = SyncMerge.mergePayloads(winner, loser)

        assertEquals("赢家的字段保留", "新标题", merged.getString("title"))
        assertEquals("赢家的独有字段保留", "green", merged.getString("color"))
        assertEquals("输家独有的字段也要补进来", "输家独有的备注", merged.getString("note"))
    }

    @Test
    fun `逐字段合并不产生多余键`() {
        val a = envelope(payload = mapOf("x" to 1))
        val b = envelope(payload = mapOf("y" to 2))

        val merged = SyncMerge.mergePayloads(a, b)

        assertEquals(2, merged.keys().asSequence().toList().size)
    }

    @Test
    fun `逐字段合并时空 payload 也能处理`() {
        val a = envelope(payload = emptyMap())
        val b = envelope(payload = mapOf("k" to "v"))

        assertEquals("v", SyncMerge.mergePayloads(a, b).getString("k"))
    }

    // ---------------------------------------------------------------- 上传判定

    @Test
    fun `云端没有时本机需要上传`() {
        assertTrue(SyncMerge.needsUpload(envelope(), null))
    }

    @Test
    fun `本机 rev 更大时需要上传`() {
        assertTrue(SyncMerge.needsUpload(envelope(rev = 5), envelope(rev = 3)))
    }

    @Test
    fun `云端 rev 更大时本机不需要上传`() {
        assertFalse(SyncMerge.needsUpload(envelope(rev = 3), envelope(rev = 5)))
    }

    @Test
    fun `rev 相同且内容相同时不需要上传`() {
        val a = envelope(rev = 3, payload = mapOf("title" to "同"))
        val b = envelope(rev = 3, payload = mapOf("title" to "同"))

        assertFalse(SyncMerge.needsUpload(a, b))
    }

    /** rev 巧合相同但内容不同：仍要上传，否则这次合并结果传不出去。 */
    @Test
    fun `rev 相同但内容不同时需要上传`() {
        val a = envelope(rev = 3, payload = mapOf("title" to "本机"))
        val b = envelope(rev = 3, payload = mapOf("title" to "云端"))

        assertTrue(SyncMerge.needsUpload(a, b))
    }

    /** 墓碑也要上传 —— 否则"删除"永远传不到另一台设备。 */
    @Test
    fun `墓碑需要上传`() {
        val tombstone = envelope(rev = 2, deletedAt = 1234L)
        val remote = envelope(rev = 1)

        assertTrue("删除必须能上传", SyncMerge.needsUpload(tombstone, remote))
    }

    // ---------------------------------------------------------------- 幂等

    /** 同一份合并两次结果不变（同步被重复触发时不能产生漂移）。 */
    @Test
    fun `合并是幂等的`() {
        val local = envelope(hlc = Hlc(1000L, 0, "devA"), payload = mapOf("title" to "本机"))
        val remote = envelope(hlc = Hlc(2000L, 0, "devB"), device = "devB", payload = mapOf("title" to "远端"))

        val first = SyncMerge.merge(local, remote)!!.winner
        val second = SyncMerge.merge(first, remote)!!.winner

        assertEquals(first.payload.toString(), second.payload.toString())
        assertEquals(first.rev, second.rev)
    }
}
