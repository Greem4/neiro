package ru.greemlab.neiro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.greemlab.neiro.data.network.DayFact
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate

/**
 * Числа в тестах — живые, сняты с фирмы 520135 20.08.2026: в июле ставка 1400
 * (закрытое начисление), с августа 1500, и увидеть её можно только по дням.
 */
class DailySessionRateTest {

    private val today = LocalDate.of(2026, 8, 20)

    private fun students(count: Int): List<String> = List(count) { "Ученик $it|4" }

    @Test
    fun `price comes from the last clean day`() {
        val facts = mapOf(
            LocalDate.of(2026, 7, 31) to DayFact(salary = 8400.0, servicesCount = 6, groupServicesCount = 0),
            today to DayFact(salary = 9000.0, servicesCount = 6, groupServicesCount = 0),
        )
        val dayData = mapOf(
            LocalDate.of(2026, 7, 31) to students(6),
            today to students(6),
        )

        val rate = sessionRateFromDailyFacts(facts, dayData, today)

        assertEquals(today, rate?.date)
        assertEquals(1500.0, rate?.pricePerSession ?: 0.0, 0.0)
    }

    @Test
    fun `day with diagnostics is skipped`() {
        // 5 занятий по 1500 плюс диагностика 2250 — деление дало бы 1625.
        val mixedDay = LocalDate.of(2026, 8, 19)
        val cleanDay = LocalDate.of(2026, 8, 18)
        val facts = mapOf(
            mixedDay to DayFact(salary = 9750.0, servicesCount = 6, groupServicesCount = 0),
            cleanDay to DayFact(salary = 9000.0, servicesCount = 6, groupServicesCount = 0),
        )
        val dayData = mapOf(
            mixedDay to students(5) + "__DIAGNOSTICS__:2250|Аня|4",
            cleanDay to students(6),
        )

        val rate = sessionRateFromDailyFacts(facts, dayData, today)

        assertEquals(cleanDay, rate?.date)
        assertEquals(1500.0, rate?.pricePerSession ?: 0.0, 0.0)
    }

    @Test
    fun `day with intensive is skipped`() {
        // 29.07.2026 живьём: услуг ноль, группа одна, начислено 4200.
        val intensiveDay = LocalDate.of(2026, 7, 29)
        val facts = mapOf(
            intensiveDay to DayFact(salary = 4200.0, servicesCount = 0, groupServicesCount = 1),
        )
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED)),
        )

        assertNull(sessionRateFromDailyFacts(facts, mapOf(intensiveDay to listOf(intensive)), today))
    }

    @Test
    fun `day where calendar disagrees with api is skipped`() {
        // В календаре пять занятий, YClients начислил за шесть: чего-то не видно.
        val facts = mapOf(today to DayFact(salary = 9000.0, servicesCount = 6, groupServicesCount = 0))

        assertNull(sessionRateFromDailyFacts(facts, mapOf(today to students(5)), today))
    }

    @Test
    fun `day without local calendar is skipped`() {
        val facts = mapOf(today to DayFact(salary = 9000.0, servicesCount = 6, groupServicesCount = 0))

        assertNull(sessionRateFromDailyFacts(facts, emptyMap(), today))
    }

    @Test
    fun `only arrived sessions count`() {
        // Шестеро пришли, седьмой ожидается и в начисление не входит.
        val facts = mapOf(today to DayFact(salary = 9000.0, servicesCount = 6, groupServicesCount = 0))
        val dayData = mapOf(today to students(6) + "Ожидающий|0")

        val rate = sessionRateFromDailyFacts(facts, dayData, today)
        assertEquals(1500.0, rate?.pricePerSession ?: 0.0, 0.0)
    }

    @Test
    fun `fractional price is skipped`() {
        // 9100 ÷ 6 = 1516,67 — в сумму дня попало что-то помимо ставки.
        val facts = mapOf(today to DayFact(salary = 9100.0, servicesCount = 6, groupServicesCount = 0))

        assertNull(sessionRateFromDailyFacts(facts, mapOf(today to students(6)), today))
    }

    @Test
    fun `days older than depth are ignored`() {
        val old = today.minusDays(DAILY_RATE_DEPTH_DAYS + 1)
        val facts = mapOf(old to DayFact(salary = 8400.0, servicesCount = 6, groupServicesCount = 0))

        assertNull(sessionRateFromDailyFacts(facts, mapOf(old to students(6)), today))
    }

    @Test
    fun `daily rate overwrites the rate of a closed month`() {
        val profile = UserProfile(pricePerSession = 1400.0)

        val updated = applyDailyRateToProfile(
            profile = profile,
            rate = DailySessionRate(today, 1500.0),
            periodEnd = LocalDate.of(2026, 7, 31),
        )

        assertEquals(1500.0, updated.pricePerSession, 0.0)
    }

    @Test
    fun `day inside a closed month does not overwrite its rate`() {
        // Начисление за июль знает про диагностики, день из июля — нет.
        val profile = UserProfile(pricePerSession = 1400.0)

        val updated = applyDailyRateToProfile(
            profile = profile,
            rate = DailySessionRate(LocalDate.of(2026, 7, 15), 1250.0),
            periodEnd = LocalDate.of(2026, 7, 31),
        )

        assertEquals(1400.0, updated.pricePerSession, 0.0)
    }

    @Test
    fun `manual price is never overwritten`() {
        val profile = UserProfile(
            pricePerSession = 1400.0,
            sessionPriceOrigin = PriceOrigin.MANUAL,
        )

        val updated = applyDailyRateToProfile(profile, DailySessionRate(today, 1500.0))

        assertEquals(profile, updated)
    }
}
