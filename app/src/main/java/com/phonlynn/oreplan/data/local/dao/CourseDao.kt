package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.CourseEntity
import com.phonlynn.oreplan.data.local.entity.CourseSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {

    @Query("SELECT * FROM courses ORDER BY name")
    fun observeCourses(): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses ORDER BY name")
    suspend fun findCourses(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun findCourse(id: String): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourse(course: CourseEntity)

    /** 外键级联会一并删掉该课程的全部上课安排。 */
    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourse(id: String)

    @Query("SELECT * FROM course_sessions ORDER BY dayOfWeek, startMinuteOfDay")
    fun observeSessions(): Flow<List<CourseSessionEntity>>

    @Query("SELECT * FROM course_sessions ORDER BY dayOfWeek, startMinuteOfDay")
    suspend fun findSessions(): List<CourseSessionEntity>

    @Query("SELECT * FROM course_sessions WHERE courseId = :courseId ORDER BY dayOfWeek, startMinuteOfDay")
    fun observeSessionsOf(courseId: String): Flow<List<CourseSessionEntity>>

    @Query("SELECT * FROM course_sessions WHERE courseId = :courseId ORDER BY dayOfWeek, startMinuteOfDay")
    suspend fun findSessionsOf(courseId: String): List<CourseSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: CourseSessionEntity)

    @Query("DELETE FROM course_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    /** 删课程时显式清掉它的上课安排。外键级联也会做同一件事，但显式更不依赖 PRAGMA 状态。 */
    @Query("DELETE FROM course_sessions WHERE courseId = :courseId")
    suspend fun deleteSessionsOf(courseId: String)

    @Query("DELETE FROM course_sessions")
    suspend fun clearAllSessions()

    @Query("DELETE FROM courses")
    suspend fun clearAllCourses()

    /** 物理全量（**不过滤**）：云同步打包用。见 SyncTableAdapter 的纪律。 */
    @Query("SELECT * FROM courses")
    suspend fun listAllCourses(): List<CourseEntity>
}
