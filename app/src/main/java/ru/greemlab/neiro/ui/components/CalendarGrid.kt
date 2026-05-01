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
import java.time.DayOfWeek

/**
 * Сетка календаря, отображающая дни месяца.
 * Включает в себя дни текущего месяца, а также заполнение (padding) из дней
 * предыдущего и следующего месяцев для сохранения прямоугольной формы сетки (6 недель).
 *
 * @param currentMonth Текущий отображаемый месяц и год.
 * @param selectedDate Выбранная пользователем дата.
 * @param dayData Карта данных, где ключ — дата, а значение — список имен (для отображения индикаторов).
 * @param workingDays Набор рабочих дней недели для визуальной фильтрации.
 * @param onDateClick Callback, вызываемый при нажатии на ячейку дня.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarGrid(
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    dayData: Map<LocalDate, List<String>> = emptyMap(),
    workingDays: Set<DayOfWeek> = emptySet(),
    onDateClick: (LocalDate) -> Unit
) {
    val firstDayOfMonth = currentMonth.atDay(1)
    val daysInMonth = currentMonth.lengthOfMonth()
    
    // Определяем день недели для первого числа месяца (Пн = 1, Вс = 7)
    // Для индекса в сетке (0-6) вычитаем 1
    val firstDayWeekIndex = firstDayOfMonth.dayOfWeek.value - 1
    
    // Подготовка дней предыдущего месяца для заполнения пустых мест в начале первой недели
    val previousMonth = currentMonth.minusMonths(1)
    val daysInPreviousMonth = previousMonth.lengthOfMonth()
    val startPaddingDays = (daysInPreviousMonth - firstDayWeekIndex + 1..daysInPreviousMonth).map { day ->
        previousMonth.atDay(day)
    }

    // Список дней текущего месяца
    val currentMonthDays = (1..daysInMonth).map { day ->
        currentMonth.atDay(day)
    }

    // Подготовка дней следующего месяца для заполнения пустых мест в конце (до 42 ячеек — 6 полных недель)
    val nextMonth = currentMonth.plusMonths(1)
    val remainingCells = 42 - (startPaddingDays.size + currentMonthDays.size)
    val endPaddingDays = (1..remainingCells).map { day ->
        nextMonth.atDay(day)
    }

    // Объединяем все дни в один список для отображения в сетке
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
                namesCount = dayData[date]?.size ?: 0,
                isWorkingDay = workingDays.isEmpty() || workingDays.contains(date.dayOfWeek),
                onDateClick = onDateClick
            )
        }
    }
}
