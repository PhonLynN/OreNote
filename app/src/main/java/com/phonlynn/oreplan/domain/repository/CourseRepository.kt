package com.phonlynn.oreplan.domain.repository

import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import kotlinx.coroutines.flow.Flow

interface CourseRepository {

    fun observeCourses(): Flow<List<Course>>

    /** 一次性取全部课程。 */
    suspend fun getCourses(): List<Course>

    /** 全部上课安排，按星期与开始时间排序。 */
    fun observeSessions(): Flow<List<CourseSession>>

    /** 一次性取全部上课安排，给不需要持续观察的场景用。 */
    suspend fun getSessions(): List<CourseSession>

    fun observeSessionsOf(courseId: String): Flow<List<CourseSession>>

    /** 一次性取某门课的全部上课时段，编辑器加载时用。 */
    suspend fun getSessionsOf(courseId: String): List<CourseSession>

    suspend fun getCourse(id: String): Course?

    suspend fun upsertCourse(course: Course)

    suspend fun upsertSession(session: CourseSession)

    /** 删除课程，并连带删掉它的全部上课安排 —— 留下孤儿安排会让课表出现无主色块。 */
    suspend fun deleteCourse(courseId: String)

    /**
     * **清空全部课程与上课安排**。导入前调用，避免新旧课表叠加。
     *
     * 导入是「整体替换」语义：一次导入的是一整套课表，
     * 与旧的逐条合并只会产生重复课程（名称相同、时段翻倍）。
     */
    suspend fun clearAll()

    suspend fun deleteSession(sessionId: String)

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
    suspend fun allCoursesIncludingDeleted(): List<Course>
}
