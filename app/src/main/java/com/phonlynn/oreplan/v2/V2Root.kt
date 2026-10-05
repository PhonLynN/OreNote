package com.phonlynn.oreplan.v2

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import com.phonlynn.oreplan.v2.ai.AiGestureHost
import com.phonlynn.oreplan.v2.ai.AiOverlay
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.phonlynn.oreplan.v2.screens.BoardArchiveScreenV2
import com.phonlynn.oreplan.v2.screens.BoardCardScreenV2
import com.phonlynn.oreplan.v2.screens.BoardScreenV2
import com.phonlynn.oreplan.v2.screens.BoardSettingsScreenV2
import com.phonlynn.oreplan.v2.screens.BoardTagEditScreenV2
import com.phonlynn.oreplan.v2.screens.TaskArchiveScreenV2
import com.phonlynn.oreplan.v2.screens.CalendarScreenV2
import com.phonlynn.oreplan.v2.screens.CloudSyncSettingsScreen
import com.phonlynn.oreplan.v2.screens.CourseEditScreenV2
import com.phonlynn.oreplan.v2.screens.CourseListScreenV2
import com.phonlynn.oreplan.v2.screens.EditorScreenV2
import com.phonlynn.oreplan.v2.screens.FocusDoneScreen
import com.phonlynn.oreplan.v2.screens.FocusFloatingWindow
import com.phonlynn.oreplan.v2.screens.FocusHolderViewModel
import com.phonlynn.oreplan.v2.screens.FocusRecordsScreen
import com.phonlynn.oreplan.v2.screens.FocusRunningScreen
import com.phonlynn.oreplan.v2.screens.FocusStartScreen
import com.phonlynn.oreplan.v2.screens.PlanArchiveScreenV2
import com.phonlynn.oreplan.v2.screens.PlanGoalEditorScreenV2
import com.phonlynn.oreplan.v2.screens.PlanScreenV2
import com.phonlynn.oreplan.v2.screens.RemindScreenV2
import com.phonlynn.oreplan.v2.screens.RoutineScreenV2
import com.phonlynn.oreplan.v2.screens.PlanSettingsScreenV2
import com.phonlynn.oreplan.v2.screens.ScheduleSettingsScreenV2
import com.phonlynn.oreplan.v2.screens.SettingsScreenV2
import com.phonlynn.oreplan.v2.screens.TimetableScreenV2
import com.phonlynn.oreplan.v2.screens.TodayScreenV2
import com.phonlynn.oreplan.v2.screens.TtSettingsScreenV2
import com.phonlynn.oreplan.v2.theme.VMotion

/** 设置页转场时长：比全局（EnterMillis 300 / ExitMillis 220）长一档。 */
private const val SettingsEnterMillis = 430
private const val SettingsExitMillis = 320

/**
 * V2 根导航。转场统一走非线性动效：
 * - Tab 之间：纯淡入淡出（220ms，快起慢收）；
 * - 进全屏页：淡入 + 轻微上浮（300ms）；
 * - 返回：淡出 + 轻微缩小。
 */
@Composable
fun V2Root() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val navigate: (String) -> Unit = { route -> navController.navigate(route) }
    val back: () -> Unit = { navController.popBackStack() }

    // AI 浮层的可见性。**按钮与手势共用这一个状态** ——
    // 用户要求两者等效；两个独立状态迟早会出现"按钮开了手势关不掉"之类的分叉。
    var aiVisible by remember { mutableStateOf(false) }

    /*
     * 四指下滑 → 唤起 AI。
     *
     * 包在 Scaffold 之外：这样**任意场景**都能触发 ——
     * 包括二级页面、编辑弹窗、甚至白板卡片拖到一半时。
     * 用户原话：「四指下滑唤起的意义就是无论在哪里都能唤起」。
     */
    AiGestureHost(onTrigger = { aiVisible = true }) {

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {

        /*
         * AI 浮层可见时，**底层内容不再参与触摸命中**。
         *
         * 这是「模态」的正确表达方式，而不是在浮层上加消费器：
         * 浮层是底层的**兄弟节点**，兄弟之间的命中顺序由 Box 决定，
         * 在浮层那一侧拦截会遇到 pass 顺序问题（父层级总在子节点之前
         * 拿到同一个 pass，于是把浮层自己的按钮也吃掉了）。
         *
         * 反过来做就干净了：**把底下的入口关掉**。浮层自己的子节点
         * 一切照常，而底下的页面完全收不到事件。
         *
         * 真机验证过的问题：不加这一段时，点浮层底部的模式切换，
         * 底下的 Tab Bar 也会响应（页面切到"日程"，浮层还浮在上面）。
         */
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (aiVisible) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Final)
                                        .changes.forEach { it.consume() }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
        NavHost(
            navController = navController,
            startDestination = V2Routes.TODAY,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            // **同级 Tab 切换统一用淡入淡出**（用户 2026-09-26：
            // 「从白板切换到其他页面的那套逻辑，加入所有方向的切换」）。
            // 不再区分是否涉及白板 —— 之前只有涉及白板才给动画、其余硬切。
            enterTransition = {
                if (V2Routes.isTab(initialState.destination.route) && V2Routes.isTab(targetState.destination.route)) {
                    fadeIn(tween(TabEnterMillis, easing = VMotion.Expressive))
                } else {
                    fadeIn(tween(VMotion.EnterMillis, easing = VMotion.Expressive)) +
                        slideInVertically(tween(VMotion.EnterMillis, easing = VMotion.Expressive)) { it / 22 }
                }
            },
            exitTransition = {
                if (V2Routes.isTab(initialState.destination.route) && V2Routes.isTab(targetState.destination.route)) {
                    fadeOut(tween(TabExitMillis, easing = VMotion.Accelerate))
                } else {
                    fadeOut(tween(VMotion.ExitMillis, easing = VMotion.Accelerate)) +
                        scaleOut(targetScale = 0.985f, animationSpec = tween(VMotion.ExitMillis, easing = VMotion.Accelerate))
                }
            },
            popEnterTransition = {
                fadeIn(tween(VMotion.EnterMillis, easing = VMotion.Expressive)) +
                    scaleIn(initialScale = 0.985f, animationSpec = tween(VMotion.EnterMillis, easing = VMotion.Expressive))
            },
            popExitTransition = {
                fadeOut(tween(VMotion.ExitMillis, easing = VMotion.Accelerate)) +
                    slideOutVertically(tween(VMotion.ExitMillis, easing = VMotion.Accelerate)) { it / 26 }
            },
        ) {
            composable(V2Routes.TODAY) {
                TodayScreenV2(
                    navigate = navigate,
                    onSelectTab = { tab -> navController.switchTab(tab.route) },
                    onAi = { aiVisible = true },
                )
            }
            composable(V2Routes.CALENDAR) {
                CalendarScreenV2(
                    navigate = navigate,
                    onSelectTab = { tab -> navController.switchTab(tab.route) },
                    onAi = { aiVisible = true },
                )
            }
            composable(V2Routes.TIMETABLE) {
                TimetableScreenV2(
                    navigate = navigate,
                    onSelectTab = { tab -> navController.switchTab(tab.route) },
                    onAi = { aiVisible = true },
                )
            }
            composable(V2Routes.BOARD) {
                BoardScreenV2(
                    navigate = navigate,
                    onSelectTab = { tab -> navController.switchTab(tab.route) },
                    onAi = { aiVisible = true },
                )
            }
            composable(V2Routes.PLAN) {
                PlanScreenV2(
                    navigate = navigate,
                    onSelectTab = { tab -> navController.switchTab(tab.route) },
                    onAi = { aiVisible = true },
                )
            }

            // 进入设置页的转场单独放慢一档：设置页层级感更强（从任何 tab 都能进），
            // 用比全局稍长的时长让「进入」这件事更明确。
            composable(
                route = V2Routes.SETTINGS,
                enterTransition = {
                    fadeIn(tween(SettingsEnterMillis, easing = VMotion.Expressive)) +
                        slideInVertically(tween(SettingsEnterMillis, easing = VMotion.Expressive)) { it / 18 }
                },
                exitTransition = {
                    fadeOut(tween(SettingsExitMillis, easing = VMotion.Accelerate)) +
                        scaleOut(targetScale = 0.985f, animationSpec = tween(SettingsExitMillis, easing = VMotion.Accelerate))
                },
                popEnterTransition = {
                    fadeIn(tween(SettingsEnterMillis, easing = VMotion.Expressive)) +
                        scaleIn(initialScale = 0.985f, animationSpec = tween(SettingsEnterMillis, easing = VMotion.Expressive))
                },
                popExitTransition = {
                    fadeOut(tween(SettingsExitMillis, easing = VMotion.Accelerate)) +
                        slideOutVertically(tween(SettingsExitMillis, easing = VMotion.Accelerate)) { it / 22 }
                },
            ) {
                SettingsScreenV2(onBack = back, navigate = navigate)
            }

            composable(V2Routes.TT_SETTINGS) {
                TtSettingsScreenV2(onBack = back, navigate = navigate)
            }

            composable(V2Routes.ROUTINE) {
                RoutineScreenV2(onBack = back)
            }

            composable(V2Routes.COURSES) {
                CourseListScreenV2(onBack = back, navigate = navigate)
            }

            composable(
                route = V2Routes.COURSE_TEMPLATE,
                arguments = listOf(
                    navArgument(V2Routes.ARG_CARD_ID) { type = NavType.StringType; defaultValue = "" },
                    navArgument(V2Routes.ARG_DAY) { type = NavType.IntType; defaultValue = V2Routes.NO_INT },
                    navArgument(V2Routes.ARG_START_MINUTE) { type = NavType.IntType; defaultValue = V2Routes.NO_INT },
                ),
            ) {
                CourseEditScreenV2(onBack = back)
            }

            composable(
                route = V2Routes.EDITOR_TEMPLATE,
                arguments = listOf(
                    navArgument(V2Routes.ARG_ITEM_ID) { type = NavType.StringType; defaultValue = "" },
                    navArgument(V2Routes.ARG_DAY) { type = NavType.IntType; defaultValue = V2Routes.NO_INT },
                    navArgument(V2Routes.ARG_START_MINUTE) { type = NavType.IntType; defaultValue = V2Routes.NO_INT },
                    navArgument(V2Routes.ARG_KIND) { type = NavType.StringType; defaultValue = "" },
                    navArgument(V2Routes.ARG_GROUP_ID) { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                EditorScreenV2(onBack = back, navigate = navigate)
            }

            composable(
                route = V2Routes.REMIND_TEMPLATE,
                arguments = listOf(
                    navArgument(V2Routes.ARG_ITEM_ID) { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                RemindScreenV2(onBack = back)
            }

            composable(
                route = V2Routes.BOARD_CARD_TEMPLATE,
                arguments = listOf(
                    navArgument(V2Routes.ARG_CARD_ID) { type = NavType.StringType; defaultValue = "" },
                    navArgument(V2Routes.ARG_TYPE) { type = NavType.StringType; defaultValue = "" },
                    navArgument(V2Routes.ARG_FOCUS_BODY) { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                BoardCardScreenV2(
                    onBack = back,
                    navigate = navigate,
                    focusBodyOnLoad = entry.arguments?.getBoolean(V2Routes.ARG_FOCUS_BODY) == true,
                )
            }

            composable(
                route = V2Routes.BOARD_TAG_EDIT_TEMPLATE,
                arguments = listOf(
                    navArgument(V2Routes.ARG_TAG_ID) { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                BoardTagEditScreenV2(onBack = back)
            }

            composable(V2Routes.BOARD_SETTINGS) {
                BoardSettingsScreenV2(onBack = back, navigate = navigate)
            }

            composable(V2Routes.BOARD_ARCHIVE) {
                BoardArchiveScreenV2(onBack = back, navigate = navigate)
            }

            composable(
                route = V2Routes.GOAL_TEMPLATE,
                arguments = listOf(
                    navArgument(V2Routes.ARG_GOAL_ID) { type = NavType.StringType; defaultValue = "" },
                    navArgument(V2Routes.ARG_TYPE) { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                PlanGoalEditorScreenV2(onBack = back, navigate = navigate)
            }

            // ---- 专注四页（0.3.0）----
            composable(V2Routes.FOCUS_START) {
                FocusStartScreen(onBack = back, navigate = navigate)
            }
            composable(V2Routes.FOCUS_RUNNING) {
                FocusRunningScreen(onBack = back, navigate = navigate)
            }
            composable(V2Routes.FOCUS_DONE) {
                FocusDoneScreen(onBack = back, navigate = navigate)
            }
            composable(V2Routes.FOCUS_RECORDS) {
                FocusRecordsScreen(onBack = back, navigate = navigate)
            }

            composable(V2Routes.PLAN_ARCHIVE) {
                PlanArchiveScreenV2(onBack = back, navigate = navigate)
            }

            composable(V2Routes.TASK_ARCHIVE) {
                TaskArchiveScreenV2(onBack = back)
            }

            composable(V2Routes.SCHEDULE_SETTINGS) {
                ScheduleSettingsScreenV2(onBack = back, navigate = navigate)
            }

            composable(V2Routes.PLAN_SETTINGS) {
                PlanSettingsScreenV2(onBack = back, navigate = navigate)
            }

            composable(V2Routes.CLOUD_SYNC_SETTINGS) {
                CloudSyncSettingsScreen(onBack = back)
            }
        }

            // 专注悬浮窗（设计稿 gkA8D）：只在**有活动专注**且**不在专注那几页**时出现。
            // 为什么放在 NavHost 之外：它要跨 Tab 一直可见；放进某个页面会被随页面销毁，
            // 而「切到别的 Tab 还能看见计时」正是这个浮窗存在的意义。
            val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
            val holder: FocusHolderViewModel = hiltViewModel()
            val activeFocus by holder.controller.active.collectAsStateWithLifecycle()
            FocusFloatingWindow(
                visible = activeFocus != null &&
                    currentRoute != V2Routes.FOCUS_RUNNING &&
                    currentRoute != V2Routes.FOCUS_DONE,
                onClick = { navigate(V2Routes.FOCUS_RUNNING) },
                holder = holder,
            )
        }
        }  // 模态屏蔽层（浮层可见时吞掉底层触摸）

        /*
         * AI 浮层。
         *
         * ## 位置：**模态屏蔽层之外**（这是真机踩出来的）
         *
         * 一开始我把它放在屏蔽层**里面**，结果浮层自己的按钮也点不动了 ——
         * 因为屏蔽层把落在其范围内的所有触摸都吃掉了，包括浮层的。
         *
         * 正确的层次是：
         *   Box（根）
         *     ├─ Box（底层内容 + 可见时吞事件的屏蔽层）   ← 被屏蔽
         *     └─ AiOverlay                              ← 不受屏蔽影响
         *
         * ## 为什么放在 NavHost 之外
         *
         * 用户要求「无论在哪里都能唤起」以及「退出后回到原来的位置」。
         * 导航跳转会销毁底层页面 —— 滚动位置、未保存的输入、
         * 白板上的临时状态全都会丢。叠加才保得住。
         *
         * 触发方式有两个，**走同一个状态**（用户要求两者等效）：
         *  · 各主 tab 右上角的圆形按钮
         *  · 四指下滑手势（[AiGestureHost]，包在整棵树上）
         */
        AiOverlay(visible = aiVisible, onDismiss = { aiVisible = false })

        /*
         * 上次崩溃的话，这里弹出堆栈并自动复制到剪贴板。
         *
         * 放在最外层：崩溃可能发生在任何页面，报告不该依赖用户恰好进到某一页。
         * 没有崩溃记录时它什么都不画。
         */
        CrashReportDialog()
    }

    }  // AiGestureHost
}

private const val TabEnterMillis = 220
private const val TabExitMillis = 180

/** Tab 切换：**最普通的逻辑** —— 栈里永远只有当前这一个主页。
 *
 * 为什么不照抄官方 bottom-nav 配方（`popUpTo(start){ saveState = true }` +
 * `restoreState = true`）：那套配方是为了「切 Tab 时保留每个 Tab 的滚动位置/状态」，
 * 实现办法是**把弹掉的页面存进已保存状态、下次切回来再恢复回栈**。
 * 而被恢复回来的页面又成了可返回的历史 —— 于是反复跳 Tab 会一层层堆栈，
 * 最后「得退十几个页面才能退出 App」（用户 2026-09-25 实测）。
 * 它的目标与「主页按返回 = 退出 App」天生矛盾：**要保留页面，就不可能只有一个页面**。
 *
 * 所以这里只做一件最普通的事：把旧栈全弹掉（连图根一起，inclusive），
 * 只留目标主页；不存、不恢复任何页面。
 *
 * 状态怎么办：不靠导航层，靠页面自己的 ViewModel（各 Tab 页都是 hiltViewModel；
 * 日程的视图/模式/日期另外写在 AppSettings 里）—— 切回来时筛选、搜索词、
 * 白板标签、日程视图都在。**唯一会归零的是滚动位置**（它在页面的 `remember` 里，
 * 随页面重建而重置），这是本方案唯一的代价。
 *
 * ⚠️ **点当前已选中的 Tab 必须直接返回**：目标就是当前页时，
 * `popUpTo(graph.id) { inclusive = true }` 会先把**当前页弹掉**、随后又被导航回来，
 * 页面因此被销毁重建 —— 表现为「闪一帧黑屏 + 页面重置」（用户 2026-09-25 实测）。
 * 所以先判「目标 = 当前目的地」，是则什么都不做。
 */
private fun androidx.navigation.NavHostController.switchTab(route: String) {
    // 点自己：无目标可切，不做任何导航（否则就是「弹掉再重建」）。
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
