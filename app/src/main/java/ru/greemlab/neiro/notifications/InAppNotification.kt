package ru.greemlab.neiro.notifications

import java.time.Instant
import java.time.LocalDate

/**
 * Запись в ленте уведомлений (активная YClients или журнал архива).
 */
data class InAppNotification(
    val id: String,
    val title: String,
    val body: String,
    val timestampEpochMillis: Long,
    val relatedDateEpochDay: Long? = null,
    val dedupeKey: String? = null,
    /** Ключ слота для подсветки в расписании дня ([SessionSlotKey]). */
    val highlightSlotKey: String? = null,
    /** [SessionEventType.name] — для акцентов в UI; старые записи без поля угадываются по заголовку. */
    val kind: String? = null,
    val read: Boolean = false,
) {
    val relatedDate: LocalDate?
        get() = relatedDateEpochDay?.let(LocalDate::ofEpochDay)

    val timestamp: Instant
        get() = Instant.ofEpochMilli(timestampEpochMillis)

    val displayKind: SessionEventType?
        get() = kind?.let { runCatching { SessionEventType.valueOf(it) }.getOrNull() }
            ?: inferKindFromTitle(title)

    companion object {
        internal fun inferKindFromTitle(title: String): SessionEventType? = when {
            title.startsWith("Новая запись:") -> SessionEventType.NEW_BOOKING
            title.startsWith("Отмена:") -> SessionEventType.CANCELLED
            title.startsWith("Перенос:") -> SessionEventType.RESCHEDULED
            title.startsWith("Удалено:") -> SessionEventType.DELETED
            title.startsWith("Подтвердил:") -> SessionEventType.CLIENT_CONFIRMED
            title.startsWith("Пришёл:") -> SessionEventType.CLIENT_ARRIVED
            title.startsWith("Скоро:") -> SessionEventType.REMINDER
            title.startsWith("Сегодня ") -> SessionEventType.TODAY_DIGEST
            title.startsWith("Завтра ") -> SessionEventType.TOMORROW_DIGEST
            title.contains("архив", ignoreCase = true) -> SessionEventType.ARCHIVE_REMINDER
            else -> null
        }
    }
}
