package ru.greemlab.neiro.ui.components.daydetails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

class TimelineLayoutTest {

    @Test
    fun `intensive and cancelled student in same slot stack in one lane`() {
        val intensive = timelineEntry(
            name = "Интенсив · 4 ребёнка",
            time = "18:00-18:50",
            status = AttendanceStatus.ARRIVED,
            isExtra = true,
            extraType = "Интенсив",
        )
        val cancelled = timelineEntry(
            name = "Полякова Виталий",
            time = "18:00-18:50",
            status = AttendanceStatus.CANCELLED,
        )
        val layout = buildDayTimelineLayout(listOf(intensive, cancelled))!!

        assertEquals(1, layout.positioned.size)
        val item = layout.positioned.single()
        assertTrue(item is PositionedTimelineItem.IntensiveCover)
        val cover = item as PositionedTimelineItem.IntensiveCover
        assertEquals(intensive.name, cover.pair.intensive.entry.name)
        assertEquals(cancelled.name, cover.pair.covered.single().entry.name)
        assertEquals(1, item.laneCount)
    }

    @Test
    fun `covered entries under intensive are stacked not shown separately`() {
        val covered = timelineEntry(
            name = "Дима",
            time = "18:00-18:50",
            status = AttendanceStatus.CANCELLED,
            sourceIndex = 1,
        )
        val intensive = timelineEntry(
            name = "Интенсив · 1 ребёнок",
            time = "18:00-18:50",
            status = AttendanceStatus.ARRIVED,
            isExtra = true,
            extraType = "Интенсив",
            coveredEntries = listOf(covered),
        )
        val layout = buildDayTimelineLayout(listOf(intensive))!!

        assertEquals(1, layout.positioned.size)
        val cover = layout.positioned.single() as PositionedTimelineItem.IntensiveCover
        assertEquals("Дима", cover.pair.covered.single().entry.name)
    }

    @Test
    fun `arrived and cancelled in same slot form replacement not intensive cover`() {
        val arrived = timelineEntry(
            name = "Новый",
            time = "16:00-16:50",
            status = AttendanceStatus.ARRIVED,
        )
        val cancelled = timelineEntry(
            name = "Старый",
            time = "16:00-16:50",
            status = AttendanceStatus.CANCELLED,
        )
        val layout = buildDayTimelineLayout(listOf(arrived, cancelled))!!

        assertEquals(1, layout.positioned.size)
        assertTrue(layout.positioned.single() is PositionedTimelineItem.Replacement)
    }

    private fun timelineEntry(
        name: String,
        time: String,
        status: AttendanceStatus,
        isExtra: Boolean = false,
        extraType: String = "",
        coveredEntries: List<TimelineEntry> = emptyList(),
        sourceIndex: Int = -1,
    ): TimelineEntry = TimelineEntry(
        name = name,
        time = time,
        comment = "",
        status = status,
        isExtra = isExtra,
        extraType = extraType,
        coveredEntries = coveredEntries,
        sourceIndex = sourceIndex,
    )
}
