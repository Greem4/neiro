package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.greemlab.neiro.theme.NeiroTheme
import java.time.LocalDate

/**
 * Компонент отдельной ячейки дня в календаре.
 *
 * @param date Дата ячейки.
 * @param today Текущая дата (передаётся снаружи, чтобы не пересчитывать в каждой ячейке).
 * @param isCurrentMonth Принадлежит ли дата текущему отображаемому месяцу.
 * @param isSelected Выбрана ли эта дата пользователем.
 * @param namesCount Количество записей на этот день.
 * @param isWorkingDay Подсвечивать ли как рабочий день.
 * @param onDateClick Обработчик нажатия.
 */
@Composable
fun DayCard(
    date: LocalDate,
    today: LocalDate,
    isCurrentMonth: Boolean,
    isSelected: Boolean,
    namesCount: Int = 0,
    isWorkingDay: Boolean = true,
    onDateClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToday = date == today
    val backgroundColor =
        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent

    val dayNumber = remember(date) { date.dayOfMonth.toString() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable { onDateClick(date) },
        contentAlignment = Alignment.Center,
    ) {
        if (isToday) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = CircleShape,
                    ),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = if (isWorkingDay) Modifier else Modifier.alpha(0.3f),
        ) {
            Text(
                text = dayNumber,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    isToday -> MaterialTheme.colorScheme.primary
                    !isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (namesCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    if (namesCount <= 3) {
                        val dotColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        }
                        repeat(namesCount) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(color = dotColor, shape = CircleShape),
                            )
                        }
                    } else {
                        Text(
                            text = namesCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 50, heightDp = 50)
@Composable
private fun DayCardPreview() {
    NeiroTheme {
        DayCard(
            date = LocalDate.now(),
            today = LocalDate.now(),
            isCurrentMonth = true,
            isSelected = false,
            namesCount = 3,
            onDateClick = {},
        )
    }
}
