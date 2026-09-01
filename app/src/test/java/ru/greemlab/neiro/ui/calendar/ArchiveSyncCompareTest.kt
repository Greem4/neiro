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
        // `true` старого формата — это «оплачено»: до разделения «пришёл» и
        // «оплачено» галочка значила «занятие посчитано в деньгах» (01.09.2026).
        val synced = listOf("Иванов|true")
        val archived = listOf(
            SessionFormat.serializeStudentExtended("Иванов", AttendanceStatus.PAID),
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

    @Test
    fun `describeDiff reports status change`() {
        val synced = listOf(
            SessionFormat.serializeStudentExtended("Иванов", AttendanceStatus.ARRIVED, "10:00"),
        )
        val archived = listOf(
            SessionFormat.serializeStudentExtended("Иванов", AttendanceStatus.EXPECTED, "10:00"),
        )
        val diff = ArchiveSyncCompare.describeDiff(synced, archived)
        assertEquals(1, diff.size)
        assertTrue(diff[0].contains("Иванов"))
        assertTrue(diff[0].contains("статус"))
        assertTrue(diff[0].contains("Пришёл"))
        assertTrue(diff[0].contains("Ожидает"))
    }

    @Test
    fun `describeDiff reports session only in archive`() {
        val synced = emptyList<String>()
        val archived = listOf(
            SessionFormat.serializeStudentExtended("Петров", AttendanceStatus.ARRIVED, "11:00"),
        )
        val diff = ArchiveSyncCompare.describeDiff(synced, archived)
        assertEquals(1, diff.size)
        assertTrue(diff[0].contains("Только в архиве"))
        assertTrue(diff[0].contains("Петров"))
    }

    @Test
    fun `describeDiff is empty when days match`() {
        val sessions = listOf(SessionFormat.serializeStudentExtended("А", AttendanceStatus.ARRIVED))
        assertTrue(ArchiveSyncCompare.describeDiff(sessions, sessions).isEmpty())
    }
}
