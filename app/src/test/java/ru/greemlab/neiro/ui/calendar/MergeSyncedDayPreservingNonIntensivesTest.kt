package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeSyncedDayPreservingNonIntensivesTest {

    @Test
    fun `keeps yclients students and replaces intensives from incoming`() {
        val existing = listOf(
            "Аня|false|10:00-10:50",
            SessionFormat.serializeIntensive(
                price = "1000",
                name = "Интенсив",
                status = AttendanceStatus.ARRIVED,
                time = "12:00-13:00",
                amountFixed = true,
            ),
        )
        val incoming = listOf(
            "ВзломанныйУченик|true|10:00-10:50",
            SessionFormat.serializeIntensive(
                price = "2000",
                name = "Интенсив",
                status = AttendanceStatus.ARRIVED,
                time = "14:00-15:00",
                amountFixed = true,
            ),
        )

        val merged = mergeSyncedDayPreservingNonIntensives(existing, incoming)

        assertEquals(2, merged.size)
        assertEquals("Аня|false|10:00-10:50", merged[0])
        assertEquals(true, SessionParser.isIntensive(merged[1]))
        assertEquals(2000.0, SessionParser.parse(merged[1]).let { (it as Session.Intensive).amount }, 0.0)
        assertEquals("14:00-15:00", (SessionParser.parse(merged[1]) as Session.Intensive).time)
    }

    @Test
    fun `drops intensives when incoming has none`() {
        val existing = listOf(
            "Аня|false|10:00-10:50",
            SessionFormat.serializeIntensive(
                price = "1000",
                name = "Интенсив",
                status = AttendanceStatus.ARRIVED,
                time = "12:00-13:00",
                amountFixed = true,
            ),
        )

        val merged = mergeSyncedDayPreservingNonIntensives(existing, listOf("Игнор|true"))

        assertEquals(listOf("Аня|false|10:00-10:50"), merged)
    }
}
