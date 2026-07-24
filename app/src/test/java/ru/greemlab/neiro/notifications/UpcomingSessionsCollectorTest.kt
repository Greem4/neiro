package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class UpcomingSessionsCollectorTest {

    @Test
    fun `collect returns empty when profile not registered`() {
        val result = UpcomingSessionsCollector.collect(
            dayData = mapOf(LocalDate.now() to listOf("Иван|1|10:00-10:50||")),
            profile = UserProfile(name = "Тест", isRegistered = false),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `collect skips cancelled and past today sessions`() {
        val today = LocalDate.of(2026, 5, 22)
        val raw = SessionFormat.serializeStudentExtended(
            name = "Мария",
            status = AttendanceStatus.EXPECTED,
            time = "09:00-09:50",
            phone = "",
            comment = "",
        )
        val cancelled = SessionFormat.serializeStudentExtended(
            name = "Пётр",
            status = AttendanceStatus.CANCELLED,
            time = "15:00-15:50",
            phone = "",
            comment = "",
        )

        val result = UpcomingSessionsCollector.collect(
            dayData = mapOf(today to listOf(raw, cancelled)),
            profile = UserProfile(name = "Тест", isRegistered = true),
            today = today,
            now = LocalTime.of(12, 0),
        )

        assertEquals(0, result.size)
    }

    @Test
    fun `collect includes future session today`() {
        val today = LocalDate.of(2026, 5, 22)
        val raw = SessionFormat.serializeStudentExtended(
            name = "Анна",
            status = AttendanceStatus.CONFIRMED,
            time = "18:00-18:50",
            phone = "",
            comment = "",
        )

        val result = UpcomingSessionsCollector.collect(
            dayData = mapOf(today to listOf(raw)),
            profile = UserProfile(name = "Тест", isRegistered = true),
            today = today,
            now = LocalTime.of(12, 0),
        )

        assertEquals(1, result.size)
        assertEquals("Анна", result.first().clientName)
        assertEquals(UpcomingSessionKind.LESSON, result.first().kind)
    }

    @Test
    fun `tomorrowSessions filters by next day`() {
        val today = LocalDate.of(2026, 5, 22)
        val tomorrow = today.plusDays(1)
        val raw = SessionFormat.serializeStudentExtended(
            name = "Олег",
            status = AttendanceStatus.EXPECTED,
            time = "11:00-11:50",
            phone = "",
            comment = "",
        )

        val upcoming = UpcomingSessionsCollector.collect(
            dayData = mapOf(tomorrow to listOf(raw)),
            profile = UserProfile(name = "Тест", isRegistered = true),
            today = today,
            now = LocalTime.of(12, 0),
        )

        val tomorrowList = UpcomingSessionsCollector.tomorrowSessions(upcoming, today)
        assertEquals(1, tomorrowList.size)
        assertEquals(tomorrow, tomorrowList.first().date)
    }

    @Test
    fun `collectDueForReminder catches session past its ideal reminder moment`() {
        // periodic-тик опоздал: до старта осталось меньше, чем reminderMinutesBefore,
        // но сессия ещё не началась — напоминание всё равно должно попасть в выборку.
        val session = UpcomingSession(
            date = LocalDate.of(2026, 5, 22),
            startTime = LocalTime.of(10, 3),
            endTime = LocalTime.of(10, 50),
            clientName = "Игорь",
            kind = UpcomingSessionKind.LESSON,
            status = AttendanceStatus.EXPECTED,
        )
        val now = LocalDateTime.of(2026, 5, 22, 10, 0)

        val due = UpcomingSessionsCollector.collectDueForReminder(
            sessions = listOf(session),
            reminderMinutesBefore = 15,
            now = now,
        )

        assertEquals(1, due.size)
    }

    @Test
    fun `collectDueForReminder excludes session already started`() {
        val session = UpcomingSession(
            date = LocalDate.of(2026, 5, 22),
            startTime = LocalTime.of(9, 59),
            endTime = LocalTime.of(10, 50),
            clientName = "Игорь",
            kind = UpcomingSessionKind.LESSON,
            status = AttendanceStatus.EXPECTED,
        )
        val now = LocalDateTime.of(2026, 5, 22, 10, 0)

        val due = UpcomingSessionsCollector.collectDueForReminder(
            sessions = listOf(session),
            reminderMinutesBefore = 15,
            now = now,
        )

        assertTrue(due.isEmpty())
    }

    @Test
    fun `collectDueForReminder excludes session far in the future`() {
        val session = UpcomingSession(
            date = LocalDate.of(2026, 5, 22),
            startTime = LocalTime.of(11, 0),
            endTime = LocalTime.of(11, 50),
            clientName = "Игорь",
            kind = UpcomingSessionKind.LESSON,
            status = AttendanceStatus.EXPECTED,
        )
        val now = LocalDateTime.of(2026, 5, 22, 10, 0)

        val due = UpcomingSessionsCollector.collectDueForReminder(
            sessions = listOf(session),
            reminderMinutesBefore = 15,
            now = now,
        )

        assertTrue(due.isEmpty())
    }
}
