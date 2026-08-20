package ru.greemlab.neiro.sync

import ru.greemlab.neiro.data.network.SalaryRatesFromApi
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.LocalDate

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

/**
 * Ставка занятия из посуточного расчёта — поверх ставки закрытого начисления.
 *
 * Начисление отстаёт на месяц: пока август идёт, единственное проведённое —
 * июльское, и оно честно отдаёт июльскую ставку. Августовскую видно только по
 * дням ([sessionRateFromDailyFacts]), поэтому свежий день главнее.
 *
 * Сравнение с [periodEnd] — не формальность: чистых дней в новом месяце может
 * не оказаться вовсе (все с диагностиками), и тогда посуточный источник
 * вернёт день из прошлого, чью ставку начисление уже уточнило.
 */
fun applyDailyRateToProfile(
    profile: UserProfile,
    rate: DailySessionRate?,
    periodEnd: LocalDate? = null,
): UserProfile {
    if (rate == null || rate.pricePerSession <= 0.0) return profile
    if (profile.sessionPriceOrigin != PriceOrigin.AUTO) return profile
    if (periodEnd != null && !rate.date.isAfter(periodEnd)) return profile
    if (rate.pricePerSession == profile.pricePerSession) return profile
    return profile.copy(pricePerSession = rate.pricePerSession)
}
