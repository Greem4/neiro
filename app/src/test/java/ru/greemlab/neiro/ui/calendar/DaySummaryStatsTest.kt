package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                Session.IntensiveChild("Дима", AttendanceStatus.PAID),
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
                Session.IntensiveChild("Дима", AttendanceStatus.PAID),
            ),
        )
        val stats = computeDayStats(
            listOf(intensive, "Дима|4|18:00-18:50"),
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
            listOf("Иванов|2", "Петров|4"),
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
    fun `arrived but unpaid lesson is conducted, not earned`() {
        // «Пришёл» в YClients — это восклицательный знак на записи, а не
        // оплата: занятие состоялось, но деньги ещё впереди (01.09.2026).
        val stats = computeDayStats(
            listOf("Шкурупий|3", "Савастьянов|4"),
            rates = EarningsContext(pricePerSession = 1500.0),
        )
        assertEquals(2, stats.totalLessons)
        assertEquals(2, stats.attendedLessons)
        assertEquals(1500.0, stats.earned, 0.0)
        assertEquals(1500.0, stats.expected, 0.0)
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

    @Test
    fun `day breakdown splits lessons diagnostics and intensive`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.EXPECTED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.PAID),
                Session.IntensiveChild("Маша", AttendanceStatus.EXPECTED),
            ),
        )
        val stats = computeDayStats(
            listOf(intensive, "Иванов|4", "__DIAGNOSTICS__:500|Аня|true"),
            rates = EarningsContext(
                pricePerSession = 1000.0,
                pricePerIntensiveChild = 800.0,
            ),
        )

        assertEquals(1, stats.lessons.planned)
        assertEquals(1, stats.lessons.attended)
        assertEquals(1000.0, stats.lessons.earned, 0.0)

        assertEquals(1, stats.diagnostics.planned)
        assertEquals(500.0, stats.diagnostics.earned, 0.0)

        assertEquals(1, stats.intensiveCount)
        assertEquals(2, stats.intensives.planned)
        assertEquals(1, stats.intensives.attended)
        assertEquals(1, stats.intensives.pending)
        assertEquals(800.0, stats.intensives.earned, 0.0)
        assertEquals(800.0, stats.intensives.expected, 0.0)

        // Интенсив занятием не считается: в дне ученик и диагностика.
        assertEquals(2, stats.totalLessons)
        assertEquals(2, stats.attendedLessons)
        assertEquals(2, stats.scheduledLessons)
    }

    @Test
    fun `day without intensive has no intensive breakdown`() {
        val stats = computeDayStats(
            listOf("Иванов|3", "Петров|0"),
            rates = EarningsContext(pricePerSession = 1000.0),
        )
        assertEquals(0, stats.intensiveCount)
        assertTrue(stats.intensives.isEmpty)
        assertEquals(2, stats.totalLessons)
        assertEquals(1, stats.attendedLessons)
        assertEquals(1, stats.pendingLessons)
    }

    @Test
    fun `confirmed intensive child is not counted as arrived in breakdown`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.CONFIRMED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.CONFIRMED),
                Session.IntensiveChild("Маша", AttendanceStatus.ARRIVED),
            ),
        )
        val stats = computeDayStats(
            listOf(intensive),
            rates = EarningsContext(pricePerIntensiveChild = 800.0),
        )
        assertEquals(2, stats.intensives.planned)
        assertEquals(1, stats.intensives.attended)
        assertEquals(1, stats.intensives.confirmed)
        assertEquals(0, stats.intensives.pending)
    }

    @Test
    fun `day fact marks earnings as coming from api`() {
        val stats = computeDayStats(
            listOf("Иванов|4"),
            rates = EarningsContext(pricePerSession = 1000.0),
            dayFact = 1500.0,
        )
        assertTrue(stats.earnedFromFact)
        assertEquals(1500.0, stats.earned, 0.0)
        // Разбивка остаётся расчётной — диалог показывает её отдельно от факта.
        assertEquals(1000.0, stats.lessons.earned, 0.0)
    }

    @Test
    fun `cancelled student stays in scheduled and potential earnings`() {
        val stats = computeDayStats(
            listOf("Иванов|2", "Петров|4"),
            rates = EarningsContext(pricePerSession = 1000.0),
        )
        assertEquals(1, stats.lessons.planned)
        assertEquals(1, stats.lessons.cancelled)
        assertEquals(2, stats.lessons.scheduled)
        assertEquals(1000.0, stats.lessons.cancelledAmount, 0.0)

        assertEquals(2, stats.scheduledLessons)
        assertEquals(1, stats.cancelledLessons)
        assertEquals(1000.0, stats.cancelledAmount, 0.0)
        // Потолок дня: пришедший ученик плюс тот, что отменился.
        assertEquals(2000.0, stats.potentialEarned, 0.0)
    }

    @Test
    fun `cancelled intensive child counts as scheduled but not as money`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.CANCELLED),
                Session.IntensiveChild("Маша", AttendanceStatus.PAID),
            ),
        )
        val stats = computeDayStats(
            listOf(intensive),
            rates = EarningsContext(pricePerIntensiveChild = 800.0),
        )
        assertEquals(1, stats.intensives.planned)
        assertEquals(1, stats.intensives.attended)
        assertEquals(1, stats.intensives.cancelled)
        assertEquals(2, stats.intensives.scheduled)
        assertEquals(800.0, stats.intensives.earned, 0.0)
        assertEquals(0.0, stats.intensives.expected, 0.0)
        assertEquals(800.0, stats.intensives.cancelledAmount, 0.0)
        assertEquals(1600.0, stats.potentialEarned, 0.0)
    }

    @Test
    fun `fixed intensive amount loses nothing on cancelled child`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.CANCELLED),
                Session.IntensiveChild("Маша", AttendanceStatus.PAID),
            ),
            amountFixed = true,
        )
        val stats = computeDayStats(
            listOf(intensive),
            rates = EarningsContext(pricePerIntensiveChild = 1400.0),
        )
        assertEquals(1, stats.intensives.cancelled)
        // Сумма задана руками — отмена ребёнка её не трогает, терять нечего.
        assertEquals(0.0, stats.intensives.cancelledAmount, 0.0)
        assertEquals(5600.0, stats.earned, 0.0)
        assertEquals(5600.0, stats.potentialEarned, 0.0)
    }


    @Test
    fun `day with only intensive has zero lessons`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.PAID),
                Session.IntensiveChild("Маша", AttendanceStatus.PAID),
            ),
        )
        val stats = computeDayStats(
            listOf(intensive),
            rates = EarningsContext(pricePerIntensiveChild = 800.0),
        )
        // Интенсив занятием не считается — плитка дня честно покажет ноль.
        assertEquals(0, stats.totalLessons)
        assertEquals(0, stats.attendedLessons)
        assertEquals(0, stats.scheduledLessons)
        // Но сам интенсив и его дети в разборе есть.
        assertEquals(1, stats.intensiveCount)
        assertEquals(2, stats.intensives.attended)
        assertEquals(1600.0, stats.earned, 0.0)
    }
}
