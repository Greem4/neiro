package ru.greemlab.neiro.ui.calendar

import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth

/** Откуда взялась цена месяца — для дисклеймера и отметки о расхождении. */
enum class PriceSource { MANUAL, FACT, PROFILE }

data class ResolvedRates(val rates: EarningsContext, val source: PriceSource)

/**
 * Локальные суммы месяца, без которых цену занятия из факта не вывести.
 *
 * Интенсивы разделены по происхождению, потому что в факт YClients они входят
 * по-разному: пришедшие из API — входят (июнь 2026: 174 450 = 115×1400 + 2250
 * + 11 200), заведённые руками — нет (GAPS 7).
 */
data class MonthLocalFacts(
    val diagnosticsCount: Int = 0,
    val diagnosticsSum: Double = 0.0,
    /** Интенсивы из API: их деньги уже внутри `factGross`, до деления вычитаются. */
    val factIntensiveSum: Double = 0.0,
    /** Интенсивы, заведённые руками: в факте их нет, из него не вычитаются. */
    val manualIntensiveSum: Double = 0.0,
)

/**
 * Цена месяца по приоритету FOUNDATION 3.2:
 *   MANUAL → его цена; есть факт → factGross ÷ занятия; иначе → профиль.
 * Текущий и будущие месяцы всегда считаются по профилю.
 *
 * Деление идёт на [MonthEntry.factSessions] (число из API), а не на локальное:
 * при расхождении локальное число меньше, и цена завысилась бы.
 */
fun resolveMonthRates(
    month: YearMonth,
    entry: MonthEntry?,
    profile: EarningsContext,
    today: LocalDate,
    diagnosticsCount: Int = 0,
    diagnosticsSum: Double = 0.0,
    factIntensiveSum: Double = 0.0,
): ResolvedRates {
    val byProfile = ResolvedRates(profile, PriceSource.PROFILE)

    // Профиль — правда про «сейчас и вперёд», история его не задевает.
    if (!month.isBefore(YearMonth.from(today))) return byProfile
    if (entry == null) return byProfile
    if (entry.origin == PriceOrigin.MANUAL) return ResolvedRates(entry.rates(), PriceSource.MANUAL)

    // factGross == 0.0 — это факт «за месяц ноль», а не «факта нет».
    val factGross = entry.factGross ?: return byProfile

    val base = factGross - diagnosticsSum - factIntensiveSum
    val divisor = (entry.factSessions ?: entry.sessions) - diagnosticsCount
    val sessionPrice = if (divisor > 0 && base > 0.0) base / divisor else profile.pricePerSession

    return ResolvedRates(
        rates = EarningsContext(
            pricePerSession = sessionPrice,
            pricePerDiagnostics = entry.diagnosticsPriceOr(profile),
            pricePerIntensiveChild = entry.intensivePriceOr(profile),
            monthlyTaxAmount = entry.taxOr(profile),
        ),
        source = PriceSource.FACT,
    )
}

/** Цена диагностики месяца: из записи, если задана, иначе из профиля. */
internal fun MonthEntry?.diagnosticsPriceOr(profile: EarningsContext): Double =
    if (this != null && priceDiagnostics > 0.0) priceDiagnostics else profile.pricePerDiagnostics

/** Ставка за ребёнка в интенсиве: из записи, если задана, иначе из профиля. */
internal fun MonthEntry?.intensivePriceOr(profile: EarningsContext): Double =
    if (this != null && priceIntensiveChild > 0.0) priceIntensiveChild else profile.pricePerIntensiveChild

/** Налог месяца: из записи, если задан, иначе из профиля. */
internal fun MonthEntry?.taxOr(profile: EarningsContext): Double =
    if (this != null && tax > 0.0) tax else profile.monthlyTaxAmount

/**
 * Собирает суммы месяца из локальных записей — то, что нужно вычесть из факта
 * перед делением (FOUNDATION 3.2, контрольный день 19.06.2026).
 */
internal fun collectMonthLocalFacts(
    dayData: Map<LocalDate, List<String>>,
    month: YearMonth,
    diagnosticsPrice: Double,
    intensivePrice: Double,
): MonthLocalFacts {
    var diagnosticsCount = 0
    var diagnosticsSum = 0.0
    var factIntensiveSum = 0.0
    var manualIntensiveSum = 0.0

    for ((date, sessions) in dayData) {
        if (date.year != month.year || date.monthValue != month.monthValue) continue
        for (raw in sessions) {
            val session = SessionParser.parse(raw)
            if (!session.countsTowardEarnings()) continue
            when (session) {
                is Session.Diagnostics -> {
                    diagnosticsCount++
                    // Цену берём из самой записи: в ней лежит ставка, актуальная
                    // на момент синхронизации, а не сегодняшняя из профиля.
                    diagnosticsSum += if (session.amount > 0.0) session.amount else diagnosticsPrice
                }

                is Session.Intensive -> {
                    val amount = session.totalAmount(intensivePrice, onlyArrived = true)
                    if (session.amountFixed) {
                        manualIntensiveSum += amount
                    } else {
                        factIntensiveSum += amount
                    }
                }

                is Session.Student -> Unit
            }
        }
    }

    return MonthLocalFacts(
        diagnosticsCount = diagnosticsCount,
        diagnosticsSum = diagnosticsSum,
        factIntensiveSum = factIntensiveSum,
        manualIntensiveSum = manualIntensiveSum,
    )
}
