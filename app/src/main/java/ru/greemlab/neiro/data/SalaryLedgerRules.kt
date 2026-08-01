package ru.greemlab.neiro.data

import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Семь дней запаса после конца месяца — на удаления задним числом
 * (`salary_discrepancy: {reason: "deleted"}`, GAPS 7). Не 1-го числа.
 */
private const val FREEZE_GRACE_DAYS = 7L

/** Расхождение меньше полурубля — это округление, а не разные цены. */
private const val PRICE_TOLERANCE = 0.5

/** Итог месяца по данным YClients — собран из посуточного расчёта. */
data class MonthFact(
    val month: YearMonth,
    val gross: Double,
    /** `services_count` за месяц: занятия и диагностики вместе. */
    val services: Int,
)

/**
 * Что приложение знает о месяце: сколько записей в его календаре и по какой
 * цене оно этот месяц считало.
 */
data class MonthAppView(
    /** Занятия + диагностики по локальному календарю. */
    val services: Int = 0,
    val diagnosticsCount: Int = 0,
    /** Цена занятия, по которой приложение показывало этот месяц. */
    val pricePerSession: Double = 0.0,
    /** Цена занятия, вытекающая из факта YClients (после вычетов). */
    val factPricePerSession: Double? = null,
)

/** Расхождение факта YClients и цены приложения (FOUNDATION 4). */
data class Discrepancy(
    val month: YearMonth,
    val sessions: Int,
    val appPricePerSession: Double,
    val factPricePerSession: Double,
) {
    val appGross: Double get() = appPricePerSession * sessions
    val factGross: Double get() = factPricePerSession * sessions
    val difference: Double get() = factGross - appGross
}

/**
 * Месяц закрыт: не текущий, факт получен, прошло ≥ [graceDays] с конца месяца.
 *
 * Заморозка обратима и ничего не удаляет — она лишь говорит «не пересчитывай».
 */
fun shouldFreeze(
    month: YearMonth,
    hasFact: Boolean,
    today: LocalDate,
    graceDays: Long = FREEZE_GRACE_DAYS,
): Boolean {
    if (!hasFact) return false
    if (!month.isBefore(YearMonth.from(today))) return false
    return !today.isBefore(month.atEndOfMonth().plusDays(graceDays))
}

/**
 * Расхождение факта и цены приложения. `null` — расхождения нет, факта нет
 * или делить не на что.
 */
fun discrepancy(entry: MonthEntry, app: MonthAppView): Discrepancy? {
    if (entry.factGross == null) return null
    val factPrice = app.factPricePerSession ?: return null
    val services = entry.factSessions ?: entry.sessions
    val sessions = services - app.diagnosticsCount
    if (sessions <= 0) return null
    if (abs(factPrice - app.pricePerSession) < PRICE_TOLERANCE) return null
    return Discrepancy(
        month = entry.yearMonth,
        sessions = sessions,
        appPricePerSession = app.pricePerSession,
        factPricePerSession = factPrice,
    )
}

/**
 * След расхождения для `note`. Пишется всегда, даже если показ расхождений
 * выключен тумблером: иначе через полгода не останется следов, а именно по
 * такому следу и нашлась история с 1500 (FOUNDATION 5).
 */
fun describeDiscrepancy(gap: Discrepancy): String =
    "факт YClients ${gap.factGross.roundToLong()}, приложение ${gap.appGross.roundToLong()}"

/**
 * Слияние факта YClients в запись месяца (FOUNDATION 4, 7).
 *
 * Ручное (`origin = MANUAL`) не переписывается никогда — приложение только
 * дописывает след расхождения в `note`. Решение человека (`resolved`) тоже
 * живёт вечно: разобранный месяц молчит.
 */
fun mergeFact(
    existing: MonthEntry?,
    fact: MonthFact,
    app: MonthAppView,
    profile: EarningsContext,
    staffId: Long,
    today: LocalDate,
): MonthEntry {
    val manual = existing != null && existing.origin == PriceOrigin.MANUAL

    val pricePerSession = when {
        manual -> existing.pricePerSession
        // Месяц, который приложение прожило само: замораживается по своей цене —
        // именно её человек видел весь месяц (FOUNDATION 4).
        app.services > 0 -> app.pricePerSession
        // Месяца в локальном календаре нет (приложение поставили позже) —
        // единственная правда о нём это факт (FOUNDATION 7).
        else -> app.factPricePerSession ?: profile.pricePerSession
    }

    val merged = MonthEntry(
        staffId = staffId,
        year = fact.month.year,
        month = fact.month.monthValue,
        sessions = if (app.services > 0) app.services else existing?.sessions ?: 0,
        pricePerSession = pricePerSession,
        priceDiagnostics = existing?.priceDiagnostics?.takeIf { it > 0.0 }
            ?: profile.pricePerDiagnostics,
        priceIntensiveChild = existing?.priceIntensiveChild?.takeIf { it > 0.0 }
            ?: profile.pricePerIntensiveChild,
        tax = existing?.tax?.takeIf { it > 0.0 } ?: profile.monthlyTaxAmount,
        factGross = fact.gross,
        factSessions = fact.services,
        origin = existing?.origin ?: PriceOrigin.AUTO,
        // Морозим, как только настал срок. Запись месяца появляется ещё до
        // заморозки (текущий месяц пишется каждый синк), поэтому «уже есть
        // запись с frozen = false» не значит «человек разморозил» — иначе
        // такой месяц не закрылся бы никогда. Разморозка живёт до следующего
        // синка: у ручной цены это ничего не меняет (её никто не переписывает),
        // а АВТО-месяц ровно за этим и размораживают — чтобы пересчитался.
        frozen = existing?.frozen == true ||
            shouldFreeze(fact.month, hasFact = true, today = today),
        resolved = true,
        note = existing?.note.orEmpty(),
    )

    val gap = discrepancy(merged, app) ?: return merged
    return merged.copy(
        resolved = existing?.resolved ?: false,
        note = appendNote(merged.note, describeDiscrepancy(gap)),
    )
}

/** Одна и та же строка расхождения не должна копиться при каждом синке. */
private fun appendNote(note: String, line: String): String = when {
    note.isBlank() -> line
    note.lineSequence().any { it == line } -> note
    else -> "$note\n$line"
}
