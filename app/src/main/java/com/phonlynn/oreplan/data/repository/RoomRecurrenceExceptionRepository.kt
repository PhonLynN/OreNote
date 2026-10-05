package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.RecurrenceExceptionDao
import com.phonlynn.oreplan.data.local.entity.RecurrenceExceptionEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRecurrenceExceptionRepository @Inject constructor(
    private val exceptionDao: RecurrenceExceptionDao,
) : RecurrenceExceptionRepository {

    override fun observeOf(itemId: String): Flow<List<RecurrenceException>> =
        exceptionDao.observeOf(itemId).map { rows -> rows.map(RecurrenceExceptionEntity::toDomain) }

    override fun observeAll(): Flow<List<RecurrenceException>> =
        exceptionDao.observeAll().map { rows -> rows.map(RecurrenceExceptionEntity::toDomain) }

    override suspend fun getAll(): List<RecurrenceException> =
        exceptionDao.findAll().map(RecurrenceExceptionEntity::toDomain)

    override suspend fun getOf(itemId: String): List<RecurrenceException> =
        exceptionDao.findOf(itemId).map(RecurrenceExceptionEntity::toDomain)

    override suspend fun upsert(exception: RecurrenceException) {
        exceptionDao.upsert(exception.toEntity())
    }

    override suspend fun deleteOccurrence(itemId: String, date: LocalDate) {
        exceptionDao.deleteOccurrence(itemId, date.toEpochDay().toInt())
    }

    override suspend fun deleteOf(itemId: String) {
        exceptionDao.deleteOf(itemId)
    }
}
