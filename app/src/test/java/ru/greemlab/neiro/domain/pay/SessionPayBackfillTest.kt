package ru.greemlab.neiro.domain.pay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class SessionPayBackfillTest {

    @Test
    fun `freezeRate is no-op while payment logic disabled`() {
        val raw = "Иванов|3|10:00-10:50||"
        assertEquals(raw, SessionPayBackfill.freezeRateInRaw(raw, 1200.0))
    }

    @Test
    fun `freezeRate does not change map`() {
        val dayData = mapOf(
            LocalDate.of(2025, 4, 1) to listOf("Иванов|true"),
        )
        assertSame(dayData, SessionPayBackfill.freezeRate(dayData, 1300.0))
    }
}
