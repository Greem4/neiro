package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.data.SalaryLedger
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate

class ProfileYearStatsTest {

    private val year = 2025
    private val pricePerSession = 1000.0
    private val monthlyTax = 200.0
    private val staffId = 3618433L

    @Test
    fun `empty year yields zeros`() {
        val stats = computeProfileYearStats(
            year = year,
            dayData = emptyMap(),
            profileRates = EarningsContext(
                pricePerSession = pricePerSession,
                monthlyTaxAmount = monthlyTax,
            ),
        )
        assertEquals(0, stats.completedSessions)
        assertEquals(0.0, stats.totalNetEarned, 0.0)
        assertEquals(12, stats.monthlyNet.size)
    }

    @Test
    fun `past year tax covers all twelve months`() {
        val stats = computeProfileYearStats(
            year = year,
            dayData = emptyMap(),
            profileRates = EarningsContext(
                pricePerSession = pricePerSession,
                monthlyTaxAmount = 6500.0,
            ),
            today = LocalDate.of(2026, 7, 30),
        )
        assertEquals(6500.0 * 12, stats.totalTaxAmount, 0.0)
        assertEquals(0.0, stats.totalNetEarned, 0.0)
        stats.monthlyNet.forEach { assertEquals(0.0, it, 0.0) }
    }

    @Test
    fun `current year tax stops at current month`() {
        val stats = computeProfileYearStats(
            year = 2026,
            dayData = emptyMap(),
            profileRates = EarningsContext(
                pricePerSession = pricePerSession,
                monthlyTaxAmount = 6500.0,
            ),
            today = LocalDate.of(2026, 7, 30),
        )
        // Июль — седьмой платёж; за август и дальше налог ещё не наступил.
        assertEquals(6500.0 * 7, stats.totalTaxAmount, 0.0)
    }

    @Test
    fun `future year has no tax yet`() {
        val stats = computeProfileYearStats(
            year = 2027,
            dayData = emptyMap(),
            profileRates = EarningsContext(
                pricePerSession = pricePerSession,
                monthlyTaxAmount = 6500.0,
            ),
            today = LocalDate.of(2026, 7, 30),
        )
        assertEquals(0.0, stats.totalTaxAmount, 0.0)
    }

    @Test
    fun `sums completed sessions and net across months with tax per month`() {
        val dayData = mapOf(
            LocalDate.of(2025, 5, 10) to listOf("Иванов|true", "Петров|true"),
            LocalDate.of(2025, 6, 1) to listOf("Иванов|true"),
        )
        val stats = computeProfileYearStats(
            year = year,
            dayData = dayData,
            profileRates = EarningsContext(
                pricePerSession = pricePerSession,
                monthlyTaxAmount = monthlyTax,
            ),
            today = LocalDate.of(2026, 7, 30),
        )
        assertEquals(3, stats.completedSessions)
        val mayNet = 2000.0 - monthlyTax
        val juneNet = 1000.0 - monthlyTax
        assertEquals(mayNet, stats.monthlyNet[4], 0.0)
        assertEquals(juneNet, stats.monthlyNet[5], 0.0)
        val otherMonthsNet = 0.0
        assertEquals(mayNet + juneNet + otherMonthsNet, stats.totalNetEarned, 0.0)
        assertEquals(monthlyTax * 12, stats.totalTaxAmount, 0.0)
        assertEquals(2, stats.monthlyCompleted[4])
        assertEquals(1, stats.monthlyCompleted[5])
    }

    @Test
    fun `intensives are not counted as sessions but their money is`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Маша", AttendanceStatus.ARRIVED),
            ),
        )
        val dayData = mapOf(
            LocalDate.of(2025, 6, 2) to listOf("Иванов|true", intensive),
        )
        val stats = computeProfileYearStats(
            year = year,
            dayData = dayData,
            profileRates = EarningsContext(
                pricePerSession = pricePerSession,
                pricePerIntensiveChild = 1400.0,
            ),
            today = LocalDate.of(2026, 7, 30),
        )
        assertEquals(1, stats.completedSessions)
        assertEquals(1, stats.monthlyCompleted[5])
        assertEquals(1000.0 + 2800.0, stats.monthlyNet[5], 0.0)
    }

    @Test
    fun `available years includes data years and current`() {
        val dayData = mapOf(
            LocalDate.of(2023, 6, 1) to listOf("Иванов|true"),
            LocalDate.of(2025, 1, 2) to listOf("Петров|true"),
        )
        val years = availableStatsYears(dayData, currentYear = 2026)
        assertEquals(listOf(2026, 2025, 2023), years)
    }

    @Test
    fun `available years includes history years`() {
        val ledger = SalaryLedger.Empty
            .withMonth(MonthEntry(staffId = staffId, year = 2024, month = 4))
        val years = availableStatsYears(emptyMap(), ledger.years(staffId), currentYear = 2026)
        assertEquals(listOf(2026, 2024), years)
    }

    @Test
    fun `month from history shows even when calendar is empty`() {
        // Приложение поставили позже: локальных записей за 2025 нет,
        // а в YClients месяц есть (FOUNDATION 3.3).
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2025,
                month = 4,
                sessions = 102,
                tax = 6500.0,
                factGross = 127_500.0,
                factSessions = 102,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = emptyMap(),
            profileRates = EarningsContext(pricePerSession = 1400.0, monthlyTaxAmount = 6500.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )
        assertEquals(102, stats.monthlyCompleted[3])
        assertEquals(102, stats.completedSessions)
        assertEquals(127_500.0 - 6500.0, stats.monthlyNet[3], 0.0)
    }

    @Test
    fun `past month with fact uses price from fact and not from profile`() {
        val dayData = mapOf(
            LocalDate.of(2025, 4, 10) to listOf("Иванов|3", "Петров|3"),
        )
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2025,
                month = 4,
                sessions = 2,
                factGross = 2500.0,
                factSessions = 2,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = dayData,
            profileRates = EarningsContext(pricePerSession = 1400.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )
        // Цена месяца из факта: 2500 ÷ 2 = 1250 за занятие, а не 1400 из профиля.
        assertEquals(2500.0, stats.monthlyNet[3], 0.0)
    }

    @Test
    fun `past month money is the payroll and not local multiplication`() {
        // В календаре два занятия, в YClients — 102 услуги и 127 500 ₽.
        // Показываем начисление: перемножением вышло бы 2500 вместо 127 500.
        val dayData = mapOf(
            LocalDate.of(2025, 4, 10) to listOf("Иванов|3", "Петров|3"),
        )
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2025,
                month = 4,
                sessions = 2,
                tax = 6500.0,
                factGross = 127_500.0,
                factSessions = 102,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = dayData,
            profileRates = EarningsContext(pricePerSession = 1400.0, monthlyTaxAmount = 6500.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )

        assertEquals(127_500.0 - 6500.0, stats.monthlyNet[3], 0.0)
        // Занятия остаются локальными: календарь знает про два.
        assertEquals(2, stats.monthlyCompleted[3])
    }

    @Test
    fun `manual price wins over payroll`() {
        // Февраль–май 2026 в жизни: YClients считал по 1500 из-за старой схемы
        // percent, на руки было 1400 — человек ставит свою цену, и она главнее.
        val dayData = mapOf(
            LocalDate.of(2025, 4, 10) to listOf("Иванов|3", "Петров|3"),
        )
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2025,
                month = 4,
                sessions = 2,
                pricePerSession = 1400.0,
                factGross = 3000.0,
                factSessions = 2,
                origin = PriceOrigin.MANUAL,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = dayData,
            profileRates = EarningsContext(pricePerSession = 1500.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )

        assertEquals(2800.0, stats.monthlyNet[3], 0.0)
    }

    @Test
    fun `manual price on a month outside the calendar uses api session count`() {
        // Месяца в локальном календаре нет вовсе: считаем ручную цену на услуги
        // из API, иначе правка цены обнулила бы месяц с живым начислением.
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2025,
                month = 4,
                pricePerSession = 1400.0,
                factGross = 139_500.0,
                factSessions = 93,
                origin = PriceOrigin.MANUAL,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = emptyMap(),
            profileRates = EarningsContext(pricePerSession = 1500.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )

        assertEquals(1400.0 * 93, stats.monthlyNet[3], 0.0)
        assertEquals(93, stats.monthlyCompleted[3])
    }

    @Test
    fun `hand made intensive is added on top of the payroll`() {
        // Интенсив, заведённый руками, в начисление YClients не попал —
        // его деньги существуют только в приложении (GAPS 7).
        val manualIntensive = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            amountFixed = true,
        )
        val dayData = mapOf(
            LocalDate.of(2025, 4, 10) to listOf("Иванов|3", manualIntensive),
        )
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2025,
                month = 4,
                factGross = 127_500.0,
                factSessions = 102,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = dayData,
            profileRates = EarningsContext(pricePerSession = 1400.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )

        assertEquals(127_500.0 + 5600.0, stats.monthlyNet[3], 0.0)
    }

    @Test
    fun `current month is not touched by payroll`() {
        // Текущий месяц считается по профилю: начисление за него ещё не закрыто.
        val dayData = mapOf(
            LocalDate.of(2026, 7, 10) to listOf("Иванов|3", "Петров|3"),
        )
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2026,
                month = 7,
                factGross = 999_999.0,
                factSessions = 500,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2026,
            dayData = dayData,
            profileRates = EarningsContext(pricePerSession = 1400.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )

        assertEquals(2800.0, stats.monthlyNet[6], 0.0)
    }

    @Test
    fun `month metadata comes from history`() {
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = staffId,
                year = 2025,
                month = 4,
                sessions = 102,
                pricePerSession = 1250.0,
                priceDiagnostics = 2250.0,
                tax = 6500.0,
                factGross = 127_500.0,
                factSessions = 102,
                origin = PriceOrigin.MANUAL,
                frozen = true,
                resolved = false,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = mapOf(LocalDate.of(2025, 4, 10) to listOf("Иванов|3")),
            profileRates = EarningsContext(pricePerSession = 1400.0, monthlyTaxAmount = 6500.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )

        val april = stats.months[3]
        assertEquals(PriceOrigin.MANUAL, april.origin)
        assertEquals(PriceSource.MANUAL, april.source)
        assertTrue(april.frozen)
        assertFalse(april.resolved)
        assertEquals(1250.0, april.pricePerSession, 0.0)
        assertEquals(127_500.0, april.factGross!!, 0.0)
        assertEquals(1250.0, april.factPricePerSession!!, 0.0)
        assertTrue(stats.hasUnresolvedMonths)

        // Месяц без истории молчит.
        val may = stats.months[4]
        assertEquals(PriceOrigin.AUTO, may.origin)
        assertTrue(may.resolved)
        assertFalse(may.frozen)
    }

    @Test
    fun `history of another staff is ignored`() {
        val ledger = SalaryLedger.Empty.withMonth(
            MonthEntry(
                staffId = 999L,
                year = 2025,
                month = 4,
                factGross = 500_000.0,
                factSessions = 100,
            ),
        )
        val stats = computeProfileYearStats(
            year = 2025,
            dayData = emptyMap(),
            profileRates = EarningsContext(pricePerSession = 1400.0),
            ledger = ledger,
            staffId = staffId,
            today = LocalDate.of(2026, 7, 30),
        )
        assertEquals(0.0, stats.totalNetEarned, 0.0)
        assertEquals(0, stats.completedSessions)
    }

    @Test
    fun `ignores data from other years`() {
        val dayData = mapOf(
            LocalDate.of(2024, 12, 31) to listOf("Иванов|true"),
            LocalDate.of(2025, 1, 2) to listOf("Петров|true"),
        )
        val stats = computeProfileYearStats(
            year = year,
            dayData = dayData,
            profileRates = EarningsContext(pricePerSession = pricePerSession),
        )
        assertEquals(1, stats.completedSessions)
        assertEquals(1000.0, stats.totalNetEarned, 0.0)
        assertEquals(1000.0, stats.monthlyNet[0], 0.0)
    }
}
