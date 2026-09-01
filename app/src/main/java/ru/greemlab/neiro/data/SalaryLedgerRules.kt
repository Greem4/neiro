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
    /**
     * Цена занятия по данным YClients: самая частая ставка среди позиций
     * начисления, а если позиций не достали — деление начисления на занятия.
     */
    val factPricePerSession: Double? = null,
    /** Цена диагностики из позиций начисления, если она там была. */
    val factPriceDiagnostics: Double? = null,
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
    // Закрытый месяц — уже посчитанное число в локальной истории: месяц кончился,
    // ЗП выдана, начисление получено. Пересчитывать его двадцать раз незачем,
    // и синк его не трогает вовсе. Вернуть его к жизни может только человек —
    // кружком синхронизации месяца или разморозкой.
    if (existing != null && existing.frozen) return existing

    val manual = existing?.takeIf { it.origin == PriceOrigin.MANUAL }

    // Ноль рублей и ни одной услуги — это «данных нет» (403, пустой период,
    // начисление ещё не проведено), а не «месяц нулевой». Затирать таким ответом
    // уже полученный факт нельзя: месяц обнулился бы в статистике.
    val hasFact = fact.gross > 0.0 || fact.services > 0

    val factPrice = app.factPricePerSession

    val pricePerSession = when {
        manual != null -> manual.pricePerSession
        // Факт YClients — главный источник за прошлое. Ставка «X с даты Y»
        // историю не восстанавливает: переходы прайса размазаны по клиентам,
        // и в одном месяце занятия идут по двум ценам (HISTORY §1).
        factPrice != null -> factPrice
        // Факт есть, но делить не на что — остаётся цена, по которой приложение
        // этот месяц показывало.
        app.services > 0 -> app.pricePerSession
        else -> existing?.pricePerSession?.takeIf { it > 0.0 } ?: profile.pricePerSession
    }

    val merged = MonthEntry(
        staffId = staffId,
        year = fact.month.year,
        month = fact.month.monthValue,
        sessions = if (app.services > 0) app.services else existing?.sessions ?: 0,
        pricePerSession = pricePerSession,
        // Диагностика тоже приходит из позиций начисления — отдельной услугой
        // со своей ставкой. Цена профиля остаётся запасным вариантом.
        priceDiagnostics = app.factPriceDiagnostics
            ?: existing?.priceDiagnostics?.takeIf { it > 0.0 }
            ?: profile.pricePerDiagnostics,
        priceIntensiveChild = existing?.priceIntensiveChild?.takeIf { it > 0.0 }
            ?: profile.pricePerIntensiveChild,
        // След, а не источник: расчёт месяца берёт налог из профиля
        // (`MonthRatesResolver`). Держим здесь свежее значение, чтобы запись
        // месяца не хранила давно исправленную сумму.
        tax = profile.monthlyTaxAmount,
        factGross = if (hasFact) fact.gross else existing?.factGross,
        factSessions = if (hasFact) fact.services else existing?.factSessions,
        origin = existing?.origin ?: PriceOrigin.AUTO,
        // Морозим, как только настал срок. Запись месяца появляется ещё до
        // заморозки (текущий месяц пишется каждый синк), поэтому «уже есть
        // запись с frozen = false» не значит «человек разморозил» — иначе
        // такой месяц не закрылся бы никогда.
        //
        // Заморозка означает «месяц закрыт, начисление получено», а не «не
        // пересчитывать»: расчёт и так идёт от факта. Она лишь убирает месяц из
        // ежедневного дотягивания, а разморозка возвращает его туда.
        frozen = existing?.frozen == true ||
            shouldFreeze(fact.month, hasFact = hasFact, today = today),
        resolved = true,
        note = existing?.note.orEmpty(),
    )

    val gap = discrepancy(merged, app) ?: return merged

    // Человек соглашался с тем начислением, которое видел. Пришло другое —
    // спрашиваем снова; то же самое молчит (FOUNDATION 5).
    val previousFact = existing?.factGross
    val factIsNew = hasFact &&
        (previousFact == null || abs(fact.gross - previousFact) >= PRICE_TOLERANCE)

    return merged.copy(
        // АВТО-цена теперь и есть факт — разбирать там нечего, след в `note`
        // остаётся историей наблюдений. Спрашиваем только когда с начислением
        // спорит собственная цена человека.
        resolved = if (manual != null) manual.resolved && !factIsNew else true,
        note = appendNote(merged.note, describeDiscrepancy(gap)),
    )
}

/** Одна и та же строка расхождения не должна копиться при каждом синке. */
private fun appendNote(note: String, line: String): String = when {
    note.isBlank() -> line
    note.lineSequence().any { it == line } -> note
    else -> "$note\n$line"
}
