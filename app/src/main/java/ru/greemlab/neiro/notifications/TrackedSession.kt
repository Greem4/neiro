package ru.greemlab.neiro.notifications

import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.components.daydetails.SESSION_DURATION_MINUTES
import ru.greemlab.neiro.ui.components.daydetails.parseTimeRangeEnd
import ru.greemlab.neiro.ui.components.daydetails.parseTimeRangeStart
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Занятие для сравнения снимков календаря (включая отменённые).
 */
data class TrackedSession(
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val clientName: String,
    val kind: UpcomingSessionKind,
    val status: AttendanceStatus,
    val isMarkedDeleted: Boolean,
) {
    /** Ключ слота: клиент + дата + время начала. */
    val slotKey: String =
        "${clientName.normalizeForKey()}|${date}|${startTime.format(TIME_FMT)}"

    /** Ключ клиента для сопоставления переносов. */
    val clientKey: String = clientName.normalizeForKey()

    fun toUpcoming(): UpcomingSession = UpcomingSession(
        date = date,
        startTime = startTime,
        endTime = endTime,
        clientName = clientName,
        kind = kind,
        status = status,
    )

    fun formatLine(): String {
        val timeRange = "${startTime.format(TIME_FMT)}–${endTime.format(TIME_FMT)}"
        val kindLabel = when (kind) {
            UpcomingSessionKind.LESSON -> "Занятие"
            UpcomingSessionKind.DIAGNOSTICS -> "Диагностика"
        }
        return "${date.format(DATE_FMT)}, $timeRange · $clientName · $kindLabel"
    }

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FMT = DateTimeFormatter.ofPattern("d.MM.yyyy")
    }
}

data class SessionEvent(
    val type: SessionEventType,
    val session: TrackedSession,
    val previous: TrackedSession? = null,
) {
    val dedupeKey: String = when (type) {
        SessionEventType.RESCHEDULED ->
            "reschedule|${session.slotKey}|${previous?.slotKey.orEmpty()}"
        SessionEventType.CLIENT_CONFIRMED ->
            "confirmed|${session.slotKey}|${session.status.name}"
        SessionEventType.CLIENT_ARRIVED ->
            "arrived|${session.slotKey}|${session.status.name}"
        else -> "${type.name}|${session.slotKey}"
    }
}

object CalendarSessionSnapshot {

    fun from(
        dayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        horizonDays: Int = 60,
    ): List<TrackedSession> {
        if (!profile.isRegistered) return emptyList()

        val today = LocalDate.now()
        val end = today.plusDays(horizonDays.toLong())

        return dayData.flatMap { (date, entries) ->
            if (date.isBefore(today.minusDays(7)) || date.isAfter(end)) return@flatMap emptyList()
            entries.mapNotNull { raw -> parseEntry(date, raw) }
        }
    }

    private fun parseEntry(date: LocalDate, raw: String): TrackedSession? {
        val session = SessionParser.parse(raw)
        val name = when (session) {
            is Session.Student -> session.name.trim()
            is Session.Diagnostics -> session.name.trim()
            is Session.Extra -> session.name.trim()
        }
        if (name.isBlank()) return null

        val time = when (session) {
            is Session.Student -> session.time
            is Session.Diagnostics -> session.time
            else -> ""
        }

        val start = time.takeIf { it.isNotBlank() }?.let { parseTimeRangeStart(it) }
        val end = time.takeIf { it.isNotBlank() }?.let { parseTimeRangeEnd(it) }
            ?: start?.plusMinutes(SESSION_DURATION_MINUTES.toLong())

        val kind = when (session) {
            is Session.Diagnostics -> UpcomingSessionKind.DIAGNOSTICS
            else -> UpcomingSessionKind.LESSON
        }

        return TrackedSession(
            date = date,
            startTime = start ?: LocalTime.MIDNIGHT,
            endTime = end ?: LocalTime.MIDNIGHT,
            clientName = name,
            kind = kind,
            status = session.status,
            isMarkedDeleted = session.isEffectivelyDeleted(),
        )
    }
}
