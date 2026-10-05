package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.model.ExtOwner
import com.phonlynn.oreplan.domain.model.ExtRecord
import kotlinx.coroutines.flow.Flow

/**
 * 扩展抽屉的入口。
 *
 * 新功能要给某个实体挂附属字段时，**只走这里**，不要自己去碰 `entity_ext` 表，
 * 也不要为了一个附属字段去给老表加列。
 *
 * 用法示例（伪代码）：
 * ```
 * // 记一次天气
 * extRepo.update(ExtOwner.DAILY_REVIEW, epochDay.toString()) {
 *     it.putText("weather.sky", "晴").putNum("weather.temp", 26.0)
 * }
 * ```
 */
interface EntityExtRepository {

    /** 读一个实体的抽屉。没有就是空抽屉（不返回 null，省掉调用方的空判断）。 */
    suspend fun get(owner: ExtOwner, ownerId: String): ExtMap

    /** 批量读某张表下所有抽屉行。 */
    suspend fun listByTable(owner: ExtOwner): List<ExtRecord>

    /** 全部抽屉行（备份、AI 全量上下文用），按表名与 id 稳定排序。 */
    fun observeAll(): Flow<List<ExtRecord>>

    suspend fun getAll(): List<ExtRecord>

    /** 整体写入。空抽屉 = 删除该行（不留空壳）。 */
    suspend fun set(owner: ExtOwner, ownerId: String, map: ExtMap)

    /**
     * 读-改-写，**这是推荐的改法**：不会覆盖掉别的功能写进去的键。
     * 返回写入后的内容。
     */
    suspend fun update(
        owner: ExtOwner,
        ownerId: String,
        block: (ExtMap) -> ExtMap,
    ): ExtMap

    /** 删除一个实体的抽屉（删除业务行时应当一并调用）。 */
    suspend fun remove(owner: ExtOwner, ownerId: String)
}
