package ru.greemlab.neiro.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.greemlab.neiro.notifications.ScheduledDigestKind
import ru.greemlab.neiro.notifications.ScheduledNotificationTime
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.notifications.SessionNotificationPreferences

data class SessionNotificationSettingsState(
    val isEnabled: Boolean = true,
    val notifyNewBooking: Boolean = true,
    val notifyCancelled: Boolean = true,
    val notifyRescheduled: Boolean = true,
    val notifyDeleted: Boolean = true,
    val notifyClientConfirmed: Boolean = true,
    val notifyClientArrived: Boolean = true,
    val notifyReminder: Boolean = false,
    val notifyTodayDigest: Boolean = true,
    val notifyTomorrowDigest: Boolean = true,
    val notifyArchiveReminder: Boolean = true,
    val reminderMinutesBefore: Int = 30,
    val todayDigestTime: ScheduledNotificationTime = ScheduledNotificationTime(8, 0),
    val tomorrowDigestTime: ScheduledNotificationTime = ScheduledNotificationTime(20, 0),
    val archiveReminderTime: ScheduledNotificationTime = ScheduledNotificationTime(21, 0),
)

class SessionNotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = SessionNotificationPreferences.get(application)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SessionNotificationSettingsState> = _state.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.isEnabled = value
        _state.update { it.copy(isEnabled = value) }
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

    fun setNotifyClientConfirmed(value: Boolean) = update({ copy(notifyClientConfirmed = value) }) {
        prefs.notifyClientConfirmed = value
    }

    fun setNotifyClientArrived(value: Boolean) = update({ copy(notifyClientArrived = value) }) {
        prefs.notifyClientArrived = value
    }

    fun setNotifyReminder(value: Boolean) = update({ copy(notifyReminder = value) }) {
        prefs.notifyReminder = value
    }

    fun setNotifyTodayDigest(value: Boolean) = update({ copy(notifyTodayDigest = value) }) {
        prefs.notifyTodayDigest = value
    }

    fun setNotifyTomorrowDigest(value: Boolean) = update({ copy(notifyTomorrowDigest = value) }) {
        prefs.notifyTomorrowDigest = value
    }

    fun setNotifyArchiveReminder(value: Boolean) = update({ copy(notifyArchiveReminder = value) }) {
        prefs.notifyArchiveReminder = value
    }

    fun setReminderMinutes(minutes: Int) = update({ copy(reminderMinutesBefore = minutes) }) {
        prefs.reminderMinutesBefore = minutes
    }

    fun setTodayDigestTime(time: ScheduledNotificationTime) {
        prefs.todayDigestTime = time
        prefs.clearTodayDigestShown()
        _state.update { it.copy(todayDigestTime = time) }
        viewModelScope.launch {
            SessionNotificationCoordinator.onDigestTimeChanged(getApplication(), ScheduledDigestKind.TODAY)
        }
    }

    fun setTomorrowDigestTime(time: ScheduledNotificationTime) {
        prefs.tomorrowDigestTime = time
        prefs.clearTomorrowDigestShown()
        _state.update { it.copy(tomorrowDigestTime = time) }
        viewModelScope.launch {
            SessionNotificationCoordinator.onDigestTimeChanged(getApplication(), ScheduledDigestKind.TOMORROW)
        }
    }

    fun setArchiveReminderTime(time: ScheduledNotificationTime) {
        prefs.archiveReminderTime = time
        prefs.clearArchiveReminderShown()
        _state.update { it.copy(archiveReminderTime = time) }
        viewModelScope.launch {
            SessionNotificationCoordinator.onDigestTimeChanged(getApplication(), ScheduledDigestKind.ARCHIVE)
        }
    }

    private inline fun update(
        crossinline stateTransform: SessionNotificationSettingsState.() -> SessionNotificationSettingsState,
        persist: () -> Unit,
    ) {
        persist()
        _state.update { it.stateTransform() }
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
        notifyClientConfirmed = prefs.notifyClientConfirmed,
        notifyClientArrived = prefs.notifyClientArrived,
        notifyReminder = prefs.notifyReminder,
        notifyTodayDigest = prefs.notifyTodayDigest,
        notifyTomorrowDigest = prefs.notifyTomorrowDigest,
        notifyArchiveReminder = prefs.notifyArchiveReminder,
        reminderMinutesBefore = prefs.reminderMinutesBefore,
        todayDigestTime = prefs.todayDigestTime,
        tomorrowDigestTime = prefs.tomorrowDigestTime,
        archiveReminderTime = prefs.archiveReminderTime,
    )
}
