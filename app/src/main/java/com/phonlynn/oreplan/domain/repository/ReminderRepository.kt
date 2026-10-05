package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.Reminder
import kotlinx.coroutines.flow.Flow

interface ReminderRepository {

    fun observeOf(itemId: String): Flow<List<Reminder>>

    /** 全部启用中的提醒，按触发时间升序。调度器用这个重建闹钟。 */
    fun observeEnabled(): Flow<List<Reminder>>

    suspend fun getEnabled(): List<Reminder>

    suspend fun upsert(reminder: Reminder)

    suspend fun delete(reminderId: String)

    suspend fun deleteOf(itemId: String)
}
