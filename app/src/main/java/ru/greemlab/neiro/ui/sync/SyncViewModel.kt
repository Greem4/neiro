package ru.greemlab.neiro.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import ru.greemlab.neiro.data.network.ApiResult
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.auth.LogoutCoordinator
import ru.greemlab.neiro.sync.AutoSyncCoordinator
import ru.greemlab.neiro.sync.SyncOutcome
import ru.greemlab.neiro.sync.SyncPreferences
import ru.greemlab.neiro.sync.YClientsCalendarSync
import java.time.LocalDate
import java.time.YearMonth

/**
 * Состояние синхронизации.
 */
data class SyncUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val syncedCount: Int = 0,
    val lastSyncDate: LocalDate? = null,
    val showSuccess: Boolean = false,
    val profileReviewReminder: String? = null,
    val openProfileSettings: Boolean = false,
)

/**
 * ViewModel для синхронизации данных из YClients.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val yclientsRepository = YClientsRepository.getInstance(application)
    private val calendarRepository: CalendarRepository =
        CalendarDataStoreProvider.get(application)
    private val calendarSync = YClientsCalendarSync.get(application)
    private val syncPreferences = SyncPreferences.get(application)

    private val _uiState = MutableStateFlow(
        SyncUiState(lastSyncDate = syncPreferences.lastSyncLocalDate()),
    )
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = yclientsRepository.isLoggedIn
    val userAvatarUrl: StateFlow<String?> = yclientsRepository.userAvatarUrl

    val isAutoSyncEnabled: Boolean
        get() = syncPreferences.isAutoSyncEnabled

    fun setAutoSyncEnabled(enabled: Boolean) {
        syncPreferences.isAutoSyncEnabled = enabled
        AutoSyncCoordinator.onAutoSyncToggled(getApplication(), enabled)
    }

    val yclientsUserName: String? get() = yclientsRepository.userName

    fun logoutYClients() {
        viewModelScope.launch {
            LogoutCoordinator.logout(getApplication())
            _uiState.value = SyncUiState()
        }
    }

    fun devLogin(autoSync: Boolean = false) {
        val login = BuildConfig.DEV_LOGIN
        val pass = BuildConfig.DEV_PASSWORD
        if (login.isBlank() || pass.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "DEV_LOGIN/PASS не заданы в local.properties")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = yclientsRepository.login(login, pass)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, showSuccess = true)
                    AutoSyncCoordinator.cancelLegacyPeriodicSync(getApplication())
                    if (autoSync) {
                        devSyncAll()
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun devFullSetup() {
        viewModelScope.launch {
            calendarRepository.clearAllData()
            LogoutCoordinator.logout(getApplication())

            calendarRepository.updateProfile { profile ->
                profile.copy(
                    pricePerSession = 1400.0,
                    pricePerDiagnostics = 2250.0,
                    monthlyTaxAmount = 6500.0,
                )
            }

            devLogin(autoSync = true)
        }
    }

    fun devResetData() {
        viewModelScope.launch {
            calendarRepository.clearAllData()
            logoutYClients()
        }
    }

    fun devSyncAll() {
        syncAllThroughCurrentMonth()
    }

    /** Только текущий месяц (экран календаря). */
    fun syncCurrentMonth() {
        syncMonth(YearMonth.now())
    }

    /**
     * После входа в YClients: один раз полная история, дальше — только узкий авто-диапазон.
     */
    fun syncAfterLogin() {
        if (syncPreferences.hasCompletedInitialFullSync) {
            syncDailyEdgeMonths(showUi = false)
        } else {
            syncAllThroughCurrentMonth()
        }
    }

    /** Текущий + следующий месяц (ежедневный авто и после повторного входа). */
    fun syncDailyEdgeMonths(showUi: Boolean = true) {
        viewModelScope.launch {
            runSync(showUi = showUi) {
                calendarSync.syncDefaultAutoRange()
            }
        }
    }

    /**
     * Полная синхронизация из профиля: вся история до конца текущего месяца (вручную).
     */
    fun syncAllThroughCurrentMonth() {
        viewModelScope.launch {
            val isFirstFullSync = !syncPreferences.hasCompletedInitialFullSync
            val outcome = runSync(showUi = true) {
                val outcome = runFullHistorySync()
                if (outcome is SyncOutcome.Success) {
                    syncPreferences.markInitialFullSyncComplete()
                }
                outcome
            }
            if (isFirstFullSync && outcome is SyncOutcome.Success) {
                maybeShowProfileReviewReminder()
            }
        }
    }

    private suspend fun runFullHistorySync(): SyncOutcome {
        val end = YearMonth.now().atEndOfMonth()
        val start = resolveFullSyncStartDate()
        return calendarSync.syncDateRange(start, end)
    }

    private suspend fun resolveFullSyncStartDate(): LocalDate {
        val defaultStart = YearMonth.now()
            .minusMonths(PROFILE_FULL_SYNC_MONTHS.toLong())
            .atDay(1)
        val earliestLocal = calendarRepository.dayDataFlow.first().keys.minOrNull()
        return if (earliestLocal != null) {
            minOf(earliestLocal.withDayOfMonth(1), defaultStart)
        } else {
            defaultStart
        }
    }

    fun syncMonth(yearMonth: YearMonth) {
        viewModelScope.launch {
            runSync(showUi = true) {
                calendarSync.syncMonth(yearMonth)
            }
        }
    }

    fun syncDateRange(startDate: LocalDate, endDate: LocalDate) {
        viewModelScope.launch {
            runSync(showUi = true) {
                calendarSync.syncDateRange(startDate, endDate)
            }
        }
    }

    private val syncMutex = Mutex()

    private suspend fun runSync(showUi: Boolean, block: suspend () -> SyncOutcome): SyncOutcome {
        // Если sync уже идёт — НЕ блокируем UI новой spinner-сменой, тихо ждём результат.
        // Можно сделать `tryLock`, тогда повторный вызов мгновенно вернёт прошлый outcome —
        // но в реальном UI пользователь ожидает завершения текущей операции.
        return syncMutex.withLock {
            if (showUi) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    showSuccess = false,
                )
            }

            val outcome = block()
            when (outcome) {
                is SyncOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        syncedCount = outcome.newlyAdded,
                        lastSyncDate = syncPreferences.lastSyncLocalDate() ?: LocalDate.now(),
                        showSuccess = showUi,
                    )
                }

                is SyncOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = outcome.message,
                    )
                }
            }
            outcome
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(showSuccess = false)
    }

    fun clearProfileReviewReminder() {
        _uiState.value = _uiState.value.copy(
            profileReviewReminder = null,
            openProfileSettings = false,
        )
    }

    /** Обновляет дату последней синхронизации в UI после тихой автосинхронизации. */
    fun refreshLastSyncFromPrefs() {
        _uiState.value = _uiState.value.copy(
            lastSyncDate = syncPreferences.lastSyncLocalDate(),
        )
    }

    private suspend fun maybeShowProfileReviewReminder() {
        val profile = calendarRepository.userProfileFlow.first()
        val hasPriceIssue = profile.pricePerSession <= 0.0
        val hasTaxIssue = profile.monthlyTaxAmount <= 0.0
        if (!hasPriceIssue && !hasTaxIssue) return

        val reminder = buildString {
            append("Проверьте профиль после синхронизации: ")
            val missingParts = buildList {
                if (hasPriceIssue) add("цену за занятие")
                if (hasTaxIssue) add("налог")
            }
            append(missingParts.joinToString(" и "))
            append(".")
        }
        _uiState.value = _uiState.value.copy(
            profileReviewReminder = reminder,
            openProfileSettings = true,
        )
    }

    companion object {
        /** Глубина истории при ручной синхронизации из профиля (месяцев назад). */
        private const val PROFILE_FULL_SYNC_MONTHS = 36
    }
}
