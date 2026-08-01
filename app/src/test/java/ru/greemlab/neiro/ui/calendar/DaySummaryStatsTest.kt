package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.domain.models.EarningsContext

class DaySummaryStatsTest {

    @Test
    fun `computeDayStats counts attended students`() {
        val stats = computeDayStats(
            listOf("Иванов|true", "Петров|false"),
            rates = EarningsContext(pricePerSession = 1000.0),
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
            rates = EarningsContext(pricePerSession = 1000.0),
        )
        assertEquals(1, stats.totalLessons)
        assertEquals(1, stats.attendedLessons)
        assertEquals(1, stats.confirmedIntensiveChildren)
        assertEquals(0, stats.pendingIntensiveChildren)
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
            rates = EarningsContext(pricePerIntensiveChild = 1000.0),
        )
        assertEquals(1, stats.confirmedIntensiveChildren)
        assertEquals(1, stats.pendingIntensiveChildren)
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
            rates = EarningsContext(
                pricePerSession = 1000.0,
                pricePerIntensiveChild = 800.0,
            ),
        )
        assertEquals(0, stats.totalLessons)
        assertEquals(1, stats.confirmedIntensiveChildren)
        assertEquals(0, stats.pendingIntensiveChildren)
        assertEquals(800.0, stats.earned, 0.0)
    }

    @Test
    fun `fixed intensive amount is not overridden by per-child rate`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            amountFixed = true,
        )
        val stats = computeDayStats(
            listOf(intensive),
            rates = EarningsContext(pricePerIntensiveChild = 1400.0),
        )
        assertEquals(5600.0, stats.earned, 0.0)
        assertEquals(0.0, stats.expected, 0.0)
    }

    @Test
    fun `attended diagnostics adds to earned`() {
        val stats = computeDayStats(
            listOf("__DIAGNOSTICS__:500|Аня|true"),
            rates = EarningsContext.Empty,
        )
        assertEquals(1, stats.totalLessons)
        assertEquals(500.0, stats.earned, 0.0)
        assertEquals(0.0, stats.expected, 0.0)
    }

    @Test
    fun `cancelled student does not count as lesson`() {
        val stats = computeDayStats(
            listOf("Иванов|2", "Петров|3"),
            rates = EarningsContext(pricePerSession = 1000.0),
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
            rates = EarningsContext(pricePerDiagnostics = 2000.0),
        )
        assertEquals(1, stats.totalLessons)
        assertEquals(2000.0, stats.earned, 0.0)
    }

    @Test
    fun `computeDayStats counts confirmed and pending lessons`() {
        val stats = computeDayStats(
            listOf(
                "Иванов|1", // CONFIRMED
                "Петров|0", // EXPECTED
                "Сидоров|3", // ARRIVED
            ),
            rates = EarningsContext(pricePerSession = 1000.0),
        )
        assertEquals(3, stats.totalLessons)
        assertEquals(1, stats.attendedLessons)
        assertEquals(1, stats.confirmedLessons)
        assertEquals(1, stats.pendingLessons)
    }
}
