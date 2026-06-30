package ru.greemlab.neiro.notifications

import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.components.daydetails.SESSION_DURATION_MINUTES
import ru.greemlab.neiro.ui.components.daydetails.parseTimeRangeEnd
import ru.greemlab.neiro.ui.components.daydetails.parseTimeRangeStart
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class UpcomingSessionKind {
    LESSON,
    DIAGNOSTICS,
}

/**
 * Предстоящее занятие из локального календаря (после синхронизации YClients — только своего staff).
 */
data class UpcomingSession(
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val clientName: String,
    val kind: UpcomingSessionKind,
    val status: AttendanceStatus,
) {
    val dedupeKey: String =
        "${date}|${startTime}|${clientName.normalizeForKey()}|${kind.name}"

    fun startsAt(): LocalDateTime =
        LocalDateTime.of(date, startTime)

    fun reminderAt(minutesBefore: Int): LocalDateTime =
        startsAt().minusMinutes(minutesBefore.toLong())
}

object UpcomingSessionsCollector {

    /**
     * Собирает предстоящие занятия из календаря.
     * Пустой список, если профиль не зарегистрирован ([UserProfile.isRegistered]).
     */
    fun collect(
        dayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now(),
        horizonDays: Int = 14,
    ): List<UpcomingSession> {
        if (!profile.isRegistered) return emptyList()

        val endDate = today.plusDays(horizonDays.toLong())
        val result = mutableListOf<UpcomingSession>()

        for ((date, entries) in dayData) {
            if (date.isBefore(today) || date.isAfter(endDate)) continue

            for (raw in entries) {
                val session = SessionParser.parse(raw)
                // Если сессия удалена целиком (или все дети в интенсиве), пропускаем.
                if (session.isEffectivelyDeleted()) continue
                if (session.status == AttendanceStatus.CANCELLED) continue

                val upcomingList = session.toUpcomingList(date)
                for (upcoming in upcomingList) {
                    if (date == today && !upcoming.startTime.isAfter(now)) continue

                    result += upcoming
                }
            }
        }

        return result.sortedWith(compareBy({ it.date }, { it.startTime }, { it.clientName }))
    }

    /**
     * Занятия, пора напомнить о которых сейчас (окно ±7 мин от [reminderMinutesBefore]).
     */
    fun collectDueForReminder(
        sessions: List<UpcomingSession>,
        reminderMinutesBefore: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<UpcomingSession> {
        val minMinutesUntilStart = (reminderMinutesBefore - REMINDER_WINDOW_HALF).toLong().coerceAtLeast(0)
        val maxMinutesUntilStart = (reminderMinutesBefore + REMINDER_WINDOW_HALF).toLong()

        return sessions.filter { session ->
            val minutesUntilStart = java.time.Duration.between(now, session.startsAt()).toMinutes()
            minutesUntilStart in minMinutesUntilStart..maxMinutesUntilStart
        }
    }

    private const val REMINDER_WINDOW_HALF = 7

    fun todaySessions(
        sessions: List<UpcomingSession>,
        today: LocalDate = LocalDate.now(),
    ): List<UpcomingSession> = sessions.filter { it.date == today }

    fun tomorrowSessions(
        sessions: List<UpcomingSession>,
        today: LocalDate = LocalDate.now(),
    ): List<UpcomingSession> = sessions.filter { it.date == today.plusDays(1) }

    private fun Session.toUpcomingList(date: LocalDate): List<UpcomingSession> {
        return when (this) {
            is Session.Student -> {
                if (name.isBlank() || time.isBlank()) return emptyList()
                val start = parseTimeRangeStart(time) ?: return emptyList()
                val end =
                    parseTimeRangeEnd(time) ?: start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
                listOf(
                    UpcomingSession(
                        date = date,
                        startTime = start,
                        endTime = end,
                        clientName = name.trim(),
                        kind = UpcomingSessionKind.LESSON,
                        status = status,
                    ),
                )
            }

            is Session.Diagnostics -> {
                if (name.isBlank() || time.isBlank()) return emptyList()
                val start = parseTimeRangeStart(time) ?: return emptyList()
                val end =
                    parseTimeRangeEnd(time) ?: start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
                listOf(
                    UpcomingSession(
                        date = date,
                        startTime = start,
                        endTime = end,
                        clientName = name.trim(),
                        kind = UpcomingSessionKind.DIAGNOSTICS,
                        status = status,
                    ),
                )
            }

            is Session.Intensive -> {
                if (children.isEmpty()) {
                    if (name.isBlank() || time.isBlank()) return emptyList()
                    val start = parseTimeRangeStart(time) ?: return emptyList()
                    val end = parseTimeRangeEnd(time)
                        ?: start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
                    return listOf(
                        UpcomingSession(
                            date = date,
                            startTime = start,
                            endTime = end,
                            clientName = name.trim(),
                            kind = UpcomingSessionKind.LESSON,
                            status = status,
                        ),
                    )
                }

                val start = parseTimeRangeStart(time) ?: return emptyList()
                val end = parseTimeRangeEnd(time)
                    ?: start.plusMinutes(SESSION_DURATION_MINUTES.toLong())

                children.mapNotNull { child ->
                    if (child.name.isBlank()) return@mapNotNull null
                    UpcomingSession(
                        date = date,
                        startTime = start,
                        endTime = end,
                        clientName = child.name.trim(),
                        kind = UpcomingSessionKind.LESSON,
                        status = child.status,
                    )
                }
            }
        }
    }
}

internal fun String.normalizeForKey(): String =
    lowercase()
        .replace('ё', 'е')
        .split(' ', '\t', '-', '.', ',', ';', ':', '(', ')', '/')
        .map { it.filter { ch -> ch.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
        .sorted()
        .joinToString(" ")
