package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable
import java.time.LocalDate

// TODO: разобраться с оплатой — история ставок отключена в расчёте прибыли (см. SessionPay).

/** Нижняя граница для первой записи в истории ставок (даты до первого изменения). */
const val SESSION_PRICE_EPOCH = "2000-01-01"

/**
 * Ставка сотрудника, действующая с [effectiveFrom] (включительно).
 * [effectiveFrom] хранится в ISO-формате `yyyy-MM-dd`.
 */
@Immutable
data class SessionPriceHistoryEntry(
    val effectiveFrom: String,
    val pricePerSession: Double,
)

/**
 * Ставка за занятие на указанную дату по истории изменений.
 * Если история пуста — используется текущая [pricePerSession].
 */
fun resolveSessionPriceOnDate(
    date: LocalDate,
    currentPrice: Double,
    history: List<SessionPriceHistoryEntry>,
): Double = UserProfile(
    pricePerSession = currentPrice,
    sessionPriceHistory = history,
).pricePerSessionOn(date)

fun UserProfile.pricePerSessionOn(date: LocalDate): Double {
    if (sessionPriceHistory.isEmpty()) return pricePerSession
    val sorted = sessionPriceHistory
        .mapNotNull { entry ->
            runCatching { LocalDate.parse(entry.effectiveFrom) }.getOrNull()
                ?.let { parsed -> parsed to entry.pricePerSession }
        }
        .sortedBy { (from, _) -> from }
    var result = pricePerSession
    for ((from, price) in sorted) {
        if (!date.isBefore(from)) {
            result = price
        } else {
            break
        }
    }
    return result
}

/** Обновляет профиль при смене ставки: дописывает историю и выставляет новую текущую цену. */
fun UserProfile.withSessionPriceChange(
    oldPrice: Double,
    newPrice: Double,
    effectiveFrom: LocalDate,
): UserProfile {
    val fromIso = effectiveFrom.toString()
    val newHistory = buildList {
        if (sessionPriceHistory.isEmpty()) {
            add(SessionPriceHistoryEntry(SESSION_PRICE_EPOCH, oldPrice))
        }
        addAll(sessionPriceHistory)
        if (none { it.effectiveFrom == fromIso && it.pricePerSession == newPrice }) {
            add(SessionPriceHistoryEntry(fromIso, newPrice))
        }
    }
    return copy(pricePerSession = newPrice, sessionPriceHistory = newHistory)
}
