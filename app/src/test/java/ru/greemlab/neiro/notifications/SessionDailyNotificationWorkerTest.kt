package ru.greemlab.neiro.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SessionDailyNotificationWorkerTest {

    @Test
    fun `isDueToday true at and after scheduled time`() {
        val scheduled = ScheduledNotificationTime(21, 0)
        assertTrue(SessionDailyNotificationWorker.isDueToday(LocalTime.of(21, 0), scheduled))
        assertTrue(SessionDailyNotificationWorker.isDueToday(LocalTime.of(21, 45), scheduled))
        assertTrue(SessionDailyNotificationWorker.isDueToday(LocalTime.of(23, 59), scheduled))
    }

    @Test
    fun `isDueToday false before scheduled time`() {
        val scheduled = ScheduledNotificationTime(21, 0)
        assertFalse(SessionDailyNotificationWorker.isDueToday(LocalTime.of(20, 59), scheduled))
        assertFalse(SessionDailyNotificationWorker.isDueToday(LocalTime.of(8, 0), scheduled))
    }
}
