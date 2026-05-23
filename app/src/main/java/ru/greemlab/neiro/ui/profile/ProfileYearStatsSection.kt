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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.ui.calendar.ProfileYearStats
import ru.greemlab.neiro.ui.calendar.getChartMonthAbbreviation
import ru.greemlab.neiro.ui.calendar.getMonthName
import ru.greemlab.neiro.ui.settings.SettingsGroupCard
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.Month
import java.time.YearMonth

private val StatsContentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
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
                Text("Статистика", style = MaterialTheme.typography.bodyLarge)
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
                    progress = chartProgress.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(188.dp),
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
        Month.entries.map { getChartMonthAbbreviation(it) }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 2.dp, vertical = 8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val chartBottom = size.height
            val chartTop = 6.dp.toPx()
            val chartHeight = chartBottom - chartTop
            val barCount = 12
            val slotWidth = size.width / barCount
            val barWidth = slotWidth * 0.52f
            val corner = CornerRadius(5.dp.toPx(), 5.dp.toPx())

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
                        size = Size(barWidth, barHeight.coerceAtLeast(3.dp.toPx())),
                        cornerRadius = corner,
                    )
                }
            }

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            monthLabels.forEach { label ->
                Text(
                    text = label,
                    style = ChartMonthLabelStyle,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        val peakValue = monthlyNet.getOrElse(peakIndex) { 0.0 }
        val peakSessions = monthlyCompleted.getOrElse(peakIndex) { 0 }
        if (peakValue > 0.0) {
            BestMonthSummary(
                monthName = getMonthName(YearMonth.of(year, peakIndex + 1)),
                netAmount = formatRubles(peakValue),
                sessions = peakSessions,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun BestMonthSummary(
    monthName: String,
    netAmount: String,
    sessions: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Лучший месяц — $monthName",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "$sessions ${pluralSessions(sessions)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = netAmount,
            style = MaterialTheme.typography.bodySmall,
            color = ScheduleHeaderGreen,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
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
