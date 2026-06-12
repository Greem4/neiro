package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class ArchiveNotificationStoreTest {

    @Test
    fun prune_keepsAllByAgeAndLimitsCount() {
        val now = 1_700_000_000_000L
        val recent = InAppNotification(
            id = "a",
            title = "t",
            body = "b",
            timestampEpochMillis = now,
        )
        val old = recent.copy(
            id = "b",
            timestampEpochMillis = now - TimeUnit.DAYS.toMillis(365),
        )

        val pruned = ArchiveNotificationStore.prune(listOf(old, recent))

        assertEquals(2, pruned.size)
        assertEquals("a", pruned.first().id)
    }
}
