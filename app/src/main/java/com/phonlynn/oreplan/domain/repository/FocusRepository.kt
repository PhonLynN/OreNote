package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.FocusSession
import kotlinx.coroutines.flow.Flow

/**
 * 专注记录。
 *
 * 它是「数据打通」里原本缺的那一段：专注记录通过 [FocusSession.itemId]
 * 挂到具体的待办/目标上，于是「规划 → 待办 → 专注 → 统计复盘」这条链才连得起来。
 */
interface FocusRepository {

    fun observeAll(): Flow<List<FocusSession>>

    suspend fun getAll(): List<FocusSession>

    /** 某条待办/目标上的全部专注记录。 */
    suspend fun listByItem(itemId: String): List<FocusSession>

    suspend fun getById(id: String): FocusSession?

    suspend fun save(session: FocusSession)

    suspend fun delete(id: String)

    /**
     * **物理全量**（含已软删的）：云同步打包用。
     *
     * 为什么不复用 [getAll]：那个会过滤墓碑（S1 加的软删语义），
     * 而同步**必须**看到墓碑才能把"删除"传到另一台设备 ——
     * 用过滤后的集合，删除就永远传不出去。
     *
     * ⚠️ 只有两类调用方该用它：**同步打包**与**墓碑回收**。
     * 界面与业务查询一律用带过滤的那些方法。
     */
    suspend fun allIncludingDeleted(): List<FocusSession>
}
