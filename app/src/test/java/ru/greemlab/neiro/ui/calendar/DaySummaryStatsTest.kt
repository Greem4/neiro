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
        assertEquals(1, stats.totalIntensiveChildren)
        assertEquals(1, stats.attendedIntensiveChildren)
        assertEquals(1000.0 + 1500.0, stats.earned, 0.0)
        assertEquals(0.0, stats.expected, 0.0)
    }

    @Test
    fun `intensive children show conducted ratio`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.EXPECTED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Маша", AttendanceStatus.EXPECTED),
            ),
        )
        val stats = computeDayStats(
            listOf(intensive),
            pricePerSession = 0.0,
            pricePerDiagnostics = 0.0,
            pricePerIntensiveChild = 1000.0,
        )
        assertEquals(2, stats.totalIntensiveChildren)
        assertEquals(1, stats.attendedIntensiveChildren)
        assertEquals(1000.0, stats.earned, 0.0)
        assertEquals(1000.0, stats.expected, 0.0)
    }

    @Test
    fun `student on intensive slot is not counted twice`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED),
            ),
        )
        val stats = computeDayStats(
            listOf(intensive, "Дима|3|18:00-18:50"),
            pricePerSession = 1000.0,
            pricePerDiagnostics = 0.0,
            pricePerIntensiveChild = 800.0,
        )
        assertEquals(0, stats.totalLessons)
        assertEquals(1, stats.totalIntensiveChildren)
        assertEquals(1, stats.attendedIntensiveChildren)
        assertEquals(800.0, stats.earned, 0.0)
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
    fun `cancelled student does not count as lesson`() {
        val stats = computeDayStats(
            listOf("Иванов|2", "Петров|3"),
            pricePerSession = 1000.0,
            pricePerDiagnostics = 0.0,
        )
        assertEquals(1, stats.totalLessons)
        assertEquals(1, stats.attendedLessons)
        assertEquals(1000.0, stats.earned, 0.0)
        assertEquals(1000.0, stats.lost, 0.0)
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
