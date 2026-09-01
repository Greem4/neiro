package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import java.time.LocalDate
import java.time.LocalTime

class SessionChangeDetectorTest {

    private val date = LocalDate.of(2026, 5, 22)

    @Test
    fun `detects new booking`() {
        val after = listOf(session("Анна", "10:00", AttendanceStatus.EXPECTED))
        val events = SessionChangeDetector.detect(emptyList(), after)
        assertEquals(1, events.size)
        assertEquals(SessionEventType.NEW_BOOKING, events.first().type)
    }

    @Test
    fun `detects cancellation by status change`() {
        val before = listOf(session("Анна", "10:00", AttendanceStatus.EXPECTED))
        val after = listOf(session("Анна", "10:00", AttendanceStatus.CANCELLED))
        val events = SessionChangeDetector.detect(before, after)
        assertTrue(events.any { it.type == SessionEventType.CANCELLED })
    }

    @Test
    fun `detects reschedule`() {
        val before = listOf(session("Анна", "10:00", AttendanceStatus.EXPECTED))
        val after = listOf(session("Анна", "14:00", AttendanceStatus.EXPECTED))
        val events = SessionChangeDetector.detect(before, after)
        assertEquals(1, events.size)
        assertEquals(SessionEventType.RESCHEDULED, events.first().type)
        assertEquals("14:00", events.first().session.startTime.toString())
    }

    @Test
    fun `detects deleted slot`() {
        val before = listOf(session("Анна", "10:00", AttendanceStatus.EXPECTED))
        val events = SessionChangeDetector.detect(before, emptyList())
        assertTrue(events.any { it.type == SessionEventType.DELETED })
    }

    @Test
    fun `detects client confirmed`() {
        val before = listOf(session("Анна", "10:00", AttendanceStatus.EXPECTED))
        val after = listOf(session("Анна", "10:00", AttendanceStatus.CONFIRMED))
        val events = SessionChangeDetector.detect(before, after)
        assertEquals(1, events.size)
        assertEquals(SessionEventType.CLIENT_CONFIRMED, events.first().type)
    }

    @Test
    fun `detects client arrived`() {
        val before = listOf(session("Анна", "10:00", AttendanceStatus.CONFIRMED))
        val after = listOf(session("Анна", "10:00", AttendanceStatus.ARRIVED))
        val events = SessionChangeDetector.detect(before, after)
        assertEquals(1, events.size)
        assertEquals(SessionEventType.CLIENT_ARRIVED, events.first().type)
    }

    @Test
    fun `payment after arrival does not raise a second event`() {
        // «Пришёл» и «оплатил» — два состояния одной записи; будить человека
        // второй раз незачем (01.09.2026).
        val before = listOf(session("Анна", "10:00", AttendanceStatus.ARRIVED))
        val after = listOf(session("Анна", "10:00", AttendanceStatus.PAID))
        assertEquals(0, SessionChangeDetector.detect(before, after).size)
    }

    @Test
    fun `arrival noticed together with payment still notifies once`() {
        val before = listOf(session("Анна", "10:00", AttendanceStatus.CONFIRMED))
        val after = listOf(session("Анна", "10:00", AttendanceStatus.PAID))
        val events = SessionChangeDetector.detect(before, after)
        assertEquals(1, events.size)
        assertEquals(SessionEventType.CLIENT_ARRIVED, events.first().type)
    }

    private fun session(
        name: String,
        start: String,
        status: AttendanceStatus,
    ): TrackedSession = TrackedSession(
        date = date,
        startTime = LocalTime.parse(start),
        endTime = LocalTime.parse(start).plusMinutes(50),
        clientName = name,
        kind = UpcomingSessionKind.LESSON,
        status = status,
        isMarkedDeleted = false,
    )
}
