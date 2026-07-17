package ru.greemlab.neiro.notifications

import android.content.Context
import java.time.LocalDate

/**
 * Дублирует push в активную ленту YClients ([InAppNotificationStore])
 * и в постоянный журнал архива ([ArchiveNotificationStore]).
 */
object InAppNotificationRecorder {

    private data class Payload(
        val title: String,
        val body: String,
        val relatedDate: LocalDate? = null,
        val dedupeKey: String? = null,
        val highlightSlotKey: String? = null,
        val kind: SessionEventType? = null,
    )

    private fun record(context: Context, payload: Payload) {
        val appContext = context.applicationContext
        val active = InAppNotificationStore.get(appContext)
        val archive = ArchiveNotificationStore.get(appContext)
        active.append(
            title = payload.title,
            body = payload.body,
            relatedDate = payload.relatedDate,
            dedupeKey = payload.dedupeKey,
            highlightSlotKey = payload.highlightSlotKey,
            kind = payload.kind,
        )
        archive.append(
            title = payload.title,
            body = payload.body,
            relatedDate = payload.relatedDate,
            dedupeKey = payload.dedupeKey?.let { "archive|$it" },
            highlightSlotKey = payload.highlightSlotKey,
            kind = payload.kind,
        )
    }

    fun recordEvent(context: Context, event: SessionEvent) {
        val appContext = context.applicationContext
        record(
            context,
            Payload(
                title = SessionNotificationTexts.eventTitle(appContext, event),
                body = SessionNotificationTexts.eventContent(appContext, event),
                relatedDate = event.session.date,
                dedupeKey = "inapp|${event.dedupeKey}",
                highlightSlotKey = event.session.slotKey,
                kind = event.type,
            ),
        )
    }

    fun recordEvents(context: Context, events: List<SessionEvent>) {
        events.forEach { recordEvent(context, it) }
    }

    fun recordReminder(context: Context, sessions: List<UpcomingSession>) {
        sessions.forEach { session ->
            val appContext = context.applicationContext
            record(
                context,
                Payload(
                    title = SessionNotificationTexts.reminderTitle(appContext, session),
                    body = SessionNotificationTexts.formatUpcomingLine(session),
                    relatedDate = session.date,
                    dedupeKey = "inapp|reminder|${session.dedupeKey}",
                    highlightSlotKey = SessionSlotKey.build(session.clientName, session.date, session.startTime),
                    kind = SessionEventType.REMINDER,
                ),
            )
        }
    }

    fun recordTodayDigest(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        val appContext = context.applicationContext
        val sorted = sessions.sortedBy { it.startTime }
        val today = LocalDate.now()
        record(
            context,
            Payload(
                title = SessionNotificationTexts.todayDigestTitle(appContext, sorted.size),
                body = SessionNotificationTexts.upcomingDigestBody(sorted),
                relatedDate = today,
                dedupeKey = "inapp|today_digest|${today.toEpochDay()}",
                kind = SessionEventType.TODAY_DIGEST,
            ),
        )
    }

    fun recordTomorrowDigest(context: Context, sessions: List<UpcomingSession>) {
        if (sessions.isEmpty()) return
        val appContext = context.applicationContext
        val sorted = sessions.sortedBy { it.startTime }
        val tomorrow = sorted.first().date
        record(
            context,
            Payload(
                title = SessionNotificationTexts.tomorrowDigestTitle(appContext, sorted.size),
                body = SessionNotificationTexts.upcomingDigestBody(sorted),
                relatedDate = tomorrow,
                dedupeKey = "inapp|tomorrow_digest|${tomorrow.toEpochDay()}",
                kind = SessionEventType.TOMORROW_DIGEST,
            ),
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
            record(
                context,
                Payload(
                    title = SessionNotificationTexts.archiveTitleForDate(appContext, date),
                    body = SessionNotificationTexts.archiveBody(appContext, count),
                    relatedDate = date,
                    dedupeKey = "inapp|archive|${date.toEpochDay()}",
                    kind = SessionEventType.ARCHIVE_REMINDER,
                ),
            )
        }
    }
}
