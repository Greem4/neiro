package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable

/** Сводка по одному дню для панели под календарём. */
@Immutable
data class DaySummaryStats(
    val totalLessons: Int = 0,
    val attendedLessons: Int = 0,
    val earned: Double = 0.0,
    val expected: Double = 0.0,
)

internal fun computeDayStats(
    sessions: List<String>,
    pricePerSession: Double,
): DaySummaryStats {
    var totalLessons = 0
    var attendedLessons = 0
    var earned = 0.0
    var expected = 0.0

    for (raw in sessions) {
        val session = SessionParser.parse(raw)
        if (session.isEffectivelyDeleted()) continue

        when (session) {
            is Session.Intensive -> {
                if (session.attended) earned += session.amount else expected += session.amount
            }

            is Session.Diagnostics -> {
                totalLessons++
                if (session.attended) {
                    attendedLessons++
                    earned += session.amount
                } else {
                    expected += session.amount
                }
            }

            is Session.Student -> {
                totalLessons++
                if (session.attended) {
                    attendedLessons++
                    earned += pricePerSession
                } else {
                    expected += pricePerSession
                }
            }
        }
    }

    return DaySummaryStats(
        totalLessons = totalLessons,
        attendedLessons = attendedLessons,
        earned = earned,
        expected = expected,
    )
}
