package ru.greemlab.neiro.data

import ru.greemlab.neiro.domain.models.MonthEntry
import java.time.LocalDate
import java.time.YearMonth

/**
 * История денег: записи месяцев + факт по дням (FOUNDATION 3.4).
 * Чистая структура — тестируется без Android.
 */
data class SalaryLedger(
    val months: Map<String, MonthEntry> = emptyMap(),
    /** Ключ — "staffId|ISO-дата", значение — salary за день из API. */
    val dailyFact: Map<String, Double> = emptyMap(),
) {
    fun month(staffId: Long, ym: YearMonth): MonthEntry? = months[monthKey(staffId, ym)]

    fun dayFact(staffId: Long, date: LocalDate): Double? = dailyFact[dayKey(staffId, date)]

    fun withMonth(entry: MonthEntry): SalaryLedger =
        copy(months = months + (monthKey(entry.staffId, entry.yearMonth) to entry))

    fun withDayFacts(staffId: Long, facts: Map<LocalDate, Double>): SalaryLedger =
        copy(dailyFact = dailyFact + facts.mapKeys { dayKey(staffId, it.key) })

    /** Годы, за которые есть история — для переключателя лет (FOUNDATION 3.3). */
    fun years(staffId: Long): Set<Int> =
        months.values.filter { it.staffId == staffId }.map { it.year }.toSet()

    companion object {
        val Empty = SalaryLedger()

        fun monthKey(staffId: Long, ym: YearMonth): String =
            "$staffId:${ym.year}-${"%02d".format(ym.monthValue)}"

        fun dayKey(staffId: Long, date: LocalDate): String = "$staffId|$date"
    }
}
