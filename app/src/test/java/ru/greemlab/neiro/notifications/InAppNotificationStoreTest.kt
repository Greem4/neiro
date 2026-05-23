package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class InAppNotificationStoreTest {

    @Test
    fun prune_keepsRecentAndDropsOlderThanRetention() {
        val now = 1_700_000_000_000L
        val recent = InAppNotification(
            id = "a",
            title = "t",
            body = "b",
            timestampEpochMillis = now,
        )
        val old = recent.copy(
            id = "b",
            timestampEpochMillis = now - TimeUnit.DAYS.toMillis(InAppNotificationStore.RETENTION_DAYS + 1L),
        )

        val pruned = InAppNotificationStore.prune(listOf(old, recent), nowMillis = now)

        assertEquals(1, pruned.size)
        assertEquals("a", pruned.single().id)
    }
}
