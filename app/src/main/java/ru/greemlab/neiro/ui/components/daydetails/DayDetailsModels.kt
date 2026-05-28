package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.runtime.Immutable

enum class StudentItemType {
    STUDENT, INTENSIVE, DIAGNOSTICS
}

/**
 * Строка ручного редактирования дня (офлайн / архив).
 *
 * Сейчас в [ru.greemlab.neiro.ui.components.DayDetailsDialog] живой календарь
 * читается из YClients; этот тип и [StudentItemRow] намеренно оставлены для
 * будущего режима правки архивных дней без API (см. TODO «Архив»).
 *
 * [attended] — упрощённая отметка для ручного ввода (`name|true/false`).
 * При подключении редактора архива понадобится [ru.greemlab.neiro.ui.calendar.AttendanceStatus]
 * (ожидание / подтвердил / отмена / пришёл), как в таймлайне просмотра.
 *
 * Помечена `@Immutable` — Compose не будет проверять каждое поле на изменение
 * через рефлексию, что снижает рекомпозиции при работе с большим списком.
 */
@Immutable
data class StudentItem(
    val id: String,
    val name: String,
    val attended: Boolean,
    val type: StudentItemType = StudentItemType.STUDENT,
    val price: String = "",
)
