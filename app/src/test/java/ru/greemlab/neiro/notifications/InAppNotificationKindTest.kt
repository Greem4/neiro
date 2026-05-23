package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class InAppNotificationKindTest {

    @Test
    fun displayKind_usesStoredKind() {
        val item = InAppNotification(
            id = "1",
            title = "ignored",
            body = "",
            timestampEpochMillis = 0L,
            kind = SessionEventType.CANCELLED.name,
        )
        assertEquals(SessionEventType.CANCELLED, item.displayKind)
    }

    @Test
    fun displayKind_infersFromTitleWhenKindMissing() {
        val item = InAppNotification(
            id = "1",
            title = "Перенос: Лев",
            body = "",
            timestampEpochMillis = 0L,
        )
        assertEquals(SessionEventType.RESCHEDULED, item.displayKind)
    }
}
