package ru.greemlab.neiro.domain.pay

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.data.network.RecordData
import ru.greemlab.neiro.data.network.ServiceData

class EmployeePayResolverTest {

    private val defaultPay = 1400.0

    @Test
    fun `salary rate matches profile uses default`() {
        val record = record(cost = 1400.0, costToPay = 1400.0)
        assertEquals(defaultPay, EmployeePayResolver.resolveFromRecord(record, defaultPay), 0.0)
    }

    @Test
    fun `salary rate 1250 uses salary not profile`() {
        val record = record(cost = 1250.0)
        assertEquals(1250.0, EmployeePayResolver.resolveFromRecord(record, defaultPay), 0.0)
    }

    @Test
    fun `client payment 2500 gives half even when salary is 1400`() {
        val record = record(cost = 1400.0, costToPay = 2500.0)
        assertEquals(1250.0, EmployeePayResolver.resolveFromRecord(record, defaultPay), 0.0)
    }

    @Test
    fun `client payment 2800 gives half`() {
        val record = record(cost = 1400.0, costToPay = 2800.0)
        assertEquals(1400.0, EmployeePayResolver.resolveFromRecord(record, defaultPay), 0.0)
    }

    @Test
    fun `client payment 3000 gives profile rate not half`() {
        val record = record(cost = 1400.0, costToPay = 3000.0)
        assertEquals(1400.0, EmployeePayResolver.resolveFromRecord(record, defaultPay), 0.0)
    }

    @Test
    fun `employeeShareFromClientPayment known totals`() {
        assertEquals(1250.0, EmployeePayResolver.employeeShareFromClientPayment(2500.0, defaultPay), 0.0)
        assertEquals(1400.0, EmployeePayResolver.employeeShareFromClientPayment(2800.0, defaultPay), 0.0)
        assertEquals(1400.0, EmployeePayResolver.employeeShareFromClientPayment(3000.0, defaultPay), 0.0)
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
