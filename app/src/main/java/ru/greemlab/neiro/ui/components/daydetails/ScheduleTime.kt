package ru.greemlab.neiro.ui.components.daydetails

import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Длительность обычного занятия в минутах. */
const val SESSION_DURATION_MINUTES = 50

val SCHEDULE_DAY_START: LocalTime = LocalTime.of(9, 0)
val SCHEDULE_DAY_END: LocalTime = LocalTime.of(22, 0)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

val SCHEDULE_TOTAL_MINUTES: Int =
    Duration.between(SCHEDULE_DAY_START, SCHEDULE_DAY_END).toMinutes().toInt()

/**
 * Нормализует интервал записи до [SESSION_DURATION_MINUTES] минут от времени начала.
 * Пример: `11:00-11:45` → `11:00-11:50`.
 */
fun normalizeSessionTime(time: String): String {
    if (time.isBlank()) return time
    val start = parseTimeRangeStart(time) ?: return time
    val end = start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
    return "${start.format(TIME_FORMAT)}-${end.format(TIME_FORMAT)}"
}

fun parseTimeRangeStart(time: String): LocalTime? {
    val startToken = time.substringBefore("-").trim()
    if (startToken.isEmpty()) return null
    return try {
        LocalTime.parse(startToken)
    } catch (_: Exception) {
        null
    }
}

/** Смещение от начала шкалы (9:00) в минутах, null если вне диапазона. */
fun minutesFromScheduleStart(time: LocalTime): Int? {
    if (time.isBefore(SCHEDULE_DAY_START) || !time.isBefore(SCHEDULE_DAY_END)) return null
    return Duration.between(SCHEDULE_DAY_START, time).toMinutes().toInt()
}

fun formatTimeRangeLabel(time: String): String {
    val normalized = normalizeSessionTime(time)
    return normalized.replace("-", " – ")
}
