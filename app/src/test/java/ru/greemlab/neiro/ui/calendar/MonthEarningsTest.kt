package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.data.SalaryLedger
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth

/**
 * Календарный экран за прошлый месяц обязан показывать начисление YClients,
 * а не цену из профиля: цена в профиле — про «сейчас и вперёд».
 */
class MonthEarningsTest {

    private val today = LocalDate.of(2026, 8, 2)

    /** Профиль «сегодняшний»: цена уже 1500, налог 6500. */
    private val profileRates = EarningsContext(
        pricePerSession = 1500.0,
        pricePerDiagnostics = 2250.0,
        pricePerIntensiveChild = 1400.0,
        monthlyTaxAmount = 6500.0,
    )

    /** Январь 2025: в YClients 18 занятий по 1250, в календаре — только 17. */
    private val january = YearMonth.of(2025, 1)
    private val januaryEntry = MonthEntry(
        staffId = 1L,
        year = 2025,
        month = 1,
        sessions = 17,
        pricePerSession = 1250.0,
        tax = 6500.0,
        factGross = 22_500.0,
        factSessions = 18,
    )

    private fun students(month: YearMonth, days: Int): Map<LocalDate, List<String>> =
        (1..days).associate { day ->
            month.atDay(day) to listOf("Ученик $day|true")
        }

    @Test
    fun `past month takes price from fact, not from profile`() {
        val earnings = computeMonthEarnings(
            month = january,
            dayData = students(january, days = 17),
            profileRates = profileRates,
            entry = januaryEntry,
            today = today,
        )

        assertEquals(PriceSource.FACT, earnings.source)
        assertEquals(1250.0, earnings.rates.pricePerSession, 0.0)
    }

    @Test
    fun `past month money comes from calculation, not from sessions times price`() {
        val earnings = computeMonthEarnings(
            month = january,
            dayData = students(january, days = 17),
            profileRates = profileRates,
            entry = januaryEntry,
            today = today,
        )

        // 17 × 1500 (профиль) = 25 500 и 17 × 1250 (факт) = 21 250 — оба мимо:
        // заплатили 22 500, столько и показываем.
        assertEquals(22_500.0, earnings.stats.totalEarned, 0.0)
        assertEquals(16_000.0, earnings.stats.netProfit, 0.0)
    }

    @Test
    fun `current month keeps profile prices`() {
        val august = YearMonth.of(2026, 8)
        val earnings = computeMonthEarnings(
            month = august,
            dayData = students(august, days = 2),
            profileRates = profileRates,
            entry = MonthEntry(
                staffId = 1L,
                year = 2026,
                month = 8,
                pricePerSession = 1250.0,
                factGross = 99_999.0,
                factSessions = 40,
            ),
            today = today,
        )

        assertEquals(PriceSource.PROFILE, earnings.source)
        assertEquals(1500.0, earnings.rates.pricePerSession, 0.0)
        assertEquals(3000.0, earnings.stats.totalEarned, 0.0)
    }

    @Test
    fun `manual month is recomputed from calendar`() {
        val march = YearMonth.of(2026, 3)
        val earnings = computeMonthEarnings(
            month = march,
            dayData = students(march, days = 10),
            profileRates = profileRates,
            entry = MonthEntry(
                staffId = 1L,
                year = 2026,
                month = 3,
                pricePerSession = 1400.0,
                tax = 6500.0,
                factGross = 15_000.0,
                factSessions = 10,
                origin = PriceOrigin.MANUAL,
            ),
            today = today,
        )

        assertEquals(PriceSource.MANUAL, earnings.source)
        assertEquals(14_000.0, earnings.stats.totalEarned, 0.0)
        assertEquals(7500.0, earnings.stats.netProfit, 0.0)
    }

    @Test
    fun `month without local records falls back to api counters`() {
        val february = YearMonth.of(2025, 2)
        val earnings = computeMonthEarnings(
            month = february,
            dayData = emptyMap(),
            profileRates = profileRates,
            entry = MonthEntry(
                staffId = 1L,
                year = 2025,
                month = 2,
                pricePerSession = 1250.0,
                tax = 6500.0,
                factGross = 20_000.0,
                factSessions = 16,
            ),
            today = today,
        )

        assertEquals(20_000.0, earnings.stats.totalEarned, 0.0)
        assertEquals(13_500.0, earnings.stats.netProfit, 0.0)
        assertEquals(16, earnings.completedCount)
    }

    @Test
    fun `calendar and year chart show the same money for one month`() {
        val dayData = students(january, days = 17)
        val ledger = SalaryLedger.Empty.withMonth(januaryEntry)

        val earnings = computeMonthEarnings(
            month = january,
            dayData = dayData,
            profileRates = profileRates,
            entry = januaryEntry,
            today = today,
        )
        val yearStats = computeProfileYearStats(
            year = 2025,
            dayData = dayData,
            profileRates = profileRates,
            ledger = ledger,
            staffId = 1L,
            today = today,
        )

        assertEquals(yearStats.monthlyNet[0], earnings.stats.netProfit, 0.0)
    }
}
