package ru.greemlab.neiro.ui.components.daydetails

enum class StudentItemType {
    STUDENT, INTENSIVE, DIAGNOSTICS
}

/**
 * Внутренняя модель для отслеживания прихода ученика или спец. занятий.
 */
data class StudentItem(
    val id: String,
    val name: String,
    val attended: Boolean,
    val type: StudentItemType = StudentItemType.STUDENT,
    val price: String = ""
)
