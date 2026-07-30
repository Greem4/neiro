package ru.greemlab.neiro.push

import android.content.Context
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.notifications.CalendarSessionSnapshot
import ru.greemlab.neiro.notifications.SessionEventType
import ru.greemlab.neiro.notifications.SessionSlotKey
import ru.greemlab.neiro.notifications.UpcomingSessionKind
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.components.daydetails.SESSION_DURATION_MINUTES
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Правит локальный календарь данными из payload события — без похода в YClients.
 *
 * Решение пользователя от 25.07.2026, расхождение с планом (app.md §5): план
 * предполагал обновление календаря только при открытии приложения, но
 * уведомление «подтвердился» при открытом и не изменившемся календаре выглядит
 * поломкой. Вызывается и из FCM-сервиса (push), и из догона — оба пути ведут
 * на один и тот же сервер и могут ошибиться одинаково.
 */
object PushEventCalendarApplier {

    suspend fun apply(context: Context, events: List<PushSessionEvent>) {
        if (events.isEmpty()) return

        val calendarRepository = CalendarDataStoreProvider.get(context)
        val pricePerDiagnostics = calendarRepository.userProfileFlow.first().pricePerDiagnostics

        calendarRepository.updateDayData { current ->
            events.fold(current) { dayData, event -> applyOne(dayData, event, pricePerDiagnostics) }
        }
    }

    /** internal, а не private — единственная точка, где применение события проверяется тестом. */
    internal fun applyOne(
        dayData: Map<LocalDate, List<String>>,
        event: PushSessionEvent,
        pricePerDiagnostics: Double,
    ): Map<LocalDate, List<String>> {
        val eventType = runCatching { SessionEventType.valueOf(event.type) }.getOrNull() ?: return dayData
        val kind = runCatching { UpcomingSessionKind.valueOf(event.kind) }.getOrNull() ?: return dayData
        val date = runCatching { LocalDate.parse(event.date) }.getOrNull() ?: return dayData
        val time = runCatching { LocalTime.parse(event.time) }.getOrNull() ?: return dayData
        val slotKey = SessionSlotKey.build(event.clientName, date, time, kind)

        return when (eventType) {
            SessionEventType.CLIENT_CONFIRMED -> updateStatus(dayData, date, slotKey, AttendanceStatus.CONFIRMED)
            SessionEventType.CLIENT_ARRIVED -> updateStatus(dayData, date, slotKey, AttendanceStatus.ARRIVED)
            SessionEventType.CANCELLED -> updateStatus(dayData, date, slotKey, AttendanceStatus.CANCELLED)
            SessionEventType.DELETED -> removeEntry(dayData, date, slotKey)
            SessionEventType.NEW_BOOKING ->
                addEntry(dayData, date, time, event.clientName, kind, pricePerDiagnostics)
            SessionEventType.RESCHEDULED ->
                applyReschedule(dayData, event, date, time, kind, pricePerDiagnostics)
            else -> dayData
        }
    }

    private class FoundSlot(val entries: List<String>, val index: Int)

    /** Ищет строку дня, чей разобранный слот совпадает с [slotKey] — переиспользует
     *  [CalendarSessionSnapshot.parseEntries], а не копирует разбор формата. */
    private fun findSlot(
        dayData: Map<LocalDate, List<String>>,
        date: LocalDate,
        slotKey: String,
    ): FoundSlot? {
        val entries = dayData[date] ?: return null
        val index = entries.indexOfFirst { raw ->
            CalendarSessionSnapshot.parseEntries(date, raw).any { it.slotKey == slotKey }
        }
        return if (index == -1) null else FoundSlot(entries, index)
    }

    private fun updateStatus(
        dayData: Map<LocalDate, List<String>>,
        date: LocalDate,
        slotKey: String,
        status: AttendanceStatus,
    ): Map<LocalDate, List<String>> {
        // Запись не нашлась — календарь мог быть не синхронизирован до этой даты;
        // ничего не делаем, уведомление всё равно покажется (app.md §5.3).
        val found = findSlot(dayData, date, slotKey) ?: return dayData
        val raw = found.entries[found.index]
        // Интенсивы правит только полный синк — слияние по детям нетривиально (§5.6).
        if (SessionParser.isIntensive(raw)) return dayData

        val updated = found.entries.toMutableList()
        updated[found.index] = SessionParser.withStatus(raw, status)
        return dayData + (date to updated)
    }

    private fun removeEntry(
        dayData: Map<LocalDate, List<String>>,
        date: LocalDate,
        slotKey: String,
    ): Map<LocalDate, List<String>> {
        val found = findSlot(dayData, date, slotKey) ?: return dayData
        if (SessionParser.isIntensive(found.entries[found.index])) return dayData

        val updated = found.entries.toMutableList().apply { removeAt(found.index) }
        return dayData + (date to updated)
    }

    private fun addEntry(
        dayData: Map<LocalDate, List<String>>,
        date: LocalDate,
        time: LocalTime,
        clientName: String,
        kind: UpcomingSessionKind,
        pricePerDiagnostics: Double,
    ): Map<LocalDate, List<String>> {
        val slotKey = SessionSlotKey.build(clientName, date, time, kind)
        // Уже есть — не дублируем.
        if (findSlot(dayData, date, slotKey) != null) return dayData

        val endTime = time.plusMinutes(SESSION_DURATION_MINUTES.toLong())
        val timeRange = "${time.format(TIME_FMT)}-${endTime.format(TIME_FMT)}"

        val raw = when (kind) {
            UpcomingSessionKind.LESSON -> SessionFormat.serializeStudentExtended(
                name = clientName,
                status = AttendanceStatus.EXPECTED,
                time = timeRange,
            )
            UpcomingSessionKind.DIAGNOSTICS -> SessionFormat.serializeDiagnostics(
                price = pricePerDiagnostics.toInt().toString(),
                name = clientName,
                status = AttendanceStatus.EXPECTED,
                time = timeRange,
            )
        }

        val entries = dayData[date].orEmpty()
        return dayData + (date to entries + raw)
    }

    private fun applyReschedule(
        dayData: Map<LocalDate, List<String>>,
        event: PushSessionEvent,
        newDate: LocalDate,
        newTime: LocalTime,
        kind: UpcomingSessionKind,
        pricePerDiagnostics: Double,
    ): Map<LocalDate, List<String>> {
        val prevDate = event.prevDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return dayData
        val prevTime = event.prevTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return dayData
        val prevSlotKey = SessionSlotKey.build(event.clientName, prevDate, prevTime, kind)

        val found = findSlot(dayData, prevDate, prevSlotKey) ?: return dayData
        if (SessionParser.isIntensive(found.entries[found.index])) return dayData

        val removed = found.entries.toMutableList().apply { removeAt(found.index) }
        val afterRemoval = dayData + (prevDate to removed)
        return addEntry(afterRemoval, newDate, newTime, event.clientName, kind, pricePerDiagnostics)
    }

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
