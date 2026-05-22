package ru.greemlab.neiro.notifications

import android.content.Context
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate
import java.time.LocalTime

/**
 * Показ тестовых push-уведомлений (только debug-сборка, меню разработчика).
 */
object SessionNotificationDevPreview {

    private const val SAMPLE_CLIENT = "Тестовый клиент"

    fun showNewBooking(context: Context) {
        showEvent(context, SessionEventType.NEW_BOOKING)
    }

    fun showCancelled(context: Context) {
        showEvent(context, SessionEventType.CANCELLED, status = AttendanceStatus.CANCELLED)
    }

    fun showRescheduled(context: Context) {
        val previous = sampleTracked(start = LocalTime.of(10, 0))
        val session = sampleTracked(start = LocalTime.of(14, 0))
        SessionNotificationDisplay.showEvents(
            context,
            listOf(
                SessionEvent(
                    type = SessionEventType.RESCHEDULED,
                    session = session,
                    previous = previous,
                ),
            ),
        )
    }

    fun showDeleted(context: Context) {
        showEvent(context, SessionEventType.DELETED, isMarkedDeleted = true)
    }

    fun showClientConfirmed(context: Context) {
        showStatusTransition(context, SessionEventType.CLIENT_CONFIRMED, AttendanceStatus.CONFIRMED)
    }

    fun showClientArrived(context: Context) {
        showStatusTransition(context, SessionEventType.CLIENT_ARRIVED, AttendanceStatus.ARRIVED)
    }

    private fun showStatusTransition(
        context: Context,
        type: SessionEventType,
        newStatus: AttendanceStatus,
    ) {
        val previous = sampleTracked(status = AttendanceStatus.EXPECTED)
        val session = sampleTracked(status = newStatus)
        SessionNotificationDisplay.showEvents(
            context,
            listOf(SessionEvent(type = type, session = session, previous = previous)),
        )
    }

    fun showReminder(context: Context) {
        val now = LocalTime.now()
        val start = now.plusMinutes(15).withSecond(0).withNano(0)
        val end = start.plusMinutes(50)
        SessionNotificationDisplay.showReminder(
            context,
            listOf(
                UpcomingSession(
                    date = LocalDate.now(),
                    startTime = start,
                    endTime = end,
                    clientName = SAMPLE_CLIENT,
                    kind = UpcomingSessionKind.LESSON,
                    status = AttendanceStatus.EXPECTED,
                ),
            ),
        )
    }

    fun showTodayDigest(context: Context) {
        val today = LocalDate.now()
        val start = LocalTime.of(10, 0)
        SessionNotificationDisplay.showTodayDigest(
            context,
            listOf(
                sampleUpcoming(today, start),
                sampleUpcoming(today, start.plusHours(2), "Второй клиент"),
            ),
        )
    }

    fun showTomorrowDigest(context: Context) {
        val tomorrow = LocalDate.now().plusDays(1)
        SessionNotificationDisplay.showTomorrowDigest(
            context,
            listOf(
                sampleUpcoming(tomorrow, LocalTime.of(11, 0)),
                sampleUpcoming(tomorrow, LocalTime.of(16, 30), "Диагностика", UpcomingSessionKind.DIAGNOSTICS),
            ),
        )
    }

    fun showArchiveReminder(context: Context) {
        val today = LocalDate.now()
        val dayData = mapOf<LocalDate, List<String>>(
            today to listOf(
                SessionFormat.serializeStudentExtended(
                    name = "Анна",
                    status = AttendanceStatus.ARRIVED,
                    time = "10:00-11:00",
                ),
                SessionFormat.serializeStudentExtended(
                    name = "Пётр",
                    status = AttendanceStatus.EXPECTED,
                    time = "14:00-15:00",
                ),
            ),
        )
        SessionNotificationDisplay.showArchiveReminder(context, listOf(today), dayData)
    }

    fun showGroupedEvents(context: Context) {
        SessionNotificationDisplay.showEvents(
            context,
            listOf(
                SessionEvent(SessionEventType.NEW_BOOKING, sampleTracked(clientName = "Анна")),
                SessionEvent(SessionEventType.CANCELLED, sampleTracked(clientName = "Борис", status = AttendanceStatus.CANCELLED)),
                SessionEvent(
                    SessionEventType.RESCHEDULED,
                    sampleTracked(clientName = "Вера", start = LocalTime.of(16, 0)),
                    previous = sampleTracked(clientName = "Вера", start = LocalTime.of(12, 0)),
                ),
            ),
        )
    }

    private fun showEvent(
        context: Context,
        type: SessionEventType,
        status: AttendanceStatus = AttendanceStatus.EXPECTED,
        isMarkedDeleted: Boolean = false,
    ) {
        SessionNotificationDisplay.showEvents(
            context,
            listOf(
                SessionEvent(
                    type = type,
                    session = sampleTracked(status = status, isMarkedDeleted = isMarkedDeleted),
                ),
            ),
        )
    }

    private fun sampleTracked(
        date: LocalDate = LocalDate.now(),
        start: LocalTime = LocalTime.of(14, 0),
        clientName: String = SAMPLE_CLIENT,
        status: AttendanceStatus = AttendanceStatus.EXPECTED,
        isMarkedDeleted: Boolean = false,
    ): TrackedSession = TrackedSession(
        date = date,
        startTime = start,
        endTime = start.plusMinutes(50),
        clientName = clientName,
        kind = UpcomingSessionKind.LESSON,
        status = status,
        isMarkedDeleted = isMarkedDeleted,
    )

    private fun sampleUpcoming(
        date: LocalDate,
        start: LocalTime,
        clientName: String = SAMPLE_CLIENT,
        kind: UpcomingSessionKind = UpcomingSessionKind.LESSON,
    ): UpcomingSession = UpcomingSession(
        date = date,
        startTime = start,
        endTime = start.plusMinutes(50),
        clientName = clientName,
        kind = kind,
        status = AttendanceStatus.EXPECTED,
    )
}
