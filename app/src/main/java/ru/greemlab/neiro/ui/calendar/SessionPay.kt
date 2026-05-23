package ru.greemlab.neiro.ui.calendar

import ru.greemlab.neiro.domain.models.SessionPriceHistoryEntry
import java.time.LocalDate

// TODO: разобраться с оплатой — карта клиента (2500/2800/3000), расчёт зарплаты YClients,
//  история ставок, заморозка payAmount в записях. Сейчас везде только ставка из профиля.

/**
 * Фактическая выплата сотруднику за запись.
 *
 * Временно: всегда [defaultEmployeePay] из настроек профиля (например 1400 ₽).
 */
fun Session.employeePay(
    defaultEmployeePay: Double,
    sessionDate: LocalDate? = null,
    sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
): Double = defaultEmployeePay

/*
 * Прежняя логика (закомментировано):
 *
 * 1. [Session.Student.payAmount], зафиксированная при синхронизации или смене ставки;
 * 2. иначе ставка на дату занятия из истории профиля;
 * 3. иначе [defaultEmployeePay] из настроек.
 *
 * import ru.greemlab.neiro.domain.models.resolveSessionPriceOnDate
 *
 * fun Session.employeePay(...): Double {
 *     val fallback = if (sessionDate != null) {
 *         resolveSessionPriceOnDate(sessionDate, defaultEmployeePay, sessionPriceHistory)
 *     } else {
 *         defaultEmployeePay
 *     }
 *     return when (this) {
 *         is Session.Student -> payAmount?.takeIf { it > 0.0 } ?: fallback
 *         else -> defaultEmployeePay
 *     }
 * }
 */

/** Выплата по сырой строке календаря. */
fun employeePayForRaw(
    raw: String,
    defaultEmployeePay: Double,
    sessionDate: LocalDate? = null,
    sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
): Double = defaultEmployeePay
