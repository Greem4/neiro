package ru.greemlab.neiro.ui.screens

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.ui.profile.ProfileContent
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.profile.SettingsScreen
import java.time.LocalDate
import java.time.YearMonth
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.components.CalendarGrid
import ru.greemlab.neiro.ui.components.CalendarHeader
import ru.greemlab.neiro.ui.components.DayDetailsDialog
import ru.greemlab.neiro.ui.components.WeekDaysRow
import java.util.Locale

/**
 * Основной экран календаря.
 * Управляет состоянием отображения диалога и взаимодействует с [CalendarViewModel].
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel()
) {
    // Подписка на состояния из ViewModel
    val currentMonth by viewModel.currentMonth.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val dayData by viewModel.dayData.collectAsState()
    
    // Состояние профиля для фильтрации календаря
    val profile by profileViewModel.userProfile.collectAsState()
    
    // Состояние видимости диалога редактирования дня
    var showDialog by remember { mutableStateOf(false) }
    
    // Состояние шторки (Drawer)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Состояние экрана настроек
    var showSettings by remember { mutableStateOf(false) }

    // Состояние диалога прибыли
    var showProfitDetails by remember { mutableStateOf(false) }

    // Состояние диалога статистики занятий
    var showLessonsDetails by remember { mutableStateOf(false) }

    // Обработка кнопки "Назад"
    BackHandler(enabled = drawerState.isOpen || showSettings || showProfitDetails || showLessonsDetails) {
        if (showSettings) {
            showSettings = false
        } else if (showProfitDetails) {
            showProfitDetails = false
        } else if (showLessonsDetails) {
            showLessonsDetails = false
        } else {
            scope.launch { drawerState.close() }
        }
    }

    if (showSettings) {
        SettingsScreen(
            viewModel = profileViewModel,
            onBack = { showSettings = false }
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.fillMaxWidth(0.8f) // Занимает 80% ширины
                ) {
                    ProfileContent(
                        profileViewModel = profileViewModel,
                        calendarViewModel = viewModel,
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            showSettings = true
                        }
                    )
                }
            }
        ) {
            // Контент экрана
            CalendarScreenContent(
                currentMonth = currentMonth,
                selectedDate = selectedDate,
                dayData = dayData,
                workingDays = profile.workingDays, // Передаем рабочие дни для фильтрации
                pricePerSession = profile.pricePerSession,
                monthlyTaxAmount = profile.monthlyTaxAmount,
                onPreviousMonth = { viewModel.previousMonth() },
                onNextMonth = { viewModel.nextMonth() },
                onTodayClick = { viewModel.goToToday() },
                onMenuClick = {
                    scope.launch { drawerState.open() }
                },
                onDateClick = {
                    viewModel.selectDate(it)
                    showDialog = true
                },
                onProfitClick = {
                    showProfitDetails = true
                },
                onLessonsClick = {
                    showLessonsDetails = true
                }
            )
        }
    }

    // Отображение диалога статистики занятий
    if (showLessonsDetails) {
        LessonsDetailsDialog(
            currentMonth = currentMonth,
            dayData = dayData,
            onDismiss = { showLessonsDetails = false }
        )
    }

    // Отображение диалога прибыли
    if (showProfitDetails) {
        ProfitDetailsDialog(
            currentMonth = currentMonth,
            dayData = dayData,
            pricePerSession = profile.pricePerSession,
            monthlyTaxAmount = profile.monthlyTaxAmount,
            onDismiss = { showProfitDetails = false }
        )
    }

    // Отображение диалога при выборе даты
    if (showDialog && selectedDate != null) {
        DayDetailsDialog(
            date = selectedDate!!,
            initialNames = dayData[selectedDate!!] ?: emptyList(),
            userProfile = profile, // Передаем профиль в диалог
            onDismiss = { showDialog = false },
            onSave = { names, repeat ->
                viewModel.saveNamesForDate(selectedDate!!, names, repeat)
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
    workingDays: Set<java.time.DayOfWeek> = emptySet(),
    pricePerSession: Double = 0.0,
    monthlyTaxAmount: Double = 0.0,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onMenuClick: () -> Unit,
    onDateClick: (LocalDate) -> Unit,
    onProfitClick: () -> Unit = {},
    onLessonsClick: () -> Unit = {}
) {
    // Расчет статистики за текущий месяц
    val monthData = remember(dayData, currentMonth) {
        dayData.filterKeys { it.month == currentMonth.month && it.year == currentMonth.year }
    }

    val completedLessons = remember(monthData) {
        monthData.values.flatten().count { it.endsWith("|true") }
    }
    
    val totalEarnings = completedLessons * pricePerSession
    val netProfit = if (totalEarnings > 0) totalEarnings - monthlyTaxAmount else 0.0

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

            Spacer(modifier = Modifier.height(20.dp))

            // Блок статистики
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = "Занятий",
                    value = completedLessons.toString(),
                    icon = Icons.Rounded.School,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onLessonsClick
                )
                StatCard(
                    label = "Прибыль",
                    value = "${String.format(Locale.getDefault(), "%.0f", netProfit)} ₽",
                    icon = Icons.Rounded.Payments,
                    color = Color(0xFF4CAF50), // Зеленый для прибыли
                    modifier = Modifier.weight(1f),
                    onClick = onProfitClick
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

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
                            workingDays = workingDays, // Передаем рабочие дни в сетку
                            onDateClick = onDateClick
                        )
                    }
                }
            }
        }
    }
}

/**
 * Красивый диалог с подробной статистикой занятий.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LessonsDetailsDialog(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    onDismiss: () -> Unit
) {
    val monthData = remember(dayData, currentMonth) {
        dayData.filterKeys { it.month == currentMonth.month && it.year == currentMonth.year }
    }

    val allLessons = monthData.values.flatten()
    val completedLessons = allLessons.count { it.endsWith("|true") }
    val totalScheduled = allLessons.size
    
    // Считаем уроки, которые еще не наступили (сегодня и позже), 
    // но в текущей логике 'false' может означать и пропуск, и будущее занятие.
    // Для более точной статистики можно было бы проверять дату, но пока оставим базово.

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Занятия за ${currentMonth.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale("ru")).replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LessonStatRow(
                    label = "Проведено",
                    value = completedLessons,
                    color = MaterialTheme.colorScheme.primary,
                    isBold = true
                )
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                
                LessonStatRow(
                    label = "Всего запланировано",
                    value = totalScheduled,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                LessonStatRow(
                    label = "Осталось / Не подтверждено",
                    value = totalScheduled - completedLessons,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun LessonStatRow(
    label: String,
    value: Int,
    color: Color,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.toString(),
            style = if (isBold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (isBold) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = color
        )
    }
}

/**
 * Красивый диалог с подробной информацией о прибыли.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ProfitDetailsDialog(
    currentMonth: YearMonth,
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    monthlyTaxAmount: Double,
    onDismiss: () -> Unit
) {
    val monthData = remember(dayData, currentMonth) {
        dayData.filterKeys { it.month == currentMonth.month && it.year == currentMonth.year }
    }

    val completedLessons = monthData.values.flatten().count { it.endsWith("|true") }
    val totalScheduledLessons = monthData.values.flatten().size
    
    val earnedGross = completedLessons * pricePerSession
    val earnedNet = if (earnedGross > 0) earnedGross - monthlyTaxAmount else 0.0
    
    val expectedGross = totalScheduledLessons * pricePerSession
    val expectedNet = if (expectedGross > 0) expectedGross - monthlyTaxAmount else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Финансы за ${currentMonth.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale("ru")).replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ProfitRow(
                    label = "Заработано чистыми",
                    value = earnedNet,
                    color = Color(0xFF4CAF50),
                    isBold = true
                )
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                
                ProfitRow(
                    label = "Заработано всего (грязными)",
                    value = earnedGross,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                ProfitRow(
                    label = "Ожидаемый доход (план)",
                    value = expectedNet,
                    color = MaterialTheme.colorScheme.primary
                )
                
                if (monthlyTaxAmount > 0) {
                    ProfitRow(
                        label = "Налог (вычтен)",
                        value = monthlyTaxAmount,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        prefix = "-"
                    )
                }
            }
        }
    )
}

@Composable
private fun ProfitRow(
    label: String,
    value: Double,
    color: Color,
    isBold: Boolean = false,
    prefix: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$prefix${String.format(Locale.getDefault(), "%.0f", value)} ₽",
            style = if (isBold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (isBold) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = color
        )
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
            pricePerSession = 1500.0,
            monthlyTaxAmount = 500.0,
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
            pricePerSession = 1500.0,
            monthlyTaxAmount = 500.0,
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {}
        )
    }
}

/**
 * Карточка статистики для отображения ключевых показателей.
 */
@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f)
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = color
                    )
                }
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
