package com.phonlynn.oreplan.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val teacher: String?,
    val defaultLocation: String?,
    val colorHex: String?,
    val note: String?,
    /** 学分。0 表示未填。 */
    @ColumnInfo(defaultValue = "0") val credit: Double = 0.0,
)

/**
 * 上课安排 —— 课表的模板层。
 *
 * 外键级联删除：删课程时自动清掉它的全部上课安排，不留孤儿行
 * （孤儿安排会让课表出现找不到课程名的色块）。
 */
@Entity(
    tableName = "course_sessions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["courseId"]),
        Index(value = ["dayOfWeek"]),
    ],
)
data class CourseSessionEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val startWeek: Int,
    val endWeek: Int,
    val parity: String,
    val location: String?,
    val note: String?,
)
