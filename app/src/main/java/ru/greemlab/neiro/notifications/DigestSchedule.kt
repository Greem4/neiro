package ru.greemlab.neiro.notifications

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/** Расчёт задержки до следующего срабатывания по времени суток. */
object DigestSchedule {

    fun delayUntilNext(
        scheduled: ScheduledNotificationTime,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: ZonedDateTime = ZonedDateTime.now(zoneId),
    ): Duration {
        var next = ZonedDateTime.of(now.toLocalDate(), scheduled.toLocalTime(), zoneId)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        return Duration.between(now, next)
    }
}
