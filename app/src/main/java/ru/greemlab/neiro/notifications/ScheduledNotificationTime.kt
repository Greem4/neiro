package ru.greemlab.neiro.notifications

import java.time.LocalTime
import java.util.Locale

/** Время суток для запланированного уведомления (сводка, архив). */
data class ScheduledNotificationTime(
    val hour: Int,
    val minute: Int,
) {
    init {
        require(hour in 0..23) { "hour=$hour outside 0..23" }
        require(minute in 0..59) { "minute=$minute outside 0..59" }
    }

    fun toLocalTime(): LocalTime = LocalTime.of(hour, minute)

    fun toMinutesFromMidnight(): Int = hour * 60 + minute

    fun formatForDisplay(): String = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    companion object {
        fun fromMinutesFromMidnight(minutes: Int): ScheduledNotificationTime {
            val total = minutes.coerceIn(0, 23 * 60 + 59)
            return ScheduledNotificationTime(total / 60, total % 60)
        }

        fun fromLocalTime(time: LocalTime): ScheduledNotificationTime =
            ScheduledNotificationTime(time.hour, time.minute)
    }
}
