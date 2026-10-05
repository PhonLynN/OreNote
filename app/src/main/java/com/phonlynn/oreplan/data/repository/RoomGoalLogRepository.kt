package com.phonlynn.oreplan.data.repository

import com.phonlynn.oreplan.data.local.dao.DailyReviewDao
import com.phonlynn.oreplan.data.local.dao.HabitLogDao
import com.phonlynn.oreplan.data.local.dao.QuantityLogDao
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.DailyReview
import com.phonlynn.oreplan.domain.model.HabitLog
import com.phonlynn.oreplan.domain.model.QuantityLog
import com.phonlynn.oreplan.domain.repository.GoalLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class RoomGoalLogRepository @Inject constructor(
    private val habitLogDao: HabitLogDao,
    private val quantityLogDao: QuantityLogDao,
    private val dailyReviewDao: DailyReviewDao,
) : GoalLogRepository {

    override fun observeHabitLogs(itemId: String): Flow<List<HabitLog>> =
        habitLogDao.observeByItem(itemId).map { list -> list.map { it.toDomain() } }

    override fun observeAllHabitLogs(): Flow<List<HabitLog>> =
        habitLogDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun toggleHabit(itemId: String, epochDay: Int) {
        val exists = habitLogDao.listAll().any { it.itemId == itemId && it.epochDay == epochDay }
        if (exists) {
            habitLogDao.delete(itemId, epochDay)
        } else {
            habitLogDao.insert(HabitLog(itemId = itemId, epochDay = epochDay).toEntity())
        }
    }

    override fun observeQuantityLogs(itemId: String): Flow<List<QuantityLog>> =
        quantityLogDao.observeByItem(itemId).map { list -> list.map { it.toDomain() } }

    override suspend fun addQuantityLog(log: QuantityLog) {
        quantityLogDao.insert(log.toEntity())
    }

    override suspend fun deleteQuantityLog(id: String) {
        quantityLogDao.deleteById(id)
    }

    override fun observeReview(epochDay: Int): Flow<DailyReview?> =
        dailyReviewDao.observeByDay(epochDay).map { it?.toDomain() }

    override suspend fun saveReview(epochDay: Int, text: String) {
        dailyReviewDao.upsert(
            DailyReview(
                epochDay = epochDay,
                text = text,
                updatedAt = Instant.now(),
            ).toEntity(),
        )
    }
}
