package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.DailyReview
import com.phonlynn.oreplan.domain.model.HabitLog
import com.phonlynn.oreplan.domain.model.QuantityLog
import kotlinx.coroutines.flow.Flow

/** 目标附加数据：习惯打卡、数量记录、每日复盘。 */
interface GoalLogRepository {

    fun observeHabitLogs(itemId: String): Flow<List<HabitLog>>

    fun observeAllHabitLogs(): Flow<List<HabitLog>>

    /** 打卡/取消打卡。 */
    suspend fun toggleHabit(itemId: String, epochDay: Int)

    fun observeQuantityLogs(itemId: String): Flow<List<QuantityLog>>

    suspend fun addQuantityLog(log: QuantityLog)

    suspend fun deleteQuantityLog(id: String)

    fun observeReview(epochDay: Int): Flow<DailyReview?>

    suspend fun saveReview(epochDay: Int, text: String)
}
