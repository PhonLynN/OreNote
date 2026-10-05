package com.phonlynn.oreplan.domain.sync.adapter

import com.phonlynn.oreplan.domain.backup.BackupCodec
import com.phonlynn.oreplan.domain.backup.SyncBundleCodec
import com.phonlynn.oreplan.domain.repository.BoardRepository
import com.phonlynn.oreplan.domain.repository.CourseRepository
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import com.phonlynn.oreplan.domain.repository.FocusRepository
import com.phonlynn.oreplan.domain.repository.TagRepository
import com.phonlynn.oreplan.domain.repository.TermRepository
import com.phonlynn.oreplan.domain.sync.SyncEntity
import com.phonlynn.oreplan.domain.sync.SyncEnvelope
import com.phonlynn.oreplan.domain.sync.SyncMeta
import com.phonlynn.oreplan.domain.sync.SyncStampWriter
import com.phonlynn.oreplan.domain.sync.SyncTableAdapter
import com.phonlynn.oreplan.domain.sync.entityBody
import com.phonlynn.oreplan.domain.sync.wrapPayload
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 白板卡片的同步适配器。
 *
 * ⚠️ **卡片的从属数据（清单项、标签关联、卡片关联）随卡片整体走**，
 * 不进 payload：它们语义上必定随卡片生灭（S1 用户拍板的口径），
 * 且都是复合主键的关联表，单独同步会引入"半个关联"的中间态。
 * 所以：**卡片同步时连同它的从属数据一起打包、一起落库**。
 */
@Singleton
class BoardCardSyncAdapter @Inject constructor(
    private val boardRepository: BoardRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.BOARD_CARD
    override val payloadKey: String = "boardCard"

    override suspend fun readAll(): Map<String, SyncEnvelope> {
        // 一次读全量，避免每条卡片查一次关联
        val todos = boardRepository.observeTodoItems().first()
        val tagLinks = boardRepository.observeCardTags().first()
        val links = boardRepository.observeCardLinks().first()
        return boardRepository.allCardsIncludingDeleted().associate { card ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, card.id))
            card.id to SyncEnvelope(
                table = entity.table,
                id = card.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(
                    payloadKey,
                    SyncBundleCodec.boardCardToJson(
                        card = card,
                        todos = todos.filter { it.cardId == card.id },
                        tagIds = tagLinks.filter { it.cardId == card.id }.map { it.tagId },
                        linkedCardIds = links.filter { it.cardId == card.id }.map { it.linkedCardId },
                    ),
                ),
            )
        }
    }

    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { body ->
                val bundle = SyncBundleCodec.boardCardFromJson(body)
                // replaceCardWithRelations 的关联参数传 null 表示"不动"，
                // 但这里我们**要**整体替换（远端是权威），所以传实际列表
                boardRepository.replaceCardWithRelations(
                    card = bundle.card,
                    todoItems = bundle.todos,
                    tagIds = bundle.tagIds,
                )
                boardRepository.setCardLinks(bundle.card.id, bundle.linkedCardIds)
            }
        }
        // 见 ItemSyncAdapter 的注释：必须覆盖回远端时钟，否则永不收敛
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(
                rev = winner.rev,
                hlc = winner.hlcValue,
                deviceId = winner.deviceId,
                deletedAt = winner.deletedAt,
            ),
        )
    }
}

/** 白板标签。 */
@Singleton
class BoardTagSyncAdapter @Inject constructor(
    private val boardRepository: BoardRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.BOARD_TAG
    override val payloadKey: String = "boardTag"

    override suspend fun readAll(): Map<String, SyncEnvelope> =
        boardRepository.allBoardTagsIncludingDeleted().associate { tag ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, tag.id))
            tag.id to SyncEnvelope(
                table = entity.table,
                id = tag.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(payloadKey, BackupCodec.boardTagToJson(tag)),
            )
        }

    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { boardRepository.upsertTag(BackupCodec.boardTagFromJson(it)) }
        }
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(winner.rev, winner.hlcValue, winner.deviceId, winner.deletedAt),
        )
    }
}

/** 课程。 */
@Singleton
class CourseSyncAdapter @Inject constructor(
    private val courseRepository: CourseRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.COURSE
    override val payloadKey: String = "course"

    override suspend fun readAll(): Map<String, SyncEnvelope> {
        val sessions = courseRepository.getSessions()
        return courseRepository.allCoursesIncludingDeleted().associate { course ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, course.id))
            course.id to SyncEnvelope(
                table = entity.table,
                id = course.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(
                    payloadKey,
                    SyncBundleCodec.courseToJson(course, sessions.filter { it.courseId == course.id }),
                ),
            )
        }
    }

    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { body ->
                val bundle = SyncBundleCodec.courseFromJson(body)
                courseRepository.upsertCourse(bundle.course)
                // 上课安排是子表：**整体替换**（远端是权威）。
                // 不整体替换会出现"远端删了一节课、本机还留着"的幽灵课表块。
                val existing = courseRepository.getSessionsOf(bundle.course.id).map { it.id }.toSet()
                val incoming = bundle.sessions.map { it.id }.toSet()
                (existing - incoming).forEach { courseRepository.deleteSession(it) }
                bundle.sessions.forEach { courseRepository.upsertSession(it) }
            }
        }
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(winner.rev, winner.hlcValue, winner.deviceId, winner.deletedAt),
        )
    }
}

/** 标签（日程模块）。 */
@Singleton
class TagSyncAdapter @Inject constructor(
    private val tagRepository: TagRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.TAG
    override val payloadKey: String = "tag"

    override suspend fun readAll(): Map<String, SyncEnvelope> =
        tagRepository.allTagsIncludingDeleted().associate { tag ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, tag.id))
            tag.id to SyncEnvelope(
                table = entity.table,
                id = tag.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(payloadKey, BackupCodec.tagToJson(tag)),
            )
        }

    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { tagRepository.upsert(BackupCodec.tagFromJson(it)) }
        }
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(winner.rev, winner.hlcValue, winner.deviceId, winner.deletedAt),
        )
    }
}

/**
 * 学期。
 *
 * ⚠️ 学期有一个**全局唯一状态**：「哪一个是当前学期」（`isActive`）。
 * 两台设备各自设了不同学期时，合并后可能出现两个 active ——
 * 所以落库后要**强制归一**：只保留合并结果里 isActive 的那一个。
 */
@Singleton
class TermSyncAdapter @Inject constructor(
    private val termRepository: TermRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.TERM
    override val payloadKey: String = "term"

    override suspend fun readAll(): Map<String, SyncEnvelope> =
        termRepository.allTermsIncludingDeleted().associate { term ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, term.id))
            term.id to SyncEnvelope(
                table = entity.table,
                id = term.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(payloadKey, BackupCodec.termToJson(term)),
            )
        }

    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { body ->
                val term = BackupCodec.termFromJson(body)
                termRepository.upsert(term)
                // 归一：若这条是 active，把其余的都关掉（避免出现两个"当前学期"）
                if (term.isActive) termRepository.activate(term.id)
            }
        }
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(winner.rev, winner.hlcValue, winner.deviceId, winner.deletedAt),
        )
    }
}

/**
 * 专注记录。
 *
 * 只增不改的流水，但删除要传播（用户误记一条、在另一台删掉，不能又冒出来）。
 */
@Singleton
class FocusSessionSyncAdapter @Inject constructor(
    private val focusRepository: FocusRepository,
    private val extRepo: EntityExtRepository,
    private val stampWriter: SyncStampWriter,
) : SyncTableAdapter {

    override val entity: SyncEntity = SyncEntity.FOCUS_SESSION
    override val payloadKey: String = "focusSession"

    override suspend fun readAll(): Map<String, SyncEnvelope> =
        focusRepository.allIncludingDeleted().associate { session ->
            val meta = SyncMeta.from(extRepo.get(entity.ext, session.id))
            session.id to SyncEnvelope(
                table = entity.table,
                id = session.id,
                rev = meta.rev,
                hlc = meta.hlc?.encode(),
                deviceId = meta.deviceId,
                deletedAt = meta.deletedAt,
                payload = wrapPayload(payloadKey, BackupCodec.focusSessionToJson(session)),
            )
        }

    override suspend fun applyToLocal(winner: SyncEnvelope, existsLocally: Boolean) {
        if (!winner.isTombstone) {
            winner.entityBody(payloadKey)?.let { focusRepository.save(BackupCodec.focusSessionFromJson(it)) }
        }
        stampWriter.writeFromRemote(
            entity,
            winner.id,
            SyncMeta(winner.rev, winner.hlcValue, winner.deviceId, winner.deletedAt),
        )
    }
}
