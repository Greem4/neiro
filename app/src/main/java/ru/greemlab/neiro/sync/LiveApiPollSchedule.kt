package ru.greemlab.neiro.sync

import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Интервалы live-опроса YClients (текущий + след. месяц для уведомлений) по МСК.
 *
 * - 09:00–21:00 МСК — каждые [DAY_INTERVAL_MINUTES];
 * - 21:00–09:00 МСК — раз в [NIGHT_INTERVAL_MINUTES].
 */
object LiveApiPollSchedule {

    val syncZone: ZoneId = ZoneId.of("Europe/Moscow")

    private val dayStart = LocalTime.of(9, 0)
    private val quietStart = LocalTime.of(21, 0)

    const val DAY_INTERVAL_MINUTES = 5L
    const val NIGHT_INTERVAL_MINUTES = 60L

    fun isQuietHours(
        time: LocalTime = LocalTime.now(syncZone),
    ): Boolean = time >= quietStart || time < dayStart

    fun intervalMillis(
        time: LocalTime = LocalTime.now(syncZone),
    ): Long = if (isQuietHours(time)) {
        TimeUnit.MINUTES.toMillis(NIGHT_INTERVAL_MINUTES)
    } else {
        TimeUnit.MINUTES.toMillis(DAY_INTERVAL_MINUTES)
    }
}
