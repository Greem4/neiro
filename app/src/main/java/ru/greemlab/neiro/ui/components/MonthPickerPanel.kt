package ru.greemlab.neiro.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.getShortMonthName
import java.time.LocalDate
import java.time.YearMonth

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

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(100)),
        modifier = Modifier.zIndex(20f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
}

@Composable
private fun MonthPickerGrid(
    currentMonth: YearMonth,
    onMonthSelected: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayedYear by remember(currentMonth) { mutableIntStateOf(currentMonth.year) }
    val today = remember { LocalDate.now() }

    val gridShape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .clip(gridShape)
            .background(MaterialTheme.colorScheme.surface, gridShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    val monthNumber = row * 4 + col + 1
                    val month = YearMonth.of(displayedYear, monthNumber)
                    MiniMonthCalendar(
                        month = month,
                        today = today,
                        isSelected = month == currentMonth,
                        onClick = { onMonthSelected(month) },
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

/** Мини-календарь одного месяца — как основной, но компактный. */
@Composable
private fun MiniMonthCalendar(
    month: YearMonth,
    today: LocalDate,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(month) { buildMonthGridDays(month) }
    val tileShape = RoundedCornerShape(8.dp)
    val tileBackground = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

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
            text = getShortMonthName(month.month),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(1.dp))
        var weekIndex = 0
        while (weekIndex < 6) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for (dayCol in 0 until 7) {
                    val date = days[weekIndex * 7 + dayCol]
                    MiniDayCell(
                        date = date,
                        today = today,
                        displayMonth = month,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            weekIndex++
        }
    }
}

/** Уменьшенная ячейка дня — те же правила, что у [DayCard]. */
@Composable
private fun MiniDayCell(
    date: LocalDate,
    today: LocalDate,
    displayMonth: YearMonth,
    modifier: Modifier = Modifier,
) {
    val isCurrentMonth =
        date.month == displayMonth.month && date.year == displayMonth.year
    val isToday = date == today

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (isToday) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(1.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape,
                    ),
            )
        }
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = 6.sp,
            lineHeight = 7.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isToday -> MaterialTheme.colorScheme.primary
                !isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
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
