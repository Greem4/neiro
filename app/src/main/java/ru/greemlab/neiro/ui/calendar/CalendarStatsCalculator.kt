package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.domain.models.SessionPriceHistoryEntry
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
    sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
): CalendarMonthStats = remember(
    currentMonth,
    dayData,
    pricePerSession,
    pricePerDiagnostics,
    monthlyTaxAmount,
    sessionPriceHistory,
) {
    computeMonthStats(
        currentMonth,
        dayData,
        pricePerSession,
        pricePerDiagnostics,
        monthlyTaxAmount,
        sessionPriceHistory,
    )
}

internal fun computeMonthStats(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    monthlyTaxAmount: Double,
    sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
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
                    val pay = session.employeePay(pricePerSession, date, sessionPriceHistory)
                    val isAttended = session.attended
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

    val netProfit = (grossEarned - monthlyTaxAmount).coerceAtLeast(0.0)

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
