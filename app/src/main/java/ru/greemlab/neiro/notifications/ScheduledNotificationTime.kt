package ru.greemlab.neiro.notifications

import java.time.LocalTime
import java.util.Locale

/** Время суток для запланированного уведомления (сводка, архив). */
data class ScheduledNotificationTime(
    val hour: Int,
    val minute: Int,
) {
    fun toLocalTime(): LocalTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun toMinutesFromMidnight(): Int = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)

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
