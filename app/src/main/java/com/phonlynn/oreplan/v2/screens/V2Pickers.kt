package com.phonlynn.oreplan.v2.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.components.LocalVDialogClose
import com.phonlynn.oreplan.v2.components.VDialog
import com.phonlynn.oreplan.v2.components.VDialogPanel
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历日期弹窗（V2 风格，仿日历页月卡）。
 *
 * 设计稿里的转盘日期选择已废弃，这里自设计一个月历网格：
 * 月份导航 + 星期表头 + 7 列日格，今天/选中态用 accent 高亮。
 * 点某一天即确认（一次点选，不必再按确定）。
 */
@Composable
fun VDatePickerDialog(
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    val today = remember { LocalDate.now() }
    val first = month.atDay(1)
    val leadingBlanks = first.dayOfWeek.value - 1 // 周一 = 0 空白
    val daysInMonth = month.lengthOfMonth()

    VDialog(onDismissRequest = onDismiss, maxWidth = 320.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            VText("选择日期", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(16.dp))

            // 月份导航
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(VColors.surface2, CircleShape)
                        .vPressable(scaleDown = 0.9f) { month = month.minusMonths(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.ChevronLeft, null, Modifier.size(16.dp), tint = VColors.ink2)
                }
                VText(
                    "${month.year}年${month.monthValue}月",
                    VTypo.cardTitle,
                    color = VColors.ink,
                    modifier = Modifier.weight(1f),
                    align = TextAlign.Center,
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .background(VColors.surface2, CircleShape)
                        .vPressable(scaleDown = 0.9f) { month = month.plusMonths(1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = VColors.ink2)
                }
            }
            Spacer(Modifier.height(12.dp))

            // 星期表头
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        VText(label, VTypo.caption, color = VColors.ink3, align = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            // 日格
            val cells = leadingBlanks + daysInMonth
            val rows = (cells + 6) / 7
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(rows) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { col ->
                            val index = row * 7 + col
                            val dayNum = index - leadingBlanks + 1
                            Box(Modifier.weight(1f).height(40.dp)) {
                                if (dayNum in 1..daysInMonth) {
                                    val date = month.atDay(dayNum)
                                    val isToday = date == today
                                    val isSelected = date == initial
                                    val bg = when {
                                        isSelected -> VColors.accent
                                        isToday -> VColors.accentSoft
                                        else -> VColors.surface
                                    }
                                    val fg = when {
                                        isSelected -> androidx.compose.ui.graphics.Color.White
                                        isToday -> VColors.accent
                                        else -> VColors.ink
                                    }
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(40.dp)
                                            .background(bg, RoundedCornerShape(12.dp))
                                            .vPressable(scaleDown = 0.94f) { close { onConfirm(date) } },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        VText("$dayNum", VTypo.bodyMed, color = fg, align = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 快捷回今天
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(VColors.surface2, RoundedCornerShape(12.dp))
                    .vPressable(scaleDown = 0.96f) { close { onConfirm(today) } },
                contentAlignment = Alignment.Center,
            ) {
                VText("回到今天", VTypo.bodyMed, color = VColors.accent)
            }
        }
    }
}

/**
 * 时间弹窗（V2 风格）：小时/分钟两列滚动列表，选中态 accent。
 *
 * [initialMinute] 为 0..1439（当天分钟）。两列用 LazyColumn，选中项 accent 底白字。
 */
@Composable
fun VTimePickerDialog(
    initialMinute: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val init = initialMinute.coerceIn(0, 1439)
    var hour by remember { mutableStateOf(init / 60) }
    var minute by remember { mutableStateOf(init % 60) }

    VDialog(onDismissRequest = onDismiss, maxWidth = 300.dp) {
        val close = LocalVDialogClose.current
        VDialogPanel() {
            VText("选择时间", VTypo.dialogTitle, color = VColors.ink)
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(VColors.surface2, RoundedCornerShape(14.dp))
                    .padding(6.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .height(228.dp)
                        .background(VColors.surface, RoundedCornerShape(10.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VText("小时", VTypo.caption, color = VColors.ink3, modifier = Modifier.padding(top = 8.dp))
                    val hourState = rememberLazyListState(initialFirstVisibleItemIndex = hour)
                    LazyColumn(state = hourState, modifier = Modifier.fillMaxWidth()) {
                        items(24) { h ->
                            val selected = h == hour
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(if (selected) VColors.accentSoft else VColors.surface)
                                    .vPressable(scaleDown = 0.94f) { hour = h },
                                contentAlignment = Alignment.Center,
                            ) {
                                VText(
                                    "%02d".format(h),
                                    VTypo.numValue,
                                    color = if (selected) VColors.accent else VColors.ink2,
                                    align = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .height(228.dp)
                        .background(VColors.surface, RoundedCornerShape(10.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VText("分钟", VTypo.caption, color = VColors.ink3, modifier = Modifier.padding(top = 8.dp))
                    val minuteState = rememberLazyListState(initialFirstVisibleItemIndex = minute)
                    LazyColumn(state = minuteState, modifier = Modifier.fillMaxWidth()) {
                        items(60) { m ->
                            val selected = m == minute
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .background(if (selected) VColors.accentSoft else VColors.surface)
                                    .vPressable(scaleDown = 0.94f) { minute = m },
                                contentAlignment = Alignment.Center,
                            ) {
                                VText(
                                    "%02d".format(m),
                                    VTypo.numValue,
                                    color = if (selected) VColors.accent else VColors.ink2,
                                    align = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(VColors.surface2, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) { close(onDismiss) },
                    contentAlignment = Alignment.Center,
                ) {
                    VText("取消", VTypo.button, color = VColors.ink)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(VColors.accent, RoundedCornerShape(13.dp))
                        .vPressable(scaleDown = 0.96f) { close { onConfirm(hour * 60 + minute) } },
                    contentAlignment = Alignment.Center,
                ) {
                    VText("确定", VTypo.button, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/** 星期中文（用于编辑器时间回显）。 */
internal fun weekdayCn(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/** "M月d日 周X · HH:mm" 回显。 */
internal fun formatDateMinute(date: LocalDate, minute: Int): String =
    "${date.monthValue}月${date.dayOfMonth}日 ${weekdayCn(date)} · %02d:%02d".format(minute / 60, minute % 60)

/** "M月d日 周X" 回显（全天用）。 */
internal fun formatDateOnly(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日 ${weekdayCn(date)}"

/** 时长文案（结束行 chip / 快捷档位回显）。 */
internal fun durationText(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    return when {
        m == 0 -> "0 分钟"
        m < 60 -> "$m 分钟"
        m % 60 == 0 -> "${m / 60} 小时"
        m % 60 == 30 -> "${m / 60}.5 小时"
        else -> "${m / 60} 小时 ${m % 60} 分钟"
    }
}
