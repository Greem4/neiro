package ru.greemlab.neiro.notifications

import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionParser
import java.time.LocalDate

/**
 * Дни с прошедшими занятиями в основном календаре, которые ещё не перенесены в архив.
 */
object PastSessionsArchiveCollector {

    /** Дальше этого окна прошлые дни «забытыми» не считаем — старая история не нервирует. */
    const val DEFAULT_LOOKBACK_DAYS = 30L

    /**
     * Прошлые дни с занятиями, забытые вне архива: бейдж на вкладке «Архив»
     * и вечернее напоминание вместе с сегодняшним днём.
     */
    fun daysNeedingArchive(
        dayData: Map<LocalDate, List<String>>,
        archivedDates: Set<LocalDate>,
        profile: UserProfile,
        today: LocalDate = LocalDate.now(),
        lookbackDays: Long = DEFAULT_LOOKBACK_DAYS,
    ): List<LocalDate> {
        if (!profile.isRegistered) return emptyList()
        val oldest = today.minusDays(lookbackDays)

        return dayData.keys
            .filter { it.isBefore(today) && !it.isBefore(oldest) }
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
