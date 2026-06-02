package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.runtime.Immutable
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Immutable
data class TimelineEntry(
    val name: String,
    val time: String,
    val comment: String,
    val status: AttendanceStatus,
    val isExtra: Boolean = false,
    val extraType: String = "",
    val extraAmount: Double = 0.0,
)

/** Длительность обычного занятия в минутах. */
const val SESSION_DURATION_MINUTES = 50

val SCHEDULE_DAY_START: LocalTime = LocalTime.of(9, 0)
val SCHEDULE_DAY_END: LocalTime = LocalTime.of(22, 0)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Immutable
data class TimedAppointment(
    val entry: TimelineEntry,
    val start: LocalTime,
    val end: LocalTime,
)

@Immutable
data class PositionedAppointment(
    val appointment: TimedAppointment,
    val lane: Int,
    val laneCount: Int,
)

/** Замена в слоте: новый клиент поверх, отменённый — под ним до раскрытия. */
@Immutable
data class ReplacementPair(
    val replacement: TimedAppointment,
    val removed: TimedAppointment,
)

@Immutable
sealed class PositionedTimelineItem {
    abstract val lane: Int
    abstract val laneCount: Int
    abstract val layoutAppointment: TimedAppointment

    @Immutable
    data class Single(
        val appointment: TimedAppointment,
        override val lane: Int,
        override val laneCount: Int,
    ) : PositionedTimelineItem() {
        override val layoutAppointment: TimedAppointment = appointment
    }

    @Immutable
    data class Replacement(
        val pair: ReplacementPair,
        override val lane: Int,
        override val laneCount: Int,
    ) : PositionedTimelineItem() {
        override val layoutAppointment: TimedAppointment = pair.replacement
    }
}

@Immutable
data class DayTimelineLayout(
    /** Начало шкалы — час первого занятия. */
    val axisStart: LocalTime,
    /** Конец шкалы — после последнего занятия, включая отменённые. */
    val axisEnd: LocalTime,
    val totalMinutes: Int,
    val positioned: List<PositionedTimelineItem>,
    val hourLabels: List<LocalTime>,
)

fun buildDayTimelineLayout(entries: List<TimelineEntry>): DayTimelineLayout? {
    val appointments = entries
        .filter { it.time.isNotEmpty() }
        .mapNotNull { entry ->
            val start = parseTimeRangeStart(entry.time) ?: return@mapNotNull null
            val end = parseTimeRangeEnd(entry.time) ?: start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
            if (start.isBefore(SCHEDULE_DAY_START) || !start.isBefore(SCHEDULE_DAY_END)) return@mapNotNull null
            TimedAppointment(entry = entry, start = start, end = end)
        }
        .sortedWith(compareBy<TimedAppointment> { it.start }.thenBy { it.entry.name })

    if (appointments.isEmpty()) return null

    val earliestStart = appointments.minOf { it.start }
    val latestEnd = appointments.maxOf { it.end }

    val axisStart = LocalTime.of(earliestStart.hour, 0)
    val axisEnd = roundUpToHour(latestEnd).coerceAtLeast(axisStart.plusHours(1))

    val hourLabels = buildList {
        var hour = axisStart
        while (hour.isBefore(axisEnd)) {
            add(hour)
            hour = hour.plusHours(1)
        }
    }

    val totalMinutes = Duration.between(axisStart, axisEnd)
        .toMinutes()
        .toInt()
        .coerceAtLeast(SESSION_DURATION_MINUTES)

    return DayTimelineLayout(
        axisStart = axisStart,
        axisEnd = axisEnd,
        totalMinutes = totalMinutes,
        positioned = computePositionedTimelineItems(appointments),
        hourLabels = hourLabels,
    )
}

/** Пара «замена + отмена» в одном временном слоте. */
fun findReplacementPair(appointments: List<TimedAppointment>): ReplacementPair? {
    val replacement = appointments.firstOrNull { it.entry.status.isReplacementTop() }
    val removed = appointments.firstOrNull { it.entry.status == AttendanceStatus.CANCELLED }
    return if (replacement != null && removed != null && replacement != removed) {
        ReplacementPair(replacement = replacement, removed = removed)
    } else {
        null
    }
}

private fun AttendanceStatus.isReplacementTop(): Boolean =
    this == AttendanceStatus.ARRIVED || this == AttendanceStatus.CONFIRMED

fun computePositionedTimelineItems(appointments: List<TimedAppointment>): List<PositionedTimelineItem> {
    val sorted = appointments.sortedWith(compareBy({ it.start }, { it.entry.name }))
    val used = mutableSetOf<TimedAppointment>()
    val groups = mutableListOf<Pair<TimedAppointment, ReplacementPair?>>()

    for (appt in sorted) {
        if (appt in used) continue
        val sameSlot = sorted.filter { other ->
            other !in used && other.start == appt.start && other.end == appt.end
        }
        val pair = findReplacementPair(sameSlot)
        if (pair != null) {
            groups.add(pair.replacement to pair)
            used.add(pair.replacement)
            used.add(pair.removed)
        } else {
            groups.add(appt to null)
            used.add(appt)
        }
    }

    val positioned = computePositionedAppointments(groups.map { it.first })

    return groups.zip(positioned) { (layoutAppt, pair), pos ->
        require(pos.appointment == layoutAppt)
        if (pair != null) {
            PositionedTimelineItem.Replacement(pair = pair, lane = pos.lane, laneCount = pos.laneCount)
        } else {
            PositionedTimelineItem.Single(
                appointment = pos.appointment,
                lane = pos.lane,
                laneCount = pos.laneCount,
            )
        }
    }
}

private fun intervalsOverlap(a: TimedAppointment, b: TimedAppointment): Boolean =
    a.start.isBefore(b.end) && b.start.isBefore(a.end)

/** Раскладывает пересекающиеся по времени занятия в колонки, чтобы не накладывались. */
fun computePositionedAppointments(appointments: List<TimedAppointment>): List<PositionedAppointment> {
    if (appointments.isEmpty()) return emptyList()

    val sorted = appointments.sortedWith(compareBy({ it.start }, { it.entry.name }))
    val laneEnds = mutableListOf<LocalTime>()
    val assigned = mutableListOf<Pair<TimedAppointment, Int>>()

    for (appt in sorted) {
        var lane = laneEnds.indexOfFirst { end -> !end.isAfter(appt.start) }
        if (lane < 0) {
            lane = laneEnds.size
            laneEnds.add(appt.end)
        } else {
            laneEnds[lane] = appt.end
        }
        assigned.add(appt to lane)
    }

    return assigned.map { (appt, lane) ->
        val cluster = assigned.filter { (other, _) -> intervalsOverlap(other, appt) }
        val laneCount = cluster.maxOf { it.second } + 1
        PositionedAppointment(appointment = appt, lane = lane, laneCount = laneCount)
    }
}

private fun roundUpToHour(time: LocalTime): LocalTime =
    if (time.minute == 0 && time.second == 0) time else LocalTime.of(time.hour, 0).plusHours(1)

fun minutesFromAxisStart(axisStart: LocalTime, time: LocalTime): Int =
    Duration.between(axisStart, time).toMinutes().toInt()

fun normalizeSessionTime(time: String): String {
    if (time.isBlank()) return time
    val start = parseTimeRangeStart(time) ?: return time
    val end = parseTimeRangeEnd(time) ?: start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
    return "${start.format(TIME_FORMAT)}-${end.format(TIME_FORMAT)}"
}

fun parseTimeRangeStart(time: String): LocalTime? {
    val startToken = time.substringBefore("-").trim()
    if (startToken.isEmpty()) return null
    return try {
        LocalTime.parse(startToken)
    } catch (_: Exception) {
        null
    }
}

fun parseTimeRangeEnd(time: String): LocalTime? {
    val endToken = time.substringAfter("-", "").trim()
    if (endToken.isEmpty()) return null
    return try {
        LocalTime.parse(endToken)
    } catch (_: Exception) {
        null
    }
}

fun formatHourLabel(time: LocalTime): String = time.format(TIME_FORMAT)

/** Текущее время на красной линии — с минутами. */
fun formatNowLabel(time: LocalTime): String = time.format(TIME_FORMAT)

/** Подпись начала слота для кнопки выбора времени (например «18:00»). */
fun formatTimeSlotLabel(timeRange: String): String =
    parseTimeRangeStart(timeRange)?.format(TIME_FORMAT) ?: timeRange

/** Час по умолчанию для нового интенсива и стартовой прокрутки ленты. */
const val INTENSIVE_TIME_SCROLL_ANCHOR_HOUR = 18

/** Слоты с [SCHEDULE_DAY_START] до [SCHEDULE_DAY_END], шаг 1 час. */
fun buildIntensiveStandardTimeGrid(): List<String> =
    (SCHEDULE_DAY_START.hour until SCHEDULE_DAY_END.hour).map { hour ->
        timeRangeFromStart(LocalTime.of(hour, 0))
    }

fun timeRangeFromStart(start: LocalTime): String {
    val end = start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
    return "${start.format(TIME_FORMAT)}-${end.format(TIME_FORMAT)}"
}

/**
 * Варианты времени для интенсива: слоты занятий дня и сетка от начала работы центра до закрытия.
 */
fun buildIntensiveTimeSlotOptions(lessonTimes: List<String>): List<String> {
    val fromLessons = lessonTimes
        .map { normalizeSessionTime(it) }
        .filter { it.isNotEmpty() }
    val standardGrid = buildIntensiveStandardTimeGrid()
    return (fromLessons + standardGrid)
        .distinct()
        .sortedBy { parseTimeRangeStart(it) ?: LocalTime.MAX }
}

/** Слоты для горизонтального выбора: опции дня + текущее значение, если его нет в списке. */
fun buildIntensiveScrollSlots(
    timeSlotOptions: List<String>,
    selectedTime: String,
): List<String> {
    val normalizedSelected = normalizeSessionTime(selectedTime)
    val base = timeSlotOptions
        .map { normalizeSessionTime(it) }
        .filter { it.isNotEmpty() }
        .distinct()
    val custom = if (normalizedSelected.isNotEmpty() && normalizedSelected !in base) {
        listOf(normalizedSelected)
    } else {
        emptyList()
    }
    return (base + custom).sortedBy { parseTimeRangeStart(it) ?: LocalTime.MAX }
}

/** Время по умолчанию для нового интенсива. */
fun intensiveDefaultTimeSlot(): String =
    timeRangeFromStart(LocalTime.of(INTENSIVE_TIME_SCROLL_ANCHOR_HOUR, 0))

/** Индекс слота для стартовой прокрутки ленты (якорный час). */
fun intensiveDefaultSlotIndex(slots: List<String>): Int {
    if (slots.isEmpty()) return 0
    val anchor = LocalTime.of(INTENSIVE_TIME_SCROLL_ANCHOR_HOUR, 0)
    slots.indexOfFirst { parseTimeRangeStart(it) == anchor }
        .takeIf { it >= 0 }
        ?.let { return it }
    return slots.indexOfFirst { slot ->
        (parseTimeRangeStart(slot) ?: LocalTime.MIN) >= anchor
    }.takeIf { it >= 0 } ?: 0
}

fun clampIntensiveStartTime(time: LocalTime): LocalTime {
    val lastHourStart = LocalTime.of(SCHEDULE_DAY_END.hour - 1, 0)
    return when {
        time.isBefore(SCHEDULE_DAY_START) -> SCHEDULE_DAY_START
        !time.isBefore(SCHEDULE_DAY_END) -> lastHourStart
        else -> time.withMinute(0).withSecond(0).withNano(0)
    }
}
