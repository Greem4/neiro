package ru.greemlab.neiro.ui.calendar

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Расчитывает и кэширует статистику за текущий месяц.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun rememberCalendarMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    monthlyTaxAmount: Double
): CalendarMonthStats {
    return remember(currentMonth, dayData, pricePerSession, monthlyTaxAmount) {
        val monthData = dayData.filterKeys { 
            it.month == currentMonth.month && it.year == currentMonth.year 
        }

        var completedSessionsCount = 0
        var totalScheduledSessionsCount = 0
        var totalGrossEarned = 0.0
        var intensiveEarnings = 0.0
        var diagnosticsEarnings = 0.0
        var expectedIncome = 0.0

        monthData.forEach { (_, sessions) ->
            sessions.forEach { session ->
                when {
                    SessionParser.isIntensive(session) -> {
                        val amount = SessionParser.getExtraAmount(session)
                        intensiveEarnings += amount
                        totalGrossEarned += amount
                    }
                    SessionParser.isDiagnostics(session) -> {
                        val amount = SessionParser.getExtraAmount(session)
                        diagnosticsEarnings += amount
                        totalGrossEarned += amount
                    }
                    !SessionParser.isExtra(session) -> {
                        totalScheduledSessionsCount++
                        if (SessionParser.isAttended(session)) {
                            completedSessionsCount++
                            totalGrossEarned += pricePerSession
                        } else {
                            expectedIncome += pricePerSession
                        }
                    }
                }
            }
        }

        val netProfit = (totalGrossEarned - monthlyTaxAmount).coerceAtLeast(0.0)

        CalendarMonthStats(
            completedCount = completedSessionsCount,
            totalScheduled = totalScheduledSessionsCount,
            remainingCount = totalScheduledSessionsCount - completedSessionsCount,
            totalEarned = totalGrossEarned,
            netProfit = netProfit,
            intensiveEarnings = intensiveEarnings,
            diagnosticsEarnings = diagnosticsEarnings,
            expectedIncome = expectedIncome,
            taxAmount = monthlyTaxAmount
        )
    }
}

/**
 * Возвращает название месяца на русском языке с заглавной буквы.
 */
@RequiresApi(Build.VERSION_CODES.O)
fun getMonthName(month: YearMonth): String {
    return month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))
        .replaceFirstChar { it.uppercase() }
}
