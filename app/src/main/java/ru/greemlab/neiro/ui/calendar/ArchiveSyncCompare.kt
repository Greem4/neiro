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

    /** Человекочитаемый список отличий между YClients и архивом за день. */
    fun describeDiff(synced: List<String>, archived: List<String>): List<String> {
        if (!differs(synced, archived)) return emptyList()

        val syncedSessions = synced.filter { it.isNotBlank() }.map { SessionParser.parse(it) }
        val archivedSessions = archived.filter { it.isNotBlank() }.map { SessionParser.parse(it) }

        val matchedArchive = BooleanArray(archivedSessions.size)
        val lines = mutableListOf<String>()

        syncedSessions.forEach { syncedSession ->
            val archiveIndex = archivedSessions.indices.firstOrNull { index ->
                !matchedArchive[index] &&
                    sessionIdentity(syncedSession) == sessionIdentity(archivedSessions[index])
            }
            if (archiveIndex == null) {
                lines += "Только в YClients: ${sessionLabel(syncedSession)}"
                return@forEach
            }
            matchedArchive[archiveIndex] = true
            lines += fieldDiffLines(syncedSession, archivedSessions[archiveIndex])
        }

        archivedSessions.forEachIndexed { index, archivedSession ->
            if (!matchedArchive[index]) {
                lines += "Только в архиве: ${sessionLabel(archivedSession)}"
            }
        }

        return lines
    }

    private fun fieldDiffLines(synced: Session, archived: Session): List<String> {
        val label = sessionLabel(synced)
        val lines = mutableListOf<String>()
        when {
            synced is Session.Student && archived is Session.Student -> {
                addFieldChange(lines, label, "статус", statusLabel(synced.status), statusLabel(archived.status))
                addFieldChange(lines, label, "время", synced.time, archived.time)
                addFieldChange(lines, label, "телефон", synced.phone, archived.phone)
                addFieldChange(lines, label, "комментарий", synced.comment, archived.comment)
            }
            synced is Session.Intensive && archived is Session.Intensive -> {
                addFieldChange(lines, label, "статус", statusLabel(synced.status), statusLabel(archived.status))
                addFieldChange(lines, label, "время", synced.time, archived.time)
                addFieldChange(
                    lines,
                    label,
                    "сумма",
                    formatAmount(synced.amount),
                    formatAmount(archived.amount),
                )
                addFieldChange(
                    lines,
                    label,
                    "сумма фиксирована",
                    fixedLabel(synced.amountFixed),
                    fixedLabel(archived.amountFixed),
                )
                addFieldChange(lines, label, "название", synced.name, archived.name)
                lines += intensiveChildrenDiffLines(label, synced.children, archived.children)
            }
            synced is Session.Diagnostics && archived is Session.Diagnostics -> {
                addFieldChange(lines, label, "статус", statusLabel(synced.status), statusLabel(archived.status))
                addFieldChange(lines, label, "время", synced.time, archived.time)
                addFieldChange(
                    lines,
                    label,
                    "сумма",
                    formatAmount(synced.amount),
                    formatAmount(archived.amount),
                )
                addFieldChange(lines, label, "название", synced.name, archived.name)
            }
        }
        return lines
    }

    private fun intensiveChildrenDiffLines(
        parentLabel: String,
        syncedChildren: List<Session.IntensiveChild>,
        archivedChildren: List<Session.IntensiveChild>,
    ): List<String> {
        val matchedArchive = BooleanArray(archivedChildren.size)
        val lines = mutableListOf<String>()

        syncedChildren.forEach { syncedChild ->
            val archiveIndex = archivedChildren.indices.firstOrNull { index ->
                !matchedArchive[index] &&
                    childIdentity(syncedChild) == childIdentity(archivedChildren[index])
            }
            if (archiveIndex == null) {
                lines += "$parentLabel — только в YClients: ${syncedChild.name.trim()}"
                return@forEach
            }
            matchedArchive[archiveIndex] = true
            val archivedChild = archivedChildren[archiveIndex]
            val childLabel = "${syncedChild.name.trim()} ($parentLabel)"
            addFieldChange(
                lines,
                childLabel,
                "статус",
                statusLabel(syncedChild.status),
                statusLabel(archivedChild.status),
            )
            addFieldChange(lines, childLabel, "телефон", syncedChild.phone, archivedChild.phone)
            addFieldChange(lines, childLabel, "комментарий", syncedChild.comment, archivedChild.comment)
        }

        archivedChildren.forEachIndexed { index, archivedChild ->
            if (!matchedArchive[index]) {
                lines += "$parentLabel — только в архиве: ${archivedChild.name.trim()}"
            }
        }

        return lines
    }

    private fun addFieldChange(
        lines: MutableList<String>,
        sessionLabel: String,
        field: String,
        syncedValue: String,
        archivedValue: String,
    ) {
        if (syncedValue.trim() == archivedValue.trim()) return
        lines += "$sessionLabel, $field: YClients — ${displayValue(syncedValue)}, архив — ${displayValue(archivedValue)}"
    }

    private fun displayValue(value: String): String =
        value.trim().ifBlank { "—" }

    private fun formatAmount(amount: Double): String =
        if (amount == 0.0) "" else amount.toLong().toString()

    private fun fixedLabel(amountFixed: Boolean): String = if (amountFixed) "да" else "нет"

    private fun sessionIdentity(session: Session): String = when (session) {
        is Session.Student -> "student:${normalize(session.name)}:${normalize(session.time)}"
        is Session.Intensive -> "intensive:${normalize(session.name)}:${normalize(session.time)}"
        is Session.Diagnostics -> "diagnostics:${normalize(session.name)}:${normalize(session.time)}"
    }

    private fun childIdentity(child: Session.IntensiveChild): String =
        normalize(child.name)

    private fun normalize(value: String): String = value.trim().lowercase()

    private fun sessionLabel(session: Session): String {
        val time = when (session) {
            is Session.Student -> session.time
            is Session.Intensive -> session.time
            is Session.Diagnostics -> session.time
        }.trim()
        val timePrefix = if (time.isBlank()) "" else "$time — "
        return when (session) {
            is Session.Student -> "$timePrefix${session.name.trim()}"
            is Session.Intensive -> "$timePrefix${session.name.trim().ifBlank { "Интенсив" }}"
            is Session.Diagnostics -> "$timePrefix${session.name.trim()} (диагностика)"
        }
    }

    private fun statusLabel(status: AttendanceStatus): String = when (status) {
        AttendanceStatus.EXPECTED -> "Ожидает"
        AttendanceStatus.CONFIRMED -> "Подтвердил"
        AttendanceStatus.CANCELLED -> "Не пришёл"
        AttendanceStatus.ARRIVED -> "Пришёл"
    }

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
            price = SessionFormat.intensivePriceField(session.amount, session.amountFixed),
            name = session.name.trim().ifBlank { "Интенсив" },
            status = session.status,
            time = session.time.trim(),
            children = session.children,
            amountFixed = session.amountFixed,
        )
        is Session.Diagnostics -> SessionFormat.serializeDiagnostics(
            price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
            name = session.name.trim(),
            status = session.status,
            time = session.time.trim(),
        )
    }
}
