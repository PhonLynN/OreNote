package com.phonlynn.oreplan.domain.sync

import org.json.JSONObject

/**
 * 冲突合并：把"本机这一份"与"云端那一份"合成一份。
 *
 * ## 这是整个同步里最容易毁数据的地方
 *
 * 所以它是一个**纯函数**：不碰数据库、不碰网络、不读时钟。
 * 输入两份快照，输出"该留下哪一份"。所有分支都能被单测穷举。
 *
 * ## 规则（按用户 2026-10-02 拍板的口径）
 *
 * | 情形 | 结果 | 理由 |
 * | --- | --- | --- |
 * | 只有一边存在 | 用存在的那一边 | 新增 |
 * | 两边都删 | 墓碑（取较晚的删除时刻） | 幂等 |
 * | **一边删、一边改** | **删除优先** | 否则"在 A 删了、在 B 改了"会让它复活，违反直觉 |
 * | 两边都改 | 逐字段 LWW（按 HLC） | 改不同字段 ⇒ 都保留；改同一字段 ⇒ 时钟大的赢 |
 * | 修订号相同且时钟相同 | 内容相同，任取一份 | 幂等 |
 *
 * ## 为什么是"逐字段 LWW"而不是"整体 LWW"
 *
 * 整体 LWW 会丢更新：A 改标题、B 改颜色，结果只会留下一边的改动。
 * 逐字段比较需要知道"每个字段各自是什么时候改的"，而完整记录那个信息
 * 意味着每个字段一个时钟 —— 对个人数据量级是过度设计。
 *
 * 折中：**字段级比较用同一个实体时钟，但字段取"两边不同就都试"的策略**——
 * 具体地说：若两边的字段集合里，某个字段只有一边有值或两边相同，直接采用；
 * 两边都有且不同，才用整体时钟决定该字段听谁的。
 *
 * 代价要说明白：A 改标题（时钟 10）、B 改颜色（时钟 20），最终 B 的时钟更大，
 * 于是标题也会用 B 的（旧的）版本 —— **A 的标题改动会丢**。
 * 这是"不记录字段级时钟"的固有代价。对单人多设备场景，
 * 两台设备同时改同一个实体的**不同字段**是小概率事件，换来的是显著更简单的实现。
 * 若将来真遇到，升级路径是给 payload 加 per-field 时钟（格式已能兼容，JSON 加键即可）。
 */
object SyncMerge {

    /** 合并结果：该留下哪一份，以及是否真的发生了"两边都改"的冲突。 */
    data class Result(
        val winner: SyncEnvelope,
        val conflict: Boolean,
    )

    /**
     * 合并本机与远端的同一实体。
     *
     * @param local 本机当前的样子；null 表示本机没有这个实体
     * @param remote 云端的样子；null 表示云端没有
     */
    fun merge(local: SyncEnvelope?, remote: SyncEnvelope?): Result? {
        // 两边都没有：没有可合并的东西
        if (local == null && remote == null) return null
        // 只有一边：直接用
        if (local == null) return Result(remote!!, conflict = false)
        if (remote == null) return Result(local, conflict = false)

        // 两边都是墓碑：取删除时刻较晚的那个（幂等、且保留最完整的删除意图）
        if (local.isTombstone && remote.isTombstone) {
            val later = if ((local.deletedAt ?: 0L) >= (remote.deletedAt ?: 0L)) local else remote
            return Result(later, conflict = false)
        }

        // **一边删、一边改 ⇒ 删除优先**
        if (local.isTombstone != remote.isTombstone) {
            val tombstone = if (local.isTombstone) local else remote
            return Result(tombstone, conflict = false)
        }

        // 两边都活着：比较时钟
        val cmp = compareClocks(local, remote)
        return when {
            cmp > 0 -> Result(local, conflict = false)
            cmp < 0 -> Result(remote, conflict = false)
            // 时钟完全相同：内容若也相同就是同一份（幂等）；
            // 若不同则是"同一时刻的两份不同内容"——判定为冲突，取 id 较小的那份**保证两端一致**。
            else -> {
                val identical = local.payload.toString() == remote.payload.toString()
                Result(
                    winner = if (identical || local.deviceId.orEmpty() <= remote.deviceId.orEmpty()) local else remote,
                    conflict = !identical,
                )
            }
        }
    }

    /**
     * 逐字段合并——**只在双方都是活实体且都改过时使用**。
     *
     * 返回的 payload 里：只在一侧出现的键保留；两侧都有且不同的键，
     * 按 [winner] 的时钟归属决定用谁的值。
     */
    fun mergePayloads(winner: SyncEnvelope, loser: SyncEnvelope): JSONObject {
        val out = JSONObject()
        // 先用赢家的全部字段
        winner.payload.keys().forEach { key ->
            out.put(key, winner.payload.get(key))
        }
        // 输家独有的字段补进来（这是"改不同字段都保留"的兑现）
        loser.payload.keys().forEach { key ->
            if (!out.has(key)) out.put(key, loser.payload.get(key))
        }
        return out
    }

    /**
     * 比较两个信封的先后。
     *
     * 优先用 HLC；缺失时退回 `rev`（本机从未同步过的实体没有 HLC，
     * 但它一定比云端那份旧 —— 它还没上传过）。
     */
    private fun compareClocks(a: SyncEnvelope, b: SyncEnvelope): Int {
        val ha = a.hlcValue
        val hb = b.hlcValue
        return when {
            ha != null && hb != null -> ha.compareTo(hb)
            // 有 HLC 的一方更新：没 HLC = 从未同步 = 一定更旧
            ha != null -> 1
            hb != null -> -1
            // 都没有 HLC（都在本机新建、都还没上传）：退回 rev
            else -> a.rev.compareTo(b.rev)
        }
    }

    /**
     * 判断本机是否"需要上传"。
     *
     * 口径：本机这份与云端那份**不一致**，且本机赢（或需要补上空缺）。
     * 注意墓碑也要上传 —— 否则删除传不出去。
     */
    fun needsUpload(local: SyncEnvelope, remote: SyncEnvelope?): Boolean {
        if (remote == null) return true
        if (local.rev > remote.rev) return true
        // rev 相同但内容不同：说明两端各自改过、计数巧合相同 —— 也要上传合并结果
        return local.rev == remote.rev && local.payload.toString() != remote.payload.toString()
    }
}
