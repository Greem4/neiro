package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

@Composable
fun WeekDaysRow() {
    val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        days.forEachIndexed { index, day ->
            // Индексы 5 и 6 — это суббота и воскресенье
            val isWeekend = index >= 5

            Text(
                text = day,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                // Настраиваем цвета:
                color = if (isWeekend) {
                    MaterialTheme.colorScheme.error // Цвет для выходных (по умолчанию красный)
                } else {
                    MaterialTheme.colorScheme.onBackground // Цвет для будней (белый в темной, черный в светлой)
                },
                // Делаем текст чуть жирнее для красоты
                fontWeight = FontWeight.Bold,
                // Подвязываем стиль из твоего Type.kt (можно поменять на bodySmall)
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}