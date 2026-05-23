package ru.greemlab.neiro.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.greemlab.neiro.MainActivity
import ru.greemlab.neiro.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Канал и показ push-уведомлений о занятиях.
 */
object SessionNotificationDisplay {

    const val CHANNEL_ID = "neiro_sessions"
    private const val GROUP_KEY = "neiro_sessions_group"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_sessions),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_sessions_desc)
            enableVibration(true)
            lightColor = NeiroNotificationBranding.channelLightColor(context)
        }
        manager.createNotificationChannel(channel)
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

    fun showTodayDigest(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        ensureChannel(context)
        InAppNotificationRecorder.recordTodayDigest(context, sessions)

        val sorted = sessions.sortedBy { it.startTime }
        val title = SessionNotificationTexts.todayDigestTitle(context, sorted.size)
        val lines = sorted.map { SessionNotificationTexts.formatUpcomingLine(it) }
        val bigText = lines.joinToString("\n")

        val notification = baseBuilder(context, title, lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openCalendarIntent(context, LocalDate.now()))
            .build()

        notify(context, NOTIFICATION_ID_TODAY_DIGEST, notification)
    }

    fun showTomorrowDigest(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        ensureChannel(context)
        InAppNotificationRecorder.recordTomorrowDigest(context, sessions)

        val sorted = sessions.sortedBy { it.startTime }
        val title = SessionNotificationTexts.tomorrowDigestTitle(context, sorted.size)
        val lines = sorted.map { SessionNotificationTexts.formatUpcomingLine(it) }
        val bigText = lines.joinToString("\n")
        val tomorrow = sorted.first().date

        val notification = baseBuilder(context, title, lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openCalendarIntent(context, tomorrow))
            .build()

        notify(context, NOTIFICATION_ID_TOMORROW_DIGEST, notification)
    }

    fun showArchiveReminder(context: Context, dates: List<LocalDate>, dayData: Map<LocalDate, List<String>>) {
        if (dates.isEmpty()) return
        ensureChannel(context)
        InAppNotificationRecorder.recordArchiveReminder(context, dates, dayData)

        when (dates.size) {
            1 -> showSingleArchiveReminder(context, dates.first(), dayData)
            else -> showGroupedArchiveReminder(context, dates, dayData)
        }
    }

    private fun showSingleArchiveReminder(
        context: Context,
        date: LocalDate,
        dayData: Map<LocalDate, List<String>>,
    ) {
        val count = PastSessionsArchiveCollector.sessionCount(dayData[date].orEmpty())
        val title = context.getString(R.string.notification_archive_title)
        val content = context.resources.getQuantityString(
            R.plurals.notification_archive_body,
            count,
            count,
        )

        val notification = baseBuilder(context, title, content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openCalendarIntent(context, date))
            .build()

        notify(context, NOTIFICATION_ID_ARCHIVE_REMINDER + date.hashCode(), notification)
    }

    private fun showGroupedArchiveReminder(
        context: Context,
        dates: List<LocalDate>,
        dayData: Map<LocalDate, List<String>>,
    ) {
        val title = context.getString(R.string.notification_archive_group_title, dates.size)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        dates.forEach { date ->
            val count = PastSessionsArchiveCollector.sessionCount(dayData[date].orEmpty())
            inbox.addLine(
                context.resources.getQuantityString(
                    R.plurals.notification_archive_line,
                    count,
                    formatArchiveDateLabel(context, date),
                    count,
                ),
            )
        }

        val firstDate = dates.first()
        val summary = baseBuilder(context, title, context.getString(R.string.notification_archive_group_summary))
            .setStyle(inbox)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openCalendarIntent(context, firstDate))
            .build()

        notify(context, NOTIFICATION_ID_ARCHIVE_REMINDER, summary)
    }

    private fun showSingleEvent(context: Context, event: SessionEvent) {
        val title = SessionNotificationTexts.eventTitle(context, event)
        val content = SessionNotificationTexts.eventContent(context, event)
        val bigText = content

        val notification = baseBuilder(context, title, content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(openCalendarIntent(context, event.session.date))
            .build()

        notify(context, event.dedupeKey.hashCode(), notification)
    }

    private fun showGroupedEvents(context: Context, events: List<SessionEvent>) {
        val title = SessionNotificationTexts.groupedEventsTitle(context, events.size)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        events.forEach { inbox.addLine(SessionNotificationTexts.eventContent(context, it)) }

        val summary = baseBuilder(context, title, SessionNotificationTexts.eventContent(context, events.first()))
            .setStyle(inbox)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openCalendarIntent(context, events.first().session.date))
            .build()

        notify(context, NOTIFICATION_ID_EVENTS_GROUP, summary)

        events.forEach { event ->
            val child = baseBuilder(
                context,
                SessionNotificationTexts.eventTitle(context, event),
                SessionNotificationTexts.eventContent(context, event),
            )
                .setGroup(GROUP_KEY)
                .setContentIntent(openCalendarIntent(context, event.session.date))
                .build()
            notify(context, event.dedupeKey.hashCode(), child)
        }
    }

    private fun showSingleReminder(context: Context, session: UpcomingSession) {
        val minutesUntil = java.time.Duration.between(
            java.time.LocalDateTime.now(),
            session.startsAt(),
        ).toMinutes().coerceAtLeast(0)

        val title = context.getString(R.string.notification_reminder_title, session.clientName)
        val content = SessionNotificationTexts.formatUpcomingLine(session)
        val subText = when {
            minutesUntil <= 1L -> context.getString(R.string.notification_reminder_soon)
            minutesUntil < 60L -> context.getString(R.string.notification_reminder_in_minutes, minutesUntil)
            else -> context.getString(
                R.string.notification_reminder_at,
                session.startTime.format(timeFormatter),
            )
        }

        val notification = baseBuilder(context, title, content)
            .setSubText(subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openCalendarIntent(context, session.date))
            .build()

        notify(context, session.dedupeKey.hashCode(), notification)
    }

    private fun showGroupedReminders(context: Context, sessions: List<UpcomingSession>) {
        val sorted = sessions.sortedBy { it.startTime }
        val title = context.getString(R.string.notification_reminder_group_title, sorted.size)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        sorted.forEach { inbox.addLine(SessionNotificationTexts.formatUpcomingLine(it)) }

        val summary = baseBuilder(context, title, SessionNotificationTexts.formatUpcomingLine(sorted.first()))
            .setStyle(inbox)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openCalendarIntent(context, sorted.first().date))
            .build()

        notify(context, NOTIFICATION_ID_REMINDER_GROUP, summary)

        sorted.forEach { session ->
            val child = baseBuilder(context, session.clientName, SessionNotificationTexts.formatUpcomingLine(session))
                .setGroup(GROUP_KEY)
                .setContentIntent(openCalendarIntent(context, session.date))
                .build()
            notify(context, session.dedupeKey.hashCode(), child)
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

    private fun formatArchiveDateLabel(context: Context, date: LocalDate): String =
        if (date == LocalDate.now()) {
            context.getString(R.string.notification_archive_date_today)
        } else {
            date.format(dateFormatter)
        }

    private fun openCalendarIntent(context: Context, date: LocalDate): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DATE, date.toString())
        }
        return PendingIntent.getActivity(
            context,
            date.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS не выдан.
        }
    }

    private const val NOTIFICATION_ID_TODAY_DIGEST = 10_001
    private const val NOTIFICATION_ID_EVENTS_GROUP = 10_002
    private const val NOTIFICATION_ID_REMINDER_GROUP = 10_003
    private const val NOTIFICATION_ID_TOMORROW_DIGEST = 10_004
    private const val NOTIFICATION_ID_ARCHIVE_REMINDER = 10_005
}
