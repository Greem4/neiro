package ru.greemlab.neiro.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.greemlab.neiro.R
import ru.greemlab.neiro.domain.models.CalendarMonthStats
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.domain.models.earningsContext
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.ui.calendar.ArchiveSyncCompare
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
import ru.greemlab.neiro.notifications.ArchiveNotificationStore
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel(),
// ...
    profileViewModel: ProfileViewModel = viewModel(),
    openDateFromNotification: String? = null,
    highlightSlotKeyFromNotification: String? = null,
    notificationDeepLinkVersion: Int = 0,
) {
    val currentMonth by viewModel.currentMonth.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val currentMonthDayData by viewModel.currentMonthDayData.collectAsStateWithLifecycle()
    val calendarMode by viewModel.calendarMode.collectAsStateWithLifecycle()
    // Полные карты dayData/savedDayData сюда не подписываем: корню экрана
    // достаточно текущего месяца и контекста выбранного дня (см. C3 аудита).
    val archiveMismatchDates by viewModel.archiveMismatchDates.collectAsStateWithLifecycle()
    val daysNeedingArchive by viewModel.daysNeedingArchive.collectAsStateWithLifecycle()
    val selectedDayContext by viewModel.selectedDayContext.collectAsStateWithLifecycle()
    val profile by profileViewModel.userProfile.collectAsStateWithLifecycle()

    var highlightSlotKey by rememberSaveable { mutableStateOf<String?>(null) }
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

    var lastHandledDeepLinkVersion by rememberSaveable { mutableIntStateOf(-1) }

    LaunchedEffect(
        openDateFromNotification,
        highlightSlotKeyFromNotification,
        notificationDeepLinkVersion,
        profile.isRegistered,
    ) {
        if (notificationDeepLinkVersion == lastHandledDeepLinkVersion) return@LaunchedEffect
        val raw = openDateFromNotification ?: return@LaunchedEffect
        runCatching { LocalDate.parse(raw) }.getOrNull()?.let { date ->
            viewModel.navigateToDate(date)
            highlightSlotKey = highlightSlotKeyFromNotification
            overlay = if (profile.isRegistered) {
                CalendarOverlay.DayDetails
            } else {
                CalendarOverlay.RegistrationPrompt
            }
            lastHandledDeepLinkVersion = notificationDeepLinkVersion
        }
    }

    LaunchedEffect(highlightSlotKey) {
        if (highlightSlotKey == null) return@LaunchedEffect
        delay(3_500)
        highlightSlotKey = null
    }

    val rates = remember(profile) { profile.earningsContext() }
    val stats = rememberCalendarMonthStats(
        currentMonth = currentMonth,
        dayData = currentMonthDayData,
        rates = rates,
    )

    val context = LocalContext.current
    val activeNotificationStore = remember(context) { InAppNotificationStore.get(context) }
    val archiveNotificationStore = remember(context) { ArchiveNotificationStore.get(context) }
    val activeNotifications by activeNotificationStore.items.collectAsStateWithLifecycle()
    val archiveNotifications by archiveNotificationStore.items.collectAsStateWithLifecycle()
    val isArchiveCalendarMode = calendarMode == CalendarMode.PERSONAL
    val visibleNotifications = if (isArchiveCalendarMode) archiveNotifications else activeNotifications
    val unreadNotificationCount = remember(isArchiveCalendarMode, activeNotifications, archiveNotifications) {
        val list = if (isArchiveCalendarMode) archiveNotifications else activeNotifications
        list.count { !it.read }
    }

    var profitDisplay by remember(context) {
        mutableStateOf(ProfitDisplayPreferences.get(context).read())
    }
    LaunchedEffect(overlay, context) {
        profitDisplay = ProfitDisplayPreferences.get(context).read()
    }

    val drawerGesturesEnabled = overlay is CalendarOverlay.None

    val syncViewModel: SyncViewModel = viewModel()
    val syncState by syncViewModel.uiState.collectAsStateWithLifecycle()
    val isYClientsLoggedIn by syncViewModel.isLoggedIn.collectAsStateWithLifecycle()

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

    LaunchedEffect(
        syncState.showSuccess,
        syncState.error,
        syncState.profileReviewReminder,
        syncState.openProfileSettings,
    ) {
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
        syncState.profileReviewReminder?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            if (syncState.openProfileSettings) {
                overlay = CalendarOverlay.Settings
            }
            syncViewModel.clearProfileReviewReminder()
        }
    }

    var showArchiveOverwriteConfirm by rememberSaveable { mutableStateOf(false) }

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
                    // drawerContent всегда в composition — не считаем годовую
                    // статистику профиля, пока панель закрыта.
                    if (drawerState.isOpen || drawerState.isAnimationRunning) {
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
                }
            },
        ) {
            CalendarScreenContent(
                currentMonth = currentMonth,
                selectedDate = selectedDate,
                selectedDaySessions = selectedDayContext?.effective.orEmpty(),
                monthDayData = currentMonthDayData,
                archiveMismatchDates = archiveMismatchDates,
                daysNeedingArchive = daysNeedingArchive,
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
                rates = rates,
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
                    syncViewModel.syncAfterLogin()
                    applyYClientsBackNavigation()
                },
            )
        }

        if (overlay is CalendarOverlay.DayDetails) {
            val dayContext = selectedDayContext
            if (dayContext == null) {
                // selectedDate уже восстановлен (SavedStateHandle), а selectedDayContext
                // (combine→stateIn) ещё не выдал первое значение — контекст грузится,
                // а не отсутствует. Закрываем оверлей только если дата и правда null,
                // иначе restore после process death мгновенно схлопывал открытый день (U3).
                if (selectedDate == null) {
                    LaunchedEffect(Unit) { overlay = CalendarOverlay.None }
                }
            } else {
                val date = dayContext.date
                val archivedSessions = dayContext.archived
                val isArchived = archivedSessions != null
                val syncedSessions = dayContext.synced
                val archiveMismatch = isArchived &&
                    ArchiveSyncCompare.differs(syncedSessions, archivedSessions.orEmpty())
                val archiveMismatchDetails = remember(syncedSessions, archivedSessions, archiveMismatch) {
                    if (archiveMismatch) {
                        ArchiveSyncCompare.describeDiff(
                            syncedSessions,
                            archivedSessions.orEmpty(),
                        )
                    } else {
                        emptyList()
                    }
                }
                DayDetailsDialog(
                    date = date,
                    initialNames = dayContext.effective,
                    userProfile = profile,
                    isArchived = isArchived,
                    archiveMismatch = archiveMismatch,
                    archiveMismatchDetails = archiveMismatchDetails,
                    allowStatusEdit = true,
                    highlightSlotKey = highlightSlotKey,
                    onDismiss = {
                        highlightSlotKey = null
                        overlay = CalendarOverlay.None
                    },
                    onSave = { updatedNames ->
                        viewModel.saveNamesForDate(date, updatedNames)
                        overlay = CalendarOverlay.None
                    },
                    onMoveToArchive = {
                        viewModel.archiveDay(date, syncedSessions)
                    },
                    onUnarchive = { viewModel.unarchiveDay(date) },
                    onRequestOverwriteArchive = { showArchiveOverwriteConfirm = true },
                    onStudentStatusChange = if (isArchiveCalendarMode) {
                        { index, status -> viewModel.updateSessionStatus(date, index, status) }
                    } else {
                        null
                    },
                )
            }
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
            rates = rates,
            salaryAdvanceOnCard = profile.salaryAdvanceOnCard,
            salaryMainOnCard = profile.salaryMainOnCard,
            salaryOnCardFallback = profile.salaryOnCard,
            display = profitDisplay,
            onDismiss = { overlay = CalendarOverlay.None },
        )

        is CalendarOverlay.Notifications -> {
            LaunchedEffect(isArchiveCalendarMode) {
                if (isArchiveCalendarMode) {
                    archiveNotificationStore.markAllRead()
                } else {
                    activeNotificationStore.markAllRead()
                }
            }
            NotificationsDialog(
                notifications = visibleNotifications,
                subtitleRes = if (isArchiveCalendarMode) {
                    R.string.in_app_notifications_subtitle_archive
                } else {
                    R.string.in_app_notifications_subtitle_sync
                },
                allowDismiss = !isArchiveCalendarMode,
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
                onDismissNotification = { activeNotificationStore.remove(it.id) },
                onClearAll = { activeNotificationStore.clearAll() },
            )
        }

        is CalendarOverlay.DayDetails -> {
            val dayContext = selectedDayContext
            if (showArchiveOverwriteConfirm && dayContext != null) {
                AlertDialog(
                    onDismissRequest = { showArchiveOverwriteConfirm = false },
                    title = { Text(stringResource(R.string.archive_sync_overwrite_title)) },
                    text = { Text(stringResource(R.string.archive_sync_overwrite_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.archiveDay(dayContext.date, dayContext.synced)
                                showArchiveOverwriteConfirm = false
                            },
                        ) {
                            Text(stringResource(R.string.archive_sync_overwrite_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showArchiveOverwriteConfirm = false }) {
                            Text(stringResource(R.string.archive_sync_overwrite_cancel))
                        }
                    },
                )
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreenContent(
    modifier: Modifier = Modifier,
    currentMonth: YearMonth,
    selectedDate: LocalDate?,
    selectedDaySessions: List<String> = emptyList(),
    monthDayData: Map<LocalDate, List<String>> = emptyMap(),
    archiveMismatchDates: Set<LocalDate> = emptySet(),
    daysNeedingArchive: Set<LocalDate> = emptySet(),
    calendarMode: CalendarMode = CalendarMode.SYNCED,
    onModeChange: (CalendarMode) -> Unit = {},
    stats: CalendarMonthStats,
    workingDays: Set<DayOfWeek> = emptySet(),
    isRegistered: Boolean = true,
    rates: EarningsContext = EarningsContext.Empty,
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
    onNotificationsClick: () -> Unit = {},
    unreadNotificationCount: Int = 0,
) {
    val daySummaryStats = remember(selectedDate, selectedDaySessions, rates) {
        if (selectedDate == null) return@remember null
        computeDayStats(selectedDaySessions, rates)
    }
    var monthPickerVisible by rememberSaveable { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val scrollableState = rememberScrollableState { 0f }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = onSyncClick,
            state = pullRefreshState,
            indicator = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .scrollable(scrollableState, Orientation.Vertical)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                CalendarRefreshSplit(
                    pullFraction = pullRefreshState.distanceFraction,
                    isRefreshing = isSyncing,
                    topContent = {
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
                            rates = rates,
                            display = profitDisplay,
                            onLessonsClick = onLessonsClick,
                            onProfitClick = onProfitClick,
                        )
                    },
                    bottomContent = {
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
                                    onModeChange = onModeChange,
                                    archiveBadgeCount = daysNeedingArchive.size,
                                )

                                if (isRegistered && selectedDate != null && daySummaryStats != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    DaySummarySlot(
                                        date = selectedDate,
                                        stats = daySummaryStats,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
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
                                        dayData = monthDayData,
                                        archiveMismatchDates = archiveMismatchDates,
                                        daysNeedingArchive = daysNeedingArchive,
                                        workingDays = workingDays,
                                        onDateClick = onDateClick,
                                    )
                                }
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.navigationBarsPadding().height(80.dp))
            }

            MonthPickerOverlay(
                visible = monthPickerVisible,
                currentMonth = currentMonth,
                onMonthSelected = onMonthSelected,
                onDismiss = { monthPickerVisible = false },
            )

            val isShowingToday = remember(selectedDate, currentMonth) {
                selectedDate == LocalDate.now() && currentMonth == YearMonth.now()
            }
            AnimatedVisibility(
                visible = !isShowingToday,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp)
                    .navigationBarsPadding(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = onTodayClick,
                    modifier = Modifier.height(40.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    icon = { Icon(Icons.Rounded.Today, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.calendar_go_to_today), style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
    }
}

@Composable
private fun MonthOverviewCard(
    stats: CalendarMonthStats,
    rates: EarningsContext,
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
    val sessionPriceText = remember(rates.pricePerSession) { formatRubles(rates.pricePerSession) }
    val overviewSubtitle = remember(
        stats.expectedIncome,
        stats.netProfit,
        rates,
        display,
        expectedIncomeText,
        sessionPriceText,
    ) {
        buildOverviewProfitSubtitle(
            display = display,
            expectedIncome = stats.expectedIncome,
            expectedIncomeText = expectedIncomeText,
            netProfit = stats.netProfit,
            rates = rates,
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
    netProfit: Double,
    rates: EarningsContext,
    sessionPriceText: String,
): String {
    val parts = mutableListOf<String>()
    
    if (display.showExpectedInOverview) {
        if (display.expectedIncludesNet) {
            val total = netProfit + expectedIncome
            parts += "Ожидается ${formatRubles(total)}"
        } else if (expectedIncome > 0.0) {
            parts += "Ожидается $expectedIncomeText"
        }
    }

    if (display.showPricePerSession && rates.pricePerSession > 0.0) {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPreviewDark() {
    NeiroTheme(darkTheme = true) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPreviewLight() {
    NeiroTheme(darkTheme = false) {
        CalendarScreenContent(
            currentMonth = YearMonth.now(),
            selectedDate = LocalDate.now(),
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
