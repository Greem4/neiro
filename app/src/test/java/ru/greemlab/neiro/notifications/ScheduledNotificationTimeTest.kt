package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class ScheduledNotificationTimeTest {

    @Test
    fun `round trip through minutes from midnight`() {
        val original = ScheduledNotificationTime(20, 30)
        val restored = ScheduledNotificationTime.fromMinutesFromMidnight(original.toMinutesFromMidnight())
        assertEquals(original, restored)
        assertEquals(LocalTime.of(20, 30), restored.toLocalTime())
    }

    @Test
    fun `formatForDisplay pads hours and minutes`() {
        assertEquals("08:05", ScheduledNotificationTime(8, 5).formatForDisplay())
    }
}
