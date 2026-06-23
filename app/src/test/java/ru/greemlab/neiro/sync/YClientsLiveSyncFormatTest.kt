package ru.greemlab.neiro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class YClientsLiveSyncFormatTest {

    @Test
    fun `changed_after includes overlap before instant`() {
        val instant = Instant.parse("2025-06-23T12:00:00Z")
        val formatted = YClientsLiveSyncFormat.formatChangedAfter(
            instant = instant,
            zone = ZoneId.of("Europe/Moscow"),
        )
        assertEquals("2025-06-23T14:59:00+0300", formatted)
    }
}
