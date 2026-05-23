package ru.greemlab.neiro.domain.models

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SessionPriceHistoryTest {

    @Test
    fun `empty history uses current price`() {
        val profile = UserProfile(pricePerSession = 1400.0)
        assertEquals(1400.0, profile.pricePerSessionOn(LocalDate.of(2025, 3, 15)), 0.0)
    }

    @Test
    fun `history returns rate effective on date`() {
        val profile = UserProfile(
            pricePerSession = 1500.0,
            sessionPriceHistory = listOf(
                SessionPriceHistoryEntry(SESSION_PRICE_EPOCH, 1200.0),
                SessionPriceHistoryEntry("2025-05-01", 1500.0),
            ),
        )
        assertEquals(1200.0, profile.pricePerSessionOn(LocalDate.of(2025, 4, 30)), 0.0)
        assertEquals(1500.0, profile.pricePerSessionOn(LocalDate.of(2025, 5, 10)), 0.0)
    }

    @Test
    fun `withSessionPriceChange appends history`() {
        val profile = UserProfile(pricePerSession = 1400.0)
        val updated = profile.withSessionPriceChange(
            oldPrice = 1400.0,
            newPrice = 1500.0,
            effectiveFrom = LocalDate.of(2025, 5, 1),
        )
        assertEquals(1500.0, updated.pricePerSession, 0.0)
        assertEquals(2, updated.sessionPriceHistory.size)
        assertEquals(1400.0, updated.pricePerSessionOn(LocalDate.of(2025, 4, 1)), 0.0)
        assertEquals(1500.0, updated.pricePerSessionOn(LocalDate.of(2025, 5, 2)), 0.0)
    }
}
