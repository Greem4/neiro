package ru.greemlab.neiro.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class IntensiveSessionUtilsTest {

    @Test
    fun `fixed amount is used as whole sum even with profile rate`() {
        val session = Session.Intensive(
            amount = 5600.0,
            name = "Интенсив",
            attended = true,
            status = AttendanceStatus.ARRIVED,
            amountFixed = true,
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Маша", AttendanceStatus.ARRIVED),
            ),
        )
        assertEquals(5600.0, session.totalAmount(1400.0, onlyArrived = true), 0.0)
        assertEquals(5600.0, session.totalAmount(1400.0, onlyArrived = false), 0.0)
    }

    @Test
    fun `api intensive without fixed uses rate times children`() {
        val session = Session.Intensive(
            amount = 0.0,
            name = "Интенсив",
            attended = true,
            status = AttendanceStatus.EXPECTED,
            amountFixed = false,
            children = listOf(
                Session.IntensiveChild("Дима", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Маша", AttendanceStatus.EXPECTED),
                Session.IntensiveChild("Петя", AttendanceStatus.CANCELLED),
            ),
        )
        assertEquals(1400.0, session.totalAmount(1400.0, onlyArrived = true), 0.0)
        assertEquals(2800.0, session.totalAmount(1400.0, onlyArrived = false), 0.0)
    }

    @Test
    fun `manual intensive without children uses stored amount`() {
        val session = Session.Intensive(
            amount = 5600.0,
            name = "Интенсив",
            attended = true,
            status = AttendanceStatus.ARRIVED,
            amountFixed = true,
        )
        assertEquals(5600.0, session.totalAmount(1400.0, onlyArrived = true), 0.0)
    }
}
