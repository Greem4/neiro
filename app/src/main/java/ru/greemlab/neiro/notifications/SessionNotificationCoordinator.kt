package ru.greemlab.neiro.notifications

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Планирование и показ уведомлений о занятиях зарегистрированного пользователя.
 */
object SessionNotificationCoordinator {

    const val KEY_DEDUPE = "dedupe_key"

    private const val PERIODIC_WORK_NAME = "session_reminder_check"
    private const val PERIODIC_DAILY_WORK_NAME = "session_daily_notifications"
    private const val WORK_TAG_REMINDER = "session_reminder"
    private const val WORK_PREFIX = "session_reminder_"
    private const val MAX_SCHEDULE_DAYS = 7L

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        SessionNotificationDisplay.ensureChannel(appContext)
        schedulePeriodicCheck(appContext)
        scheduleDailyNotifications(appContext)
    }

    suspend fun onNotificationsToggled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (enabled) {
            schedulePeriodicCheck(appContext)
            scheduleDailyNotifications(appContext)
            refreshFromCalendar(appContext)
        } else {
            cancelAll(appContext)
        }
    }

    suspend fun onSettingsChanged(context: Context) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) {
            cancelAll(appContext)
            return
        }
        schedulePeriodicCheck(appContext)
        scheduleDailyNotifications(appContext)
        refreshFromCalendar(appContext)
    }

    /**
     * После синхронизации YClients: сравнить календарь до/после и уведомить об изменениях.
     */
    suspend fun onSyncCompleted(
        context: Context,
        dayDataBefore: Map<java.time.LocalDate, List<String>>,
        dayDataAfter: Map<java.time.LocalDate, List<String>>,
    ) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) return
        if (!YClientsRepository.getInstance(appContext).isLoggedIn.value) return

        val calendarRepository = CalendarDataStoreProvider.get(appContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) return

        val before = CalendarSessionSnapshot.from(dayDataBefore, profile)
        val after = CalendarSessionSnapshot.from(dayDataAfter, profile)

        processSnapshotTransition(appContext, profile, before, after, prefs)
    }

    suspend fun refreshFromCalendar(context: Context) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) return

        val calendarRepository = CalendarDataStoreProvider.get(appContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) {
            cancelAll(appContext)
            return
        }

        val dayData = calendarRepository.dayDataFlow.first()
        val savedDayData = calendarRepository.savedDayDataFlow.first()
        val upcoming = UpcomingSessionsCollector.collect(dayData, profile)

        if (prefs.notifyReminder) {
            rescheduleReminders(appContext, upcoming, prefs.reminderMinutesBefore)
        } else {
            WorkManager.getInstance(appContext).cancelAllWorkByTag(WORK_TAG_REMINDER)
        }

        runDailyScheduledChecks(
            appContext,
            prefs = prefs,
            dayData = dayData,
            savedDayData = savedDayData,
            profile = profile,
            upcoming = upcoming,
        )
    }

    private suspend fun processSnapshotTransition(
        context: Context,
        profile: UserProfile,
        before: List<TrackedSession>,
        after: List<TrackedSession>,
        prefs: SessionNotificationPreferences,
    ) {
        if (!prefs.hasBaselineSnapshot) {
            prefs.establishBaseline(after)
            scheduleAfterBaseline(context, profile, after, prefs)
            return
        }

        val storedBefore = prefs.loadSnapshot()
        val effectiveBefore = if (storedBefore.isNotEmpty()) storedBefore else before

        val events = SessionChangeDetector.detect(effectiveBefore, after)
            .filter { prefs.isTypeEnabled(it.type) }
            .filter { !prefs.wasEventNotified(it.dedupeKey) }

        if (events.isNotEmpty()) {
            SessionNotificationDisplay.showEvents(context, events)
            events.forEach { prefs.markEventNotified(it.dedupeKey) }
        }

        prefs.saveSnapshot(after)
        scheduleAfterBaseline(context, profile, after, prefs)
    }

    private suspend fun scheduleAfterBaseline(
        context: Context,
        profile: UserProfile,
        after: List<TrackedSession>,
        prefs: SessionNotificationPreferences,
    ) {
        val calendarRepository = CalendarDataStoreProvider.get(context)
        val dayData = calendarRepository.dayDataFlow.first()
        val savedDayData = calendarRepository.savedDayDataFlow.first()
        val upcoming = UpcomingSessionsCollector.collect(dayData, profile)

        if (prefs.notifyReminder) {
            rescheduleReminders(context, upcoming, prefs.reminderMinutesBefore)
        }

        runDailyScheduledChecks(
            context,
            prefs = prefs,
            dayData = dayData,
            savedDayData = savedDayData,
            profile = profile,
            upcoming = upcoming,
        )
    }

    suspend fun runDailyScheduledChecks(context: Context) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) return

        val calendarRepository = CalendarDataStoreProvider.get(appContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) return

        val dayData = calendarRepository.dayDataFlow.first()
        val savedDayData = calendarRepository.savedDayDataFlow.first()
        val upcoming = UpcomingSessionsCollector.collect(dayData, profile)

        runDailyScheduledChecks(
            appContext,
            prefs = prefs,
            dayData = dayData,
            savedDayData = savedDayData,
            profile = profile,
            upcoming = upcoming,
        )
    }

    private fun runDailyScheduledChecks(
        context: Context,
        prefs: SessionNotificationPreferences,
        dayData: Map<LocalDate, List<String>>,
        savedDayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        upcoming: List<UpcomingSession>,
    ) {
        val now = LocalTime.now()

        if (prefs.notifyTodayDigest &&
            SessionDailyNotificationWorker.shouldCheckAt(now, prefs.todayDigestTime)
        ) {
            maybeShowTodayDigest(context, upcoming, prefs)
        }

        if (prefs.notifyTomorrowDigest &&
            SessionDailyNotificationWorker.shouldCheckAt(now, prefs.tomorrowDigestTime)
        ) {
            maybeShowTomorrowDigest(context, upcoming, prefs)
        }

        if (prefs.notifyArchiveReminder &&
            SessionDailyNotificationWorker.shouldCheckAt(now, prefs.archiveReminderTime)
        ) {
            maybeShowArchiveReminder(context, dayData, savedDayData, profile, prefs)
        }
    }

    private fun schedulePeriodicCheck(context: Context) {
        val prefs = SessionNotificationPreferences.get(context)
        if (!prefs.isEnabled || !prefs.notifyReminder) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<SessionReminderWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun scheduleDailyNotifications(context: Context) {
        val prefs = SessionNotificationPreferences.get(context)
        val workManager = WorkManager.getInstance(context)
        if (!prefs.isEnabled ||
            (!prefs.notifyTodayDigest && !prefs.notifyTomorrowDigest && !prefs.notifyArchiveReminder)
        ) {
            workManager.cancelUniqueWork(PERIODIC_DAILY_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<SessionDailyNotificationWorker>(15, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_DAILY_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun rescheduleReminders(
        context: Context,
        sessions: List<UpcomingSession>,
        reminderMinutesBefore: Int,
    ) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(WORK_TAG_REMINDER)

        val zone = ZoneId.systemDefault()
        val now = java.time.Instant.now()

        for (session in sessions) {
            val trigger = session.reminderAt(reminderMinutesBefore, zone).atZone(zone).toInstant()
            val delayMs = Duration.between(now, trigger).toMillis()
            if (delayMs <= 0L || delayMs > TimeUnit.DAYS.toMillis(MAX_SCHEDULE_DAYS)) continue

            val request = OneTimeWorkRequestBuilder<SessionReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG_REMINDER)
                .setInputData(workDataOf(KEY_DEDUPE to session.dedupeKey))
                .build()

            workManager.enqueueUniqueWork(
                WORK_PREFIX + session.dedupeKey,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    private fun maybeShowTodayDigest(
        context: Context,
        upcoming: List<UpcomingSession>,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val todayList = UpcomingSessionsCollector.todaySessions(upcoming, today)
        if (todayList.isEmpty()) return

        val epochDay = today.toEpochDay()
        if (prefs.lastTodayDigestEpochDay() == epochDay) return

        SessionNotificationDisplay.showTodayDigest(context, todayList)
        prefs.markTodayDigestShown(epochDay)
    }

    private fun maybeShowTomorrowDigest(
        context: Context,
        upcoming: List<UpcomingSession>,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val tomorrowList = UpcomingSessionsCollector.tomorrowSessions(upcoming, today)
        if (tomorrowList.isEmpty()) return

        val targetEpochDay = tomorrow.toEpochDay()
        if (prefs.lastTomorrowDigestTargetEpochDay() == targetEpochDay) return

        SessionNotificationDisplay.showTomorrowDigest(context, tomorrowList)
        prefs.markTomorrowDigestShown(targetEpochDay)
    }

    private fun maybeShowArchiveReminder(
        context: Context,
        dayData: Map<LocalDate, List<String>>,
        savedDayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val date = PastSessionsArchiveCollector.todayNeedingArchive(
            dayData = dayData,
            archivedDates = savedDayData.keys,
            profile = profile,
            today = today,
        ) ?: return
        if (prefs.wasArchiveReminderShown(date.toEpochDay())) return

        SessionNotificationDisplay.showArchiveReminder(context, listOf(date), dayData)
        prefs.markArchiveReminderShown(date.toEpochDay())
    }

    private fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(PERIODIC_DAILY_WORK_NAME)
            cancelAllWorkByTag(WORK_TAG_REMINDER)
        }
    }
}
