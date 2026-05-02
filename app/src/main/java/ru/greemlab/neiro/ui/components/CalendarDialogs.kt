package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.ui.calendar.getMonthName
import java.time.YearMonth

/**
 * Диалог с подробной статистикой занятий.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LessonsDetailsDialog(
    currentMonth: YearMonth,
    stats: CalendarMonthStats,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = "Занятия за ${getMonthName(currentMonth)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LessonStatRow(
                    label = "Проведено",
                    value = stats.completedCount,
                    color = MaterialTheme.colorScheme.primary,
                    isBold = true
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LessonStatRow(
                    label = "Всего запланировано",
                    value = stats.totalScheduled,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LessonStatRow(
                    label = "Осталось / Не подтверждено",
                    value = stats.remainingCount,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * Диалог с подробной информацией о прибыли.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ProfitDetailsDialog(
    currentMonth: YearMonth,
    stats: CalendarMonthStats,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = "Финансы за ${getMonthName(currentMonth)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProfitRow(
                    label = "Заработано чистыми",
                    value = stats.netProfit,
                    color = Color(0xFF4CAF50),
                    isBold = true
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ProfitRow(
                    label = "Заработано всего (грязными)",
                    value = stats.grossEarnings,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ProfitRow(
                    label = "Ожидаемый доход (план)",
                    value = stats.expectedNet,
                    color = MaterialTheme.colorScheme.primary
                )
                if (stats.taxAmount > 0) {
                    ProfitRow(
                        label = "Налог (вычтен)",
                        value = stats.taxAmount,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        prefix = "-"
                    )
                }
            }
        }
    )
}

/**
 * Диалог-предложение завершить регистрацию/настройку профиля.
 */
@Composable
fun RegistrationPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Требуется профиль") },
        text = { Text("Чтобы планировать занятия и видеть статистику, нужно сначала настроить ваш профиль.") },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Создать профиль") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Позже") }
        },
        shape = RoundedCornerShape(28.dp)
    )
}
