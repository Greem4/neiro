package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.NeiroSurfaceAlpha
import ru.greemlab.neiro.theme.glassBorder
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.calendar.DaySummaryStats
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.formatDayMonth
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.TextStyle

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

/**
 * Плашка дня говорит на языке шапки месяца.
 *
 * Раньше здесь был свой набор правил — голубая подложка, цветные подложки у
 * сумм, иконка над числом по центру, — и день выглядел вставкой из другого
 * приложения. Теперь плитка устроена как «Занятий» и «Прибыль» наверху:
 * иконка в цветном чипе слева, подпись над значением. Плашка при этом уходит
 * темнее панели, чтобы день читался отдельной группой, а не продолжением
 * вкладок.
 *
 * Тон плашки и плиток — не свои числа, а ступени общей лестницы
 * ([NeiroSurfaceAlpha]): день стоит на ступень выше панели вкладок, плитки —
 * ещё на ступень выше дня. Раньше плашка бралась от `background` и на амоледе
 * проваливалась в чёрный.
 *
 * Плиток всегда четыре, по две в ряду. Интенсив когда-то вставал третьей
 * плиткой в верхний ряд, и подписи в нём ужимались до «заня…», «пров…»,
 * «инте…»: чип с иконкой съедает половину узкой плитки. Теперь интенсив
 * живёт внутри разбора, который открывается нажатием на плитку. В счётчик
 * занятий он не входит — интенсив не занятие.
 */
/** Заливка чипа под иконкой — та же доля акцента, что у плиток шапки месяца. */
private const val DayChipAlpha = 0.14f

/** Подложка плашки — ступень над панелью вкладок. */
private val dayCardColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
        .copy(alpha = NeiroSurfaceAlpha.CARD)

/** Плитка внутри плашки: одна на метрики и на суммы, ступень над плашкой. */
private val dayTileColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
        .copy(alpha = NeiroSurfaceAlpha.TILE)

@Composable
fun DaySummarySlot(
    date: LocalDate,
    stats: DaySummaryStats,
    modifier: Modifier = Modifier,
    onLessonsClick: () -> Unit = {},
    onConductedClick: () -> Unit = {},
    onEarnedClick: () -> Unit = {},
    onExpectedClick: () -> Unit = {},
) {
    DaySummaryCard(
        date = date,
        stats = stats,
        onLessonsClick = onLessonsClick,
        onConductedClick = onConductedClick,
        onEarnedClick = onEarnedClick,
        onExpectedClick = onExpectedClick,
        modifier = modifier
            .fillMaxWidth()
            .height(daySummarySlotHeight),
    )
}

@Composable
private fun DaySummaryCard(
    date: LocalDate,
    stats: DaySummaryStats,
    onLessonsClick: () -> Unit,
    onConductedClick: () -> Unit,
    onEarnedClick: () -> Unit,
    onExpectedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateLabel = remember(date) {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU_LOCALE)
            .replaceFirstChar { it.uppercase(RU_LOCALE) }
        "$weekday ${formatDayMonth(date)}"
    }
    val earnedText = remember(stats.earned) { formatRubles(stats.earned) }
    val expectedText = remember(stats.expected) { formatRubles(stats.expected) }

    // Интенсив занятием не считается: день с одним интенсивом честно
    // показывает «занятий 0», а сам интенсив ждёт в разборе по нажатию.
    val lessonsValue = remember(stats) {
        if (stats.pendingLessons > 0) {
            "${stats.confirmedLessons + stats.attendedLessons}/${stats.pendingLessons}"
        } else {
            stats.totalLessons.toString()
        }
    }
    val conductedValue = remember(stats) {
        if (stats.totalLessons > 0) "${stats.attendedLessons}/${stats.totalLessons}" else "0"
    }

    val semanticColors = neiroSemanticColors
    val cardShape = RoundedCornerShape(14.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .glassBorder(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = dayCardColor),
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
                DayTile(
                    icon = Icons.Rounded.School,
                    accent = MaterialTheme.colorScheme.primary,
                    label = "занятий",
                    value = lessonsValue,
                    onClick = onLessonsClick,
                    modifier = Modifier.weight(1f),
                )
                DayTile(
                    icon = Icons.Rounded.CheckCircle,
                    accent = semanticColors.scheduleHeader,
                    label = "проведено",
                    value = conductedValue,
                    onClick = onConductedClick,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DayTile(
                    icon = Icons.Rounded.Payments,
                    accent = semanticColors.scheduleHeader,
                    label = "Заработано",
                    value = earnedText,
                    valueColor = semanticColors.scheduleHeader,
                    onClick = onEarnedClick,
                    modifier = Modifier.weight(1f),
                )
                // Нулевое ожидание не новость — сумма уходит в серый, а чип
                // остаётся цветным, чтобы плитка не выпадала из ряда.
                val nothingExpected = stats.expected <= 0.0
                DayTile(
                    icon = Icons.Rounded.Schedule,
                    accent = semanticColors.expected,
                    label = "Ожидается",
                    value = expectedText,
                    valueColor = if (nothingExpected) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        semanticColors.expected
                    },
                    muted = nothingExpected,
                    onClick = onExpectedClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Плитка дня: иконка в цветном чипе, подпись над значением.
 *
 * Тот же кирпич, что «Занятий» и «Прибыль» в шапке месяца — размеры чипа,
 * доля акцента в его заливке и кегли повторены оттуда намеренно, чтобы два
 * блока на экране читались одним набором.
 *
 * @param muted значение не несёт новости (нулевое ожидание) — подпись глуше.
 */
@Composable
private fun DayTile(
    icon: ImageVector,
    accent: Color,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    muted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = dayTileColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accent.copy(alpha = DayChipAlpha),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = accent,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (muted) 0.6f else 1f,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Кегль подбирается по месту: «17 500 ₽» при крупном системном
                // шрифте иначе превращается в «17 5…».
                AutoShrinkText(
                    text = value,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = valueColor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
