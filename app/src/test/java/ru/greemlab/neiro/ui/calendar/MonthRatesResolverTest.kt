package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth

class MonthRatesResolverTest {

    private val today = LocalDate.of(2026, 7, 15)
    private val profile = EarningsContext(
        pricePerSession = 1400.0,
        pricePerDiagnostics = 2250.0,
        pricePerIntensiveChild = 1400.0,
        monthlyTaxAmount = 6500.0,
    )

    private fun entry(
        year: Int = 2026,
        month: Int = 3,
        sessions: Int = 0,
        factGross: Double? = null,
        factSessions: Int? = null,
        origin: PriceOrigin = PriceOrigin.AUTO,
        pricePerSession: Double = 0.0,
    ) = MonthEntry(
        staffId = 1L,
        year = year,
        month = month,
        sessions = sessions,
        pricePerSession = pricePerSession,
        origin = origin,
        factGross = factGross,
        factSessions = factSessions,
    )

    @Test
    fun `current month always uses profile even with fact and manual price`() {
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 7),
            entry = entry(year = 2026, month = 7, factGross = 999_999.0, factSessions = 10, origin = PriceOrigin.MANUAL, pricePerSession = 111.0),
            profile = profile,
            today = today,
        )
        assertEquals(PriceSource.PROFILE, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `future month uses profile`() {
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 8),
            entry = null,
            profile = profile,
            today = today,
        )
        assertEquals(PriceSource.PROFILE, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `manual month keeps its own price forever`() {
        val manual = entry(
            year = 2026,
            month = 3,
            origin = PriceOrigin.MANUAL,
            pricePerSession = 1500.0,
            factGross = 170_600.0,
            factSessions = 115,
        )
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = manual,
            profile = profile,
            today = today,
        )
        assertEquals(PriceSource.MANUAL, resolved.source)
        assertEquals(1500.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `past month without fact uses profile`() {
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = entry(sessions = 115),
            profile = profile,
            today = today,
        )
        assertEquals(PriceSource.PROFILE, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `fact divided by sessions gives session price`() {
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 2),
            entry = entry(year = 2026, month = 2, factGross = 112_000.0, factSessions = 80),
            profile = profile,
            today = today,
        )
        assertEquals(PriceSource.FACT, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `diagnostics are subtracted before division`() {
        // Контрольный случай 19.06.2026: services_count = 8, salary = 12 050,
        // из них диагностика 2250. Верный ответ 1400, а не 12050 ÷ 8 = 1506.
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 6),
            entry = entry(year = 2026, month = 6, factGross = 12_050.0, factSessions = 8),
            profile = profile,
            today = today,
            diagnosticsCount = 1,
            diagnosticsSum = 2250.0,
        )
        assertEquals(PriceSource.FACT, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `api intensive is subtracted before division`() {
        // Июнь 2026 целиком: 174 450 = 115×1400 + 2250 + 11 200.
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 6),
            entry = entry(year = 2026, month = 6, factGross = 174_450.0, factSessions = 116),
            profile = profile,
            today = today,
            diagnosticsCount = 1,
            diagnosticsSum = 2250.0,
            factIntensiveSum = 11_200.0,
        )
        assertEquals(PriceSource.FACT, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `zero sessions does not divide by zero`() {
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = entry(factGross = 5000.0, factSessions = 0),
            profile = profile,
            today = today,
        )
        // Делить не на что — цену даёт профиль, и «откуда» это честно показывает.
        assertEquals(PriceSource.PROFILE, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `zero fact falls back to profile price`() {
        val emptyProfile = EarningsContext.Empty
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = entry(factGross = 0.0, factSessions = 0),
            profile = emptyProfile,
            today = today,
        )
        assertEquals(PriceSource.PROFILE, resolved.source)
        assertEquals(0.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `division uses api session count not local one`() {
        // В YClients 80, в календаре 78: делим на 80, иначе цена завысится.
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = entry(sessions = 78, factGross = 112_000.0, factSessions = 80),
            profile = profile,
            today = today,
        )
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `local session count is used when api count is missing`() {
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = entry(sessions = 80, factGross = 112_000.0, factSessions = null),
            profile = profile,
            today = today,
        )
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `corrected tax reaches a month with an old snapshot`() {
        // Человек однажды ввёл в настройках не ту сумму налога и исправил её.
        // Прошлые месяцы обязаны показать исправленную: задать налог отдельно
        // на месяц негде, и слепок в записи — не решение человека (01.09.2026).
        val withWrongTax = MonthEntry(
            staffId = 1L,
            year = 2026,
            month = 5,
            pricePerSession = 1400.0,
            tax = 62_500.0,
            factGross = 79_300.0,
            factSessions = 51,
        )
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 5),
            entry = withWrongTax,
            profile = profile,
            today = today,
        )
        assertEquals(6500.0, resolved.rates.monthlyTaxAmount, 0.0)
    }

    @Test
    fun `entry prices win over profile when set`() {
        val withPrices = MonthEntry(
            staffId = 1L,
            year = 2025,
            month = 4,
            pricePerSession = 1250.0,
            priceDiagnostics = 2000.0,
            priceIntensiveChild = 1250.0,
            tax = 6000.0,
            factGross = 127_500.0,
            factSessions = 102,
        )
        val resolved = resolveMonthRates(
            month = YearMonth.of(2025, 4),
            entry = withPrices,
            profile = profile,
            today = today,
        )
        assertEquals(1250.0, resolved.rates.pricePerSession, 0.0)
        assertEquals(2000.0, resolved.rates.pricePerDiagnostics, 0.0)
        assertEquals(1250.0, resolved.rates.pricePerIntensiveChild, 0.0)
        // Налог — исключение: он живёт только в профиле, слепок 6000 в записи
        // расчётом не читается (01.09.2026).
        assertEquals(6500.0, resolved.rates.monthlyTaxAmount, 0.0)
    }

    @Test
    fun `closed month shows the number it was closed with`() {
        // Месяц закрыт: его цена уже выведена из начисления и лежит в истории.
        // Делить факт заново нельзя — число занятий в календаре с тех пор могло
        // дозаполниться, и цена поехала бы вниз на ровном месте.
        val frozen = MonthEntry(
            staffId = 1L,
            year = 2026,
            month = 3,
            sessions = 80,
            pricePerSession = 1500.0,
            factGross = 120_000.0,
            factSessions = 80,
            frozen = true,
        )
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = frozen,
            profile = profile,
            today = today,
            // Календарь дозаполнился до 100 занятий — на закрытый месяц это
            // больше не влияет.
            diagnosticsCount = 20,
            diagnosticsSum = 45_000.0,
        )
        assertEquals(PriceSource.FACT, resolved.source)
        assertEquals(1500.0, resolved.rates.pricePerSession, 0.0)
        // Недостающие ставки всё равно доберутся из профиля.
        assertEquals(2250.0, resolved.rates.pricePerDiagnostics, 0.0)
    }

    @Test
    fun `price from calculation items wins over dividing the fact`() {
        // Август 2025 живьём: 70 занятий по 1400 и 30 по 1250, начислено 135 500.
        // Деление дало бы 1355 — цену, которой не было ни у одного занятия.
        // В записи лежит 1400, взятая из позиций начисления, — её и показываем.
        val august = MonthEntry(
            staffId = 1L,
            year = 2025,
            month = 8,
            sessions = 100,
            pricePerSession = 1400.0,
            factGross = 135_500.0,
            factSessions = 100,
            frozen = false,
        )
        val resolved = resolveMonthRates(
            month = YearMonth.of(2025, 8),
            entry = august,
            profile = profile,
            today = today,
        )
        assertEquals(PriceSource.FACT, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `division is the fallback when the month has no price of its own`() {
        // Позиции начисления не достали — нет прав, офлайн или месяц не проведён.
        val resolved = resolveMonthRates(
            month = YearMonth.of(2026, 3),
            entry = entry(sessions = 80, factGross = 112_000.0, factSessions = 80),
            profile = profile,
            today = today,
        )
        assertEquals(PriceSource.FACT, resolved.source)
        assertEquals(1400.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `stored price is used when there is no fact at all`() {
        // 403 у сотрудника без прав владельца или офлайн: факта нет, но месяц
        // когда-то закрылся по своей цене — она и остаётся, а не сегодняшняя.
        val frozen = MonthEntry(
            staffId = 1L,
            year = 2025,
            month = 4,
            sessions = 102,
            pricePerSession = 1250.0,
            frozen = true,
        )
        val resolved = resolveMonthRates(
            month = YearMonth.of(2025, 4),
            entry = frozen,
            profile = profile,
            today = today,
        )
        assertEquals(1250.0, resolved.rates.pricePerSession, 0.0)
    }

    @Test
    fun `session price from fact returns null when there is nothing to divide`() {
        assertNull(
            sessionPriceFromFact(
                factGross = 12_050.0,
                services = 1,
                diagnosticsCount = 1,
                diagnosticsSum = 2250.0,
                factIntensiveSum = 0.0,
            ),
        )
        assertNull(
            sessionPriceFromFact(
                factGross = 2250.0,
                services = 8,
                diagnosticsCount = 1,
                diagnosticsSum = 2250.0,
                factIntensiveSum = 0.0,
            ),
        )
        assertEquals(
            1400.0,
            sessionPriceFromFact(
                factGross = 12_050.0,
                services = 8,
                diagnosticsCount = 1,
                diagnosticsSum = 2250.0,
                factIntensiveSum = 0.0,
            )!!,
            0.0,
        )
    }

    @Test
    fun `local facts count services like yclients does`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED)),
        )
        val dayData = mapOf(
            LocalDate.of(2026, 6, 19) to listOf(
                "Иванов|4",
                "Петров|4",
                "Сидоров|0", // ещё не пришёл — в услуги месяца не идёт
                "__DIAGNOSTICS__:2250|Аня|4",
                intensive,
                // Ученик на слоте интенсива — та же услуга, второй раз не считаем.
                "Дима|3|18:00-18:50",
            ),
        )

        val facts = collectMonthLocalFacts(
            dayData = dayData,
            month = YearMonth.of(2026, 6),
            diagnosticsPrice = 2250.0,
            intensivePrice = 1400.0,
        )

        assertEquals(3, facts.services)
        assertEquals(1, facts.diagnosticsCount)
    }

    @Test
    fun `local facts split api and manual intensives`() {
        val apiIntensive = SessionFormat.serializeIntensive(
            price = "",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "18:00-18:50",
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Маша", AttendanceStatus.ARRIVED),
            ),
        )
        val manualIntensive = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            amountFixed = true,
        )
        val dayData = mapOf(
            LocalDate.of(2026, 6, 19) to listOf(
                "Иванов|4",
                "__DIAGNOSTICS__:2250|Аня|4",
                apiIntensive,
                manualIntensive,
            ),
            // Другой месяц — в счёт не идёт.
            LocalDate.of(2026, 5, 19) to listOf("__DIAGNOSTICS__:2250|Оля|4"),
        )

        val facts = collectMonthLocalFacts(
            dayData = dayData,
            month = YearMonth.of(2026, 6),
            diagnosticsPrice = 2250.0,
            intensivePrice = 1400.0,
        )

        assertEquals(1, facts.diagnosticsCount)
        assertEquals(2250.0, facts.diagnosticsSum, 0.0)
        assertEquals(2800.0, facts.factIntensiveSum, 0.0)
        assertEquals(5600.0, facts.manualIntensiveSum, 0.0)
    }

    @Test
    fun `local facts skip cancelled records`() {
        val dayData = mapOf(
            LocalDate.of(2026, 6, 19) to listOf(
                "__DIAGNOSTICS__:2250|Аня|2", // отменена
                "__DIAGNOSTICS__:2250|Оля|0", // только ожидается
            ),
        )
        val facts = collectMonthLocalFacts(
            dayData = dayData,
            month = YearMonth.of(2026, 6),
            diagnosticsPrice = 2250.0,
            intensivePrice = 1400.0,
        )
        assertEquals(0, facts.diagnosticsCount)
        assertEquals(0.0, facts.diagnosticsSum, 0.0)
    }
}
