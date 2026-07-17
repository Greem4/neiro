package ru.greemlab.neiro.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.greemlab.neiro.MainActivity
import ru.greemlab.neiro.R
import java.time.LocalDate

/**
 * Канал и показ push-уведомлений о занятиях.
 */
object SessionNotificationDisplay {

    const val CHANNEL_ID = "neiro_sessions"

    // Раздельные group key: digest-summary в общей группе с events давал
    // странную группировку в шторке (E2).
    private const val GROUP_KEY_EVENTS = "neiro_sessions_events"
    private const val GROUP_KEY_REMINDERS = "neiro_sessions_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        try {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_sessions),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.notification_channel_sessions_desc)
                enableVibration(true)
                lightColor = NeiroNotificationBranding.channelLightColor(appContext)
            }
            manager.createNotificationChannel(channel)
        } catch (_: SecurityException) {
            // Игнорируем ошибки AppOps/UID при создании канала на некоторых устройствах.
        }
    }

    fun showEvents(context: Context, events: List<SessionEvent>) {
        if (events.isEmpty()) return
        ensureChannel(context)
        InAppNotificationRecorder.recordEvents(context, events)

        if (events.size == 1) {
            showSingleEvent(context, events.first())
        } else {
            showGroupedEvents(context, events)
        }
    }

    fun showReminder(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        ensureChannel(context)
        InAppNotificationRecorder.recordReminder(context, sessions)

        when (sessions.size) {
            1 -> showSingleReminder(context, sessions.first())
            else -> showGroupedReminders(context, sessions)
        }
    }

    /** @return false, если система не приняла уведомление — вызывающий откатывает claim. */
    fun showTodayDigest(context: Context, sessions: List<UpcomingSession>): Boolean {
        if (sessions.isEmpty()) return false
        ensureChannel(context)
        InAppNotificationRecorder.recordTodayDigest(context, sessions)

        val sorted = sessions.sortedBy { it.startTime }
        val title = SessionNotificationTexts.todayDigestTitle(context, sorted.size)
        val bigText = SessionNotificationTexts.upcomingDigestBody(sorted)
        val content = SessionNotificationTexts.formatUpcomingLine(sorted.first())

        val notification = baseBuilder(context, title, content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openCalendarIntent(context, LocalDate.now()))
            .build()

        return notify(context, NOTIFICATION_ID_TODAY_DIGEST, notification)
    }

    /** @return false, если система не приняла уведомление — вызывающий откатывает claim. */
    fun showTomorrowDigest(context: Context, sessions: List<UpcomingSession>): Boolean {
        if (sessions.isEmpty()) return false
        ensureChannel(context)
        InAppNotificationRecorder.recordTomorrowDigest(context, sessions)

        val sorted = sessions.sortedBy { it.startTime }
        val title = SessionNotificationTexts.tomorrowDigestTitle(context, sorted.size)
        val bigText = SessionNotificationTexts.upcomingDigestBody(sorted)
        val content = SessionNotificationTexts.formatUpcomingLine(sorted.first())
        val tomorrow = sorted.first().date

        val notification = baseBuilder(context, title, content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openCalendarIntent(context, tomorrow))
            .build()

        return notify(context, NOTIFICATION_ID_TOMORROW_DIGEST, notification)
    }

    /** @return false, если система не приняла уведомление — вызывающий откатывает claim. */
    fun showArchiveReminder(
        context: Context,
        dates: List<LocalDate>,
        dayData: Map<LocalDate, List<String>>,
    ): Boolean {
        if (dates.isEmpty()) return false
        ensureChannel(context)
        InAppNotificationRecorder.recordArchiveReminder(context, dates, dayData)

        return when (dates.size) {
            1 -> showSingleArchiveReminder(context, dates.first(), dayData)
            else -> showGroupedArchiveReminder(context, dates, dayData)
        }
    }

    private fun showSingleArchiveReminder(
        context: Context,
        date: LocalDate,
        dayData: Map<LocalDate, List<String>>,
    ): Boolean {
        val count = PastSessionsArchiveCollector.sessionCount(dayData[date].orEmpty())
        val title = SessionNotificationTexts.archiveTitle(context)
        val content = SessionNotificationTexts.archiveBody(context, count)

        val notification = baseBuilder(context, title, content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openCalendarIntent(context, date))
            .build()

        return notify(context, dynamicNotificationId("archive|$date"), notification)
    }

    private fun showGroupedArchiveReminder(
        context: Context,
        dates: List<LocalDate>,
        dayData: Map<LocalDate, List<String>>,
    ): Boolean {
        val title = SessionNotificationTexts.archiveGroupTitle(context, dates.size)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        dates.forEach { date ->
            val count = PastSessionsArchiveCollector.sessionCount(dayData[date].orEmpty())
            inbox.addLine(SessionNotificationTexts.archiveLine(context, count, date))
        }

        val firstDate = dates.first()
        val summary = baseBuilder(context, title, SessionNotificationTexts.archiveGroupSummary(context))
            .setStyle(inbox)
            .setContentIntent(openCalendarIntent(context, firstDate))
            .build()

        return notify(context, NOTIFICATION_ID_ARCHIVE_REMINDER, summary)
    }

    private fun showSingleEvent(context: Context, event: SessionEvent) {
        val title = SessionNotificationTexts.eventTitle(context, event)
        val content = SessionNotificationTexts.eventContent(context, event)

        val notification = baseBuilder(context, title, content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openCalendarIntent(context, event.session.date, event.session.slotKey))
            .build()

        notify(context, dynamicNotificationId(event.dedupeKey), notification)
    }

    private fun showGroupedEvents(context: Context, events: List<SessionEvent>) {
        val title = SessionNotificationTexts.groupedEventsTitle(context, events.size)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        events.forEach { inbox.addLine(SessionNotificationTexts.eventContent(context, it)) }

        val summary = baseBuilder(context, title, SessionNotificationTexts.eventContent(context, events.first()))
            .setStyle(inbox)
            .setGroup(GROUP_KEY_EVENTS)
            .setGroupSummary(true)
            .setContentIntent(openCalendarIntent(context, events.first().session.date, events.first().session.slotKey))
            .build()

        notify(context, NOTIFICATION_ID_EVENTS_GROUP, summary)

        events.forEach { event ->
            val child = baseBuilder(
                context,
                SessionNotificationTexts.eventTitle(context, event),
                SessionNotificationTexts.eventContent(context, event),
            )
                .setGroup(GROUP_KEY_EVENTS)
                .setContentIntent(openCalendarIntent(context, event.session.date, event.session.slotKey))
                .build()
            notify(context, dynamicNotificationId(event.dedupeKey), child)
        }
    }

    private fun showSingleReminder(context: Context, session: UpcomingSession) {
        val minutesUntil = java.time.Duration.between(
            java.time.LocalDateTime.now(),
            session.startsAt(),
        ).toMinutes().coerceAtLeast(0)

        val title = SessionNotificationTexts.reminderTitle(context, session)
        val content = SessionNotificationTexts.formatUpcomingLine(session)
        val subText = SessionNotificationTexts.reminderSubText(context, minutesUntil, session.startTime)

        val notification = baseBuilder(context, title, content)
            .setSubText(subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(
                openCalendarIntent(
                    context,
                    session.date,
                    SessionSlotKey.build(session.clientName, session.date, session.startTime),
                ),
            )
            .build()

        notify(context, dynamicNotificationId(session.dedupeKey), notification)
    }

    private fun showGroupedReminders(context: Context, sessions: List<UpcomingSession>) {
        val sorted = sessions.sortedBy { it.startTime }
        val title = SessionNotificationTexts.reminderGroupTitle(context, sorted.size)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        sorted.forEach { inbox.addLine(SessionNotificationTexts.formatUpcomingLine(it)) }

        val summary = baseBuilder(context, title, SessionNotificationTexts.formatUpcomingLine(sorted.first()))
            .setStyle(inbox)
            .setGroup(GROUP_KEY_REMINDERS)
            .setGroupSummary(true)
            .setContentIntent(openCalendarIntent(context, sorted.first().date))
            .build()

        notify(context, NOTIFICATION_ID_REMINDER_GROUP, summary)

        sorted.forEach { session ->
            val child = baseBuilder(context, session.clientName, SessionNotificationTexts.formatUpcomingLine(session))
                .setGroup(GROUP_KEY_REMINDERS)
                .setContentIntent(
                    openCalendarIntent(
                        context,
                        session.date,
                        SessionSlotKey.build(session.clientName, session.date, session.startTime),
                    ),
                )
                .build()
            notify(context, dynamicNotificationId(session.dedupeKey), child)
        }
    }

    private fun baseBuilder(context: Context, title: String, content: String): NotificationCompat.Builder =
        NeiroNotificationBranding.apply(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true),
            context,
        )

    private fun openCalendarIntent(
        context: Context,
        date: LocalDate,
        highlightSlotKey: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DATE, date.toString())
            highlightSlotKey?.let { putExtra(MainActivity.EXTRA_HIGHLIGHT_SLOT_KEY, it) }
        }
        val requestCode = stableHash("${date}|${highlightSlotKey.orEmpty()}")
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stableHash(value: String): Int = value.hashCode() and 0x7fffffff

    /**
     * Динамические id живут в выделенном диапазоне [NOTIFICATION_ID_DYNAMIC_BASE, ...) —
     * не пересекаются с фиксированными 10_001–10_005 и всегда неотрицательны (E1).
     */
    private fun dynamicNotificationId(key: String): Int =
        NOTIFICATION_ID_DYNAMIC_BASE + stableHash(key) % NOTIFICATION_ID_DYNAMIC_RANGE

    /** @return false, если система не приняла уведомление (нет разрешения / AppOps). */
    private fun notify(context: Context, id: Int, notification: android.app.Notification): Boolean =
        try {
            NotificationManagerCompat.from(context.applicationContext).notify(id, notification)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS не выдан или ошибка AppOps (uid -1).
            false
        }

    private const val NOTIFICATION_ID_TODAY_DIGEST = 10_001
    private const val NOTIFICATION_ID_EVENTS_GROUP = 10_002
    private const val NOTIFICATION_ID_REMINDER_GROUP = 10_003
    private const val NOTIFICATION_ID_TOMORROW_DIGEST = 10_004
    private const val NOTIFICATION_ID_ARCHIVE_REMINDER = 10_005
    private const val NOTIFICATION_ID_DYNAMIC_BASE = 20_000
    private const val NOTIFICATION_ID_DYNAMIC_RANGE = 100_000_000
}
