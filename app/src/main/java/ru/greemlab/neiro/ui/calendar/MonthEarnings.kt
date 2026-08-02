package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.data.SalaryLedger
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth

/** Деньги месяца: грязными и после налога. */
@Immutable
data class MonthMoney(val gross: Double, val net: Double)

/**
 * Деньги месяца: **за прошлое их называет YClients, а не «цена × занятия»**.
 *
 * Локальный календарь может не досчитать занятий (день не синкнулся, запись
 * завели задним числом), и перемножением получилось бы меньше, чем реально
 * заплатили. Профиль отвечает только за текущий и будущие месяцы.
 *
 * Ручную цену пересчитываем по календарю: её ставят как раз затем, чтобы
 * разойтись с фактом (февраль–май 2026 — YClients считал по 1500 из-за старой
 * схемы percent, на руки было 1400, HISTORY §1).
 */
fun resolveMonthMoney(
    month: YearMonth,
    entry: MonthEntry?,
    rates: EarningsContext,
    /** Заработано по локальным записям — то, что насчитал [computeMonthStats]. */
    localGross: Double,
    /** Интенсивы, заведённые руками: в начисление они не попали, их деньги прибавляются. */
    manualIntensiveSum: Double,
    hasLocalRecords: Boolean,
    today: LocalDate,
): MonthMoney {
    val factMoney = entry?.factGross?.takeIf {
        it > 0.0 &&
            month.isBefore(YearMonth.from(today)) &&
            entry?.origin != PriceOrigin.MANUAL
    }
    val factSessions = entry?.factSessions
    val gross = when {
        factMoney != null -> factMoney + manualIntensiveSum

        // Месяц, которого нет в локальном календаре (приложение поставили
        // позже): занятия берём из API, иначе месяц с живым начислением
        // показал бы ноль.
        !hasLocalRecords && factSessions != null && rates.pricePerSession > 0.0 ->
            rates.pricePerSession * factSessions

        else -> localGross
    }
    return MonthMoney(gross = gross, net = monthlyNetProfit(gross, rates.monthlyTaxAmount))
}

/**
 * Месяц целиком: цены, статистика и деньги, уже приведённые к факту YClients.
 *
 * Один расчёт на все экраны — календарь и годовая статистика в профиле обязаны
 * показывать за один и тот же месяц одно и то же число.
 */
@Immutable
data class MonthEarnings(
    val month: YearMonth,
    /** Запись истории ЗП за месяц. null — месяца в истории нет. */
    val entry: MonthEntry?,
    val rates: EarningsContext,
    val source: PriceSource,
    /** Статистика месяца: счётчики локальные, деньги — из факта за прошлое. */
    val stats: CalendarMonthStats,
    val local: MonthLocalFacts,
    val hasLocalRecords: Boolean,
) {
    /**
     * Проведено занятий: локальный календарь, а без него — счётчик услуг из API.
     * Иначе история до установки приложения покажет деньги без занятий.
     */
    val completedCount: Int
        get() = if (hasLocalRecords) stats.completedCount else entry?.factSessions ?: 0
}

internal fun computeMonthEarnings(
    month: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    profileRates: EarningsContext,
    entry: MonthEntry?,
    today: LocalDate = LocalDate.now(),
): MonthEarnings {
    val local = collectMonthLocalFacts(
        dayData = dayData,
        month = month,
        diagnosticsPrice = entry.diagnosticsPriceOr(profileRates),
        intensivePrice = entry.intensivePriceOr(profileRates),
    )
    val resolved = resolveMonthRates(
        month = month,
        entry = entry,
        profile = profileRates,
        today = today,
        diagnosticsCount = local.diagnosticsCount,
        diagnosticsSum = local.diagnosticsSum,
        factIntensiveSum = local.factIntensiveSum,
    )
    val monthStats = computeMonthStats(month, dayData, resolved.rates)
    val hasLocalRecords = dayData.any { (date, sessions) ->
        date.year == month.year && date.monthValue == month.monthValue && sessions.isNotEmpty()
    }
    val money = resolveMonthMoney(
        month = month,
        entry = entry,
        rates = resolved.rates,
        localGross = monthStats.totalEarned,
        manualIntensiveSum = local.manualIntensiveSum,
        hasLocalRecords = hasLocalRecords,
        today = today,
    )

    return MonthEarnings(
        month = month,
        entry = entry,
        rates = resolved.rates,
        source = resolved.source,
        stats = monthStats.copy(totalEarned = money.gross, netProfit = money.net),
        local = local,
        hasLocalRecords = hasLocalRecords,
    )
}

/**
 * Считает и кэширует месяц для календарного экрана.
 * [dayData] лучше передавать уже отфильтрованную по месяцу карту
 * ([CalendarViewModel.currentMonthDayData]).
 */
@Composable
fun rememberMonthEarnings(
    month: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    profileRates: EarningsContext,
    ledger: SalaryLedger = SalaryLedger.Empty,
    staffId: Long = 0L,
): MonthEarnings = remember(month, dayData, profileRates, ledger, staffId) {
    computeMonthEarnings(
        month = month,
        dayData = dayData,
        profileRates = profileRates,
        entry = ledger.month(staffId, month),
    )
}
