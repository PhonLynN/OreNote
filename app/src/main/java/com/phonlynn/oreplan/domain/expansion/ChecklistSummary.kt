package com.phonlynn.oreplan.domain.expansion

import com.phonlynn.oreplan.domain.model.ChecklistCount
import com.phonlynn.oreplan.domain.model.ChecklistEntry

/**
 * 备忘清单的汇总。纯函数。
 *
 * 只按 `itemId` 汇总**直接挂在条目上**的清单项，不做父子传递：
 * 「父目标还剩几项没准备」这种数字会把整棵子树的清单混在一起，
 * 而用户看的是「这一件事要准备的东西」。子项各自有自己的进度。
 */
object ChecklistSummary {

    fun counts(entries: List<ChecklistEntry>): Map<String, ChecklistCount> =
        entries
            .groupBy { it.itemId }
            .mapValues { (_, list) ->
                ChecklistCount(done = list.count { it.done }, total = list.size)
            }
}
