package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek

/**
 * Модель данных профиля пользователя.
 *
 * @param activityType Вид деятельности (например, "Репетитор", "Тренер").
 * @param workingDays Набор рабочих дней недели.
 * @param pricePerSession Ставка сотрудника за одно занятие (базовая «заморозка» для расчёта).
 * @param pricePerDiagnostics Цена за диагностику.
 * @param monthlyTaxAmount Налог в рублях за месяц.
 * @param isRegistered Флаг завершения первичной настройки.
 */
@Immutable
data class UserProfile(
    val name: String = "",
    val activityType: String = "",
    val workingDays: Set<DayOfWeek> = emptySet(),
    val pricePerSession: Double = 0.0,
    val pricePerDiagnostics: Double = 0.0,
    val monthlyTaxAmount: Double = 0.0,
    val isRegistered: Boolean = false,
)
