package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable

/**
 * Цены, по которым считаются деньги за отрезок времени.
 *
 * Один носитель вместо четырёх параметров: цена месяца берётся из истории
 * (см. docs/pricing-from-api/FOUNDATION.md, раздел 3.2), а профиль — лишь
 * один из источников. Добавление пятой цены не должно задевать десяток
 * сигнатур.
 */
@Immutable
data class EarningsContext(
    val pricePerSession: Double = 0.0,
    val pricePerDiagnostics: Double = 0.0,
    val pricePerIntensiveChild: Double = 0.0,
    val monthlyTaxAmount: Double = 0.0,
) {
    companion object {
        val Empty = EarningsContext()
    }
}

/** Цены профиля — правда про текущий и будущие месяцы (FOUNDATION 1.3). */
fun UserProfile.earningsContext(): EarningsContext = EarningsContext(
    pricePerSession = pricePerSession,
    pricePerDiagnostics = pricePerDiagnostics,
    pricePerIntensiveChild = pricePerIntensiveChild,
    monthlyTaxAmount = monthlyTaxAmount,
)
