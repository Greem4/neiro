package ru.greemlab.neiro.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.zIndex
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.util.cappedSp
import java.time.LocalDate
import java.time.YearMonth

/** Единообразные подписи месяцев — одинаковая длина, без сдвигов сетки. */
private val MONTH_PICKER_LABELS = listOf(
    "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
    "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек",
)

private object MonthPickerLayout {
    val panelPadding: Dp = 4.dp
    val overlayTopPadding: Dp = 60.dp

    val yearBarHeight: Dp = 40.dp
    val yearNavButtonSize: Dp = 32.dp
    val yearNavIconSize: Dp = 22.dp
    val yearLabelWidth: Dp = 60.dp
    val yearNavGap: Dp = 4.dp

    val tileRowHeight: Dp = 100.dp
    val tileSpacing: Dp = 6.dp
    val rowSpacing: Dp = 8.dp

    val tileCorner: Dp = 12.dp
    val tileInnerPadding: Dp = 4.dp
    val monthLabelHeight: Dp = 20.dp
    // Плитка — уменьшенная копия месяца с жёсткой сеткой 7×6, поэтому её шрифты
    // растут вместе с системным лишь до предела: иначе числа налезают друг на друга.
    val monthLabelFontSize: TextUnit
        @Composable get() = cappedSp(11.dp)
    val dayFontSize: TextUnit
        @Composable get() = cappedSp(8.dp)
}

@Immutable
private data class MonthPickerTile(
    val month: YearMonth,
    val days: List<LocalDate?>,
    val label: String,
)

/**
 * Сетка из 12 мини-календарей поверх экрана.
 * Визуально — уменьшенная копия основного [CalendarGrid] / [DayCard].
 */
@Composable
fun MonthPickerOverlay(
    visible: Boolean,
    currentMonth: YearMonth,
    onMonthSelected: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    if (!visible) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        MonthPickerGrid(
            currentMonth = currentMonth,
            onMonthSelected = { month ->
                onMonthSelected(month)
                onDismiss()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = MonthPickerLayout.overlayTopPadding,
                )
                .fillMaxWidth()
                .padding(horizontal = MonthPickerLayout.panelPadding),
        )
    }
}

@Composable
private fun MonthPickerGrid(
    currentMonth: YearMonth,
    onMonthSelected: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayedYear by remember(currentMonth) { mutableIntStateOf(currentMonth.year) }
    val today = remember { LocalDate.now() }
    val textMeasurer = rememberTextMeasurer()

    val tiles = remember(displayedYear) {
        (1..12).map { monthNumber ->
            val month = YearMonth.of(displayedYear, monthNumber)
            MonthPickerTile(
                month = month,
                days = buildMonthGridCells(month),
                label = MONTH_PICKER_LABELS[monthNumber - 1],
            )
        }
    }

    val dayTextStyle = TextStyle(
        fontSize = MonthPickerLayout.dayFontSize,
        color = Color.Unspecified,
        fontWeight = FontWeight.Normal,
    )
    val todayTextStyle = dayTextStyle.copy(fontWeight = FontWeight.Bold)

    val gridInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .clickable(
                interactionSource = gridInteraction,
                indication = null,
                onClick = {},
            ),
    ) {
        MonthPickerYearBar(
            year = displayedYear,
            onPreviousYear = { displayedYear -= 1 },
            onNextYear = { displayedYear += 1 },
        )

        Spacer(modifier = Modifier.height(MonthPickerLayout.rowSpacing))

        for (row in 0 until 3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MonthPickerLayout.tileRowHeight),
                horizontalArrangement = Arrangement.spacedBy(MonthPickerLayout.tileSpacing),
            ) {
                for (col in 0 until 4) {
                    val tile = tiles[row * 4 + col]
                    MiniMonthCalendar(
                        tile = tile,
                        today = today,
                        isSelected = tile.month == currentMonth,
                        textMeasurer = textMeasurer,
                        dayTextStyle = dayTextStyle,
                        todayTextStyle = todayTextStyle,
                        onClick = { onMonthSelected(tile.month) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
            if (row < 2) {
                Spacer(modifier = Modifier.height(MonthPickerLayout.rowSpacing))
            }
        }
    }
}

/** Стрелки вплотную к году, блок по центру — не разъезжаются к краям экрана. */
@Composable
private fun MonthPickerYearBar(
    year: Int,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MonthPickerLayout.yearBarHeight),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MonthPickerLayout.yearNavGap),
        ) {
            IconButton(
                onClick = onPreviousYear,
                modifier = Modifier.size(MonthPickerLayout.yearNavButtonSize),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Предыдущий год",
                    modifier = Modifier.size(MonthPickerLayout.yearNavIconSize),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            AutoShrinkText(
                text = year.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(MonthPickerLayout.yearLabelWidth),
            )

            IconButton(
                onClick = onNextYear,
                modifier = Modifier.size(MonthPickerLayout.yearNavButtonSize),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Следующий год",
                    modifier = Modifier.size(MonthPickerLayout.yearNavIconSize),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun MiniMonthCalendar(
    tile: MonthPickerTile,
    today: LocalDate,
    isSelected: Boolean,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    dayTextStyle: TextStyle,
    todayTextStyle: TextStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(MonthPickerLayout.tileCorner)
    val tileBackground = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val labelColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val dayColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val todayColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .clip(tileShape)
            .background(tileBackground, tileShape)
            .clickable(onClick = onClick)
            .padding(MonthPickerLayout.tileInnerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MonthPickerLayout.monthLabelHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tile.label,
                fontSize = MonthPickerLayout.monthLabelFontSize,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MiniMonthDaysCanvas(
            days = tile.days,
            month = tile.month,
            today = today,
            todayColor = todayColor,
            textMeasurer = textMeasurer,
            dayTextStyle = dayTextStyle.copy(color = dayColor),
            todayTextStyle = todayTextStyle.copy(color = todayColor),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun MiniMonthDaysCanvas(
    days: List<LocalDate?>,
    month: YearMonth,
    today: LocalDate,
    todayColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    dayTextStyle: TextStyle,
    todayTextStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val cellW = size.width / 7f
        val rowCount = ((days.size + 6) / 7).coerceAtLeast(1)
        val cellH = size.height / rowCount
        val todayRadius = minOf(cellW, cellH) * 0.4f

        for (index in days.indices) {
            val date = days[index] ?: continue

            val col = index % 7
            val row = index / 7
            val cx = col * cellW + cellW / 2f
            val cy = row * cellH + cellH / 2f
            val isToday = date == today

            if (isToday) {
                drawCircle(
                    color = todayColor.copy(alpha = 0.14f),
                    radius = todayRadius,
                    center = Offset(cx, cy),
                )
            }

            val text = date.dayOfMonth.toString()
            val layout = textMeasurer.measure(text, if (isToday) todayTextStyle else dayTextStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    cx - layout.size.width / 2f,
                    cy - layout.size.height / 2f,
                ),
            )
        }
    }
}

@Preview(showBackground = true, name = "Month picker grid")
@Composable
private fun MonthPickerGridPreview() {
    NeiroTheme {
        MonthPickerGrid(
            currentMonth = YearMonth.of(2026, 5),
            onMonthSelected = {},
            modifier = Modifier.padding(12.dp),
        )
    }
}
