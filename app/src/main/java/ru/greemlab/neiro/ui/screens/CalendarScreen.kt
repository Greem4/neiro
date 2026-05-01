package ru.greemlab.neiro.ui.screens

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.YearMonth
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.components.CalendarGrid
import ru.greemlab.neiro.ui.components.CalendarHeader
import ru.greemlab.neiro.ui.components.DayDetailsDialog
import ru.greemlab.neiro.ui.components.WeekDaysRow

/**
 * Основной экран календаря.
 * Управляет состоянием отображения диалога и взаимодействует с [CalendarViewModel].
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel()
) {
    // Подписка на состояния из ViewModel
    val currentMonth by viewModel.currentMonth.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val dayData by viewModel.dayData.collectAsState()
    
    // Состояние видимости диалога редактирования дня
    var showDialog by remember { mutableStateOf(false) }
    
    // Состояние шторки (Drawer)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Обработка кнопки "Назад": если шторка открыта — закрываем её
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.8f) // Занимает 80% ширины (чуть больше 1/3 для читаемости)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                NavigationDrawerItem(
                    label = { Text("Профиль") },
                    selected = false,
                    onClick = { /* TODO: Navigate to Profile */ },
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        // Контент экрана
        CalendarScreenContent(
            currentMonth = currentMonth,
            selectedDate = selectedDate,
            dayData = dayData,
            onPreviousMonth = { viewModel.previousMonth() },
            onNextMonth = { viewModel.nextMonth() },
            onTodayClick = { viewModel.goToToday() },
            onMenuClick = {
                scope.launch { drawerState.open() }
            },
            onDateClick = {
                viewModel.selectDate(it)
                showDialog = true
            }
        )
    }

    // Отображение диалога при выборе даты
    if (showDialog && selectedDate != null) {
        DayDetailsDialog(
            date = selectedDate!!,
            initialNames = dayData[selectedDate!!] ?: emptyList(),
            onDismiss = { showDialog = false },
            onSave = { names ->
                viewModel.saveNamesForDate(selectedDate!!, names)
                showDialog = false
            }
        )
    }
}

/**
 * Чистый UI контент экрана календаря.
 * Отделен от ViewModel для удобства тестирования и превью.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreenContent(
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    dayData: Map<LocalDate, List<String>> = emptyMap(),
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onMenuClick: () -> Unit,
    onDateClick: (LocalDate) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .statusBarsPadding()
        ) {
            // Шапка календаря (Месяц, Год, Кнопки навигации)
            CalendarHeader(
                currentMonth = currentMonth,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onTodayClick = onTodayClick,
                onMenuClick = onMenuClick
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Карточка с сеткой календаря
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Строка с названиями дней недели
                    WeekDaysRow()
                    
                    // Сетка дней с анимацией перелистывания месяцев
                    AnimatedContent(
                        targetState = currentMonth,
                        transitionSpec = {
                            if (targetState.isAfter(initialState)) {
                                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                    slideOutHorizontally { width -> -width } + fadeOut())
                            } else {
                                (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                    slideOutHorizontally { width -> width } + fadeOut())
                            }
                        },
                        label = "CalendarGridTransition"
                    ) { targetMonth ->
                        CalendarGrid(
                            currentMonth = targetMonth,
                            selectedDate = selectedDate,
                            dayData = dayData,
                            onDateClick = onDateClick
                        )
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "Dark Theme"
)
@Composable
fun CalendarPreviewDark() {
    NeiroTheme(darkTheme = true) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
            dayData = emptyMap(),
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {}
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Light Theme")
@Composable
fun CalendarPreviewLight() {
    NeiroTheme(darkTheme = false) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
            dayData = emptyMap(),
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {}
        )
    }
}
