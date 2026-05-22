package ru.greemlab.neiro.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.notifications.SessionNotificationPreferences

data class SessionNotificationSettingsState(
    val isEnabled: Boolean = true,
    val notifyNewBooking: Boolean = true,
    val notifyCancelled: Boolean = true,
    val notifyRescheduled: Boolean = true,
    val notifyDeleted: Boolean = true,
    val notifyStatusChanged: Boolean = true,
    val notifyReminder: Boolean = true,
    val notifyTodayDigest: Boolean = true,
    val reminderMinutesBefore: Int = 30,
)

class SessionNotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = SessionNotificationPreferences.get(application)

    var state by mutableStateOf(loadState())
        private set

    fun setEnabled(value: Boolean) {
        prefs.isEnabled = value
        state = state.copy(isEnabled = value)
        viewModelScope.launch {
            SessionNotificationCoordinator.onNotificationsToggled(getApplication(), value)
        }
    }

    fun setNotifyNewBooking(value: Boolean) = update({ copy(notifyNewBooking = value) }) {
        prefs.notifyNewBooking = value
    }

    fun setNotifyCancelled(value: Boolean) = update({ copy(notifyCancelled = value) }) {
        prefs.notifyCancelled = value
    }

    fun setNotifyRescheduled(value: Boolean) = update({ copy(notifyRescheduled = value) }) {
        prefs.notifyRescheduled = value
    }

    fun setNotifyDeleted(value: Boolean) = update({ copy(notifyDeleted = value) }) {
        prefs.notifyDeleted = value
    }

    fun setNotifyStatusChanged(value: Boolean) = update({ copy(notifyStatusChanged = value) }) {
        prefs.notifyStatusChanged = value
    }

    fun setNotifyReminder(value: Boolean) = update({ copy(notifyReminder = value) }) {
        prefs.notifyReminder = value
    }

    fun setNotifyTodayDigest(value: Boolean) = update({ copy(notifyTodayDigest = value) }) {
        prefs.notifyTodayDigest = value
    }

    fun setReminderMinutes(minutes: Int) = update({ copy(reminderMinutesBefore = minutes) }) {
        prefs.reminderMinutesBefore = minutes
    }

    private inline fun update(
        stateTransform: SessionNotificationSettingsState.() -> SessionNotificationSettingsState,
        persist: () -> Unit,
    ) {
        persist()
        state = state.stateTransform()
        viewModelScope.launch {
            SessionNotificationCoordinator.onSettingsChanged(getApplication())
        }
    }

    private fun loadState() = SessionNotificationSettingsState(
        isEnabled = prefs.isEnabled,
        notifyNewBooking = prefs.notifyNewBooking,
        notifyCancelled = prefs.notifyCancelled,
        notifyRescheduled = prefs.notifyRescheduled,
        notifyDeleted = prefs.notifyDeleted,
        notifyStatusChanged = prefs.notifyStatusChanged,
        notifyReminder = prefs.notifyReminder,
        notifyTodayDigest = prefs.notifyTodayDigest,
        reminderMinutesBefore = prefs.reminderMinutesBefore,
    )
}
