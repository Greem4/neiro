package ru.greemlab.neiro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth

class SalaryLedgerRulesTest {

    private val staffId = 3618433L
    private val june = YearMonth.of(2026, 6)
    private val profile = EarningsContext(
        pricePerSession = 1400.0,
        pricePerDiagnostics = 2250.0,
        pricePerIntensiveChild = 1400.0,
        monthlyTaxAmount = 6500.0,
    )

    // --- Заморозка -------------------------------------------------------

    @Test
    fun `month ended five days ago is not frozen yet`() {
        assertFalse(shouldFreeze(june, hasFact = true, today = LocalDate.of(2026, 7, 5)))
    }

    @Test
    fun `month ended eight days ago is frozen`() {
        assertTrue(shouldFreeze(june, hasFact = true, today = LocalDate.of(2026, 7, 8)))
    }

    @Test
    fun `exactly seven days after month end is frozen`() {
        assertTrue(shouldFreeze(june, hasFact = true, today = LocalDate.of(2026, 7, 7)))
    }

    @Test
    fun `current month is never frozen even with fact`() {
        assertFalse(shouldFreeze(june, hasFact = true, today = LocalDate.of(2026, 6, 30)))
    }

    @Test
    fun `month without fact is never frozen`() {
        assertFalse(shouldFreeze(june, hasFact = false, today = LocalDate.of(2027, 1, 1)))
    }

    // --- Сверка ----------------------------------------------------------

    @Test
    fun `matching price gives no discrepancy`() {
        val entry = MonthEntry(
            staffId = staffId,
            year = 2025,
            month = 4,
            sessions = 102,
            factGross = 127_500.0,
            factSessions = 102,
        )
        val app = MonthAppView(
            services = 102,
            pricePerSession = 1250.0,
            factPricePerSession = 1250.0,
        )
        assertNull(discrepancy(entry, app))
    }

    @Test
    fun `different price gives discrepancy with both grosses`() {
        val entry = MonthEntry(
            staffId = staffId,
            year = 2026,
            month = 3,
            sessions = 80,
            factGross = 120_000.0,
            factSessions = 80,
        )
        val app = MonthAppView(
            services = 80,
            pricePerSession = 1400.0,
            factPricePerSession = 1500.0,
        )

        val gap = discrepancy(entry, app)

        assertNotNull(gap)
        assertEquals(80, gap!!.sessions)
        assertEquals(112_000.0, gap.appGross, 0.0)
        assertEquals(120_000.0, gap.factGross, 0.0)
        assertEquals(8_000.0, gap.difference, 0.0)
        assertEquals(
            "факт YClients 120000, приложение 112000",
            describeDiscrepancy(gap),
        )
    }

    @Test
    fun `no fact means no discrepancy`() {
        val entry = MonthEntry(staffId = staffId, year = 2026, month = 3, sessions = 80)
        val app = MonthAppView(services = 80, pricePerSession = 1400.0)
        assertNull(discrepancy(entry, app))
    }

    // --- Слияние факта ---------------------------------------------------

    private fun fact(gross: Double, services: Int) = MonthFact(june, gross, services)

    @Test
    fun `matching month is frozen silently and stays resolved`() {
        val merged = mergeFact(
            existing = null,
            fact = fact(161_000.0, 115),
            app = MonthAppView(
                services = 115,
                pricePerSession = 1400.0,
                factPricePerSession = 1400.0,
            ),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )

        assertTrue(merged.frozen)
        assertTrue(merged.resolved)
        assertEquals("", merged.note)
        assertEquals(1400.0, merged.pricePerSession, 0.0)
        assertEquals(161_000.0, merged.factGross!!, 0.0)
        assertEquals(115, merged.factSessions)
    }

    @Test
    fun `auto month takes fact price and does not ask to review`() {
        val merged = mergeFact(
            existing = null,
            fact = fact(172_500.0, 115),
            app = MonthAppView(
                services = 115,
                pricePerSession = 1400.0,
                factPricePerSession = 1500.0,
            ),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )

        assertTrue(merged.frozen)
        // Цена приходит из начисления, а не из профиля.
        assertEquals(1500.0, merged.pricePerSession, 0.0)
        // Разбирать нечего: приложение и YClients теперь говорят одно и то же.
        assertTrue(merged.resolved)
        // След расхождения всё равно остаётся — по нему и находят такие истории.
        assertTrue(merged.note.contains("факт YClients"))
    }

    @Test
    fun `manual price is asked to review only when fact changes`() {
        val manual = MonthEntry(
            staffId = staffId,
            year = 2026,
            month = 6,
            sessions = 115,
            pricePerSession = 1400.0,
            factGross = 172_500.0,
            factSessions = 115,
            origin = PriceOrigin.MANUAL,
            resolved = true,
        )
        val app = MonthAppView(services = 115, pricePerSession = 1400.0, factPricePerSession = 1500.0)

        val sameFact = mergeFact(
            existing = manual,
            fact = fact(172_500.0, 115),
            app = app,
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )
        assertTrue(sameFact.resolved)

        val changedFact = mergeFact(
            existing = manual,
            fact = fact(180_000.0, 120),
            app = app,
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )
        assertFalse(changedFact.resolved)
        assertEquals(1400.0, changedFact.pricePerSession, 0.0)
    }

    @Test
    fun `manual price and note survive merge`() {
        val existing = MonthEntry(
            staffId = staffId,
            year = 2026,
            month = 6,
            sessions = 115,
            pricePerSession = 1450.0,
            tax = 6000.0,
            origin = PriceOrigin.MANUAL,
            frozen = true,
            resolved = true,
            note = "договорённость с центром",
        )

        val merged = mergeFact(
            existing = existing,
            fact = fact(172_500.0, 115),
            app = MonthAppView(
                services = 115,
                pricePerSession = 1450.0,
                factPricePerSession = 1500.0,
            ),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )

        assertEquals(PriceOrigin.MANUAL, merged.origin)
        assertEquals(1450.0, merged.pricePerSession, 0.0)
        assertEquals(6000.0, merged.tax, 0.0)
        assertTrue(merged.note.startsWith("договорённость с центром"))
        // Факт всё равно обновился — сверять есть с чем.
        assertEquals(172_500.0, merged.factGross!!, 0.0)
        // Цену человек ставил, ещё не зная начисления: теперь оно пришло и
        // спорит с ней — просим сверить. Дальше тот же факт будет молчать.
        assertFalse(merged.resolved)
    }

    @Test
    fun `empty answer is not a fact and does not erase the old one`() {
        // Ноль рублей и ни одной услуги — это «не ответили», а не «месяц нулевой»:
        // деньги месяца теперь берутся из факта, и обнулять его нельзя.
        val existing = MonthEntry(
            staffId = staffId,
            year = 2026,
            month = 6,
            sessions = 115,
            pricePerSession = 1400.0,
            factGross = 161_000.0,
            factSessions = 115,
            frozen = true,
        )

        val merged = mergeFact(
            existing = existing,
            fact = fact(0.0, 0),
            app = MonthAppView(services = 115, pricePerSession = 1400.0),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )

        assertEquals(161_000.0, merged.factGross!!, 0.0)
        assertEquals(115, merged.factSessions)
        assertEquals(1400.0, merged.pricePerSession, 0.0)
    }

    @Test
    fun `empty answer on a brand new month leaves no fact`() {
        val merged = mergeFact(
            existing = null,
            fact = fact(0.0, 0),
            app = MonthAppView(services = 0, pricePerSession = 1400.0),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )

        assertNull(merged.factGross)
        // Без факта морозить нечего — месяц ещё дотянется.
        assertFalse(merged.frozen)
    }

    @Test
    fun `month without local records takes price from fact`() {
        // Приложение поставили позже: локальных записей нет, цена профиля
        // сегодняшняя (1400), а правда о том месяце — 1250 из факта.
        val merged = mergeFact(
            existing = null,
            fact = MonthFact(YearMonth.of(2025, 4), 127_500.0, 102),
            app = MonthAppView(
                services = 0,
                pricePerSession = 1400.0,
                factPricePerSession = 1250.0,
            ),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )

        assertEquals(1250.0, merged.pricePerSession, 0.0)
        assertTrue(merged.frozen)
    }

    @Test
    fun `month written before the grace period freezes when due`() {
        // Запись текущего месяца появляется каждый синк — она не должна мешать
        // месяцу закрыться, когда срок пришёл.
        val existing = MonthEntry(
            staffId = staffId,
            year = 2026,
            month = 6,
            sessions = 100,
            pricePerSession = 1400.0,
            factGross = 140_000.0,
            factSessions = 100,
            frozen = false,
        )

        val stillOpen = mergeFact(
            existing = existing,
            fact = fact(161_000.0, 115),
            app = MonthAppView(services = 115, pricePerSession = 1400.0, factPricePerSession = 1400.0),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 3),
        )
        assertFalse(stillOpen.frozen)

        val closed = mergeFact(
            existing = stillOpen,
            fact = fact(161_000.0, 115),
            app = MonthAppView(services = 115, pricePerSession = 1400.0, factPricePerSession = 1400.0),
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )
        assertTrue(closed.frozen)
    }

    @Test
    fun `same discrepancy note is not appended twice`() {
        val app = MonthAppView(
            services = 115,
            pricePerSession = 1400.0,
            factPricePerSession = 1500.0,
        )
        val first = mergeFact(
            existing = null,
            fact = fact(172_500.0, 115),
            app = app,
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 10),
        )
        val second = mergeFact(
            existing = first,
            fact = fact(172_500.0, 115),
            app = app,
            profile = profile,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 11),
        )

        assertEquals(first.note, second.note)
    }
}
