package ru.greemlab.neiro.sync

import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Интервалы live-опроса YClients по времени суток (локальный часовой пояс устройства).
 *
 * - 08:00–22:00 — активно, каждые [DAY_INTERVAL_MINUTES];
 * - 22:00–08:00 — тихий режим, раз в [NIGHT_INTERVAL_MINUTES].
 */
object LiveApiPollSchedule {

    private val dayStart = LocalTime.of(8, 0)
    private val quietStart = LocalTime.of(22, 0)

    const val DAY_INTERVAL_MINUTES = 3L
    const val NIGHT_INTERVAL_MINUTES = 60L

    fun isQuietHours(
        time: LocalTime = LocalTime.now(ZoneId.systemDefault()),
    ): Boolean = time >= quietStart || time < dayStart

    fun intervalMillis(
        time: LocalTime = LocalTime.now(ZoneId.systemDefault()),
    ): Long = if (isQuietHours(time)) {
        TimeUnit.MINUTES.toMillis(NIGHT_INTERVAL_MINUTES)
    } else {
        TimeUnit.MINUTES.toMillis(DAY_INTERVAL_MINUTES)
    }
}
