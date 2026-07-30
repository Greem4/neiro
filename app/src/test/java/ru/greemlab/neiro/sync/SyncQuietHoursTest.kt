package ru.greemlab.neiro.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SyncQuietHoursTest {

    @Test
    fun `active hours are 09-21 Moscow`() {
        assertFalse(SyncQuietHours.isQuietHours(LocalTime.of(9, 0)))
        assertFalse(SyncQuietHours.isQuietHours(LocalTime.of(20, 59)))
        assertTrue(SyncQuietHours.isQuietHours(LocalTime.of(21, 0)))
        assertTrue(SyncQuietHours.isQuietHours(LocalTime.of(8, 59)))
    }
}
