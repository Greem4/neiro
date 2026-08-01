package ru.greemlab.neiro.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryParsingTest {

    @Test
    fun `money comes as string from api`() {
        assertEquals(12_050.0, "12050".toMoneyOrNull()!!, 0.0)
        assertEquals(18_000.0, "18 000".toMoneyOrNull()!!, 0.0)
        assertEquals(1_400.5, "1400,50".toMoneyOrNull()!!, 0.0)
        assertEquals(0.0, "0".toMoneyOrNull()!!, 0.0)
        assertEquals(-1_250.0, "-1250".toMoneyOrNull()!!, 0.0)
    }

    @Test
    fun `broken money is null and does not throw`() {
        assertNull((null as String?).toMoneyOrNull())
        assertNull("".toMoneyOrNull())
        assertNull("не число".toMoneyOrNull())
    }

    private fun recordItem(
        title: String,
        salarySum: String?,
        itemType: String? = "record",
    ) = SalaryCalculationItem(
        date = "2026-06-30",
        time = "16:00",
        itemTypeSlug = itemType,
        recordId = 1511357589L,
        clientId = 255370970L,
        cost = "3000",
        salarySum = salarySum,
        targets = listOf(
            SalaryTarget(
                targetTypeSlug = "service",
                targetId = 17390933L,
                title = title,
                cost = "3000",
                salarySum = salarySum,
                salaryCalculation = SalaryCalculationRule(typeSlug = "fix", value = 1400.0),
            ),
        ),
    )

    @Test
    fun `session and diagnostics rates come from salary sum`() {
        val items = List(20) { recordItem("Нейрокоррекция", "1400") } +
            recordItem("Нейропсихологическая диагностика", "2250")

        val rates = extractSalaryRates(items)

        assertEquals(1400.0, rates.pricePerSession!!, 0.0)
        assertEquals(2250.0, rates.pricePerDiagnostics!!, 0.0)
    }

    @Test
    fun `unknown item type is skipped and does not break parsing`() {
        val items = listOf(
            recordItem("Нейрокоррекция", "1400"),
            recordItem("Штраф за опоздание", "-500", itemType = "penalty"),
            recordItem("Интенсив", "5600", itemType = "activity"),
        )

        val rates = extractSalaryRates(items)

        assertEquals(1400.0, rates.pricePerSession!!, 0.0)
        assertNull(rates.pricePerDiagnostics)
    }

    @Test
    fun `empty list yields empty rates`() {
        val rates = extractSalaryRates(emptyList())
        assertTrue(rates.isEmpty)
        assertNull(rates.pricePerSession)
        assertNull(rates.pricePerDiagnostics)

        assertTrue(extractSalaryRates(null).isEmpty)
    }

    @Test
    fun `most frequent rate wins over a single odd one`() {
        val items = List(10) { recordItem("Нейрокоррекция", "1400") } +
            recordItem("Нейрокоррекция", "1500")

        assertEquals(1400.0, extractSalaryRates(items).pricePerSession!!, 0.0)
    }

    @Test
    fun `positions without salary sum are ignored`() {
        val items = listOf(
            recordItem("Нейрокоррекция", null),
            recordItem("Нейрокоррекция", "0"),
            recordItem("Нейрокоррекция", "1400"),
        )

        assertEquals(1400.0, extractSalaryRates(items).pricePerSession!!, 0.0)
    }

    @Test
    fun `item without targets does not break parsing`() {
        val items = listOf(
            SalaryCalculationItem(
                date = "2026-06-30",
                time = null,
                itemTypeSlug = "record",
                recordId = null,
                clientId = null,
                cost = null,
                salarySum = "1400",
                targets = null,
            ),
            recordItem("Нейрокоррекция", "1400"),
        )

        assertEquals(1400.0, extractSalaryRates(items).pricePerSession!!, 0.0)
    }
}
