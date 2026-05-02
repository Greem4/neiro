package ru.greemlab.neiro.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStore
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.DayOfWeek

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = CalendarDataStore(application)

    val userProfile: StateFlow<UserProfile?> = dataStore.userProfileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    fun updateName(name: String) {
        viewModelScope.launch {
            val current = userProfile.value ?: UserProfile()
            dataStore.saveUserProfile(current.copy(name = name))
        }
    }

    fun updateActivityType(type: String) {
        viewModelScope.launch {
            val current = userProfile.value ?: UserProfile()
            dataStore.saveUserProfile(current.copy(activityType = type))
        }
    }

    fun toggleWorkingDay(day: DayOfWeek) {
        viewModelScope.launch {
            val current = userProfile.value ?: UserProfile()
            val newDays = if (current.workingDays.contains(day)) {
                current.workingDays - day
            } else {
                current.workingDays + day
            }
            dataStore.saveUserProfile(current.copy(workingDays = newDays))
        }
    }

    fun updatePrice(price: Double) {
        viewModelScope.launch {
            val current = userProfile.value ?: UserProfile()
            dataStore.saveUserProfile(current.copy(pricePerSession = price))
        }
    }

    fun updateTaxAmount(tax: Double) {
        viewModelScope.launch {
            val current = userProfile.value ?: UserProfile()
            dataStore.saveUserProfile(current.copy(monthlyTaxAmount = tax))
        }
    }

    fun completeRegistration() {
        viewModelScope.launch {
            val current = userProfile.value ?: UserProfile()
            dataStore.saveUserProfile(current.copy(isRegistered = true))
        }
    }
}
