package ru.greemlab.neiro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate

class YClientsCalendarSyncTest {

    private val start = LocalDate.of(2025, 5, 1)
    private val end = LocalDate.of(2025, 5, 31)

    @Test
    fun `merge allowed when API returned records`() {
        assertTrue(
            YClientsCalendarSync.shouldApplySyncMerge(
                records = listOf(fakeRecord()),
                localDayData = mapOf(
                    LocalDate.of(2025, 5, 10) to listOf("Иванов|1"),
                ),
                startDate = start,
                endDate = end,
            ),
        )
    }

    @Test
    fun `merge allowed when API empty and local calendar empty in range`() {
        assertTrue(
            YClientsCalendarSync.shouldApplySyncMerge(
                records = emptyList(),
                localDayData = mapOf(
                    LocalDate.of(2025, 6, 1) to listOf("Иванов|1"),
                ),
                startDate = start,
                endDate = end,
            ),
        )
    }

    @Test
    fun `merge skipped when API empty but local has students in range`() {
        assertFalse(
            YClientsCalendarSync.shouldApplySyncMerge(
                records = emptyList(),
                localDayData = mapOf(
                    LocalDate.of(2025, 5, 10) to listOf("Иванов|1"),
                ),
                startDate = start,
                endDate = end,
            ),
        )
    }

    @Test
    fun `intensives do not block merge on empty API response`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "5000",
            name = "Летний лагерь",
            status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.EXPECTED,
            time = "10:00-12:00",
        )
        assertTrue(
            YClientsCalendarSync.shouldApplySyncMerge(
                records = emptyList(),
                localDayData = mapOf(
                    LocalDate.of(2025, 5, 10) to listOf(intensive),
                ),
                startDate = start,
                endDate = end,
            ),
        )
    }

    @Test
    fun `counts only non-intensive entries in range`() {
        val intensive = SessionFormat.serializeIntensive(
            price = "5000",
            name = "Интенсив",
            status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.EXPECTED,
            time = "",
        )
        val dayData = mapOf(
            LocalDate.of(2025, 5, 10) to listOf("Иванов|1", intensive),
            LocalDate.of(2025, 5, 11) to listOf(intensive),
        )
        assertEquals(1, YClientsCalendarSync.countYClientsManagedLocalEntries(dayData, start, end))
    }

    private fun fakeRecord() = ru.greemlab.neiro.data.network.RecordData(
        id = 1L,
        companyId = 1,
        staffId = 1,
        client = null,
        date = "2025-05-10",
        datetime = "2025-05-10 10:00:00",
        createDate = null,
        comment = null,
        attendance = 0,
        seanceLength = null,
        length = null,
        visitAttendance = null,
        services = null,
    )
}
