package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.domain.models.CalendarMonthStats
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
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    monthlyTaxAmount: Double,
): CalendarMonthStats = remember(
    currentMonth,
    dayData,
    pricePerSession,
    pricePerDiagnostics,
    monthlyTaxAmount,
) {
    computeMonthStats(
        currentMonth,
        dayData,
        pricePerSession,
        pricePerDiagnostics,
        monthlyTaxAmount,
    )
}

internal fun computeMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    monthlyTaxAmount: Double,
): CalendarMonthStats {
    var completed = 0
    var completedSessions = 0
    var completedDiagnostics = 0
    var scheduled = 0
    var grossEarned = 0.0
    var intensiveEarnings = 0.0
    var diagnosticsEarnings = 0.0
    var expectedIncome = 0.0

    val studentStatsMap = mutableMapOf<String, ru.greemlab.neiro.domain.models.StudentMonthStats>()

    val month: Month = currentMonth.month
    val year = currentMonth.year

    for ((date, sessions) in dayData) {
        if (date.month != month || date.year != year) continue
        for (raw in sessions) {
            val session = SessionParser.parse(raw)

            if (session.isEffectivelyDeleted()) continue

            when (session) {
                is Session.Intensive -> {
                    if (session.countsTowardEarnings()) {
                        intensiveEarnings += session.amount
                        grossEarned += session.amount
                    } else {
                        expectedIncome += session.amount
                    }
                }

                is Session.Diagnostics -> {
                    scheduled++
                    val price = if (pricePerDiagnostics > 0.0) pricePerDiagnostics else session.amount
                    if (session.countsTowardEarnings()) {
                        completed++
                        completedDiagnostics++
                        diagnosticsEarnings += price
                        grossEarned += price
                    } else {
                        expectedIncome += price
                    }
                }

                is Session.Student -> {
                    scheduled++
                    val pay = pricePerSession
                    val isAttended = session.countsTowardEarnings()
                    if (isAttended) {
                        completed++
                        completedSessions++
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
                        totalEarned = current.totalEarned + (if (isAttended) pay else 0.0)
                    )
                }
            }
        }
    }

    val netProfit = monthlyNetProfit(grossEarned, monthlyTaxAmount)

    return CalendarMonthStats(
        completedCount = completed,
        completedSessionsCount = completedSessions,
        completedDiagnosticsCount = completedDiagnostics,
        totalScheduled = scheduled,
        remainingCount = scheduled - completed,
        totalEarned = grossEarned,
        netProfit = netProfit,
        intensiveEarnings = intensiveEarnings,
        diagnosticsEarnings = diagnosticsEarnings,
        expectedIncome = expectedIncome,
        taxAmount = monthlyTaxAmount,
        statsByStudent = studentStatsMap,
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
