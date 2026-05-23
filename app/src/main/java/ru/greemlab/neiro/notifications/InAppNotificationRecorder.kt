package ru.greemlab.neiro.notifications

import android.content.Context
import java.time.LocalDate

/**
 * Дублирует показанные push-уведомления в in-app ленту ([InAppNotificationStore]).
 */
object InAppNotificationRecorder {

    fun recordEvent(context: Context, event: SessionEvent) {
        val appContext = context.applicationContext
        InAppNotificationStore.get(appContext).append(
            title = SessionNotificationTexts.eventTitle(appContext, event),
            body = SessionNotificationTexts.eventContent(appContext, event),
            relatedDate = event.session.date,
            dedupeKey = "inapp|${event.dedupeKey}",
        )
    }

    fun recordEvents(context: Context, events: List<SessionEvent>) {
        if (events.isEmpty()) return
        val appContext = context.applicationContext
        if (events.size == 1) {
            recordEvent(appContext, events.first())
            return
        }
        val first = events.first()
        InAppNotificationStore.get(appContext).append(
            title = SessionNotificationTexts.groupedEventsTitle(appContext, events.size),
            body = SessionNotificationTexts.groupedEventsBody(appContext, events),
            relatedDate = first.session.date,
            dedupeKey = "inapp|group|${events.joinToString(",") { it.dedupeKey }}",
        )
    }

    fun recordReminder(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        val appContext = context.applicationContext
        if (sessions.size == 1) {
            val session = sessions.first()
            InAppNotificationStore.get(appContext).append(
                title = SessionNotificationTexts.reminderTitle(appContext, session),
                body = SessionNotificationTexts.formatUpcomingLine(session),
                relatedDate = session.date,
                dedupeKey = "inapp|reminder|${session.dedupeKey}",
            )
            return
        }
        val sorted = sessions.sortedBy { it.startTime }
        InAppNotificationStore.get(appContext).append(
            title = SessionNotificationTexts.groupedRemindersTitle(appContext, sorted.size),
            body = sorted.joinToString("\n") { SessionNotificationTexts.formatUpcomingLine(it) },
            relatedDate = sorted.first().date,
            dedupeKey = "inapp|reminder|group|${sorted.joinToString(",") { it.dedupeKey }}",
        )
    }

    fun recordTodayDigest(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        val appContext = context.applicationContext
        val sorted = sessions.sortedBy { it.startTime }
        val today = LocalDate.now()
        InAppNotificationStore.get(appContext).append(
            title = SessionNotificationTexts.todayDigestTitle(appContext, sorted.size),
            body = SessionNotificationTexts.upcomingDigestBody(sorted),
            relatedDate = today,
            dedupeKey = "inapp|today_digest|${today.toEpochDay()}",
        )
    }

    fun recordTomorrowDigest(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        val appContext = context.applicationContext
        val sorted = sessions.sortedBy { it.startTime }
        val tomorrow = sorted.first().date
        InAppNotificationStore.get(appContext).append(
            title = SessionNotificationTexts.tomorrowDigestTitle(appContext, sorted.size),
            body = SessionNotificationTexts.upcomingDigestBody(sorted),
            relatedDate = tomorrow,
            dedupeKey = "inapp|tomorrow_digest|${tomorrow.toEpochDay()}",
        )
    }

    fun recordArchiveReminder(
        context: Context,
        dates: List<LocalDate>,
        dayData: Map<LocalDate, List<String>>,
    ) {
        if (dates.isEmpty()) return
        val appContext = context.applicationContext
        if (dates.size == 1) {
            val date = dates.first()
            val count = PastSessionsArchiveCollector.sessionCount(dayData[date].orEmpty())
            InAppNotificationStore.get(appContext).append(
                title = SessionNotificationTexts.archiveTitle(appContext),
                body = SessionNotificationTexts.archiveBody(appContext, count),
                relatedDate = date,
                dedupeKey = "inapp|archive|${date.toEpochDay()}",
            )
            return
        }
        InAppNotificationStore.get(appContext).append(
            title = SessionNotificationTexts.groupedArchiveTitle(appContext, dates.size),
            body = SessionNotificationTexts.groupedArchiveBody(appContext, dates, dayData),
            relatedDate = dates.first(),
            dedupeKey = "inapp|archive|group|${dates.joinToString(",") { it.toEpochDay().toString() }}",
        )
    }
}
