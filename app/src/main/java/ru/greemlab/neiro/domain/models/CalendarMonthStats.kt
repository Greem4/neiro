package ru.greemlab.neiro.domain.models

/**
 * Данные статистики за месяц.
 */
data class CalendarMonthStats(
    val completedCount: Int,
    val totalScheduled: Int,
    val remainingCount: Int,
    val totalEarned: Double, // Грязный доход (факт)
    val netProfit: Double,   // Чистый доход (за вычетом налога)
    val intensiveEarnings: Double,
    val diagnosticsEarnings: Double,
    val expectedIncome: Double,
    val taxAmount: Double
)
