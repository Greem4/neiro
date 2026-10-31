package ru.greemlab.neiro.ui.calendar

import java.time.LocalDate

/**
 * Перевод архивного «Пришёл» в «Оплачено» там, где YClients говорит «оплачено».
 *
 * До 0.2.5 «оплачено» отдельным статусом не было: всё, что состоялось, архив
 * помнит как «Пришёл». YClients теперь отдаёт «Оплачено», и сравнение с архивом
 * помечало почти каждый старый день как несовпадающий, а «Обновить архив?» в
 * таком виде затёрло бы ручные правки.
 *
 * Правило узкое намеренно:
 * - только дни до [CUTOFF] — дня выхода 0.2.5. Позже «Пришёл» в архиве мог
 *   поставить человек руками, и это уже настоящее расхождение с YClients;
 * - запись поднимается, только если у неё есть близнец в YClients (тот же тип,
 *   имя и время) и после подъёма они совпадают целиком. Разошлось хоть одно
 *   другое поле — запись не трогается и остаётся в окне отличий.
 *
 * Сами исходные строки сохраняет хранилище — из них делается откат.
 */
object ArchivePaidMigration {

    /** Выход 0.2.5: с этого дня архив знает статус «Оплачено». */
    val CUTOFF: LocalDate = LocalDate.of(2026, 9, 1)

    /**
     * Дни архива, которые меняются, — с новым содержимым. Дни без изменений,
     * дни от [CUTOFF] и позже и дни без данных YClients в ответ не попадают.
     */
    fun migrate(
        syncedDayData: Map<LocalDate, List<String>>,
        archivedDayData: Map<LocalDate, List<String>>,
    ): Map<LocalDate, List<String>> = buildMap {
        for ((date, archived) in archivedDayData) {
            if (!date.isBefore(CUTOFF)) continue
            val synced = syncedDayData[date] ?: continue
            migrateDay(synced, archived)?.let { put(date, it) }
        }
    }

    /** Новый список строк архива за день или `null`, если поднимать нечего. */
    fun migrateDay(synced: List<String>, archived: List<String>): List<String>? {
        val syncedSessions = synced.filter { it.isNotBlank() }.map { SessionParser.parse(it) }
        val usedSynced = BooleanArray(syncedSessions.size)
        var changed = false

        val result = archived.map { raw ->
            if (raw.isBlank()) return@map raw
            val archivedSession = SessionParser.parse(raw)
            val identity = ArchiveSyncCompare.sessionIdentity(archivedSession)
            val twinIndex = syncedSessions.indices.firstOrNull { index ->
                !usedSynced[index] &&
                    ArchiveSyncCompare.sessionIdentity(syncedSessions[index]) == identity
            } ?: return@map raw
            val twin = syncedSessions[twinIndex]
            val promoted = promote(archivedSession, twin) ?: return@map raw
            val promotedRaw = serialize(promoted)
            if (ArchiveSyncCompare.canonicalSession(promotedRaw) !=
                ArchiveSyncCompare.canonicalSession(serialize(twin))
            ) {
                return@map raw
            }
            usedSynced[twinIndex] = true
            changed = true
            promotedRaw
        }
        return if (changed) result else null
    }

    private fun upgrade(archived: AttendanceStatus, synced: AttendanceStatus): AttendanceStatus =
        if (archived == AttendanceStatus.ARRIVED && synced == AttendanceStatus.PAID) {
            AttendanceStatus.PAID
        } else {
            archived
        }

    /** Запись с поднятыми статусами или `null`, если поднимать нечего. */
    private fun promote(archived: Session, synced: Session): Session? {
        val promoted: Session = when {
            archived is Session.Student && synced is Session.Student ->
                archived.copy(status = upgrade(archived.status, synced.status))
            archived is Session.Diagnostics && synced is Session.Diagnostics ->
                archived.copy(status = upgrade(archived.status, synced.status))
            archived is Session.Intensive && synced is Session.Intensive -> {
                val syncedByName = synced.children.associateBy { it.name.trim().lowercase() }
                archived.copy(
                    status = upgrade(archived.status, synced.status),
                    children = archived.children.map { child ->
                        val twin = syncedByName[child.name.trim().lowercase()] ?: return@map child
                        child.copy(status = upgrade(child.status, twin.status))
                    },
                )
            }
            else -> return null
        }
        return promoted.takeIf { it != archived }
    }

    private fun serialize(session: Session): String = when (session) {
        is Session.Student -> SessionFormat.serializeStudentExtended(
            name = session.name,
            status = session.status,
            time = session.time,
            phone = session.phone,
            comment = session.comment,
            notCounted = session.notCounted,
        )
        is Session.Intensive -> SessionFormat.serializeIntensive(
            price = SessionFormat.intensivePriceField(session.amount, session.amountFixed),
            name = session.name,
            status = session.status,
            time = session.time,
            children = session.children,
            amountFixed = session.amountFixed,
        )
        is Session.Diagnostics -> SessionFormat.serializeDiagnostics(
            price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
            name = session.name,
            status = session.status,
            time = session.time,
        )
    }
}
