package com.phonlynn.oreplan.v2

import java.time.LocalDate

/**
 * V2 全部路由。
 *
 * 哨兵值（"" / -1）表示「未传入」，和旧版约定一致 —— Compose Navigation 的可空 query 参数
 * 行为不稳，哨兵更可靠。
 */
object V2Routes {

    // Tab
    const val TODAY = "today"
    const val CALENDAR = "calendar"
    const val TIMETABLE = "timetable"
    const val BOARD = "board"
    const val PLAN = "plan"

    val TAB_ROUTES = listOf(TODAY, CALENDAR, TIMETABLE, BOARD, PLAN)
    fun isTab(route: String?): Boolean = route in TAB_ROUTES

    // 日程
    const val SETTINGS = "settings"
    const val COUNTDOWN = "countdown"

    // 日程编辑器（添加 / 详情共用）
    const val ARG_ITEM_ID = "itemId"
    const val ARG_DAY = "day"
    const val ARG_START_MINUTE = "startMinute"
    const val ARG_KIND = "kind"
    const val ARG_TYPE = "type"
    const val ARG_CARD_ID = "cardId"
    /** 打开卡片页后是否自动聚焦正文输入框（双击进编辑时为 true）。 */
    const val ARG_FOCUS_BODY = "focusBody"
    const val ARG_TAG_ID = "tagId"
    const val ARG_GOAL_ID = "goalId"
    const val ARG_STEP_ID = "stepId"
    const val NO_INT = -1

    const val ARG_GROUP_ID = "groupId"

    const val EDITOR_TEMPLATE =
        "editor?$ARG_ITEM_ID={$ARG_ITEM_ID}&$ARG_DAY={$ARG_DAY}&$ARG_START_MINUTE={$ARG_START_MINUTE}&$ARG_KIND={$ARG_KIND}&$ARG_GROUP_ID={$ARG_GROUP_ID}"

    fun editor(
        itemId: String? = null,
        day: LocalDate? = null,
        startMinute: Int? = null,
        kind: String? = null,
        /** 预选的待办组（从待办页分组行的「+」进来时带）。 */
        groupId: String? = null,
    ): String = buildString {
        append("editor")
        append("?$ARG_ITEM_ID=").append(itemId.orEmpty())
        append("&$ARG_DAY=").append(day?.toEpochDay() ?: NO_INT)
        append("&$ARG_START_MINUTE=").append(startMinute ?: NO_INT)
        append("&$ARG_KIND=").append(kind.orEmpty())
        append("&$ARG_GROUP_ID=").append(groupId.orEmpty())
    }

    const val REMIND_TEMPLATE = "remind?$ARG_ITEM_ID={$ARG_ITEM_ID}"
    fun remind(itemId: String): String = "remind?$ARG_ITEM_ID=$itemId"

    const val TT_SETTINGS = "ttSettings"
    const val ROUTINE = "routine"
    const val COURSES = "courses"

    const val COURSE_TEMPLATE = "course?$ARG_CARD_ID={$ARG_CARD_ID}&$ARG_DAY={$ARG_DAY}&$ARG_START_MINUTE={$ARG_START_MINUTE}"
    fun courseEditor(
        courseId: String? = null,
        day: LocalDate? = null,
        startMinute: Int? = null,
    ): String = buildString {
        append("course")
        append("?$ARG_CARD_ID=").append(courseId.orEmpty())
        append("&$ARG_DAY=").append(day?.toEpochDay() ?: NO_INT)
        append("&$ARG_START_MINUTE=").append(startMinute ?: NO_INT)
    }

    // 白板
    const val BOARD_SEARCH = "boardSearch"

    const val BOARD_CARD_TEMPLATE =
        "boardCard?$ARG_CARD_ID={$ARG_CARD_ID}&$ARG_TYPE={$ARG_TYPE}&$ARG_FOCUS_BODY={$ARG_FOCUS_BODY}"

    fun boardCard(
        cardId: String? = null,
        type: String? = null,
        focusBody: Boolean = false,
    ): String = "boardCard?$ARG_CARD_ID=${cardId.orEmpty()}&$ARG_TYPE=${type.orEmpty()}&$ARG_FOCUS_BODY=$focusBody"

    const val BOARD_TAG_EDIT_TEMPLATE = "boardTagEdit?$ARG_TAG_ID={$ARG_TAG_ID}"
    fun boardTagEdit(tagId: String? = null): String = "boardTagEdit?$ARG_TAG_ID=${tagId.orEmpty()}"

    const val BOARD_SETTINGS = "boardSettings"
    const val BOARD_ARCHIVE = "boardArchive"

    // ---------------------------------------------------------------- 规划（0.3.0 全新）
    //
    // 页面清单来自 pen.dev 设计稿，与之一一对应：
    //   wBNwV 规划 V2          → PLAN（一级 Tab）
    //   E1Vcu3 规划 · 专注开始 → FOCUS_START
    //   aogr4/lBnWn 专注中     → FOCUS_RUNNING（仅运行时可达）
    //   UmxQp 规划 · 专注完成  → FOCUS_DONE（仅结束时可达）
    //   DnhqZ 规划 · 专注记录  → FOCUS_RECORDS
    //   Mc5BR 规划 · 已归档    → PLAN_ARCHIVE
    //   V62qjP 规划 · 新建目标 → GOAL_EDITOR（goalId 为空 = 新建）
    //   Dl2iy/g13Ial/LWz7k 目标设置 → GOAL_EDITOR（goalId 非空 = 设置）
    //
    // 目标详情（svsrX/CAMRJ/oTvuc）**不是路由**：它是规划页内的底部弹层，
    // 打开后底栏仍在（被蒙层压暗），返回也不该多一层返回栈。

    /** 目标设置 / 新建。goalId 为空表示新建。 */
    const val GOAL_TEMPLATE = "goal?$ARG_GOAL_ID={$ARG_GOAL_ID}&$ARG_TYPE={$ARG_TYPE}"

    fun goalEditor(goalId: String? = null, type: String? = null): String =
        "goal?$ARG_GOAL_ID=${goalId.orEmpty()}&$ARG_TYPE=${type.orEmpty()}"

    /** 专注开始。 */
    const val FOCUS_START = "focusStart"

    /** 专注中。只有存在活动专注时才进得来。 */
    const val FOCUS_RUNNING = "focusRunning"

    /** 专注完成。只有存在未保存结果时才进得来。 */
    const val FOCUS_DONE = "focusDone"

    /** 专注记录（热力图 + 当日列表）。 */
    const val FOCUS_RECORDS = "focusRecords"

    /** 规划 · 已归档。 */
    const val PLAN_ARCHIVE = "planArchive"

    /** 已归档的待办（设置 → 已归档的待办）。 */
    const val TASK_ARCHIVE = "taskArchive"

    // 设置
    const val SCHEDULE_SETTINGS = "scheduleSettings"
    const val PLAN_SETTINGS = "planSettings"

    /** 云同步设置（设置 → 同步 → 账号与连接）。 */
    const val CLOUD_SYNC_SETTINGS = "cloudSyncSettings"
}
