package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.domain.model.ExceptionAction
import com.phonlynn.oreplan.domain.model.Frequency
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.RecurrenceException
import com.phonlynn.oreplan.domain.model.RecurrenceRule
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ExpandRecurrenceUseCaseTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-09-01T00:00:00Z")

    private class FakeExceptions : RecurrenceExceptionRepository {
        val storage = mutableMapOf<String, MutableList<RecurrenceException>>()

        override fun observeOf(itemId: String): Flow<List<RecurrenceException>> =
            flowOf(storage[itemId].orEmpty())

        override fun observeAll(): Flow<List<RecurrenceException>> =
            flowOf(storage.values.flatten())

        override suspend fun getAll(): List<RecurrenceException> =
            storage.values.flatten()

        override suspend fun getOf(itemId: String): List<RecurrenceException> =
            storage[itemId].orEmpty().toList()

        override suspend fun upsert(exception: RecurrenceException) {
            storage.getOrPut(exception.itemId) { mutableListOf() }
                .removeAll { it.date == exception.date }
            storage.getValue(exception.itemId) += exception
        }

        override suspend fun deleteOccurrence(itemId: String, date: LocalDate) {
            storage[itemId]?.removeAll { it.date == date }
        }

        override suspend fun deleteOf(itemId: String) {
            storage.remove(itemId)
        }
    }

    private fun singleEvent(
        startAt: Instant = Instant.parse("2026-09-15T01:00:00Z"),
        endAt: Instant? = Instant.parse("2026-09-15T02:00:00Z"),
    ): Item = Item.newRoot(
        kind = ItemKind.EVENT,
        title = "小组讨论",
        now = now,
        startAt = startAt,
        endAt = endAt,
        id = "e1",
    )

    private fun dailyEvent(): Item = Item.newRoot(
        kind = ItemKind.EVENT,
        title = "晨跑",
        now = now,
        startAt = Instant.parse("2026-09-01T23:00:00Z"),   // 北京时间 09-02 07:00
        endAt = Instant.parse("2026-09-02T00:00:00Z"),
        rrule = RecurrenceRule(frequency = Frequency.DAILY).toRRule(),
        id = "e2",
    )

    @Test
    fun `单次日程落在区间内返回一次`() = runTest {
        val useCase = ExpandRecurrenceUseCase(FakeExceptions())
        val result = useCase(
            item = singleEvent(),
            from = LocalDate.parse("2026-09-15"),
            to = LocalDate.parse("2026-09-15"),
            zone = zone,
        )
        assertEquals(1, result.size)
        assertEquals("小组讨论", result.single().title)
        assertEquals(LocalDate.parse("2026-09-15"), result.single().originalDate)
    }

    @Test
    fun `单次日程落在区间外返回空`() = runTest {
        val useCase = ExpandRecurrenceUseCase(FakeExceptions())
        val result = useCase(
            item = singleEvent(),
            from = LocalDate.parse("2026-09-20"),
            to = LocalDate.parse("2026-09-25"),
            zone = zone,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `没有开始时刻的待办不产生发生`() = runTest {
        val floating = Item.newRoot(kind = ItemKind.TASK, title = "读书", now = now, id = "t1")
        val useCase = ExpandRecurrenceUseCase(FakeExceptions())
        val result = useCase(
            item = floating,
            from = LocalDate.parse("2026-09-01"),
            to = LocalDate.parse("2026-09-30"),
            zone = zone,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `重复日程按规则展开出多次`() = runTest {
        val useCase = ExpandRecurrenceUseCase(FakeExceptions())
        val result = useCase(
            item = dailyEvent(),
            from = LocalDate.parse("2026-09-02"),
            to = LocalDate.parse("2026-09-05"),
            zone = zone,
        )
        assertEquals(
            listOf("09-02", "09-03", "09-04", "09-05").map { LocalDate.parse("2026-$it") },
            result.map { it.originalDate },
        )
    }

    @Test
    fun `每次发生都保持原来的时长`() = runTest {
        val useCase = ExpandRecurrenceUseCase(FakeExceptions())
        val result = useCase(
            item = dailyEvent(),
            from = LocalDate.parse("2026-09-02"),
            to = LocalDate.parse("2026-09-03"),
            zone = zone,
        )
        result.forEach { occurrence ->
            assertEquals(3600L, occurrence.endAt!!.epochSecond - occurrence.startAt.epochSecond)
        }
    }

    @Test
    fun `删除本次把那一天从结果里去掉`() = runTest {
        val fake = FakeExceptions()
        fake.upsert(
            RecurrenceException(
                id = "x1",
                itemId = "e2",
                date = LocalDate.parse("2026-09-03"),
                action = ExceptionAction.DELETED,
            ),
        )
        val useCase = ExpandRecurrenceUseCase(fake)
        val result = useCase(
            item = dailyEvent(),
            from = LocalDate.parse("2026-09-02"),
            to = LocalDate.parse("2026-09-05"),
            zone = zone,
        )
        assertEquals(
            listOf("09-02", "09-04", "09-05").map { LocalDate.parse("2026-$it") },
            result.map { it.originalDate },
        )
    }

    @Test
    fun `本次改期用覆盖后的时间与标题 并按原日期定位`() = runTest {
        val fake = FakeExceptions()
        // 原本 09-04 那一次改到 09-06 的同一个钟点，并改了标题
        fake.upsert(
            RecurrenceException(
                id = "x2",
                itemId = "e2",
                date = LocalDate.parse("2026-09-04"),
                action = ExceptionAction.OVERRIDDEN,
                overrideStartAt = Instant.parse("2026-09-05T23:00:00Z"),
                overrideEndAt = Instant.parse("2026-09-06T00:00:00Z"),
                overrideTitle = "晨跑（改期）",
            ),
        )
        val useCase = ExpandRecurrenceUseCase(fake)
        val result = useCase(
            item = dailyEvent(),
            from = LocalDate.parse("2026-09-02"),
            to = LocalDate.parse("2026-09-05"),
            zone = zone,
        )

        val overridden = result.single { it.originalDate == LocalDate.parse("2026-09-04") }
        assertTrue(overridden.isOverride)
        assertEquals("晨跑（改期）", overridden.title)
        assertEquals(Instant.parse("2026-09-05T23:00:00Z"), overridden.startAt)
    }

    @Test
    fun `改期只覆盖开始时间时 结束时间按原时长推算`() = runTest {
        val fake = FakeExceptions()
        fake.upsert(
            RecurrenceException(
                id = "x3",
                itemId = "e2",
                date = LocalDate.parse("2026-09-04"),
                action = ExceptionAction.OVERRIDDEN,
                overrideStartAt = Instant.parse("2026-09-05T23:00:00Z"),
            ),
        )
        val useCase = ExpandRecurrenceUseCase(fake)
        val result = useCase(
            item = dailyEvent(),
            from = LocalDate.parse("2026-09-04"),
            to = LocalDate.parse("2026-09-04"),
            zone = zone,
        )
        val occurrence = result.single()
        assertEquals(3600L, occurrence.endAt!!.epochSecond - occurrence.startAt.epochSecond)
    }

    @Test
    fun `条目上的 UNTIL 列在没有写进 RRULE 时同样生效`() = runTest {
        val item = Item.newRoot(
            kind = ItemKind.EVENT,
            title = "带结束的重复",
            now = now,
            startAt = Instant.parse("2026-09-01T23:00:00Z"),
            endAt = Instant.parse("2026-09-02T00:00:00Z"),
            rrule = RecurrenceRule(frequency = Frequency.DAILY).toRRule(),
            rruleUntil = Instant.parse("2026-09-02T23:00:00Z"),
            id = "e3",
        )
        val useCase = ExpandRecurrenceUseCase(FakeExceptions())
        val result = useCase(
            item = item,
            from = LocalDate.parse("2026-09-02"),
            to = LocalDate.parse("2026-09-10"),
            zone = zone,
        )
        // UNTIL 写在外面时，序列应该提前于「一直重复」断掉
        assertEquals(
            listOf("09-02", "09-03").map { LocalDate.parse("2026-$it") },
            result.map { it.originalDate },
        )
    }

    @Test
    fun `区间反向时返回空`() = runTest {
        val useCase = ExpandRecurrenceUseCase(FakeExceptions())
        val result = useCase(
            item = dailyEvent(),
            from = LocalDate.parse("2026-09-10"),
            to = LocalDate.parse("2026-09-01"),
            zone = zone,
        )
        assertTrue(result.isEmpty())
    }
}
