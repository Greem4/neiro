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
        val allSessions = monthData.values.flatten()
        val completedCount = allSessions.count { SessionParser.isAttended(it) }
        val studentsCount = allSessions.count { !SessionParser.isExtra(it) }
        val totalExtras = allSessions.sumOf { SessionParser.getExtraAmount(it) }
        
        val grossEarnings = (completedCount * pricePerSession) + totalExtras
        val netProfit = if (grossEarnings > 0) grossEarnings - monthlyTaxAmount else 0.0
        
        val expectedGross = (studentsCount * pricePerSession) + totalExtras
        val expectedNet = if (expectedGross > 0) expectedGross - monthlyTaxAmount else 0.0

        CalendarMonthStats(
            completedCount = completedCount,
            totalScheduled = studentsCount,
            remainingCount = studentsCount - completedCount,
            netProfit = netProfit,
            grossEarnings = grossEarnings,
            expectedNet = expectedNet,
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
