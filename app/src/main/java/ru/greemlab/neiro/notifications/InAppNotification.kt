package ru.greemlab.neiro.notifications

import java.time.Instant
import java.time.LocalDate

/**
 * Запись в ленте уведомлений внутри приложения (колокольчик в шапке календаря).
 */
data class InAppNotification(
    val id: String,
    val title: String,
    val body: String,
    val timestampEpochMillis: Long,
    val relatedDateEpochDay: Long? = null,
    val dedupeKey: String? = null,
    val read: Boolean = false,
) {
    val relatedDate: LocalDate?
        get() = relatedDateEpochDay?.let(LocalDate::ofEpochDay)

    val timestamp: Instant
        get() = Instant.ofEpochMilli(timestampEpochMillis)
}
