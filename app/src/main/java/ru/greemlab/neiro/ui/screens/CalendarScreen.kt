package ru.greemlab.neiro.ui.screens

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import ru.greemlab.neiro.ui.components.CalendarGrid
import ru.greemlab.neiro.ui.components.CalendarHeader
import ru.greemlab.neiro.ui.components.WeekDaysRow

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreen() {
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }

    Column(modifier = Modifier.padding(16.dp)) {
        CalendarHeader(
            currentMonth = currentMonth,
            onPreviousMonth = { currentMonth = currentMonth.minusMonths(1) },
            onNextMonth = { currentMonth = currentMonth.plusMonths(1) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        WeekDaysRow()

        Spacer(modifier = Modifier.height(8.dp))

        CalendarGrid(currentMonth = currentMonth)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true)
@Composable
fun CalendarPreview() {
    CalendarScreen()
}