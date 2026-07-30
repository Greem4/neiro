package ru.greemlab.neiro.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Формат параметра `changed_after` для live-опроса YClients API.
 */
object YClientsLiveSyncFormat {

    /** Небольшое перекрытие, чтобы не пропустить изменения на границе опроса. */
    const val CHANGED_AFTER_OVERLAP_SECONDS = 60L

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

    fun formatChangedAfter(
        instant: Instant,
        zone: ZoneId = SyncQuietHours.syncZone,
    ): String = instant
        .minusSeconds(CHANGED_AFTER_OVERLAP_SECONDS)
        .atZone(zone)
        .format(formatter)
}
