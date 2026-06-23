package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.runtime.Immutable

enum class StudentItemType {
    STUDENT, INTENSIVE, DIAGNOSTICS
}

/**
 * Строка ручного редактирования дня (режим планирования в диалоге дня).
 *
 * Live-календарь синхронизируется с YClients; в архиве статусы меняются
 * через [AttendanceStatusPickerIcon] в таймлайне.
 *
 * [attended] — упрощённая отметка для ручного ввода (`name|true/false`).
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
