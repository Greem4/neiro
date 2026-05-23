package ru.greemlab.neiro.domain.pay

import ru.greemlab.neiro.data.network.RecordData
import ru.greemlab.neiro.data.network.ServiceData
import kotlin.math.roundToInt

/**
 * Расчёт выплаты сотруднику за занятие по данным YClients.
 *
 * [defaultEmployeePay] — ставка из настроек профиля («заморозка», сейчас обычно 1400 ₽).
 *
 * Логика:
 * 1. Если в услуге записи [ServiceData.cost] совпадает со ставкой — берём ставку из профиля.
 * 2. Иначе, если в «оплатной карточке» ([ServiceData.costToPay] или [ServiceData.cost])
 *    известная сумма — делим по правилам:
 *    - 2500, 2800 → пополам сотруднику;
 *    - 3000 → сотруднику [defaultEmployeePay], остальное компании;
 *    - близко к удвоенной ставке → пополам.
 * 3. Иначе, если в зарплате/услуге другая ставка — берём её как есть.
 * 4. Иначе — [defaultEmployeePay].
 */
object EmployeePayResolver {

    private val SPLIT_IN_HALF_TOTALS = setOf(2500, 2800)
    private const val FIXED_SPLIT_TOTAL = 3000

    fun resolveFromRecord(record: RecordData, defaultEmployeePay: Double): Double {
        if (defaultEmployeePay <= 0.0) {
            return fallbackFromRecord(record)
        }

        val services = regularServices(record)
        val salaryRate = primaryServiceCost(services)
        val clientPayment = extractClientPayment(services)

        if (clientPayment != null && clientPayment > 0.0) {
            val total = clientPayment.roundToInt()
            val shareFromCard = employeeShareFromClientPayment(clientPayment, defaultEmployeePay)
            // Известные суммы с карты (2500, 2800, 3000) — всегда по правилам деления.
            if (total in SPLIT_IN_HALF_TOTALS || total == FIXED_SPLIT_TOTAL) {
                return shareFromCard
            }
            // Иная оплата, дающая не базовую ставку (например удвоенная сумма → пополам).
            if (!moneyEquals(shareFromCard, defaultEmployeePay)) {
                return shareFromCard
            }
        }

        if (salaryRate != null && moneyEquals(salaryRate, defaultEmployeePay)) {
            return defaultEmployeePay
        }

        if (salaryRate != null && salaryRate > 0.0) {
            return salaryRate
        }

        return defaultEmployeePay
    }

    /**
     * Доля сотрудника из суммы, которую заплатил клиент (оплатная карточка).
     */
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
        return extractClientPayment(services)
            ?: primaryServiceCost(services)
            ?: 0.0
    }

    private fun regularServices(record: RecordData): List<ServiceData> {
        val diagKeywords = listOf("диагностика", "пробн", "тест")
        return record.services.orEmpty().filter { service ->
            diagKeywords.none { kw -> service.title?.contains(kw, ignoreCase = true) == true }
        }
    }

    private fun primaryServiceCost(services: List<ServiceData>): Double? =
        services.mapNotNull { it.cost }.firstOrNull { it > 0.0 }

    /** Сумма к оплате с карточки клиента; приоритет — [ServiceData.costToPay]. */
    private fun extractClientPayment(services: List<ServiceData>): Double? {
        val toPay = services.mapNotNull { it.costToPay }.filter { it > 0.0 }
        if (toPay.isNotEmpty()) return toPay.sum()
        val costs = services.mapNotNull { it.cost }.filter { it > 0.0 }
        if (costs.isNotEmpty()) return costs.sum()
        return null
    }

    private fun moneyEquals(a: Double, b: Double): Boolean =
        a.roundToInt() == b.roundToInt()
}
