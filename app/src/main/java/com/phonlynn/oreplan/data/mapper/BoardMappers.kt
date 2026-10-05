package com.phonlynn.oreplan.data.mapper

import com.phonlynn.oreplan.data.local.entity.BoardCardEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTodoItemEntity
import com.phonlynn.oreplan.data.local.entity.DailyReviewEntity
import com.phonlynn.oreplan.data.local.entity.HabitLogEntity
import com.phonlynn.oreplan.data.local.entity.QuantityLogEntity
import com.phonlynn.oreplan.domain.model.BoardCard
import com.phonlynn.oreplan.domain.model.BoardCardTag
import com.phonlynn.oreplan.domain.model.BoardCardType
import com.phonlynn.oreplan.domain.model.BoardTag
import com.phonlynn.oreplan.domain.model.BoardTodoItem
import com.phonlynn.oreplan.domain.model.DailyReview
import com.phonlynn.oreplan.domain.model.HabitLog
import com.phonlynn.oreplan.domain.model.QuantityLog
import java.time.Instant

internal fun BoardCardEntity.toDomain(): BoardCard = BoardCard(
    id = id,
    type = BoardCardType.fromKey(type),
    title = title,
    body = body,
    color = color,
    pinned = pinned,
    secret = secret,
    secretHint = secretHint,
    archived = archived,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    sortIndex = sortIndex,
    widthMode = widthMode,
    showDate = showDate,
    imageLayout = imageLayout,
    // 动态置顶：kind/rule/duration 三列合成一个密封类型。
    // 解码失败会返回 null（视为未启用），不让脏数据把整张卡片读崩。
    autoPin = AutoPinCodec.decode(autoPinKind, autoPinRule, autoPinDurationMinutes),
    autoPinResolvedAt = autoPinResolvedAt?.let { Instant.ofEpochMilli(it) },
)

internal fun BoardCard.toEntity(): BoardCardEntity = BoardCardEntity(
    id = id,
    type = type.key,
    title = title,
    body = body,
    color = color,
    pinned = pinned,
    secret = secret,
    secretHint = secretHint,
    archived = archived,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    sortIndex = sortIndex,
    widthMode = widthMode,
    showDate = showDate,
    imageLayout = imageLayout,
    autoPinKind = AutoPinCodec.kindOf(autoPin),
    autoPinRule = autoPin?.let { AutoPinCodec.encodeRule(it) },
    autoPinDurationMinutes = autoPin?.durationMinutes,
    autoPinResolvedAt = autoPinResolvedAt?.toEpochMilli(),
)

internal fun BoardTodoItemEntity.toDomain(): BoardTodoItem = BoardTodoItem(
    id = id,
    cardId = cardId,
    text = text,
    done = done,
    sortIndex = sortIndex,
)

internal fun BoardTodoItem.toEntity(): BoardTodoItemEntity = BoardTodoItemEntity(
    id = id,
    cardId = cardId,
    text = text,
    done = done,
    sortIndex = sortIndex,
)

internal fun BoardTagEntity.toDomain(): BoardTag = BoardTag(
    id = id,
    name = name,
    parentId = parentId,
    sortIndex = sortIndex,
    hideFromAll = hideFromAll,
)

internal fun BoardTag.toEntity(): BoardTagEntity = BoardTagEntity(
    id = id,
    name = name,
    parentId = parentId,
    sortIndex = sortIndex,
    hideFromAll = hideFromAll,
)

internal fun BoardCardTagEntity.toDomain(): BoardCardTag =
    BoardCardTag(cardId = cardId, tagId = tagId)

internal fun BoardCardTag.toEntity(): BoardCardTagEntity =
    BoardCardTagEntity(cardId = cardId, tagId = tagId)

internal fun HabitLogEntity.toDomain(): HabitLog = HabitLog(itemId = itemId, epochDay = epochDay)

internal fun HabitLog.toEntity(): HabitLogEntity = HabitLogEntity(itemId = itemId, epochDay = epochDay)

internal fun QuantityLogEntity.toDomain(): QuantityLog = QuantityLog(
    id = id,
    itemId = itemId,
    at = Instant.ofEpochMilli(at),
    amount = amount,
    label = label,
)

internal fun QuantityLog.toEntity(): QuantityLogEntity = QuantityLogEntity(
    id = id,
    itemId = itemId,
    at = at.toEpochMilli(),
    amount = amount,
    label = label,
)

internal fun DailyReviewEntity.toDomain(): DailyReview = DailyReview(
    epochDay = epochDay,
    text = text,
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun DailyReview.toEntity(): DailyReviewEntity = DailyReviewEntity(
    epochDay = epochDay,
    text = text,
    updatedAt = updatedAt.toEpochMilli(),
)
