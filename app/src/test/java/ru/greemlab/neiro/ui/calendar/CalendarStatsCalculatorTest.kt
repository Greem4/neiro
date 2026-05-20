package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
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
            monthlyTaxAmount = monthlyTax,
        )
        assertEquals(0, stats.completedCount)
        assertEquals(0, stats.totalScheduled)
        assertEquals(0.0, stats.totalEarned, 0.0)
        // Налог не применяется к нулевому доходу — оставляем отрицательное значение,
        // чтобы UI мог явно его показать.
        assertEquals(-200.0, stats.netProfit, 0.0)
    }

    @Test
    fun `counts only sessions of current month`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 10) to listOf("Иванов|true", "Петров|false"),
            LocalDate.of(2025, 4, 10) to listOf("Сидоров|true"), // другой месяц
        )
        val stats = computeMonthStats(month, dayData, pricePerSession, monthlyTax)
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
        val stats = computeMonthStats(month, dayData, pricePerSession, monthlyTax)
        assertEquals(1, stats.completedCount)
        assertEquals(1, stats.totalScheduled)
        assertEquals(2000.0, stats.intensiveEarnings, 0.0)
        assertEquals(0.0, stats.diagnosticsEarnings, 0.0)
        assertEquals(1000.0 + 2000.0, stats.totalEarned, 0.0)
        assertEquals(500.0, stats.expectedIncome, 0.0)
    }

    @Test
    fun `net profit can be negative when tax exceeds income`() {
        val stats = computeMonthStats(
            currentMonth = month,
            dayData = mapOf(
                LocalDate.of(2025, 5, 10) to listOf("Иванов|true"),
            ),
            pricePerSession = 500.0,
            monthlyTaxAmount = 1000.0,
        )
        // 500 - 1000 = -500, должно сохраняться (а не обрезаться до 0).
        assertEquals(-500.0, stats.netProfit, 0.0)
    }
}
