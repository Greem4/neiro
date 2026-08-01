package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.data.SalaryLedger
import ru.greemlab.neiro.domain.models.EarningsContext
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
    profileRates: EarningsContext,
    ledger: SalaryLedger = SalaryLedger.Empty,
    staffId: Long = 0L,
): ProfileYearStats = remember(year, dayData, profileRates, ledger, staffId) {
    computeProfileYearStats(
        year = year,
        dayData = dayData,
        profileRates = profileRates,
        ledger = ledger,
        staffId = staffId,
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

/**
 * Годы с данными в календаре + годы из истории ЗП + текущий год (по убыванию).
 *
 * Годы истории обязательны: месяцы, которых нет в локальном календаре
 * (приложение поставили позже), иначе не покажутся вовсе (FOUNDATION 3.3).
 */
fun availableStatsYears(
    dayData: Map<LocalDate, List<String>>,
    ledgerYears: Set<Int> = emptySet(),
    currentYear: Int = YearMonth.now().year,
): List<Int> {
    val years = dayData.keys.map { it.year }.toMutableSet()
    years += ledgerYears
    years.add(currentYear)
    return years.sortedDescending()
}

internal fun computeProfileYearStats(
    year: Int,
    dayData: Map<LocalDate, List<String>>,
    profileRates: EarningsContext,
    ledger: SalaryLedger = SalaryLedger.Empty,
    staffId: Long = 0L,
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
        val currentMonth = YearMonth.of(year, month)
        val entry = ledger.month(staffId, currentMonth)
        val local = collectMonthLocalFacts(
            dayData = yearDayData,
            month = currentMonth,
            diagnosticsPrice = entry.diagnosticsPriceOr(profileRates),
            intensivePrice = entry.intensivePriceOr(profileRates),
        )
        val monthRates = resolveMonthRates(
            month = currentMonth,
            entry = entry,
            profile = profileRates,
            today = today,
            diagnosticsCount = local.diagnosticsCount,
            diagnosticsSum = local.diagnosticsSum,
            factIntensiveSum = local.factIntensiveSum,
        ).rates

        val hasLocalRecords = yearDayData.any { (date, sessions) ->
            date.monthValue == month && sessions.isNotEmpty()
        }
        val factGross = entry?.factGross
        if (!hasLocalRecords && factGross != null) {
            // Месяца нет в локальном календаре, но в YClients он есть —
            // показываем цифрами из истории (FOUNDATION 3.3).
            val net = monthlyNetProfit(factGross, monthRates.monthlyTaxAmount)
            val sessions = entry?.factSessions ?: 0
            completedSessions += sessions
            monthlyNet[month - 1] = net
            monthlyCompleted[month - 1] = sessions
            totalNetEarned += net
            continue
        }

        val monthStats = computeMonthStats(
            currentMonth = currentMonth,
            dayData = yearDayData,
            rates = monthRates,
        )
        // Интенсивы в счётчик занятий не входят — это отдельный формат.
        // На деньги это не влияет: их сумма уже учтена в netProfit.
        completedSessions += monthStats.completedCount
        monthlyNet[month - 1] = monthStats.netProfit
        monthlyCompleted[month - 1] = monthStats.completedCount
        totalNetEarned += monthStats.netProfit
    }

    val totalTaxAmount = profileRates.monthlyTaxAmount * elapsedMonthsInYear(year, today)

    return ProfileYearStats(
        year = year,
        completedSessions = completedSessions,
        totalNetEarned = totalNetEarned,
        totalTaxAmount = totalTaxAmount,
        monthlyNet = monthlyNet.toList(),
        monthlyCompleted = monthlyCompleted.toList(),
    )
}
