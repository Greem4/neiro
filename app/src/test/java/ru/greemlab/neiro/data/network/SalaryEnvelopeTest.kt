package ru.greemlab.neiro.data.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конверт зарплатного ответа.
 *
 * Живой YClients дважды не совпал с документацией: `data` пришёл объектом,
 * `meta` — массивом. Оба раза Gson ронял разбор целиком, и зарплата не
 * доезжала до приложения. Ни одно поле конверта больше не имеет права это
 * сделать.
 */
class SalaryEnvelopeTest {

    private fun envelope(json: String) = SalaryEnvelope(JsonParser.parseString(json))

    @Test
    fun `meta as empty array does not break anything`() {
        // Ровно этот ответ ронял разбор: Expected BEGIN_OBJECT but was BEGIN_ARRAY at $.meta
        val parsed = envelope("""{"success":true,"data":{"2025-01-16":{"salary":"2500"}},"meta":[]}""")

        assertTrue(parsed.isSuccess)
        assertNull(parsed.message)
        assertEquals(1, salaryDailyItems(parsed.data).size)
    }

    @Test
    fun `meta as object gives the refusal text`() {
        val parsed = envelope("""{"success":false,"data":[],"meta":{"message":"Нет прав"}}""")

        assertFalse(parsed.isSuccess)
        assertEquals("Нет прав", parsed.message)
    }

    @Test
    fun `success survives any spelling`() {
        assertTrue(envelope("""{"success":true}""").isSuccess)
        assertTrue(envelope("""{"success":"true"}""").isSuccess)
        assertTrue(envelope("""{"success":1}""").isSuccess)
        assertFalse(envelope("""{"success":false}""").isSuccess)
        assertFalse(envelope("""{"success":0}""").isSuccess)
        // Поля нет вовсе — это не отказ.
        assertTrue(envelope("""{"data":[]}""").isSuccess)
    }

    @Test
    fun `garbage instead of an envelope gives empty, not a crash`() {
        assertTrue(envelope("[]").isSuccess)
        assertNull(envelope("[]").data)
        assertNull(envelope("\"привет\"").message)
        assertTrue(SalaryEnvelope(null).isSuccess)
    }
}
