package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable
import java.time.YearMonth

/** Кто выставил цену: приложение из данных API или человек руками. */
enum class PriceOrigin { AUTO, MANUAL }

/**
 * Замороженная правда об одном месяце (FOUNDATION 3.1).
 *
 * Сумма месяца не хранится: она считается как sessions × pricePerSession
 * плюс диагностики и интенсивы. Правится только цена.
 */
@Immutable
data class MonthEntry(
    val staffId: Long = 0L,
    val year: Int,
    val month: Int,
    val sessions: Int = 0,
    val pricePerSession: Double = 0.0,
    val priceDiagnostics: Double = 0.0,
    val priceIntensiveChild: Double = 0.0,
    val tax: Double = 0.0,
    /** Что говорит YClients за месяц. null — факта нет (офлайн, 403, не тянули). */
    val factGross: Double? = null,
    /** Занятий по данным API (services_count). Может расходиться с [sessions]. */
    val factSessions: Int? = null,
    val origin: PriceOrigin = PriceOrigin.AUTO,
    val frozen: Boolean = false,
    /** Расхождение разобрано человеком — больше не спрашивать. */
    val resolved: Boolean = true,
    val note: String = "",
) {
    val yearMonth: YearMonth get() = YearMonth.of(year, month)

    fun rates(): EarningsContext = EarningsContext(
        pricePerSession = pricePerSession,
        pricePerDiagnostics = priceDiagnostics,
        pricePerIntensiveChild = priceIntensiveChild,
        monthlyTaxAmount = tax,
    )
}
