package ru.greemlab.neiro.ui.calendar

/**
 * Фактическая выплата сотруднику за запись.
 *
 * Для обычного занятия берётся [Session.Student.payAmount], зафиксированная при синхронизации;
 * для остальных типов — переданный [defaultEmployeePay] (не используется).
 */
fun Session.employeePay(defaultEmployeePay: Double): Double = when (this) {
    is Session.Student -> payAmount?.takeIf { it > 0.0 } ?: defaultEmployeePay
    else -> defaultEmployeePay
}

/** Выплата по сырой строке календаря (без повторного парсинга лишний раз — один parse). */
fun employeePayForRaw(raw: String, defaultEmployeePay: Double): Double =
    SessionParser.parse(raw).employeePay(defaultEmployeePay)
