package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.ChecklistEntry
import kotlinx.coroutines.flow.Flow

/**
 * 备忘清单仓储。
 *
 * [observeAll] 不是顺手加的：条目列表与今日任务要显示「清单 2/4」的进度徽标，
 * 一条条目一个 Flow 会让界面层出现 N 个订阅；清单总量很小（几百行量级），
 * 一次取全表再在内存里分组更省事也更稳。
 */
interface ChecklistRepository {

    fun observeAll(): Flow<List<ChecklistEntry>>

    fun observeOf(itemId: String): Flow<List<ChecklistEntry>>

    suspend fun getOf(itemId: String): List<ChecklistEntry>

    suspend fun getAll(): List<ChecklistEntry>

    suspend fun upsert(entry: ChecklistEntry)

    suspend fun upsertAll(entries: List<ChecklistEntry>)

    suspend fun delete(id: String)

    suspend fun deleteOf(itemId: String)

    /** [keepIds] 为空即全删。 */
    suspend fun retainOnly(itemId: String, keepIds: List<String>)
}
