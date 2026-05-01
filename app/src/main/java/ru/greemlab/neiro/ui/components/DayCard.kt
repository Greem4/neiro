package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import ru.greemlab.neiro.theme.NeiroTheme

/**
 * Компонент отдельной ячейки дня в календаре.
 * 
 * @param date Дата, которую отображает ячейка.
 * @param isCurrentMonth Принадлежит ли дата текущему выбранному месяцу.
 * @param isSelected Выбрана ли эта дата пользователем.
 * @param namesCount Количество людей, записанных на этот день.
 * @param onDateClick Обработчик нажатия на ячейку.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DayCard(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isSelected: Boolean,
    namesCount: Int = 0,
    isWorkingDay: Boolean = true, // По умолчанию все рабочие, если не указано иное
    onDateClick: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val isToday = date == today

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
            )
            .clickable { onDateClick(date) },
        contentAlignment = Alignment.Center
    ) {
        // Подсветка "Сегодня"
        if (isToday) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = CircleShape
                    )
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(if (isWorkingDay) 1f else 0.3f) // Полупрозрачные нерабочие дни
        ) {
            // Число месяца
            Text(
                text = date.dayOfMonth.toString(),
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    isToday -> MaterialTheme.colorScheme.primary
                    !isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium
            )
            
            // Превью: индикатор количества записей (точки или число)
            if (namesCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (namesCount <= 3) {
                        // Если мало людей - рисуем точки
                        repeat(namesCount) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary 
                                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    )
                            )
                        }
                    } else {
                        // Если много - пишем число
                        Text(
                            text = namesCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(widthDp = 50, heightDp = 50)
@Composable
fun DayCardPreview() {
    NeiroTheme {
        DayCard(
            date = LocalDate.now(),
            isCurrentMonth = true,
            isSelected = false,
            namesCount = 3,
            onDateClick = {}
        )
    }
}
