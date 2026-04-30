package ru.greemlab.neiro.ui.screens

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.YearMonth
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.components.CalendarGrid
import ru.greemlab.neiro.ui.components.CalendarHeader
import ru.greemlab.neiro.ui.components.WeekDaysRow


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel()
) {
    // Собираем состояние из ViewModel
    val currentMonth by viewModel.currentMonth.collectAsState()

    // Передаем данные в глупый компонент
    CalendarScreen(
        currentMonth = currentMonth,
        onPreviousMonth = { viewModel.previousMonth() },
        onNextMonth = { viewModel.nextMonth() }
    )
}

// 2. Глупый компонент (Stateless). Только рисует UI.
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreen(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Цвет фона автоматически берется светлый или темный из NeiroTheme
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .statusBarsPadding() // Чтобы не налезало на статус-бар
    ) {
        CalendarHeader(
            currentMonth = currentMonth,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        Spacer(modifier = Modifier.height(16.dp))
        WeekDaysRow()
        Spacer(modifier = Modifier.height(8.dp))
        CalendarGrid(currentMonth = currentMonth)
    }
}


//// Превью 1: Светлая тема
//@RequiresApi(Build.VERSION_CODES.O)
//@Preview(showBackground = true, name = "Light Theme")
//@Composable
//fun CalendarPreviewLight() {
//    NeiroTheme(darkTheme = false) { // Принудительно светлая для превью
//        CalendarScreen(
//            currentMonth = YearMonth.now(),
//            onPreviousMonth = {},
//            onNextMonth = {}
//        )
//    }
//}

// Превью 2: Темная тема
@RequiresApi(Build.VERSION_CODES.O)
@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES, // Говорим студии включить ночной режим
    name = "Dark Theme"
)
@Composable
fun CalendarPreviewDark() {
    NeiroTheme(darkTheme = true) { // Принудительно темная для превью
        CalendarScreen(
            currentMonth = YearMonth.now(),
            onPreviousMonth = {},
            onNextMonth = {}
        )
    }
}