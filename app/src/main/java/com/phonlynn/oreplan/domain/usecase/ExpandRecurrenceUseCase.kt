package com.phonlynn.oreplan.domain.usecase

import com.phonlynn.oreplan.domain.expansion.OccurrenceExpander
import com.phonlynn.oreplan.domain.model.Item
import com.phonlynn.oreplan.domain.model.Occurrence
import com.phonlynn.oreplan.domain.repository.RecurrenceExceptionRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 把一个条目在区间内展开成具体发生，例外从数据库读。
 *
 * 展开逻辑本身在 [OccurrenceExpander]（纯函数）。这个用例只负责「取例外」这一件事 ——
 * 需要批量展开时（比如一次算一整个月的议程）不该走这里，否则每个条目都要访问一次数据库。
 */
class ExpandRecurrenceUseCase @Inject constructor(
    private val exceptionRepository: RecurrenceExceptionRepository,
) {

    suspend operator fun invoke(
        item: Item,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<Occurrence> = OccurrenceExpander.expand(
        item = item,
        exceptions = exceptionRepository.getOf(item.id),
        from = from,
        to = to,
        zone = zone,
    )
}
