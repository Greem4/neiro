package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.theme.ProfitGreen
import ru.greemlab.neiro.ui.calendar.getMonthName
import java.time.YearMonth

/** Диалог с подробной статистикой занятий за месяц. */
@Composable
fun LessonsDetailsDialog(
    currentMonth: YearMonth,
    stats: CalendarMonthStats,
    onDismiss: () -> Unit,
) {
    val title = remember(currentMonth) { "Занятия за ${getMonthName(currentMonth)}" }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LessonStatRow(
                    label = "Проведено",
                    value = stats.completedCount,
                    color = MaterialTheme.colorScheme.primary,
                    isBold = true,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                LessonStatRow(
                    label = "Всего запланировано",
                    value = stats.totalScheduled,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LessonStatRow(
                    label = "Осталось / Не подтверждено",
                    value = stats.remainingCount,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Диалог с подробной информацией о прибыли за месяц. */
@Composable
fun ProfitDetailsDialog(
    currentMonth: YearMonth,
    stats: CalendarMonthStats,
    onDismiss: () -> Unit,
) {
    val title = remember(currentMonth) { "Финансы за ${getMonthName(currentMonth)}" }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfitRow(
                    label = "Чистый доход",
                    value = stats.netProfit,
                    color = ProfitGreen,
                    isBold = true,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                ProfitRow(
                    label = "Заработано всего ",
                    value = stats.totalEarned,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (stats.taxAmount > 0.0) {
                    ProfitRow(
                        label = "Налог за месяц",
                        value = stats.taxAmount,
                        color = MaterialTheme.colorScheme.error,
                        prefix = "−",
                    )
                }

                if (stats.intensiveEarnings > 0.0) {
                    ProfitRow(
                        label = "Заработано интенсив",
                        value = stats.intensiveEarnings,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (stats.diagnosticsEarnings > 0.0) {
                    ProfitRow(
                        label = "Заработано диагностика",
                        value = stats.diagnosticsEarnings,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                ProfitRow(
                    label = "Ожидаемый доход",
                    value = stats.expectedIncome,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/** Диалог-предложение завершить регистрацию/настройку профиля. */
@Composable
fun RegistrationPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
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
        shape = RoundedCornerShape(28.dp),
    )
}
