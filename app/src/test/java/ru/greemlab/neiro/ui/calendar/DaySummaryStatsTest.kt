package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class DaySummaryStatsTest {

    @Test
    fun `computeDayStats counts attended students`() {
        val stats = computeDayStats(
            listOf("Иванов|true", "Петров|false"),
            pricePerSession = 1000.0,
            pricePerDiagnostics = 0.0,
        )
        assertEquals(2, stats.totalLessons)
        assertEquals(1, stats.attendedLessons)
        assertEquals(1000.0, stats.earned, 0.0)
        assertEquals(1000.0, stats.expected, 0.0)
    }

    @Test
    fun `intensive does not count as student lesson`() {
        val stats = computeDayStats(
            listOf("__INTENSIVE__:1500|Дима|true", "Иванов|true"),
            pricePerSession = 1000.0,
            pricePerDiagnostics = 0.0,
        )
        assertEquals(1, stats.totalLessons)
        assertEquals(1, stats.attendedLessons)
        assertEquals(1000.0 + 1500.0, stats.earned, 0.0)
        assertEquals(0.0, stats.expected, 0.0)
    }

    @Test
    fun `attended diagnostics adds to earned`() {
        val stats = computeDayStats(
            listOf("__DIAGNOSTICS__:500|Аня|true"),
            pricePerSession = 0.0,
            pricePerDiagnostics = 0.0,
        )
        assertEquals(1, stats.totalLessons)
        assertEquals(500.0, stats.earned, 0.0)
        assertEquals(0.0, stats.expected, 0.0)
    }

    @Test
    fun `diagnostics uses global price if provided`() {
        val stats = computeDayStats(
            listOf("__DIAGNOSTICS__:500|Аня|true"),
            pricePerSession = 0.0,
            pricePerDiagnostics = 2000.0,
        )
        assertEquals(1, stats.totalLessons)
        assertEquals(2000.0, stats.earned, 0.0)
    }
}
