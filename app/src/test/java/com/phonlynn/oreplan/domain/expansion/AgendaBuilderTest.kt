package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.core.time.WeekParity
import com.phonlynn.oreplan.domain.model.AgendaSource
import com.phonlynn.oreplan.domain.model.ChecklistCount
import com.phonlynn.oreplan.domain.model.Course
import com.phonlynn.oreplan.domain.model.CourseSession
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.ItemStatus
import com.phonlynn.oreplan.domain.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 议程聚合的边界。
 *
 * 重点有三块，都是界面上「看不到东西但说不清为什么」的常见来源：
 *  - 无时限待办什么时候浮出来（有具体时刻的不能重复浮）；
 *  - 逾期清单的取值（已完成、已取消、还没到期都不该进来）；
 *  - 备忘清单进度有没有正确挂到条目上。
 */
class AgendaBuilderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 9, 11)
    private val now: Instant = today.atStartOfDay(zone).toInstant()

    private fun at(date: LocalDate, time: LocalTime): Instant =
        date.atTime(time).atZone(zone).toInstant()

    private fun task(
        id: String,
        title: String,
        due: LocalDate?,
        status: ItemStatus = ItemStatus.TODO,
        startAt: Instant? = null,
    ): Item = Item.newRoot(
        kind = ItemKind.TASK,
        title = title,
        now = now,
        status = status,
        startAt = startAt,
        softDueAt = due?.atTime(LocalTime.of(23, 59))?.atZone(zone)?.toInstant(),
        id = id,
    )

    private fun build(
        items: List<Item>,
        from: LocalDate = today,
        to: LocalDate = today,
        term: Term? = null,
        sessions: List<CourseSession> = emptyList(),
        courses: List<Course> = emptyList(),
        checklist: Map<String, ChecklistCount> = emptyMap(),
    ) = AgendaBuilder.build(
        from = from,
        to = to,
        zone = zone,
        items = items,
        exceptionsByItem = emptyMap(),
        term = term,
        sessions = sessions,
        courses = courses,
        today = today,
        checklistCounts = checklist,
    )

    @Test
    fun `无时限待办按期望完成期浮出`() {
        val agenda = build(listOf(task("t1", "交实验报告", due = today)))

        val entries = agenda.untimedOn(today)
        assertEquals(1, entries.size)
        assertEquals("交实验报告", entries.single().title)
        assertEquals(AgendaSource.ITEM, entries.single().source)
        assertNull(entries.single().startMinute)
    }

    @Test
    fun `没填期望完成期的待办也出现在待办列表里`() {
        // 这是修掉的 bug：以前只在「到期那天」才浮出来，没填日期的待办永远不出现，
        // 用户刚添加完看不到任何变化。
        val agenda = build(listOf(task("t1", "有空再看", due = null)))

        assertTrue("它不该进时间轴", agenda.on(today).isEmpty())
        assertEquals(listOf("有空再看"), agenda.openTasks.map { it.title })
        assertNull("没填日期就没有到期提示", agenda.openTasks.single().dueDate)
    }

    @Test
    fun `逾期待办不进时间轴 但留在待办列表且带上到期日`() {
        val agenda = build(listOf(task("t1", "逾期的事", due = today.minusDays(3))))

        assertTrue("到期日已过，不该出现在今天的时间轴上", agenda.on(today).isEmpty())
        val entry = agenda.openTasks.single()
        assertEquals("逾期的事", entry.title)
        assertEquals(today.minusDays(3), entry.dueDate)
    }

    @Test
    fun `待办列表按手动排序键排列`() {
        // 列表要能拖动自由排序，所以顺序必须由 orderIndex 决定，而不是到期日 ——
        // 否则用户拖完下一次刷新就又被打回按日期的顺序。
        val agenda = build(
            listOf(
                task("t1", "排在后面的", due = today.minusDays(9)).copy(orderIndex = 2048.0),
                task("t2", "排在前面的", due = today.minusDays(1)).copy(orderIndex = 1024.0),
            ),
        )

        assertEquals(listOf("排在前面的", "排在后面的"), agenda.openTasks.map { it.title })
    }

    @Test
    fun `已完成的待办只在完成当天留在列表里`() {
        val doneToday = task("t1", "今天勾掉的", due = today, status = ItemStatus.DONE)
            .copy(completedAt = at(today, LocalTime.of(20, 0)))
        val doneBefore = task("t2", "昨天勾掉的", due = today, status = ItemStatus.DONE)
            .copy(completedAt = at(today.minusDays(1), LocalTime.of(20, 0)))

        val agenda = build(listOf(doneToday, doneBefore))

        // 刚勾完就消失会让人怀疑没勾上；而昨天的已经翻篇了
        assertEquals(listOf("今天勾掉的"), agenda.openTasks.map { it.title })
    }

    @Test
    fun `已取消的待办不出现在列表里`() {
        val agenda = build(
            listOf(task("t1", "不做了", due = today.minusDays(2), status = ItemStatus.CANCELLED)),
        )

        assertTrue(agenda.openTasks.isEmpty())
    }

    @Test
    fun `今天到期的待办带今天到期的到期日`() {
        val agenda = build(listOf(task("t1", "今天到期", due = today)))

        assertEquals(1, agenda.untimedOn(today).size)
        assertEquals(today, agenda.openTasks.single().dueDate)
    }

    @Test
    fun `已排到时间轴的待办不再按到期日重复浮出`() {
        val item = task(
            id = "t1",
            title = "有具体时刻的待办",
            due = today,
            startAt = at(today, LocalTime.of(14, 0)),
        ).copy(endAt = at(today, LocalTime.of(15, 0)))

        val agenda = build(listOf(item))

        assertEquals(1, agenda.on(today).size)
        assertTrue("有时刻的条目不该再出现在待办清单里", agenda.untimedOn(today).isEmpty())
    }

    @Test
    fun `有具体时刻的待办即使逾期也不算逾期`() {
        val item = task(
            id = "t1",
            title = "排过期了",
            due = today.minusDays(5),
            startAt = at(today.minusDays(5), LocalTime.of(9, 0)),
        )
        val agenda = build(
            items = listOf(item),
            from = today.minusDays(6),
            to = today,
        )

        // 它已经在 5 天前的时间轴上了；待办清单只收「没有时刻」的那些
        assertTrue(agenda.openTasks.isEmpty())
    }

    @Test
    fun `目标不占时间轴`() {
        val goal = Item.newRoot(
            kind = ItemKind.GOAL,
            title = "读完 6 本书",
            now = now,
            softDueAt = at(today, LocalTime.NOON),
            startAt = at(today, LocalTime.of(9, 0)),
            id = "g1",
        )

        assertTrue(build(listOf(goal)).on(today).isEmpty())
    }

    @Test
    fun `工作区不占时间轴`() {
        val workspace = Item.newRoot(
            kind = ItemKind.WORKSPACE,
            title = "考研准备",
            now = now,
            startAt = at(today, LocalTime.of(9, 0)),
            softDueAt = at(today, LocalTime.NOON),
            id = "w1",
        )

        assertTrue(build(listOf(workspace)).on(today).isEmpty())
    }

    @Test
    fun `条目带上创建时刻 供列表行显示「创建于」`() {
        val createdAt = today.atTime(22, 5).atZone(zone).toInstant()
        val item = Item.newRoot(
            kind = ItemKind.TASK,
            title = "刚记下来的一件事",
            now = createdAt,
            softDueAt = at(today, LocalTime.of(23, 59)),
            id = "t1",
        )

        val entry = build(listOf(item)).untimedOn(today).single()
        assertEquals(22 * 60 + 5, entry.createdMinute)
    }

    @Test
    fun `课程展开的条没有创建时刻`() {
        val termStart = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks(1)
        val term = Term(
            id = "term1",
            name = "2026 秋",
            startDate = termStart,
            totalWeeks = 18,
            isActive = true,
        )
        val course = Course(
            id = "c1",
            name = "高等数学",
            teacher = null,
            defaultLocation = null,
            colorHex = null,
            note = null,
        )
        val session = CourseSession(
            id = "s1",
            courseId = "c1",
            dayOfWeek = today.dayOfWeek.value,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 9 * 60 + 40,
            startWeek = 1,
            endWeek = Int.MAX_VALUE,
            parity = WeekParity.ALL,
            location = null,
            note = null,
        )

        val entry = build(listOf(), term = term, sessions = listOf(session), courses = listOf(course))
            .timedOn(today)
            .single()
        assertNull(entry.createdMinute)
    }

    @Test
    fun `全天日程不进时间轴 归到待办那一边`() {
        // 全天日程在库里存的是「当天 00:00 到次日 00:00」，在时间轴上只能被画成午夜的一小段。
        val allDay = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "体检",
            now = now,
            startAt = today.atStartOfDay(zone).toInstant(),
            endAt = today.plusDays(1).atStartOfDay(zone).toInstant(),
            allDay = true,
            id = "e1",
        )
        val timed = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "小组讨论",
            now = now,
            startAt = at(today, LocalTime.of(9, 0)),
            endAt = at(today, LocalTime.of(10, 0)),
            id = "e2",
        )

        val agenda = build(listOf(allDay, timed))

        assertEquals(listOf("小组讨论"), agenda.scheduledOn(today).map { it.title })
        assertEquals(listOf("体检"), agenda.tasksOn(today).map { it.title })
    }

    @Test
    fun `待办那一边包含不限时待办与全天日程`() {
        val allDay = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "全天的事",
            now = now,
            startAt = today.atStartOfDay(zone).toInstant(),
            endAt = today.plusDays(1).atStartOfDay(zone).toInstant(),
            allDay = true,
            id = "e1",
        )

        val agenda = build(listOf(allDay, task("t1", "要做的事", due = today)))

        assertEquals(listOf("全天的事", "要做的事"), agenda.tasksOn(today).map { it.title })
    }

    @Test
    fun `备忘清单进度挂到条目上`() {
        val agenda = build(
            items = listOf(task("t1", "要准备的事", due = today)),
            checklist = mapOf("t1" to ChecklistCount(done = 2, total = 5)),
        )

        val entry = agenda.untimedOn(today).single()
        assertEquals(2, entry.checklistCount?.done)
        assertEquals(5, entry.checklistCount?.total)
    }

    @Test
    fun `没有清单的条目进度为空`() {
        val agenda = build(listOf(task("t1", "没清单", due = today)))

        assertNull(agenda.untimedOn(today).single().checklistCount)
    }

    @Test
    fun `课程与日程合流在同一根时间轴上`() {
        // 约定：学期 startDate 就是第 1 周的周一（见 TermClock）。用今天的周一往前推一周。
        val termStart = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks(1)
        val term = Term(
            id = "term1",
            name = "2026 秋",
            startDate = termStart,
            totalWeeks = 18,
            isActive = true,
        )
        val course = Course(
            id = "c1",
            name = "高等数学",
            teacher = null,
            defaultLocation = "A301",
            colorHex = null,
            note = null,
        )
        val session = CourseSession(
            id = "s1",
            courseId = "c1",
            dayOfWeek = today.dayOfWeek.value,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 9 * 60 + 40,
            startWeek = 1,
            endWeek = Int.MAX_VALUE,
            parity = WeekParity.ALL,
            location = null,
            note = null,
        )
        val event = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "小组讨论",
            now = now,
            startAt = at(today, LocalTime.of(14, 0)),
            endAt = at(today, LocalTime.of(15, 30)),
            id = "e1",
        )

        val agenda = build(
            items = listOf(event),
            term = term,
            sessions = listOf(session),
            courses = listOf(course),
        )

        val titles = agenda.timedOn(today).map { it.title }
        assertEquals(listOf("高等数学", "小组讨论"), titles)
        assertEquals(1, agenda.courseCountOn(today))
        assertTrue(agenda.hasActiveTerm)
    }

    @Test
    fun `只有开始时刻的日程就是点日程 不补默认时长`() {
        // 以前这里会补 30 分钟，于是「12:00 交表」被画成一段半小时的块；
        // 现在保持 endMinute = null，时间轴把它画成一根横线。
        val event = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "交表",
            now = now,
            startAt = at(today, LocalTime.of(10, 0)),
            id = "e1",
        )

        val entry = build(listOf(event)).timedOn(today).single()
        assertEquals(10 * 60, entry.startMinute)
        assertNull(entry.endMinute)
    }

    @Test
    fun `结束时间早于开始时间时按默认时长而不是负数`() {
        val event = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "填反了",
            now = now,
            startAt = at(today, LocalTime.of(10, 0)),
            endAt = at(today, LocalTime.of(9, 0)),
            id = "e1",
        )

        val entry = build(listOf(event)).timedOn(today).single()
        assertEquals(10 * 60 + AgendaBuilder.DEFAULT_DURATION_MINUTES, entry.endMinute)
    }

    @Test
    fun `起始日晚于结束日时返回空议程`() {
        val agenda = build(
            items = listOf(task("t1", "x", due = today)),
            from = today,
            to = today.minusDays(1),
        )

        assertTrue(agenda.on(today).isEmpty())
        assertTrue(agenda.openTasks.isEmpty())
    }

    // ------------------------------------------------------------ 待办卡的取数规则

    /**
     * 今日页与日历日视图共用同一条规则（`Agenda.taskCardEntries`）。
     * 以前它在卡片与 ViewModel 里各写一遍，且「非今天」那支只取 `allDay` ——
     * 于是同一份数据在周视图的当天清单里看得到、在日视图里看不到。
     */
    @Test
    fun `待办卡：今天列全部没做完的待办 不分到期日`() {
        val agenda = build(
            items = listOf(
                task("t1", "今天到期", due = today),
                task("t2", "下周到期", due = today.plusDays(7)),
                task("t3", "没有期限", due = null),
            ),
            from = today,
            to = today.plusDays(7),
        )

        assertEquals(
            setOf("今天到期", "下周到期", "没有期限"),
            agenda.taskCardEntries(today, isToday = true).map { it.title }.toSet(),
        )
    }

    @Test
    fun `待办卡：其他日期列出期望完成期就在这一天的待办`() {
        val tomorrow = today.plusDays(1)
        val agenda = build(
            items = listOf(
                task("t1", "明天到期", due = tomorrow),
                task("t2", "没有任何期限", due = null),
                task("t3", "今天到期", due = today),
            ),
            from = today,
            to = tomorrow,
        )

        assertEquals(
            listOf("明天到期"),
            agenda.taskCardEntries(tomorrow, isToday = false).map { it.title },
        )
        // 今天那一屏走的是全局未完成清单，三条都在
        assertEquals(3, agenda.taskCardEntries(today, isToday = true).size)
    }
}
