package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次同步的**统一入口** —— 手动点「立即同步」与后台自动同步都走这里。
 *
 * ## 为什么必须只有一个入口
 *
 * 同步的调用点会长出好几个：设置页的手动按钮、后台闹钟、将来可能的
 * "保存后自动同步"。若各自拼一遍"读配置 → 取密钥 → 建客户端 → 记账"，
 * 迟早会有某条路径**忘了写 `lastSyncAt`** 或**忘了检查开关** ——
 * 那种 bug 表现为"自动同步好像没生效"，极难查。
 *
 * 所以：检查前置条件、构造客户端、执行、记账，全部收在这里。
 */
@Singleton
class SyncRunner @Inject constructor(
    private val settings: SyncSettings,
    private val keyVault: SyncKeyVault,
    private val engine: SyncEngine,
    private val recycler: TombstoneRecycler,
) {

    /**
     * 执行一次同步。
     *
     * @param requireEnabled true = 后台自动同步（关掉开关就不该跑）；
     *   false = 用户手动点的（用户意图明确，即使开关关着也允许 ——
     *   但那种情况下界面上应当已经提示过）
     */
    suspend fun run(
        requireEnabled: Boolean = false,
        onProgress: (suspend (stage: String, done: Int, total: Int) -> Unit)? = null,
    ): SyncOutcome {
        val config = settings.state.value

        if (requireEnabled && !config.enabled) {
            return SyncOutcome(error = "同步未开启")
        }
        if (!config.isConfigured) {
            return SyncOutcome(error = "先填完连接信息")
        }
        val key = keyVault.currentKey()
            ?: return SyncOutcome(error = "还没有主密钥，无法加解密")

        val r2Config = R2Config(
            accountId = config.accountId,
            bucket = config.bucket,
            accessKeyId = config.accessKeyId,
            secretAccessKey = config.secretAccessKey,
            keyPrefix = config.keyPrefix,
        )

        onProgress?.invoke("正在同步数据…", 0, 0)
        val outcome = runCatching {
            engine.syncAll(
                client = R2Client(r2Config),
                config = r2Config,
                key = key,
                onBlobProgress = { done, total -> onProgress?.invoke("正在同步附件…", done, total) },
            )
        }.getOrElse { e ->
            SyncOutcome(error = e.message ?: "同步失败")
        }

        // 记账：即使失败也记（用户要看的是"上次尝试的结果"，
        // 只记成功会让失败看起来像"从没同步过"）。
        settings.update {
            it.copy(
                lastSyncAt = System.currentTimeMillis(),
                lastSyncResult = outcome.summary(),
                lastMergedCount = outcome.conflicts,
            )
        }

        // 同步成功后顺手回收墓碑 —— 放在这里而不是独立定时任务，
        // 是因为"刚同步完"正是所有设备最可能都已经见过删除的时刻。
        // 失败不影响同步结果（回收是清理，不是功能）。
        if (outcome.isSuccess) {
            runCatching { recycler.recycle(now = System.currentTimeMillis()) }
        }

        return outcome
    }
}
