package ru.greemlab.neiro.ui.screens

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.ui.profile.ProfileContent
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.profile.SettingsScreen
import ru.greemlab.neiro.ui.settings.AppSettingsScreen
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import java.time.LocalDate
import java.time.YearMonth
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.calendar.rememberCalendarMonthStats
import ru.greemlab.neiro.ui.components.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import ru.greemlab.neiro.ui.calendar.SessionParser
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Основной экран календаря.
 * Управляет состоянием отображения диалогов и взаимодействует с [CalendarViewModel].
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel(),
    profileViewModel: ProfileViewModel = viewModel(),
) {
    // Состояния из ViewModels
    val currentMonth by viewModel.currentMonth.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val dayData by viewModel.dayData.collectAsState()
    val profileState by profileViewModel.userProfile.collectAsState()
    
    val profile = profileState ?: UserProfile()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Состояния UI (диалоги и дочерние экраны)
    var showDialog by remember { mutableStateOf(value = false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showRegistrationPrompt by remember { mutableStateOf(false) }
    var showProfitDetails by remember { mutableStateOf(false) }
    var showLessonsDetails by remember { mutableStateOf(false) }

    // Расчет статистики за текущий месяц
    val stats = rememberCalendarMonthStats(
        currentMonth = currentMonth,
        dayData = dayData,
        pricePerSession = profile.pricePerSession,
        monthlyTaxAmount = profile.monthlyTaxAmount
    )

    // Обработка системной кнопки "Назад"
    val isAnyOverlayOpen = drawerState.isOpen || showSettings || showAppSettings || showProfitDetails || showLessonsDetails
    BackHandler(enabled = isAnyOverlayOpen) {
        when {
            showSettings -> showSettings = false
            showAppSettings -> showAppSettings = false
            showProfitDetails -> showProfitDetails = false
            showLessonsDetails -> showLessonsDetails = false
            else -> scope.launch { drawerState.close() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = profile.isRegistered,
            drawerContent = {
                if (drawerState.isOpen || drawerState.isAnimationRunning) {
                    ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f)) {
                        ProfileContent(
                            profileViewModel = profileViewModel,
                            calendarViewModel = viewModel,
                            onOpenSettings = {
                                scope.launch { drawerState.close() }
                                showSettings = true
                            },
                            onOpenAppSettings = {
                                scope.launch { drawerState.close() }
                                showAppSettings = true
                            }
                        )
                    }
                }
            }
        ) {
            CalendarScreenContent(
                currentMonth = currentMonth,
                selectedDate = selectedDate,
                dayData = dayData,
                stats = stats,
                workingDays = profile.workingDays,
                isRegistered = profile.isRegistered,
                onPreviousMonth = { viewModel.previousMonth() },
                onNextMonth = { viewModel.nextMonth() },
                onTodayClick = { viewModel.goToToday() },
                onMenuClick = { scope.launch { drawerState.open() } },
                onDateClick = {
                    if (profile.isRegistered) {
                        viewModel.selectDate(it)
                        showDialog = true
                    } else {
                        showRegistrationPrompt = true
                    }
                },
                onToggleAttendance = { date, index -> viewModel.toggleAttendance(date, index) },
                onDeleteSession = { date, index -> viewModel.deleteSession(date, index) },
                onEditDay = { date ->
                    viewModel.selectDate(date)
                    showDialog = true
                },
                onProfitClick = {
                    if (profile.isRegistered) showProfitDetails = true
                    else showRegistrationPrompt = true
                },
                onLessonsClick = {
                    if (profile.isRegistered) showLessonsDetails = true
                    else showRegistrationPrompt = true
                },
                onRegistrationRequired = { 
                    showRegistrationPrompt = true 
                }
            )
        }

        if (showSettings) {
            SettingsScreen(
                viewModel = profileViewModel,
                onBack = { showSettings = false }
            )
        }

        if (showAppSettings) {
            AppSettingsScreen(
                onBack = { showAppSettings = false }
            )
        }
    }

    // Диалоговые окна
    if (showRegistrationPrompt) {
        RegistrationPromptDialog(
            onDismiss = { showRegistrationPrompt = false },
            onConfirm = {
                showRegistrationPrompt = false
                showSettings = true
            }
        )
    }

    if (showLessonsDetails) {
        LessonsDetailsDialog(
            currentMonth = currentMonth,
            stats = stats,
            onDismiss = { showLessonsDetails = false }
        )
    }

    if (showProfitDetails) {
        ProfitDetailsDialog(
            currentMonth = currentMonth,
            stats = stats,
            onDismiss = { showProfitDetails = false }
        )
    }

    if (showDialog && (selectedDate != null)) {
        DayDetailsDialog(
            date = selectedDate!!,
            initialNames = dayData[selectedDate!!] ?: emptyList(),
            userProfile = profile,
            onDismiss = { showDialog = false },
            onSave = { names, repeat, repeatNext ->
                viewModel.saveNamesForDate(selectedDate!!, names, repeat, repeatNext)
                showDialog = false
            }
        )
    }
}

/**
 * Чистый UI контент экрана календаря.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreenContent(
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    dayData: Map<LocalDate, List<String>>,
    stats: CalendarMonthStats,
    workingDays: Set<java.time.DayOfWeek> = emptySet(),
    isRegistered: Boolean = true,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onMenuClick: () -> Unit,
    onDateClick: (LocalDate) -> Unit,
    onToggleAttendance: (LocalDate, Int) -> Unit = { _, _ -> },
    onDeleteSession: (LocalDate, Int) -> Unit = { _, _ -> },
    onEditDay: (LocalDate) -> Unit = {},
    onProfitClick: () -> Unit = {},
    onLessonsClick: () -> Unit = {},
    onRegistrationRequired: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            CalendarHeader(
                currentMonth = currentMonth,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onTodayClick = onTodayClick,
                onMenuClick = onMenuClick,
                isRegistered = isRegistered,
                onRegistrationRequired = onRegistrationRequired
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = "Занятий",
                    value = stats.completedCount.toString(),
                    icon = Icons.Rounded.School,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onLessonsClick
                )
                StatCard(
                    label = "Прибыль",
                    value = String.format(Locale.getDefault(), "%.0f ₽", stats.netProfit),
                    icon = Icons.Rounded.Payments,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.weight(1f),
                    onClick = onProfitClick
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Прогресс месяца
            MonthlyProgressCard(
                completed = stats.completedCount,
                total = stats.totalScheduled,
                expectedIncome = stats.expectedIncome
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    WeekDaysRow()

                    AnimatedContent(
                        targetState = currentMonth,
                        transitionSpec = {
                            if (targetState.isAfter(initialState)) {
                                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                    slideOutHorizontally { width -> -width } + fadeOut(),
                                )
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
                            workingDays = workingDays,
                            onDateClick = onDateClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonthlyProgressCard(
    completed: Int,
    total: Int,
    expectedIncome: Double,
    modifier: Modifier = Modifier
) {
    val progress = if (total > 0) completed.toFloat() / total else 0f
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "План на месяц",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$completed из $total занятий",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                strokeCap = StrokeCap.Round,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Ожидаемый доход: ${String.format(Locale.getDefault(), "%.0f", expectedIncome)} ₽",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Dark Theme")
@Composable
fun CalendarPreviewDark() {
    NeiroTheme(darkTheme = true) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
            dayData = emptyMap(),
            stats = CalendarMonthStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            isRegistered = true,
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {},
            onToggleAttendance = { _, _ -> },
            onDeleteSession = { _, _ -> },
            onEditDay = {}
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
            stats = CalendarMonthStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            isRegistered = true,
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {},
            onToggleAttendance = { _, _ -> },
            onDeleteSession = { _, _ -> },
            onEditDay = {}
        )
    }
}
