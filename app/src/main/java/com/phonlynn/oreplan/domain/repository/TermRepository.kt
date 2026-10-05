package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.Term
import kotlinx.coroutines.flow.Flow

interface TermRepository {

    fun observeActiveTerm(): Flow<Term?>

    fun observeAll(): Flow<List<Term>>

    suspend fun getActiveTerm(): Term?

    /** 写入学期。若 [Term.isActive] 为真，会先取消其他学期的激活状态。 */
    suspend fun upsert(term: Term)

    suspend fun activate(termId: String)

    suspend fun delete(termId: String)

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
    suspend fun allTermsIncludingDeleted(): List<Term>
}
