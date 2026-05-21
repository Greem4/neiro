package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
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
import ru.greemlab.neiro.theme.ExpectedAmber
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ProfitGreen
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.StatusExpectedMint
import ru.greemlab.neiro.theme.StatusRedBody
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

/**
 * Элемент расписания: фон стандартный, цвет меняется только у имени.
 */
@Composable
fun ScheduleSlotItem(
    time: String,
    name: String,
    comment: String,
    status: AttendanceStatus,
    modifier: Modifier = Modifier,
    isDiagnostics: Boolean = false,
) {
    // Цвет для текста имени в зависимости от статуса
    val nameColor = when (status) {
        AttendanceStatus.ARRIVED -> ProfitGreen
        AttendanceStatus.CONFIRMED -> ExpectedAmber
        AttendanceStatus.CANCELLED -> StatusRedBody
        AttendanceStatus.EXPECTED -> StatusExpectedMint
    }

    val icon = when (status) {
        AttendanceStatus.ARRIVED -> Icons.Rounded.Add
        AttendanceStatus.CONFIRMED -> Icons.Rounded.Check
        AttendanceStatus.CANCELLED -> Icons.Rounded.Remove
        AttendanceStatus.EXPECTED -> Icons.Rounded.History
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        // Фон карточки - стандартный surfaceVariant
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Зеленая полоска слева («поле зеленое»)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(if (isDiagnostics) Color(0xFF5C6BC0) else ScheduleHeaderGreen)
            )

            // Время (стандартный цвет)
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

            // Имя (ЦВЕТ МЕНЯЕТСЯ) и комментарий
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = name.ifEmpty { "Без имени" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isDiagnostics) Color(0xFF5C6BC0) else nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (comment.isNotEmpty()) {
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Иконка (зеленая на белом)
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(24.dp),
                shape = CircleShape,
                color = Color.White,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isDiagnostics) Color(0xFF5C6BC0) else ScheduleHeaderGreen,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScheduleSlotItemPreview() {
    NeiroTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScheduleSlotItem(
                time = "10:00",
                name = "Ерженинов Владислав",
                comment = "7.6(Юля)",
                status = AttendanceStatus.EXPECTED,
            )
            ScheduleSlotItem(
                time = "12:00",
                name = "Пирогов Лев",
                comment = "Нейрокоррекция",
                status = AttendanceStatus.ARRIVED,
            )
            ScheduleSlotItem(
                time = "15:00",
                name = "Петрушкин Михаил",
                comment = "1,9г (Алина)",
                status = AttendanceStatus.CANCELLED,
            )
        }
    }
}
