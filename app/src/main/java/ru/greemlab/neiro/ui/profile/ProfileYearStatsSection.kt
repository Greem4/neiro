package ru.greemlab.neiro.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.ui.calendar.ProfileYearStats
import ru.greemlab.neiro.ui.calendar.getShortMonthName
import ru.greemlab.neiro.ui.settings.SettingsGroupCard
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.Month
import java.time.YearMonth

@Composable
fun ProfileYearStatsSection(
    stats: ProfileYearStats,
    availableYears: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
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
            if (stats.totalNetEarned > 0.0) {
                append(" · ")
                append(formatRubles(stats.totalNetEarned))
            }
        }
    }

    SettingsGroupCard(modifier = modifier) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            headlineContent = {
                Text("Статистика", style = MaterialTheme.typography.bodyLarge)
            },
            supportingContent = {
                Text(
                    text = if (expanded) "За ${stats.year} год" else collapsedSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(Icons.Rounded.BarChart, contentDescription = null)
            },
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                )
            },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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

                AnimatedContent(
                    targetState = stats,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    label = "yearStatsContent",
                ) { animatedStats ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                Text(
                    text = "Чистая прибыль по месяцам",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )

                YearNetProfitChart(
                    monthlyNet = stats.monthlyNet,
                    progress = chartProgress.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(172.dp),
                )
            }
        }
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
            modifier = Modifier.size(40.dp),
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
            modifier = Modifier.size(40.dp),
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
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = 0.1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.18f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun YearNetProfitChart(
    monthlyNet: List<Double>,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val accent = ScheduleHeaderGreen
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    val maxValue = remember(monthlyNet) { monthlyNet.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0 }
    val peakIndex = remember(monthlyNet) {
        monthlyNet.indices.maxByOrNull { monthlyNet.getOrElse(it) { 0.0 } } ?: 0
    }

    val monthLabels = remember {
        Month.entries.map { getShortMonthName(it) }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val labelReserve = 4.dp.toPx()
            val chartBottom = size.height - labelReserve
            val chartTop = 8.dp.toPx()
            val chartHeight = chartBottom - chartTop
            val barCount = 12
            val slotWidth = size.width / barCount
            val barWidth = slotWidth * 0.62f
            val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())

            // Горизонтальные направляющие
            listOf(0.33f, 0.66f, 1f).forEach { guide ->
                val y = chartBottom - chartHeight * guide
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            val barCenters = FloatArray(barCount)
            val barTops = FloatArray(barCount)

            monthlyNet.take(barCount).forEachIndexed { index, net ->
                val fraction = (net / maxValue).toFloat().coerceIn(0f, 1f)
                val animatedFraction = fraction * progress
                val barHeight = chartHeight * animatedFraction
                val left = slotWidth * index + (slotWidth - barWidth) / 2f
                val top = chartBottom - barHeight
                barCenters[index] = left + barWidth / 2f
                barTops[index] = top

                // Трек столбца
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, chartTop),
                    size = Size(barWidth, chartHeight),
                    cornerRadius = corner,
                )

                if (barHeight > 0f) {
                    val barBrush = if (index == peakIndex && net > 0.0) {
                        Brush.verticalGradient(
                            colors = listOf(accent, accent.copy(alpha = 0.65f)),
                            startY = top,
                            endY = chartBottom,
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(primary, primaryContainer),
                            startY = top,
                            endY = chartBottom,
                        )
                    }
                    drawRoundRect(
                        brush = barBrush,
                        topLeft = Offset(left, top),
                        size = Size(
                            barWidth,
                            barHeight.coerceAtLeast(3.dp.toPx()),
                        ),
                        cornerRadius = corner,
                    )
                }
            }

            // Линия тренда по вершинам столбцов
            if (progress > 0.05f) {
                val linePath = Path()
                var started = false
                monthlyNet.take(barCount).forEachIndexed { index, net ->
                    if (net <= 0.0) return@forEachIndexed
                    val x = barCenters[index]
                    val y = barTops[index]
                    if (!started) {
                        linePath.moveTo(x, y)
                        started = true
                    } else {
                        linePath.lineTo(x, y)
                    }
                }
                if (started) {
                    drawPath(
                        path = linePath,
                        color = primary.copy(alpha = 0.35f * progress),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }

                // Точки на вершинах
                monthlyNet.take(barCount).forEachIndexed { index, net ->
                    if (net <= 0.0) return@forEachIndexed
                    val radius = if (index == peakIndex) 4.dp.toPx() else 2.5.dp.toPx()
                    drawCircle(
                        color = if (index == peakIndex) accent else primary,
                        radius = radius * progress,
                        center = Offset(barCenters[index], barTops[index]),
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            monthLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.92f,
                )
            }
        }

        val peakMonth = Month.of(peakIndex + 1)
        val peakValue = monthlyNet.getOrElse(peakIndex) { 0.0 }
        if (peakValue > 0.0) {
            Text(
                text = "Лучший месяц: ${getShortMonthName(peakMonth)} · ${formatRubles(peakValue)}",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                    completedSessions = 87,
                    totalNetEarned = 124_500.0,
                    monthlyNet = listOf(
                        8_000.0, 12_000.0, 9_500.0, 11_000.0, 14_200.0, 10_800.0,
                        7_500.0, 13_000.0, 11_500.0, 9_000.0, 8_200.0, 10_000.0,
                    ),
                ),
                availableYears = listOf(2026, 2025, 2024),
                selectedYear = 2025,
                onYearSelected = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
