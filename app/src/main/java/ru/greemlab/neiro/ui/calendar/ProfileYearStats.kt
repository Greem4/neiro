package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.domain.models.SessionPriceHistoryEntry
import java.time.LocalDate
import java.time.YearMonth

/**
 * Сводная статистика за календарный год.
 *
 * @param completedSessions Число проведённых занятий (посещённые ученики и диагностики).
 * @param totalNetEarned Сумма чистой прибыли по месяцам (грязные − налог за каждый месяц).
 * @param monthlyNet Чистая прибыль по месяцам, индекс 0 = январь.
 */
@Immutable
data class ProfileYearStats(
    val year: Int,
    val completedSessions: Int,
    val totalNetEarned: Double,
    val monthlyNet: List<Double>,
) {
    companion object {
        fun empty(year: Int): ProfileYearStats = ProfileYearStats(
            year = year,
            completedSessions = 0,
            totalNetEarned = 0.0,
            monthlyNet = List(12) { 0.0 },
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
    sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
): ProfileYearStats = remember(
    year,
    dayData,
    pricePerSession,
    pricePerDiagnostics,
    monthlyTaxAmount,
    sessionPriceHistory,
) {
    computeProfileYearStats(
        year = year,
        dayData = dayData,
        pricePerSession = pricePerSession,
        pricePerDiagnostics = pricePerDiagnostics,
        monthlyTaxAmount = monthlyTaxAmount,
        sessionPriceHistory = sessionPriceHistory,
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
    sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
): ProfileYearStats {
    var completedSessions = 0
    var totalNetEarned = 0.0
    val monthlyNet = Array(12) { 0.0 }

    for (month in 1..12) {
        val monthStats = computeMonthStats(
            currentMonth = YearMonth.of(year, month),
            dayData = dayData,
            pricePerSession = pricePerSession,
            pricePerDiagnostics = pricePerDiagnostics,
            monthlyTaxAmount = monthlyTaxAmount,
            sessionPriceHistory = sessionPriceHistory,
        )
        completedSessions += monthStats.completedCount
        monthlyNet[month - 1] = monthStats.netProfit
        totalNetEarned += monthStats.netProfit
    }

    return ProfileYearStats(
        year = year,
        completedSessions = completedSessions,
        totalNetEarned = totalNetEarned,
        monthlyNet = monthlyNet.toList(),
    )
}
