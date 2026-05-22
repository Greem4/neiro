package ru.greemlab.neiro.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SessionDailyNotificationWorkerTest {

    @Test
    fun `shouldCheckAt matches scheduled time within window`() {
        val scheduled = ScheduledNotificationTime(21, 0)
        assertTrue(
            SessionDailyNotificationWorker.shouldCheckAt(LocalTime.of(21, 15), scheduled),
        )
        assertTrue(
            SessionDailyNotificationWorker.shouldCheckAt(LocalTime.of(20, 45), scheduled),
        )
    }

    @Test
    fun `shouldCheckAt rejects time outside window`() {
        val scheduled = ScheduledNotificationTime(8, 0)
        assertFalse(
            SessionDailyNotificationWorker.shouldCheckAt(LocalTime.of(9, 5), scheduled),
        )
        assertFalse(
            SessionDailyNotificationWorker.shouldCheckAt(LocalTime.of(7, 0), scheduled),
        )
    }
}
