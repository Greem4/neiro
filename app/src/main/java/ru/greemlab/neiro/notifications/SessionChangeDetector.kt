package ru.greemlab.neiro.notifications

import ru.greemlab.neiro.ui.calendar.AttendanceStatus

/**
 * Сравнивает снимки календаря до и после обновления с API и выявляет события по занятиям.
 */
object SessionChangeDetector {

    fun detect(
        before: List<TrackedSession>,
        after: List<TrackedSession>,
    ): List<SessionEvent> {
        val beforeBySlot = before.associateBy { it.slotKey }
        val afterBySlot = after.associateBy { it.slotKey }

        val removed = before.filter { it.slotKey !in afterBySlot }
        val added = after.filter { it.slotKey !in beforeBySlot }

        val events = mutableListOf<SessionEvent>()
        val pairedRemoved = mutableSetOf<TrackedSession>()
        val pairedAdded = mutableSetOf<TrackedSession>()

        for (removedSession in removed) {
            val rescheduleTarget = added.firstOrNull { addedSession ->
                addedSession !in pairedAdded &&
                    addedSession.clientKey == removedSession.clientKey &&
                    (addedSession.date != removedSession.date ||
                        addedSession.startTime != removedSession.startTime)
            }
            if (rescheduleTarget != null) {
                events += SessionEvent(
                    type = SessionEventType.RESCHEDULED,
                    session = rescheduleTarget,
                    previous = removedSession,
                )
                pairedRemoved += removedSession
                pairedAdded += rescheduleTarget
            }
        }

        for (session in added) {
            if (session in pairedAdded) continue
            if (session.isMarkedDeleted || session.status == AttendanceStatus.CANCELLED) continue
            events += SessionEvent(SessionEventType.NEW_BOOKING, session)
        }

        for (session in removed) {
            if (session in pairedRemoved) continue
            events += when {
                session.isMarkedDeleted -> SessionEvent(SessionEventType.DELETED, session)
                session.status == AttendanceStatus.CANCELLED ->
                    SessionEvent(SessionEventType.CANCELLED, session)
                else -> SessionEvent(SessionEventType.DELETED, session)
            }
        }

        for ((slotKey, afterSession) in afterBySlot) {
            val beforeSession = beforeBySlot[slotKey] ?: continue
            if (beforeSession.status == afterSession.status) continue
            if (afterSession.isMarkedDeleted) {
                if (beforeSession.status != AttendanceStatus.CANCELLED) {
                    events += SessionEvent(SessionEventType.CANCELLED, afterSession, beforeSession)
                }
                continue
            }
            when {
                afterSession.status == AttendanceStatus.CANCELLED ->
                    events += SessionEvent(SessionEventType.CANCELLED, afterSession, beforeSession)

                beforeSession.status == AttendanceStatus.CANCELLED ->
                    events += SessionEvent(SessionEventType.NEW_BOOKING, afterSession, beforeSession)

                afterSession.status == AttendanceStatus.CONFIRMED ->
                    events += SessionEvent(SessionEventType.CLIENT_CONFIRMED, afterSession, beforeSession)

                // Оплата приходит вместе с приходом или сразу за ним: событие
                // ставится один раз, на сам факт «клиент пришёл», а переход
                // «пришёл → оплачено» человека не будит (01.09.2026).
                afterSession.status.hasArrived && !beforeSession.status.hasArrived ->
                    events += SessionEvent(SessionEventType.CLIENT_ARRIVED, afterSession, beforeSession)

                else -> Unit
            }
        }

        return events.distinctBy { it.dedupeKey }
    }
}
