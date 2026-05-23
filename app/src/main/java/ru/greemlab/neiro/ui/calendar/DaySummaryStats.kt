package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable
import ru.greemlab.neiro.domain.models.SessionPriceHistoryEntry
import java.time.LocalDate

/** Сводка по одному дню для панели под календарём. */
@Immutable
data class DaySummaryStats(
    val totalLessons: Int = 0,
    val attendedLessons: Int = 0,
    val earned: Double = 0.0,
    val expected: Double = 0.0,
    val lost: Double = 0.0,
)

internal fun computeDayStats(
    sessions: List<String>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    sessionDate: LocalDate? = null,
    sessionPriceHistory: List<SessionPriceHistoryEntry> = emptyList(),
): DaySummaryStats {
    var totalLessons = 0
    var attendedLessons = 0
    var earned = 0.0
    var expected = 0.0
    var lost = 0.0

    for (raw in sessions) {
        val session = SessionParser.parse(raw)
        
        if (session.isEffectivelyDeleted()) {
            val price = when (session) {
                is Session.Intensive -> session.amount
                is Session.Diagnostics -> if (pricePerDiagnostics > 0.0) pricePerDiagnostics else session.amount
                is Session.Student -> session.employeePay(pricePerSession, sessionDate, sessionPriceHistory)
            }
            lost += price
            continue
        }

        when (session) {
            is Session.Intensive -> {
                if (session.attended) earned += session.amount else expected += session.amount
            }

            is Session.Diagnostics -> {
                totalLessons++
                val price = if (pricePerDiagnostics > 0.0) pricePerDiagnostics else session.amount
                if (session.attended) {
                    attendedLessons++
                    earned += price
                } else {
                    expected += price
                }
            }

            is Session.Student -> {
                totalLessons++
                val pay = session.employeePay(pricePerSession, sessionDate, sessionPriceHistory)
                if (session.attended) {
                    attendedLessons++
                    earned += pay
                } else {
                    expected += pay
                }
            }
        }
    }

    return DaySummaryStats(
        totalLessons = totalLessons,
        attendedLessons = attendedLessons,
        earned = earned,
        expected = expected,
        lost = lost,
    )
}
