package ru.greemlab.neiro.domain.models

import java.time.DayOfWeek

/**
 * Модель данных профиля пользователя.
 *
 * @param activityType Вид деятельности (например, "Репетитор", "Тренер").
 * @param workingDays Набор рабочих дней недели.
 * @param pricePerSession Цена за одно занятие (запись в списке).
 */
data class UserProfile(
    val name: String = "",
    val activityType: String = "",
    val workingDays: Set<DayOfWeek> = emptySet(),
    val pricePerSession: Double = 0.0,
    val monthlyTaxAmount: Double = 0.0, // Налог в рублях
    val isRegistered: Boolean = false // Флаг завершения регистрации
)
