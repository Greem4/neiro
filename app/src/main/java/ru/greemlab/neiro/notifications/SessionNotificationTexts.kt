package ru.greemlab.neiro.notifications

import android.content.Context
import ru.greemlab.neiro.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Тексты push- и in-app уведомлений о занятиях (общий источник формулировок).
 */
object SessionNotificationTexts {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

    fun eventTitle(context: Context, event: SessionEvent): String = when (event.type) {
        SessionEventType.NEW_BOOKING ->
            context.getString(R.string.notification_event_new_title, event.session.clientName)
        SessionEventType.CANCELLED ->
            context.getString(R.string.notification_event_cancelled_title, event.session.clientName)
        SessionEventType.RESCHEDULED ->
            context.getString(R.string.notification_event_rescheduled_title, event.session.clientName)
        SessionEventType.DELETED ->
            context.getString(R.string.notification_event_deleted_title, event.session.clientName)
        SessionEventType.CLIENT_CONFIRMED ->
            context.getString(R.string.notification_event_confirmed_title, event.session.clientName)
        SessionEventType.CLIENT_ARRIVED ->
            context.getString(R.string.notification_event_arrived_title, event.session.clientName)
        else -> event.session.clientName
    }

    fun eventContent(context: Context, event: SessionEvent): String = when (event.type) {
        SessionEventType.NEW_BOOKING -> event.session.formatLine()
        SessionEventType.CANCELLED -> event.session.formatLine()
        SessionEventType.DELETED -> event.session.formatLine()
        SessionEventType.RESCHEDULED -> {
            val prev = event.previous ?: return event.session.formatLine()
            context.getString(
                R.string.notification_event_rescheduled_body,
                prev.formatLine(),
                event.session.formatLine(),
            )
        }
        SessionEventType.CLIENT_CONFIRMED ->
            context.getString(R.string.notification_event_confirmed_body, event.session.formatLine())
        SessionEventType.CLIENT_ARRIVED ->
            context.getString(R.string.notification_event_arrived_body, event.session.formatLine())
        else -> event.session.formatLine()
    }

    fun groupedEventsTitle(context: Context, count: Int): String =
        context.getString(R.string.notification_events_group_title, count)

    fun groupedEventsBody(context: Context, events: List<SessionEvent>): String =
        events.joinToString("\n") { eventContent(context, it) }

    fun todayDigestTitle(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_today_title, count, count)

    fun tomorrowDigestTitle(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_tomorrow_title, count, count)

    fun upcomingDigestBody(sessions: List<UpcomingSession>): String =
        sessions.sortedBy { it.startTime }.joinToString("\n") { formatUpcomingLine(it) }

    fun reminderTitle(context: Context, session: UpcomingSession): String =
        context.getString(R.string.notification_reminder_title, session.clientName)

    fun groupedRemindersTitle(context: Context, count: Int): String =
        context.getString(R.string.notification_reminder_group_title, count)

    fun archiveTitle(context: Context): String =
        context.getString(R.string.notification_archive_title)

    fun archiveBody(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_archive_body, count, count)

    fun groupedArchiveTitle(context: Context, days: Int): String =
        context.getString(R.string.notification_archive_group_title, days)

    fun groupedArchiveBody(context: Context, dates: List<LocalDate>, dayData: Map<LocalDate, List<String>>): String =
        dates.joinToString("\n") { date ->
            val count = PastSessionsArchiveCollector.sessionCount(dayData[date].orEmpty())
            context.resources.getQuantityString(
                R.plurals.notification_archive_line,
                count,
                formatArchiveDateLabel(context, date),
                count,
            )
        }

    fun formatUpcomingLine(session: UpcomingSession): String {
        val timeRange = "${session.startTime.format(timeFormatter)}–${session.endTime.format(timeFormatter)}"
        val kind = when (session.kind) {
            UpcomingSessionKind.LESSON -> "Занятие"
            UpcomingSessionKind.DIAGNOSTICS -> "Диагностика"
        }
        val datePrefix = if (session.date != LocalDate.now()) {
            "${session.date.format(dateFormatter)}, "
        } else {
            ""
        }
        return "$datePrefix$timeRange · ${session.clientName} · $kind"
    }

    private fun formatArchiveDateLabel(context: Context, date: LocalDate): String =
        if (date == LocalDate.now()) {
            context.getString(R.string.notification_archive_date_today)
        } else {
            date.format(dateFormatter)
        }
}
