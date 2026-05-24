package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionParserTest {

    @Test
    fun `parses plain student name without separator as not attended`() {
        val result = SessionParser.parse("Иванов") as Session.Student
        assertEquals("Иванов", result.name)
        assertFalse(result.attended)
    }

    @Test
    fun `parses student with attended=true`() {
        val result = SessionParser.parse("Иванов|true") as Session.Student
        assertEquals("Иванов", result.name)
        assertTrue(result.attended)
    }

    @Test
    fun `parses student with attended=false`() {
        val result = SessionParser.parse("Иванов|false") as Session.Student
        assertEquals("Иванов", result.name)
        assertFalse(result.attended)
    }

    @Test
    fun `parses student with invalid attended falls back to false`() {
        val result = SessionParser.parse("Иванов|garbage") as Session.Student
        assertEquals("Иванов", result.name)
        assertFalse(result.attended)
    }

    @Test
    fun `parses intensive with full payload`() {
        val raw = "__INTENSIVE__:1500|Петров|true"
        val result = SessionParser.parse(raw) as Session.Intensive
        assertEquals(1500.0, result.amount, 0.0)
        assertEquals("Петров", result.name)
        assertTrue(result.attended)
    }

    @Test
    fun `parses intensive with only amount and name (legacy)`() {
        val raw = "__INTENSIVE__:1500|Петров"
        val result = SessionParser.parse(raw) as Session.Intensive
        assertEquals(1500.0, result.amount, 0.0)
        assertEquals("Петров", result.name)
        // Legacy: без флага считаем посещённым.
        assertTrue(result.attended)
    }

    @Test
    fun `parses intensive with only amount (legacy)`() {
        val raw = "__INTENSIVE__:2000"
        val result = SessionParser.parse(raw) as Session.Intensive
        assertEquals(2000.0, result.amount, 0.0)
        assertEquals("", result.name)
        assertTrue(result.attended)
    }

    @Test
    fun `parses diagnostics with full payload`() {
        val raw = "__DIAGNOSTICS__:900|Сидоров|false"
        val result = SessionParser.parse(raw) as Session.Diagnostics
        assertEquals(900.0, result.amount, 0.0)
        assertEquals("Сидоров", result.name)
        assertFalse(result.attended)
    }

    @Test
    fun `isExtra detects intensive and diagnostics`() {
        assertTrue(SessionParser.isExtra("__INTENSIVE__:100|name|true"))
        assertTrue(SessionParser.isExtra("__DIAGNOSTICS__:100|name|true"))
        assertFalse(SessionParser.isExtra("Иванов|true"))
        assertFalse(SessionParser.isExtra("Иванов"))
    }

    @Test
    fun `getExtraAmount returns 0 for non-extra`() {
        assertEquals(0.0, SessionParser.getExtraAmount("Иванов|true"), 0.0)
    }

    @Test
    fun `getExtraAmount parses intensive amount`() {
        assertEquals(1500.0, SessionParser.getExtraAmount("__INTENSIVE__:1500|Петров|true"), 0.0)
    }

    @Test
    fun `isAttended works for all session kinds`() {
        assertTrue(SessionParser.isAttended("Иванов|true"))
        assertFalse(SessionParser.isAttended("Иванов|false"))
        assertTrue(SessionParser.isAttended("__INTENSIVE__:100|name|true"))
        assertFalse(SessionParser.isAttended("__DIAGNOSTICS__:100|name|false"))
    }

    @Test
    fun `SessionFormat round-trips student`() {
        val raw = SessionFormat.serializeStudent("Иванов", true)
        val parsed = SessionParser.parse(raw) as Session.Student
        assertEquals("Иванов", parsed.name)
        assertTrue(parsed.attended)
    }

    @Test
    fun `SessionFormat round-trips intensive`() {
        val raw = SessionFormat.serializeIntensive("1500", "Петров", true)
        val parsed = SessionParser.parse(raw) as Session.Intensive
        assertEquals(1500.0, parsed.amount, 0.0)
        assertEquals("Петров", parsed.name)
        assertTrue(parsed.attended)
    }

    @Test
    fun `SessionFormat round-trips intensive with time`() {
        val raw = SessionFormat.serializeIntensive(
            price = "3000",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
            time = "14:00-14:50",
        )
        val parsed = SessionParser.parse(raw) as Session.Intensive
        assertEquals(3000.0, parsed.amount, 0.0)
        assertEquals("14:00-14:50", parsed.time)
        assertEquals(AttendanceStatus.ARRIVED, parsed.status)
    }

    @Test
    fun `SessionFormat round-trips diagnostics`() {
        val raw = SessionFormat.serializeDiagnostics("900", "Сидоров", false)
        val parsed = SessionParser.parse(raw) as Session.Diagnostics
        assertEquals(900.0, parsed.amount, 0.0)
        assertEquals("Сидоров", parsed.name)
        assertFalse(parsed.attended)
    }

    @Test
    fun `parses extended student with arrived status and earnings flag`() {
        val raw = SessionFormat.serializeStudentExtended(
            name = "Иванов",
            status = AttendanceStatus.ARRIVED,
            time = "10:00-11:00",
        )
        val parsed = SessionParser.parse(raw) as Session.Student
        assertEquals(AttendanceStatus.ARRIVED, parsed.status)
        assertTrue(parsed.attended)
    }

    @Test
    fun `parses extended student with confirmed status without earnings`() {
        val raw = SessionFormat.serializeStudentExtended(
            name = "Петров",
            status = AttendanceStatus.CONFIRMED,
        )
        val parsed = SessionParser.parse(raw) as Session.Student
        assertEquals(AttendanceStatus.CONFIRMED, parsed.status)
        assertFalse(parsed.attended)
    }

    @Test
    fun `fromYClients maps api codes to app statuses`() {
        assertEquals(AttendanceStatus.EXPECTED, AttendanceStatus.fromYClients(0))
        assertEquals(AttendanceStatus.ARRIVED, AttendanceStatus.fromYClients(1))
        assertEquals(AttendanceStatus.CONFIRMED, AttendanceStatus.fromYClients(2))
        assertEquals(AttendanceStatus.CANCELLED, AttendanceStatus.fromYClients(-1))
    }

    @Test
    fun `resolveFromRecord prefers stronger visit status`() {
        val status = AttendanceStatus.resolveFromRecord(
            attendance = 0,
            visitAttendance = 1,
        )
        assertEquals(AttendanceStatus.ARRIVED, status)
    }

    @Test
    fun `resolveFromRecord prefers cancelled over waiting`() {
        val status = AttendanceStatus.resolveFromRecord(
            attendance = 0,
            visitAttendance = -1,
        )
        assertEquals(AttendanceStatus.CANCELLED, status)
    }

    @Test
    fun `parses diagnostics with cancelled status code`() {
        val raw = SessionFormat.serializeDiagnostics(
            price = "4500",
            name = "Белов Марк",
            status = AttendanceStatus.CANCELLED,
            time = "11:00-11:50",
        )
        val parsed = SessionParser.parse(raw) as Session.Diagnostics
        assertEquals(AttendanceStatus.CANCELLED, parsed.status)
        assertFalse(parsed.attended)
    }
}
