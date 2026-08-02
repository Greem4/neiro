package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable
import java.time.YearMonth

/** Кто выставил цену: приложение из данных API или человек руками. */
enum class PriceOrigin { AUTO, MANUAL }

/**
 * Правда об одном месяце (FOUNDATION 3.1).
 *
 * Деньги прошлого месяца — это [factGross], начисление YClients. [pricePerSession]
 * из него выведена и нужна, чтобы месяц можно было показать и поправить: если
 * человек ставит свою цену, месяц становится MANUAL и считается как
 * `занятия × цена` плюс диагностики и интенсивы.
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
