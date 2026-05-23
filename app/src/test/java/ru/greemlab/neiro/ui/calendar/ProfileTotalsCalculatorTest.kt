package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ProfileTotalsCalculatorTest {

    private val today = LocalDate.of(2025, 5, 15)

    @Test
    fun `empty data yields empty totals`() {
        val totals = computeProfileTotals(emptyMap(), pricePerSession = 1000.0, pricePerDiagnostics = 0.0, today = today, monthlyTaxAmount = 0.0)
        assertEquals(ProfileTotals.Empty, totals)
    }

    @Test
    fun `splits past and future sessions correctly`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("A|true", "B|false"), // past
            LocalDate.of(2025, 5, 20) to listOf("C|false"), // future
            LocalDate.of(2025, 5, 15) to listOf("D|true"), // today = past per logic
        )
        val totals = computeProfileTotals(dayData, pricePerSession = 1000.0, pricePerDiagnostics = 0.0, today = today)
        assertEquals(3, totals.pastSessions)
        assertEquals(1, totals.futureSessions)
        assertEquals(2, totals.attendedSessions)
        assertEquals(2 * 1000.0, totals.totalEarned, 0.0)
        // Только из будущего считаем ожидание.
        assertEquals(1000.0, totals.expectedFromFuture, 0.0)
    }

    @Test
    fun `extra sessions add to earned only when attended`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("__INTENSIVE__:2000|Дима|true"),
            LocalDate.of(2025, 5, 20) to listOf("__DIAGNOSTICS__:800|Аня|false"),
        )
        val totals = computeProfileTotals(dayData, pricePerSession = 0.0, pricePerDiagnostics = 0.0, today = today)
        assertEquals(2000.0, totals.totalEarned, 0.0)
        assertEquals(800.0, totals.expectedFromFuture, 0.0)
        assertEquals(0, totals.attendedSessions)
        assertEquals(0, totals.pastSessions)
        assertEquals(1, totals.futureSessions) // Diagnostics counts as session
    }

    @Test
    fun `uses profile rate not stored pay while payment logic disabled`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("Иванов|3|||1250"),
        )
        val totals = computeProfileTotals(dayData, pricePerSession = 1400.0, pricePerDiagnostics = 0.0, today = today)
        assertEquals(1400.0, totals.totalEarned, 0.0)
    }

    @Test
    fun `diagnostics uses global price from profile`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("__DIAGNOSTICS__:500|Аня|true"),
        )
        val totals = computeProfileTotals(dayData, pricePerSession = 0.0, pricePerDiagnostics = 4000.0, today = today)
        assertEquals(4000.0, totals.totalEarned, 0.0)
        assertEquals(1, totals.attendedSessions)
    }
}
