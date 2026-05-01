package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarGrid(
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    onDateClick: (LocalDate) -> Unit
) {
    val firstDayOfMonth = currentMonth.atDay(1)
    val daysInMonth = currentMonth.lengthOfMonth()
    
    // Пн = 1, Вс = 7. Для индекса (0-6) делаем -1
    val firstDayWeekIndex = firstDayOfMonth.dayOfWeek.value - 1
    
    // Дни предыдущего месяца для заполнения начала сетки
    val previousMonth = currentMonth.minusMonths(1)
    val daysInPreviousMonth = previousMonth.lengthOfMonth()
    val startPaddingDays = (daysInPreviousMonth - firstDayWeekIndex + 1..daysInPreviousMonth).map { day ->
        previousMonth.atDay(day)
    }

    // Дни текущего месяца
    val currentMonthDays = (1..daysInMonth).map { day ->
        currentMonth.atDay(day)
    }

    // Дни следующего месяца для заполнения конца сетки (до 42 ячеек - 6 недель)
    val nextMonth = currentMonth.plusMonths(1)
    val remainingCells = 42 - (startPaddingDays.size + currentMonthDays.size)
    val endPaddingDays = (1..remainingCells).map { day ->
        nextMonth.atDay(day)
    }

    val allDays = startPaddingDays + currentMonthDays + endPaddingDays

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(allDays) { date ->
            DayCard(
                date = date,
                isCurrentMonth = YearMonth.from(date) == currentMonth,
                isSelected = date == selectedDate,
                onDateClick = onDateClick
            )
        }
    }
}
