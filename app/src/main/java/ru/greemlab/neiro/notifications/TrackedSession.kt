package ru.greemlab.neiro.notifications

import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.calendar.isEffectivelyDeleted
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
    val slotKey: String = SessionSlotKey.build(clientName, date, startTime, kind)

    /** Ключ клиента для сопоставления переносов. */
    val clientKey: String = clientName.normalizeForKey()

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

    const val DEFAULT_HORIZON_DAYS = 60

    fun from(
        dayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        today: LocalDate = LocalDate.now(),
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
    ): List<TrackedSession> {
        if (!profile.isRegistered) return emptyList()

        return dayData.flatMap { (date, entries) ->
            if (!isDateWithinHorizon(date, today, horizonDays)) return@flatMap emptyList()
            entries.flatMap { raw -> parseEntries(date, raw) }
        }
    }

    /** Оставляет только занятия в том же окне, что и [from] (сегодня и вперёд). */
    fun withinHorizon(
        sessions: List<TrackedSession>,
        today: LocalDate = LocalDate.now(),
        horizonDays: Int = DEFAULT_HORIZON_DAYS,
    ): List<TrackedSession> = sessions.filter { session ->
        isDateWithinHorizon(session.date, today, horizonDays)
    }

    private fun isDateWithinHorizon(date: LocalDate, today: LocalDate, horizonDays: Int): Boolean {
        if (date.isBefore(today)) return false
        return !date.isAfter(today.plusDays(horizonDays.toLong()))
    }

    private fun parseEntries(date: LocalDate, raw: String): List<TrackedSession> {
        val session = SessionParser.parse(raw)

        // Для интенсива отслеживаем каждого ребёнка как отдельное занятие,
        // чтобы получать уведомления об их отмене/подтверждении.
        if (session is Session.Intensive && session.children.isNotEmpty()) {
            val time = session.time
            val start = time.takeIf { it.isNotBlank() }?.let { parseTimeRangeStart(it) }
            val end = time.takeIf { it.isNotBlank() }?.let { parseTimeRangeEnd(it) }
                ?: start?.plusMinutes(SESSION_DURATION_MINUTES.toLong())

            return session.children.mapNotNull { child ->
                if (child.name.isBlank()) return@mapNotNull null
                TrackedSession(
                    date = date,
                    startTime = start ?: LocalTime.MIDNIGHT,
                    endTime = end ?: LocalTime.MIDNIGHT,
                    clientName = child.name.trim(),
                    kind = UpcomingSessionKind.LESSON,
                    status = child.status,
                    isMarkedDeleted = child.isEffectivelyDeleted(),
                )
            }
        }

        val name = when (session) {
            is Session.Student -> session.name.trim()
            is Session.Diagnostics -> session.name.trim()
            is Session.Extra -> session.name.trim()
        }
        if (name.isBlank()) return emptyList()

        val time = when (session) {
            is Session.Student -> session.time
            is Session.Diagnostics -> session.time
            is Session.Intensive -> session.time
        }

        val start = time.takeIf { it.isNotBlank() }?.let { parseTimeRangeStart(it) }
        val end = time.takeIf { it.isNotBlank() }?.let { parseTimeRangeEnd(it) }
            ?: start?.plusMinutes(SESSION_DURATION_MINUTES.toLong())

        val kind = when (session) {
            is Session.Diagnostics -> UpcomingSessionKind.DIAGNOSTICS
            else -> UpcomingSessionKind.LESSON
        }

        return listOf(
            TrackedSession(
                date = date,
                startTime = start ?: LocalTime.MIDNIGHT,
                endTime = end ?: LocalTime.MIDNIGHT,
                clientName = name,
                kind = kind,
                status = session.status,
                isMarkedDeleted = session.isEffectivelyDeleted(),
            )
        )
    }
}
