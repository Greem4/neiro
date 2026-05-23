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
            highlightSlotKey = event.session.slotKey,
            kind = event.type,
        )
    }

    fun recordEvents(context: Context, events: List<SessionEvent>) {
        events.forEach { recordEvent(context, it) }
    }

    fun recordReminder(context: Context, sessions: List<UpcomingSession>) {
        sessions.forEach { session ->
            val appContext = context.applicationContext
            InAppNotificationStore.get(appContext).append(
                title = SessionNotificationTexts.reminderTitle(appContext, session),
                body = SessionNotificationTexts.formatUpcomingLine(session),
                relatedDate = session.date,
                dedupeKey = "inapp|reminder|${session.dedupeKey}",
                highlightSlotKey = SessionSlotKey.build(session.clientName, session.date, session.startTime),
                kind = SessionEventType.REMINDER,
            )
        }
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
            kind = SessionEventType.TODAY_DIGEST,
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
            kind = SessionEventType.TOMORROW_DIGEST,
        )
    }

    fun recordArchiveReminder(
        context: Context,
        dates: List<LocalDate>,
        dayData: Map<LocalDate, List<String>>,
    ) {
        dates.forEach { date ->
            val appContext = context.applicationContext
            val count = PastSessionsArchiveCollector.sessionCount(dayData[date].orEmpty())
            InAppNotificationStore.get(appContext).append(
                title = SessionNotificationTexts.archiveTitle(appContext),
                body = SessionNotificationTexts.archiveBody(appContext, count),
                relatedDate = date,
                dedupeKey = "inapp|archive|${date.toEpochDay()}",
                kind = SessionEventType.ARCHIVE_REMINDER,
            )
        }
    }
}
