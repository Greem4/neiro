package ru.greemlab.neiro.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
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

    fun updatePrice(price: Double) = enqueueUpdate { it.copy(pricePerSession = price) }

    fun updateDiagnosticsPrice(price: Double) = enqueueUpdate { it.copy(pricePerDiagnostics = price) }

    fun updateTaxAmount(tax: Double) = enqueueUpdate { it.copy(monthlyTaxAmount = tax) }

    fun completeRegistration() = enqueueUpdate { it.copy(isRegistered = true) }
}
