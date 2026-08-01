package ru.greemlab.neiro.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import ru.greemlab.neiro.data.MonthAppView
import ru.greemlab.neiro.data.MonthFact
import ru.greemlab.neiro.data.SalaryLedger
import ru.greemlab.neiro.data.SalaryLedgerStore
import ru.greemlab.neiro.data.mergeFact
import ru.greemlab.neiro.data.network.ApiResult
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.domain.models.earningsContext
import ru.greemlab.neiro.ui.calendar.collectMonthLocalFacts
import ru.greemlab.neiro.ui.calendar.diagnosticsPriceOr
import ru.greemlab.neiro.ui.calendar.intensivePriceOr
import ru.greemlab.neiro.ui.calendar.sessionPriceFromFact
import java.time.LocalDate
import java.time.YearMonth

/**
 * Чем кончилась попытка достать деньги из YClients.
 *
 * Фоновым синкам хватает «получилось/нет» (FOUNDATION 3.5: 403 у сотрудника без
 * прав владельца пользователю не показывают). Но когда человек сам нажал кнопку,
 * молчание — худший из ответов: «не нажалось», «нет прав» и «за месяц нет
 * начислений» выглядят одинаково, и починить по такому непонятно что.
 */
sealed interface SalaryPullResult {
    /** Факт получен и записан в историю. */
    data object Updated : SalaryPullResult

    /** Ответ пришёл, но начислений за период нет — месяц до найма, например. */
    data object NoData : SalaryPullResult

    /**
     * Не ответили: нет прав (403), истекла сессия (401), нет сети, не разобрался
     * ответ. [reason] — то, что сказал сам YClients или исключение: без него все
     * эти беды выглядят одинаково и чинятся вслепую.
     */
    data class Failed(val code: Int?, val reason: String = "") : SalaryPullResult

    /** `staffId` неизвестен — писать историю некуда. */
    data object NoStaff : SalaryPullResult
}

/**
 * Заполнение истории ЗП из YClients (FOUNDATION 4, 7).
 *
 * Вызывается только после успешного календарного синка: цена месяца сверяется
 * с локальными записями, и они должны быть уже на месте. Live-опрос, push и
 * воркеры уведомлений к деньгам отношения не имеют и здесь не участвуют.
 *
 * Ошибки не показываются пользователем никогда: при 403 у сотрудника без прав
 * владельца и в офлайне история просто не обновляется, а расчёт остаётся по
 * цене профиля (FOUNDATION 3.5).
 */
class SalaryHistorySync private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val repository = YClientsRepository.getInstance(appContext)
    private val ledgerStore = SalaryLedgerStore.get(appContext)
    private val calendarRepository: CalendarRepository = CalendarDataStoreProvider.get(appContext)
    private val syncPreferences = SyncPreferences.get(appContext)

    /** Текущий месяц каждый синк + прошлый, пока он не заморожен. */
    suspend fun syncRecentMonths(today: LocalDate = LocalDate.now()): SalaryPullResult {
        val staffId = staffIdOrNull() ?: return SalaryPullResult.NoStaff
        ledgerStore.warmUp()
        updateAutoProfileRates()

        val previousMonth = YearMonth.from(today).minusMonths(1)
        val previousFrozen = ledgerStore.ledger.value.month(staffId, previousMonth)?.frozen == true
        val from = if (previousFrozen) YearMonth.from(today).atDay(1) else previousMonth.atDay(1)

        return pull(staffId = staffId, from = from, to = today, today = today)
    }

    /**
     * Вся история: первый логин и кнопка «перетянуть». `factGross`
     * перезаписывается свежим значением, ручное и закрытые месяцы не трогаются.
     *
     * @return `true`, если факт реально получен и записан.
     */
    suspend fun syncFullHistory(
        from: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): SalaryPullResult {
        val staffId = staffIdOrNull() ?: return SalaryPullResult.NoStaff
        ledgerStore.warmUp()
        updateAutoProfileRates()
        return pull(staffId = staffId, from = from, to = today, today = today)
    }

    /**
     * Первое заполнение истории — один раз за установку.
     *
     * Не зависит ни от календарного синка, ни от [SyncPreferences.hasCompletedInitialFullSync]:
     * деньги приходят из начисления YClients, локальные записи им не нужны, а
     * старый флаг у всех уже взведён — по нему история не заполнилась бы никогда.
     * Флаг ставится только при реально полученном факте, иначе офлайн при первом
     * запуске оставил бы статистику пустой навсегда.
     *
     * @return `true`, если историю подтянули именно сейчас.
     */
    suspend fun ensureHistoryPulledOnce(today: LocalDate = LocalDate.now()): Boolean {
        if (syncPreferences.hasCompletedSalaryHistorySync) return false
        val staffId = staffIdOrNull() ?: return false
        ledgerStore.warmUp()
        reopenAutoMonths(staffId)
        val pulled = syncFullHistory(from = historyStart(today), today = today)
        if (pulled is SalaryPullResult.Updated) syncPreferences.markSalaryHistorySyncComplete()
        return pulled is SalaryPullResult.Updated
    }

    /**
     * Снимает заморозку со всех АВТО-месяцев перед первым заполнением.
     *
     * Ранние сборки закрывали месяц по цене профиля, а не по начислению, и такая
     * запись уже лежит в истории. Заморозка теперь означает «не пересчитывать»,
     * и без этого шага неверная цена законсервировалась бы навсегда. Ручные
     * месяцы не трогаем: их цену ставил человек, она правде не подлежит.
     */
    private suspend fun reopenAutoMonths(staffId: Long) {
        ledgerStore.update { ledger ->
            val reopened = ledger.months.values.filter {
                it.staffId == staffId && it.frozen && it.origin != PriceOrigin.MANUAL
            }
            if (reopened.isEmpty()) return@update ledger
            reopened.fold(ledger) { acc, entry -> acc.withMonth(entry.copy(frozen = false)) }
        }
    }

    /**
     * Перетянуть один месяц — кружок синхронизации в разборе месяца.
     *
     * Закрытый месяц синк обходит стороной (`mergeFact`), поэтому здесь он
     * сначала размораживается: нажатие на кнопку и значит «посчитай заново».
     * Ручная цена переживает и это — её переписывает только человек.
     */
    suspend fun syncSingleMonth(
        month: YearMonth,
        today: LocalDate = LocalDate.now(),
    ): SalaryPullResult {
        val staffId = staffIdOrNull() ?: return SalaryPullResult.NoStaff
        ledgerStore.warmUp()

        ledgerStore.update { ledger ->
            val existing = ledger.month(staffId, month) ?: return@update ledger
            if (!existing.frozen) ledger else ledger.withMonth(existing.copy(frozen = false))
        }

        val to = minOf(month.atEndOfMonth(), today)
        if (month.atDay(1).isAfter(to)) return SalaryPullResult.NoData
        return pull(staffId = staffId, from = month.atDay(1), to = to, today = today)
    }

    /**
     * Начало истории: 36 месяцев назад или первый день локального календаря,
     * если он старше. Та же глубина, что и у полной синхронизации календаря.
     */
    private suspend fun historyStart(today: LocalDate): LocalDate {
        val default = YearMonth.from(today).minusMonths(HISTORY_DEPTH_MONTHS).atDay(1)
        val earliestLocal = calendarRepository.dayDataFlow.first().keys.minOrNull()
        return if (earliestLocal != null) {
            minOf(earliestLocal.withDayOfMonth(1), default)
        } else {
            default
        }
    }

    /**
     * АВТО-цены профиля из детализации последнего начисления (FOUNDATION 6.2).
     *
     * Живёт здесь, а не в календарном синке: тот вызывается и live-опросом,
     * который трогать нельзя. Ручные цены не переписываются никогда.
     */
    private suspend fun updateAutoProfileRates() {
        val rates = when (val result = repository.fetchLatestSalaryRates()) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                Log.w(TAG, "Ставки из начисления не получены (code=${result.code})")
                return
            }
        }
        if (rates.isEmpty) return
        calendarRepository.updateProfile { profile -> applyApiRatesToProfile(profile, rates) }
    }

    /**
     * `staffId` неизвестен — историю не тянем вовсе: писать её под ключом `0`
     * значит смешать чужие данные при первом же удачном детекте (FOUNDATION 8.1).
     */
    private fun staffIdOrNull(): Long? {
        val staffId = repository.staffId?.toLong()
        if (staffId == null || staffId == 0L) {
            Log.w(TAG, "staffId неизвестен — история ЗП не обновляется")
            return null
        }
        return staffId
    }

    private suspend fun pull(
        staffId: Long,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ): SalaryPullResult {
        val facts = when (val result = repository.fetchSalaryDaily(from, to, today)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                Log.w(TAG, "Факт ЗП не получен (code=${result.code}): ${result.message}")
                return SalaryPullResult.Failed(result.code, result.message)
            }
        }
        if (facts.isEmpty()) return SalaryPullResult.NoData

        var merged = 0

        val profile = calendarRepository.userProfileFlow.first().earningsContext()
        val dayData = calendarRepository.dayDataFlow.first()
        val factsByMonth = facts.entries.groupBy { YearMonth.from(it.key) }

        ledgerStore.update { ledger ->
            var next: SalaryLedger = ledger.withDayFacts(
                staffId = staffId,
                facts = facts.mapValues { (_, fact) -> fact.salary },
            )
            for ((month, days) in factsByMonth) {
                val existing = next.month(staffId, month)
                val local = collectMonthLocalFacts(
                    dayData = dayData,
                    month = month,
                    diagnosticsPrice = existing.diagnosticsPriceOr(profile),
                    intensivePrice = existing.intensivePriceOr(profile),
                )
                val fact = MonthFact(
                    month = month,
                    gross = days.sumOf { it.value.salary },
                    services = days.sumOf { it.value.servicesCount },
                )

                // Месяц до найма: YClients отдаёт по нему нули, локальных записей
                // тоже нет. Заводить на него запись незачем — иначе в
                // переключателе лет всплывают годы, в которых человек ещё не
                // работал, а месяцы показывают сегодняшнюю цену профиля.
                val hasAnything = fact.gross > 0.0 || fact.services > 0 || local.services > 0
                if (existing == null && !hasAnything) continue

                val app = MonthAppView(
                    services = local.services,
                    diagnosticsCount = local.diagnosticsCount,
                    // Цена приложения: у закрытого месяца — его собственная,
                    // иначе текущая из профиля.
                    pricePerSession = existing?.pricePerSession?.takeIf { it > 0.0 }
                        ?: profile.pricePerSession,
                    factPricePerSession = sessionPriceFromFact(
                        factGross = fact.gross,
                        services = fact.services,
                        diagnosticsCount = local.diagnosticsCount,
                        diagnosticsSum = local.diagnosticsSum,
                        factIntensiveSum = local.factIntensiveSum,
                    ),
                )
                next = next.withMonth(
                    mergeFact(
                        existing = existing,
                        fact = fact,
                        app = app,
                        profile = profile,
                        staffId = staffId,
                        today = today,
                    ),
                )
                merged++
            }
            next
        }
        return if (merged > 0) SalaryPullResult.Updated else SalaryPullResult.NoData
    }

    companion object {
        private const val TAG = "SalaryHistorySync"

        /** Глубина первого заполнения истории — как у полного синка календаря. */
        private const val HISTORY_DEPTH_MONTHS = 36L

        @Volatile
        private var instance: SalaryHistorySync? = null

        fun get(context: Context): SalaryHistorySync =
            instance ?: synchronized(this) {
                instance ?: SalaryHistorySync(context).also { instance = it }
            }
    }
}
