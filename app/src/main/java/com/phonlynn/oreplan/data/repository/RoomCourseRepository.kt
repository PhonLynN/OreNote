package com.phonlynn.oreplan.data.repository

import androidx.room.withTransaction
import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.dao.CourseDao
import com.phonlynn.oreplan.data.local.entity.CourseEntity
import com.phonlynn.oreplan.data.local.entity.CourseSessionEntity
import com.phonlynn.oreplan.data.mapper.toDomain
import com.phonlynn.oreplan.data.mapper.toEntity
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.TombstoneRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomCourseRepository @Inject constructor(
    private val database: AppDatabase,
    private val courseDao: CourseDao,
    private val tombstones: TombstoneRegistry,
    private val stampWriter: SyncStampWriter,
) : CourseRepository {

    /**
     * 读取过滤器。
     *
     * ⚠️ 上课安排（`course_sessions`）是**子表、仍硬删**（只给顶层实体加墓碑），
     * 但同步场景下会出现"远端删了课程、本机只打了墓碑、安排行还在"的中间态。
     * 所以安排的读取出口也要**按它所属课程**过滤，否则课表上会留下一块
     * 找不到课程名的孤儿色块（正是 deleteCourse 注释里说的那种脏数据）。
     */
    private fun List<CourseEntity>.visible(): List<Course> =
        tombstones.filter(SyncEntity.COURSE, this) { it.id }.map(CourseEntity::toDomain)

    private fun List<CourseSessionEntity>.visibleSessions(): List<CourseSession> =
        tombstones.filter(SyncEntity.COURSE, this) { it.courseId }
            .map(CourseSessionEntity::toDomain)

    override fun observeCourses(): Flow<List<Course>> =
        tombstones.observing(SyncEntity.COURSE, courseDao.observeCourses())
            .map { rows -> rows.visible() }

    override suspend fun getCourses(): List<Course> =
        courseDao.findCourses().visible()

    override fun observeSessions(): Flow<List<CourseSession>> =
        tombstones.observing(SyncEntity.COURSE, courseDao.observeSessions())
            .map { rows -> rows.visibleSessions() }

    override suspend fun getSessions(): List<CourseSession> =
        courseDao.findSessions().visibleSessions()

    override fun observeSessionsOf(courseId: String): Flow<List<CourseSession>> =
        tombstones.observing(SyncEntity.COURSE, courseDao.observeSessionsOf(courseId))
            .map { rows -> rows.visibleSessions() }

    override suspend fun getSessionsOf(courseId: String): List<CourseSession> =
        courseDao.findSessionsOf(courseId).visibleSessions()

    override suspend fun getCourse(id: String): Course? =
        tombstones.filterOne(SyncEntity.COURSE, courseDao.findCourse(id), id)?.toDomain()

        /** 物理全量（含墓碑）。同步打包用 —— 见接口注释。 */
    override suspend fun allCoursesIncludingDeleted(): List<Course> =
        courseDao.listAllCourses().map(CourseEntity::toDomain)

override suspend fun upsertCourse(course: Course) {
        courseDao.upsertCourse(course.toEntity())
        stampWriter.stampModified(SyncEntity.COURSE, course.id)
    }

    override suspend fun upsertSession(session: CourseSession) {
        courseDao.upsertSession(session.toEntity())
        // 安排是子表、不参与同步；但改它会改变所属课程的实体内容，
        // 所以给它所属**课程**盖一次修订，否则这次改动传不出去。
        stampWriter.stampModified(SyncEntity.COURSE, session.courseId)
    }

    /**
     * 删课程要连同它的上课安排一起删。留下孤儿安排，课表上会出现一块
     * 找不到课程名、也不知道属于谁的色块 —— 这是课程表应用最常见的脏数据来源。
     *
     * 课程本身**软删**（云同步要求删除可传播）；安排是子表，仍走硬删。
     */
    override suspend fun deleteCourse(courseId: String) {
        database.withTransaction {
            stampWriter.stampDeleted(SyncEntity.COURSE, courseId)
            courseDao.deleteSessionsOf(courseId)
        }
        tombstones.markDeleted(SyncEntity.COURSE, courseId)
    }

    override suspend fun deleteSession(sessionId: String) {
        courseDao.deleteSession(sessionId)
    }

    override suspend fun clearAll() = database.withTransaction {
        // 先删安排再删课程。外键级联也会做，但显式更不依赖 PRAGMA 状态。
        courseDao.clearAllSessions()
        courseDao.clearAllCourses()
        // clearAll 是"清空重建"语义（导入课表用），不是删除：不写墓碑，
        // 否则另一台设备会把这次清空当成删除、把本地课程也删掉。
        // 真正的删除意图必须走 deleteCourse。
    }
}
