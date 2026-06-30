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
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

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

    fun todayDigestTitle(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_today_title, count, count)

    fun tomorrowDigestTitle(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_tomorrow_title, count, count)

    fun upcomingDigestBody(sessions: List<UpcomingSession>): String =
        sessions.sortedBy { it.startTime }.joinToString("\n") { formatUpcomingLine(it) }

    fun reminderTitle(context: Context, session: UpcomingSession): String =
        context.getString(R.string.notification_reminder_title, session.clientName)

    fun archiveTitle(context: Context): String =
        context.getString(R.string.notification_archive_title)

    fun archiveBody(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.notification_archive_body, count, count)

    fun archiveGroupTitle(context: Context, count: Int): String =
        context.getString(R.string.notification_archive_group_title, count)

    fun archiveGroupSummary(context: Context): String =
        context.getString(R.string.notification_archive_group_summary)

    fun archiveLine(context: Context, count: Int, date: LocalDate): String =
        context.resources.getQuantityString(
            R.plurals.notification_archive_line,
            count,
            formatArchiveDateLabel(context, date),
            count,
        )

    fun reminderGroupTitle(context: Context, count: Int): String =
        context.getString(R.string.notification_reminder_group_title, count)

    fun reminderSubText(context: Context, minutesUntil: Long, startTime: java.time.LocalTime): String = when {
        minutesUntil <= 1L -> context.getString(R.string.notification_reminder_soon)
        minutesUntil < 60L -> context.getString(R.string.notification_reminder_in_minutes, minutesUntil)
        else -> context.getString(R.string.notification_reminder_at, startTime.format(timeFormatter))
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
        val statusSuffix = when (session.status) {
            ru.greemlab.neiro.ui.calendar.AttendanceStatus.CANCELLED -> " (Отмена)"
            ru.greemlab.neiro.ui.calendar.AttendanceStatus.CONFIRMED -> " (Подтверждено)"
            else -> ""
        }
        return "$datePrefix$timeRange · ${session.clientName} · $kind$statusSuffix"
    }

    fun formatArchiveDateLabel(context: Context, date: LocalDate): String =
        if (date == LocalDate.now()) {
            context.getString(R.string.notification_archive_date_today)
        } else {
            date.format(dateFormatter)
        }
}
