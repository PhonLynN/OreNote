package com.phonlynn.oreplan.v2.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VMotion
import com.phonlynn.oreplan.v2.theme.VText
import kotlinx.coroutines.delay

/**
 * 页面中央的小型提示（「已复制」）。
 *
 * ## 用户口径（2026-10-03）
 *
 * > 「复制行为都应该有提示，**震动一下 ＋ 页面中央出现小型弹出提示"已复制"**」
 *
 * 两件事一起做才算完成：震动是**即时**反馈（手指还按在屏幕上时就该有），
 * 提示是**事后**确认（眼睛看到"确实复制了"）。缺任何一半，
 * 用户都会不确定到底成没成功，于是反复长按。
 *
 * ## 为什么是"中央"而不是底部 Snackbar
 *
 * 底部正是**悬浮输入框**所在的位置，提示会被它压住或被键盘顶走。
 * 中央没有这些干扰，而且和「正在生成」那类状态提示不抢位置
 *（状态提示在消息流里，不在屏幕中央）。
 *
 * ## 样式
 *
 * 深色半透明胶囊 + 白字 —— 与页面浅色底反差足够大，扫一眼就能看见，
 * 但又不至于像弹窗那样打断操作。**没有任何按钮**：
 * 它不需要用户回应，只是告诉他"刚才那下成功了"。
 *
 * ## 自动消失
 *
 * [ToastMillis]（1.4 秒）后自动收掉。时间取的是"能读完两个字、但不会赖着不走"
 * 的档位。出现与消失都走 `VMotion` 的曲线，没有 LinearEasing。
 *
 * @param message 非空时显示；由调用方在超时后置回 null（见 [CopiedToastHost]）
 */
@Composable
fun CopiedToast(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(VMotion.RevealMillis, easing = VMotion.Expressive)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(VMotion.RevealMillis, easing = VMotion.Expressive),
            ),
        exit = fadeOut(tween(VMotion.ExitMillis, easing = VMotion.Accelerate)) +
            scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(VMotion.ExitMillis, easing = VMotion.Accelerate),
            ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                // 深色半透明：浅色页面上对比够，又不像弹窗那样"要求你处理它"
                .background(VColors.ink.copy(alpha = 0.88f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                Lucide.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
            VText(
                message.orEmpty(),
                AiTypo.chip,
                color = Color.White,
            )
        }
    }
}

/**
 * 「已复制」提示的**状态托管**。
 *
 * ## 为什么单独抽一个 host
 *
 * 提示是"一次性事件"（显示 → 等一下 → 自己消失），而 `ChatScreen` 里
 * 已经有十来处状态了。把"什么时候该消失"这件事收在这里，
 * 调用方只需要 `toast.show("已复制")`，不用自己起协程、也不用管防抖。
 *
 * ## ⚠️ 连续复制时**重新计时**，不是排队
 *
 * 用户连点两次，第二次应该让提示**重新亮 1.4 秒**，而不是等第一次走完
 * 再显示第二次（那样中间会闪一下）。`LaunchedEffect(key)` 天然是这个语义：
 * key 变了就把上一条协程取消、重新开始计时。
 *
 * ⚠️ 也不能用"message 变了才算新事件"当 key —— 连续复制同一段文字时
 * message 相同，key 不变，第二次就不会重新计时了。
 * 所以 key 用一个**自增的序号**。
 */
class CopiedToastState {
    internal val message = androidx.compose.runtime.mutableStateOf<String?>(null)
    internal val stamp = androidx.compose.runtime.mutableIntStateOf(0)

    /** 弹一条提示（重复调用会让它重新计时）。 */
    fun show(text: String = "已复制") {
        message.value = text
        stamp.intValue++
    }
}

/** 记住一份 [CopiedToastState]（跨重组保留）。 */
@Composable
fun rememberCopiedToastState(): CopiedToastState =
    androidx.compose.runtime.remember { CopiedToastState() }

/**
 * 把 [state] 接到 [CopiedToast] 上，并负责到点自动收掉。
 *
 * 放在 `Box` 的**中央**（`Alignment.Center`）—— 见 [CopiedToast] 的注释。
 */
@Composable
fun CopiedToastHost(state: CopiedToastState, modifier: Modifier = Modifier) {
    LaunchedEffect(state.stamp.intValue) {
        if (state.stamp.intValue == 0) return@LaunchedEffect
        delay(ToastMillis)
        state.message.value = null
    }
    CopiedToast(message = state.message.value, modifier = modifier)
}

/**
 * 提示停留多久。
 *
 * 1.4 秒：够看清"已复制"三个字，又不至于挡着正文读太久。
 * 和系统 Toast 的短档（约 2 秒）相比更短 —— 它是叠加在内容上的，
 * 用户已经在看别的东西了。
 */
private const val ToastMillis = 1_400L
