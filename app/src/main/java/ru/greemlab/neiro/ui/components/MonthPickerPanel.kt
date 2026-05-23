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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.getShortMonthName
import java.time.LocalDate
import java.time.YearMonth

@Immutable
private data class MonthPickerTile(
    val month: YearMonth,
    val days: List<LocalDate>,
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
                .padding(start = 16.dp, end = 16.dp, top = 52.dp)
                .fillMaxWidth(),
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
                days = buildMonthGridDays(month),
                label = getShortMonthName(month.month),
            )
        }
    }

    val dayTextStyle = TextStyle(
        fontSize = 6.sp,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { displayedYear -= 1 }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Предыдущий год",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = displayedYear.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            IconButton(onClick = { displayedYear += 1 }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Следующий год",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        for (row in 0 until 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (row < 2) {
                Spacer(modifier = Modifier.height(4.dp))
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
    val tileShape = RoundedCornerShape(8.dp)
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
            .aspectRatio(1f)
            .clip(tileShape)
            .background(tileBackground, tileShape)
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tile.label,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(1.dp))
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
    days: List<LocalDate>,
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
        val cellH = size.height / 6f
        val todayRadius = minOf(cellW, cellH) * 0.42f

        for (index in days.indices) {
            val date = days[index]
            if (date.month != month.month || date.year != month.year) continue

            val col = index % 7
            val row = index / 7
            val cx = col * cellW + cellW / 2f
            val cy = row * cellH + cellH / 2f
            val isToday = date == today

            if (isToday) {
                drawCircle(
                    color = todayColor.copy(alpha = 0.12f),
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
