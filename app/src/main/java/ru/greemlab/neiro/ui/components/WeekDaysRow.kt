package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Строка с сокращенными названиями дней недели (Пн, Вт, ...).
 * Отображается над сеткой календаря. Выходные дни подсвечиваются другим цветом.
 */
@Composable
fun WeekDaysRow() {
    val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        days.forEachIndexed { index, day ->
            // Сб и Вс — выходные дни
            val isWeekend = index >= 5

            Text(
                text = day,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                color = if (isWeekend) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
