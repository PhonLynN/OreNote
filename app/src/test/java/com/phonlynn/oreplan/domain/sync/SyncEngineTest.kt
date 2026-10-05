package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.sync.crypto.SyncCrypto
import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import com.phonlynn.oreplan.domain.sync.r2.R2Exception
import com.phonlynn.oreplan.domain.sync.r2.Transport
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步引擎 —— **两台设备互相同步**的完整走查（S4 起改为表无关的通用引擎）。
 *
 * ## 为什么这个测试值钱
 *
 * 同步的 bug 几乎都只在"两端并发/交替操作"时才出现，而真机上要复现
 * 两个设备的状态机极难。这个测试用**一个假 R2 + 两份独立的本机状态**
 * 把那些场景静态地构造出来：谁先改、谁后改、谁删、谁没网。
 *
 * 加密与合并走的是**生产代码**（只有网络与"数据库"是假的），
 * 所以它验证的是真实链路，而不是一个被简化过的模型。
 *
 * ## 这里用什么替身
 *
 * [SyncTableAdapter] 是引擎与各表之间的唯一接口，所以测试只需要一个
 * **内存版的适配器**，就能把整条流程跑通 —— 这正是当初把差异抽成
 * 适配器的收益：同步流程不必依赖真实的八张表也能被验证。
 */
class SyncEngineTest {

    // ---------------------------------------------------------------- 假的世界

    /** 内存版 R2：key → 密文。 */
    private class FakeR2 {
        val objects = linkedMapOf<String, ByteArray>()
        var failWith: Int? = null

        fun listXml(prefix: String): ByteArray = buildString {
            append("<?xml version=\"1.0\"?><ListBucketResult>")
            objects.keys.filter { it.startsWith(prefix) }.forEach { k ->
                append("<Contents><Key>").append(k).append("</Key>")
                append("<ETag>\"etag\"</ETag><Size>1</Size></Contents>")
            }
            append("</ListBucketResult>")
        }.toByteArray()

        fun plaintextOf(key: String, masterKey: ByteArray): String =
            String(SyncCrypto.decrypt(objects.getValue(key), masterKey, aad = key.toByteArray()))
    }

    /** 假的网络层：解析 list-type=2 走真实的 XML 解析器。 */
    private fun transport(r2: FakeR2): Transport = object : Transport {
        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): R2Client.Response {
            // ⚠️ 失败注入必须**先于**列表分支：否则"凭据错误"的用例会因为
            // 列表照常返回而漏过去（我第一版就是这么写错的，测试因此假绿过）。
            r2.failWith?.let { throw R2Exception(it, "forced", "") }
            if (url.contains("list-type=2")) {
                val prefix = url.substringAfter("prefix=").substringBefore("&").replace("%2F", "/")
                return R2Client.Response(200, r2.listXml(prefix), null, 0)
            }
            val key = url.substringAfter("://").substringAfter("/").substringAfter("/")
            return when (method) {
                "PUT" -> {
                    r2.objects[key] = body ?: ByteArray(0)
                    R2Client.Response(200, ByteArray(0), "\"etag\"", 0)
                }
                "GET" -> r2.objects[key]?.let { R2Client.Response(200, it, "\"etag\"", it.size.toLong()) }
                    ?: R2Client.Response(404, ByteArray(0), null, 0)
                else -> R2Client.Response(405, ByteArray(0), null, 0)
            }
        }
    }

    /**
     * 内存版的条目表适配器。
     *
     * `items` 用最简的 JSON 形状（id + title），因为这个测试验证的是**流程**，
     * 字段映射本身由 `BackupCodec` 的既有测试覆盖。
     */
    private class MemoryAdapter(
        override val entity: SyncEntity = SyncEntity.ITEM,
        override val payloadKey: String = "item",
    ) : SyncTableAdapter {
        /** id → (title, rev, hlc, deviceId, deletedAt) */
        val rows = linkedMapOf<String, Row>()

        data class Row(
            val title: String,
            val rev: Long,
            val hlc: Hlc?,
            val deviceId: String?,
            val deletedAt: Long?,
        )

        val registry = TombstoneRegistry(EmptyExtRepo)

        override suspend fun readAll(): Map<String, SyncEnvelope> =
            rows.mapValues { (id, row) ->
                SyncEnvelope(
                    table = entity.table,
                    id = id,
                    rev = row.rev,
                    hlc = row.hlc?.encode(),
                    deviceId = row.deviceId,
                    deletedAt = row.deletedAt,
                    // 墓碑的行没有实体内容 —— 与真实实现一致
                    payload = if (row.deletedAt != null) {
                        JSONObject()
                    } else {
                        JSONObject().put(payloadKey, JSONObject().put("id", id).put("title", row.title))
                    },
                )
            }

        override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
            val title = winner.payload.optJSONObject(payloadKey)?.optString("title").orEmpty()
            rows[winner.id] = Row(
                title = if (winner.isTombstone) rows[winner.id]?.title.orEmpty() else title,
                rev = winner.rev,
                hlc = winner.hlcValue,
                deviceId = winner.deviceId,
                deletedAt = winner.deletedAt,
            )
        }

        /** 模拟一次"本机修改"：盖新时钟、rev+1。 */
        fun modify(id: String, title: String, clock: SyncClock) {
            val previous = rows[id]
            rows[id] = Row(
                title = title,
                rev = (previous?.rev ?: 0L) + 1,
                hlc = clock.tick(),
                deviceId = clock.deviceId,
                deletedAt = null,
            )
        }

        /** 模拟一次"本机删除"。 */
        suspend fun delete(id: String, clock: SyncClock) {
            val previous = rows[id]
            rows[id] = Row(
                title = previous?.title.orEmpty(),
                rev = (previous?.rev ?: 0L) + 1,
                hlc = clock.tick(),
                deviceId = clock.deviceId,
                deletedAt = 1_700_000_000_000L,
            )
            registry.markDeleted(entity, id)
        }

        /** 界面上能不能看见这条（走墓碑过滤，与真实仓储出口一致）。 */
        fun visible(id: String): String? =
            registry.filterOne(entity, rows[id]?.takeIf { it.deletedAt == null }?.title, id)
    }

    private object EmptyExtRepo : com.phonlynn.oreplan.domain.repository.EntityExtRepository {
        override suspend fun get(
            owner: com.phonlynn.oreplan.domain.model.ExtOwner,
            ownerId: String,
        ) = com.phonlynn.oreplan.domain.model.ExtMap.EMPTY

        override suspend fun listByTable(owner: com.phonlynn.oreplan.domain.model.ExtOwner) =
            emptyList<com.phonlynn.oreplan.domain.model.ExtRecord>()

        override fun observeAll() =
            kotlinx.coroutines.flow.flowOf(emptyList<com.phonlynn.oreplan.domain.model.ExtRecord>())

        override suspend fun getAll() = emptyList<com.phonlynn.oreplan.domain.model.ExtRecord>()

        override suspend fun set(
            owner: com.phonlynn.oreplan.domain.model.ExtOwner,
            ownerId: String,
            map: com.phonlynn.oreplan.domain.model.ExtMap,
        ) = Unit

        override suspend fun update(
            owner: com.phonlynn.oreplan.domain.model.ExtOwner,
            ownerId: String,
            block: (com.phonlynn.oreplan.domain.model.ExtMap) -> com.phonlynn.oreplan.domain.model.ExtMap,
        ) = block(com.phonlynn.oreplan.domain.model.ExtMap.EMPTY)

        override suspend fun remove(
            owner: com.phonlynn.oreplan.domain.model.ExtOwner,
            ownerId: String,
        ) = Unit
    }

    private val masterKey = ByteArray(32) { it.toByte() }
    private val config = R2Config(
        accountId = "acc",
        bucket = "bucket",
        accessKeyId = "ak",
        secretAccessKey = "sk",
        keyPrefix = "orenote/",
    )

    private fun engine(adapter: SyncTableAdapter) = SyncEngine(
        adapters = listOf(adapter),
        stampWriter = SyncStampWriter(EmptyExtRepo, SyncClock()),
        tombstones = (adapter as? MemoryAdapter)?.registry ?: TombstoneRegistry(EmptyExtRepo),
    )

    private fun clockOf(device: String) = SyncClock().apply { restore(device) }

    // ---------------------------------------------------------------- 用例

    /**
     * 最核心的一条：设备 A 的改动能出现在设备 B 上。
     * 这是整个功能存在的理由。
     */
    @Test
    fun `本机修改能同步到另一台设备`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        a.modify("t1", "在 A 上写的", clockOf("devA"))
        val outcomeA = engine(a).syncAll(client, config, masterKey)
        assertTrue("A 应上传：$outcomeA", outcomeA.uploaded >= 1)

        val b = MemoryAdapter()
        val outcomeB = engine(b).syncAll(client, config, masterKey)

        assertEquals("B 应下载 1 条：$outcomeB", 1, outcomeB.downloaded)
        assertEquals("在 A 上写的", b.visible("t1"))
    }

    /**
     * **服务端看不到明文** —— 这是"客户端加密"这个卖点的验证。
     */
    @Test
    fun `云端存的是密文而非明文`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        a.modify("t1", "SECRET-TITLE-期末复习", clockOf("devA"))
        engine(a).syncAll(client, config, masterKey)

        val stored = r2.objects.values.firstOrNull()
        assertNotNull("应该有一个对象", stored)
        val asText = String(stored!!, Charsets.ISO_8859_1)
        assertFalse("桶里的字节不该含明文标题", asText.contains("SECRET-TITLE"))
        assertFalse("也不该含字段名", asText.contains("\"title\""))
        val key = r2.objects.keys.first()
        assertTrue("解密后应含标题", r2.plaintextOf(key, masterKey).contains("SECRET-TITLE"))
    }

    /** 删除也要传播 —— 在 A 删了，B 上必须也看不见。 */
    @Test
    fun `删除能同步到另一台设备`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        val clockA = clockOf("devA")
        a.modify("t1", "待删除", clockA)
        engine(a).syncAll(client, config, masterKey)

        val b = MemoryAdapter()
        engine(b).syncAll(client, config, masterKey)
        assertEquals("待删除", b.visible("t1"))

        a.delete("t1", clockA)
        engine(a).syncAll(client, config, masterKey)

        val outcome = engine(b).syncAll(client, config, masterKey)
        assertEquals("B 应记录一次远端删除", 1, outcome.deletedLocally)
        assertNull("B 上不该再看到这条", b.visible("t1"))
        assertTrue("B 的登记处应记住它被删了", b.registry.isDeleted(SyncEntity.ITEM, "t1"))
    }

    /**
     * **一边删、一边改 ⇒ 删优先**（端到端验证，不只是合并函数层面）。
     * 若这条挂了，用户会遇到"删掉的东西自己回来了"。
     */
    @Test
    fun `一端删除另一端修改时删除胜出`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        val b = MemoryAdapter()
        val clockA = clockOf("devA")
        val clockB = clockOf("devB")

        a.modify("t1", "原始", clockA)
        engine(a).syncAll(client, config, masterKey)
        engine(b).syncAll(client, config, masterKey)
        assertEquals("原始", b.visible("t1"))

        // A 删除；B 在"不知情"的情况下改了它（模拟两台设备离线期间的并发操作）
        a.delete("t1", clockA)
        b.modify("t1", "在 B 上改过", clockB)

        engine(a).syncAll(client, config, masterKey)
        val outcomeB = engine(b).syncAll(client, config, masterKey)

        assertTrue("B 应收到删除", outcomeB.deletedLocally >= 1)
        assertNull("B 上不该复活", b.visible("t1"))
    }

    /**
     * 幂等：同步两次不该产生重复上传或数据漂移。
     *
     * 这条很重要 —— 同步会被反复触发（切后台、手动点），
     * 若每次都不一致，用户会看到"同步一直在传东西"。
     */
    @Test
    fun `重复同步是幂等的`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        a.modify("t1", "内容", clockOf("devA"))
        val e = engine(a)
        e.syncAll(client, config, masterKey)
        val second = e.syncAll(client, config, masterKey)

        assertEquals("第二次不该再上传", 0, second.uploaded)
        assertEquals("第二次不该下载", 0, second.downloaded)
        assertEquals("也不该有冲突", 0, second.conflicts)
        assertEquals("应报告已是最新", "已是最新", second.summary())
    }

    /** 两台设备交替改，最终必须收敛到同一份内容。 */
    @Test
    fun `两端交替修改后收敛`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        val b = MemoryAdapter()
        val clockA = clockOf("devA")
        val clockB = clockOf("devB")
        val ea = engine(a)
        val eb = engine(b)

        a.modify("t1", "A 的第一版", clockA)
        ea.syncAll(client, config, masterKey)
        eb.syncAll(client, config, masterKey)
        assertEquals("A 的第一版", b.visible("t1"))

        b.modify("t1", "B 改的", clockB)
        eb.syncAll(client, config, masterKey)
        ea.syncAll(client, config, masterKey)
        assertEquals("A 应看到 B 的改动", "B 改的", a.visible("t1"))

        // 最后再各同步一次，两边完全一致
        ea.syncAll(client, config, masterKey)
        eb.syncAll(client, config, masterKey)
        assertEquals("最终必须收敛", a.visible("t1"), b.visible("t1"))
    }

    /** 凭据错误要给出可操作的原因，而不是一个状态码。 */
    @Test
    fun `凭据错误给出可读原因`() = runTest {
        val r2 = FakeR2().apply { failWith = 403 }
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        a.modify("t1", "x", clockOf("devA"))
        val outcome = engine(a).syncAll(client, config, masterKey)

        assertFalse("应失败", outcome.isSuccess)
        assertTrue("要提示检查凭据，实际：${outcome.error}", outcome.error!!.contains("凭据"))
    }

    /**
     * **一个坏对象不该阻塞整次同步**。
     *
     * 历史脏数据、旧版本写入、或上传中断都可能留下解不开的对象。
     * 若整次同步因此失败，用户会永久卡住 —— 那是最糟的失败模式。
     */
    @Test
    fun `解不开的坏对象被跳过而不是中断同步`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        // 用**别的密钥**加密的对象（模拟换过密钥/损坏）
        val wrongKey = ByteArray(32) { (it + 99).toByte() }
        val badKey = config.key(SyncEntity.ITEM.table, "bad.bin")
        r2.objects[badKey] = SyncCrypto.encrypt("not valid json".toByteArray(), wrongKey, aad = badKey.toByteArray())

        val a = MemoryAdapter()
        a.modify("good", "正常条目", clockOf("devA"))
        val outcome = engine(a).syncAll(client, config, masterKey)

        assertTrue("整次同步应成功（坏对象只被跳过）", outcome.isSuccess)
        assertTrue("正常那条应上传成功", outcome.uploaded >= 1)
        assertTrue("应有跳过计数", outcome.skipped >= 1)
    }

    /** 空库 + 空云端：不该报错，也不该报告"同步了东西"。 */
    @Test
    fun `两边都空时是安全的空操作`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val outcome = engine(MemoryAdapter()).syncAll(client, config, masterKey)

        assertTrue(outcome.isSuccess)
        assertEquals(0, outcome.uploaded)
        assertEquals(0, outcome.downloaded)
        assertTrue(outcome.didNothing)
    }

    /**
     * 对象名的口径：`<keyPrefix>/<表名>/<id>.bin`。
     *
     * 刻意把**实际键**写死在断言里（而不是用 `config.key(...)` 现算）——
     * 用同一个函数算出来再比对等于没测：改了 `key()` 的实现，两边一起变，
     * 测试照样绿，但云端已有对象的路径就全错了（表现为"上传了却拉不到"）。
     */
    @Test
    fun `云端对象路径符合约定`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val a = MemoryAdapter()
        a.modify("abc", "x", clockOf("devA"))
        engine(a).syncAll(client, config, masterKey)

        assertEquals(
            "实际键：${r2.objects.keys}",
            listOf("orenote/items/abc.bin"),
            r2.objects.keys.toList(),
        )
    }

    // ---------------------------------------------------------------- 多表

    /**
     * 多表同步：所有表都要被跑到，结果累加。
     *
     * 这条守着一件容易忘的事：**新加一张表时忘了接进 SyncModule**，
     * 那张表的数据就静默不同步 —— 界面上完全看不出来。
     */
    @Test
    fun `多张表都会被同步且结果累加`() = runTest {
        val r2 = FakeR2()
        val client = R2Client(config, transport(r2))

        val items = MemoryAdapter(SyncEntity.ITEM, "item")
        val cards = MemoryAdapter(SyncEntity.BOARD_CARD, "boardCard")
        items.modify("i1", "条目", clockOf("devA"))
        cards.modify("c1", "卡片", clockOf("devA"))

        val multi = SyncEngine(
            adapters = listOf(items, cards),
            stampWriter = SyncStampWriter(EmptyExtRepo, SyncClock()),
            tombstones = items.registry,
        )
        val outcome = multi.syncAll(client, config, masterKey)

        assertEquals("两张表各上传 1 条", 2, outcome.uploaded)
        assertTrue(
            "两张表的对象都应在云端：${r2.objects.keys}",
            r2.objects.keys.any { it.contains("items/i1") } && r2.objects.keys.any { it.contains("board_cards/c1") },
        )
    }

    /**
     * 认证类失败要**提前中断**，不要对剩下每张表重复失败。
     *
     * 否则用户要等八次超时才看到错误，而且日志里八条同样的失败会掩盖真正的原因。
     */
    @Test
    fun `认证失败时提前中断多表同步`() = runTest {
        val r2 = FakeR2().apply { failWith = 403 }
        val client = R2Client(config, transport(r2))

        val tables = SyncEntity.entries.map { MemoryAdapter(it, it.table) }
        val multi = SyncEngine(
            adapters = tables,
            stampWriter = SyncStampWriter(EmptyExtRepo, SyncClock()),
            tombstones = tables.first().registry,
        )
        val outcome = multi.syncAll(client, config, masterKey)

        assertFalse(outcome.isSuccess)
        assertTrue("要提示检查凭据", outcome.error!!.contains("凭据"))
    }

    /** 累加时任一表失败就让整体失败 —— 静默吞掉一张表的失败会让人以为同步成功了。 */
    @Test
    fun `任一表失败则整体失败`() {
        val ok = SyncOutcome(uploaded = 3)
        val failed = SyncOutcome(error = "存储桶不存在")

        assertEquals("存储桶不存在", (ok + failed).error)
        assertEquals("存储桶不存在", (failed + ok).error)
        assertEquals(3, (ok + failed).uploaded)
    }
}
