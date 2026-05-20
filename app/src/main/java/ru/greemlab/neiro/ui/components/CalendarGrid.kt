package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

private const val GRID_CELLS = 42 // 6 недель × 7 дней

@Immutable
private data class MonthGrid(
    val days: List<LocalDate>,
)

/**
 * Сетка календаря. Показывает дни текущего месяца плюс выравнивание
 * предыдущего/следующего месяцев до прямоугольника 6×7.
 *
 * Текущая дата [today] обновляется автоматически при пересечении полуночи —
 * пока экран открыт, выделение «сегодня» переезжает на новый день.
 */
@Composable
fun CalendarGrid(
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    dayData: Map<LocalDate, List<String>> = emptyMap(),
    workingDays: Set<DayOfWeek> = emptySet(),
    onDateClick: (LocalDate) -> Unit,
) {
    val grid = remember(currentMonth) { buildMonthGrid(currentMonth) }
    val today by rememberCurrentDate()
    val hasWorkingDayFilter = workingDays.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        val days = grid.days
        var i = 0
        while (i < days.size) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (j in 0 until 7) {
                    val date = days[i + j]
                    val sessions = dayData[date]
                    // Считаем только учеников (точки), не экстра-сессии.
                    val studentsCount = sessions?.count { !SessionParser.isExtra(it) } ?: 0
                    DayCard(
                        date = date,
                        today = today,
                        isCurrentMonth = date.month == currentMonth.month && date.year == currentMonth.year,
                        isSelected = date == selectedDate,
                        namesCount = studentsCount,
                        isWorkingDay = !hasWorkingDayFilter || workingDays.contains(date.dayOfWeek),
                        onDateClick = onDateClick,
                        modifier = Modifier.weight(1f),
                    )
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

private fun buildMonthGrid(currentMonth: YearMonth): MonthGrid {
    val firstDayOfMonth = currentMonth.atDay(1)
    val daysInMonth = currentMonth.lengthOfMonth()
    val firstDayWeekIndex = firstDayOfMonth.dayOfWeek.value - 1 // Пн = 0 … Вс = 6

    val previousMonth = currentMonth.minusMonths(1)
    val daysInPrevious = previousMonth.lengthOfMonth()
    val nextMonth = currentMonth.plusMonths(1)

    val days = ArrayList<LocalDate>(GRID_CELLS)
    for (offset in firstDayWeekIndex downTo 1) {
        days += previousMonth.atDay(daysInPrevious - offset + 1)
    }
    for (day in 1..daysInMonth) {
        days += currentMonth.atDay(day)
    }
    var nextDay = 1
    while (days.size < GRID_CELLS) {
        days += nextMonth.atDay(nextDay++)
    }
    return MonthGrid(days = days)
}
