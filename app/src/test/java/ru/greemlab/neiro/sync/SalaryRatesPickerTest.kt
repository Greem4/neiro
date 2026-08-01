package ru.greemlab.neiro.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.data.network.SalaryRatesFromApi
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.domain.models.UserProfile

class SalaryRatesPickerTest {

    private val profile = UserProfile(
        name = "Светлана",
        pricePerSession = 1400.0,
        pricePerDiagnostics = 2250.0,
        isRegistered = true,
    )

    @Test
    fun `auto price is updated from api`() {
        val updated = applyApiRatesToProfile(
            profile = profile,
            rates = SalaryRatesFromApi(pricePerSession = 1500.0, pricePerDiagnostics = 2400.0),
        )

        assertEquals(1500.0, updated.pricePerSession, 0.0)
        assertEquals(2400.0, updated.pricePerDiagnostics, 0.0)
    }

    @Test
    fun `manual price is never overwritten`() {
        val manual = profile.copy(
            sessionPriceOrigin = PriceOrigin.MANUAL,
            diagnosticsPriceOrigin = PriceOrigin.MANUAL,
        )

        val updated = applyApiRatesToProfile(
            profile = manual,
            rates = SalaryRatesFromApi(pricePerSession = 1500.0, pricePerDiagnostics = 2400.0),
        )

        assertEquals(1400.0, updated.pricePerSession, 0.0)
        assertEquals(2250.0, updated.pricePerDiagnostics, 0.0)
        assertEquals(manual, updated)
    }

    @Test
    fun `manual session price does not block auto diagnostics price`() {
        val mixed = profile.copy(sessionPriceOrigin = PriceOrigin.MANUAL)

        val updated = applyApiRatesToProfile(
            profile = mixed,
            rates = SalaryRatesFromApi(pricePerSession = 1500.0, pricePerDiagnostics = 2400.0),
        )

        assertEquals(1400.0, updated.pricePerSession, 0.0)
        assertEquals(2400.0, updated.pricePerDiagnostics, 0.0)
    }

    @Test
    fun `empty rates change nothing`() {
        assertEquals(profile, applyApiRatesToProfile(profile, SalaryRatesFromApi()))
    }

    @Test
    fun `zero and negative rates are ignored`() {
        val updated = applyApiRatesToProfile(
            profile = profile,
            rates = SalaryRatesFromApi(pricePerSession = 0.0, pricePerDiagnostics = -100.0),
        )
        assertEquals(profile, updated)
    }

    @Test
    fun `same price is not rewritten`() {
        val updated = applyApiRatesToProfile(
            profile = profile,
            rates = SalaryRatesFromApi(pricePerSession = 1400.0, pricePerDiagnostics = 2250.0),
        )
        assertEquals(profile, updated)
    }

    @Test
    fun `intensive price stays local`() {
        val withIntensive = profile.copy(pricePerIntensiveChild = 1400.0)

        val updated = applyApiRatesToProfile(
            profile = withIntensive,
            rates = SalaryRatesFromApi(pricePerSession = 1500.0),
        )

        // В позиции activity нет ни record_id, ни списка детей — ставку
        // интенсива из API вывести нельзя, она остаётся локальной.
        assertEquals(1400.0, updated.pricePerIntensiveChild, 0.0)
        assertEquals(PriceOrigin.AUTO, updated.intensivePriceOrigin)
    }
}
