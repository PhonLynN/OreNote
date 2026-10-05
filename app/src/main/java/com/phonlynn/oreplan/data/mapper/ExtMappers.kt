package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.data.local.entity.EntityExtEntity
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import java.time.Instant

/**
 * 抽屉行 ↔ 领域值。
 *
 * 抽出来是因为有**两个调用方**：仓储（日常读写）与备份服务（整体导出/恢复）。
 * 两处各写一份的话，迟早出现「仓储认得、备份不认得」这种不对称。
 */

/**
 * 表名认不出（更新的版本写下的、或手工改过库）→ 返回 null 跳过该行；
 * 内容是空对象也跳过（不留空壳）。
 */
internal fun EntityExtEntity.toRecord(): ExtRecord? {
    val owner = ExtOwner.fromTable(ownerTable) ?: return null
    val map = ExtCodec.decode(ext)
    if (map.isEmpty) return null
    return ExtRecord(
        owner = owner,
        ownerId = ownerId,
        map = map,
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

/**
 * [fallbackUpdatedAt] 只在记录自己没有时间戳时使用（新建抽屉的场景）。
 * 从备份恢复时，记录自带备份里的原始时刻，**不会被改写成「恢复那一刻」**。
 */
internal fun ExtRecord.toEntity(fallbackUpdatedAt: Long): EntityExtEntity = EntityExtEntity(
    ownerTable = owner.table,
    ownerId = ownerId,
    ext = ExtCodec.encode(map) ?: "{}",
    updatedAt = updatedAt?.toEpochMilli() ?: fallbackUpdatedAt,
)
