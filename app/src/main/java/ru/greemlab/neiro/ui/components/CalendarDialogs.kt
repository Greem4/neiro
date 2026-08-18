package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.theme.ApplyDialogGlass
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.glassContainerColor
import ru.greemlab.neiro.ui.calendar.getMonthName
import ru.greemlab.neiro.ui.settings.ProfitDisplaySettings
import ru.greemlab.neiro.ui.util.DialogEdgeFade
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.fadingEdges
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Диалоги статистики шире платформенного умолчания (~320.dp): пары
 * «подпись — сумма» должны вставать в одну строку и при крупном системном
 * шрифте, а не переноситься. На планшете ширина упирается в [StatsDialogMaxWidth].
 */
private val StatsDialogMaxWidth: Dp = 560.dp
private val StatsDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

private val StatsDialogModifier: Modifier
    get() = Modifier
        .widthIn(max = StatsDialogMaxWidth)
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 24.dp)

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
        containerColor = glassContainerColor(),
        modifier = StatsDialogModifier,
        properties = StatsDialogProperties,
        confirmButton = {
            // Размытие за окном включается изнутри диалога — только здесь
            // composable сидит в его собственном окне.
            ApplyDialogGlass()
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
                // Скролл: при крупном системном шрифте контент выше диалога, и без
                // него AlertDialog просто обрезал бы нижние строки.
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .fadingEdges(top = DialogEdgeFade, bottom = DialogEdgeFade),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LessonStatRow(
                        label = "Проведено",
                        value = stats.completedCount,
                        color = MaterialTheme.colorScheme.primary,
                        isBold = true,
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LessonStatRow(
                            label = "Занятий",
                            value = stats.completedSessionsCount,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LessonStatRow(
                            label = "Диагностик",
                            value = stats.completedDiagnosticsCount,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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

                if (stats.completedIntensives.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = "Проведенные интенсивы (${stats.completedIntensivesCount}):",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )

                    stats.completedIntensives.forEachIndexed { index, intensive ->
                        val dateStr = intensive.date.format(DateTimeFormatter.ofPattern("d MMMM", RU_LOCALE))
                        val name = intensive.name.ifBlank { "Интенсив" }
                        Text(
                            text = "• $name №${index + 1} проведен $dateStr",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
    )
}

/** Диалог с подробной информацией о прибыли за месяц. */
@Composable
fun ProfitDetailsDialog(
    currentMonth: YearMonth,
    stats: CalendarMonthStats,
    rates: EarningsContext,
    salaryAdvanceOnCard: Double,
    salaryMainOnCard: Double,
    salaryOnCardFallback: Double,
    display: ProfitDisplaySettings,
    onDismiss: () -> Unit,
) {
    val title = remember(currentMonth) { "Финансы за ${getMonthName(currentMonth)}" }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainerColor(),
        modifier = StatsDialogModifier,
        properties = StatsDialogProperties,
        confirmButton = {
            // Размытие за окном включается изнутри диалога — только здесь
            // composable сидит в его собственном окне.
            ApplyDialogGlass()
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
                // Скролл: при крупном системном шрифте контент выше диалога, и без
                // него AlertDialog просто обрезал бы нижние строки.
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .fadingEdges(top = DialogEdgeFade, bottom = DialogEdgeFade),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (display.showNetProfit) {
                    ProfitRow(
                        label = "Чистый доход",
                        value = stats.netProfit,
                        color = ScheduleHeaderGreen,
                        isBold = true,
                    )
                }

                if (display.showGrossEarned) {
                    if (display.showNetProfit) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    ProfitRow(
                        label = "Заработано всего",
                        value = stats.totalEarned,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (display.showTax && stats.taxAmount > 0.0) {
                    // Удержать больше заработанного за месяц нельзя — иначе при
                    // gross < taxAmount строка показывала абсурдный минус (P5).
                    ProfitRow(
                        label = "Налог за месяц",
                        value = minOf(stats.taxAmount, stats.totalEarned),
                        color = MaterialTheme.colorScheme.error,
                        prefix = "−",
                    )
                }

                if (display.showIntensiveEarnings && stats.intensiveEarnings > 0.0) {
                    ProfitRow(
                        label = "Заработано интенсив",
                        value = stats.intensiveEarnings,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (display.showDiagnosticsEarnings && stats.diagnosticsEarnings > 0.0) {
                    ProfitRow(
                        label = "Заработано диагностика",
                        value = stats.diagnosticsEarnings,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (display.showExpectedIncome) {
                    ProfitRow(
                        label = "Ожидаемый доход",
                        value = stats.expectedIncome,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (display.showPricePerSession && rates.pricePerSession > 0.0) {
                    ProfitRow(
                        label = "Стоимость одного занятия",
                        value = rates.pricePerSession,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (display.showTotalProfit) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ProfitRow(
                        label = "Всего",
                        value = stats.netProfit + stats.expectedIncome,
                        color = ScheduleHeaderGreen,
                        isBold = true,
                    )
                }

                val totalOnCard = salaryAdvanceOnCard + salaryMainOnCard
                if (totalOnCard > 0.0 || salaryOnCardFallback > 0.0) {
                    SalarySummaryPlaque(
                        salaryAdvanceOnCard = salaryAdvanceOnCard,
                        salaryMainOnCard = salaryMainOnCard,
                        salaryOnCardFallback = salaryOnCardFallback,
                        netProfit = stats.netProfit,
                    )
                }
            }
        },
    )
}

@Composable
private fun SalarySummaryPlaque(
    salaryAdvanceOnCard: Double,
    salaryMainOnCard: Double,
    salaryOnCardFallback: Double,
    netProfit: Double,
    modifier: Modifier = Modifier,
) {
    val totalOnCard = remember(salaryAdvanceOnCard, salaryMainOnCard, salaryOnCardFallback) {
        val fromParts = salaryAdvanceOnCard + salaryMainOnCard
        if (fromParts > 0.0) fromParts else salaryOnCardFallback
    }
    val salaryInHand = remember(netProfit, totalOnCard) {
        (netProfit - totalOnCard).coerceAtLeast(0.0)
    }
    val showBreakdown = salaryAdvanceOnCard > 0.0 || salaryMainOnCard > 0.0
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProfitRow(
                label = "Зарплата на карту",
                value = totalOnCard,
                color = MaterialTheme.colorScheme.onSurface,
                approximate = true,
                isBold = true,
            )
            if (showBreakdown) {
                Column(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (salaryAdvanceOnCard > 0.0) {
                        ProfitRow(
                            label = "Аванс",
                            value = salaryAdvanceOnCard,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            compact = true,
                            approximate = true,
                        )
                    }
                    if (salaryMainOnCard > 0.0) {
                        ProfitRow(
                            label = "Зарплата",
                            value = salaryMainOnCard,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            compact = true,
                            approximate = true,
                        )
                    }
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                modifier = Modifier.padding(vertical = 2.dp),
            )
            ProfitRow(
                label = "Зарплата на руки",
                value = salaryInHand,
                color = ScheduleHeaderGreen,
                isBold = true,
            )
        }
    }
}

/** Диалог-предложение завершить регистрацию/настройку профиля. */
@Composable
fun RegistrationPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainerColor(),
        title = { Text("Требуется профиль") },
        text = { Text("Чтобы планировать занятия и видеть статистику, нужно сначала настроить ваш профиль.") },
        confirmButton = {
            // Размытие за окном включается изнутри диалога — только здесь
            // composable сидит в его собственном окне.
            ApplyDialogGlass()
            Button(onClick = onConfirm) { Text("Создать профиль") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Позже") }
        },
        shape = RoundedCornerShape(28.dp),
    )
}
