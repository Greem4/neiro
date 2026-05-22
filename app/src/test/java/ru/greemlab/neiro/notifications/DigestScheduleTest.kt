package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DigestScheduleTest {

    @Test
    fun `delayUntilNext same day when time not passed`() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = ZonedDateTime.of(2026, 5, 22, 10, 0, 0, 0, zone)
        val delay = DigestSchedule.delayUntilNext(ScheduledNotificationTime(20, 0), zone, now)
        assertEquals(10 * 60L, delay.toMinutes())
    }

    @Test
    fun `delayUntilNext next day when time already passed`() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = ZonedDateTime.of(2026, 5, 22, 21, 0, 0, 0, zone)
        val delay = DigestSchedule.delayUntilNext(ScheduledNotificationTime(8, 0), zone, now)
        assertEquals(11 * 60L, delay.toMinutes())
    }
}
