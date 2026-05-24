package ru.greemlab.neiro.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.ui.calendar.CalendarMode
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.calendar.computeDayStats
import ru.greemlab.neiro.ui.calendar.rememberCalendarMonthStats
import ru.greemlab.neiro.ui.components.*
import ru.greemlab.neiro.ui.auth.AuthScreen
import ru.greemlab.neiro.ui.profile.ProfileContent
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.profile.SettingsScreen
import ru.greemlab.neiro.ui.settings.AppSettingsScreen
import ru.greemlab.neiro.ui.settings.ProfitDisplayPreferences
import ru.greemlab.neiro.ui.settings.ProfitDisplaySettings
import ru.greemlab.neiro.ui.settings.ProfitDisplaySettingsScreen
import ru.greemlab.neiro.ui.settings.SessionNotificationSettingsScreen
import ru.greemlab.neiro.notifications.InAppNotificationStore
import ru.greemlab.neiro.ui.sync.SyncViewModel
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Все типы overlay, отображающихся поверх календаря.
 * Хранение в одном sealed класс гарантирует, что одновременно открыт
 * не более одного overlay; «Назад» идёт на уровень выше (вложенные настройки → родитель).
 */
private sealed interface CalendarOverlay {
    data object None : CalendarOverlay
    data object Settings : CalendarOverlay
    data object AppSettings : CalendarOverlay
    data object NotificationSettings : CalendarOverlay
    data object YClients : CalendarOverlay
    data object RegistrationPrompt : CalendarOverlay
    data object ProfitDetails : CalendarOverlay
    data object ProfitSettings : CalendarOverlay
    data object LessonsDetails : CalendarOverlay
    data object DayDetails : CalendarOverlay
    data object Notifications : CalendarOverlay
}

/** Куда вернуться по «Назад» с текущего overlay (иерархия настроек). */
private fun CalendarOverlay.onSystemBack(yClientsReturnTo: CalendarOverlay): CalendarOverlay = when (this) {
    CalendarOverlay.NotificationSettings,
    CalendarOverlay.ProfitSettings -> CalendarOverlay.AppSettings
    CalendarOverlay.YClients -> yClientsReturnTo
    else -> CalendarOverlay.None
}

/**
 * Основной экран календаря.
 *
 * ## Профиль (боковая панель)
 *
 * Профиль реализован через [ModalNavigationDrawer] и [ProfileContent].
 *
 * TODO: Доработка боковой панели (дизайн и функциональность).
 *
 * Панель не открывается сама — только явным действием пользователя:
 * свайп слева направо или тап по логотипу «N» в шапке.
 *
 * Жесты drawer отключаются, пока открыт любой overlay, чтобы не конфликтовать
 * с прокруткой и жестами внутри них.
 *
 * @see ru.greemlab.neiro.ui.components.NeiroLogo
 *
 * Подробнее: `docs/profile-drawer.md` в корне репозитория.
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel(),
// ...
    profileViewModel: ProfileViewModel = viewModel(),
    openDateFromNotification: String? = null,
    highlightSlotKeyFromNotification: String? = null,
) {
    val currentMonth by viewModel.currentMonth.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val dayData by viewModel.effectiveDayData.collectAsState()
    val calendarMode by viewModel.calendarMode.collectAsState()
    val profile by profileViewModel.userProfile.collectAsState()

    var highlightSlotKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var overlay by rememberSaveable(stateSaver = OverlaySaver) {
        mutableStateOf<CalendarOverlay>(CalendarOverlay.None)
    }
    var yClientsReturnOverlay by rememberSaveable(stateSaver = OverlaySaver) {
        mutableStateOf(CalendarOverlay.None)
    }
    /** После закрытия overlay, открытый из drawer, снова показать боковую панель. */
    var returnToDrawerOnOverlayClose by rememberSaveable { mutableStateOf(false) }

    fun applyOverlayBackNavigation() {
        val next = overlay.onSystemBack(yClientsReturnOverlay)
        overlay = next
        if (next is CalendarOverlay.None && returnToDrawerOnOverlayClose) {
            returnToDrawerOnOverlayClose = false
            scope.launch { drawerState.open() }
        }
    }

    fun applyYClientsBackNavigation() {
        val next = yClientsReturnOverlay
        overlay = next
        if (next is CalendarOverlay.None && returnToDrawerOnOverlayClose) {
            returnToDrawerOnOverlayClose = false
            scope.launch { drawerState.open() }
        }
    }

    var handledNotificationDeepLink by rememberSaveable(openDateFromNotification) {
        mutableStateOf(false)
    }

    LaunchedEffect(
        openDateFromNotification,
        highlightSlotKeyFromNotification,
        profile.isRegistered,
        handledNotificationDeepLink,
    ) {
        if (handledNotificationDeepLink) return@LaunchedEffect
        val raw = openDateFromNotification ?: return@LaunchedEffect
        runCatching { LocalDate.parse(raw) }.getOrNull()?.let { date ->
            viewModel.navigateToDate(date)
            highlightSlotKey = highlightSlotKeyFromNotification
            overlay = if (profile.isRegistered) {
                CalendarOverlay.DayDetails
            } else {
                CalendarOverlay.RegistrationPrompt
            }
            handledNotificationDeepLink = true
        }
    }

    LaunchedEffect(highlightSlotKey) {
        if (highlightSlotKey == null) return@LaunchedEffect
        delay(3_500)
        highlightSlotKey = null
    }

    val stats = rememberCalendarMonthStats(
        currentMonth = currentMonth,
        dayData = dayData,
        pricePerSession = profile.pricePerSession,
        pricePerDiagnostics = profile.pricePerDiagnostics,
        monthlyTaxAmount = profile.monthlyTaxAmount,
    )

    val context = LocalContext.current
    val notificationStore = remember(context) { InAppNotificationStore.get(context) }
    val inAppNotifications by notificationStore.items.collectAsState()
    val unreadNotificationCount = remember(inAppNotifications) {
        inAppNotifications.count { !it.read }
    }

    var profitDisplay by remember(context) {
        mutableStateOf(ProfitDisplayPreferences.get(context).read())
    }
    LaunchedEffect(overlay, context) {
        profitDisplay = ProfitDisplayPreferences.get(context).read()
    }

    val drawerGesturesEnabled = overlay is CalendarOverlay.None

    val syncViewModel: SyncViewModel = viewModel()
    val syncState by syncViewModel.uiState.collectAsState()
    val isYClientsLoggedIn by syncViewModel.isLoggedIn.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, syncViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncViewModel.refreshLastSyncFromPrefs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(syncState.showSuccess, syncState.error) {
        if (syncState.showSuccess) {
            val message = if (syncState.syncedCount > 0) {
                "Обновлено записей: ${syncState.syncedCount}"
            } else {
                "Данные актуальны"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            syncViewModel.clearSuccess()
        }
        syncState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            syncViewModel.clearError()
        }
    }

    val isAnyOverlayOpen = drawerState.isOpen || overlay !is CalendarOverlay.None
    BackHandler(enabled = isAnyOverlayOpen) {
        when {
            overlay !is CalendarOverlay.None -> applyOverlayBackNavigation()
            drawerState.isOpen -> scope.launch { drawerState.close() }
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
                        syncViewModel = syncViewModel,
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            returnToDrawerOnOverlayClose = true
                            overlay = CalendarOverlay.Settings
                        },
                        onOpenAppSettings = {
                            scope.launch { drawerState.close() }
                            returnToDrawerOnOverlayClose = true
                            overlay = CalendarOverlay.AppSettings
                        },
                        onOpenYClients = {
                            scope.launch { drawerState.close() }
                            returnToDrawerOnOverlayClose = true
                            yClientsReturnOverlay = CalendarOverlay.None
                            overlay = CalendarOverlay.YClients
                        },
                    )
                }
            },
        ) {
            CalendarScreenContent(
                // TODO: Улучшить основной календарь.
                currentMonth = currentMonth,
                selectedDate = selectedDate,
                dayData = dayData,
                calendarMode = calendarMode,
                onModeChange = viewModel::setCalendarMode,
                stats = stats,
                workingDays = profile.workingDays,
                isRegistered = profile.isRegistered,
                onPreviousMonth = viewModel::previousMonth,
                onNextMonth = viewModel::nextMonth,
                onMonthSelected = viewModel::setMonth,
                onTodayClick = viewModel::goToToday,
                onMenuClick = { scope.launch { drawerState.open() } },
                onSyncClick = {
                    if (isYClientsLoggedIn) {
                        syncViewModel.syncMonth(currentMonth)
                    } else {
                        yClientsReturnOverlay = CalendarOverlay.None
                        overlay = CalendarOverlay.YClients
                    }
                },
                isSyncing = syncState.isLoading,
                pricePerSession = profile.pricePerSession,
                pricePerDiagnostics = profile.pricePerDiagnostics,
                profitDisplay = profitDisplay,
                onDateClick = { date ->
                    if (!profile.isRegistered) {
                        overlay = CalendarOverlay.RegistrationPrompt
                        return@CalendarScreenContent
                    }
                    if (selectedDate == date) {
                        overlay = CalendarOverlay.DayDetails
                    } else {
                        viewModel.selectDate(date)
                    }
                },
                onProfitClick = {
                    overlay = if (profile.isRegistered) {
                        CalendarOverlay.ProfitDetails
                    } else {
                        CalendarOverlay.RegistrationPrompt
                    }
                },
                onLessonsClick = {
                    overlay = if (profile.isRegistered) {
                        CalendarOverlay.LessonsDetails
                    } else {
                        CalendarOverlay.RegistrationPrompt
                    }
                },
                onRegistrationRequired = { overlay = CalendarOverlay.RegistrationPrompt },
                onNotificationsClick = { overlay = CalendarOverlay.Notifications },
                unreadNotificationCount = unreadNotificationCount,
            )
        }

        if (overlay is CalendarOverlay.Settings) {
            SettingsScreen(
                viewModel = profileViewModel,
                syncViewModel = syncViewModel,
                onBack = ::applyOverlayBackNavigation,
                onOpenYClientsAuth = {
                    yClientsReturnOverlay = CalendarOverlay.Settings
                    overlay = CalendarOverlay.YClients
                },
            )
        }

        if (overlay is CalendarOverlay.AppSettings) {
            AppSettingsScreen(
                onBack = ::applyOverlayBackNavigation,
                onOpenNotificationSettings = { overlay = CalendarOverlay.NotificationSettings },
                onOpenProfitSettings = { overlay = CalendarOverlay.ProfitSettings },
            )
        }

        if (overlay is CalendarOverlay.NotificationSettings) {
            SessionNotificationSettingsScreen(
                onBack = { overlay = CalendarOverlay.AppSettings },
            )
        }

        if (overlay is CalendarOverlay.ProfitSettings) {
            ProfitDisplaySettingsScreen(
                onBack = { overlay = CalendarOverlay.AppSettings },
            )
        }

        if (overlay is CalendarOverlay.YClients) {
            AuthScreen(
                onBack = ::applyYClientsBackNavigation,
                onLoginSuccess = {
                    syncViewModel.syncCurrentMonth()
                    applyYClientsBackNavigation()
                },
            )
        }
    }

    when (overlay) {
        is CalendarOverlay.RegistrationPrompt -> RegistrationPromptDialog(
            onDismiss = { overlay = CalendarOverlay.None },
            onConfirm = { overlay = CalendarOverlay.Settings },
        )

        is CalendarOverlay.LessonsDetails -> LessonsDetailsDialog(
            currentMonth = currentMonth,
            stats = stats,
            onDismiss = { overlay = CalendarOverlay.None },
        )

        is CalendarOverlay.ProfitDetails -> ProfitDetailsDialog(
            currentMonth = currentMonth,
            stats = stats,
            pricePerSession = profile.pricePerSession,
            display = profitDisplay,
            onDismiss = { overlay = CalendarOverlay.None },
        )

        is CalendarOverlay.Notifications -> {
            LaunchedEffect(Unit) {
                notificationStore.markAllRead()
            }
            NotificationsDialog(
                notifications = inAppNotifications,
                onDismiss = { overlay = CalendarOverlay.None },
                onNotificationClick = { item ->
                    val date = item.relatedDate
                    if (date != null) {
                        viewModel.navigateToDate(date)
                        highlightSlotKey = item.highlightSlotKey
                        overlay = if (profile.isRegistered) {
                            CalendarOverlay.DayDetails
                        } else {
                            CalendarOverlay.RegistrationPrompt
                        }
                    } else {
                        overlay = CalendarOverlay.None
                    }
                },
                onDismissNotification = { notificationStore.remove(it.id) },
                onClearAll = { notificationStore.clearAll() },
            )
        }

        is CalendarOverlay.DayDetails -> {
            val date = selectedDate
            val savedDayData by viewModel.savedDayData.collectAsState()

            if (date != null) {
                val isArchived = savedDayData.containsKey(date)
                    DayDetailsDialog(
                        date = date,
                        // TODO: Добавить возможность отмечать проведенное занятие и др. статусы.
                        initialNames = dayData[date].orEmpty(),
                    userProfile = profile,
                    isArchived = isArchived,
                    highlightSlotKey = highlightSlotKey,
                    isRefreshing = syncState.isLoading,
                    onRefresh = {
                        if (isYClientsLoggedIn) {
                            syncViewModel.syncMonth(YearMonth.from(date))
                        } else {
                            yClientsReturnOverlay = CalendarOverlay.DayDetails
                            overlay = CalendarOverlay.YClients
                        }
                    },
                    onDismiss = {
                        highlightSlotKey = null
                        overlay = CalendarOverlay.None
                    },
                    onSave = { updatedNames ->
                        viewModel.saveNamesForDate(date, updatedNames)
                        overlay = CalendarOverlay.None
                    },
                    onArchive = {
                        if (isArchived) {
                            viewModel.unarchiveDay(date)
                        } else {
                            viewModel.archiveDay(date, dayData[date].orEmpty())
                        }
                    },
                )
            } else {
                overlay = CalendarOverlay.None
            }
        }

        else -> Unit
    }
}

/**
 * Сохраняем тип overlay при ротации устройства, чтобы пользователь не терял
 * открытый диалог. Сериализуем как строку — это компактно и достаточно.
 */
private val OverlaySaver = Saver<CalendarOverlay, String>(
    save = { value ->
        when (value) {
            CalendarOverlay.None -> "none"
            CalendarOverlay.Settings -> "settings"
            CalendarOverlay.AppSettings -> "app_settings"
            CalendarOverlay.NotificationSettings -> "notification_settings"
            CalendarOverlay.YClients -> "yclients"
            CalendarOverlay.RegistrationPrompt -> "registration"
            CalendarOverlay.ProfitDetails -> "profit_details"
            CalendarOverlay.ProfitSettings -> "profit_settings"
            CalendarOverlay.LessonsDetails -> "lessons"
            CalendarOverlay.DayDetails -> "day"
            CalendarOverlay.Notifications -> "notifications"
        }
    },
    restore = { token ->
        when (token) {
            "settings" -> CalendarOverlay.Settings
            "app_settings" -> CalendarOverlay.AppSettings
            "notification_settings" -> CalendarOverlay.NotificationSettings
            "yclients" -> CalendarOverlay.YClients
            "registration" -> CalendarOverlay.RegistrationPrompt
            "profit" -> CalendarOverlay.ProfitDetails
            "profit_details" -> CalendarOverlay.ProfitDetails
            "profit_settings" -> CalendarOverlay.ProfitSettings
            "lessons" -> CalendarOverlay.LessonsDetails
            "day" -> CalendarOverlay.DayDetails
            "notifications" -> CalendarOverlay.Notifications
            else -> CalendarOverlay.None
        }
    },
)

/**
 * Чистый UI календарного экрана (без drawer и overlay).
 * Используется в [CalendarScreen] и в Compose Preview.
 */
@Composable
fun CalendarScreenContent(
    modifier: Modifier = Modifier,
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    dayData: Map<LocalDate, List<String>>,
    calendarMode: CalendarMode = CalendarMode.SYNCED,
    onModeChange: (CalendarMode) -> Unit = {},
    stats: CalendarMonthStats,
    workingDays: Set<DayOfWeek> = emptySet(),
    isRegistered: Boolean = true,
    pricePerSession: Double = 0.0,
    pricePerDiagnostics: Double = 0.0,
    profitDisplay: ProfitDisplaySettings = ProfitDisplaySettings(),
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onMonthSelected: (YearMonth) -> Unit = {},
    onTodayClick: () -> Unit,
    onMenuClick: () -> Unit,
    onSyncClick: () -> Unit = {},
    isSyncing: Boolean = false,
    onDateClick: (LocalDate) -> Unit,
    onProfitClick: () -> Unit = {},
    onLessonsClick: () -> Unit = {},
    onRegistrationRequired: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    unreadNotificationCount: Int = 0,
) {
    val daySummaryStats = remember(selectedDate, dayData, pricePerSession, pricePerDiagnostics) {
        val date = selectedDate ?: return@remember null
        computeDayStats(
            dayData[date].orEmpty(),
            pricePerSession,
            pricePerDiagnostics,
        )
    }
    var monthPickerVisible by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                CalendarHeader(
                    currentMonth = currentMonth,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onMonthTitleClick = { monthPickerVisible = !monthPickerVisible },
                    onMenuClick = onMenuClick,
                    onNotificationsClick = onNotificationsClick,
                    unreadNotificationCount = unreadNotificationCount,
                )

                Spacer(modifier = Modifier.height(8.dp))

                MonthOverviewCard(
                    stats = stats,
                    pricePerSession = pricePerSession,
                    display = profitDisplay,
                    onLessonsClick = onLessonsClick,
                    onProfitClick = onProfitClick,
                )

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        CalendarToolbar(
                            calendarMode = calendarMode,
                            isSyncing = isSyncing,
                            onModeChange = onModeChange,
                            onSyncClick = onSyncClick,
                        )

                        if (isRegistered && selectedDate != null && daySummaryStats != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            DaySummarySlot(
                                date = selectedDate,
                                stats = daySummaryStats,
                                onTodayClick = onTodayClick,
                                isRegistered = isRegistered,
                                onRegistrationRequired = onRegistrationRequired,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        WeekDaysRow()

                        AnimatedContent(
                            targetState = currentMonth,
                            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                            label = "CalendarGridTransition",
                        ) { targetMonth ->
                            CalendarGrid(
                                currentMonth = targetMonth,
                                selectedDate = selectedDate,
                                dayData = dayData,
                                workingDays = workingDays,
                                onDateClick = onDateClick,
                            )
                        }
                    }
                }
            }

            MonthPickerOverlay(
                visible = monthPickerVisible,
                currentMonth = currentMonth,
                onMonthSelected = onMonthSelected,
                onDismiss = { monthPickerVisible = false },
            )
        }
    }
}

@Composable
private fun MonthOverviewCard(
    stats: CalendarMonthStats,
    pricePerSession: Double,
    display: ProfitDisplaySettings,
    onLessonsClick: () -> Unit,
    onProfitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (stats.totalScheduled > 0) {
        (stats.completedCount.toFloat() / stats.totalScheduled).coerceIn(0f, 1f)
    } else 0f
    val profitAmount = when {
        display.showNetProfit -> stats.netProfit
        display.showGrossEarned -> stats.totalEarned
        else -> 0.0
    }
    val profitValue = remember(profitAmount, display) { formatRubles(profitAmount) }
    val progressLabel = remember(stats.completedCount, stats.totalScheduled) {
        "${stats.completedCount} из ${stats.totalScheduled}"
    }
    val expectedIncomeText = remember(stats.expectedIncome) {
        formatRubles(stats.expectedIncome)
    }
    val sessionPriceText = remember(pricePerSession) { formatRubles(pricePerSession) }
    val overviewSubtitle = remember(
        stats.expectedIncome,
        pricePerSession,
        display,
        expectedIncomeText,
        sessionPriceText,
    ) {
        buildOverviewProfitSubtitle(
            display = display,
            expectedIncome = stats.expectedIncome,
            expectedIncomeText = expectedIncomeText,
            pricePerSession = pricePerSession,
            sessionPriceText = sessionPriceText,
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactStatTile(
                    label = "Занятий",
                    value = stats.completedCount.toString(),
                    icon = Icons.Rounded.School,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onLessonsClick,
                )
                CompactStatTile(
                    label = "Прибыль",
                    value = profitValue,
                    icon = Icons.Rounded.Payments,
                    color = ScheduleHeaderGreen,
                    modifier = Modifier.weight(1f),
                    onClick = onProfitClick,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Прогресс",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                )
                val hasSubtitle = overviewSubtitle.isNotBlank()
                Text(
                    text = overviewSubtitle.ifBlank { "\u00A0" },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (hasSubtitle) 0.85f else 0f,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun buildOverviewProfitSubtitle(
    display: ProfitDisplaySettings,
    expectedIncome: Double,
    expectedIncomeText: String,
    pricePerSession: Double,
    sessionPriceText: String,
): String {
    val parts = mutableListOf<String>()
    if (display.showExpectedInOverview && expectedIncome > 0.0) {
        parts += "Ожидается $expectedIncomeText"
    }
    if (display.showPricePerSession && pricePerSession > 0.0) {
        parts += "занятие $sessionPriceText"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun CompactStatTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = color.copy(alpha = 0.14f),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = color,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
    name = "Dark Theme",
)
@Composable
private fun CalendarPreviewDark() {
    NeiroTheme(darkTheme = true) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
            dayData = emptyMap(),
            calendarMode = CalendarMode.SYNCED,
            stats = previewStats(),
            isRegistered = true,
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Light Theme")
@Composable
private fun CalendarPreviewLight() {
    NeiroTheme(darkTheme = false) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
            dayData = emptyMap(),
            calendarMode = CalendarMode.SYNCED,
            stats = previewStats(),
            isRegistered = true,
            onPreviousMonth = {},
            onNextMonth = {},
            onTodayClick = {},
            onMenuClick = {},
            onDateClick = {},
        )
    }
}

private fun previewStats() = CalendarMonthStats(
    completedCount = 12,
    totalScheduled = 20,
    remainingCount = 8,
    totalEarned = 15000.0,
    netProfit = 14000.0,
    intensiveEarnings = 0.0,
    diagnosticsEarnings = 0.0,
    expectedIncome = 25000.0,
    taxAmount = 1000.0,
)
