package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.domain.models.EarningsContext
import java.time.LocalDate

class ProfileTotalsCalculatorTest {

    private val today = LocalDate.of(2025, 5, 15)

    @Test
    fun `empty data yields empty totals`() {
        val totals = computeProfileTotals(
            emptyMap(),
            today = today,
            rates = EarningsContext(pricePerSession = 1000.0),
        )
        assertEquals(ProfileTotals.Empty, totals)
    }

    @Test
    fun `splits past and future sessions correctly`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("A|true", "B|false"), // past
            LocalDate.of(2025, 5, 20) to listOf("C|false"), // future
            LocalDate.of(2025, 5, 15) to listOf("D|true"), // today = past per logic
        )
        val totals = computeProfileTotals(
            dayData,
            today = today,
            rates = EarningsContext(pricePerSession = 1000.0),
        )
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
        val totals = computeProfileTotals(dayData, today = today, rates = EarningsContext.Empty)
        assertEquals(2000.0, totals.totalEarned, 0.0)
        assertEquals(800.0, totals.expectedFromFuture, 0.0)
        assertEquals(0, totals.attendedSessions)
        assertEquals(0, totals.pastSessions)
        assertEquals(1, totals.futureSessions) // Diagnostics counts as session
    }

    @Test
    fun `cancelled sessions are excluded from totals`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("Иванов|2", "Петров|3"),
        )
        val totals = computeProfileTotals(
            dayData,
            today = today,
            rates = EarningsContext(pricePerSession = 1000.0),
        )
        assertEquals(1, totals.pastSessions)
        assertEquals(1, totals.attendedSessions)
        assertEquals(1000.0, totals.totalEarned, 0.0)
    }

    @Test
    fun `net earned subtracts tax per month with calendar entries`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("Иванов|3"),
            LocalDate.of(2025, 6, 1) to listOf("Петров|false"),
        )
        val totals = computeProfileTotals(
            dayData,
            today = today,
            rates = EarningsContext(pricePerSession = 1000.0, monthlyTaxAmount = 6500.0),
        )
        assertEquals(1000.0, totals.totalEarned, 0.0)
        assertEquals(0.0, totals.netEarned, 0.0)
    }

    @Test
    fun `diagnostics uses global price from profile`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 1) to listOf("__DIAGNOSTICS__:500|Аня|true"),
        )
        val totals = computeProfileTotals(
            dayData,
            today = today,
            rates = EarningsContext(pricePerDiagnostics = 4000.0),
        )
        assertEquals(4000.0, totals.totalEarned, 0.0)
        assertEquals(1, totals.attendedSessions)
    }
}
