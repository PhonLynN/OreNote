package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonlynn.oreplan.core.id.Ids
import com.phonlynn.oreplan.data.settings.AppSettingsStore
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.ItemKind
import com.phonlynn.oreplan.domain.model.Reminder
import com.phonlynn.oreplan.domain.repository.ItemRepository
import com.phonlynn.oreplan.domain.repository.ReminderRepository
import com.phonlynn.oreplan.v2.V2Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 自定义提醒的单位。 */
enum class RemindUnit(val factor: Int, val label: String) {
    MINUTE(1, "分钟"),
    HOUR(60, "小时"),
    DAY(24 * 60, "天"),
}

data class RemindUiState(
    val loading: Boolean = true,
    val item: Item? = null,
    val referenceStartAt: Instant? = null,
    val enabled: Boolean = false,
    val offsetMinutes: Int = 15,
    val customExpanded: Boolean = false,
    val customValue: Int = 1,
    val customUnit: RemindUnit = RemindUnit.HOUR,
    val allDayEnabled: Boolean = true,
    val allDayTime: String = "09:00",
)

/**
 * 单项提醒设置页（41 规格）。
 *
 * 提醒模型里 `triggerAt` 只是编辑回填，真正排闹钟由 ReminderPlanner 用
 * `offsetMinutes + 条目 startAt` 重算，所以这里保存时只把 triggerAt 当作
 * 「参考时刻」写进去，offsetMinutes 才是关键。
 */
@HiltViewModel
class RemindV2ViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val reminderRepository: ReminderRepository,
    private val settingsStore: AppSettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val argItemId: String? =
        savedStateHandle.get<String>(V2Routes.ARG_ITEM_ID)?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(RemindUiState())
    val state: StateFlow<RemindUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val item = argItemId?.let { itemRepository.getById(it) }
        val existing = argItemId?.let { reminderRepository.observeOf(it).first().firstOrNull() }
        val settings = settingsStore.settings.first()

        // 参考时刻：日程取 startAt，待办取期望完成期；新建条目（还没有行）退到今天 9:00。
        val reference = when {
            item?.kind == ItemKind.TASK -> item.softDueAt ?: item.startAt
            else -> item?.startAt
        } ?: LocalDate.now(zone).atTime(9, 0).atZone(zone).toInstant()

        _state.value = RemindUiState(
            loading = false,
            item = item,
            referenceStartAt = reference,
            enabled = existing?.enabled ?: false,
            offsetMinutes = existing?.offsetMinutes ?: settings.reminderLeadMinutes,
            allDayEnabled = existing != null,
            allDayTime = settings.allDayReminderTime,
        )
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    /** 点预设 chip：直接选中该档位并收起自定义。 */
    fun selectPreset(minutes: Int) = update {
        it.copy(offsetMinutes = minutes, customExpanded = false)
    }

    /** 点「自定义」：把当前 offset 拆成 数值 + 单位 回填。 */
    fun expandCustom() = update { state ->
        val (value, unit) = decompose(state.offsetMinutes)
        state.copy(customExpanded = true, customValue = value, customUnit = unit)
    }

    fun setCustomValue(value: Int) = update { state ->
        val v = value.coerceAtLeast(1)
        state.copy(customValue = v, offsetMinutes = v * state.customUnit.factor)
    }

    fun setCustomUnit(unit: RemindUnit) = update { state ->
        state.copy(customUnit = unit, offsetMinutes = state.customValue * unit.factor)
    }

    fun setAllDayEnabled(enabled: Boolean) = update { it.copy(allDayEnabled = enabled) }

    fun setAllDayTime(hhmm: String) = update { it.copy(allDayTime = hhmm) }

    fun save(onSaved: () -> Unit) {
        val itemId = argItemId ?: return
        val current = _state.value
        viewModelScope.launch {
            reminderRepository.deleteOf(itemId)
            val reference = current.referenceStartAt

            if (current.enabled && reference != null) {
                reminderRepository.upsert(
                    Reminder(
                        id = Ids.newId(),
                        itemId = itemId,
                        triggerAt = reference.minus(
                            Duration.ofMinutes(current.offsetMinutes.toLong()),
                        ),
                        offsetMinutes = current.offsetMinutes,
                        enabled = true,
                    ),
                )
            }

            // 全天事件提醒：在事件当天 HH:mm 响。ReminderPlanner 只用 offsetMinutes，
            // 存负偏移让「startAt - offset」落在当天 HH:mm（全天事件 startAt 为当天 0 点）。
            if (current.allDayEnabled && current.item?.allDay == true && reference != null) {
                val minuteOfDay = remindParseHm(current.allDayTime)
                reminderRepository.upsert(
                    Reminder(
                        id = Ids.newId(),
                        itemId = itemId,
                        triggerAt = reference.plus(Duration.ofMinutes(minuteOfDay.toLong())),
                        offsetMinutes = -minuteOfDay,
                        enabled = true,
                    ),
                )
            }

            onSaved()
        }
    }

    private fun decompose(minutes: Int): Pair<Int, RemindUnit> {
        val m = minutes.coerceAtLeast(1)
        return when {
            m % (24 * 60) == 0 -> m / (24 * 60) to RemindUnit.DAY
            m % 60 == 0 -> m / 60 to RemindUnit.HOUR
            else -> m to RemindUnit.MINUTE
        }
    }

    private fun update(transform: (RemindUiState) -> RemindUiState) {
        _state.value = transform(_state.value)
    }
}

/** "09:00" → 当天分钟。 */
internal fun remindParseHm(text: String): Int {
    val parts = text.split(":")
    if (parts.size != 2) return 540
    return ((parts[0].toIntOrNull() ?: 9) * 60 + (parts[1].toIntOrNull() ?: 0)).coerceIn(0, 1439)
}

/** 当天分钟 → "09:00"。 */
internal fun remindFormatHm(minutes: Int): String =
    "%02d:%02d".format((minutes.coerceIn(0, 1439)) / 60, (minutes.coerceIn(0, 1439)) % 60)
