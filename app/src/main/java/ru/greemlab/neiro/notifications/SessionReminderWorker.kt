package ru.greemlab.neiro.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.CalendarDataStoreProvider

/**
 * Показывает напоминание о занятии в запланированное время
 * или при периодической проверке «окна» напоминания.
 */
class SessionReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = SessionNotificationPreferences.get(applicationContext)
        if (!prefs.isEnabled || !prefs.notifyReminder) return Result.success()

        val calendarRepository = CalendarDataStoreProvider.get(applicationContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) return Result.success()

        val dayData = calendarRepository.dayDataFlow.first()
        val upcoming = UpcomingSessionsCollector.collect(dayData, profile)
        if (upcoming.isEmpty()) return Result.success()

        val dedupeFromWork = inputData.getString(SessionNotificationCoordinator.KEY_DEDUPE)

        val toNotify = if (dedupeFromWork != null) {
            upcoming.filter { it.dedupeKey == dedupeFromWork }
        } else {
            UpcomingSessionsCollector.collectDueForReminder(
                upcoming,
                prefs.reminderMinutesBefore,
            )
        }.filter { !prefs.wasReminderNotified(it.dedupeKey) }

        if (toNotify.isEmpty()) return Result.success()

        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            SessionNotificationDisplay.showReminder(applicationContext, toNotify)
            toNotify.forEach { prefs.markReminderNotified(it.dedupeKey) }
        }

        return Result.success()
    }
}
