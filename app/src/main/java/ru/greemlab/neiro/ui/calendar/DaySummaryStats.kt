package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable

/** Сводка по одному дню для панели под календарём. */
@Immutable
data class DaySummaryStats(
    val totalLessons: Int = 0,
    val attendedLessons: Int = 0,
    val confirmedLessons: Int = 0,
    val pendingLessons: Int = 0,
    val confirmedIntensiveChildren: Int = 0,
    val pendingIntensiveChildren: Int = 0,
    val hasIntensive: Boolean = false,
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
    var confirmedLessons = 0
    var pendingLessons = 0
    var confirmedIntensiveChildren = 0
    var pendingIntensiveChildren = 0
    var hasIntensive = false
    var earned = 0.0
    var expected = 0.0
    var lost = 0.0

    val parsed = sessions.map(SessionParser::parse)
    val intensiveChildrenByTime = buildIntensiveChildrenByTime(parsed)

    for (raw in sessions) {
        val session = SessionParser.parse(raw)

        if (session.isEffectivelyDeleted()) {
            if (session is Session.Intensive) hasIntensive = true
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
                hasIntensive = true
                confirmedIntensiveChildren += session.confirmedChildCount()
                pendingIntensiveChildren += session.pendingChildCount()
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
                    if (session.status == AttendanceStatus.CONFIRMED) {
                        confirmedLessons++
                    } else if (session.status == AttendanceStatus.EXPECTED) {
                        pendingLessons++
                    }
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
                    if (session.status == AttendanceStatus.CONFIRMED) {
                        confirmedLessons++
                    } else if (session.status == AttendanceStatus.EXPECTED) {
                        pendingLessons++
                    }
                }
            }
        }
    }

    return DaySummaryStats(
        totalLessons = totalLessons,
        attendedLessons = attendedLessons,
        confirmedLessons = confirmedLessons,
        pendingLessons = pendingLessons,
        confirmedIntensiveChildren = confirmedIntensiveChildren,
        pendingIntensiveChildren = pendingIntensiveChildren,
        hasIntensive = hasIntensive,
        earned = earned,
        expected = expected,
        lost = lost,
    )
}
