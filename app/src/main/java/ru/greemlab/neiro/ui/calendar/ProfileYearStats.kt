package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import ru.greemlab.neiro.data.SalaryLedger
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.LocalDate
import java.time.YearMonth

/**
 * Что известно про цену одного месяца — для дисклеймера, отметки о расхождении
 * и разбора в статистике (FOUNDATION 4–5).
 *
 * Один носитель вместо десятка параллельных списков по 12 элементов: добавить
 * поле в разбор месяца не должно быть правкой пяти сигнатур.
 */
@Immutable
data class MonthPriceMeta(
    val origin: PriceOrigin = PriceOrigin.AUTO,
    val source: PriceSource = PriceSource.PROFILE,
    /** Расхождение разобрано — отметку не показываем. */
    val resolved: Boolean = true,
    val frozen: Boolean = false,
    val pricePerSession: Double = 0.0,
    val priceDiagnostics: Double = 0.0,
    val priceIntensiveChild: Double = 0.0,
    val tax: Double = 0.0,
    /** Что говорит YClients за месяц. */
    val factGross: Double? = null,
    /** Цена занятия, вытекающая из факта — вторая строка в разборе расхождения. */
    val factPricePerSession: Double? = null,
    /** Занятий для сверки: услуги месяца минус диагностики. */
    val billableSessions: Int = 0,
) {
    /** Есть что разбирать: факт расходится с ценой приложения и человек ещё не решил. */
    val needsReview: Boolean get() = !resolved && factPricePerSession != null
}

/**
 * Сводная статистика за календарный год.
 *
 * @param completedSessions Число проведённых занятий (посещённые ученики и диагностики).
 * @param totalNetEarned Сумма чистой прибыли по месяцам года.
 * @param totalTaxAmount Налог за прошедшие месяцы года: он платится ежемесячно
 *   независимо от занятости, но будущие месяцы в сумму не входят — в июле за год
 *   набежало семь платежей, а не двенадцать.
 * @param monthlyNet Чистая прибыль по месяцам, индекс 0 = январь.
 * @param monthlyCompleted Проведённые занятия по месяцам, индекс 0 = январь.
 */
@Immutable
data class ProfileYearStats(
    val year: Int,
    val completedSessions: Int,
    val totalNetEarned: Double,
    val totalTaxAmount: Double,
    val monthlyNet: List<Double>,
    val monthlyCompleted: List<Int>,
    /** По одному элементу на месяц, индекс 0 = январь. */
    val months: List<MonthPriceMeta> = List(12) { MonthPriceMeta() },
) {
    /** Хоть один месяц года ждёт разбора — бейдж в заголовке секции. */
    val hasUnresolvedMonths: Boolean get() = months.any { it.needsReview }

    companion object {
        fun empty(year: Int): ProfileYearStats = ProfileYearStats(
            year = year,
            completedSessions = 0,
            totalNetEarned = 0.0,
            totalTaxAmount = 0.0,
            monthlyNet = List(12) { 0.0 },
            monthlyCompleted = List(12) { 0 },
            months = List(12) { MonthPriceMeta() },
        )
    }
}

@Composable
fun rememberProfileYearStats(
    year: Int,
    dayData: Map<LocalDate, List<String>>,
    profileRates: EarningsContext,
    ledger: SalaryLedger = SalaryLedger.Empty,
    staffId: Long = 0L,
): ProfileYearStats = remember(year, dayData, profileRates, ledger, staffId) {
    computeProfileYearStats(
        year = year,
        dayData = dayData,
        profileRates = profileRates,
        ledger = ledger,
        staffId = staffId,
    )
}

/**
 * Сколько месяцев года уже прожито на дату [today] — за столько платежей набежал
 * налог. Текущий месяц считается: платёж за него уже наступил.
 */
internal fun elapsedMonthsInYear(year: Int, today: LocalDate): Int = when {
    year < today.year -> 12
    year > today.year -> 0
    else -> today.monthValue
}

/**
 * Годы с данными в календаре + годы из истории ЗП + текущий год (по убыванию).
 *
 * Годы истории обязательны: месяцы, которых нет в локальном календаре
 * (приложение поставили позже), иначе не покажутся вовсе (FOUNDATION 3.3).
 */
fun availableStatsYears(
    dayData: Map<LocalDate, List<String>>,
    ledgerYears: Set<Int> = emptySet(),
    currentYear: Int = YearMonth.now().year,
): List<Int> {
    val years = dayData.keys.map { it.year }.toMutableSet()
    years += ledgerYears
    years.add(currentYear)
    return years.sortedDescending()
}

internal fun computeProfileYearStats(
    year: Int,
    dayData: Map<LocalDate, List<String>>,
    profileRates: EarningsContext,
    ledger: SalaryLedger = SalaryLedger.Empty,
    staffId: Long = 0L,
    today: LocalDate = LocalDate.now(),
): ProfileYearStats {
    var completedSessions = 0
    var totalNetEarned = 0.0
    val monthlyNet = Array(12) { 0.0 }
    val monthlyCompleted = Array(12) { 0 }
    val monthsMeta = Array(12) { MonthPriceMeta() }

    val yearDayData = buildMap {
        for ((date, sessions) in dayData) {
            if (date.year == year) put(date, sessions)
        }
    }

    for (month in 1..12) {
        val currentMonth = YearMonth.of(year, month)
        val entry = ledger.month(staffId, currentMonth)
        val local = collectMonthLocalFacts(
            dayData = yearDayData,
            month = currentMonth,
            diagnosticsPrice = entry.diagnosticsPriceOr(profileRates),
            intensivePrice = entry.intensivePriceOr(profileRates),
        )
        val resolvedRates = resolveMonthRates(
            month = currentMonth,
            entry = entry,
            profile = profileRates,
            today = today,
            diagnosticsCount = local.diagnosticsCount,
            diagnosticsSum = local.diagnosticsSum,
            factIntensiveSum = local.factIntensiveSum,
        )
        val monthRates = resolvedRates.rates
        val factGross = entry?.factGross
        val services = entry?.factSessions ?: local.services
        monthsMeta[month - 1] = MonthPriceMeta(
            origin = entry?.origin ?: PriceOrigin.AUTO,
            source = resolvedRates.source,
            resolved = entry?.resolved ?: true,
            frozen = entry?.frozen ?: false,
            pricePerSession = monthRates.pricePerSession,
            priceDiagnostics = monthRates.pricePerDiagnostics,
            priceIntensiveChild = monthRates.pricePerIntensiveChild,
            tax = monthRates.monthlyTaxAmount,
            factGross = factGross,
            factPricePerSession = factGross?.let {
                sessionPriceFromFact(
                    factGross = it,
                    services = services,
                    diagnosticsCount = local.diagnosticsCount,
                    diagnosticsSum = local.diagnosticsSum,
                    factIntensiveSum = local.factIntensiveSum,
                )
            },
            billableSessions = (services - local.diagnosticsCount).coerceAtLeast(0),
        )

        val monthStats = computeMonthStats(
            currentMonth = currentMonth,
            dayData = yearDayData,
            rates = monthRates,
        )

        // Деньги прошлого месяца — это начисление YClients целиком, а не
        // «цена × занятия»: локальный календарь может не досчитать занятий, и
        // перемножением получилось бы меньше, чем реально заплатили.
        //
        // Ручную цену пересчитываем по календарю: её ставят как раз затем, чтобы
        // разойтись с фактом (февраль–май 2026 — YClients считал по 1500 из-за
        // старой схемы percent, на руки было 1400, HISTORY §1).
        val factMoney = factGross?.takeIf {
            it > 0.0 &&
                currentMonth.isBefore(YearMonth.from(today)) &&
                entry?.origin != PriceOrigin.MANUAL
        }
        val hasLocalRecords = yearDayData.any { (date, sessions) ->
            date.monthValue == month && sessions.isNotEmpty()
        }
        val factSessions = entry?.factSessions
        val net = when {
            // Интенсивы, заведённые руками, в начисление не попали — их деньги
            // существуют только в приложении и прибавляются к факту.
            factMoney != null ->
                monthlyNetProfit(factMoney + local.manualIntensiveSum, monthRates.monthlyTaxAmount)

            // Ручная цена на месяце, которого нет в локальном календаре
            // (приложение поставили позже): занятия берём из API, иначе правка
            // цены обнулила бы месяц с живым начислением.
            !hasLocalRecords && factSessions != null && monthRates.pricePerSession > 0.0 ->
                monthlyNetProfit(
                    monthRates.pricePerSession * factSessions,
                    monthRates.monthlyTaxAmount,
                )

            else -> monthStats.netProfit
        }

        // Интенсивы в счётчик занятий не входят — это отдельный формат.
        // Месяц без локальных записей считаем по числу услуг из API: иначе
        // история до установки приложения покажет деньги без занятий.
        val completed = if (hasLocalRecords) monthStats.completedCount else factSessions ?: 0

        completedSessions += completed
        monthlyNet[month - 1] = net
        monthlyCompleted[month - 1] = completed
        totalNetEarned += net
    }

    val totalTaxAmount = profileRates.monthlyTaxAmount * elapsedMonthsInYear(year, today)

    return ProfileYearStats(
        year = year,
        completedSessions = completedSessions,
        totalNetEarned = totalNetEarned,
        totalTaxAmount = totalTaxAmount,
        months = monthsMeta.toList(),
        monthlyNet = monthlyNet.toList(),
        monthlyCompleted = monthlyCompleted.toList(),
    )
}
