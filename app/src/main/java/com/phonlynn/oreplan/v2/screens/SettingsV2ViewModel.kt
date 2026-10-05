package com.phonlynn.oreplan.v2.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.data.backup.RoomBackupService
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.backup.BackupCodec
import com.phonlynn.oreplan.domain.sync.SyncSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 设置页的短反馈（Toast 文案）。 */
data class SettingsFeedback(val message: String, val isError: Boolean = false)

@HiltViewModel
class SettingsV2ViewModel @Inject constructor(
    private val backupService: RoomBackupService,
    val settingsStore: AppSettingsStore,
    private val syncSettings: SyncSettings,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _feedback = MutableStateFlow<SettingsFeedback?>(null)
    val feedback: StateFlow<SettingsFeedback?> = _feedback.asStateFlow()

    /**
     * 云同步的当前状态流。
     *
     * 入口行要显示**真实状态**（未配置 / 已开启 / 上次同步时间），
     * 而不是一个写死的"仅本机"—— 那种标签在用户开过同步之后就是错的，
     * 而"设置页显示的和实际不一样"是最容易让人不信任同步的地方。
     */
    val syncConfig: StateFlow<com.phonlynn.oreplan.domain.sync.SyncConfig> = syncSettings.state

    init {
        // 设置页可能先从其他入口打开（如刚装完），这里保证拿到库里最新的配置
        viewModelScope.launch { syncSettings.refresh() }
    }

    fun consumeFeedback() {
        _feedback.value = null
    }

    fun suggestedFileName(): String {
        val date = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        return "拓记备份-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.json"
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshot = backupService.export()
                    val json = BackupCodec.encode(snapshot)
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                    snapshot.totalRows
                }
            }
            _busy.value = false
            result.fold(
                onSuccess = { rows -> _feedback.value = SettingsFeedback("已导出 $rows 条记录") },
                onFailure = { error -> _feedback.value = SettingsFeedback("导出失败：${error.message ?: "未知原因"}", isError = true) },
            )
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                    val snapshot = BackupCodec.decode(text)
                    backupService.restore(snapshot)
                    snapshot.totalRows
                }
            }
            _busy.value = false
            result.fold(
                onSuccess = { rows -> _feedback.value = SettingsFeedback("已恢复 $rows 条记录") },
                onFailure = { error -> _feedback.value = SettingsFeedback("恢复失败：${error.message ?: "未知原因"}", isError = true) },
            )
        }
    }
}
