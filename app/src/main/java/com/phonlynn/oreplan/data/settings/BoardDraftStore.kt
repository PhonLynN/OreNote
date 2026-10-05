package com.phonlynn.oreplan.data.settings

import com.phonlynn.oreplan.core.order.BoardDraftCodec
import com.phonlynn.oreplan.core.order.BoardDraftPayload
import com.phonlynn.oreplan.data.local.dao.AppMetaDao
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 未写完的新卡片草稿（「自动保存草稿」开启时）。
 *
 * 为什么用 `app_meta` 而不是新建一张表：草稿只是一份**单例临时数据**——
 * 同时最多存在一份、结构随草稿字段变化频繁、且随时可丢弃。
 * 为它建表并做 Room 迁移，代价远高于直接复用现有的键值表。
 *
 * 语义（与用户 2026-09-18 确认）：
 *  - 开启开关时，离开新建页（无论点 X 还是按系统返回）都把草稿**存起来**，
 *    **不**写入白板卡片墙——它还是一张没写完的卡片；
 *  - 下次新建卡片时自动回填上次的内容；
 *  - 正常保存（「添加到白板」）时清掉草稿；关闭开关时也清掉，避免残留旧草稿。
 *
 * 备注：保存草稿时会先把本次未落库的附件清理掉（见 ViewModel.closeDraft），
 * 避免反复暂存产生孤儿文件。
 */
@Singleton
class BoardDraftStore @Inject constructor(
    private val appMetaDao: AppMetaDao,
) {
    private val state = MutableStateFlow<BoardDraftPayload?>(null)
    private var loaded = false
    // 本次会话是否已经写过草稿。用于防止「加载还没完成就被 save/clear，
    // 随后迟到的加载结果把刚写的值盖掉」这种竞态。
    private var written = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前草稿（同步可读；首次读取会触发一次后台加载）。 */
    val draft: StateFlow<BoardDraftPayload?> get() {
        ensureLoaded()
        return state
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scope.launch {
            runCatching {
                val raw = appMetaDao.find(KEY)?.value
                // 若加载期间已经写过，不再用库里旧值覆盖内存中的新值。
                if (!written) state.value = BoardDraftCodec.decode(raw)
            }
        }
    }

    /**
     * 读取当前草稿（挂起版）：保证**加载已完成**后再返回。
     *
     * 与直接读 `draft.value` 的区别：后者在首次调用时只触发后台加载，
     * 若紧接着就读可能拿到 null（加载还没回来），表现为「明明有草稿却没回填」。
     * 恢复草稿是低频动作，多等一次查询不影响体验。
     */
    suspend fun awaitDraft(): BoardDraftPayload? {
        // 触发一次加载（首次调用时）。
        val initial = draft.value
        // 本次会话已写过，或首读已经拿到值：直接返回，不重复查库。
        if (written || initial != null) return state.value
        runCatching {
            state.value = BoardDraftCodec.decode(appMetaDao.find(KEY)?.value)
        }
        return state.value
    }

    suspend fun save(payload: BoardDraftPayload) {
        written = true
        state.value = payload
        runCatching { appMetaDao.upsert(AppMetaEntity(KEY, BoardDraftCodec.encode(payload))) }
    }

    suspend fun clear() {
        written = true
        state.value = null
        runCatching { appMetaDao.upsert(AppMetaEntity(KEY, "")) }
    }

    companion object {
        const val KEY = "board_new_card_draft"
    }
}
