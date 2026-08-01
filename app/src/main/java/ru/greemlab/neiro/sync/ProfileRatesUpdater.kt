package ru.greemlab.neiro.sync

import ru.greemlab.neiro.data.network.SalaryRatesFromApi
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.domain.models.UserProfile

/**
 * Переносит ставки из детализации начисления в профиль (FOUNDATION 6.1).
 *
 * АВТО обновляется при каждом синке, РУЧНОЕ не переписывается никогда:
 * приложение может поправить только то, что подставило само. Именно поэтому
 * ставка 1500 с августа 2026 подставится сама тем, кто цену не трогал, а
 * тем, кто вписал своё, останется их значение.
 *
 * Интенсив в API не разложен по детям (в позиции `activity` нет ни `record_id`,
 * ни списка детей), поэтому его ставка остаётся локальной.
 */
fun applyApiRatesToProfile(profile: UserProfile, rates: SalaryRatesFromApi): UserProfile {
    var updated = profile

    val sessionPrice = rates.pricePerSession
    if (sessionPrice != null &&
        sessionPrice > 0.0 &&
        profile.sessionPriceOrigin == PriceOrigin.AUTO &&
        sessionPrice != profile.pricePerSession
    ) {
        updated = updated.copy(pricePerSession = sessionPrice)
    }

    val diagnosticsPrice = rates.pricePerDiagnostics
    if (diagnosticsPrice != null &&
        diagnosticsPrice > 0.0 &&
        profile.diagnosticsPriceOrigin == PriceOrigin.AUTO &&
        diagnosticsPrice != profile.pricePerDiagnostics
    ) {
        updated = updated.copy(pricePerDiagnostics = diagnosticsPrice)
    }

    return updated
}
