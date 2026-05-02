package ru.greemlab.neiro.domain.models

/**
 * Данные статистики за месяц.
 */
data class CalendarMonthStats(
    val completedCount: Int,
    val totalScheduled: Int,
    val remainingCount: Int,
    val netProfit: Double,
    val grossEarnings: Double,
    val expectedNet: Double,
    val taxAmount: Double
)
