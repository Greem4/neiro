package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate

class CalendarSessionSnapshotTest {

    private val profile = UserProfile(name = "Тест", isRegistered = true)
    private val today = LocalDate.of(2026, 5, 25)

    @Test
    fun `from excludes past dates`() {
        val dayData = mapOf(
            LocalDate.of(2026, 5, 17) to listOf(studentEntry("Прошлое", "14:00-14:50")),
            today to listOf(studentEntry("Сегодня", "15:00-15:50")),
        )

        val snapshot = CalendarSessionSnapshot.from(dayData, profile, today = today)

        assertEquals(1, snapshot.size)
        assertEquals("Сегодня", snapshot.first().clientName)
    }

    @Test
    fun `withinHorizon drops aged-out sessions so they are not treated as deleted`() {
        val pastSession = TrackedSession(
            date = LocalDate.of(2026, 5, 17),
            startTime = java.time.LocalTime.of(14, 0),
            endTime = java.time.LocalTime.of(14, 50),
            clientName = "Прошлое",
            kind = UpcomingSessionKind.LESSON,
            status = AttendanceStatus.EXPECTED,
            isMarkedDeleted = false,
        )
        val todaySession = pastSession.copy(
            date = today,
            clientName = "Сегодня",
            startTime = java.time.LocalTime.of(15, 0),
            endTime = java.time.LocalTime.of(15, 50),
        )

        val storedBefore = CalendarSessionSnapshot.withinHorizon(
            sessions = listOf(pastSession, todaySession),
            today = today,
        )
        val after = listOf(todaySession)

        val events = SessionChangeDetector.detect(storedBefore, after)

        assertTrue(events.none { it.type == SessionEventType.DELETED })
    }

    private fun studentEntry(name: String, time: String): String =
        SessionFormat.serializeStudentExtended(
            name = name,
            status = AttendanceStatus.EXPECTED,
            time = time,
            phone = "",
            comment = "",
        )
}
