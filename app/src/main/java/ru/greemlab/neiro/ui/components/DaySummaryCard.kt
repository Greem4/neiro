package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.ExpectedAmber
import ru.greemlab.neiro.theme.LocalGlassEnabled
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.glassBorder
import ru.greemlab.neiro.ui.calendar.DaySummaryStats
import ru.greemlab.neiro.ui.calendar.formatIntensiveConductedLabel
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val ShortDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", RU_LOCALE)

/** Базовая высота слота при системном шрифте 100%. */
private val DaySummarySlotBaseHeight: Dp = 166.dp

/**
 * Высота слота: фиксированная — календарь не прыгает при смене даты, — но
 * растущая вместе с системным шрифтом (до 1.6×). В карточке три яруса текста,
 * и при 150%+ они переставали помещаться в базовую высоту.
 */
private val daySummarySlotHeight: Dp
    @Composable get() = DaySummarySlotBaseHeight *
        LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

/** Высота строки даты — не даёт контенту раздувать карточку. */
private val DaySummaryHeaderRowHeight: Dp = 26.dp

/** Прозрачность плашки дня и её плиток при включённом стеклянном виде. */
private const val DaySummaryGlassAlpha = 0.18f
private const val DayMetricGlassAlpha = 0.3f

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
            .height(daySummarySlotHeight),
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
    val intensiveConductedText = remember(stats.confirmedIntensiveChildren, stats.pendingIntensiveChildren) {
        formatIntensiveConductedLabel(stats.confirmedIntensiveChildren, stats.pendingIntensiveChildren)
    }
    val showIntensiveMetric = stats.hasIntensive

    val lessonsValue = remember(stats) {
        if (stats.pendingLessons > 0) {
            "${stats.confirmedLessons + stats.attendedLessons}/${stats.pendingLessons}"
        } else {
            stats.totalLessons.toString()
        }
    }

    // Со стеклом плашка почти растворяется в фоне и получает блик по краю;
    // без стекла всё как было.
    val glass = LocalGlassEnabled.current
    val cardShape = RoundedCornerShape(14.dp)
    val containerAlpha = if (glass) DaySummaryGlassAlpha else 0.35f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = containerAlpha),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DaySummaryHeaderRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DaySummaryMetric(
                    icon = Icons.Rounded.School,
                    tint = MaterialTheme.colorScheme.primary,
                    value = lessonsValue,
                    label = "занятий",
                    modifier = Modifier.weight(1f),
                )
                DaySummaryMetric(
                    icon = Icons.Rounded.CheckCircle,
                    tint = ScheduleHeaderGreen,
                    value = if (stats.totalLessons > 0) {
                        "${stats.attendedLessons}/${stats.totalLessons}"
                    } else "0",
                    label = "проведено",
                    modifier = Modifier.weight(1f),
                )
                if (showIntensiveMetric) {
                    DaySummaryMetric(
                        icon = Icons.Rounded.Groups,
                        tint = Color(0xFFE53935),
                        value = intensiveConductedText,
                        label = "интенсив",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DayMoneyCard(
                    label = "Заработано",
                    amountText = earnedText,
                    amountColor = ScheduleHeaderGreen,
                    background = ScheduleHeaderGreen.copy(alpha = 0.14f),
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
                .padding(horizontal = 8.dp, vertical = 6.dp),
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
            // Половина ширины карточки на сумму — при крупном шрифте кегль
            // подбирается по месту, чтобы «161 500 ₽» не превращалось в «161 5…».
            AutoShrinkText(
                text = amountText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = amountColor.copy(alpha = amountAlpha),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
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
        color = MaterialTheme.colorScheme.surface.copy(
            alpha = if (LocalGlassEnabled.current) DayMetricGlassAlpha else 0.55f,
        ),
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
            AutoShrinkText(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
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
