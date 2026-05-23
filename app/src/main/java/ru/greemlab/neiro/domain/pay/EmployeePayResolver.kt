package ru.greemlab.neiro.domain.pay

import ru.greemlab.neiro.data.network.RecordData
import ru.greemlab.neiro.data.network.ServiceData
import kotlin.math.roundToInt

// TODO: разобраться с оплатой — деление суммы с карты клиента (2500/2800 пополам, 3000 → ставка
//  из профиля), отделение cost (зарплата) от cost_to_pay (карта клиента).

/**
 * Расчёт выплаты сотруднику за занятие по данным YClients.
 *
 * Временно: всегда [defaultEmployeePay] из настроек профиля.
 */
object EmployeePayResolver {

    fun resolveFromRecord(record: RecordData, defaultEmployeePay: Double): Double =
        defaultEmployeePay

    fun hasClientCardPayment(record: RecordData): Boolean = false

    fun hasPayrollSalary(record: RecordData): Boolean = false

    fun employeeShareFromClientPayment(clientPayment: Double, defaultEmployeePay: Double): Double =
        defaultEmployeePay

    internal fun extractClientCardTotal(services: List<ServiceData>): Double? = null

    internal fun extractPayrollSalary(services: List<ServiceData>): Double? = null

    /*
    private val SPLIT_IN_HALF_TOTALS = setOf(2500, 2800)
    private const val FIXED_SPLIT_TOTAL = 3000
    private val CLIENT_CARD_TOTALS = setOf(2500, 2800, 3000)

    fun resolveFromRecord(record: RecordData, defaultEmployeePay: Double): Double {
        if (defaultEmployeePay <= 0.0) {
            return fallbackFromRecord(record)
        }
        val services = regularServices(record)
        extractClientCardTotal(services)?.let { clientTotal ->
            return employeeShareFromClientPayment(clientTotal, defaultEmployeePay)
        }
        extractPayrollSalary(services)?.let { payroll ->
            return payroll
        }
        return defaultEmployeePay
    }

    fun hasClientCardPayment(record: RecordData): Boolean =
        extractClientCardTotal(regularServices(record)) != null

    fun hasPayrollSalary(record: RecordData): Boolean =
        extractPayrollSalary(regularServices(record)) != null

    fun employeeShareFromClientPayment(clientPayment: Double, defaultEmployeePay: Double): Double {
        val total = clientPayment.roundToInt()
        return when (total) {
            in SPLIT_IN_HALF_TOTALS -> clientPayment / 2.0
            FIXED_SPLIT_TOTAL -> defaultEmployeePay
            else -> {
                val doubled = defaultEmployeePay * 2.0
                if (moneyEquals(clientPayment, doubled)) {
                    clientPayment / 2.0
                } else {
                    defaultEmployeePay
                }
            }
        }
    }

    private fun fallbackFromRecord(record: RecordData): Double {
        val services = regularServices(record)
        return extractClientCardTotal(services)
            ?: extractPayrollSalary(services)
            ?: 0.0
    }

    private fun regularServices(record: RecordData): List<ServiceData> {
        val diagKeywords = listOf("диагностика", "пробн", "тест")
        return record.services.orEmpty().filter { service ->
            diagKeywords.none { kw -> service.title?.contains(kw, ignoreCase = true) == true }
        }
    }

    internal fun extractClientCardTotal(services: List<ServiceData>): Double? {
        val toPay = services.mapNotNull { it.costToPay }.filter { it > 0.0 }
        if (toPay.isNotEmpty()) return toPay.sum()
        val costs = services.mapNotNull { it.cost }.filter { it > 0.0 }
        if (costs.isEmpty()) return null
        val sum = costs.sum()
        return if (sum.roundToInt() in CLIENT_CARD_TOTALS) sum else null
    }

    internal fun extractPayrollSalary(services: List<ServiceData>): Double? {
        val costs = services.mapNotNull { it.cost }.filter { it > 0.0 }
        if (costs.isEmpty()) return null
        val salary = costs.sum()
        if (salary.roundToInt() in CLIENT_CARD_TOTALS) return null
        return salary
    }

    private fun moneyEquals(a: Double, b: Double): Boolean =
        a.roundToInt() == b.roundToInt()
    */
}
