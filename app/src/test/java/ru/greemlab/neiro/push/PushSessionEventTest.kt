package ru.greemlab.neiro.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.greemlab.neiro.notifications.SessionEventType
import ru.greemlab.neiro.notifications.UpcomingSessionKind
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import java.time.LocalDate
import java.time.LocalTime

/**
 * Разбор payload сервера. Сервер живёт своей жизнью и может уйти вперёд по версии —
 * всё, чего приложение не понимает, обязано превращаться в null, а не в мусорное
 * уведомление.
 */
class PushSessionEventTest {

    @Test
    fun `подтверждение клиента разбирается со статусом CONFIRMED`() {
        val event = event(type = "CLIENT_CONFIRMED").toSessionEvent()

        assertEquals(SessionEventType.CLIENT_CONFIRMED, event?.type)
        assertEquals(AttendanceStatus.CONFIRMED, event?.session?.status)
        assertEquals("Иванова Мария", event?.session?.clientName)
        assertEquals(LocalDate.of(2026, 7, 30), event?.session?.date)
        assertEquals(LocalTime.of(15, 0), event?.session?.startTime)
        assertNull(event?.previous)
    }

    @Test
    fun `удаление помечает сессию удалённой`() {
        val event = event(type = "DELETED").toSessionEvent()

        assertEquals(SessionEventType.DELETED, event?.type)
        assertTrue(event?.session?.isMarkedDeleted == true)
    }

    @Test
    fun `незнакомый серверу тип события отбрасывается`() {
        assertNull(event(type = "CLIENT_TELEPORTED").toSessionEvent())
    }

    @Test
    fun `незнакомый вид занятия отбрасывается`() {
        assertNull(event(kind = "MASTERCLASS").toSessionEvent())
    }

    @Test
    fun `битая дата отбрасывается`() {
        assertNull(event(date = "30-07-2026").toSessionEvent())
    }

    @Test
    fun `битое время отбрасывается`() {
        assertNull(event(time = "15 часов").toSessionEvent())
    }

    @Test
    fun `перенос без prev_date отбрасывается`() {
        val event = event(type = "RESCHEDULED", prevDate = null, prevTime = "14:00")

        assertNull(event.toSessionEvent())
    }

    @Test
    fun `перенос без prev_time отбрасывается`() {
        val event = event(type = "RESCHEDULED", prevDate = "2026-07-29", prevTime = null)

        assertNull(event.toSessionEvent())
    }

    @Test
    fun `перенос с битым prev_time отбрасывается`() {
        val event = event(type = "RESCHEDULED", prevDate = "2026-07-29", prevTime = "вчера")

        assertNull(event.toSessionEvent())
    }

    @Test
    fun `перенос заполняет previous прежним слотом`() {
        val event = event(type = "RESCHEDULED", prevDate = "2026-07-29", prevTime = "14:00")
            .toSessionEvent()

        assertEquals(SessionEventType.RESCHEDULED, event?.type)
        assertEquals(LocalDate.of(2026, 7, 29), event?.previous?.date)
        assertEquals(LocalTime.of(14, 0), event?.previous?.startTime)
        // Новый слот — в session, прежний — в previous, иначе уведомление о переносе
        // покажет одну и ту же дату дважды.
        assertEquals(LocalDate.of(2026, 7, 30), event?.session?.date)
        assertEquals(LocalTime.of(15, 0), event?.session?.startTime)
    }

    @Test
    fun `диагностика сохраняет свой kind`() {
        val event = event(kind = "DIAGNOSTICS").toSessionEvent()

        assertEquals(UpcomingSessionKind.DIAGNOSTICS, event?.session?.kind)
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
