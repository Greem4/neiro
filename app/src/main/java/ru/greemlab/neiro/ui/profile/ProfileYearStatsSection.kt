package ru.greemlab.neiro.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.greemlab.neiro.domain.models.PriceOrigin
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.ui.calendar.MonthPriceMeta
import ru.greemlab.neiro.ui.calendar.PriceSource
import ru.greemlab.neiro.ui.calendar.ProfileYearStats
import ru.greemlab.neiro.ui.calendar.getChartMonthAbbreviation
import ru.greemlab.neiro.ui.calendar.getMonthName
import ru.greemlab.neiro.ui.settings.ProfitDisplaySettings
import ru.greemlab.neiro.ui.settings.SettingsGroupCard
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.Month
import java.time.YearMonth

private val StatsContentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
/** Доля высоты области графика под столбцы (линия использует [ChartLineHeightFraction]). */
private const val ChartBarHeightFraction = 0.85f
private const val ChartLineHeightFraction = 0.96f
private val ChartMonthLabelStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontSize = 9.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.sp,
    )

@Composable
fun ProfileYearStatsSection(
    stats: ProfileYearStats,
    availableYears: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    display: ProfitDisplaySettings = ProfitDisplaySettings(),
    onMonthPriceEdited: (YearMonth, Double) -> Unit = { _, _ -> },
    onMonthDiscrepancyResolved: (YearMonth, Double) -> Unit = { _, _ -> },
    onMonthFactAccepted: (YearMonth) -> Unit = {},
    onMonthUnfrozen: (YearMonth) -> Unit = {},
    onMonthRefreshed: (YearMonth) -> Unit = {},
    syncingMonth: YearMonth? = null,
    monthSyncNote: MonthSyncNote? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val showDiscrepancyMark = display.showDiscrepancy && stats.hasUnresolvedMonths

    val peakIndex = remember(stats.monthlyNet) {
        stats.monthlyNet.indices.maxByOrNull { stats.monthlyNet.getOrElse(it) { 0.0 } } ?: 0
    }
    var selectedMonthIndex by remember(stats.year) { mutableIntStateOf(peakIndex) }
    var detailsExpanded by remember(stats.year) { mutableStateOf(false) }
    var priceEditorMonth by remember(stats.year) { mutableStateOf<Int?>(null) }
    var discrepancyMonth by remember(stats.year) { mutableStateOf<Int?>(null) }
    LaunchedEffect(stats.year, peakIndex) {
        selectedMonthIndex = peakIndex
    }
    val chartProgress = remember { Animatable(0f) }
    LaunchedEffect(expanded, stats.year) {
        if (expanded) {
            chartProgress.snapTo(0f)
            chartProgress.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
        } else {
            chartProgress.snapTo(0f)
        }
    }

    val yearIndex = availableYears.indexOf(selectedYear).coerceAtLeast(0)
    val canGoNewer = yearIndex > 0
    val canGoOlder = yearIndex < availableYears.lastIndex

    val collapsedSubtitle = remember(stats) {
        buildString {
            append(stats.completedSessions)
            append(" ")
            append(pluralSessions(stats.completedSessions))
            if (stats.totalNetEarned != 0.0) {
                append(" · ")
                append(formatRubles(stats.totalNetEarned))
            }
        }
    }

    SettingsGroupCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(StatsContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.BarChart,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Статистика", style = MaterialTheme.typography.bodyLarge)
                    // Человек должен видеть, что месяц чего-то ждёт, не разворачивая секцию.
                    if (showDiscrepancyMark) {
                        Spacer(modifier = Modifier.width(6.dp))
                        DiscrepancyBadge()
                    }
                }
                Text(
                    text = if (expanded) "За ${stats.year} год" else collapsedSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = StatsContentPadding.calculateStartPadding(LayoutDirection.Ltr),
                        end = StatsContentPadding.calculateEndPadding(LayoutDirection.Ltr),
                        bottom = 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                YearSelectorRow(
                    year = selectedYear,
                    canGoOlder = canGoOlder,
                    canGoNewer = canGoNewer,
                    onOlder = {
                        if (canGoOlder) onYearSelected(availableYears[yearIndex + 1])
                    },
                    onNewer = {
                        if (canGoNewer) onYearSelected(availableYears[yearIndex - 1])
                    },
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AnimatedContent(
                        targetState = stats,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                        },
                        label = "yearStatsContent",
                        modifier = Modifier.fillMaxWidth(),
                    ) { animatedStats ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            YearStatRow(
                                label = "Проведено занятий",
                                value = animatedStats.completedSessions.toString(),
                                icon = Icons.Rounded.School,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            YearStatRow(
                                label = "Чистыми за год",
                                value = formatRubles(animatedStats.totalNetEarned),
                                icon = Icons.Rounded.Payments,
                                tint = ScheduleHeaderGreen,
                            )
                        }
                    }
                }

                Text(
                    text = "Чистая прибыль по месяцам",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )

                YearNetProfitChart(
                    year = stats.year,
                    monthlyNet = stats.monthlyNet,
                    monthlyCompleted = stats.monthlyCompleted,
                    months = stats.months,
                    peakIndex = peakIndex,
                    selectedMonthIndex = selectedMonthIndex,
                    onMonthSelected = { selectedMonthIndex = it },
                    onSummaryClick = { detailsExpanded = !detailsExpanded },
                    showDiscrepancy = display.showDiscrepancy,
                    progress = chartProgress.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHART_HEIGHT),
                )

                // Раскрытие месяца — рядом с графиком, а не внутри него:
                // геометрия Canvas от этого не зависит.
                AnimatedVisibility(
                    visible = detailsExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    val detailsMonth = YearMonth.of(stats.year, selectedMonthIndex + 1)
                    MonthPriceDetails(
                        month = detailsMonth,
                        meta = stats.months.getOrElse(selectedMonthIndex) { MonthPriceMeta() },
                        display = display,
                        isSyncing = syncingMonth == detailsMonth,
                        // Итог показываем только тому месяцу, к которому он
                        // относится: переключил месяц — чужой ответ не висит.
                        syncNote = monthSyncNote?.takeIf { it.month == detailsMonth }?.text,
                        onEditPrice = { priceEditorMonth = selectedMonthIndex },
                        onReviewDiscrepancy = { discrepancyMonth = selectedMonthIndex },
                        onUnfreeze = { onMonthUnfrozen(detailsMonth) },
                        onRefresh = { onMonthRefreshed(detailsMonth) },
                    )
                }
            }
        }
    }

    priceEditorMonth?.let { index ->
        val month = YearMonth.of(stats.year, index + 1)
        MonthPriceEditorDialog(
            month = month,
            currentPrice = stats.months.getOrElse(index) { MonthPriceMeta() }.pricePerSession,
            onDismiss = { priceEditorMonth = null },
            onConfirm = { price ->
                priceEditorMonth = null
                onMonthPriceEdited(month, price)
            },
        )
    }

    discrepancyMonth?.let { index ->
        val month = YearMonth.of(stats.year, index + 1)
        val meta = stats.months.getOrElse(index) { MonthPriceMeta() }
        // Без цены из факта разбирать нечего: кнопка «Разобрать» и появляется
        // только при расхождении, но состояние переживает смену года.
        meta.factPricePerSession?.let { factPrice ->
            MonthDiscrepancyDialog(
                month = month,
                meta = meta,
                factPricePerSession = factPrice,
                onDismiss = { discrepancyMonth = null },
                onConfirmPrice = { price ->
                    discrepancyMonth = null
                    onMonthDiscrepancyResolved(month, price)
                },
                onAcceptFact = {
                    discrepancyMonth = null
                    onMonthFactAccepted(month)
                },
            )
        }
    }
}

/** Высота области графика: Canvas считает геометрию от неё. */
private val CHART_HEIGHT = 188.dp

/** Точка о расхождении: заметнее дисклеймера, но не крик. */
@Composable
private fun DiscrepancyBadge() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.error, CircleShape),
    )
}

/**
 * Раскрытые данные месяца: цена, откуда она, диагностики, интенсивы, налог,
 * «закрыт» и кнопки правки/разбора (FOUNDATION 5).
 */
@Composable
private fun MonthPriceDetails(
    month: YearMonth,
    meta: MonthPriceMeta,
    display: ProfitDisplaySettings,
    onEditPrice: () -> Unit,
    onReviewDiscrepancy: () -> Unit,
    onUnfreeze: () -> Unit,
    onRefresh: () -> Unit = {},
    isSyncing: Boolean = false,
    syncNote: String? = null,
    modifier: Modifier = Modifier,
) {
    val showReview = display.showDiscrepancy && meta.needsReview
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = getMonthName(month),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )

            MonthDetailRow("Цена занятия", formatRubles(meta.pricePerSession))
            MonthDetailRow("Откуда цена", priceSourceLabel(meta))
            if (meta.priceDiagnostics > 0.0) {
                MonthDetailRow("Диагностика", formatRubles(meta.priceDiagnostics))
            }
            if (meta.priceIntensiveChild > 0.0) {
                MonthDetailRow("Интенсив за ребёнка", formatRubles(meta.priceIntensiveChild))
            }
            if (display.showTax && meta.tax > 0.0) {
                MonthDetailRow("Налог", formatRubles(meta.tax))
            }
            if (display.showDiscrepancy && meta.factGross != null) {
                MonthDetailRow("В YClients за месяц", formatRubles(meta.factGross))
            }

            if (meta.origin == PriceOrigin.AUTO && meta.source != PriceSource.FACT) {
                // Про цену из YClients так писать нельзя: она и есть начисление,
                // а не прикидка приложения.
                Text(
                    text = "Считано по цене из профиля — проверьте",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showReview) {
                Text(
                    text = "В YClients другая сумма",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEditPrice) { Text("Поправить цену") }
                MonthSyncButton(isSyncing = isSyncing, onClick = onRefresh)
                if (showReview) {
                    TextButton(onClick = onReviewDiscrepancy) { Text("Разобрать") }
                }
                if (meta.frozen) {
                    TextButton(onClick = onUnfreeze) { Text("Разморозить") }
                }
            }

            if (syncNote != null) {
                Text(
                    text = syncNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (meta.frozen) {
                Text(
                    text = "Месяц закрыт — начисление получено",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Кружок синхронизации месяца: перетянуть начисление именно за него.
 *
 * Нужен рядом с правкой цены: закрытый месяц синк больше не пересчитывает сам,
 * и это единственный способ сказать «посчитай заново» точечно, не гоняя всю
 * историю. Во время запроса кнопка гаснет — иначе десять нажатий подряд
 * запустили бы десять запросов.
 */
@Composable
private fun MonthSyncButton(isSyncing: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = !isSyncing,
        modifier = Modifier.size(36.dp),
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = "Обновить месяц из YClients",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MonthDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun priceSourceLabel(meta: MonthPriceMeta): String = when {
    meta.origin == PriceOrigin.MANUAL -> "вы поправили"
    meta.source == PriceSource.FACT -> "из YClients"
    else -> "по вашей цене"
}

/** Правка цены месяца — одно поле: сумма пересчитывается сама (FOUNDATION 3.1). */
@Composable
private fun MonthPriceEditorDialog(
    month: YearMonth,
    currentPrice: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember(month, currentPrice) {
        mutableStateOf(if (currentPrice > 0.0) currentPrice.toLong().toString() else "")
    }
    val price = text.filter { it.isDigit() }.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Цена занятия · ${getMonthName(month)}") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() }.take(7) },
                label = { Text("Цена за занятие, ₽") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { price?.let(onConfirm) },
                enabled = price != null && price > 0.0,
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/**
 * Разбор расхождения (FOUNDATION 4): три варианта, решение уходит в историю
 * и больше не спрашивается.
 */
@Composable
private fun MonthDiscrepancyDialog(
    month: YearMonth,
    meta: MonthPriceMeta,
    factPricePerSession: Double,
    onDismiss: () -> Unit,
    onConfirmPrice: (Double) -> Unit,
    onAcceptFact: () -> Unit,
) {
    val appPrice = meta.pricePerSession
    val sessions = meta.billableSessions
    var choice by remember(month) { mutableStateOf(DiscrepancyChoice.APP) }
    var customText by remember(month) { mutableStateOf("") }
    val customPrice = customText.filter { it.isDigit() }.toDoubleOrNull()

    // FACT цены не фиксирует: месяц уходит под начисление и следит за ним дальше.
    val chosenPrice = when (choice) {
        DiscrepancyChoice.APP -> appPrice
        DiscrepancyChoice.FACT -> factPricePerSession
        DiscrepancyChoice.CUSTOM -> customPrice
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("${getMonthName(month)} — расхождение") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Ваша цена ${formatRubles(appPrice)} × $sessions " +
                        "= ${formatRubles(appPrice * sessions)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "YClients начислил ${formatRubles(factPricePerSession)} × $sessions " +
                        "= ${formatRubles(factPricePerSession * sessions)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Разница ${formatRubles((factPricePerSession - appPrice) * sessions)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Text("Что отправляем в историю?", style = MaterialTheme.typography.bodySmall)

                DiscrepancyOptionRow(
                    selected = choice == DiscrepancyChoice.APP,
                    label = "${formatRubles(appPrice)} — оставить свою",
                    onClick = { choice = DiscrepancyChoice.APP },
                )
                DiscrepancyOptionRow(
                    selected = choice == DiscrepancyChoice.FACT,
                    label = "${formatRubles(factPricePerSession)} — как в YClients",
                    onClick = { choice = DiscrepancyChoice.FACT },
                )
                DiscrepancyOptionRow(
                    selected = choice == DiscrepancyChoice.CUSTOM,
                    label = "Другая цена",
                    onClick = { choice = DiscrepancyChoice.CUSTOM },
                )
                if (choice == DiscrepancyChoice.CUSTOM) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { input -> customText = input.filter { it.isDigit() }.take(7) },
                        label = { Text("Цена за занятие, ₽") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (choice == DiscrepancyChoice.FACT) {
                        onAcceptFact()
                    } else {
                        chosenPrice?.let(onConfirmPrice)
                    }
                },
                enabled = chosenPrice != null && chosenPrice > 0.0,
            ) {
                Text("В историю")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Позже") }
        },
    )
}

private enum class DiscrepancyChoice { APP, FACT, CUSTOM }

@Composable
private fun DiscrepancyOptionRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun YearSelectorRow(
    year: Int,
    canGoOlder: Boolean,
    canGoNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = onOlder,
            enabled = canGoOlder,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "Предыдущий год",
            )
        }
        Text(
            text = year.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        IconButton(
            onClick = onNewer,
            enabled = canGoNewer,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Следующий год",
            )
        }
    }
}

@Composable
private fun YearStatRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.18f),
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.CenterStart),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 34.dp, end = 4.dp)
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun YearNetProfitChart(
    year: Int,
    monthlyNet: List<Double>,
    monthlyCompleted: List<Int>,
    months: List<MonthPriceMeta>,
    peakIndex: Int,
    selectedMonthIndex: Int,
    onMonthSelected: (Int) -> Unit,
    onSummaryClick: () -> Unit,
    showDiscrepancy: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val accent = ScheduleHeaderGreen
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    val chartSurface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    val chartBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    val maxValue = remember(monthlyNet) { monthlyNet.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0 }

    val monthLabels = remember {
        Month.entries.map { getChartMonthAbbreviation(it) }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(chartSurface)
            .border(1.dp, chartBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val chartBottom = size.height
                val chartTop = 6.dp.toPx()
                val chartHeight = chartBottom - chartTop
                val barCount = 12
                val slotWidth = size.width / barCount
                val barWidth = slotWidth * 0.56f
                val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                val minBarHeight = 3.dp.toPx()

                listOf(0.33f, 0.66f, 1f).forEach { guide ->
                    val y = chartBottom - chartHeight * guide
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                val barChartHeight = chartHeight * ChartBarHeightFraction
                val lineChartHeight = chartHeight * ChartLineHeightFraction

                val barCenters = FloatArray(barCount)
                val barTops = FloatArray(barCount)
                val lineTops = FloatArray(barCount)

                monthlyNet.take(barCount).forEachIndexed { index, net ->
                    val isSelected = index == selectedMonthIndex
                    val dimmed = !isSelected
                    val fraction = (net / maxValue).toFloat().coerceIn(0f, 1f)
                    val animatedFraction = fraction * progress
                    val barHeight = barChartHeight * animatedFraction
                    val left = slotWidth * index + (slotWidth - barWidth) / 2f
                    val top = chartBottom - barHeight
                    barCenters[index] = left + barWidth / 2f
                    barTops[index] = top
                    lineTops[index] = chartBottom - lineChartHeight * animatedFraction

                    if (isSelected) {
                        val slotLeft = slotWidth * index
                        drawRoundRect(
                            color = primary.copy(alpha = 0.1f),
                            topLeft = Offset(slotLeft + 1.dp.toPx(), chartTop),
                            size = Size(slotWidth - 2.dp.toPx(), chartHeight),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                        )
                    }

                    drawRoundRect(
                        color = trackColor.copy(alpha = if (dimmed) 0.7f else 1f),
                        topLeft = Offset(left, chartTop),
                        size = Size(barWidth, chartHeight),
                        cornerRadius = corner,
                    )

                    if (barHeight > 0f) {
                        val barAlpha = when {
                            isSelected -> 1f
                            dimmed -> 0.48f
                            else -> 1f
                        }
                        val barBrush = when {
                            isSelected -> Brush.verticalGradient(
                                colors = listOf(
                                    accent.copy(alpha = barAlpha),
                                    accent.copy(alpha = 0.55f * barAlpha),
                                ),
                                startY = top,
                                endY = chartBottom,
                            )
                            index == peakIndex && net > 0.0 -> Brush.verticalGradient(
                                colors = listOf(
                                    primary.copy(alpha = 0.95f * barAlpha),
                                    primaryContainer.copy(alpha = 0.75f * barAlpha),
                                ),
                                startY = top,
                                endY = chartBottom,
                            )
                            else -> Brush.verticalGradient(
                                colors = listOf(
                                    primary.copy(alpha = 0.88f * barAlpha),
                                    primary.copy(alpha = 0.42f * barAlpha),
                                ),
                                startY = top,
                                endY = chartBottom,
                            )
                        }
                        val fillHeight = barHeight.coerceAtLeast(minBarHeight)
                        drawRoundRect(
                            brush = barBrush,
                            topLeft = Offset(left, top),
                            size = Size(barWidth, fillHeight),
                            cornerRadius = corner,
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.14f * barAlpha),
                            topLeft = Offset(left + barWidth * 0.12f, top),
                            size = Size(barWidth * 0.22f, (fillHeight * 0.35f).coerceAtLeast(2.dp.toPx())),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        )
                    }
                }

                if (progress > 0.05f) {
                    val linePath = Path()
                    val areaPath = Path()
                    var started = false
                    monthlyNet.take(barCount).forEachIndexed { index, net ->
                        if (net <= 0.0) return@forEachIndexed
                        val x = barCenters[index]
                        val y = lineTops[index]
                        if (!started) {
                            linePath.moveTo(x, y)
                            areaPath.moveTo(x, chartBottom)
                            areaPath.lineTo(x, y)
                            started = true
                        } else {
                            linePath.lineTo(x, y)
                            areaPath.lineTo(x, y)
                        }
                    }
                    if (started) {
                        val lastIndex = monthlyNet.take(barCount)
                            .indices
                            .lastOrNull { monthlyNet.getOrElse(it) { 0.0 } > 0.0 } ?: 0
                        areaPath.lineTo(barCenters[lastIndex], chartBottom)
                        areaPath.close()
                        clipRect(top = chartTop, bottom = chartBottom) {
                            drawPath(
                                path = areaPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        primary.copy(alpha = 0.16f * progress),
                                        primary.copy(alpha = 0.02f * progress),
                                    ),
                                    startY = chartTop,
                                    endY = chartBottom,
                                ),
                            )
                        }
                        drawPath(
                            path = linePath,
                            color = primary.copy(alpha = 0.28f * progress),
                            style = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }

                    monthlyNet.take(barCount).forEachIndexed { index, net ->
                        if (net <= 0.0) return@forEachIndexed
                        val isSelected = index == selectedMonthIndex
                        val radius = when {
                            isSelected -> 4.5.dp.toPx()
                            index == peakIndex -> 3.5.dp.toPx()
                            else -> 2.5.dp.toPx()
                        }
                        drawCircle(
                            color = when {
                                isSelected -> accent
                                index == peakIndex -> primary
                                else -> primary.copy(alpha = 0.75f)
                            },
                            radius = radius * progress,
                            center = Offset(barCenters[index], lineTops[index]),
                        )
                        if (isSelected) {
                            drawCircle(
                                color = accent.copy(alpha = 0.22f * progress),
                                radius = (radius + 3.dp.toPx()) * progress,
                                center = Offset(barCenters[index], lineTops[index]),
                            )
                        }
                    }
                }

                // Отметка о расхождении — отдельным проходом после всей отрисовки,
                // чтобы не задевать расчёт геометрии столбцов.
                if (showDiscrepancy) {
                    val markerRadius = 2.5.dp.toPx()
                    months.take(barCount).forEachIndexed { index, meta ->
                        if (!meta.needsReview) return@forEachIndexed
                        drawCircle(
                            color = errorColor,
                            radius = markerRadius,
                            center = Offset(
                                x = slotWidth * index + slotWidth / 2f,
                                y = chartTop + markerRadius,
                            ),
                        )
                    }
                }
            }

            Row(modifier = Modifier.matchParentSize()) {
                repeat(12) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onMonthSelected(index) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            monthLabels.forEachIndexed { index, label ->
                val selected = index == selectedMonthIndex
                val monthLabelColor by animateColorAsState(
                    targetValue = if (selected) primary else labelColor,
                    animationSpec = tween(180),
                    label = "chartMonthLabel",
                )
                Text(
                    text = label,
                    style = ChartMonthLabelStyle.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = monthLabelColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        val selectedNet = monthlyNet.getOrElse(selectedMonthIndex) { 0.0 }
        val selectedSessions = monthlyCompleted.getOrElse(selectedMonthIndex) { 0 }
        val selectedMeta = months.getOrElse(selectedMonthIndex) { MonthPriceMeta() }
        SelectedMonthSummary(
            monthName = getMonthName(YearMonth.of(year, selectedMonthIndex + 1)),
            netAmount = formatRubles(selectedNet),
            sessions = selectedSessions,
            isPeakMonth = selectedMonthIndex == peakIndex && selectedNet > 0.0,
            needsReview = showDiscrepancy && selectedMeta.needsReview,
            onClick = onSummaryClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun SelectedMonthSummary(
    monthName: String,
    netAmount: String,
    sessions: Int,
    isPeakMonth: Boolean,
    needsReview: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = Triple(monthName, sessions, netAmount),
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
        },
        label = "selectedMonthStats",
        modifier = modifier.clickable(onClick = onClick),
    ) { (name, sessionCount, amount) ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (isPeakMonth) "$name · лучший месяц" else name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                if (needsReview) DiscrepancyBadge()
            }
            Text(
                text = "$sessionCount ${pluralSessions(sessionCount)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = amount,
                style = MaterialTheme.typography.bodySmall,
                color = ScheduleHeaderGreen,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun pluralSessions(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "занятие"
        mod10 in 2..4 && mod100 !in 12..14 -> "занятия"
        else -> "занятий"
    }
}

@Preview(showBackground = true, name = "Year stats")
@Composable
private fun ProfileYearStatsSectionPreview() {
    NeiroTheme(darkTheme = false) {
        Surface {
            ProfileYearStatsSection(
                stats = ProfileYearStats(
                    year = YearMonth.now().year,
                    completedSessions = 998,
                    totalNetEarned = 1_319_200.0,
                    totalTaxAmount = 78_000.0,
                    monthlyNet = listOf(
                        8_000.0, 12_000.0, 9_500.0, 11_000.0, 151_700.0, 10_800.0,
                        7_500.0, 13_000.0, 11_500.0, 9_000.0, 8_200.0, 10_000.0,
                    ),
                    monthlyCompleted = listOf(
                        6, 8, 7, 9, 113, 10, 5, 11, 9, 7, 6, 8,
                    ),
                ),
                availableYears = listOf(2026, 2025, 2024),
                selectedYear = 2025,
                onYearSelected = {},
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
