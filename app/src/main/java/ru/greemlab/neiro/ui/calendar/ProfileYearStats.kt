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
 * @param totalNetEarned Сумма чистой прибыли по месяцам года.
 * @param totalTaxAmount Налог за прошедшие месяцы года: он платится ежемесячно
 *   независимо от занятости, но будущие месяцы в сумму не входят — в июле за год
 *   набежало семь платежей, а не двенадцать.
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
    pricePerIntensiveChild: Double = 0.0,
): ProfileYearStats = remember(
    year,
    dayData,
    pricePerSession,
    pricePerDiagnostics,
    monthlyTaxAmount,
    pricePerIntensiveChild,
) {
    computeProfileYearStats(
        year = year,
        dayData = dayData,
        pricePerSession = pricePerSession,
        pricePerDiagnostics = pricePerDiagnostics,
        monthlyTaxAmount = monthlyTaxAmount,
        pricePerIntensiveChild = pricePerIntensiveChild,
    )
}

/**
 * Сколько месяцев года уже прожито на дату [today] — за столько платежей набежал
 * налог. Текущий месяц считается: платёж за него уже наступил.
 */
internal fun elapsedMonthsInYear(year: Int, today: LocalDate): Int = when {
    year < today.year -> 12
    year > today.year -> 0
    else -> today.monthValue
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
    pricePerIntensiveChild: Double = 0.0,
    today: LocalDate = LocalDate.now(),
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
            pricePerIntensiveChild = pricePerIntensiveChild,
        )
        // + completedIntensivesCount: иначе месяц с одними интенсивами показывал
        // «0 занятий», хотя totalNetEarned их сумму уже учитывает (P4).
        val monthCompletedWithIntensives = monthStats.completedCount + monthStats.completedIntensivesCount
        completedSessions += monthCompletedWithIntensives
        monthlyNet[month - 1] = monthStats.netProfit
        monthlyCompleted[month - 1] = monthCompletedWithIntensives
        totalNetEarned += monthStats.netProfit
    }

    val totalTaxAmount = monthlyTaxAmount * elapsedMonthsInYear(year, today)

    return ProfileYearStats(
        year = year,
        completedSessions = completedSessions,
        totalNetEarned = totalNetEarned,
        totalTaxAmount = totalTaxAmount,
        monthlyNet = monthlyNet.toList(),
        monthlyCompleted = monthlyCompleted.toList(),
    )
}
