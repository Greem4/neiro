package ru.greemlab.neiro.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.DayOfWeek

/**
 * ViewModel для управления профилем пользователя.
 *
 * Все апдейты идут через одну очередь [updateChannel] — это гарантирует, что
 * параллельные изменения (например, быстрый ввод в нескольких полях) применяются
 * последовательно и не теряют данные.
 */
class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CalendarRepository = CalendarDataStoreProvider.get(application)

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
