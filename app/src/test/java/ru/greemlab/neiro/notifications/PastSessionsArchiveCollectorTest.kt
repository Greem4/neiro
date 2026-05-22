package ru.greemlab.neiro.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate

class PastSessionsArchiveCollectorTest {

    @Test
    fun `daysNeedingArchive returns empty when profile not registered`() {
        val today = LocalDate.of(2026, 5, 23)
        val yesterday = today.minusDays(1)
        val result = PastSessionsArchiveCollector.daysNeedingArchive(
            dayData = mapOf(yesterday to listOf("Иван|1|10:00-10:50||")),
            archivedDates = emptySet(),
            profile = UserProfile(name = "Тест", isRegistered = false),
            today = today,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `daysNeedingArchive skips archived and future days`() {
        val today = LocalDate.of(2026, 5, 23)
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val raw = SessionFormat.serializeStudentExtended(
            name = "Анна",
            status = AttendanceStatus.ARRIVED,
            time = "10:00-10:50",
            phone = "",
            comment = "",
        )

        val result = PastSessionsArchiveCollector.daysNeedingArchive(
            dayData = mapOf(
                yesterday to listOf(raw),
                tomorrow to listOf(raw),
            ),
            archivedDates = setOf(yesterday),
            profile = UserProfile(name = "Тест", isRegistered = true),
            today = today,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `todayNeedingArchive returns today when sessions exist and not archived`() {
        val today = LocalDate.of(2026, 5, 23)
        val raw = SessionFormat.serializeStudentExtended(
            name = "Пётр",
            status = AttendanceStatus.ARRIVED,
            time = "12:00-12:50",
            phone = "",
            comment = "",
        )

        val result = PastSessionsArchiveCollector.todayNeedingArchive(
            dayData = mapOf(today to listOf(raw)),
            archivedDates = emptySet(),
            profile = UserProfile(name = "Тест", isRegistered = true),
            today = today,
        )

        assertEquals(today, result)
    }

    @Test
    fun `todayNeedingArchive returns null when already archived`() {
        val today = LocalDate.of(2026, 5, 23)
        val raw = SessionFormat.serializeStudentExtended(
            name = "Пётр",
            status = AttendanceStatus.ARRIVED,
            time = "12:00-12:50",
            phone = "",
            comment = "",
        )

        val result = PastSessionsArchiveCollector.todayNeedingArchive(
            dayData = mapOf(today to listOf(raw)),
            archivedDates = setOf(today),
            profile = UserProfile(name = "Тест", isRegistered = true),
            today = today,
        )

        assertNull(result)
    }

    @Test
    fun `todayNeedingArchive returns null when no sessions today`() {
        val today = LocalDate.of(2026, 5, 23)

        val result = PastSessionsArchiveCollector.todayNeedingArchive(
            dayData = emptyMap(),
            archivedDates = emptySet(),
            profile = UserProfile(name = "Тест", isRegistered = true),
            today = today,
        )

        assertNull(result)
    }
}
