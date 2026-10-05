package com.phonlynn.oreplan.core.order

/**
 * 白板拖动时的「让位」计算。纯函数，便于把连续性钉在单测里。
 *
 * ## 为什么只有垂直、且恒向下（2026-09-18 按用户反馈重写）
 *
 * 上一版两轴都推，而且方向按「静止卡中心在拖拽卡哪一侧」决定，结果两个真实 bug：
 *
 *  1. **卡片被推出列外**：水平位移上限是卡片宽的 60%，两列布局里卡片会滑出列，
 *     甚至滑到屏幕左右外。**两列的卡片水平位置只有两个合法值**，不该有连续水平位移；
 *     列之间的转移应当交给「重排 → 静态槽位重算」完成（那是平滑的）。
 *
 *  2. **抖动**：`if (dx >= 0) +overlap else -overlap` 在中心过零处从 `+overlap`
 *     瞬间翻到 `-overlap`，一次跳变两倍交叠量。
 *
 * 现在改成：
 *  - 只返回**垂直**位移（水平恒为 0）；
 *  - 方向恒为向下，且用两个**连续因子**把位移在边界处平滑衰减到 0：
 *    · 水平交叠越浅 → 越小（离开水平范围时连续归零）；
 *    · 垂直中心越接近/低于本卡中心 → 越小（中心过零处连续归零，不翻转）。
 *
 * 这样位移是各输入量的连续函数，手指连续移动 → 位移连续变化，不会跳变。
 */
object BoardPush {

    /**
     * 一张静止卡片被拖拽矩形「向下推开」的垂直位移（恒 >= 0）。
     *
     * @param left 静止卡片左上角 X（与 [dragged*] 同坐标系）
     * @param top 静止卡片左上角 Y
     * @param width 静止卡片宽
     * @param height 静止卡片高
     * @param draggedLeft 被拖卡片左边界
     * @param draggedTop 被拖卡片上边界
     * @param draggedRight 被拖卡片右边界
     * @param draggedBottom 被拖卡片下边界
     * @param maxPush 位移上限（避免一张卡被推太远、再撞到下一张造成连锁跳动）
     * @return 垂直位移，恒 >= 0；无交叠或不该让位时返回 0。
     */
    fun pushDown(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        draggedLeft: Float,
        draggedTop: Float,
        draggedRight: Float,
        draggedBottom: Float,
        maxPush: Float,
    ): Float {
        if (width <= 0f || height <= 0f || maxPush <= 0f) return 0f

        // 必须两轴都有投影交叠才算「压住」（避免斜对角远距离被误推）。
        val overlapX = minOf(left + width, draggedRight) - maxOf(left, draggedLeft)
        if (overlapX <= 0f) return 0f
        val overlapY = minOf(top + height, draggedBottom) - maxOf(top, draggedTop)
        if (overlapY <= 0f) return 0f

        val centerY = top + height / 2f
        val draggedCenterY = (draggedTop + draggedBottom) / 2f

        // ① 水平连续因子：按「实际水平交叠占较小宽度的比例」归一。
        //
        // 早先的分母是**本卡**宽度×0.35，所以「窄卡压在宽卡上」时
        // 两侧卡片得到的因子完全不同 —— 宽卡被挤得少、窄卡被挤得多，
        // 看起来就像“挤压中心不在卡片中心”（用户 2026-09-18 反馈）。
        // 改用两卡较小宽度作归一基准后，同样的交叠量得到同样的因子，
        // 挤压才是对称的。
        val minW = minOf(width, draggedRight - draggedLeft).coerceAtLeast(1f)
        val hx = minW * 0.5f
        val fx = if (hx <= 0f) 1f else (overlapX / hx).coerceIn(0f, 1f)

        // ② 垂直连续因子：被拖卡片中心在本卡中心之上时为正，越接近越弱，
        //    过零处恰好归零 —— 因此永远不会出现方向翻转造成的跳变。
        //    分母同样用两卡的较小高度（与 fx 对称），避免高矮不一时挤压不对称。
        val minH = minOf(height, draggedBottom - draggedTop).coerceAtLeast(1f)
        val halfH = minH * 0.5f
        val dy = centerY - draggedCenterY
        val fy = if (halfH <= 0f) 0f else (dy / halfH).coerceIn(0f, 1f)
        if (fy <= 0f) return 0f

        // 幅度 = 垂直交叠量（推到刚好不重叠）夹上限，再乘两个连续因子。
        return overlapY.coerceAtMost(maxPush) * fx * fy
    }
}
