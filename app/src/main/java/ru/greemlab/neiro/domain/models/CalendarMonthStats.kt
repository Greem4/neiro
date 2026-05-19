package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable

/**
 * Данные статистики за месяц.
 */
@Immutable
data class CalendarMonthStats(
    val completedCount: Int,
    val totalScheduled: Int,
    val remainingCount: Int,
    val totalEarned: Double,
    val netProfit: Double,
    val intensiveEarnings: Double,
    val diagnosticsEarnings: Double,
    val expectedIncome: Double,
    val taxAmount: Double,
) {
    companion object {
        val Empty = CalendarMonthStats(
            completedCount = 0,
            totalScheduled = 0,
            remainingCount = 0,
            totalEarned = 0.0,
            netProfit = 0.0,
            intensiveEarnings = 0.0,
            diagnosticsEarnings = 0.0,
            expectedIncome = 0.0,
            taxAmount = 0.0,
        )
    }
}
