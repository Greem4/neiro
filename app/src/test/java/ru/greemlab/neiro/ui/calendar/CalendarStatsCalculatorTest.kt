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
    fun `computeRecentStudents ranks by frequency in given map only`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("Иванов|true", "Петров|false", "Иванов|true"),
            LocalDate.of(2025, 5, 2) to listOf("__INTENSIVE__:1000|Лагерь|true"),
        )
        assertEquals(listOf("Иванов", "Петров"), computeRecentStudents(dayData, limit = 5))
    }

    @Test
    fun `filterDayDataForMonth keeps only target month`() {
        val full = mapOf(
            LocalDate.of(2025, 5, 10) to listOf("Иванов|true"),
            LocalDate.of(2025, 4, 10) to listOf("Петров|true"),
            LocalDate.of(2024, 5, 1) to listOf("Старый|true"),
        )
        val may = filterDayDataForMonth(full, month)
        assertEquals(1, may.size)
        assertEquals(listOf("Иванов|true"), may[LocalDate.of(2025, 5, 10)])
    }

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
    fun `month stats use per-child rate for api intensives and fixed amount for manual`() {
        val apiIntensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Маша", AttendanceStatus.ARRIVED),
            ),
        )
        val manual = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            amountFixed = true,
        )
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf(apiIntensive, manual),
        )
        val stats = computeMonthStats(
            currentMonth = month,
            dayData = dayData,
            pricePerSession = 0.0,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = 0.0,
            pricePerIntensiveChild = 1400.0,
        )
        assertEquals(2800.0 + 5600.0, stats.intensiveEarnings, 0.0)
        assertEquals(2800.0 + 5600.0, stats.totalEarned, 0.0)
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
    fun `month profit uses pricePerSession from profile`() {
        val april = mapOf(
            LocalDate.of(2025, 4, 10) to listOf("Иванов|true"),
        )
        val aprilStats = computeMonthStats(
            YearMonth.of(2025, 4),
            april,
            pricePerSession = 1400.0,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = 0.0,
        )
        assertEquals(1400.0, aprilStats.totalEarned, 0.0)
    }

    @Test
    fun `net profit is zero when income is lower than tax`() {
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

    @Test
    fun `net profit is zero for month without earnings`() {
        val stats = computeMonthStats(
            currentMonth = month,
            dayData = emptyMap(),
            pricePerSession = 1000.0,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = 6500.0,
        )
        assertEquals(0.0, stats.totalEarned, 0.0)
        assertEquals(0.0, stats.netProfit, 0.0)
    }
}
