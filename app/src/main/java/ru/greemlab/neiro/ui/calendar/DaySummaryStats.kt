package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable

/** Сводка по одному дню для панели под календарём. */
@Immutable
data class DaySummaryStats(
    val totalLessons: Int = 0,
    val attendedLessons: Int = 0,
    val totalIntensiveChildren: Int = 0,
    val attendedIntensiveChildren: Int = 0,
    val earned: Double = 0.0,
    val expected: Double = 0.0,
    val lost: Double = 0.0,
)

internal fun computeDayStats(
    sessions: List<String>,
    pricePerSession: Double,
    pricePerDiagnostics: Double,
    pricePerIntensiveChild: Double = 0.0,
): DaySummaryStats {
    var totalLessons = 0
    var attendedLessons = 0
    var totalIntensiveChildren = 0
    var attendedIntensiveChildren = 0
    var earned = 0.0
    var expected = 0.0
    var lost = 0.0

    val parsed = sessions.map(SessionParser::parse)
    val intensiveChildrenByTime = buildIntensiveChildrenByTime(parsed)

    for (raw in sessions) {
        val session = SessionParser.parse(raw)

        if (session.isEffectivelyDeleted()) {
            val price = when (session) {
                is Session.Intensive -> session.totalAmount(pricePerIntensiveChild, onlyArrived = false)
                is Session.Diagnostics -> if (pricePerDiagnostics > 0.0) pricePerDiagnostics else session.amount
                is Session.Student -> pricePerSession
            }
            lost += price
            continue
        }

        when (session) {
            is Session.Intensive -> {
                totalIntensiveChildren += session.expectedChildCount()
                attendedIntensiveChildren += session.arrivedChildCount()
                val actual = session.totalAmount(pricePerIntensiveChild, onlyArrived = true)
                val planned = session.totalAmount(pricePerIntensiveChild, onlyArrived = false)
                earned += actual
                expected += (planned - actual).coerceAtLeast(0.0)
            }

            is Session.Diagnostics -> {
                totalLessons++
                val price = if (pricePerDiagnostics > 0.0) pricePerDiagnostics else session.amount
                if (session.countsTowardEarnings()) {
                    attendedLessons++
                    earned += price
                } else {
                    expected += price
                }
            }

            is Session.Student -> {
                if (isStudentCoveredByIntensive(session, intensiveChildrenByTime)) continue
                totalLessons++
                val pay = pricePerSession
                if (session.countsTowardEarnings()) {
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
        totalIntensiveChildren = totalIntensiveChildren,
        attendedIntensiveChildren = attendedIntensiveChildren,
        earned = earned,
        expected = expected,
        lost = lost,
    )
}
