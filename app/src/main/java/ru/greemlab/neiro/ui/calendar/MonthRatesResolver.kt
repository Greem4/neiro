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
    /**
     * Занятия и диагностики вместе — как `services_count` в YClients.
     * Интенсивы сюда не входят: у них свой счётчик `group_services_count`.
     */
    val services: Int = 0,
    val diagnosticsCount: Int = 0,
    val diagnosticsSum: Double = 0.0,
    /** Интенсивы из API: их деньги уже внутри `factGross`, до деления вычитаются. */
    val factIntensiveSum: Double = 0.0,
    /** Интенсивы, заведённые руками: в факте их нет, из него не вычитаются. */
    val manualIntensiveSum: Double = 0.0,
)

/**
 * Цена месяца: **факт YClients — главный источник за прошлое.**
 *
 *   MANUAL → его цена; есть факт → factGross ÷ занятия; иначе → профиль.
 *
 * Профиль отвечает только за текущий и будущие месяцы — правка цены в профиле
 * не переписывает историю.
 *
 * Заморозка не отменяет пересчёт из факта: начисление за закрытый месяц уже не
 * меняется, а вот число занятий в локальном календаре может дозаполниться. Своя
 * цена закрытого месяца идёт в дело только когда факта нет вовсе (403, офлайн).
 *
 * Ставка «X с даты Y» историю не восстанавливает: переходы прайса размазаны по
 * клиентам на 2,5 месяца, и в августе 2025 занятия шли одновременно по 1250 и
 * 1400 (HISTORY §1). Где факт врёт — месяц правится руками и становится MANUAL.
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

    // Цена, которой закрылся месяц, — на случай, когда факта не будет.
    val storedRates = if (entry.pricePerSession > 0.0) {
        ResolvedRates(entry.rates(), PriceSource.PROFILE)
    } else {
        byProfile
    }

    val factPrice = entry.factGross?.let { factGross ->
        sessionPriceFromFact(
            factGross = factGross,
            services = entry.factSessions ?: entry.sessions,
            diagnosticsCount = diagnosticsCount,
            diagnosticsSum = diagnosticsSum,
            factIntensiveSum = factIntensiveSum,
        )
    } ?: return storedRates

    return ResolvedRates(
        rates = EarningsContext(
            pricePerSession = factPrice,
            pricePerDiagnostics = entry.diagnosticsPriceOr(profile),
            pricePerIntensiveChild = entry.intensivePriceOr(profile),
            monthlyTaxAmount = entry.taxOr(profile),
        ),
        source = PriceSource.FACT,
    )
}

/**
 * Цена занятия, вытекающая из факта YClients:
 * `(факт − диагностики − интенсивы) ÷ занятия`.
 *
 * Контрольный день 19.06.2026: `(12 050 − 2250) ÷ 7 = 1400`, а не `12 050 ÷ 8 = 1506`.
 * Возвращает `null`, если делить нечего или не на что — тогда цену даёт профиль.
 */
fun sessionPriceFromFact(
    factGross: Double,
    services: Int,
    diagnosticsCount: Int,
    diagnosticsSum: Double,
    factIntensiveSum: Double,
): Double? {
    val base = factGross - diagnosticsSum - factIntensiveSum
    val divisor = services - diagnosticsCount
    if (divisor <= 0 || base <= 0.0) return null
    return base / divisor
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
    var services = 0
    var diagnosticsCount = 0
    var diagnosticsSum = 0.0
    var factIntensiveSum = 0.0
    var manualIntensiveSum = 0.0

    for ((date, sessions) in dayData) {
        if (date.year != month.year || date.monthValue != month.monthValue) continue
        val parsed = sessions.map(SessionParser::parse)
        val intensiveChildrenByTime = buildIntensiveChildrenByTime(parsed)
        for (session in parsed) {
            if (!session.countsTowardEarnings()) continue
            when (session) {
                is Session.Diagnostics -> {
                    services++
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

                is Session.Student -> {
                    // Ученик на слоте интенсива — та же запись, что и ребёнок
                    // интенсива: в индивидуальные услуги он не входит.
                    if (!isStudentCoveredByIntensive(session, intensiveChildrenByTime)) services++
                }
            }
        }
    }

    return MonthLocalFacts(
        services = services,
        diagnosticsCount = diagnosticsCount,
        diagnosticsSum = diagnosticsSum,
        factIntensiveSum = factIntensiveSum,
        manualIntensiveSum = manualIntensiveSum,
    )
}
