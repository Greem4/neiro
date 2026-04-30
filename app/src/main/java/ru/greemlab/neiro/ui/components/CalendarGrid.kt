package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.YearMonth

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarGrid(currentMonth: YearMonth) {
    val firstDayOfMonth = currentMonth.atDay(1)
    val daysInMonth = currentMonth.lengthOfMonth()
    val firstDayWeekIndex = firstDayOfMonth.dayOfWeek.value % 7 // Пн=1, Вс=7

    val days = (1..daysInMonth).toList()

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Пустые ячейки до первого дня месяца
        items(firstDayWeekIndex) {
            Box(modifier = Modifier.size(48.dp))
        }

        // Дни месяца
        items(days) { day ->
            DayCard(day = day)
        }
    }
}