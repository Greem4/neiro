package ru.greemlab.neiro.domain.pay

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.data.network.RecordData
import ru.greemlab.neiro.data.network.ServiceData

class EmployeePayResolverTest {

    private val defaultPay = 1400.0

    @Test
    fun `always returns profile rate while payment logic disabled`() {
        val record = record(cost = 1250.0, costToPay = 2500.0)
        assertEquals(defaultPay, EmployeePayResolver.resolveFromRecord(record, defaultPay), 0.0)
    }

    private fun record(cost: Double?, costToPay: Double? = null): RecordData =
        RecordData(
            id = 1L,
            companyId = 1,
            staffId = 1,
            date = "2024-01-15",
            datetime = "2024-01-15 10:00:00",
            createDate = null,
            comment = null,
            attendance = 1,
            seanceLength = null,
            length = null,
            visitAttendance = null,
            client = null,
            services = listOf(
                ServiceData(
                    id = 1L,
                    title = "Занятие",
                    cost = cost,
                    costToPay = costToPay,
                    discount = null,
                    amount = 1,
                ),
            ),
        )
}
