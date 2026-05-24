package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek

/**
 * Модель данных профиля пользователя.
 *
 * @param activityType Вид деятельности (например, "Репетитор", "Тренер").
 * @param workingDays Набор рабочих дней недели.
 * @param pricePerSession Текущая ставка сотрудника за одно занятие.
 * @param sessionPriceHistory История смены ставки (для расчёта прибыли в прошлых месяцах).
 * @param pricePerDiagnostics Цена за диагностику.
 * @param monthlyTaxAmount Налог в рублях за месяц.
 * @param showAvatar Показывать аватар (если выключено — показывается логотип).
 * @param isRegistered Флаг завершения первичной настройки.
 */
@Immutable
data class UserProfile(
    val name: String = "",
    val activityType: String = "",
    val workingDays: Set<DayOfWeek> = emptySet(),
    val pricePerSession: Double = 0.0,
    val sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
    val pricePerDiagnostics: Double = 0.0,
    val monthlyTaxAmount: Double = 0.0,
    val showAvatar: Boolean = true,
    val isRegistered: Boolean = false,
)
