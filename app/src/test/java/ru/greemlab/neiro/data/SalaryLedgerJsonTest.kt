package ru.greemlab.neiro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth

class SalaryLedgerJsonTest {

    private val staffId = 3618433L

    @Test
    fun `blank json yields empty ledger`() {
        assertEquals(SalaryLedger.Empty, SalaryLedgerJson.fromJson(null))
        assertEquals(SalaryLedger.Empty, SalaryLedgerJson.fromJson(""))
        assertEquals(SalaryLedger.Empty, SalaryLedgerJson.fromJson("   "))
        assertEquals(SalaryLedger.Empty, SalaryLedgerJson.fromJson("{}"))
    }

    @Test
    fun `broken json yields empty ledger without throwing`() {
        assertEquals(SalaryLedger.Empty, SalaryLedgerJson.fromJson("не json"))
        assertEquals(SalaryLedger.Empty, SalaryLedgerJson.fromJson("[1, 2, 3]"))
    }

    @Test
    fun `manual month survives round trip`() {
        val entry = MonthEntry(
            staffId = staffId,
            year = 2026,
            month = 3,
            sessions = 115,
            pricePerSession = 1400.0,
            priceDiagnostics = 2250.0,
            priceIntensiveChild = 1400.0,
            tax = 6500.0,
            factGross = 170_600.0,
            factSessions = 115,
            origin = PriceOrigin.MANUAL,
            frozen = true,
            resolved = false,
            note = "факт YClients 170 600, приложение 161 000",
        )
        val ledger = SalaryLedger.Empty.withMonth(entry)

        val restored = SalaryLedgerJson.fromJson(SalaryLedgerJson.toJson(ledger))

        val restoredEntry = restored.month(staffId, YearMonth.of(2026, 3))
        assertEquals(entry, restoredEntry)
        assertEquals(PriceOrigin.MANUAL, restoredEntry?.origin)
        assertTrue(restoredEntry!!.frozen)
        assertFalse(restoredEntry.resolved)
        assertEquals("факт YClients 170 600, приложение 161 000", restoredEntry.note)
    }

    @Test
    fun `day facts survive round trip`() {
        val date = LocalDate.of(2026, 6, 19)
        val ledger = SalaryLedger.Empty.withDayFacts(staffId, mapOf(date to 12_050.0))

        val restored = SalaryLedgerJson.fromJson(SalaryLedgerJson.toJson(ledger))

        assertEquals(12_050.0, restored.dayFact(staffId, date)!!, 0.0)
        assertNull(restored.dayFact(staffId, LocalDate.of(2026, 6, 20)))
        assertNull(restored.dayFact(0L, date))
    }

    @Test
    fun `withMonth overwrites same month and keeps neighbour`() {
        val march = MonthEntry(staffId = staffId, year = 2026, month = 3, pricePerSession = 1400.0)
        val april = MonthEntry(staffId = staffId, year = 2026, month = 4, pricePerSession = 1400.0)
        val ledger = SalaryLedger.Empty.withMonth(march).withMonth(april)

        val updated = ledger.withMonth(march.copy(pricePerSession = 1500.0, origin = PriceOrigin.MANUAL))

        assertEquals(2, updated.months.size)
        assertEquals(1500.0, updated.month(staffId, YearMonth.of(2026, 3))!!.pricePerSession, 0.0)
        assertEquals(1400.0, updated.month(staffId, YearMonth.of(2026, 4))!!.pricePerSession, 0.0)
    }

    @Test
    fun `month key pads single digit month`() {
        // Двузначный месяц обязателен: иначе сортировка и сравнение ключей поедут.
        assertEquals("$staffId:2026-01", SalaryLedger.monthKey(staffId, YearMonth.of(2026, 1)))
        assertEquals("$staffId:2026-12", SalaryLedger.monthKey(staffId, YearMonth.of(2026, 12)))
    }

    @Test
    fun `years returns only own staff`() {
        val ledger = SalaryLedger.Empty
            .withMonth(MonthEntry(staffId = staffId, year = 2025, month = 4))
            .withMonth(MonthEntry(staffId = staffId, year = 2026, month = 3))
            .withMonth(MonthEntry(staffId = 999L, year = 2024, month = 1))

        assertEquals(setOf(2025, 2026), ledger.years(staffId))
        assertEquals(setOf(2024), ledger.years(999L))
        assertEquals(emptySet<Int>(), ledger.years(0L))
    }
}
