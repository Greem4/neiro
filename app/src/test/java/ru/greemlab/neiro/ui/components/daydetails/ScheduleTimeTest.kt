package ru.greemlab.neiro.ui.components.daydetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ScheduleTimeTest {

    @Test
    fun `buildIntensiveTimeSlotOptions covers work day with hourly steps`() {
        val slots = buildIntensiveTimeSlotOptions(emptyList())
        assertTrue(slots.any { formatTimeSlotLabel(it) == "09:00" })
        assertTrue(slots.none { formatTimeSlotLabel(it) == "09:30" })
        assertTrue(slots.any { formatTimeSlotLabel(it) == "16:00" })
        assertTrue(slots.any { formatTimeSlotLabel(it) == "21:00" })
    }

    @Test
    fun `buildIntensiveTimeSlotOptions merges lesson times`() {
        val slots = buildIntensiveTimeSlotOptions(listOf("11:30-12:20"))
        assertTrue(slots.any { formatTimeSlotLabel(it) == "11:30" })
        assertTrue(slots.any { formatTimeSlotLabel(it) == "16:00" })
    }

    @Test
    fun `intensiveDefaultTimeSlot is 18 00`() {
        assertEquals("18:00", formatTimeSlotLabel(intensiveDefaultTimeSlot()))
    }

    @Test
    fun `intensiveDefaultSlotIndex prefers 18 00`() {
        val slots = buildIntensiveTimeSlotOptions(emptyList())
        assertEquals("18:00", formatTimeSlotLabel(slots[intensiveDefaultSlotIndex(slots)]))
    }

    @Test
    fun `formatTimeSlotLabel shows start only`() {
        assertEquals("14:00", formatTimeSlotLabel("14:00-14:50"))
    }

    @Test
    fun `clampIntensiveStartTime keeps within work hours`() {
        assertEquals(SCHEDULE_DAY_START, clampIntensiveStartTime(LocalTime.of(7, 0)))
        assertEquals(LocalTime.of(21, 0), clampIntensiveStartTime(LocalTime.of(23, 0)))
    }
}
