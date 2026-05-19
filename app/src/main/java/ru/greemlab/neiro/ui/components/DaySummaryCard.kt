package ru.greemlab.neiro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.ui.calendar.DaySummaryStats
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.formatNumber
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val ProfitGreen = Color(0xFF4CAF50)
private val ShortDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", RU_LOCALE)

@Composable
fun DaySummaryPanel(
    visible: Boolean,
    date: LocalDate,
    stats: DaySummaryStats,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        DaySummaryCard(
            date = date,
            stats = stats,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
fun DaySummaryCard(
    date: LocalDate,
    stats: DaySummaryStats,
    modifier: Modifier = Modifier,
) {
    val dateLabel = remember(date) {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU_LOCALE)
            .replaceFirstChar { it.uppercase(RU_LOCALE) }
        "$weekday, ${date.format(ShortDateFormat)}"
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "ещё раз — редактировать",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }

            if (stats.hasSessions) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DaySummaryMetric(
                        icon = Icons.Rounded.School,
                        tint = MaterialTheme.colorScheme.primary,
                        value = stats.totalLessons.toString(),
                        label = "занятий",
                        modifier = Modifier.weight(1f),
                    )
                    DaySummaryMetric(
                        icon = Icons.Rounded.CheckCircle,
                        tint = MaterialTheme.colorScheme.tertiary,
                        value = "${stats.attendedLessons}/${stats.totalLessons}",
                        label = "проведено",
                        modifier = Modifier.weight(1f),
                    )
                    DaySummaryMetric(
                        icon = Icons.Rounded.Payments,
                        tint = ProfitGreen,
                        value = formatNumber(stats.earned),
                        label = "₽ доход",
                        modifier = Modifier.weight(1f),
                    )
                }

                if (stats.expected > 0.0) {
                    Text(
                        text = "Ожидается ${formatRubles(stats.expected)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = "Занятий нет — нажмите ещё раз, чтобы добавить",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DaySummaryMetric(
    icon: ImageVector,
    tint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
