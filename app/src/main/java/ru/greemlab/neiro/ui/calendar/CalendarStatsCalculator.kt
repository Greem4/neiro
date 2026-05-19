package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val RU_LOCALE: Locale = Locale.forLanguageTag("ru")

/**
 * Рассчитывает и кэширует статистику за выбранный месяц.
 * Делает один проход по всем дням, минуя промежуточные map/filter.
 */
@Composable
fun rememberCalendarMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    monthlyTaxAmount: Double,
): CalendarMonthStats = remember(currentMonth, dayData, pricePerSession, monthlyTaxAmount) {
    computeMonthStats(currentMonth, dayData, pricePerSession, monthlyTaxAmount)
}

internal fun computeMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    monthlyTaxAmount: Double,
): CalendarMonthStats {
    var completed = 0
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
            when (val session = SessionParser.parse(raw)) {
                is Session.Intensive -> {
                    if (session.attended) {
                        intensiveEarnings += session.amount
                        grossEarned += session.amount
                    } else {
                        expectedIncome += session.amount
                    }
                }

                is Session.Diagnostics -> {
                    if (session.attended) {
                        diagnosticsEarnings += session.amount
                        grossEarned += session.amount
                    } else {
                        expectedIncome += session.amount
                    }
                }

                is Session.Student -> {
                    scheduled++
                    if (session.attended) {
                        completed++
                        grossEarned += pricePerSession
                    } else {
                        expectedIncome += pricePerSession
                    }
                }
            }
        }
    }

    val netProfit = (grossEarned - monthlyTaxAmount).coerceAtLeast(0.0)

    return CalendarMonthStats(
        completedCount = completed,
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

/**
 * Возвращает название месяца на русском языке с заглавной буквы.
 */
fun getMonthName(month: YearMonth): String =
    month.month
        .getDisplayName(TextStyle.FULL_STANDALONE, RU_LOCALE)
        .replaceFirstChar { it.uppercase(RU_LOCALE) }
