package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.model.ExtMap
import com.phonlynn.oreplan.domain.repository.EntityExtRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步元数据的**唯一写入者**：给实体盖 rev / hlc / deviceId，以及打墓碑。
 *
 * ## 为什么单独一个类，而不是散在仓储里
 *
 * `rev` 必须**单调且连续**，`hlc` 必须**全序**。如果每个仓储各写各的，
 * 迟早出现两个人算出同一个 rev、或两台设备算出相同 hlc 的情况 ——
 * 那是"偶发数据回退"，最难查的一类 bug。
 * 所以全部收敛到这里：时钟状态只有一个，`tick()` 只有一个入口。
 *
 * ## 与仓储的分工
 *
 * - 仓储：管业务行（`items` 表）的增删改
 * - 本类：管**抽屉里的同步元数据**（`entity_ext` 表）
 *
 * 两者必须在**同一个事务里**完成（见各仓储的删除路径），
 * 否则会出现"业务行删了、墓碑没写上"，那它下次同步就会**复活**。
 */
@Singleton
class SyncStampWriter @Inject constructor(
    private val extRepo: EntityExtRepository,
    private val clock: SyncClock,
) {

    private val mutex = Mutex()

    /**
     * 标记一次**本地修改**：rev +1，盖上新的 HLC。
     *
     * 在仓储的 create/update 之后调用。
     */
    suspend fun stampModified(entity: SyncEntity, id: String): SyncMeta = mutex.withLock {
        val base = extRepo.get(entity.ext, id)
        val meta = SyncMeta.from(base)
        val next = SyncMeta(
            rev = meta.rev + 1,
            hlc = clock.tick(),
            deviceId = clock.deviceId,
            deletedAt = null,
        )
        // 修改会**清除墓碑**：一个被软删的实体又被改活了，
        // 那它就是活的（删除优先只作用于"同一时刻"的冲突，事务序上后写的赢）。
        extRepo.set(entity.ext, id, next.toExt(base))
        next
    }

    /**
     * 标记一次**本地删除**：写墓碑。
     *
     * 墓碑也要 rev +1 —— 否则远端看到 rev 没变会认为"没更新"，删除就传不出去。
     */
    suspend fun stampDeleted(entity: SyncEntity, id: String): SyncMeta = mutex.withLock {
        val base = extRepo.get(entity.ext, id)
        val meta = SyncMeta.from(base)
        val now = clock.now()
        val next = SyncMeta(
            rev = meta.rev + 1,
            hlc = clock.tick(),
            deviceId = clock.deviceId,
            deletedAt = now,
        )
        extRepo.set(entity.ext, id, next.toExt(base))
        next
    }

    /** 读元数据（不写）。 */
    suspend fun read(entity: SyncEntity, id: String): SyncMeta =
        SyncMeta.from(extRepo.get(entity.ext, id))

    /**
     * 批量标记删除（删子树时用）。**只写一次抽屉往返**，而不是 N 次。
     *
     * @return 被标记的 id → 新元数据
     */
    suspend fun stampDeletedAll(entity: SyncEntity, ids: Collection<String>): Map<String, SyncMeta> =
        mutex.withLock {
            if (ids.isEmpty()) return@withLock emptyMap()
            val now = clock.now()
            ids.associateWith { id ->
                val base = extRepo.get(entity.ext, id)
                val meta = SyncMeta.from(base)
                val next = SyncMeta(
                    rev = meta.rev + 1,
                    hlc = clock.tick(),
                    deviceId = clock.deviceId,
                    deletedAt = now,
                )
                extRepo.set(entity.ext, id, next.toExt(base))
                next
            }
        }

    /**
     * 由**同步合并**写入元数据：不回本机时钟、rev 取远端值。
     *
     * 与 [stampModified] 的区别：那个是"本机发生的事"，这个是"承认远端的事"，
     * 所以 hlc 用远端的、rev 也接受远端的。
     */
    suspend fun writeFromRemote(entity: SyncEntity, id: String, meta: SyncMeta) = mutex.withLock {
        val base = extRepo.get(entity.ext, id)
        extRepo.set(entity.ext, id, meta.toExt(base))
        clock.observe(meta.hlc)
    }

    /**
     * 该实体是否"本机有未上传的修改 = 从未同步过"。
     * 上传时用来决定走 `If-None-Match: *`（新建）还是 `If-Match`（覆盖）。
     */
    suspend fun wasNeverSynced(entity: SyncEntity, id: String): Boolean =
        read(entity, id).isNeverSynced
}
