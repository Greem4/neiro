package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.runtime.Immutable

enum class StudentItemType {
    STUDENT, INTENSIVE, DIAGNOSTICS
}

/**
 * Внутренняя модель строки в диалоге редактирования дня.
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
