package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
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

private val WEEK_DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private const val WEEKEND_START_INDEX = 5

/**
 * Строка с сокращёнными названиями дней недели (Пн, Вт, ...).
 * Отображается над сеткой календаря. Выходные дни подсвечиваются другим цветом.
 */
@Composable
fun WeekDaysRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val weekdayColor = MaterialTheme.colorScheme.onSurfaceVariant
        val weekendColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        WEEK_DAYS.forEachIndexed { index, day ->
            Text(
                text = day,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
                color = if (index >= WEEKEND_START_INDEX) weekendColor else weekdayColor,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
