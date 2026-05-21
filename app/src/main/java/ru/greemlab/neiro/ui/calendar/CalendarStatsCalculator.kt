package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.ui.util.RU_LOCALE
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle

/**
 * Рассчитывает и кэширует статистику за выбранный месяц.
 * Один проход по всем дням без промежуточных коллекций.
 */
@Composable
fun rememberCalendarMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    monthlyTaxAmount: Double,
): CalendarMonthStats = remember(currentMonth, dayData, pricePerSession, pricePerDiagnostics, monthlyTaxAmount) {
    computeMonthStats(currentMonth, dayData, pricePerSession, pricePerDiagnostics, monthlyTaxAmount)
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

    val month: Month = currentMonth.month
    val year = currentMonth.year

    for ((date, sessions) in dayData) {
        if (date.month != month || date.year != year) continue
        for (raw in sessions) {
            val session = SessionParser.parse(raw)

            if (session.isEffectivelyDeleted()) continue

            when (session) {
                is Session.Intensive -> {
                    if (session.attended) {
                        intensiveEarnings += session.amount
                        grossEarned += session.amount
                    } else {
                        expectedIncome += session.amount
                    }
                }

                is Session.Diagnostics -> {
                    scheduled++
                    val price = if (pricePerDiagnostics > 0.0) pricePerDiagnostics else session.amount
                    if (session.attended) {
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
                    if (session.attended) {
                        completed++
                        completedSessions++
                        grossEarned += pricePerSession
                    } else {
                        expectedIncome += pricePerSession
                    }
                }
            }
        }
    }

    // Чистый доход может быть отрицательным, если налог превышает выручку —
    // показываем как есть, иначе пользователь не поймёт, что в минусе.
    val netProfit = grossEarned - monthlyTaxAmount

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
    )
}

/** Возвращает название месяца на русском языке с заглавной буквы. */
fun getMonthName(month: YearMonth): String =
    month.month
        .getDisplayName(TextStyle.FULL_STANDALONE, RU_LOCALE)
        .replaceFirstChar { it.uppercase(RU_LOCALE) }
