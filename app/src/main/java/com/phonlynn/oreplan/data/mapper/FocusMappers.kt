package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.data.local.entity.FocusSessionEntity
import com.phonlynn.oreplan.domain.model.FocusKind
import com.phonlynn.oreplan.domain.model.FocusSession
import java.time.Instant

internal fun FocusSessionEntity.toDomain(): FocusSession = FocusSession(
    id = id,
    startedAt = Instant.ofEpochMilli(startedAt),
    endedAt = Instant.ofEpochMilli(endedAt),
    minutes = minutes,
    kind = FocusKind.fromKey(kind),
    plannedMinutes = plannedMinutes,
    completed = completed,
    label = label,
    itemId = itemId,
    createdAt = Instant.ofEpochMilli(createdAt),
)

internal fun FocusSession.toEntity(): FocusSessionEntity = FocusSessionEntity(
    id = id,
    startedAt = startedAt.toEpochMilli(),
    endedAt = endedAt.toEpochMilli(),
    minutes = minutes,
    kind = kind.key,
    plannedMinutes = plannedMinutes,
    completed = completed,
    label = label,
    itemId = itemId,
    createdAt = createdAt.toEpochMilli(),
)
