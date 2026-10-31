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

    /** Где запись нашлась при сравнении дня. */
    enum class Presence { BOTH, ONLY_SYNCED, ONLY_ARCHIVED }

    /** Одно поле, которое разошлось: значение в YClients и в архиве («—», если пусто). */
    data class FieldDiff(
        val field: String,
        val synced: String,
        val archived: String,
    )

    /**
     * Отличие по одной записи дня. Для [Presence.BOTH] — список разошедшихся полей,
     * для записи только с одной стороны поля пустые.
     */
    data class SessionDiff(
        val time: String,
        val title: String,
        val presence: Presence,
        val fields: List<FieldDiff> = emptyList(),
    )

    /**
     * Отличия между YClients и архивом за день — по записям, отсортированы по времени.
     *
     * Записи сопоставляются в два прохода: сначала по типу, имени и времени, затем
     * оставшиеся — по типу и имени. Без второго прохода перенос занятия на другое
     * время выглядел как две несвязанные записи «только в YClients» и «только в архиве».
     */
    fun describeDiff(synced: List<String>, archived: List<String>): List<SessionDiff> {
        if (!differs(synced, archived)) return emptyList()

        val syncedSessions = synced.filter { it.isNotBlank() }.map { SessionParser.parse(it) }
        val archivedSessions = archived.filter { it.isNotBlank() }.map { SessionParser.parse(it) }

        val pairs = arrayOfNulls<Int>(syncedSessions.size)
        val matchedArchive = BooleanArray(archivedSessions.size)
        listOf(::sessionIdentity, ::sessionNameIdentity).forEach { identity ->
            syncedSessions.forEachIndexed { syncedIndex, syncedSession ->
                if (pairs[syncedIndex] != null) return@forEachIndexed
                val archiveIndex = archivedSessions.indices.firstOrNull { index ->
                    !matchedArchive[index] && identity(syncedSession) == identity(archivedSessions[index])
                } ?: return@forEachIndexed
                pairs[syncedIndex] = archiveIndex
                matchedArchive[archiveIndex] = true
            }
        }

        val diffs = mutableListOf<SessionDiff>()
        syncedSessions.forEachIndexed { syncedIndex, syncedSession ->
            val archiveIndex = pairs[syncedIndex]
            if (archiveIndex == null) {
                diffs += sessionDiff(syncedSession, Presence.ONLY_SYNCED)
                return@forEachIndexed
            }
            val fields = fieldDiffs(syncedSession, archivedSessions[archiveIndex])
            if (fields.isNotEmpty()) {
                diffs += sessionDiff(syncedSession, Presence.BOTH, fields)
            }
        }
        archivedSessions.forEachIndexed { index, archivedSession ->
            if (!matchedArchive[index]) {
                diffs += sessionDiff(archivedSession, Presence.ONLY_ARCHIVED)
            }
        }

        return diffs.sortedWith(compareBy<SessionDiff>({ it.time.isBlank() }, { it.time }))
    }

    private fun sessionDiff(
        session: Session,
        presence: Presence,
        fields: List<FieldDiff> = emptyList(),
    ) = SessionDiff(
        time = sessionTime(session),
        title = sessionTitle(session),
        presence = presence,
        fields = fields,
    )

    private fun fieldDiffs(synced: Session, archived: Session): List<FieldDiff> {
        val fields = mutableListOf<FieldDiff>()
        when {
            synced is Session.Student && archived is Session.Student -> {
                addFieldChange(fields, "Статус", statusLabel(synced.status), statusLabel(archived.status))
                addFieldChange(fields, "Время", synced.time, archived.time)
                addFieldChange(fields, "Телефон", synced.phone, archived.phone)
                addFieldChange(fields, "Комментарий", synced.comment, archived.comment)
            }
            synced is Session.Intensive && archived is Session.Intensive -> {
                addFieldChange(fields, "Статус", statusLabel(synced.status), statusLabel(archived.status))
                addFieldChange(fields, "Время", synced.time, archived.time)
                addFieldChange(fields, "Сумма", formatAmount(synced.amount), formatAmount(archived.amount))
                addFieldChange(
                    fields,
                    "Сумма фиксирована",
                    fixedLabel(synced.amountFixed),
                    fixedLabel(archived.amountFixed),
                )
                addFieldChange(fields, "Название", synced.name, archived.name)
                fields += intensiveChildrenDiffs(synced.children, archived.children)
            }
            synced is Session.Diagnostics && archived is Session.Diagnostics -> {
                addFieldChange(fields, "Статус", statusLabel(synced.status), statusLabel(archived.status))
                addFieldChange(fields, "Время", synced.time, archived.time)
                addFieldChange(fields, "Сумма", formatAmount(synced.amount), formatAmount(archived.amount))
                addFieldChange(fields, "Название", synced.name, archived.name)
            }
        }
        return fields
    }

    private fun intensiveChildrenDiffs(
        syncedChildren: List<Session.IntensiveChild>,
        archivedChildren: List<Session.IntensiveChild>,
    ): List<FieldDiff> {
        val matchedArchive = BooleanArray(archivedChildren.size)
        val fields = mutableListOf<FieldDiff>()

        syncedChildren.forEach { syncedChild ->
            val childName = syncedChild.name.trim()
            val archiveIndex = archivedChildren.indices.firstOrNull { index ->
                !matchedArchive[index] &&
                    childIdentity(syncedChild) == childIdentity(archivedChildren[index])
            }
            if (archiveIndex == null) {
                fields += FieldDiff(childName, synced = CHILD_PRESENT, archived = CHILD_ABSENT)
                return@forEach
            }
            matchedArchive[archiveIndex] = true
            val archivedChild = archivedChildren[archiveIndex]
            addFieldChange(
                fields,
                "$childName: статус",
                statusLabel(syncedChild.status),
                statusLabel(archivedChild.status),
            )
            addFieldChange(fields, "$childName: телефон", syncedChild.phone, archivedChild.phone)
            addFieldChange(fields, "$childName: комментарий", syncedChild.comment, archivedChild.comment)
        }

        archivedChildren.forEachIndexed { index, archivedChild ->
            if (!matchedArchive[index]) {
                fields += FieldDiff(archivedChild.name.trim(), synced = CHILD_ABSENT, archived = CHILD_PRESENT)
            }
        }

        return fields
    }

    private const val CHILD_PRESENT = "в группе"
    private const val CHILD_ABSENT = "нет"

    private fun addFieldChange(
        fields: MutableList<FieldDiff>,
        field: String,
        syncedValue: String,
        archivedValue: String,
    ) {
        if (syncedValue.trim() == archivedValue.trim()) return
        fields += FieldDiff(field, displayValue(syncedValue), displayValue(archivedValue))
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

    private fun sessionNameIdentity(session: Session): String = when (session) {
        is Session.Student -> "student:${normalize(session.name)}"
        is Session.Intensive -> "intensive:${normalize(session.name)}"
        is Session.Diagnostics -> "diagnostics:${normalize(session.name)}"
    }

    private fun sessionTime(session: Session): String = when (session) {
        is Session.Student -> session.time
        is Session.Intensive -> session.time
        is Session.Diagnostics -> session.time
    }.trim()

    private fun sessionTitle(session: Session): String = when (session) {
        is Session.Student -> session.name.trim()
        is Session.Intensive -> session.name.trim().ifBlank { "Интенсив" }
        is Session.Diagnostics -> "${session.name.trim()} (диагностика)"
    }

    private fun statusLabel(status: AttendanceStatus): String = when (status) {
        AttendanceStatus.EXPECTED -> "Ожидает"
        AttendanceStatus.CONFIRMED -> "Подтвердил"
        AttendanceStatus.CANCELLED -> "Не пришёл"
        AttendanceStatus.ARRIVED -> "Пришёл"
        AttendanceStatus.PAID -> "Оплачено"
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
