package ru.greemlab.neiro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate
import java.time.YearMonth

class YClientsCalendarSyncTest {

    @Test
    fun `default auto sync range is current and next calendar month`() {
        val month = YearMonth.now()
        val (start, end) = YClientsCalendarSync.defaultAutoSyncRange()
        assertEquals(month.atDay(1), start)
        assertEquals(month.plusMonths(1).atEndOfMonth(), end)
    }

    @Test
    fun `live refresh range matches daily edge months`() {
        assertEquals(
            YClientsCalendarSync.defaultAutoSyncRange(),
            YClientsCalendarSync.defaultLiveRefreshRange(),
        )
    }

    @Test
    fun `full live sync due when never synced`() {
        assertTrue(
            YClientsCalendarSync.isFullLiveSyncDue(
                lastFullLiveSyncEpochMillis = 0L,
                nowMillis = 1_000_000L,
            ),
        )
    }

    @Test
    fun `full live sync not due within interval`() {
        val now = YClientsCalendarSync.FULL_LIVE_SYNC_INTERVAL_MS * 10
        val last = now - YClientsCalendarSync.FULL_LIVE_SYNC_INTERVAL_MS + 1_000L
        assertFalse(
            YClientsCalendarSync.isFullLiveSyncDue(
                lastFullLiveSyncEpochMillis = last,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `full live sync due after interval`() {
        val now = YClientsCalendarSync.FULL_LIVE_SYNC_INTERVAL_MS * 10
        val last = now - YClientsCalendarSync.FULL_LIVE_SYNC_INTERVAL_MS
        assertTrue(
            YClientsCalendarSync.isFullLiveSyncDue(
                lastFullLiveSyncEpochMillis = last,
                nowMillis = now,
            ),
        )
    }

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

    // Инкрементальный live-опрос перечитывает текущий месяц авторитативно и
    // стирает всё, чего нет в ответе. Аудит 14.08.26 (S1): пустой ответ на этом
    // перезапросе выносил из календаря всех учеников месяца.

    @Test
    fun `current month refetch skipped when API empty but month has students`() {
        assertFalse(
            YClientsCalendarSync.shouldApplyCurrentMonthMerge(
                refetchedRecords = emptyList(),
                localDayData = mapOf(
                    LocalDate.of(2025, 5, 10) to listOf("Иванов|1"),
                ),
                month = YearMonth.of(2025, 5),
            ),
        )
    }

    @Test
    fun `current month refetch checks month bounds, not whole live range`() {
        // Ученики следующего месяца перезапросом не затрагиваются и прикрывать
        // пустой текущий месяц не должны.
        assertTrue(
            YClientsCalendarSync.shouldApplyCurrentMonthMerge(
                refetchedRecords = emptyList(),
                localDayData = mapOf(
                    LocalDate.of(2025, 6, 10) to listOf("Иванов|1"),
                ),
                month = YearMonth.of(2025, 5),
            ),
        )
    }

    @Test
    fun `current month refetch allowed when API returned records`() {
        assertTrue(
            YClientsCalendarSync.shouldApplyCurrentMonthMerge(
                refetchedRecords = listOf(fakeRecord()),
                localDayData = mapOf(
                    LocalDate.of(2025, 5, 10) to listOf("Иванов|1"),
                ),
                month = YearMonth.of(2025, 5),
            ),
        )
    }

    @Test
    fun `isInCurrentMonth matches calendar month boundaries`() {
        val month = YearMonth.of(2025, 6)
        assertTrue(
            YClientsCalendarSync.isInCurrentMonth(
                LocalDate.of(2025, 6, 1),
                month = month,
            ),
        )
        assertTrue(
            YClientsCalendarSync.isInCurrentMonth(
                LocalDate.of(2025, 6, 30),
                month = month,
            ),
        )
        assertFalse(
            YClientsCalendarSync.isInCurrentMonth(
                LocalDate.of(2025, 5, 31),
                month = month,
            ),
        )
        assertFalse(
            YClientsCalendarSync.isInCurrentMonth(
                LocalDate.of(2025, 7, 1),
                month = month,
            ),
        )
    }

    @Test
    fun `resolveIntensiveSyncAmount keeps fixed local amount`() {
        val local = Session.Intensive(
            amount = 5600.0,
            name = "Интенсив",
            attended = true,
            amountFixed = true,
        )
        val (amount, fixed) = YClientsCalendarSync.resolveIntensiveSyncAmount(
            localMatch = local,
            unitPrice = 1400.0,
            billableChildCount = 4,
        )
        assertEquals(5600.0, amount, 0.0)
        assertTrue(fixed)
    }

    @Test
    fun `resolveIntensiveSyncAmount uses rate times children when not fixed`() {
        val (amount, fixed) = YClientsCalendarSync.resolveIntensiveSyncAmount(
            localMatch = null,
            unitPrice = 1400.0,
            billableChildCount = 4,
        )
        assertEquals(5600.0, amount, 0.0)
        assertFalse(fixed)
    }

    @Test
    fun `unmatchedLocalIntensives keeps manual slots outside API times`() {
        val manual = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            amountFixed = true,
        )
        val apiSlot = SessionFormat.serializeIntensive(
            price = "2800",
            name = "Интенсив",
            status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.EXPECTED,
            time = "18:00-18:50",
        )
        val retained = YClientsCalendarSync.unmatchedLocalIntensives(
            existingLocal = listOf(manual, apiSlot, "Иванов|1"),
            apiIntensiveTimes = setOf("18:00-18:50"),
        )
        assertEquals(listOf(manual), retained)
    }

    @Test
    fun `full API answer drops local entries without a pair`() {
        val student = SessionFormat.serializeStudentExtended(
            name = "Иванов",
            status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.ARRIVED,
            time = "10:00-10:50",
        )
        val intensive = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            amountFixed = true,
        )
        val survived = YClientsCalendarSync.survivingLocalEntries(
            unmatched = listOf(student, intensive),
            dropUnmatched = true,
        )
        assertEquals(listOf(intensive), survived)
    }

    @Test
    fun `incremental answer keeps the rest of the day`() {
        // Догон по changed_after отдаёт одну изменившуюся запись; остальные
        // занятия дня в ответе не участвуют и удалению не подлежат.
        val untouched = listOf(
            SessionFormat.serializeStudentExtended(
                name = "Иванов",
                status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.ARRIVED,
                time = "10:00-10:50",
            ),
            SessionFormat.serializeStudentExtended(
                name = "Петров",
                status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.EXPECTED,
                time = "11:00-11:50",
            ),
        )
        val survived = YClientsCalendarSync.survivingLocalEntries(
            unmatched = untouched,
            dropUnmatched = false,
        )
        assertEquals(untouched, survived)
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
