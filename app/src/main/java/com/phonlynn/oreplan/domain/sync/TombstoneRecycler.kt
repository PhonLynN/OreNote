package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 墓碑回收：把**足够老、且已经传出去**的墓碑真正删掉。
 *
 * ## 为什么必须回收
 *
 * 墓碑是"这条被删了"的记录。它不能让同步把删除误判成"新增"，
 * 所以删除时**不能直接删行** —— 得留个标记。
 *
 * 但墓碑不能永远留着：
 *  · 数据库会无限增长（用户删过的东西越多，墓碑越多）；
 *  · 每次同步都要为它们做一次往返判断。
 *
 * ## 为什么是 90 天
 *
 * 回收的前提是"所有设备都已经知道这条被删了"。而"所有设备都知道"这件事
 * 无法精确判定（一台设备可能半年没开）。所以只能取一个**足够长的保守窗口**：
 * 90 天内没同步过的设备，本来就会错过很多删除 —— 那时用户应当做的是
 * 重新同步或从备份恢复，而不是期待墓碑还在。
 *
 * 缩短这个窗口会让"长期离线的设备复活已删数据"变得容易；拉长只是多占一点空间。
 * 所以宁可长：**90 天是保守值，不是精确值**。
 *
 * ## ⚠️ 只回收"已经上传成功"的墓碑
 *
 * 判据是存在 HLC（`sync.hlc`）—— 它只在一次成功的同步之后才会被写上。
 * 没有 HLC 的墓碑意味着**云端还不知道这条被删了**，
 * 此时删掉它 = 那台设备永远不会知道要删 → 删除丢失。
 *
 * 这一条是回收功能里最容易写错、且错了最难发现的地方（数据不会立刻出问题，
 * 而是几天后在某台设备上"复活"）。所以单独成一个判据并配测试。
 */
@Singleton
class TombstoneRecycler @Inject constructor(
    private val extRepo: EntityExtRepository,
    private val tombstones: TombstoneRegistry,
) {

    data class Result(val scanned: Int = 0, val recycled: Int = 0, val keptTooYoung: Int = 0, val keptUnsynced: Int = 0)

    /**
     * 扫描并回收。
     *
     * @param now 当前时刻（可注入，便于测试"90 天后"而不必真的等）
     * @param retentionMillis 保留窗口，默认 90 天
     * @param dryRun true = 只统计不删（调试与验证用）
     */
    suspend fun recycle(
        now: Long,
        retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
        dryRun: Boolean = false,
    ): Result {
        var scanned = 0
        var recycled = 0
        var keptTooYoung = 0
        var keptUnsynced = 0
        val recycledIds = ArrayList<Pair<SyncEntity, String>>()

        for (entity in SyncEntity.entries) {
            val records = runCatching { extRepo.listByTable(entity.ext) }.getOrDefault(emptyList())
            for (record in records) {
                val meta = SyncMeta.from(record.map)
                if (!meta.isDeleted) continue
                scanned++

                // ① 没同步出去过 → 绝不能删（云端还不知道要删）
                if (meta.hlc == null) {
                    keptUnsynced++
                    continue
                }
                // ② 还没够老 → 留着（别的设备可能还没见过它）
                val deletedAt = meta.deletedAt ?: 0L
                if (now - deletedAt < retentionMillis) {
                    keptTooYoung++
                    continue
                }

                // ③ 可以回收：清掉抽屉里的同步元数据（业务行在 S1 已不存在）
                if (!dryRun) {
                    runCatching { stampClear(entity, record.ownerId) }
                }
                recycledIds += entity to record.ownerId
                recycled++
            }
        }

        // 内存登记处也要同步 —— 否则被回收的 id 会一直留在里面，
        // 而业务行早已不存在，那些条目就成了"永远不会被清理的内存垃圾"。
        if (!dryRun) {
            recycledIds.forEach { (entity, id) -> tombstones.unmarkDeleted(entity, id) }
        }

        return Result(
            scanned = scanned,
            recycled = recycled,
            keptTooYoung = keptTooYoung,
            keptUnsynced = keptUnsynced,
        )
    }

    private suspend fun stampClear(entity: SyncEntity, id: String) {
        extRepo.update(entity.ext, id) { SyncMeta.EMPTY.clearedFrom(it) }
    }

    companion object {
        /**
         * 90 天。见类注释：这是**保守值** —— 缩短会让长期离线设备复活已删数据，
         * 拉长只是多占空间，所以宁可长。
         */
        const val DEFAULT_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}
