package ru.greemlab.neiro.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class LiveApiPollScheduleTest {

    @Test
    fun `active hours are 09-21 Moscow`() {
        assertFalse(LiveApiPollSchedule.isQuietHours(LocalTime.of(9, 0)))
        assertFalse(LiveApiPollSchedule.isQuietHours(LocalTime.of(20, 59)))
        assertTrue(LiveApiPollSchedule.isQuietHours(LocalTime.of(21, 0)))
        assertTrue(LiveApiPollSchedule.isQuietHours(LocalTime.of(8, 59)))
    }
}
