package ru.greemlab.neiro.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Планирование и показ уведомлений о занятиях зарегистрированного пользователя.
 */
object SessionNotificationCoordinator {

    const val KEY_DEDUPE = "dedupe_key"

    private const val PERIODIC_WORK_NAME = "session_reminder_check"
    private const val PERIODIC_DAILY_WORK_NAME = "session_daily_notifications"
    private const val WORK_TODAY_DIGEST = "session_today_digest"
    private const val WORK_TOMORROW_DIGEST = "session_tomorrow_digest"
    private const val WORK_ARCHIVE_DIGEST = "session_archive_digest"
    private const val WORK_TAG_REMINDER = "session_reminder"
    private const val WORK_PREFIX = "session_reminder_"
    private const val DEDUPE_TAG_PREFIX = "session_reminder_dedupe:"
    private const val MAX_SCHEDULE_DAYS = 7L

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        SessionNotificationDisplay.ensureChannel(appContext)
        // KEEP: старт приложения/boot не должен сбрасывать фазу уже идущего
        // 15-минутного тика — это ослабляет fallback напоминаний (см. N1).
        rescheduleAllWork(appContext, periodicPolicy = ExistingPeriodicWorkPolicy.KEEP)
    }

    suspend fun onNotificationsToggled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (enabled) {
            rescheduleAllWork(appContext, periodicPolicy = ExistingPeriodicWorkPolicy.UPDATE)
            refreshFromCalendar(appContext, forceReminderRefresh = true)
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
        rescheduleAllWork(appContext, periodicPolicy = ExistingPeriodicWorkPolicy.UPDATE)
        // forceReminderRefresh: reminderMinutesBefore мог измениться — старые
        // one-time work с прежней задержкой нужно пересчитать, а не просто оставить.
        refreshFromCalendar(appContext, forceReminderRefresh = true)
    }

    /** Смена времени доставки: перепланирование и немедленная проверка, если новое время «сейчас». */
    suspend fun onDigestTimeChanged(context: Context, kind: ScheduledDigestKind) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) return

        rescheduleDigest(appContext, kind)

        val scheduled = when (kind) {
            ScheduledDigestKind.TODAY -> prefs.todayDigestTime
            ScheduledDigestKind.TOMORROW -> prefs.tomorrowDigestTime
            ScheduledDigestKind.ARCHIVE -> prefs.archiveReminderTime
        }
        if (SessionDailyNotificationWorker.isDueToday(java.time.LocalTime.now(), scheduled)) {
            deliverScheduledDigest(appContext, kind)
        }
    }

    /**
     * После обновления календаря с API (live-опрос или ручная синхронизация):
     * сравнить снимок до/после и уведомить об изменениях.
     *
     * Если [dayDataBefore] == [dayDataAfter] — быстрый выход (без перепланирования WorkManager).
     */
    suspend fun onCalendarUpdatedFromApi(
        context: Context,
        dayDataBefore: Map<LocalDate, List<String>>,
        dayDataAfter: Map<LocalDate, List<String>>,
    ) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) return
        if (!YClientsRepository.getInstance(appContext).isLoggedIn.first()) return

        val calendarRepository = CalendarDataStoreProvider.get(appContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) return

        if (dayDataBefore == dayDataAfter) {
            if (!prefs.hasBaselineSnapshot) {
                prefs.establishBaseline(CalendarSessionSnapshot.from(dayDataAfter, profile))
                scheduleAfterBaseline(appContext, profile, prefs)
            }
            return
        }

        val before = CalendarSessionSnapshot.from(dayDataBefore, profile)
        val after = CalendarSessionSnapshot.from(dayDataAfter, profile)

        processSnapshotTransition(appContext, profile, before, after, prefs)
    }

    /**
     * Debug: прогон [onCalendarUpdatedFromApi] с заданными картами dayData без запроса к API.
     * Перед сравнением фиксирует «до» из [dayDataBefore], сбрасывает dedupe событий.
     */
    suspend fun simulateSyncForDev(
        context: Context,
        dayDataBefore: Map<LocalDate, List<String>>,
        dayDataAfter: Map<LocalDate, List<String>>,
    ) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) return

        val calendarRepository = CalendarDataStoreProvider.get(appContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) return

        val before = CalendarSessionSnapshot.from(dayDataBefore, profile)
        val after = CalendarSessionSnapshot.from(dayDataAfter, profile)

        prefs.clearNotifiedKeys()
        prefs.establishBaseline(before)

        val events = SessionChangeDetector.detect(before, after)
            .filter { prefs.isTypeEnabled(it.type) }
            .filter { !prefs.wasEventNotified(it.dedupeKey) }

        if (events.isNotEmpty()) {
            // In-app лента и dedupe-mark — всегда; showEvents сам решает,
            // показывать ли системный push (permission недоступен — не критично).
            SessionNotificationDisplay.showEvents(appContext, events)
            events.forEach { prefs.markEventNotified(it.dedupeKey) }
        }

        prefs.saveSnapshot(after)
        scheduleAfterBaseline(appContext, profile, prefs)
    }

    suspend fun refreshFromCalendar(context: Context, forceReminderRefresh: Boolean = false) {
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

        applyReminderSchedule(appContext, upcoming, prefs, forceRefresh = forceReminderRefresh)

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
            scheduleAfterBaseline(context, profile, prefs)
            return
        }

        // Diff только по факту этого sync (dayData до/после), не по prefs-snapshot:
        // иначе локальные правки интенсивов выглядят как NEW_BOOKING/DELETED.
        val today = LocalDate.now()
        val effectiveBefore = CalendarSessionSnapshot.withinHorizon(before, today)
        val effectiveAfter = CalendarSessionSnapshot.withinHorizon(after, today)

        if (effectiveBefore == effectiveAfter) {
            prefs.saveSnapshot(effectiveAfter)
            return
        }

        val events = SessionChangeDetector.detect(effectiveBefore, effectiveAfter)
            .filter { prefs.isTypeEnabled(it.type) }
            .filter { !prefs.wasEventNotified(it.dedupeKey) }

        if (events.isNotEmpty()) {
            // In-app лента и dedupe-mark — всегда; showEvents сам решает,
            // показывать ли системный push (permission недоступен — не критично).
            SessionNotificationDisplay.showEvents(context, events)
            events.forEach { prefs.markEventNotified(it.dedupeKey) }
        }

        prefs.saveSnapshot(effectiveAfter)
        scheduleAfterBaseline(context, profile, prefs)
    }

    private suspend fun scheduleAfterBaseline(
        context: Context,
        profile: UserProfile,
        prefs: SessionNotificationPreferences,
    ) {
        val calendarRepository = CalendarDataStoreProvider.get(context)
        val dayData = calendarRepository.dayDataFlow.first()
        val savedDayData = calendarRepository.savedDayDataFlow.first()
        val upcoming = UpcomingSessionsCollector.collect(dayData, profile)

        applyReminderSchedule(context, upcoming, prefs)
        scheduleDailyNotifications(context)
        // Из sync-пути только гарантируем наличие работ (KEEP), без cancel:
        // cancel+enqueue около времени сводки убивал бы уже запущенный digest-worker.
        ensureAllDailyDigestsScheduled(context)

        runDailyScheduledChecks(
            context,
            prefs = prefs,
            dayData = dayData,
            savedDayData = savedDayData,
            profile = profile,
            upcoming = upcoming,
        )
    }

    suspend fun deliverScheduledDigest(context: Context, kind: ScheduledDigestKind) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) return

        val calendarRepository = CalendarDataStoreProvider.get(appContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) return

        val dayData = calendarRepository.dayDataFlow.first()
        val savedDayData = calendarRepository.savedDayDataFlow.first()
        val upcoming = UpcomingSessionsCollector.collect(dayData, profile)

        when (kind) {
            ScheduledDigestKind.TODAY -> if (prefs.notifyTodayDigest) {
                maybeShowTodayDigest(appContext, dayData, profile, prefs)
            }
            ScheduledDigestKind.TOMORROW -> if (prefs.notifyTomorrowDigest) {
                maybeShowTomorrowDigest(appContext, upcoming, prefs)
            }
            ScheduledDigestKind.ARCHIVE -> if (prefs.notifyArchiveReminder) {
                maybeShowArchiveReminder(appContext, dayData, savedDayData, profile, prefs)
            }
        }
    }

    /**
     * Жёсткое перепланирование — только при смене времени/настроек пользователем.
     * Один enqueue с REPLACE (без отдельного cancel): cancel + enqueue — гонка,
     * при которой enqueue мог быть отброшен, пока отменённая работа ещё числится
     * ENQUEUED. Из sync-пути использовать [ensureAllDailyDigestsScheduled].
     */
    fun rescheduleDigest(context: Context, kind: ScheduledDigestKind) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled || !isDigestEnabled(prefs, kind)) {
            cancelDigestWork(appContext, kind)
            return
        }
        enqueueDigestWork(
            appContext,
            digestWorkName(kind),
            digestTime(prefs, kind),
            kind,
            policy = ExistingWorkPolicy.REPLACE,
        )
    }

    /**
     * Self-reschedule из работающего digest-worker'а: без cancel (не убивать себя)
     * и с APPEND_OR_REPLACE — KEEP отбросил бы запрос, пока worker в RUNNING.
     */
    fun rescheduleDigestFromWorker(context: Context, kind: ScheduledDigestKind) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled || !isDigestEnabled(prefs, kind)) return
        enqueueDigestWork(
            appContext,
            digestWorkName(kind),
            digestTime(prefs, kind),
            kind,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    private fun ensureDigestScheduled(context: Context, kind: ScheduledDigestKind) {
        val prefs = SessionNotificationPreferences.get(context)
        if (!prefs.isEnabled || !isDigestEnabled(prefs, kind)) {
            cancelDigestWork(context, kind)
            return
        }
        enqueueDigestWork(context, digestWorkName(kind), digestTime(prefs, kind), kind)
    }

    private fun ensureAllDailyDigestsScheduled(context: Context) {
        ScheduledDigestKind.entries.forEach { ensureDigestScheduled(context, it) }
    }

    private fun isDigestEnabled(
        prefs: SessionNotificationPreferences,
        kind: ScheduledDigestKind,
    ): Boolean = when (kind) {
        ScheduledDigestKind.TODAY -> prefs.notifyTodayDigest
        ScheduledDigestKind.TOMORROW -> prefs.notifyTomorrowDigest
        ScheduledDigestKind.ARCHIVE -> prefs.notifyArchiveReminder
    }

    private fun digestTime(
        prefs: SessionNotificationPreferences,
        kind: ScheduledDigestKind,
    ): ScheduledNotificationTime = when (kind) {
        ScheduledDigestKind.TODAY -> prefs.todayDigestTime
        ScheduledDigestKind.TOMORROW -> prefs.tomorrowDigestTime
        ScheduledDigestKind.ARCHIVE -> prefs.archiveReminderTime
    }

    private fun digestWorkName(kind: ScheduledDigestKind): String = when (kind) {
        ScheduledDigestKind.TODAY -> WORK_TODAY_DIGEST
        ScheduledDigestKind.TOMORROW -> WORK_TOMORROW_DIGEST
        ScheduledDigestKind.ARCHIVE -> WORK_ARCHIVE_DIGEST
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
        val now = java.time.LocalTime.now()

        if (prefs.notifyTodayDigest &&
            SessionDailyNotificationWorker.isDueToday(now, prefs.todayDigestTime)
        ) {
            maybeShowTodayDigest(context, dayData, profile, prefs)
        }

        if (prefs.notifyTomorrowDigest &&
            SessionDailyNotificationWorker.isDueToday(now, prefs.tomorrowDigestTime)
        ) {
            maybeShowTomorrowDigest(context, upcoming, prefs)
        }

        if (prefs.notifyArchiveReminder &&
            SessionDailyNotificationWorker.isDueToday(now, prefs.archiveReminderTime)
        ) {
            maybeShowArchiveReminder(context, dayData, savedDayData, profile, prefs)
        }
    }

    private fun rescheduleAllDailyDigests(context: Context) {
        val prefs = SessionNotificationPreferences.get(context)
        if (!prefs.isEnabled ||
            (!prefs.notifyTodayDigest && !prefs.notifyTomorrowDigest && !prefs.notifyArchiveReminder)
        ) {
            cancelAllDailyDigests(context)
            return
        }

        rescheduleDigest(context, ScheduledDigestKind.TODAY)
        rescheduleDigest(context, ScheduledDigestKind.TOMORROW)
        rescheduleDigest(context, ScheduledDigestKind.ARCHIVE)
    }

    private fun enqueueDigestWork(
        context: Context,
        uniqueName: String,
        scheduled: ScheduledNotificationTime,
        kind: ScheduledDigestKind,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val delayMs = DigestSchedule
            .delayUntilNext(scheduled, ZoneId.systemDefault(), ZonedDateTime.now(ZoneId.systemDefault()))
            .toMillis()
            .coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<SessionScheduledDigestWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(SessionScheduledDigestWorker.KEY_KIND to kind.name))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            policy,
            request,
        )
    }

    private fun cancelDigestWork(context: Context, kind: ScheduledDigestKind) {
        val uniqueName = when (kind) {
            ScheduledDigestKind.TODAY -> WORK_TODAY_DIGEST
            ScheduledDigestKind.TOMORROW -> WORK_TOMORROW_DIGEST
            ScheduledDigestKind.ARCHIVE -> WORK_ARCHIVE_DIGEST
        }
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName)
    }

    private fun cancelAllDailyDigests(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WORK_TODAY_DIGEST)
            cancelUniqueWork(WORK_TOMORROW_DIGEST)
            cancelUniqueWork(WORK_ARCHIVE_DIGEST)
        }
    }

    /** Перепланировать все фоновые задачи по текущим настройкам (независимо друг от друга). */
    private fun rescheduleAllWork(
        context: Context,
        periodicPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    ) {
        val prefs = SessionNotificationPreferences.get(context)
        if (!prefs.isEnabled) {
            cancelAll(context)
            return
        }
        scheduleDailyNotifications(context, periodicPolicy)
        rescheduleAllDailyDigests(context)
    }

    private suspend fun applyReminderSchedule(
        context: Context,
        upcoming: List<UpcomingSession>,
        prefs: SessionNotificationPreferences,
        forceRefresh: Boolean = false,
    ) {
        if (prefs.notifyReminder) {
            rescheduleReminders(context, upcoming, prefs.reminderMinutesBefore, forceRefresh)
        } else {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG_REMINDER)
        }
    }

    /**
     * Единый периодический «notification tick»: напоминания (fallback к one-time),
     * сводки и архив. Один periodic вместо двух — меньше пробуждений и расхода батареи.
     */
    private fun scheduleDailyNotifications(
        context: Context,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    ) {
        val prefs = SessionNotificationPreferences.get(context)
        val workManager = WorkManager.getInstance(context)
        // Легаси-имя старого отдельного periodic-воркера напоминаний.
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        if (!prefs.isEnabled ||
            (
                !prefs.notifyReminder &&
                    !prefs.notifyTodayDigest &&
                    !prefs.notifyTomorrowDigest &&
                    !prefs.notifyArchiveReminder
                )
        ) {
            workManager.cancelUniqueWork(PERIODIC_DAILY_WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<SessionDailyNotificationWorker>(15, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_DAILY_WORK_NAME,
            policy,
            request,
        )
    }

    /** Проверка сводок и архива при открытии приложения (если время уже наступило). */
    suspend fun checkDueDigestsOnAppOpen(context: Context) {
        runDailyScheduledChecks(context)
    }

    /**
     * Показ напоминаний «за N минут»: точечный (по [dedupeKey] из one-time work)
     * или все попавшие в окно (периодический fallback, dedupeKey == null).
     */
    suspend fun runReminderCheck(context: Context, dedupeKey: String? = null) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled || !prefs.notifyReminder) return

        val calendarRepository = CalendarDataStoreProvider.get(appContext)
        val profile = calendarRepository.userProfileFlow.first()
        if (!profile.isRegistered) return

        val dayData = calendarRepository.dayDataFlow.first()
        val upcoming = UpcomingSessionsCollector.collect(dayData, profile)
        if (upcoming.isEmpty()) return

        val toNotify = if (dedupeKey != null) {
            upcoming.filter { it.dedupeKey == dedupeKey }
        } else {
            UpcomingSessionsCollector.collectDueForReminder(upcoming, prefs.reminderMinutesBefore)
        }.filter { !prefs.wasReminderNotified(it.dedupeKey) }

        if (toNotify.isEmpty()) return

        if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            SessionNotificationDisplay.showReminder(appContext, toNotify)
            toNotify.forEach { prefs.markReminderNotified(it.dedupeKey) }
        }
    }

    /**
     * Diff-перепланирование: трогает только dedupe-ключи, которых больше нет
     * среди актуальных сессий (отмена) или которые ещё не запланированы (enqueue).
     * Уже запланированные сессии не трогаем — cancel-all на каждый sync пересоздавал
     * все one-time work и в сочетании со старым skip-ом при delayMs<=0 терял
     * напоминания (см. N1). [forceRefresh] — пересчитать всё (сменился
     * reminderMinutesBefore в настройках, старые delay уже не актуальны).
     */
    private suspend fun rescheduleReminders(
        context: Context,
        sessions: List<UpcomingSession>,
        reminderMinutesBefore: Int,
        forceRefresh: Boolean = false,
    ) {
        val workManager = WorkManager.getInstance(context)

        val desiredKeys = sessions.map { it.dedupeKey }.toSet()
        val scheduledKeys = runCatching { workManager.getWorkInfosByTag(WORK_TAG_REMINDER).await() }
            .getOrDefault(emptyList())
            .filter { !it.state.isFinished }
            .flatMap { it.tags }
            .filter { it.startsWith(DEDUPE_TAG_PREFIX) }
            .map { it.removePrefix(DEDUPE_TAG_PREFIX) }
            .toSet()

        for (staleKey in scheduledKeys - desiredKeys) {
            workManager.cancelUniqueWork(WORK_PREFIX + staleKey)
        }

        val zone = ZoneId.systemDefault()
        val now = java.time.Instant.now()
        val nowDateTime = java.time.LocalDateTime.ofInstant(now, zone)

        for (session in sessions) {
            if (!forceRefresh && session.dedupeKey in scheduledKeys) continue

            val trigger = session.reminderAt(reminderMinutesBefore).atZone(zone).toInstant()
            val rawDelayMs = Duration.between(now, trigger).toMillis()
            if (rawDelayMs > TimeUnit.DAYS.toMillis(MAX_SCHEDULE_DAYS)) continue
            // Расчётный момент напоминания уже прошёл (например, sync случился
            // прямо перед стартом) — если сессия ещё не началась, всё равно
            // ставим one-time work с нулевой задержкой, а не пропускаем её.
            if (rawDelayMs <= 0L && !session.startsAt().isAfter(nowDateTime)) continue
            val delayMs = rawDelayMs.coerceAtLeast(0L)

            val request = OneTimeWorkRequestBuilder<SessionReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG_REMINDER)
                .addTag(DEDUPE_TAG_PREFIX + session.dedupeKey)
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
        dayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        // includeStartedToday: сводка «сегодня» должна включать уже идущие/прошедшие
        // слоты дня, если digest доставлен с опозданием (Doze) — не только будущие.
        val allToday = UpcomingSessionsCollector.collect(dayData, profile, includeStartedToday = true)
        val todayList = UpcomingSessionsCollector.todaySessions(allToday, today)
        if (todayList.isEmpty()) return

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (!prefs.claimTodayDigest(today.toEpochDay())) return

        if (!SessionNotificationDisplay.showTodayDigest(context, todayList)) {
            // Показ не удался — откатываем claim, иначе сводка потеряна на весь день.
            prefs.clearTodayDigestShown()
        }
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

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (!prefs.claimTomorrowDigest(tomorrow.toEpochDay())) return

        if (!SessionNotificationDisplay.showTomorrowDigest(context, tomorrowList)) {
            prefs.clearTomorrowDigestShown()
        }
    }

    /**
     * Вечернее напоминание об архиве: сегодняшний день + забытые прошлые дни
     * (например, сегодня 17-е, а 14-е так и не перенесено). Каждая дата
     * напоминается один раз (claim); в UI забытые дни дальше видны бейджем
     * на вкладке «Архив».
     */
    private fun maybeShowArchiveReminder(
        context: Context,
        dayData: Map<LocalDate, List<String>>,
        savedDayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val pastDates = PastSessionsArchiveCollector.daysNeedingArchive(
            dayData = dayData,
            archivedDates = savedDayData.keys,
            profile = profile,
            today = today,
        )
        val todayDate = PastSessionsArchiveCollector.todayNeedingArchive(
            dayData = dayData,
            archivedDates = savedDayData.keys,
            profile = profile,
            today = today,
        )
        val candidates = pastDates + listOfNotNull(todayDate)
        if (candidates.isEmpty()) return

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val claimed = candidates.filter { prefs.claimArchiveReminder(it.toEpochDay()) }
        if (claimed.isEmpty()) return

        if (!SessionNotificationDisplay.showArchiveReminder(context, claimed, dayData)) {
            claimed.forEach { prefs.clearArchiveReminderShown(it.toEpochDay()) }
        }
    }

    suspend fun onLoggedOut(context: Context) {
        val appContext = context.applicationContext
        cancelAll(appContext)
        SessionNotificationPreferences.get(appContext).resetSyncNotificationState()
        SessionNotificationPreferences.get(appContext).clearTodayDigestShown()
        SessionNotificationPreferences.get(appContext).clearTomorrowDigestShown()
        SessionNotificationPreferences.get(appContext).clearAllArchiveReminders()
    }

    private fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelUniqueWork(PERIODIC_DAILY_WORK_NAME)
            cancelAllDailyDigests(context)
            cancelAllWorkByTag(WORK_TAG_REMINDER)
        }
    }
}
