package ru.greemlab.neiro.notifications

import ru.greemlab.neiro.ui.components.daydetails.TimelineEntry
import ru.greemlab.neiro.ui.components.daydetails.parseTimeRangeStart
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Стабильный ключ слота расписания (клиент + дата + время начала).
 * Используется в уведомлениях и подсветке в [DayScheduleTimeline].
 */
object SessionSlotKey {
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * [kind] в ключе: занятие и диагностика одного клиента в одно время иначе
     * схлопываются в один слот в associateBy-детекторе изменений (терялись/путались).
     */
    fun build(
        clientName: String,
        date: LocalDate,
        startTime: LocalTime,
        kind: UpcomingSessionKind = UpcomingSessionKind.LESSON,
    ): String =
        "${clientName.normalizeForKey()}|$date|${startTime.format(TIME_FMT)}|${kind.name}"

    fun fromTimelineEntry(entry: TimelineEntry, date: LocalDate): String? {
        if (entry.isExtra && entry.extraType != "Диагностика") return null
        val start = parseTimeRangeStart(entry.time) ?: return null
        val kind = if (entry.isExtra && entry.extraType == "Диагностика") {
            UpcomingSessionKind.DIAGNOSTICS
        } else {
            UpcomingSessionKind.LESSON
        }
        return build(entry.name, date, start, kind)
    }
}
