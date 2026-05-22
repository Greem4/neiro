package ru.greemlab.neiro.notifications

import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionParser
import java.time.LocalDate

/**
 * Дни с прошедшими занятиями в основном календаре, которые ещё не перенесены в архив.
 */
object PastSessionsArchiveCollector {

    fun daysNeedingArchive(
        dayData: Map<LocalDate, List<String>>,
        archivedDates: Set<LocalDate>,
        profile: UserProfile,
        today: LocalDate = LocalDate.now(),
    ): List<LocalDate> {
        if (!profile.isRegistered) return emptyList()

        return dayData.keys
            .filter { it.isBefore(today) }
            .filter { it !in archivedDates }
            .filter { sessionCount(dayData[it].orEmpty()) > 0 }
            .sorted()
    }

    /**
     * Сегодняшний день с занятиями, ещё не перенесённый в архив
     * (напоминание вечером в настроенное время).
     */
    fun todayNeedingArchive(
        dayData: Map<LocalDate, List<String>>,
        archivedDates: Set<LocalDate>,
        profile: UserProfile,
        today: LocalDate = LocalDate.now(),
    ): LocalDate? {
        if (!profile.isRegistered) return null
        if (today in archivedDates) return null
        if (sessionCount(dayData[today].orEmpty()) <= 0) return null
        return today
    }

    fun sessionCount(entries: List<String>): Int =
        entries.count { raw ->
            val session = SessionParser.parse(raw)
            !session.isEffectivelyDeleted() && session.status != AttendanceStatus.CANCELLED
        }
}
