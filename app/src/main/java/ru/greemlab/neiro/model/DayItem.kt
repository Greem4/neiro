package ru.greemlab.neiro.model

import androidx.compose.ui.graphics.Color

/*
- Модель одного дня календаря
- Пока хранит только число
*/

data class DayItem(
    val day: Int,
    val color: Color = Color.Gray
)