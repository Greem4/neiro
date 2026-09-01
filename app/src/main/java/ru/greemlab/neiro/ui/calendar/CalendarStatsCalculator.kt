package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.ui.util.RU_LOCALE
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle

/** Дни одного месяца из полной карты календаря (для статистики и сетки без скана всего архива). */
internal fun filterDayDataForMonth(
    dayData: Map<LocalDate, List<String>>,
    month: YearMonth,
): Map<LocalDate, List<String>> {
    val year = month.year
    val monthValue = month.monthValue
    val result = HashMap<LocalDate, List<String>>()
    for ((date, sessions) in dayData) {
        if (date.year == year && date.monthValue == monthValue) {
            result[date] = sessions
        }
    }
    return result
}

/** Топ имён учеников по числу записей в переданной карте (обычно один месяц). */
internal fun computeRecentStudents(
    dayData: Map<LocalDate, List<String>>,
    limit: Int = 10,
): List<String> {
    val counts = HashMap<String, Int>()
    for (sessions in dayData.values) {
        for (raw in sessions) {
            val session = SessionParser.parse(raw)
            if (session !is Session.Student) continue
            val name = session.name
            if (name.isBlank()) continue
            counts[name] = (counts[name] ?: 0) + 1
        }
    }
    return counts.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }
}

/** Чистая прибыль за месяц: грязные минус фиксированный налог из профиля, не ниже нуля. */
internal fun monthlyNetProfit(grossEarned: Double, monthlyTaxAmount: Double): Double =
    (grossEarned - monthlyTaxAmount).coerceAtLeast(0.0)

/**
 * Рассчитывает и кэширует статистику за выбранный месяц.
 * [dayData] лучше передавать уже отфильтрованную по месяцу карту ([CalendarViewModel.currentMonthDayData]).
 */
@Composable
fun rememberCalendarMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    rates: EarningsContext,
): CalendarMonthStats = remember(currentMonth, dayData, rates) {
    computeMonthStats(currentMonth, dayData, rates)
}

internal fun computeMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    rates: EarningsContext,
): CalendarMonthStats {
    var completed = 0
    var completedSessions = 0
    var completedDiagnostics = 0
    var completedIntensives = 0
    var scheduled = 0
    var grossEarned = 0.0
    var intensiveEarnings = 0.0
    var diagnosticsEarnings = 0.0
    var expectedIncome = 0.0

    val studentStatsMap = mutableMapOf<String, ru.greemlab.neiro.domain.models.StudentMonthStats>()
    val completedIntensivesList = mutableListOf<ru.greemlab.neiro.domain.models.IntensiveSession>()

    val month: Month = currentMonth.month
    val year = currentMonth.year

    for ((date, sessions) in dayData) {
        if (date.month != month || date.year != year) continue
        for (raw in sessions) {
            val session = SessionParser.parse(raw)

            if (session.isEffectivelyDeleted()) continue

            when (session) {
                is Session.Intensive -> {
                    // Интенсив занятием не считается нигде (0.2.3), поэтому
                    // счётчик «проведено» его не касается — только деньги.
                    if (session.countsTowardEarnings()) {
                        val intensiveAmount = session.totalAmount(
                            rates.pricePerIntensiveChild,
                            onlyArrived = true,
                        )
                        completedIntensives++
                        intensiveEarnings += intensiveAmount
                        grossEarned += intensiveAmount
                        completedIntensivesList.add(
                            ru.greemlab.neiro.domain.models.IntensiveSession(
                                name = session.name.ifBlank { "Интенсив" },
                                date = date,
                                amount = intensiveAmount
                            )
                        )
                    } else {
                        expectedIncome += session.totalAmount(
                            rates.pricePerIntensiveChild,
                            onlyArrived = false,
                        )
                    }
                }

                is Session.Diagnostics -> {
                    scheduled++
                    val price = if (rates.pricePerDiagnostics > 0.0) rates.pricePerDiagnostics else session.amount
                    // «Проведено» — про работу, деньги — про оплату: клиент,
                    // который пришёл и ещё не заплатил, стоит в проведённых, а
                    // его деньги — в ожидаемых (01.09.2026).
                    if (session.countsAsAttended()) {
                        completed++
                        completedDiagnostics++
                    }
                    if (session.countsTowardEarnings()) {
                        diagnosticsEarnings += price
                        grossEarned += price
                    } else {
                        expectedIncome += price
                    }
                }

                is Session.Student -> {
                    scheduled++
                    val pay = rates.pricePerSession
                    val isAttended = session.countsAsAttended()
                    val isPaid = session.countsTowardEarnings()
                    if (isAttended) {
                        completed++
                        completedSessions++
                    }
                    if (isPaid) {
                        grossEarned += pay
                    } else {
                        expectedIncome += pay
                    }

                    // Собираем стастику по ученикам
                    val name = session.name.ifBlank { "Без имени" }
                    val current = studentStatsMap[name] ?: ru.greemlab.neiro.domain.models.StudentMonthStats(name, 0, 0, 0.0)
                    studentStatsMap[name] = current.copy(
                        completedCount = current.completedCount + (if (isAttended) 1 else 0),
                        totalScheduled = current.totalScheduled + 1,
                        totalEarned = current.totalEarned + (if (isPaid) pay else 0.0)
                    )
                }
            }
        }
    }

    val netProfit = monthlyNetProfit(grossEarned, rates.monthlyTaxAmount)

    return CalendarMonthStats(
        completedCount = completed,
        completedSessionsCount = completedSessions,
        completedDiagnosticsCount = completedDiagnostics,
        completedIntensivesCount = completedIntensives,
        totalScheduled = scheduled,
        remainingCount = scheduled - completed,
        totalEarned = grossEarned,
        netProfit = netProfit,
        intensiveEarnings = intensiveEarnings,
        diagnosticsEarnings = diagnosticsEarnings,
        expectedIncome = expectedIncome,
        taxAmount = rates.monthlyTaxAmount,
        statsByStudent = studentStatsMap,
        completedIntensives = completedIntensivesList.sortedBy { it.date },
    )
}

/** Возвращает название месяца на русском языке с заглавной буквы. */
fun getMonthName(month: YearMonth): String =
    month.month
        .getDisplayName(TextStyle.FULL_STANDALONE, RU_LOCALE)
        .replaceFirstChar { it.uppercase(RU_LOCALE) }

/** Короткое название месяца для сетки выбора (например, «Янв»). */
fun getShortMonthName(month: java.time.Month): String =
    month.getDisplayName(TextStyle.SHORT_STANDALONE, RU_LOCALE)
        .replaceFirstChar { it.uppercase(RU_LOCALE) }

/** Сокращения месяцев для графиков: «Янв», «Фев», … без точки. */
fun getChartMonthAbbreviation(month: java.time.Month): String =
    CHART_MONTH_ABBREVIATIONS[month.ordinal]

private val CHART_MONTH_ABBREVIATIONS = listOf(
    "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
    "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
)
