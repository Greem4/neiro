package ru.greemlab.neiro.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SalaryPeriodSplitterTest {

    private val today = LocalDate.of(2026, 7, 30)

    @Test
    fun `period fully in the future is empty`() {
        val periods = splitSalaryPeriods(
            from = LocalDate.of(2026, 8, 1),
            to = LocalDate.of(2026, 8, 31),
            today = today,
        )
        assertTrue(periods.isEmpty())
    }

    @Test
    fun `period end is trimmed to today`() {
        val periods = splitSalaryPeriods(
            from = LocalDate.of(2026, 7, 1),
            to = LocalDate.of(2026, 12, 31),
            today = today,
        )
        assertEquals(1, periods.size)
        assertEquals(LocalDate.of(2026, 7, 1), periods.first().start)
        assertEquals(today, periods.first().endInclusive)
    }

    @Test
    fun `single year gives single chunk`() {
        val periods = splitSalaryPeriods(
            from = LocalDate.of(2026, 1, 1),
            to = LocalDate.of(2026, 6, 30),
            today = today,
        )
        assertEquals(1, periods.size)
        assertEquals(LocalDate.of(2026, 1, 1), periods.first().start)
        assertEquals(LocalDate.of(2026, 6, 30), periods.first().endInclusive)
    }

    @Test
    fun `three years split by calendar years`() {
        val periods = splitSalaryPeriods(
            from = LocalDate.of(2024, 3, 15),
            to = LocalDate.of(2026, 7, 30),
            today = today,
        )
        assertEquals(3, periods.size)
        assertEquals(LocalDate.of(2024, 3, 15), periods[0].start)
        assertEquals(LocalDate.of(2024, 12, 31), periods[0].endInclusive)
        assertEquals(LocalDate.of(2025, 1, 1), periods[1].start)
        assertEquals(LocalDate.of(2025, 12, 31), periods[1].endInclusive)
        assertEquals(LocalDate.of(2026, 1, 1), periods[2].start)
        assertEquals(today, periods[2].endInclusive)
    }

    @Test
    fun `no chunk is longer than a year`() {
        val periods = splitSalaryPeriods(
            from = LocalDate.of(2024, 1, 1),
            to = LocalDate.of(2026, 7, 30),
            today = today,
        )
        periods.forEach { period ->
            val days = java.time.temporal.ChronoUnit.DAYS.between(period.start, period.endInclusive) + 1
            assertTrue("Кусок длиннее года: $period", days <= 365)
        }
    }

    @Test
    fun `single day period gives one chunk`() {
        val periods = splitSalaryPeriods(
            from = LocalDate.of(2026, 6, 19),
            to = LocalDate.of(2026, 6, 19),
            today = today,
        )
        assertEquals(1, periods.size)
        assertEquals(LocalDate.of(2026, 6, 19), periods.first().start)
        assertEquals(LocalDate.of(2026, 6, 19), periods.first().endInclusive)
    }

    @Test
    fun `chunks are contiguous and cover the period`() {
        val periods = splitSalaryPeriods(
            from = LocalDate.of(2025, 1, 1),
            to = LocalDate.of(2026, 7, 30),
            today = today,
        )
        assertEquals(LocalDate.of(2025, 1, 1), periods.first().start)
        assertEquals(today, periods.last().endInclusive)
        periods.zipWithNext { current, next ->
            assertEquals(current.endInclusive.plusDays(1), next.start)
        }
    }
}
