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
        assertEquals("Иванов", diff[0].title)
        assertEquals(ArchiveSyncCompare.Presence.BOTH, diff[0].presence)
        assertEquals(
            listOf(ArchiveSyncCompare.FieldDiff("Статус", synced = "Пришёл", archived = "Ожидает")),
            diff[0].fields,
        )
    }

    @Test
    fun `describeDiff reports session only in archive`() {
        val synced = emptyList<String>()
        val archived = listOf(
            SessionFormat.serializeStudentExtended("Петров", AttendanceStatus.ARRIVED, "11:00"),
        )
        val diff = ArchiveSyncCompare.describeDiff(synced, archived)
        assertEquals(1, diff.size)
        assertEquals("Петров", diff[0].title)
        assertEquals(ArchiveSyncCompare.Presence.ONLY_ARCHIVED, diff[0].presence)
        assertTrue(diff[0].fields.isEmpty())
    }

    @Test
    fun `describeDiff pairs moved session by name and reports time change`() {
        val synced = listOf(
            SessionFormat.serializeStudentExtended("Сухова", AttendanceStatus.ARRIVED, "12:00-12:50"),
        )
        val archived = listOf(
            SessionFormat.serializeStudentExtended("Сухова", AttendanceStatus.ARRIVED, "12:30-13:20"),
        )
        val diff = ArchiveSyncCompare.describeDiff(synced, archived)
        assertEquals(1, diff.size)
        assertEquals(ArchiveSyncCompare.Presence.BOTH, diff[0].presence)
        assertEquals(
            listOf(ArchiveSyncCompare.FieldDiff("Время", synced = "12:00-12:50", archived = "12:30-13:20")),
            diff[0].fields,
        )
    }

    @Test
    fun `describeDiff is empty when days match`() {
        val sessions = listOf(SessionFormat.serializeStudentExtended("А", AttendanceStatus.ARRIVED))
        assertTrue(ArchiveSyncCompare.describeDiff(sessions, sessions).isEmpty())
    }
}
