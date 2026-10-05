package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.domain.sync.ConnectionState
import com.phonlynn.oreplan.domain.sync.SyncConfig
import com.phonlynn.oreplan.domain.sync.SyncRunner
import com.phonlynn.oreplan.domain.sync.SyncOutcome
import com.phonlynn.oreplan.domain.sync.SyncKeyVault
import com.phonlynn.oreplan.domain.sync.SyncSettings
import com.phonlynn.oreplan.domain.sync.crypto.InvalidKeyCodeException
import com.phonlynn.oreplan.domain.sync.crypto.SyncKeyCodec
import com.phonlynn.oreplan.domain.sync.r2.R2Client
import com.phonlynn.oreplan.domain.sync.r2.R2Config
import com.phonlynn.oreplan.domain.sync.r2.R2Exception
import com.phonlynn.oreplan.v2.icons.Lucide
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 可编辑的凭据字段。用枚举而不是给每个字段写一套 setter。 */
enum class SyncCredentialField(
    val label: String,
    val placeholder: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    ACCOUNT_ID("Account ID", "Cloudflare 概览页右侧", Lucide.Hash),
    BUCKET("存储桶名", "orenote-sync", Lucide.Archive),
    ACCESS_KEY("Access Key ID", "R2 API 令牌", Lucide.Key),
    SECRET("Secret Access Key", "只显示一次", Lucide.Lock),
    PREFIX("路径前缀", "orenote/", Lucide.Folder),
    ;

    fun current(config: SyncConfig): String = when (this) {
        ACCOUNT_ID -> config.accountId
        BUCKET -> config.bucket
        ACCESS_KEY -> config.accessKeyId
        SECRET -> config.secretAccessKey
        PREFIX -> config.keyPrefix
    }

    /**
     * 显示在行右侧的值。
     *
     * ⚠️ **Secret 永远不显示明文**，只显示"已填写 / 未填写"。
     * 否则用户截图设置页就等于泄露了密钥。
     */
    fun display(config: SyncConfig): String {
        val value = current(config)
        return when {
            this == SECRET -> if (value.isBlank()) "未填写" else "已填写 ····${value.takeLast(4)}"
            value.isBlank() -> placeholder
            else -> value
        }
    }
}

/** 密钥弹窗要展示什么。 */
sealed interface KeyDisplay {
    /** 还没有密钥。 */
    data object None : KeyDisplay

    /** 刚生成：展示完整备份码（**只此一次**）。 */
    data class Fresh(val lines: List<String>) : KeyDisplay

    /** 已有密钥：只给指纹。 */
    data class Existing(val fingerprint: String, val canReveal: Boolean) : KeyDisplay
}

enum class CloudSyncDialog { Key, Reset, RegenerateKey }

data class CloudSyncUiState(
    val config: SyncConfig = SyncConfig(),
    val draft: SyncConfig = SyncConfig(),
    val hasKey: Boolean = false,
    val keyFingerprint: String? = null,
    val editing: SyncCredentialField? = null,
    val dialog: CloudSyncDialog? = null,
    val keyDisplay: KeyDisplay = KeyDisplay.None,
    val connection: ConnectionState = ConnectionState.Untested,
    val testing: Boolean = false,
    /** 正在执行一次真实同步。 */
    val syncing: Boolean = false,

    /**
     * 同步进行中的阶段文案（如「正在同步附件… 3/12」）。
     *
     * 为什么要有它：附件多的时候一次同步可能几十秒，没有进度的话
     * 用户看到的是一个静止的「正在同步…」，会以为卡死了。
     */
    val progress: String? = null,
    /** 是否正在输入备份码。 */
    val importingKey: Boolean = false,
    /** 备份码输入草稿。 */
    val importCode: String = "",
    /** 备份码校验失败的原因（留在输入框里显示）。 */
    val importError: String? = null,
) {
    val fields: List<SyncCredentialField> get() = SyncCredentialField.entries

    /** 能不能开启同步：凭据齐 + 有主密钥。缺一个都不该允许开（否则第一上传就会失败）。 */
    val canEnable: Boolean get() = draft.isConfigured && hasKey

    val statusText: String
        get() = when {
            syncing -> "正在上传与合并…"
            !config.enabled -> "同步未开启"
            config.lastSyncAt == null -> "尚未同步过"
            config.lastSyncResult != null -> config.lastSyncResult
            else -> "上次同步：${formatAgo(config.lastSyncAt)}"
        }

    private fun formatAgo(at: Long): String {
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 1440 -> "${minutes / 60} 小时前"
            else -> "${minutes / 1440} 天前"
        }
    }
}

/**
 * 云同步设置页的 ViewModel。
 *
 * ## 只有"保存"才落库（用户 2026-10-02 的既有纪律）
 *
 * 草稿 [CloudSyncUiState.draft] 与已保存的 [CloudSyncUiState.config] 分开：
 * 用户改到一半退出，不该留下半套凭据 —— 那会让同步在下次启动时用一个错误配置去试。
 * 底部「保存设置」是唯一写入口。
 *
 * ## 例外：主密钥立即落库
 *
 * 密钥不进草稿。理由是"生成后必须当场抄走"——若它只存在于草稿里，
 * 用户点开弹窗抄完、又退出不保存，备份码就永远对不上了。
 * 所以生成即写入，并立即展示。
 */
@HiltViewModel
class CloudSyncSettingsViewModel @Inject constructor(
    private val settings: SyncSettings,
    private val keyVault: SyncKeyVault,
    private val runner: SyncRunner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudSyncUiState())
    val uiState: StateFlow<CloudSyncUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.refresh()
            val config = settings.state.value
            val key = keyVault.currentKey()
            _uiState.value = _uiState.value.copy(
                config = config,
                draft = config,
                hasKey = key != null,
                keyFingerprint = key?.let { SyncKeyCodec.fingerprint(it) },
            )
        }
    }

    // ---------------------------------------------------------------- 编辑

    fun startEditing(field: SyncCredentialField) {
        _uiState.value = _uiState.value.copy(editing = field)
    }

    fun editField(field: SyncCredentialField, value: String) {
        val draft = _uiState.value.draft
        _uiState.value = _uiState.value.copy(
            draft = when (field) {
                SyncCredentialField.ACCOUNT_ID -> draft.copy(accountId = value.trim())
                SyncCredentialField.BUCKET -> draft.copy(bucket = value.trim())
                SyncCredentialField.ACCESS_KEY -> draft.copy(accessKeyId = value.trim())
                SyncCredentialField.SECRET -> draft.copy(secretAccessKey = value.trim())
                SyncCredentialField.PREFIX -> draft.copy(keyPrefix = value.trim().ifBlank { "orenote/" })
            },
            // 改了连接信息，之前的测试结论就作废了 —— 不复用"连接正常"
            connection = ConnectionState.Untested,
        )
    }

    fun finishEditing() {
        _uiState.value = _uiState.value.copy(editing = null)
    }

    // ---------------------------------------------------------------- 开关

    fun setEnabled(enabled: Boolean) {
        if (enabled && !_uiState.value.canEnable) return
        val draft = _uiState.value.draft.copy(enabled = enabled)
        // 开关立即生效（它是"要不要同步"这件事本身，不是配置细节）
        _uiState.value = _uiState.value.copy(draft = draft)
        viewModelScope.launch {
            settings.update { draft }
            _uiState.value = _uiState.value.copy(config = settings.state.value)
            // 同步定时闹钟跟着开关走 —— 关掉同步却留着闹钟，
            // 它会每 6 小时醒来一次、发现不该跑再退出，纯属浪费电。
            applyAutoSyncSchedule()
        }
    }

    fun setWifiOnly(value: Boolean) = updateDraft { it.copy(wifiOnly = value) }

    fun setAutoSync(value: Boolean) {
        updateDraft { it.copy(autoSync = value) }
    }

    /**
     * 立即把"自动同步"这个开关落到闹钟上。
     *
     * 与 `setEnabled` 不同，这里是**即时生效**（不等待保存）：
     * 用户拨动开关后如果还要"保存设置"才生效，他会以为没反应。
     */
    fun applyAutoSyncSetting(value: Boolean) {
        setAutoSync(value)
        viewModelScope.launch {
            settings.update { it.copy(autoSync = value) }
            _uiState.value = _uiState.value.copy(
                config = settings.state.value,
                draft = settings.state.value,
            )
            applyAutoSyncSchedule()
        }
    }

    /**
     * 按当前配置排定或取消自动同步闹钟。
     *
     * 调用方（Application / Receiver）通过 `SyncAlarms` 做实际调度。
     * 这里只发一个事件出去 —— ViewModel 不该直接依赖 Android 的 Context。
     */
    private suspend fun applyAutoSyncSchedule() {
        val config = settings.state.value
        _scheduleRequest.value = config.enabled && config.autoSync
    }

    /** true = 需要排定；false = 需要取消。由界面层消费（它有 Context）。 */
    private val _scheduleRequest = MutableStateFlow<Boolean?>(null)
    val scheduleRequest: StateFlow<Boolean?> = _scheduleRequest.asStateFlow()

    fun consumeScheduleRequest() {
        _scheduleRequest.value = null
    }

    private fun updateDraft(transform: (SyncConfig) -> SyncConfig) {
        _uiState.value = _uiState.value.copy(draft = transform(_uiState.value.draft))
    }

    // ---------------------------------------------------------------- 连接测试

    /**
     * 用**草稿**里的凭据测试连通性。
     *
     * 刻意不先保存再测：用户还没决定要不要用这套凭据，
     * 测试失败时不该在数据库里留下一套坏配置。
     */
    fun testConnection() {
        val draft = _uiState.value.draft
        if (!draft.isConfigured) return
        _uiState.value = _uiState.value.copy(testing = true, connection = ConnectionState.Untested)
        viewModelScope.launch {
            val result = runCatching {
                val client = R2Client(
                    R2Config(
                        accountId = draft.accountId,
                        bucket = draft.bucket,
                        accessKeyId = draft.accessKeyId,
                        secretAccessKey = draft.secretAccessKey,
                        keyPrefix = draft.keyPrefix,
                    ),
                )
                client.headBucket()
            }
            _uiState.value = _uiState.value.copy(
                testing = false,
                connection = result.fold(
                    onSuccess = { response ->
                        if (response.isSuccess) {
                            ConnectionState.Success
                        } else {
                            ConnectionState.Failure(describe(response.status))
                        }
                    },
                    onFailure = { e ->
                        ConnectionState.Failure(
                            when (e) {
                                is R2Exception -> describe(e.status)
                                else -> e.message ?: "网络不可达"
                            },
                        )
                    },
                ),
            )
        }
    }

    /** 把状态码翻成人话 —— 用户不该去看 HTTP 状态码。 */
    private fun describe(status: Int): String = when (status) {
        401, 403 -> "凭据不对，或令牌缺少权限"
        404 -> "找不到这个存储桶，检查桶名与 Account ID"
        else -> "连接失败（HTTP $status）"
    }

    // ---------------------------------------------------------------- 主密钥

    fun showKeyDialog() {
        viewModelScope.launch {
            val key = keyVault.currentKey()
            _uiState.value = _uiState.value.copy(
                dialog = CloudSyncDialog.Key,
                keyDisplay = if (key == null) {
                    KeyDisplay.None
                } else {
                    KeyDisplay.Existing(SyncKeyCodec.fingerprint(key), canReveal = false)
                },
            )
        }
    }

    fun generateKey() {
        viewModelScope.launch {
            val (_, code) = keyVault.generateAndStore()
            applyNewKey(code)
        }
    }

    fun askRegenerate() {
        _uiState.value = _uiState.value.copy(dialog = CloudSyncDialog.RegenerateKey)
    }

    fun generateKeyKeepingCloud() {
        viewModelScope.launch {
            val (_, code) = keyVault.generateAndStore()
            applyNewKey(code)
        }
    }

    private fun applyNewKey(code: String) {
        _uiState.value = _uiState.value.copy(
            dialog = CloudSyncDialog.Key,
            hasKey = true,
            keyFingerprint = runCatching {
                SyncKeyCodec.fingerprint(SyncKeyCodec.decode(code))
            }.getOrNull(),
            keyDisplay = KeyDisplay.Fresh(SyncKeyCodec.formatForDisplay(code)),
        )
    }

    fun revealKey() {
        // 目前**刻意不支持**再次显示完整备份码（只在生成时给一次）。
        // 若以后要放开，必须同时加"需要设备凭据解锁"的前置，否则设置页会变成密钥泄露面。
        _uiState.value = _uiState.value.copy(
            keyDisplay = KeyDisplay.Existing(_uiState.value.keyFingerprint.orEmpty(), canReveal = false),
        )
    }

    /**
     * 打开「导入备份码」的输入弹窗。
     *
     * 关掉密钥弹窗、换成输入框 —— 两者不能同时显示（弹窗叠弹窗会失去焦点层级）。
     */
    fun startImportKey() {
        _uiState.value = _uiState.value.copy(dialog = null, keyDisplay = KeyDisplay.None, importingKey = true)
    }

    fun cancelImportKey() {
        _uiState.value = _uiState.value.copy(importingKey = false)
    }

    /** 导入备份码的**草稿**。用户打字时只改这个，确认时才校验。 */
    fun editImportCode(code: String) {
        _uiState.value = _uiState.value.copy(importCode = code, importError = null)
    }

    fun importKey(code: String) {
        viewModelScope.launch {
            val result = runCatching {
                val key = SyncKeyCodec.decode(code)
                keyVault.store(key)
                key
            }
            _uiState.value = result.fold(
                onSuccess = { key ->
                    settings.update { it.copy(keyFingerprint = SyncKeyCodec.fingerprint(key)) }
                    _uiState.value.copy(
                        hasKey = true,
                        keyFingerprint = SyncKeyCodec.fingerprint(key),
                        dialog = null,
                        importingKey = false,
                        importCode = "",
                        importError = null,
                    )
                },
                onFailure = { e ->
                    // 校验失败**留在输入框里**并把原因显示出来 —— 校验位的意义就是
                    // 让"抄错一位"在导入那一刻被发现，而不是等到第一次解密失败。
                    val message = if (e is InvalidKeyCodeException) e.message else "备份码无法识别"
                    _uiState.value.copy(
                        importError = message ?: "备份码无法识别",
                        connection = ConnectionState.Untested,
                    )
                },
            )
        }
    }

    // ---------------------------------------------------------------- 其它

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(dialog = null, keyDisplay = KeyDisplay.None)
    }

    fun askReset() {
        _uiState.value = _uiState.value.copy(dialog = CloudSyncDialog.Reset)
    }

    fun reset() {
        viewModelScope.launch {
            keyVault.clear()
            settings.update { SyncConfig(deviceId = it.deviceId) }
            val config = settings.state.value
            _uiState.value = CloudSyncUiState(
                config = config,
                draft = config,
                hasKey = false,
                keyFingerprint = null,
            )
        }
    }

    /**
     * 立即同步。
     *
     * 走真实的引擎。前提是凭据齐全且有主密钥 —— 缺任何一个都直接给可读的原因，
     * 而不是发起一次必然失败的请求。
     */
    fun syncNow() {
        if (_uiState.value.syncing) return
        _uiState.value = _uiState.value.copy(
            syncing = true,
            connection = ConnectionState.Untested,
            progress = null,
        )
        viewModelScope.launch {
            // 走**统一入口**而不是自己拼一遍流程：
            // 手动点与后台自动必须是同一条路径，否则迟早有一条忘了记账或忘了检查开关。
            val outcome = runner.run(requireEnabled = false) { stage, done, total ->
                _uiState.value = _uiState.value.copy(
                    progress = if (total > 0) "$stage $done/$total" else stage,
                )
            }
            _uiState.value = _uiState.value.copy(
                syncing = false,
                progress = null,
                config = settings.state.value,
                connection = if (outcome.isSuccess) {
                    ConnectionState.Success
                } else {
                    ConnectionState.Failure(outcome.error ?: "同步失败")
                },
            )
        }
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.update { _uiState.value.draft }
            onDone()
        }
    }
}
