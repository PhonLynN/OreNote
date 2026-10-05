package com.phonlynn.oreplan.domain.sync

import org.json.JSONObject

/**
 * 一张**可同步的表**接入引擎所需的全部能力。
 *
 * ## 为什么要有这层
 *
 * S3 的引擎是给 `items` 写死的。铺到其余七张表时若把引擎复制七遍，
 * 就会有**八份"拉清单 → 合并 → 写回 → 上传"的流程** —— 而流程里
 * 每一条纪律（先合并再上传、本机没有时必须更新、远端元数据要覆盖本机时钟、
 * 墓碑要同步登记处）都是踩过坑才定下来的。复制八份等于把这八个坑埋八遍。
 *
 * 所以：**流程留在引擎里只有一份，各表的差异抽到这个接口**。
 *
 * ## 实现者的责任（三条，都有测试守着）
 *
 * 1. [readAllIncludingDeleted] 必须返回**物理全量**（含已软删的）。
 *    只返回可见的会导致墓碑传不出去、删除在另一端失效。
 * 2. [applyToLocal] 写完后，**同步元数据要以远端那份为准**。
 *    仓储的写方法会盖本机时钟，所以实现里必须最后调 `writeFromRemote` 覆盖回去，
 *    否则本机会显得"刚改过"，下轮又把它传回去 —— 来回震荡、永不收敛。
 * 3. [applyToLocal] 遇到墓碑时**不动业务行**（软删语义），但引擎会负责更新
 *    内存登记处；实现者不要在墓碑分支里删行。
 */
interface SyncTableAdapter {

    val entity: SyncEntity

    /**
     * **一次读出该表的物理全量**（含墓碑），并把每条打包成信封。
     *
     * ## 为什么是"一次读全量"而不是"按 id 逐条读"
     *
     * 逐条读的接口看起来更干净，但那样每张表都要在仓储上开一个"按 id 取物理行"
     * 的方法（要绕开墓碑过滤，语义特殊），而且同步一条要读一次库 ——
     * 1000 条就是 1000 次查询。个人数据量级下"一次全读进内存"完全可行，
     * 且实现简单得多。
     *
     * 返回 `Map<id, SyncEnvelope>`；读不出来的条目直接不出现在 map 里
     * （脏数据不该让整张表同步失败）。
     */
    suspend fun readAll(): Map<String, SyncEnvelope>

    /**
     * 把合并结果落到本机。
     *
     * @param existsLocally 本机是否已有这条（决定 insert 还是 update）
     */
    suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean)

    /**
     * payload 里实体挂在哪个键下。
     *
     * 与 `BackupCodec` 的约定一致（如 items 表用 `"item"`）——
     * 这个键让信封能自描述、也方便调试时肉眼看懂。
     */
    val payloadKey: String
}

/**
 * 把实体 JSON 包成信封 payload 的公共helper。
 *
 * 八张表都用同一个形状：`{ "<key>": { ...实体字段 } }`，
 * 所以包/解包不该各写一遍。
 */
internal fun wrapPayload(key: String, body: JSONObject): JSONObject =
    JSONObject().apply { put(key, body) }

/** 从信封里取出实体 JSON。取不到返回 null（引擎据此跳过，而不是崩）。 */
internal fun SyncEnvelope.entityBody(key: String): JSONObject? = payload.optJSONObject(key)
