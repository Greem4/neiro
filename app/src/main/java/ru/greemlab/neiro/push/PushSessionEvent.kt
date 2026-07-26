package ru.greemlab.neiro.push

import com.google.gson.annotations.SerializedName
import ru.greemlab.neiro.notifications.SessionEvent
import ru.greemlab.neiro.notifications.SessionEventType
import ru.greemlab.neiro.notifications.TrackedSession
import ru.greemlab.neiro.notifications.UpcomingSessionKind
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.components.daydetails.SESSION_DURATION_MINUTES
import java.time.LocalDate
import java.time.LocalTime

/**
 * Событие занятия из payload сервера (push или догон) — зеркало `EventPayload`
 * в neiro-push-events/app/schemas.py.
 */
data class PushSessionEvent(
    val id: Long,
    @SerializedName("staff_id") val staffId: Int,
    val type: String,
    @SerializedName("client_name") val clientName: String,
    val date: String,
    val time: String,
    val kind: String,
    @SerializedName("prev_date") val prevDate: String? = null,
    @SerializedName("prev_time") val prevTime: String? = null,
) {
    /** `null`, если сервер новее приложения (незнакомый тип/kind) или дата/время не парсятся. */
    fun toSessionEvent(): SessionEvent? {
        val eventType = runCatching { SessionEventType.valueOf(type) }.getOrNull() ?: return null
        val kindEnum = runCatching { UpcomingSessionKind.valueOf(kind) }.getOrNull() ?: return null
        val sessionDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val startTime = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null

        val status = when (eventType) {
            SessionEventType.CLIENT_CONFIRMED -> AttendanceStatus.CONFIRMED
            SessionEventType.CLIENT_ARRIVED -> AttendanceStatus.ARRIVED
            SessionEventType.CANCELLED -> AttendanceStatus.CANCELLED
            SessionEventType.NEW_BOOKING,
            SessionEventType.RESCHEDULED,
            SessionEventType.DELETED,
            -> AttendanceStatus.EXPECTED
            else -> return null
        }

        val session = TrackedSession(
            date = sessionDate,
            startTime = startTime,
            endTime = startTime.plusMinutes(SESSION_DURATION_MINUTES.toLong()),
            clientName = clientName,
            kind = kindEnum,
            status = status,
            isMarkedDeleted = eventType == SessionEventType.DELETED,
        )

        // Перенос без prev_date/prev_time — переносить некуда, событие отбрасываем,
        // а не показываем как перенос «в никуда» (app.md §4.1).
        val previous = if (eventType == SessionEventType.RESCHEDULED) {
            val prevDateParsed = prevDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return null
            val prevTimeParsed = prevTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                ?: return null
            TrackedSession(
                date = prevDateParsed,
                startTime = prevTimeParsed,
                endTime = prevTimeParsed.plusMinutes(SESSION_DURATION_MINUTES.toLong()),
                clientName = clientName,
                kind = kindEnum,
                status = AttendanceStatus.EXPECTED,
                isMarkedDeleted = false,
            )
        } else {
            null
        }

        return SessionEvent(type = eventType, session = session, previous = previous)
    }
}
