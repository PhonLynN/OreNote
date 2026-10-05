package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FocusSessionMapperTest {

    private val now = Instant.ofEpochMilli(1_800_000_000_000L)

    @Test
    fun `领域模型与实体往返一致`() {
        val session = FocusSession(
            id = "f1",
            startedAt = now,
            endedAt = now.plusSeconds(1500),
            minutes = 25,
            kind = FocusKind.POMODORO,
            plannedMinutes = 25,
            completed = true,
            label = "写日报",
            itemId = "i-1",
            createdAt = now,
        )

        assertEquals(session, session.toEntity().toDomain())
    }

    @Test
    fun `可空字段往返保持 null`() {
        val session = FocusSession(
            id = "f2",
            startedAt = now,
            endedAt = now,
            minutes = 0,
            kind = FocusKind.COUNT_UP,
            createdAt = now,
        )

        val back = session.toEntity().toDomain()

        assertEquals(null, back.plannedMinutes)
        assertEquals(null, back.label)
        assertEquals(null, back.itemId)
        assertEquals(session, back)
    }

    /** 脏数据（旧版本写下的、手工改过库的）应当降级显示，而不是崩溃。 */
    @Test
    fun `认不出的计时方式降级为正计时`() {
        assertEquals(FocusKind.COUNT_UP, FocusKind.fromKey("nonsense"))
        assertEquals(FocusKind.COUNT_UP, FocusKind.fromKey(null))
        assertEquals(FocusKind.POMODORO, FocusKind.fromKey("pomodoro"))
        assertEquals(FocusKind.COUNT_DOWN, FocusKind.fromKey("countdown"))
    }

    /** 存的是字符串键而不是序号：以后加计时方式不需要迁移。 */
    @Test
    fun `计时方式落库为字符串键`() {
        val entity = FocusSession(
            id = "f3",
            startedAt = now,
            endedAt = now,
            minutes = 5,
            kind = FocusKind.COUNT_DOWN,
            createdAt = now,
        ).toEntity()

        assertEquals("countdown", entity.kind)
    }

    /** 净时长与「是否走完」是两件事：中途放弃也应当留下已专注的分钟数。 */
    @Test
    fun `中途放弃保留已专注分钟数`() {
        val session = FocusSession(
            id = "f4",
            startedAt = now,
            endedAt = now.plusSeconds(600),
            minutes = 10,
            kind = FocusKind.POMODORO,
            plannedMinutes = 25,
            completed = false,
            createdAt = now,
        )

        val back = session.toEntity().toDomain()

        assertEquals(10, back.minutes)
        assertEquals(false, back.completed)
        assertEquals(25, back.plannedMinutes)
    }
}
