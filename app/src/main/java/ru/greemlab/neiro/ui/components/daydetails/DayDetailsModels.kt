package ru.greemlab.neiro.ui.components.daydetails

/**
 * Внутренняя модель для отслеживания прихода ученика.
 */
data class StudentItem(
    val id: String,
    val name: String,
    val attended: Boolean
)
