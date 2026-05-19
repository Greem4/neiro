package ru.greemlab.neiro.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.calendar.computeDayStats
import ru.greemlab.neiro.ui.calendar.rememberCalendarMonthStats
import ru.greemlab.neiro.ui.components.*
import ru.greemlab.neiro.ui.profile.ProfileContent
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.profile.SettingsScreen
import ru.greemlab.neiro.ui.settings.AppSettingsScreen
import ru.greemlab.neiro.ui.util.RU_LOCALE
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val ProfitGreen = Color(0xFF4CAF50)

/**
 * Основной экран календаря.
 *
 * Управляет состоянием отображения диалогов и взаимодействует с [CalendarViewModel].
 *
 * ## Профиль (боковая панель)
 *
 * Профиль реализован через [ModalNavigationDrawer] и [ProfileContent].
 * Панель **не открывается сама** — только явным действием пользователя:
 *
 * - **Свайп слева направо** с любой точки левого края экрана (по всей высоте).
 *   Панель следует за пальцем во время жеста; закрывается свайпом обратно влево.
 * - **Тап по кругу с буквой «N»** в шапке ([NeiroLogo] в [CalendarHeader]).
 *
 * Закрытие профиля:
 * - свайп влево по панели или основному экрану;
 * - тап по затемнённой области;
 * - системная кнопка «Назад» ([BackHandler]).
 *
 * Жесты drawer отключаются, пока открыт любой оверлей (диалог дня, настройки,
 * детализация занятий/прибыли и т.д.), чтобы не конфликтовать с прокруткой и жестами внутри них.
 *
 * @see ru.greemlab.neiro.ui.components.NeiroLogo
 *
 * Подробнее: `docs/profile-drawer.md` в корне репозитория.
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
    val profile by profileViewModel.userProfile.collectAsState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Состояния UI (диалоги и дочерние экраны)
    var showDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showRegistrationPrompt by remember { mutableStateOf(false) }
    var showProfitDetails by remember { mutableStateOf(false) }
    var showLessonsDetails by remember { mutableStateOf(false) }
    var showDaySummary by remember { mutableStateOf(false) }

    // Расчет статистики за текущий месяц
    val stats = rememberCalendarMonthStats(
        currentMonth = currentMonth,
        dayData = dayData,
        pricePerSession = profile.pricePerSession,
        monthlyTaxAmount = profile.monthlyTaxAmount
    )

    // Жест «вытащить профиль» — только когда нет модальных оверлеев поверх календаря.
    val drawerGesturesEnabled = !showSettings && !showAppSettings && !showDialog &&
        !showRegistrationPrompt && !showProfitDetails && !showLessonsDetails

    // Обработка системной кнопки "Назад"
    val isAnyOverlayOpen = drawerState.isOpen || showSettings || showAppSettings
    BackHandler(enabled = isAnyOverlayOpen) {
        when {
            showSettings -> showSettings = false
            showAppSettings -> showAppSettings = false
            else -> scope.launch { drawerState.close() }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerGesturesEnabled,
            drawerContent = {
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
                showDaySummary = showDaySummary,
                pricePerSession = profile.pricePerSession,
                onDateClick = { date ->
                    if (!profile.isRegistered) {
                        showRegistrationPrompt = true
                        return@CalendarScreenContent
                    }
                    if (selectedDate == date && showDaySummary) {
                        showDialog = true
                    } else {
                        viewModel.selectDate(date)
                        showDaySummary = true
                    }
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
 * Чистый UI контент экрана календаря (без drawer и диалогов).
 *
 * Используется в [CalendarScreen] и в Compose Preview.
 *
 * @param onMenuClick Открытие профиля по тапу на логотип «N» в шапке.
 *                    Реализация (анимация drawer) — в [CalendarScreen].
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarScreenContent(
    modifier: Modifier = Modifier,
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    dayData: Map<LocalDate, List<String>>,
    stats: CalendarMonthStats,
    workingDays: Set<DayOfWeek> = emptySet(),
    isRegistered: Boolean = true,
    showDaySummary: Boolean = false,
    pricePerSession: Double = 0.0,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onMenuClick: () -> Unit,
    onDateClick: (LocalDate) -> Unit,
    onProfitClick: () -> Unit = {},
    onLessonsClick: () -> Unit = {},
    onRegistrationRequired: () -> Unit = {}
) {
    val daySummaryStats = remember(selectedDate, dayData, pricePerSession) {
        val date = selectedDate ?: return@remember null
        computeDayStats(dayData[date] ?: emptyList(), pricePerSession)
    }
    Surface(
        modifier = modifier.fillMaxSize(),
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
                val profitValue = remember(stats.netProfit) {
                    String.format(RU_LOCALE, "%.0f ₽", stats.netProfit)
                }
                StatCard(
                    label = "Прибыль",
                    value = profitValue,
                    icon = Icons.Rounded.Payments,
                    color = ProfitGreen,
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

                    // Лёгкая crossfade-анимация вместо тяжёлой slide+fade —
                    // на первом кадре отрисовка мгновенная, без накладной анимации.
                    AnimatedContent(
                        targetState = currentMonth,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
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

            if (selectedDate != null && daySummaryStats != null) {
                DaySummaryPanel(
                    visible = showDaySummary,
                    date = selectedDate,
                    stats = daySummaryStats,
                )
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
            val incomeText = remember(expectedIncome) {
                "Ожидаемый доход: ${String.format(RU_LOCALE, "%.0f", expectedIncome)} ₽"
            }
            Text(
                text = incomeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark Theme")
@Composable
fun CalendarPreviewDark() {
    NeiroTheme(darkTheme = true) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
            dayData = emptyMap(),
            stats = CalendarMonthStats(
                completedCount = 12,
                totalScheduled = 20,
                remainingCount = 8,
                totalEarned = 15000.0,
                netProfit = 14000.0,
                intensiveEarnings = 0.0,
                diagnosticsEarnings = 0.0,
                expectedIncome = 25000.0,
                taxAmount = 1000.0
            ),
            isRegistered = true,
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
            stats = CalendarMonthStats(
                completedCount = 12,
                totalScheduled = 20,
                remainingCount = 8,
                totalEarned = 15000.0,
                netProfit = 14000.0,
                intensiveEarnings = 0.0,
                diagnosticsEarnings = 0.0,
                expectedIncome = 25000.0,
                taxAmount = 1000.0
            ),
            isRegistered = true,
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {}
        )
    }
}
