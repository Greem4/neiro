package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.domain.models.SESSION_PRICE_EPOCH
import ru.greemlab.neiro.domain.models.SessionPriceHistoryEntry
import java.time.LocalDate
import java.time.YearMonth

class CalendarStatsCalculatorTest {

    private val month: YearMonth = YearMonth.of(2025, 5)
    private val pricePerSession = 1000.0
    private val monthlyTax = 200.0

    @Test
    fun `empty data yields zero stats`() {
        val stats = computeMonthStats(
            currentMonth = month,
            dayData = emptyMap(),
            pricePerSession = pricePerSession,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = monthlyTax,
        )
        assertEquals(0, stats.completedCount)
        assertEquals(0, stats.totalScheduled)
        assertEquals(0.0, stats.totalEarned, 0.0)
        assertEquals(0.0, stats.netProfit, 0.0)
    }

    @Test
    fun `counts only sessions of current month`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 10) to listOf("Иванов|true", "Петров|false"),
            LocalDate.of(2025, 4, 10) to listOf("Сидоров|true"), // другой месяц
        )
        val stats = computeMonthStats(month, dayData, pricePerSession, 0.0, monthlyTax)
        assertEquals(1, stats.completedCount)
        assertEquals(2, stats.totalScheduled)
        assertEquals(1000.0, stats.totalEarned, 0.0)
        assertEquals(1000.0 - monthlyTax, stats.netProfit, 0.0)
    }

    @Test
    fun `intensive and diagnostics add to earnings only when attended`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf(
                "__INTENSIVE__:2000|Дима|true",
                "__DIAGNOSTICS__:500|Аня|false",
                "Иванов|true",
            ),
        )
        val stats = computeMonthStats(month, dayData, pricePerSession, 0.0, monthlyTax)
        assertEquals(1, stats.completedCount)
        assertEquals(2, stats.totalScheduled)
        assertEquals(2000.0, stats.intensiveEarnings, 0.0)
        assertEquals(0.0, stats.diagnosticsEarnings, 0.0)
        assertEquals(1000.0 + 2000.0, stats.totalEarned, 0.0)
        assertEquals(500.0, stats.expectedIncome, 0.0)
    }

    @Test
    fun `diagnostics uses global price`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf(
                "__DIAGNOSTICS__:500|Аня|true",
            ),
        )
        val stats = computeMonthStats(month, dayData, 0.0, 3000.0, 0.0)
        assertEquals(1, stats.completedCount)
        assertEquals(3000.0, stats.diagnosticsEarnings, 0.0)
    }

    @Test
    fun `month profit uses profile rate regardless of history while payment disabled`() {
        val history = listOf(
            SessionPriceHistoryEntry(SESSION_PRICE_EPOCH, 1200.0),
            SessionPriceHistoryEntry("2025-05-01", 1500.0),
        )
        val april = mapOf(
            LocalDate.of(2025, 4, 10) to listOf("Иванов|true|||1250"),
        )
        val aprilStats = computeMonthStats(
            YearMonth.of(2025, 4),
            april,
            pricePerSession = 1400.0,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = 0.0,
            sessionPriceHistory = history,
        )
        assertEquals(1400.0, aprilStats.totalEarned, 0.0)
    }

    @Test
    fun `net profit is zero when tax exceeds income`() {
        val stats = computeMonthStats(
            currentMonth = month,
            dayData = mapOf(
                LocalDate.of(2025, 5, 10) to listOf("Иванов|true"),
            ),
            pricePerSession = 500.0,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = 1000.0,
        )
        assertEquals(500.0, stats.totalEarned, 0.0)
        assertEquals(0.0, stats.netProfit, 0.0)
    }
}
