package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.YearMonth

/**
 * Сводная статистика по всем записям пользователя.
 *
 * - [pastSessions] — занятия, которые уже прошли (по дате не позднее [today])
 * - [futureSessions] — будущие запланированные занятия
 * - [attendedSessions] — посещённые ученики (любая дата)
 * - [totalEarned] — фактически заработано (грязными)
 * - [netEarned] — чистыми (грязные минус [monthlyTaxAmount] за каждый месяц, в котором есть записи)
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
 * @param pricePerDiagnostics Стоимость одной диагностики.
 * @param today Сегодняшняя дата — нужна, чтобы разделить «прошлое» и «будущее».
 * @param monthlyTaxAmount Налог в рублях за каждый календарный месяц (как в профиле).
 */
internal fun computeProfileTotals(
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    today: LocalDate,
    monthlyTaxAmount: Double = 0.0,
    pricePerIntensiveChild: Double = 0.0,
): ProfileTotals {
    var pastSessions = 0
    var futureSessions = 0
    var attended = 0
    var earned = 0.0
    var expectedFuture = 0.0
    val grossByMonth = mutableMapOf<YearMonth, Double>()

    for ((date, sessions) in dayData) {
        val isFuture = date.isAfter(today)
        for (raw in sessions) {
            val session = SessionParser.parse(raw)
            if (session.isEffectivelyDeleted()) continue

            when (session) {
                is Session.Student -> {
                    if (isFuture) futureSessions++ else pastSessions++
                    val pay = pricePerSession
                    if (session.countsTowardEarnings()) {
                        attended++
                        earned += pay
                        addGross(grossByMonth, date, pay)
                    } else if (isFuture) {
                        expectedFuture += pay
                    }
                }

                is Session.Intensive -> {
                    if (session.countsTowardEarnings()) {
                        val intensiveAmount = session.totalAmount(
                            pricePerIntensiveChild,
                            onlyArrived = true,
                        )
                        earned += intensiveAmount
                        addGross(grossByMonth, date, intensiveAmount)
                    } else if (isFuture) {
                        expectedFuture += session.totalAmount(
                            pricePerIntensiveChild,
                            onlyArrived = false,
                        )
                    }
                }

                is Session.Diagnostics -> {
                    if (isFuture) futureSessions++ else pastSessions++
                    val price = if (pricePerDiagnostics > 0.0) pricePerDiagnostics else session.amount
                    if (session.countsTowardEarnings()) {
                        attended++
                        earned += price
                        addGross(grossByMonth, date, price)
                    } else if (isFuture) {
                        expectedFuture += price
                    }
                }
            }
        }
    }

    val monthsWithEntries = dayData.keys.map { YearMonth.from(it) }.toSet()
    val netEarned = monthsWithEntries.sumOf { month ->
        monthlyNetProfit(grossByMonth[month] ?: 0.0, monthlyTaxAmount)
    }

    return ProfileTotals(
        pastSessions = pastSessions,
        futureSessions = futureSessions,
        attendedSessions = attended,
        totalEarned = earned,
        netEarned = netEarned,
        expectedFromFuture = expectedFuture,
    )
}

private fun addGross(
    grossByMonth: MutableMap<YearMonth, Double>,
    date: LocalDate,
    amount: Double,
) {
    val month = YearMonth.from(date)
    grossByMonth[month] = (grossByMonth[month] ?: 0.0) + amount
}
