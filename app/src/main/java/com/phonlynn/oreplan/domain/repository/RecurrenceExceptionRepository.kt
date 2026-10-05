package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.RecurrenceException
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface RecurrenceExceptionRepository {

    fun observeOf(itemId: String): Flow<List<RecurrenceException>>

    /** 全部例外。批量展开议程时用，避免逐条查库。 */
    fun observeAll(): Flow<List<RecurrenceException>>

    suspend fun getAll(): List<RecurrenceException>

    suspend fun getOf(itemId: String): List<RecurrenceException>

    suspend fun upsert(exception: RecurrenceException)

    /** 取消「这一天是例外」，让它回到规则的正常发生。 */
    suspend fun deleteOccurrence(itemId: String, date: LocalDate)

    suspend fun deleteOf(itemId: String)
}
