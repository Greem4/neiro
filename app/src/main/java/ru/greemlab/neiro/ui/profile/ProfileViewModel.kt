package ru.greemlab.neiro.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.DayOfWeek

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = CalendarDataStoreProvider.get(application)

    val userProfile: StateFlow<UserProfile> = dataStore.userProfileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CalendarDataStoreProvider.peekProfile(application),
        )

    private fun updateProfile(transform: (UserProfile) -> UserProfile) {
        viewModelScope.launch {
            dataStore.saveUserProfile(transform(userProfile.value))
        }
    }

    fun updateName(name: String) = updateProfile { it.copy(name = name) }

    fun updateActivityType(type: String) = updateProfile { it.copy(activityType = type) }

    fun toggleWorkingDay(day: DayOfWeek) {
        updateProfile { current ->
            val newDays = if (current.workingDays.contains(day)) {
                current.workingDays - day
            } else {
                current.workingDays + day
            }
            current.copy(workingDays = newDays)
        }
    }

    fun updatePrice(price: Double) = updateProfile { it.copy(pricePerSession = price) }

    fun updateTaxAmount(tax: Double) = updateProfile { it.copy(monthlyTaxAmount = tax) }

    fun completeRegistration() = updateProfile { it.copy(isRegistered = true) }
}
