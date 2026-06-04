package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ArchiveSyncCompareTest {

    @Test
    fun `differs when status code changed`() {
        val synced = listOf(SessionFormat.serializeStudentExtended("Иванов", AttendanceStatus.ARRIVED))
        val archived = listOf(SessionFormat.serializeStudentExtended("Иванов", AttendanceStatus.EXPECTED))
        assertTrue(ArchiveSyncCompare.differs(synced, archived))
    }

    @Test
    fun `not differs for same session different string format`() {
        val synced = listOf("Иванов|true")
        val archived = listOf(
            SessionFormat.serializeStudentExtended("Иванов", AttendanceStatus.ARRIVED),
        )
        assertFalse(ArchiveSyncCompare.differs(synced, archived))
    }

    @Test
    fun `mismatchDates only when archived day differs`() {
        val date = LocalDate.of(2026, 5, 23)
        val synced = mapOf(
            date to listOf(SessionFormat.serializeStudentExtended("А", AttendanceStatus.ARRIVED)),
        )
        val archived = mapOf(
            date to listOf(SessionFormat.serializeStudentExtended("А", AttendanceStatus.EXPECTED)),
        )
        assertEquals(setOf(date), ArchiveSyncCompare.mismatchDates(synced, archived))
    }
}
