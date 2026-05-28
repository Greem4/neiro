package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import java.time.LocalDate
import java.time.YearMonth

/**
 * Сводная статистика за календарный год.
 *
 * @param completedSessions Число проведённых занятий (посещённые ученики и диагностики).
 * @param totalNetEarned Сумма чистой прибыли по месяцам (грязные − [monthlyTaxAmount] × 12).
 * @param totalTaxAmount Суммарный налог за год ([monthlyTaxAmount] × 12).
 * @param monthlyNet Чистая прибыль по месяцам, индекс 0 = январь.
 * @param monthlyCompleted Проведённые занятия по месяцам, индекс 0 = январь.
 */
@Immutable
data class ProfileYearStats(
    val year: Int,
    val completedSessions: Int,
    val totalNetEarned: Double,
    val totalTaxAmount: Double,
    val monthlyNet: List<Double>,
    val monthlyCompleted: List<Int>,
) {
    companion object {
        fun empty(year: Int): ProfileYearStats = ProfileYearStats(
            year = year,
            completedSessions = 0,
            totalNetEarned = 0.0,
            totalTaxAmount = 0.0,
            monthlyNet = List(12) { 0.0 },
            monthlyCompleted = List(12) { 0 },
        )
    }
}

@Composable
fun rememberProfileYearStats(
    year: Int,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    monthlyTaxAmount: Double,
): ProfileYearStats = remember(
    year,
    dayData,
    pricePerSession,
    pricePerDiagnostics,
    monthlyTaxAmount,
) {
    computeProfileYearStats(
        year = year,
        dayData = dayData,
        pricePerSession = pricePerSession,
        pricePerDiagnostics = pricePerDiagnostics,
        monthlyTaxAmount = monthlyTaxAmount,
    )
}

/** Годы с данными в календаре + текущий год (по убыванию). */
fun availableStatsYears(
    dayData: Map<LocalDate, List<String>>,
    currentYear: Int = YearMonth.now().year,
): List<Int> {
    val years = dayData.keys.map { it.year }.toMutableSet()
    years.add(currentYear)
    return years.sortedDescending()
}

internal fun computeProfileYearStats(
    year: Int,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    monthlyTaxAmount: Double,
): ProfileYearStats {
    var completedSessions = 0
    var totalNetEarned = 0.0
    val monthlyNet = Array(12) { 0.0 }
    val monthlyCompleted = Array(12) { 0 }

    val yearDayData = buildMap {
        for ((date, sessions) in dayData) {
            if (date.year == year) put(date, sessions)
        }
    }

    for (month in 1..12) {
        val monthStats = computeMonthStats(
            currentMonth = YearMonth.of(year, month),
            dayData = yearDayData,
            pricePerSession = pricePerSession,
            pricePerDiagnostics = pricePerDiagnostics,
            monthlyTaxAmount = monthlyTaxAmount,
        )
        completedSessions += monthStats.completedCount
        monthlyNet[month - 1] = monthStats.netProfit
        monthlyCompleted[month - 1] = monthStats.completedCount
        totalNetEarned += monthStats.netProfit
    }

    val totalTaxAmount = monthlyTaxAmount * 12

    return ProfileYearStats(
        year = year,
        completedSessions = completedSessions,
        totalNetEarned = totalNetEarned,
        totalTaxAmount = totalTaxAmount,
        monthlyNet = monthlyNet.toList(),
        monthlyCompleted = monthlyCompleted.toList(),
    )
}
