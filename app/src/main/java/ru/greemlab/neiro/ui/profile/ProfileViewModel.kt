package ru.greemlab.neiro.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import ru.greemlab.neiro.data.SalaryLedger
import ru.greemlab.neiro.data.SalaryLedgerStore
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.sync.SalaryHistorySync
import ru.greemlab.neiro.sync.SalaryPullResult
import java.time.DayOfWeek
import java.time.YearMonth

/** Итог перетяжки месяца: к какому месяцу относится и что сказать человеку. */
data class MonthSyncNote(val month: YearMonth, val text: String)

/**
 * Что показать после нажатия на кружок синхронизации месяца.
 *
 * Молчать здесь нельзя, хотя фоновые денежные запросы молчат намеренно
 * (FOUNDATION 3.5): человек нажал кнопку и ждёт ответа, а «нет прав»,
 * «нет сети» и «за месяц нет начислений» без текста неразличимы.
 */
private fun describePull(result: SalaryPullResult): String = when (result) {
    is SalaryPullResult.Updated -> "Обновлено из YClients"
    is SalaryPullResult.NoData -> "За этот месяц начислений нет"
    is SalaryPullResult.NoStaff -> "Не удалось определить сотрудника в YClients"
    is SalaryPullResult.Failed -> when (result.code) {
        401 -> "Сессия истекла — войдите в YClients заново"
        403 -> "YClients не отдаёт зарплату этому аккаунту"
        else -> "Не удалось получить — проверьте связь"
    }
}

/**
 * ViewModel для управления профилем пользователя.
 *
 * Все апдейты идут через одну очередь [updateChannel] — это гарантирует, что
 * параллельные изменения (например, быстрый ввод в нескольких полях) применяются
 * последовательно и не теряют данные.
 */
class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CalendarRepository = CalendarDataStoreProvider.get(application)
    private val ledgerStore = SalaryLedgerStore.get(application)
    private val salaryHistorySync = SalaryHistorySync.get(application)

    /** История ЗП: цены прошедших месяцев и факт по дням. */
    val salaryLedger: StateFlow<SalaryLedger> = ledgerStore.ledger

    /** Месяц, который сейчас перетягивается из YClients, — для кружка в разборе. */
    private val _syncingMonth = MutableStateFlow<YearMonth?>(null)
    val syncingMonth: StateFlow<YearMonth?> = _syncingMonth.asStateFlow()

    /** Чем кончилась последняя перетяжка месяца: месяц и текст для человека. */
    private val _monthSyncNote = MutableStateFlow<MonthSyncNote?>(null)
    val monthSyncNote: StateFlow<MonthSyncNote?> = _monthSyncNote.asStateFlow()

    /**
     * Все денежные данные лежат под ключом сотрудника (FOUNDATION 8.1).
     * `0` — сотрудник неизвестен, истории нет.
     */
    val salaryStaffId: Long
        get() = YClientsRepository.getInstance(getApplication()).staffId?.toLong() ?: 0L

    val userProfile: StateFlow<UserProfile> = repository.userProfileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CalendarDataStoreProvider.peekProfile(application),
        )

    /**
     * Очередь трансформаций профиля. Буфер с DROP_OLDEST не подходит —
     * нам нельзя терять обновления; используем SUSPEND и большой буфер.
     */
    private var priceUpdateJob: Job? = null

    private val updateChannel = MutableSharedFlow<(UserProfile) -> UserProfile>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    init {
        viewModelScope.launch {
            updateChannel.collect { transform ->
                repository.updateProfile(transform)
            }
        }
        // История нужна статистике при первом же открытии профиля.
        viewModelScope.launch { ledgerStore.warmUp() }
    }

    /**
     * Правка цены месяца из статистики: цена становится ручной навсегда,
     * а расхождение считается разобранным (FOUNDATION 5).
     */
    fun updateMonthPrice(month: YearMonth, price: Double) {
        if (price <= 0.0) return
        val staffId = salaryStaffId
        viewModelScope.launch {
            ledgerStore.update { ledger ->
                val existing = ledger.month(staffId, month)
                val entry = existing?.copy(
                    pricePerSession = price,
                    origin = PriceOrigin.MANUAL,
                    resolved = true,
                ) ?: MonthEntry(
                    staffId = staffId,
                    year = month.year,
                    month = month.monthValue,
                    pricePerSession = price,
                    origin = PriceOrigin.MANUAL,
                    resolved = true,
                )
                ledger.withMonth(entry)
            }
        }
    }

    /**
     * «Считать как в YClients»: месяц возвращается в АВТО, и цену ему снова
     * даёт начисление. Без этого выбор «как в YClients» в разборе расхождения
     * зафиксировал бы сегодняшний факт ручной ценой, и месяц замер бы навсегда.
     */
    fun acceptMonthFact(month: YearMonth) {
        val staffId = salaryStaffId
        viewModelScope.launch {
            ledgerStore.update { ledger ->
                val existing = ledger.month(staffId, month) ?: return@update ledger
                ledger.withMonth(
                    existing.copy(origin = PriceOrigin.AUTO, resolved = true),
                )
            }
        }
    }

    /**
     * Перетянуть один месяц из YClients — кружок синхронизации в разборе месяца.
     *
     * Закрытый месяц синк сам не трогает, поэтому здесь он размораживается: это
     * ровно то, чего человек хочет от кнопки. Ошибка наружу не идёт — при 403 и
     * офлайне месяц просто остаётся с прежними числами (FOUNDATION 3.5).
     */
    fun refreshMonth(month: YearMonth) {
        if (_syncingMonth.value != null) return
        _syncingMonth.value = month
        _monthSyncNote.value = null
        viewModelScope.launch {
            try {
                val result = runCatching { salaryHistorySync.syncSingleMonth(month) }
                    .getOrElse { SalaryPullResult.Failed(null) }
                _monthSyncNote.value = MonthSyncNote(month, describePull(result))
            } finally {
                // Только в finally: иначе отменённая корутина оставит кружок
                // крутиться навсегда, и месяц больше не перетянуть.
                _syncingMonth.value = null
            }
        }
    }

    /** Разморозка месяца: «дотяни начисление снова». Ничего не удаляет (FOUNDATION 4). */
    fun unfreezeMonth(month: YearMonth) {
        val staffId = salaryStaffId
        viewModelScope.launch {
            ledgerStore.update { ledger ->
                val existing = ledger.month(staffId, month) ?: return@update ledger
                ledger.withMonth(existing.copy(frozen = false))
            }
        }
    }

    private fun enqueueUpdate(transform: (UserProfile) -> UserProfile) {
        // tryEmit с большим буфером ~ всегда успешен; suspend-emit на крайний случай.
        val emitted = updateChannel.tryEmit(transform)
        if (!emitted) {
            viewModelScope.launch { updateChannel.emit(transform) }
        }
    }

    fun updateName(name: String) = enqueueUpdate { it.copy(name = name) }

    fun updateActivityType(type: String) = enqueueUpdate { it.copy(activityType = type) }

    fun toggleWorkingDay(day: DayOfWeek) = enqueueUpdate { current ->
        val newDays = if (current.workingDays.contains(day)) {
            current.workingDays - day
        } else {
            current.workingDays + day
        }
        current.copy(workingDays = newDays)
    }

    fun updatePrice(price: Double) {
        priceUpdateJob?.cancel()
        priceUpdateJob = viewModelScope.launch {
            delay(PRICE_UPDATE_DEBOUNCE_MS)
            val current = userProfile.value.pricePerSession
            if (price == current) return@launch
            // Через ту же очередь профиля, что и остальные поля — обходной
            // repository.applySessionPriceChange больше не нужен: RMW внутри
            // dataStore.edit (см. D2) даёт ту же атомарность, что и updateProfile.
            // Тронул поле — цена становится РУЧНОЙ навсегда: приложение больше
            // не перезаписывает её из API, только сообщает о расхождении.
            enqueueUpdate {
                it.copy(pricePerSession = price, sessionPriceOrigin = PriceOrigin.MANUAL)
            }
        }
    }

    private companion object {
        const val PRICE_UPDATE_DEBOUNCE_MS = 600L
    }

    fun updateDiagnosticsPrice(price: Double) = enqueueUpdate {
        it.copy(pricePerDiagnostics = price, diagnosticsPriceOrigin = PriceOrigin.MANUAL)
    }

    fun updateIntensiveChildPrice(price: Double) = enqueueUpdate {
        it.copy(pricePerIntensiveChild = price, intensivePriceOrigin = PriceOrigin.MANUAL)
    }

    fun updateTaxAmount(tax: Double) = enqueueUpdate { it.copy(monthlyTaxAmount = tax) }

    fun updateSalaryAdvanceOnCard(amount: Double) = enqueueUpdate { it.copy(salaryAdvanceOnCard = amount) }

    fun updateSalaryMainOnCard(amount: Double) = enqueueUpdate { it.copy(salaryMainOnCard = amount) }

    fun updateShowAvatar(show: Boolean) = enqueueUpdate { it.copy(showAvatar = show) }

    fun completeRegistration() = enqueueUpdate { it.copy(isRegistered = true) }
}
