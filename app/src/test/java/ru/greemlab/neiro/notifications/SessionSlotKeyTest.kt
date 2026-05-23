package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.components.daydetails.TimelineEntry
import java.time.LocalDate
import java.time.LocalTime

class SessionSlotKeyTest {

    @Test
    fun fromTimelineEntry_matchesBuild() {
        val date = LocalDate.of(2026, 5, 24)
        val entry = TimelineEntry(
            name = "Пирогов Лев",
            time = "16:00-16:50",
            comment = "",
            status = AttendanceStatus.ARRIVED,
        )
        val key = SessionSlotKey.fromTimelineEntry(entry, date)
        val expected = SessionSlotKey.build("Пирогов Лев", date, LocalTime.of(16, 0))
        assertEquals(expected, key)
    }
}
