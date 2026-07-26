package ru.greemlab.neiro.push

import android.content.Context
import ru.greemlab.neiro.notifications.CalendarSessionSnapshot
import ru.greemlab.neiro.notifications.SessionEvent
import ru.greemlab.neiro.notifications.SessionNotificationDisplay
import ru.greemlab.neiro.notifications.SessionNotificationPreferences
import java.time.LocalDate

/**
 * Показ уведомлений по событиям с сервера (push или догон) — зеркало хвоста
 * [ru.greemlab.neiro.notifications.SessionNotificationCoordinator.processSnapshotTransition]:
 * фильтры → показ → mark, без бейзлайна (сервер уже отсеял его сидированием).
 *
 * Фильтра по `staff_id` здесь нет намеренно — чужие события отсекаются раньше,
 * при разборе payload (app.md §2.5), одним барьером на оба пути доставки.
 */
object PushEventNotifier {

    fun notify(context: Context, events: List<SessionEvent>) {
        val prefs = SessionNotificationPreferences.get(context)
        if (!prefs.isEnabled) return

        val ready = events
            .filter { it.session.date.isWithinPushHorizon() }
            .filter { prefs.isTypeEnabled(it.type) }
            .filter { !prefs.wasEventNotified(it.dedupeKey) }
            .distinctBy { it.dedupeKey }
        if (ready.isEmpty()) return

        val shown = SessionNotificationDisplay.showEvents(context, ready)
        ready.filter { it.dedupeKey in shown }.forEach { prefs.markEventNotified(it.dedupeKey) }
    }

    /**
     * Сервер видит всю компанию и не знает горизонт приложения (60 дней вперёд,
     * см. [CalendarSessionSnapshot.DEFAULT_HORIZON_DAYS]) — без фильтра появятся
     * уведомления о прошлом и о занятиях за горизонтом (app.md, риск 3).
     */
    private fun LocalDate.isWithinPushHorizon(today: LocalDate = LocalDate.now()): Boolean {
        if (isBefore(today)) return false
        return !isAfter(today.plusDays(CalendarSessionSnapshot.DEFAULT_HORIZON_DAYS.toLong()))
    }
}
