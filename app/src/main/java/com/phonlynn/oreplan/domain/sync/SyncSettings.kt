package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.data.local.AppDatabase
import com.phonlynn.oreplan.data.local.entity.AppMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云同步的**配置与身份**：R2 凭据、同步开关、本机 deviceId、上次同步时间。
 *
 * ## 为什么存 `app_meta` 而不是加一张表
 *
 * `app_meta` 就是项目现有的 KV 表，设置类数据本来就在这（外观、提醒开关…）。
 * 加一张 `sync_config` 表意味着又一次 schema 版本提升 ——
 * 而 `SchemaFreezeTest` 把 v11 冻住了，能不动结构就不动。
 *
 * ⚠️ **`app_meta` 不进备份**（历史决定：它混了设置与未保存草稿，见 project-current 第五节）。
 * 因此云同步配置**不会被备份带走** —— 这是可接受的：
 * 换机重装后凭据要重填，而数据本来就从云端拉。
 * 但**deviceId 必须稳定**，所以它单独有个"重生"路径（见 [restoreIdentity]）。
 *
 * ## 加密边界（重要，别误解）
 *
 * 这里存的 Access Key / Secret **目前是明文**（`app_meta` 里的字符串）。
 * 理由：Android 上没有"无需用户解锁即可后台同步"的硬件级密钥保护 ——
 * Keystore 的密钥在设备锁定后不可用，会直接破坏后台同步。
 * 真正的保护是**设备锁屏 + 应用私有目录**（非 root 无法读取）。
 *
 * 主密钥（用于加解密云端对象）走另一条路：它由用户生成，
 * 只需要在**同步那一刻**可用，因此可以更高的保护等级（见 S2 的密钥管理）。
 */
@Singleton
class SyncSettings @Inject constructor(
    private val database: AppDatabase,
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(SyncConfig())
    val state: StateFlow<SyncConfig> = _state.asStateFlow()

    /** 恢复设备身份。App 启动时调用一次（见 `OrePlanApplication.prepareSync`）。 */
    suspend fun restoreIdentity() {
        val existing = read(KEY_DEVICE_ID)
        val id = existing?.takeIf { it.isNotBlank() } ?: newDeviceId().also { write(KEY_DEVICE_ID, it) }
        mutex.withLock {
            _state.value = _state.value.copy(deviceId = id)
            // 顺带把其余配置读进来，避免界面首帧显示默认值再跳变。
            _state.value = loadConfig(id)
        }
    }

    /** 重新读一遍全部配置（设置页保存后、或外部改动后调用）。 */
    suspend fun refresh() = mutex.withLock {
        _state.value = loadConfig(_state.value.deviceId)
    }

    /** 当前 deviceId（若还没 [restoreIdentity] 则返回空串）。 */
    val deviceId: String get() = _state.value.deviceId

    suspend fun update(transform: (SyncConfig) -> SyncConfig) {
        val next = mutex.withLock {
            val updated = transform(_state.value)
            persist(updated)
            _state.value = updated
            updated
        }
        // 触发一次校验，尽早发现"填了一半"的配置
        check(next.isConfigured || !next.enabled) {
            "云同步已启用但凭据不完整"
        }
    }

    /** 本机是否已完成配置（可以尝试同步）。 */
    val isConfigured: Boolean get() = _state.value.isConfigured

    // ---------------------------------------------------------------- 内部

    private suspend fun loadConfig(deviceId: String): SyncConfig = SyncConfig(
        deviceId = deviceId,
        enabled = read(KEY_ENABLED) == "1",
        accountId = read(KEY_ACCOUNT_ID).orEmpty(),
        bucket = read(KEY_BUCKET).orEmpty(),
        accessKeyId = read(KEY_ACCESS_KEY).orEmpty(),
        secretAccessKey = read(KEY_SECRET).orEmpty(),
        keyPrefix = read(KEY_PREFIX).orEmpty(),
        wifiOnly = read(KEY_WIFI_ONLY) != "0",
        autoSync = read(KEY_AUTO_SYNC) != "0",
        lastSyncAt = read(KEY_LAST_SYNC)?.toLongOrNull(),
        lastSyncResult = read(KEY_LAST_RESULT),
        lastMergedCount = read(KEY_LAST_MERGED)?.toIntOrNull() ?: 0,
        keyFingerprint = read(KEY_KEY_FINGERPRINT),
    )

    private suspend fun persist(config: SyncConfig) {
        write(KEY_ENABLED, if (config.enabled) "1" else "0")
        write(KEY_ACCOUNT_ID, config.accountId)
        write(KEY_BUCKET, config.bucket)
        write(KEY_ACCESS_KEY, config.accessKeyId)
        write(KEY_SECRET, config.secretAccessKey)
        write(KEY_PREFIX, config.keyPrefix)
        write(KEY_WIFI_ONLY, if (config.wifiOnly) "1" else "0")
        write(KEY_AUTO_SYNC, if (config.autoSync) "1" else "0")
        config.lastSyncAt?.let { write(KEY_LAST_SYNC, it.toString()) }
        config.lastSyncResult?.let { write(KEY_LAST_RESULT, it) }
        write(KEY_LAST_MERGED, config.lastMergedCount.toString())
        config.keyFingerprint?.let { write(KEY_KEY_FINGERPRINT, it) }
    }

    private suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        database.appMetaDao().find(key)?.value
    }

    private suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        database.appMetaDao().upsert(AppMetaEntity(key, value))
    }

    private fun newDeviceId(): String = java.util.UUID.randomUUID().toString()

    private companion object {
        const val KEY_DEVICE_ID = "sync.device_id"
        const val KEY_ENABLED = "sync.enabled"
        const val KEY_ACCOUNT_ID = "sync.r2_account_id"
        const val KEY_BUCKET = "sync.r2_bucket"
        const val KEY_ACCESS_KEY = "sync.r2_access_key"
        const val KEY_SECRET = "sync.r2_secret"
        const val KEY_PREFIX = "sync.key_prefix"
        const val KEY_WIFI_ONLY = "sync.wifi_only"
        const val KEY_AUTO_SYNC = "sync.auto_sync"
        const val KEY_LAST_SYNC = "sync.last_sync_at"
        const val KEY_LAST_RESULT = "sync.last_result"
        const val KEY_LAST_MERGED = "sync.last_merged"
        const val KEY_KEY_FINGERPRINT = "sync.key_fingerprint"
    }
}

/**
 * 云同步配置的一份快照。
 *
 * [keyPrefix] 默认 `orenote/`：桶里可能还有别的东西，
 * 给一个前缀是"不误删别人对象"的最低成本保险。
 */
data class SyncConfig(
    val deviceId: String = "",
    val enabled: Boolean = false,
    val accountId: String = "",
    val bucket: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val keyPrefix: String = "orenote/",
    val wifiOnly: Boolean = true,
    val autoSync: Boolean = true,
    val lastSyncAt: Long? = null,
    /** 上次同步的结果说明（成功/失败原因），直接显示给用户。 */
    val lastSyncResult: String? = null,
    /** 上次同步自动合并了多少项（冲突数，用于提示）。 */
    val lastMergedCount: Int = 0,
    /** 主密钥指纹（前 8 位），仅用于显示"配的是哪把钥匙"，不是密钥本身。 */
    val keyFingerprint: String? = null,
) {

    /** 凭据是否齐全到可以尝试连接。 */
    val isConfigured: Boolean
        get() = accountId.isNotBlank() &&
            bucket.isNotBlank() &&
            accessKeyId.isNotBlank() &&
            secretAccessKey.isNotBlank()

    /** 还差哪些字段 —— 设置页用它生成"还差 X 项"的提示。 */
    val missingFields: List<String>
        get() = buildList {
            if (accountId.isBlank()) add("Account ID")
            if (bucket.isBlank()) add("存储桶名")
            if (accessKeyId.isBlank()) add("Access Key ID")
            if (secretAccessKey.isBlank()) add("Secret Access Key")
        }
}
