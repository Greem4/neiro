package ru.greemlab.neiro.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.calendar.computeDayStats
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
            status = AttendanceStatus.EXPECTED,
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
            status = AttendanceStatus.EXPECTED,
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
            status = AttendanceStatus.ARRIVED,
            time = "19:00-19:50",
            amountFixed = true,
        )
        val apiSlot = SessionFormat.serializeIntensive(
            price = "2800",
            name = "Интенсив",
            status = AttendanceStatus.EXPECTED,
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
            status = AttendanceStatus.ARRIVED,
            time = "10:00-10:50",
        )
        val intensive = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = AttendanceStatus.ARRIVED,
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
                status = AttendanceStatus.ARRIVED,
                time = "10:00-10:50",
            ),
            SessionFormat.serializeStudentExtended(
                name = "Петров",
                status = AttendanceStatus.EXPECTED,
                time = "11:00-11:50",
            ),
        )
        val survived = YClientsCalendarSync.survivingLocalEntries(
            unmatched = untouched,
            dropUnmatched = false,
        )
        assertEquals(untouched, survived)
    }

    // ─── Занятое время без услуги ───────────────────────────────────────────
    //
    // В журнале так занимают время: слот подписывают одним именем («Кац»,
    // «Сапожникова») или оставляют памятку («ДИАГНОСТИКИ, если нет никого…»).
    // Услуги у такой записи нет, а `paid_full` YClients ставит в 1 — платить
    // нечего. Пока приложение читало это как оплату, 10.09.2026 выходило
    // «Занятий 7, итог 10 500 ₽» там, где YClients начислил 9 000 ₽.

    @Test
    fun `услуга — признак того, что оплате есть чем быть`() {
        assertFalse(YClientsCalendarSync.hasPayableService(fakeRecord().copy(services = null)))
        assertFalse(YClientsCalendarSync.hasPayableService(fakeRecord().copy(services = emptyList())))
        assertTrue(
            YClientsCalendarSync.hasPayableService(
                fakeRecord().copy(services = listOf(fakeService())),
            ),
        )
    }

    @Test
    fun `без услуги оплата не читается, статус остаётся настоящим`() {
        val waiting = fakeRecord().copy(services = emptyList(), attendance = 0, paidFull = 1)
        val absent = fakeRecord().copy(services = emptyList(), attendance = -1, paidFull = 1)
        assertEquals(AttendanceStatus.EXPECTED, YClientsCalendarSync.attendanceStatusOf(waiting))
        assertEquals(AttendanceStatus.CANCELLED, YClientsCalendarSync.attendanceStatusOf(absent))
    }

    @Test
    fun `настоящее занятие читается как раньше`() {
        val paid = fakeRecord().copy(services = listOf(fakeService()), attendance = 1, paidFull = 1)
        val arrived = fakeRecord().copy(services = listOf(fakeService()), attendance = 1, paidFull = 0)
        assertEquals(AttendanceStatus.PAID, YClientsCalendarSync.attendanceStatusOf(paid))
        assertEquals(AttendanceStatus.ARRIVED, YClientsCalendarSync.attendanceStatusOf(arrived))
    }

    @Test
    fun `признак не-занятия переживает сериализацию и смену статуса`() {
        val raw = SessionFormat.serializeStudentExtended(
            name = "Кац",
            status = AttendanceStatus.CANCELLED,
            time = "17:00-17:50",
            notCounted = true,
        )
        val session = SessionParser.parse(raw) as Session.Student
        assertTrue(session.notCounted)
        assertTrue(session.isNotCounted)
        // Статус настоящий: «не пришёл» так и остаётся «не пришёл».
        assertEquals(AttendanceStatus.CANCELLED, session.status)

        val changed = SessionParser.parse(SessionParser.withStatus(raw, AttendanceStatus.PAID))
        assertTrue(changed.isNotCounted)
        assertFalse(changed.countsTowardEarnings())
    }

    @Test
    fun `день 10 сентября 2026 — шесть занятий и 9000, как в YClients`() {
        // Живой день: шесть оплаченных занятий, отменённый «Моторнов» с услугой,
        // «Кац» — слот, занятый одним именем, и памятка «ДИАГНОСТИКИ» в 18:00.
        // YClients начислил 9 000 ₽; приложение показывало 10 500 ₽.
        val paid = (1..6).map { index ->
            fakeRecord().copy(id = index.toLong(), services = listOf(fakeService()), attendance = 1, paidFull = 1)
        }
        val motornov = fakeRecord().copy(id = 7L, services = listOf(fakeService()), attendance = -1, paidFull = 0)
        val kats = fakeRecord().copy(id = 8L, services = emptyList(), attendance = -1, paidFull = 1)
        val note = fakeRecord().copy(id = 9L, services = emptyList(), attendance = 0, paidFull = 1)

        val stats = dayStats(paid + motornov + kats + note)

        assertEquals(6, stats.totalLessons)        // «Занятий 6», а не 7
        assertEquals(6, stats.attendedLessons)
        assertEquals(9000.0, stats.earned, 0.0)    // столько же, сколько в YClients
        assertEquals(0.0, stats.expected, 0.0)
        // В «Потеряно на отменах» — только настоящая отмена с услугой.
        assertEquals(1500.0, stats.lost, 0.0)
    }

    @Test
    fun `день 11 сентября 2026 — все цифры по нулям`() {
        // Шесть записей «не пришёл» с услугой, «Пирогов» без услуги и памятка
        // «Если Филиппов отменится…». YClients начислил 0.
        val cancelled = (1..6).map { index ->
            fakeRecord().copy(id = index.toLong(), services = listOf(fakeService()), attendance = -1, paidFull = 0)
        }
        val pirogov = fakeRecord().copy(id = 7L, services = emptyList(), attendance = -1, paidFull = 1)
        val note = fakeRecord().copy(id = 8L, services = emptyList(), attendance = 0, paidFull = 1)

        val stats = dayStats(cancelled + pirogov + note)

        assertEquals(0, stats.totalLessons)
        assertEquals(0, stats.attendedLessons)
        assertEquals(0.0, stats.earned, 0.0)
        assertEquals(0.0, stats.expected, 0.0)
        assertEquals(6 * 1500.0, stats.lost, 0.0)
    }

    // ─── Имя записи: клиент, а если его нет — комментарий ────────────────────

    @Test
    fun `имя берётся у клиента, комментарий остаётся комментарием`() {
        val record = fakeRecord().copy(
            client = fakeClient("Кожин Роман, 1,8 года"),
            comment = "принесут договор",
        )
        assertEquals("Кожин Роман, 1,8 года", YClientsCalendarSync.recordDisplayName(record))
        assertEquals("принесут договор", YClientsCalendarSync.recordComment(record))
    }

    @Test
    fun `без клиента имя берётся из комментария`() {
        // «Пирогов», «Сапожникова», «Савостьянов», «Чудаев» — слоты, занятые
        // подписью в комментарии. Раньше имя выходило пустым, и запись
        // отсеивалась до попадания в день: в журнале слот занят, в приложении
        // пусто.
        val record = fakeRecord().copy(client = null, comment = "Сапожникова\n")
        assertEquals("Сапожникова", YClientsCalendarSync.recordDisplayName(record))
        // Второй раз та же подпись в комментарий не уходит.
        assertEquals("", YClientsCalendarSync.recordComment(record))
    }

    @Test
    fun `без клиента берётся первая непустая строка комментария`() {
        val record = fakeRecord().copy(client = null, comment = "\n  Пирогов  \nвторая строка")
        assertEquals("Пирогов", YClientsCalendarSync.recordDisplayName(record))
    }

    @Test
    fun `без клиента и без комментария имени нет`() {
        val record = fakeRecord().copy(client = null, comment = "   ")
        assertEquals("", YClientsCalendarSync.recordDisplayName(record))
    }

    @Test
    fun `подпись из комментария видна, но в счётчики не идёт`() {
        val pirogov = fakeRecord().copy(
            client = null,
            comment = "Пирогов",
            services = emptyList(),
            attendance = -1,
            paidFull = 1,
        )
        val raw = SessionFormat.serializeStudentExtended(
            name = YClientsCalendarSync.recordDisplayName(pirogov),
            status = YClientsCalendarSync.attendanceStatusOf(pirogov),
            time = "16:00-17:00",
            comment = YClientsCalendarSync.recordComment(pirogov),
            notCounted = !YClientsCalendarSync.hasPayableService(pirogov),
        )
        val session = SessionParser.parse(raw) as Session.Student

        assertEquals("Пирогов", session.name)                 // в дне видна
        assertEquals(AttendanceStatus.CANCELLED, session.status)
        assertTrue(session.isNotCounted)                      // но ни во что не считается
        assertFalse(session.countsTowardEarnings())
        assertFalse(session.countsAsAttended())
        assertFalse(SessionParser.countsAsCalendarLesson(raw))
    }

    private fun fakeClient(name: String) = ru.greemlab.neiro.data.network.ClientData(
        id = 1L,
        name = name,
        surname = null,
        patronymic = null,
        displayName = null,
        phone = null,
        email = null,
        successVisitsCount = null,
        failVisitsCount = null,
    )

    /** День из записей API — теми же правилами, что и синхронизация. */
    private fun dayStats(records: List<ru.greemlab.neiro.data.network.RecordData>) =
        computeDayStats(
            records.mapIndexed { index, record ->
                SessionFormat.serializeStudentExtended(
                    name = "Запись $index",
                    status = YClientsCalendarSync.attendanceStatusOf(record),
                    time = "1$index:00-1$index:50",
                    notCounted = !YClientsCalendarSync.hasPayableService(record),
                )
            },
            rates = EarningsContext(pricePerSession = 1500.0),
        )

    private fun fakeService() = ru.greemlab.neiro.data.network.ServiceData(
        id = 1L,
        title = "Нейрокоррекция",
        cost = 1500.0,
        costToPay = 1500.0,
        firstCost = 1500.0,
        costPerUnit = 1500.0,
        discount = 0.0,
        amount = 1,
    )

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
