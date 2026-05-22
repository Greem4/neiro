package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.ExpectedAmber
import ru.greemlab.neiro.ui.calendar.DaySummaryStats
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val StatusGreen = Color(0xFF4CAF50)
private val StatusRed = Color(0xFFF44336)

private val ShortDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", RU_LOCALE)

/** Фиксированная высота слота — календарь не прыгает при смене даты. */
val DaySummarySlotHeight: Dp = 148.dp

@Composable
fun DaySummarySlot(
    date: LocalDate,
    stats: DaySummaryStats,
    modifier: Modifier = Modifier,
) {
    DaySummaryCard(
        date = date,
        stats = stats,
        modifier = modifier
            .fillMaxWidth()
            .height(DaySummarySlotHeight),
    )
}

@Composable
private fun DaySummaryCard(
    date: LocalDate,
    stats: DaySummaryStats,
    modifier: Modifier = Modifier,
) {
    val dateLabel = remember(date) {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU_LOCALE)
            .replaceFirstChar { it.uppercase(RU_LOCALE) }
        "$weekday, ${date.format(ShortDateFormat)}"
    }
    val earnedText = remember(stats.earned) { formatRubles(stats.earned) }
    val expectedText = remember(stats.expected) { formatRubles(stats.expected) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

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
                    tint = StatusGreen,
                    value = if (stats.totalLessons > 0) {
                        "${stats.attendedLessons}/${stats.totalLessons}"
                    } else "0",
                    label = "проведено",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DayMoneyCard(
                    label = "Заработано",
                    amountText = earnedText,
                    amountColor = StatusGreen,
                    background = StatusGreen.copy(alpha = 0.14f),
                    modifier = Modifier.weight(1f),
                )
                DayMoneyCard(
                    label = "Ожидается",
                    amountText = expectedText,
                    amountColor = ExpectedAmber,
                    background = ExpectedAmber.copy(alpha = 0.16f),
                    muted = stats.expected <= 0.0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayMoneyCard(
    label: String,
    amountText: String,
    amountColor: Color,
    background: Color,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val labelAlpha = if (muted) 0.55f else 1f
    val amountAlpha = if (muted) 0.65f else 1f
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = labelAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = amountText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = amountColor.copy(alpha = amountAlpha),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
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
