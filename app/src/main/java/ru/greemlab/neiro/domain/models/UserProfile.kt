package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek

/**
 * Модель данных профиля пользователя.
 *
 * @param activityType Вид деятельности (например, "Репетитор", "Тренер").
 * @param workingDays Набор рабочих дней недели.
 * @param pricePerSession Ставка сотрудника за одно занятие.
 * @param pricePerDiagnostics Цена за диагностику.
 * @param pricePerIntensiveChild Ставка за одного ребёнка в интенсиве.
 * @param monthlyTaxAmount Налог в рублях за месяц. Признака происхождения не
 *   имеет: YClients про налог не знает, он всегда ручной.
 * @param sessionPriceOrigin Кто выставил [pricePerSession]: приложение или человек.
 *   АВТО обновляется при синке, РУЧНОЕ — никогда (FOUNDATION 6.1).
 * @param diagnosticsPriceOrigin То же для [pricePerDiagnostics].
 * @param intensivePriceOrigin То же для [pricePerIntensiveChild].
 * @param salaryOnCard Устаревшее поле: сумма на карту, если не заданы части ниже.
 * @param salaryAdvanceOnCard Аванс на карту (примерная сумма, каждый месяц).
 * @param salaryMainOnCard Основная выплата на карту (примерная сумма, каждый месяц).
 * @param showAvatar Показывать аватар (если выключено — показывается логотип).
 * @param isRegistered Флаг завершения первичной настройки.
 */
@Immutable
data class UserProfile(
    val name: String = "",
    val activityType: String = "",
    val workingDays: Set<DayOfWeek> = emptySet(),
    val pricePerSession: Double = 0.0,
    val pricePerDiagnostics: Double = 0.0,
    val pricePerIntensiveChild: Double = 0.0,
    val monthlyTaxAmount: Double = 0.0,
    val sessionPriceOrigin: PriceOrigin = PriceOrigin.AUTO,
    val diagnosticsPriceOrigin: PriceOrigin = PriceOrigin.AUTO,
    val intensivePriceOrigin: PriceOrigin = PriceOrigin.AUTO,
    val salaryOnCard: Double = 33_000.0,
    val salaryAdvanceOnCard: Double = 11_206.0,
    val salaryMainOnCard: Double = 21_854.0,
    val showAvatar: Boolean = true,
    val isRegistered: Boolean = false,
)

/** Сумма переводов на карту: аванс + зарплата, иначе [salaryOnCard]. */
fun UserProfile.totalSalaryOnCard(): Double {
    val fromParts = salaryAdvanceOnCard + salaryMainOnCard
    return if (fromParts > 0.0) fromParts else salaryOnCard
}
