package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.ReminderDao
import com.phonlynn.oreplan.data.local.entity.ReminderEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomReminderRepository @Inject constructor(
    private val reminderDao: ReminderDao,
) : ReminderRepository {

    override fun observeOf(itemId: String): Flow<List<Reminder>> =
        reminderDao.observeOf(itemId).map { rows -> rows.map(ReminderEntity::toDomain) }

    override fun observeEnabled(): Flow<List<Reminder>> =
        reminderDao.observeEnabled().map { rows -> rows.map(ReminderEntity::toDomain) }

    override suspend fun getEnabled(): List<Reminder> =
        reminderDao.findEnabled().map(ReminderEntity::toDomain)

    override suspend fun upsert(reminder: Reminder) {
        reminderDao.upsert(reminder.toEntity())
    }

    override suspend fun delete(reminderId: String) {
        reminderDao.deleteById(reminderId)
    }

    override suspend fun deleteOf(itemId: String) {
        reminderDao.deleteOf(itemId)
    }
}
