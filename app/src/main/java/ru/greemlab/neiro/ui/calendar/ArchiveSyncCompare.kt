package ru.greemlab.neiro.ui.calendar

import java.time.LocalDate

/**
 * Сравнение дня в основном календаре (YClients) и в архиве.
 */
object ArchiveSyncCompare {

    /** Даты, для которых архив есть и содержимое не совпадает с синхронизацией. */
    fun mismatchDates(
        syncedDayData: Map<LocalDate, List<String>>,
        archivedDayData: Map<LocalDate, List<String>>,
    ): Set<LocalDate> = archivedDayData.keys.filter { date ->
        differs(
            synced = syncedDayData[date].orEmpty(),
            archived = archivedDayData[date].orEmpty(),
        )
    }.toSet()

    fun differs(synced: List<String>, archived: List<String>): Boolean =
        canonicalDay(synced) != canonicalDay(archived)

    private fun canonicalDay(sessions: List<String>): List<String> =
        sessions
            .filter { it.isNotBlank() }
            .map { canonicalSession(it) }
            .sorted()

    private fun canonicalSession(raw: String): String = when (val session = SessionParser.parse(raw)) {
        is Session.Student -> SessionFormat.serializeStudentExtended(
            name = session.name.trim(),
            status = session.status,
            time = session.time.trim(),
            phone = session.phone.trim(),
            comment = session.comment.trim(),
        )
        is Session.Intensive -> SessionFormat.serializeIntensive(
            price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
            name = session.name.trim().ifBlank { "Интенсив" },
            status = session.status,
            time = session.time.trim(),
            children = session.children,
        )
        is Session.Diagnostics -> SessionFormat.serializeDiagnostics(
            price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
            name = session.name.trim(),
            status = session.status,
            time = session.time.trim(),
        )
    }
}
