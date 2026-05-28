package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable

/**
 * Данные статистики за месяц.
 */
@Immutable
data class CalendarMonthStats(
    val completedCount: Int,
    val completedSessionsCount: Int = 0,
    val completedDiagnosticsCount: Int = 0,
    val completedIntensivesCount: Int = 0,
    val totalScheduled: Int,
    val remainingCount: Int,
    val totalEarned: Double,
    val netProfit: Double,
    val intensiveEarnings: Double,
    val diagnosticsEarnings: Double,
    val expectedIncome: Double,
    val taxAmount: Double,
    val statsByStudent: Map<String, StudentMonthStats> = emptyMap(),
    val completedIntensives: List<IntensiveSession> = emptyList(),
) {
    companion object {
        val Empty = CalendarMonthStats(
            completedCount = 0,
            completedSessionsCount = 0,
            completedDiagnosticsCount = 0,
            completedIntensivesCount = 0,
            totalScheduled = 0,
            remainingCount = 0,
            totalEarned = 0.0,
            netProfit = 0.0,
            intensiveEarnings = 0.0,
            diagnosticsEarnings = 0.0,
            expectedIncome = 0.0,
            taxAmount = 0.0,
            statsByStudent = emptyMap(),
            completedIntensives = emptyList(),
        )
    }
}

/**
 * Статистика по конкретному ученику за месяц.
 */
@Immutable
data class StudentMonthStats(
    val name: String,
    val completedCount: Int,
    val totalScheduled: Int,
    val totalEarned: Double,
)

/**
 * Информация о проведенном интенсиве.
 */
@Immutable
data class IntensiveSession(
    val name: String,
    val date: java.time.LocalDate,
    val amount: Double,
)
