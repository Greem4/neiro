package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.greemlab.neiro.ui.calendar.SessionParser
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

@Immutable
private data class MonthGrid(
    /** `null` — пустая ячейка в начале/конце недели (без дней соседних месяцев). */
    val cells: List<LocalDate?>,
)

/**
 * Сетка календаря: только дни выбранного месяца, выровненные по дням недели (Пн–Вс).
 * Число строк — от 4 до 6 в зависимости от месяца.
 *
 * Текущая дата [today] обновляется автоматически при пересечении полуночи —
 * пока экран открыт, выделение «сегодня» переезжает на новый день.
 */
@Composable
fun CalendarGrid(
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    dayData: Map<LocalDate, List<String>> = emptyMap(),
    archiveMismatchDates: Set<LocalDate> = emptySet(),
    workingDays: Set<DayOfWeek> = emptySet(),
    onDateClick: (LocalDate) -> Unit,
) {
    val grid = remember(currentMonth) { buildMonthGrid(currentMonth) }
    val today by rememberCurrentDate()
    val hasWorkingDayFilter = workingDays.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        val cells = grid.cells
        var i = 0
        while (i < cells.size) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (j in 0 until 7) {
                    val date = cells[i + j]
                    if (date == null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                        )
                    } else {
                        val sessions = dayData[date]
                        val namesLabel = sessions?.let {
                            SessionParser.formatCalendarCounts(it, date, today)
                        } ?: ""
                        val hasIntensive = sessions?.any(SessionParser::isVisibleIntensive) ?: false

                        DayCard(
                            date = date,
                            today = today,
                            isCurrentMonth = true,
                            isSelected = date == selectedDate,
                            namesLabel = namesLabel,
                            hasIntensive = hasIntensive,
                            isWorkingDay = !hasWorkingDayFilter || workingDays.contains(date.dayOfWeek),
                            archiveMismatch = date in archiveMismatchDates,
                            onDateClick = onDateClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            i += 7
        }
    }
}

/**
 * Возвращает текущую дату с авто-обновлением сразу после полуночи.
 * Реализация: засыпаем до 00:00:01 следующих суток и обновляем значение.
 */
@Composable
private fun rememberCurrentDate(): androidx.compose.runtime.State<LocalDate> =
    produceState(initialValue = LocalDate.now()) {
        while (true) {
            val now = LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atTime(LocalTime.MIDNIGHT)
            val delayMs = Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000)
            delay(delayMs + 1_000)
            value = LocalDate.now()
        }
    }

internal fun buildMonthGridCells(currentMonth: YearMonth): List<LocalDate?> =
    buildMonthGrid(currentMonth).cells

private fun buildMonthGrid(currentMonth: YearMonth): MonthGrid {
    val firstDayOfMonth = currentMonth.atDay(1)
    val daysInMonth = currentMonth.lengthOfMonth()
    val leadingEmpty = firstDayOfMonth.dayOfWeek.value - 1 // Пн = 0 … Вс = 6

    val cells = ArrayList<LocalDate?>(leadingEmpty + daysInMonth)
    repeat(leadingEmpty) { cells += null }
    for (day in 1..daysInMonth) {
        cells += currentMonth.atDay(day)
    }
    val trailingEmpty = (7 - cells.size % 7) % 7
    repeat(trailingEmpty) { cells += null }
    return MonthGrid(cells = cells)
}
