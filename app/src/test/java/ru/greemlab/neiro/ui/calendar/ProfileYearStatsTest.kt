package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ProfileYearStatsTest {

    private val year = 2025
    private val pricePerSession = 1000.0
    private val monthlyTax = 200.0

    @Test
    fun `empty year yields zeros`() {
        val stats = computeProfileYearStats(
            year = year,
            dayData = emptyMap(),
            pricePerSession = pricePerSession,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = monthlyTax,
        )
        assertEquals(0, stats.completedSessions)
        assertEquals(0.0, stats.totalNetEarned, 0.0)
        assertEquals(12, stats.monthlyNet.size)
    }

    @Test
    fun `year tax is monthly amount times twelve`() {
        val stats = computeProfileYearStats(
            year = year,
            dayData = emptyMap(),
            pricePerSession = pricePerSession,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = 6500.0,
        )
        assertEquals(6500.0 * 12, stats.totalTaxAmount, 0.0)
        assertEquals(-6500.0 * 12, stats.totalNetEarned, 0.0)
        stats.monthlyNet.forEach { assertEquals(-6500.0, it, 0.0) }
    }

    @Test
    fun `sums completed sessions and net across months with tax per month`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 10) to listOf("Иванов|true", "Петров|true"),
            LocalDate.of(2025, 6, 1) to listOf("Иванов|true"),
        )
        val stats = computeProfileYearStats(
            year = year,
            dayData = dayData,
            pricePerSession = pricePerSession,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = monthlyTax,
        )
        assertEquals(3, stats.completedSessions)
        val mayNet = 2000.0 - monthlyTax
        val juneNet = 1000.0 - monthlyTax
        assertEquals(mayNet, stats.monthlyNet[4], 0.0)
        assertEquals(juneNet, stats.monthlyNet[5], 0.0)
        val otherMonthsNet = -monthlyTax * 10
        assertEquals(mayNet + juneNet + otherMonthsNet, stats.totalNetEarned, 0.0)
        assertEquals(monthlyTax * 12, stats.totalTaxAmount, 0.0)
        assertEquals(2, stats.monthlyCompleted[4])
        assertEquals(1, stats.monthlyCompleted[5])
    }

    @Test
    fun `available years includes data years and current`() {
        val dayData = mapOf(
            LocalDate.of(2023, 6, 1) to listOf("Иванов|true"),
            LocalDate.of(2025, 1, 2) to listOf("Петров|true"),
        )
        val years = availableStatsYears(dayData, currentYear = 2026)
        assertEquals(listOf(2026, 2025, 2023), years)
    }

    @Test
    fun `ignores data from other years`() {
        val dayData = mapOf(
            LocalDate.of(2024, 12, 31) to listOf("Иванов|true"),
            LocalDate.of(2025, 1, 2) to listOf("Петров|true"),
        )
        val stats = computeProfileYearStats(
            year = year,
            dayData = dayData,
            pricePerSession = pricePerSession,
            pricePerDiagnostics = 0.0,
            monthlyTaxAmount = 0.0,
        )
        assertEquals(1, stats.completedSessions)
        assertEquals(1000.0, stats.totalNetEarned, 0.0)
        assertEquals(1000.0, stats.monthlyNet[0], 0.0)
    }
}
