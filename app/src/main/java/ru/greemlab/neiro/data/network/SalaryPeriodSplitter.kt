package ru.greemlab.neiro.data.network

import java.time.LocalDate

/** Предел одного запроса начислений — год (API-HOWTO 6). */
private const val MAX_PERIOD_DAYS = 365L

/**
 * Режет период под ограничения YClients (API-HOWTO 6): будущее не считается
 * (422 «Расчет заработной платы возможен только по текущую дату»), поиск
 * начислений — не больше года (422).
 *
 * Границы кусков — по календарным годам, чтобы одни и те же месяцы всегда
 * попадали в один и тот же запрос между прогонами.
 */
fun splitSalaryPeriods(
    from: LocalDate,
    to: LocalDate,
    today: LocalDate,
): List<ClosedRange<LocalDate>> {
    val end = if (to.isAfter(today)) today else to
    if (from.isAfter(end)) return emptyList()

    val periods = mutableListOf<ClosedRange<LocalDate>>()
    var cursor = from
    while (!cursor.isAfter(end)) {
        val yearEnd = LocalDate.of(cursor.year, 12, 31)
        val chunkEnd = minOf(yearEnd, end, cursor.plusDays(MAX_PERIOD_DAYS - 1))
        periods += cursor..chunkEnd
        cursor = chunkEnd.plusDays(1)
    }
    return periods
}
