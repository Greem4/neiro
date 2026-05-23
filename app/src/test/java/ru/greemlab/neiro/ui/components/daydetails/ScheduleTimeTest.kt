package ru.greemlab.neiro.ui.components.daydetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTimeTest {

    @Test
    fun `buildIntensiveTimeSlotOptions includes standard grid up to 18 00`() {
        val slots = buildIntensiveTimeSlotOptions(emptyList())
        assertTrue(slots.any { formatTimeSlotLabel(it) == "18:00" })
        assertTrue(slots.any { formatTimeSlotLabel(it) == "09:00" })
    }

    @Test
    fun `buildIntensiveTimeSlotOptions merges lesson times`() {
        val slots = buildIntensiveTimeSlotOptions(listOf("11:30-12:20"))
        assertTrue(slots.any { formatTimeSlotLabel(it) == "11:30" })
        assertTrue(slots.any { formatTimeSlotLabel(it) == "18:00" })
    }

    @Test
    fun `formatTimeSlotLabel shows start only`() {
        assertEquals("14:00", formatTimeSlotLabel("14:00-14:50"))
    }
}
