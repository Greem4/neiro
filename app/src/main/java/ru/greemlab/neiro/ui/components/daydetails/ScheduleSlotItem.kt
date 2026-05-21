package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

private val StatusGreen = Color(0xFF4CAF50)
private val StatusGreenDark = Color(0xFF2E7D32)
private val StatusOrange = Color(0xFFFF9800)
private val StatusRed = Color(0xFFF44336)

/**
 * Компактный элемент расписания для отображения записи.
 *
 * @param time Время записи, например "10:00-10:50"
 * @param name Имя клиента
 * @param comment Комментарий к записи (например, возраст, объём)
 * @param status Статус записи
 */
@Composable
fun ScheduleSlotItem(
    time: String,
    name: String,
    comment: String,
    status: AttendanceStatus,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (status) {
        AttendanceStatus.EXPECTED -> StatusGreen
        AttendanceStatus.CONFIRMED -> StatusOrange
        AttendanceStatus.ARRIVED -> StatusGreenDark
        AttendanceStatus.CANCELLED -> StatusRed
    }

    val backgroundColor = statusColor.copy(alpha = 0.12f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Цветная полоска слева
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(statusColor)
            )

            // Время
            if (time.isNotEmpty()) {
                Text(
                    text = time.substringBefore("-"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Основная информация
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            ) {
                // Имя + комментарий
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name.ifEmpty { "Без имени" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (comment.isNotEmpty()) {
                        Text(
                            text = " $comment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

            }

            // Индикатор статуса
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(28.dp),
                shape = CircleShape,
                color = statusColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (status) {
                            AttendanceStatus.EXPECTED -> Icons.Rounded.Add
                            AttendanceStatus.CONFIRMED,
                            AttendanceStatus.ARRIVED,
                            -> Icons.Rounded.Check
                            AttendanceStatus.CANCELLED -> Icons.Rounded.Remove
                        },
                        contentDescription = when (status) {
                            AttendanceStatus.EXPECTED -> "Ожидает"
                            AttendanceStatus.CONFIRMED -> "Подтвердил, что придёт"
                            AttendanceStatus.ARRIVED -> "Пришёл"
                            AttendanceStatus.CANCELLED -> "Не пришёл"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun ScheduleSlotItemPreviewExpected() {
    NeiroTheme {
        Column(modifier = Modifier.padding(8.dp)) {
            ScheduleSlotItem(
                time = "10:00-10:50",
                name = "Шахабутдинов Тимур",
                comment = "5л",
                status = AttendanceStatus.EXPECTED,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ScheduleSlotItem(
                time = "11:00-11:50",
                name = "Зорин Владимир",
                comment = "2.8Г",
                status = AttendanceStatus.CONFIRMED,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ScheduleSlotItem(
                time = "12:00-12:50",
                name = "Савельев Михаил",
                comment = "5л",
                status = AttendanceStatus.ARRIVED,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ScheduleSlotItem(
                time = "14:00-14:50",
                name = "Якубов Рашит",
                comment = "6,11л",
                status = AttendanceStatus.CANCELLED,
            )
        }
    }
}
