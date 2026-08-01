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
import ru.greemlab.neiro.domain.models.earningsContext
import ru.greemlab.neiro.ui.calendar.collectMonthLocalFacts
import ru.greemlab.neiro.ui.calendar.diagnosticsPriceOr
import ru.greemlab.neiro.ui.calendar.intensivePriceOr
import ru.greemlab.neiro.ui.calendar.sessionPriceFromFact
import java.time.LocalDate
import java.time.YearMonth

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

    /** Текущий месяц каждый синк + прошлый, пока он не заморожен. */
    suspend fun syncRecentMonths(today: LocalDate = LocalDate.now()) {
        val staffId = staffIdOrNull() ?: return
        ledgerStore.warmUp()

        val previousMonth = YearMonth.from(today).minusMonths(1)
        val previousFrozen = ledgerStore.ledger.value.month(staffId, previousMonth)?.frozen == true
        val from = if (previousFrozen) YearMonth.from(today).atDay(1) else previousMonth.atDay(1)

        pull(staffId = staffId, from = from, to = today, today = today)
    }

    /**
     * Вся история: первый логин и кнопка «перетянуть». `factGross`
     * перезаписывается свежим значением, ручное не трогается.
     */
    suspend fun syncFullHistory(from: LocalDate, today: LocalDate = LocalDate.now()) {
        val staffId = staffIdOrNull() ?: return
        ledgerStore.warmUp()
        pull(staffId = staffId, from = from, to = today, today = today)
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

    private suspend fun pull(staffId: Long, from: LocalDate, to: LocalDate, today: LocalDate) {
        val facts = when (val result = repository.fetchSalaryDaily(from, to, today)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                Log.w(TAG, "Факт ЗП не получен (code=${result.code}) — считаем по цене профиля")
                return
            }
        }
        if (facts.isEmpty()) return

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
            }
            next
        }
    }

    companion object {
        private const val TAG = "SalaryHistorySync"

        @Volatile
        private var instance: SalaryHistorySync? = null

        fun get(context: Context): SalaryHistorySync =
            instance ?: synchronized(this) {
                instance ?: SalaryHistorySync(context).also { instance = it }
            }
    }
}
