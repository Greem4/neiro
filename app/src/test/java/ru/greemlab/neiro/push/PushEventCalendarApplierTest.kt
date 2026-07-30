package ru.greemlab.neiro.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import java.time.LocalDate

/**
 * Применение события к локальному календарю. Ошибка здесь означает разъехавшийся
 * календарь при закрытом приложении — увидеть её без теста можно только глазами
 * пользователя.
 */
class PushEventCalendarApplierTest {

    private val date = LocalDate.of(2026, 7, 30)
    private val prevDate = LocalDate.of(2026, 7, 29)
    private val priceDiagnostics = 3000.0

    @Test
    fun `подтверждение меняет статус найденной записи`() {
        val dayData = mapOf(date to listOf(student("Иванова Мария", "15:00-15:50")))

        val result = applyOne(dayData, event(type = "CLIENT_CONFIRMED"))

        assertEquals(AttendanceStatus.CONFIRMED, statusOf(result, date, 0))
    }

    @Test
    fun `приход меняет статус на ARRIVED`() {
        val dayData = mapOf(date to listOf(student("Иванова Мария", "15:00-15:50")))

        val result = applyOne(dayData, event(type = "CLIENT_ARRIVED"))

        assertEquals(AttendanceStatus.ARRIVED, statusOf(result, date, 0))
    }

    @Test
    fun `отмена меняет статус на CANCELLED и запись остаётся`() {
        val dayData = mapOf(date to listOf(student("Иванова Мария", "15:00-15:50")))

        val result = applyOne(dayData, event(type = "CANCELLED"))

        assertEquals(1, result[date]?.size)
        assertEquals(AttendanceStatus.CANCELLED, statusOf(result, date, 0))
    }

    @Test
    fun `удаление убирает запись из дня`() {
        val dayData = mapOf(
            date to listOf(
                student("Иванова Мария", "15:00-15:50"),
                student("Петров Пётр", "16:00-16:50"),
            ),
        )

        val result = applyOne(dayData, event(type = "DELETED"))

        assertEquals(1, result[date]?.size)
        assertEquals("Петров Пётр", nameOf(result, date, 0))
    }

    @Test
    fun `новая запись добавляется с временем занятия`() {
        val result = applyOne(emptyMap(), event(type = "NEW_BOOKING"))

        val session = SessionParser.parse(result[date]!!.single()) as Session.Student
        assertEquals("Иванова Мария", session.name)
        assertEquals("15:00-15:50", session.time)
        assertEquals(AttendanceStatus.EXPECTED, session.status)
    }

    @Test
    fun `повторное NEW_BOOKING не дублирует запись`() {
        val once = applyOne(emptyMap(), event(type = "NEW_BOOKING"))
        val twice = applyOne(once, event(type = "NEW_BOOKING"))

        assertEquals(1, twice[date]?.size)
    }

    @Test
    fun `новая диагностика добавляется с ценой из профиля`() {
        val result = applyOne(emptyMap(), event(type = "NEW_BOOKING", kind = "DIAGNOSTICS"))

        val session = SessionParser.parse(result[date]!!.single()) as Session.Diagnostics
        assertEquals("Иванова Мария", session.name)
        assertEquals(priceDiagnostics, session.amount, 0.01)
    }

    @Test
    fun `перенос убирает запись со старой даты и ставит на новую`() {
        val dayData = mapOf(prevDate to listOf(student("Иванова Мария", "14:00-14:50")))

        val result = applyOne(
            dayData,
            event(type = "RESCHEDULED", prevDate = "2026-07-29", prevTime = "14:00"),
        )

        assertTrue(result[prevDate].orEmpty().isEmpty())
        assertEquals("Иванова Мария", nameOf(result, date, 0))
    }

    @Test
    fun `перенос без prev полей ничего не меняет`() {
        val dayData = mapOf(prevDate to listOf(student("Иванова Мария", "14:00-14:50")))

        val result = applyOne(dayData, event(type = "RESCHEDULED"))

        assertEquals(dayData, result)
    }

    @Test
    fun `перенос с неизвестной прежней записью ничего не меняет`() {
        val dayData = mapOf(prevDate to listOf(student("Петров Пётр", "14:00-14:50")))

        val result = applyOne(
            dayData,
            event(type = "RESCHEDULED", prevDate = "2026-07-29", prevTime = "14:00"),
        )

        assertEquals(dayData, result)
    }

    @Test
    fun `интенсив событием не правится`() {
        val raw = SessionFormat.serializeIntensive(
            price = "5600",
            name = "Интенсив",
            status = AttendanceStatus.EXPECTED,
            time = "15:00-15:50",
            children = listOf(
                Session.IntensiveChild(name = "Иванова Мария", status = AttendanceStatus.EXPECTED),
            ),
        )
        val dayData = mapOf(date to listOf(raw))

        val result = applyOne(dayData, event(type = "CLIENT_CONFIRMED"))

        // Слияние интенсива по детям делает только полный синк (§5.6).
        assertEquals(dayData, result)
    }

    @Test
    fun `событие о незнакомой записи календарь не трогает`() {
        val dayData = mapOf(date to listOf(student("Петров Пётр", "15:00-15:50")))

        val result = applyOne(dayData, event(type = "CLIENT_CONFIRMED"))

        assertEquals(dayData, result)
    }

    @Test
    fun `незнакомый тип события календарь не трогает`() {
        val dayData = mapOf(date to listOf(student("Иванова Мария", "15:00-15:50")))

        val result = applyOne(dayData, event(type = "CLIENT_TELEPORTED"))

        assertEquals(dayData, result)
    }

    @Test
    fun `битая дата в событии календарь не трогает`() {
        val dayData = mapOf(date to listOf(student("Иванова Мария", "15:00-15:50")))

        val result = applyOne(dayData, event(type = "CLIENT_CONFIRMED", date = "30.07.2026"))

        assertEquals(dayData, result)
    }

    @Test
    fun `подтверждение не задевает соседние записи дня`() {
        val dayData = mapOf(
            date to listOf(
                student("Петров Пётр", "14:00-14:50"),
                student("Иванова Мария", "15:00-15:50"),
                student("Сидорова Анна", "16:00-16:50"),
            ),
        )

        val result = applyOne(dayData, event(type = "CLIENT_CONFIRMED"))

        assertEquals(3, result[date]?.size)
        assertEquals(AttendanceStatus.EXPECTED, statusOf(result, date, 0))
        assertEquals(AttendanceStatus.CONFIRMED, statusOf(result, date, 1))
        assertEquals(AttendanceStatus.EXPECTED, statusOf(result, date, 2))
    }

    private fun applyOne(
        dayData: Map<LocalDate, List<String>>,
        event: PushSessionEvent,
    ): Map<LocalDate, List<String>> =
        PushEventCalendarApplier.applyOne(dayData, event, priceDiagnostics)

    private fun student(name: String, time: String) =
        SessionFormat.serializeStudentExtended(
            name = name,
            status = AttendanceStatus.EXPECTED,
            time = time,
        )

    private fun statusOf(dayData: Map<LocalDate, List<String>>, date: LocalDate, index: Int) =
        SessionParser.parse(dayData[date]!![index]).status

    private fun nameOf(dayData: Map<LocalDate, List<String>>, date: LocalDate, index: Int) =
        when (val session = SessionParser.parse(dayData[date]!![index])) {
            is Session.Student -> session.name
            is Session.Extra -> session.name
        }

    private fun event(
        type: String = "CLIENT_CONFIRMED",
        kind: String = "LESSON",
        date: String = "2026-07-30",
        time: String = "15:00",
        prevDate: String? = null,
        prevTime: String? = null,
    ) = PushSessionEvent(
        id = 1L,
        staffId = 3618433,
        type = type,
        clientName = "Иванова Мария",
        date = date,
        time = time,
        kind = kind,
        prevDate = prevDate,
        prevTime = prevTime,
    )
}
