package com.phonlynn.oreplan.v2.screens

import androidx.lifecycle.ViewModel
import com.phonlynn.oreplan.domain.focus.FocusController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 把单例 [FocusController] 交给 Compose 的最小载体。
 *
 * 为什么需要它：`FocusController` 是 `@Singleton` 而不是 `ViewModel`
 * （它必须活得比任何页面都久 —— 见它自己的注释），
 * 而 Compose 页面里拿 Hilt 依赖的标准做法是 `hiltViewModel<T>()`。
 * 这个 ViewModel 只做一件事：把那个单例暴露出来。
 *
 * 它**不持有任何状态**，所以谁拿到它、拿到几次都没有区别。
 */
@HiltViewModel
class FocusHolderViewModel @Inject constructor(
    val controller: FocusController,
) : ViewModel()
