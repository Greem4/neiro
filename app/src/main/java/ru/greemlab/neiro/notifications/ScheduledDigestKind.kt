package ru.greemlab.neiro.notifications

/** Тип сводки / напоминания, запускаемого в настроенное время суток. */
enum class ScheduledDigestKind {
    TODAY,
    TOMORROW,
    ARCHIVE,
}
