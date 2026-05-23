package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.domain.models.SESSION_PRICE_EPOCH
import ru.greemlab.neiro.domain.models.SessionPriceHistoryEntry
import java.time.LocalDate

class SessionPayTest {

    @Test
    fun `always uses profile rate`() {
        val session = Session.Student("A", attended = true, payAmount = 1250.0)
        assertEquals(1400.0, session.employeePay(1400.0), 0.0)
    }

    @Test
    fun `ignores history while payment logic disabled`() {
        val session = Session.Student("A", attended = true)
        val history = listOf(
            SessionPriceHistoryEntry(SESSION_PRICE_EPOCH, 1200.0),
            SessionPriceHistoryEntry("2025-05-01", 1500.0),
        )
        assertEquals(1400.0, session.employeePay(1400.0, LocalDate.of(2025, 4, 15), history), 0.0)
    }
}
