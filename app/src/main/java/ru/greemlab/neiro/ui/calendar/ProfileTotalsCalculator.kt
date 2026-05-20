package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * Сводная статистика по всем записям пользователя.
 *
 * - [pastSessions] — занятия, которые уже прошли (по дате не позднее [today])
 * - [futureSessions] — будущие запланированные занятия
 * - [attendedSessions] — посещённые ученики (любая дата)
 * - [totalEarned] — фактически заработано (грязными)
 * - [netEarned] — чистыми (грязные минус налог за месяц)
 * - [expectedFromFuture] — деньги, ожидаемые от будущих занятий
 */
@Immutable
data class ProfileTotals(
    val pastSessions: Int = 0,
    val futureSessions: Int = 0,
    val attendedSessions: Int = 0,
    val totalEarned: Double = 0.0,
    val netEarned: Double = 0.0,
    val expectedFromFuture: Double = 0.0,
) {
    companion object {
        val Empty = ProfileTotals()
    }
}

/**
 * Считает сводку по всем записям за один проход.
 *
 * @param dayData Полные данные календаря (дата → список записей).
 * @param pricePerSession Стоимость одного занятия ученика.
 * @param today Сегодняшняя дата — нужна, чтобы разделить «прошлое» и «будущее».
 * @param monthlyTaxAmount Налог за месяц — вычитается из [totalEarned] для расчёта [ProfileTotals.netEarned].
 */
internal fun computeProfileTotals(
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    today: LocalDate,
    monthlyTaxAmount: Double = 0.0,
): ProfileTotals {
    var pastSessions = 0
    var futureSessions = 0
    var attended = 0
    var earned = 0.0
    var expectedFuture = 0.0

    for ((date, sessions) in dayData) {
        val isFuture = date.isAfter(today)
        for (raw in sessions) {
            when (val session = SessionParser.parse(raw)) {
                is Session.Student -> {
                    if (isFuture) futureSessions++ else pastSessions++
                    if (session.attended) {
                        attended++
                        earned += pricePerSession
                    } else if (isFuture) {
                        expectedFuture += pricePerSession
                    }
                }

                is Session.Intensive -> {
                    if (session.attended) earned += session.amount
                    else if (isFuture) expectedFuture += session.amount
                }

                is Session.Diagnostics -> {
                    if (session.attended) earned += session.amount
                    else if (isFuture) expectedFuture += session.amount
                }
            }
        }
    }

    return ProfileTotals(
        pastSessions = pastSessions,
        futureSessions = futureSessions,
        attendedSessions = attended,
        totalEarned = earned,
        netEarned = (earned - monthlyTaxAmount).coerceAtLeast(0.0),
        expectedFromFuture = expectedFuture,
    )
}
