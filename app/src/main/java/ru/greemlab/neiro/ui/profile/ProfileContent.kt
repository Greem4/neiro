package ru.greemlab.neiro.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.notifications.SessionNotificationDevPreview
import ru.greemlab.neiro.notifications.SessionNotificationSyncSimulation
import kotlinx.coroutines.launch
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.OnYClientsYellow
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.YClientsYellow
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.calendar.ProfileYearStats
import ru.greemlab.neiro.ui.calendar.availableStatsYears
import ru.greemlab.neiro.ui.calendar.rememberProfileYearStats
import ru.greemlab.neiro.ui.components.NeiroLogo
import ru.greemlab.neiro.ui.components.ProfileAvatar
import ru.greemlab.neiro.ui.settings.SettingsGroupCard
import java.time.YearMonth
import ru.greemlab.neiro.ui.settings.SettingsNavigationRow
import ru.greemlab.neiro.ui.settings.SettingsSection
import ru.greemlab.neiro.ui.sync.SyncUiState
import ru.greemlab.neiro.ui.sync.SyncViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Боковая панель профиля в [ModalNavigationDrawer][androidx.compose.material3.ModalNavigationDrawer].
 *
 * Переходы в настройки профиля и приложения, синхронизация YClients.
 */
@Composable
fun ProfileContent(
    profileViewModel: ProfileViewModel,
    calendarViewModel: CalendarViewModel,
    syncViewModel: SyncViewModel,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenYClients: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profile by profileViewModel.userProfile.collectAsState()
    val dayData by calendarViewModel.effectiveDayData.collectAsState()
    val syncState by syncViewModel.uiState.collectAsState()
    val isLoggedIn by syncViewModel.isLoggedIn.collectAsState()
    val userAvatarUrl by syncViewModel.userAvatarUrl.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var autoSyncEnabled by remember { mutableStateOf(syncViewModel.isAutoSyncEnabled) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autoSyncEnabled = syncViewModel.isAutoSyncEnabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentYear = YearMonth.now().year
    val availableYears = remember(dayData) { availableStatsYears(dayData, currentYear) }
    var selectedYear by rememberSaveable { mutableIntStateOf(currentYear) }
    LaunchedEffect(availableYears) {
        if (selectedYear !in availableYears) {
            selectedYear = availableYears.first()
        }
    }
    val yearStats = rememberProfileYearStats(
        year = selectedYear,
        dayData = dayData,
        pricePerSession = profile.pricePerSession,
        pricePerDiagnostics = profile.pricePerDiagnostics,
        monthlyTaxAmount = profile.monthlyTaxAmount,
        sessionPriceHistory = profile.sessionPriceHistory,
    )

    ProfileContentImpl(
        profile = profile,
        yearStats = yearStats,
        availableYears = availableYears,
        selectedYear = selectedYear,
        onYearSelected = { selectedYear = it },
        syncState = syncState,
        isLoggedInToYClients = isLoggedIn,
        userAvatarUrl = userAvatarUrl,
        onOpenSettings = onOpenSettings,
        onOpenAppSettings = onOpenAppSettings,
        onOpenYClients = onOpenYClients,
        autoSyncEnabled = autoSyncEnabled,
        onSyncNow = syncViewModel::syncAllThroughCurrentMonth,
        onDevLogin = syncViewModel::devLogin,
        onDevSync = syncViewModel::devSyncAll,
        onDevReset = syncViewModel::devResetData,
        onDevFullSetup = syncViewModel::devFullSetup,
        modifier = modifier,
    )
}

@Composable
private fun ProfileContentImpl(
    profile: UserProfile,
    yearStats: ProfileYearStats,
    availableYears: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit,
    syncState: SyncUiState,
    isLoggedInToYClients: Boolean,
    userAvatarUrl: String? = null,
    autoSyncEnabled: Boolean = true,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenYClients: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onDevLogin: () -> Unit = {},
    onDevSync: () -> Unit = {},
    onDevReset: () -> Unit = {},
    onDevFullSetup: () -> Unit = {},
    modifier: Modifier = Modifier,
    nameStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    professionStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 16.dp)
            .verticalScroll(scrollState),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 20.dp),
        ) {
            if (profile.showAvatar) {
                ProfileAvatar(
                    avatarUrl = userAvatarUrl,
                    size = 64.dp,
                    contentDescription = profile.name.ifBlank { "Пользователь" },
                )
            } else {
                NeiroLogo(size = 64.dp)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name.ifBlank { "Пользователь" },
                    style = nameStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = profile.activityType.ifBlank { "Профессия не указана" },
                    style = professionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ProfileYearStatsSection(
            stats = yearStats,
            availableYears = availableYears,
            selectedYear = selectedYear,
            onYearSelected = onYearSelected,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        SettingsGroupCard(modifier = Modifier.padding(bottom = 24.dp)) {
            // TODO: Добавить переход к списку карточек детей (статистика по ученикам).
            SettingsNavigationRow(
                title = if (profile.isRegistered) "Профиль" else "Создать профиль",
                subtitle = if (profile.isRegistered) {
                    "Имя, занятость, цены и налог"
                } else {
                    "Заполните данные для расчёта дохода"
                },
                icon = Icons.Default.Person,
                onClick = onOpenSettings,
            )
            SettingsNavigationRow(
                title = "Приложение",
                subtitle = "Тема, уведомления, экспорт данных",
                icon = Icons.Default.Tune,
                onClick = onOpenAppSettings,
                showDivider = true,
            )
        }

        SettingsSection(title = "Синхронизация") {
            YClientsActionBlock(
                isLoggedIn = isLoggedInToYClients,
                autoSyncEnabled = autoSyncEnabled,
                syncState = syncState,
                onOpenYClients = onOpenYClients,
                onSyncNow = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(32.dp))
            DevDrawerMenu(
                onLogin = onDevLogin,
                onSync = onDevSync,
                onReset = onDevReset,
                onFullSetup = onDevFullSetup,
            )
        }
    }
}

@Composable
private fun DevDrawerMenu(
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onReset: () -> Unit,
    onFullSetup: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { menuExpanded = !menuExpanded },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            ),
        ) {
            Text(
                text = if (menuExpanded) "Скрыть" else "Dev",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (menuExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        }

        AnimatedVisibility(
            visible = menuExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DevMenuSectionTitle("Загрузка данных")
                DevMenuItem(
                    title = "Войти в YClients",
                    subtitle = "Тестовый логин из local.properties",
                    onClick = onLogin,
                )
                DevMenuItem(
                    title = "Синхронизировать календарь",
                    subtitle = "Загрузить текущий месяц из API",
                    onClick = onSync,
                )
                DevMenuItem(
                    title = "Сбросить данные",
                    subtitle = "Очистить календарь и выйти из YClients",
                    onClick = onReset,
                )
                DevMenuItem(
                    title = "Полная настройка",
                    subtitle = "Сброс → профиль → вход → синхронизация",
                    onClick = onFullSetup,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DevMenuSectionTitle("Симуляция синка (API)")
                DevMenuItem(
                    title = "Сброс снимка синка",
                    subtitle = "Baseline и dedupe — перед повторным прогоном",
                    onClick = { SessionNotificationSyncSimulation.resetState(context) },
                )
                DevMenuItem(
                    title = "Синк: новая запись",
                    subtitle = "live API → детектор → push",
                    onClick = {
                        scope.launch { SessionNotificationSyncSimulation.simulateNewBooking(context) }
                    },
                )
                DevMenuItem(
                    title = "Синк: отмена",
                    subtitle = "Статус → отменён",
                    onClick = {
                        scope.launch { SessionNotificationSyncSimulation.simulateCancelled(context) }
                    },
                )
                DevMenuItem(
                    title = "Синк: перенос",
                    subtitle = "Тот же клиент, другое время",
                    onClick = {
                        scope.launch { SessionNotificationSyncSimulation.simulateRescheduled(context) }
                    },
                )
                DevMenuItem(
                    title = "Синк: удаление",
                    subtitle = "Запись исчезла из dayData",
                    onClick = {
                        scope.launch { SessionNotificationSyncSimulation.simulateDeleted(context) }
                    },
                )
                DevMenuItem(
                    title = "Синк: подтвердил",
                    subtitle = "Ожидание → галочка",
                    onClick = {
                        scope.launch { SessionNotificationSyncSimulation.simulateClientConfirmed(context) }
                    },
                )
                DevMenuItem(
                    title = "Синк: пришёл",
                    subtitle = "Ожидание → пришёл",
                    onClick = {
                        scope.launch { SessionNotificationSyncSimulation.simulateClientArrived(context) }
                    },
                )
                DevMenuItem(
                    title = "Синк: несколько событий",
                    subtitle = "Новая запись + отмена",
                    onClick = {
                        scope.launch { SessionNotificationSyncSimulation.simulateMultipleEvents(context) }
                    },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DevMenuSectionTitle("Тест уведомлений")
                DevMenuItem(
                    title = "Новая запись",
                    subtitle = "Изменение в расписании",
                    onClick = { SessionNotificationDevPreview.showNewBooking(context) },
                )
                DevMenuItem(
                    title = "Отмена",
                    subtitle = "Занятие отменено",
                    onClick = { SessionNotificationDevPreview.showCancelled(context) },
                )
                DevMenuItem(
                    title = "Перенос",
                    subtitle = "Другое время у того же клиента",
                    onClick = { SessionNotificationDevPreview.showRescheduled(context) },
                )
                DevMenuItem(
                    title = "Удаление",
                    subtitle = "Запись исчезла из календаря",
                    onClick = { SessionNotificationDevPreview.showDeleted(context) },
                )
                DevMenuItem(
                    title = "Подтвердил визит",
                    subtitle = "Галочка в YClients",
                    onClick = { SessionNotificationDevPreview.showClientConfirmed(context) },
                )
                DevMenuItem(
                    title = "Пришёл",
                    subtitle = "Отметка «пришёл»",
                    onClick = { SessionNotificationDevPreview.showClientArrived(context) },
                )
                DevMenuItem(
                    title = "Несколько событий",
                    subtitle = "Групповое уведомление",
                    onClick = { SessionNotificationDevPreview.showGroupedEvents(context) },
                )
                DevMenuItem(
                    title = "Напоминание",
                    subtitle = "За 15 минут до начала",
                    onClick = { SessionNotificationDevPreview.showReminder(context) },
                )
                DevMenuItem(
                    title = "Сводка на сегодня",
                    subtitle = "Список занятий на день",
                    onClick = { SessionNotificationDevPreview.showTodayDigest(context) },
                )
                DevMenuItem(
                    title = "Сводка на завтра",
                    subtitle = "Вечерняя сводка",
                    onClick = { SessionNotificationDevPreview.showTomorrowDigest(context) },
                )
                DevMenuItem(
                    title = "Архив сегодня",
                    subtitle = "Перенести занятия за сегодня в архив",
                    onClick = { SessionNotificationDevPreview.showArchiveReminder(context) },
                )
            }
        }
    }
}

@Composable
private fun DevMenuSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun DevMenuItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Жёлтая кнопка YClients с двойной ролью.
 *
 * Поведение зависит от состояния авторизации (флаг хранится в `TokenStorage`
 * и переживает перезапуск приложения):
 *  - если пользователь ещё не вошёл — открывает экран авторизации;
 *  - если уже вошёл — синхронизирует всю историю до конца текущего месяца.
 *
 * Под кнопкой выводится короткая строка статуса: «не подключено», «последняя
 * синхронизация …», прогресс или ошибка. Управление аккаунтом (просмотр имени,
 * выход) живёт в «Настройках профиля» — `SettingsScreen`.
 */
@Composable
private fun YClientsActionBlock(
    isLoggedIn: Boolean,
    autoSyncEnabled: Boolean,
    syncState: SyncUiState,
    onOpenYClients: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLoading = syncState.isLoading
    val hasError = syncState.error != null && !isLoading
    val hasSuccess = syncState.showSuccess && !isLoading && syncState.error == null

    Column(modifier = modifier) {
        Button(
            onClick = { if (isLoggedIn) onSyncNow() else onOpenYClients() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = YClientsYellow,
                contentColor = OnYClientsYellow,
                disabledContainerColor = YClientsYellow.copy(alpha = 0.55f),
                disabledContentColor = OnYClientsYellow.copy(alpha = 0.7f),
            ),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = OnYClientsYellow,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Синхронизирую…", fontWeight = FontWeight.SemiBold)
                }
                isLoggedIn -> {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Синхронизировать YClients", fontWeight = FontWeight.SemiBold)
                }
                else -> {
                    Icon(Icons.Rounded.CloudSync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Войти в YClients", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        YClientsStatusLine(
            isLoggedIn = isLoggedIn,
            autoSyncEnabled = autoSyncEnabled,
            isLoading = isLoading,
            hasError = hasError,
            hasSuccess = hasSuccess,
            syncState = syncState,
        )
    }
}

@Composable
private fun YClientsStatusLine(
    isLoggedIn: Boolean,
    autoSyncEnabled: Boolean,
    isLoading: Boolean,
    hasError: Boolean,
    hasSuccess: Boolean,
    syncState: SyncUiState,
) {
    val (icon, tint, text) = when {
        hasError -> Triple(
            Icons.Rounded.ErrorOutline,
            MaterialTheme.colorScheme.error,
            syncState.error.orEmpty(),
        )
        hasSuccess -> {
            val count = syncState.syncedCount
            val label = if (count > 0) {
                "Готово · добавлено $count ${plural(count, "запись", "записи", "записей")}"
            } else {
                "Готово · новых записей нет"
            }
            Triple(Icons.Rounded.CheckCircle, ScheduleHeaderGreen, label)
        }
        isLoading -> Triple(
            Icons.Rounded.Sync,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Загружаю записи…",
        )
        isLoggedIn -> {
            val last = syncState.lastSyncDate
            val label = when {
                !autoSyncEnabled && last != null ->
                    "Подключено · обновлено " + last.format(LAST_SYNC_FORMATTER)
                !autoSyncEnabled ->
                    "Подключено · только ручная синхронизация"
                last != null ->
                    "Подключено · обновлено " + last.format(LAST_SYNC_FORMATTER) +
                        " · авто каждые 4 ч"
                else ->
                    "Подключено · авто при открытии приложения"
            }
            Triple(Icons.Rounded.CheckCircle, ScheduleHeaderGreen, label)
        }
        else -> Triple(
            Icons.Rounded.CloudSync,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Войдите, чтобы автоматически подтягивать записи",
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

private val LAST_SYNC_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

@Preview(showBackground = true, name = "Profile Light")
@Composable
private fun ProfileContentLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProfileContentImpl(
                profile = UserProfile(
                    name = "Света",
                    activityType = "Репетитор",
                    pricePerSession = 1500.0,
                    monthlyTaxAmount = 5000.0,
                ),
                yearStats = ProfileYearStats(
                    year = YearMonth.now().year,
                    completedSessions = 42,
                    totalNetEarned = 58_000.0,
                    monthlyNet = listOf(
                        4_000.0, 5_000.0, 4_500.0, 6_000.0, 5_500.0, 4_800.0,
                        3_200.0, 5_500.0, 6_500.0, 4_000.0, 4_500.0, 5_000.0,
                    ),
                    monthlyCompleted = List(12) { 3 + it % 4 },
                ),
                availableYears = listOf(YearMonth.now().year),
                selectedYear = YearMonth.now().year,
                onYearSelected = {},
                syncState = SyncUiState(),
                isLoggedInToYClients = true,
                onOpenSettings = {},
                onOpenAppSettings = {},
            )
        }
    }
}
