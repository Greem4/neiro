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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.OnYClientsYellow
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.YClientsYellow
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.calendar.ProfileTotals
import ru.greemlab.neiro.ui.calendar.computeProfileTotals
import ru.greemlab.neiro.ui.components.NeiroLogo
import ru.greemlab.neiro.ui.components.StatRow
import ru.greemlab.neiro.ui.sync.SyncUiState
import ru.greemlab.neiro.ui.sync.SyncViewModel
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Боковая панель профиля в [ModalNavigationDrawer][androidx.compose.material3.ModalNavigationDrawer].
 *
 * Показывает сводную статистику по всему периоду работы и кнопки перехода
 * в настройки профиля и приложения.
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
    val dayData by calendarViewModel.dayData.collectAsState()
    val syncState by syncViewModel.uiState.collectAsState()
    val isLoggedIn by syncViewModel.isLoggedIn.collectAsState()
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

    ProfileContentImpl(
        profile = profile,
        dayData = dayData,
        syncState = syncState,
        isLoggedInToYClients = isLoggedIn,
        onOpenSettings = onOpenSettings,
        onOpenAppSettings = onOpenAppSettings,
        onOpenYClients = onOpenYClients,
        autoSyncEnabled = autoSyncEnabled,
        onSyncNow = syncViewModel::syncCurrentMonth,
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
    dayData: Map<LocalDate, List<String>>,
    syncState: SyncUiState,
    isLoggedInToYClients: Boolean,
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
    val today = remember { LocalDate.now() }

    val totals: ProfileTotals = remember(dayData, profile.pricePerSession, profile.pricePerDiagnostics, profile.monthlyTaxAmount, today) {
        computeProfileTotals(dayData, profile.pricePerSession, profile.pricePerDiagnostics, today, profile.monthlyTaxAmount)
    }

    val netEarnedText = remember(totals.netEarned) { formatRubles(totals.netEarned) }
    val earnedText = remember(totals.totalEarned) { formatRubles(totals.totalEarned) }
    val expectedText = remember(totals.expectedFromFuture) { formatRubles(totals.expectedFromFuture) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            NeiroLogo(size = 64.dp)
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

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Статистика работы",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(12.dp))

                StatRow("Занятий проведено", totals.attendedSessions.toString())
                StatRow("Запланировано впереди", totals.futureSessions.toString())
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                StatRow("Чистыми (с налогом)", netEarnedText, isHighlight = true)
                StatRow("Без налога", earnedText)
                StatRow("Ожидаемый доход", expectedText)
            }
        }

        YClientsActionBlock(
            isLoggedIn = isLoggedInToYClients,
            autoSyncEnabled = autoSyncEnabled,
            syncState = syncState,
            onOpenYClients = onOpenYClients,
            onSyncNow = onSyncNow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (profile.isRegistered) "Настройки профиля" else "Создать профиль")
        }

        TextButton(
            onClick = onOpenAppSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Настройки приложения", style = MaterialTheme.typography.bodyMedium)
        }

        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(24.dp))
            DevDrawerSection(
                onLogin = onDevLogin,
                onSync = onDevSync,
                onReset = onDevReset,
                onFullSetup = onDevFullSetup
            )
        }
    }
}

@Composable
private fun DevDrawerSection(
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onReset: () -> Unit,
    onFullSetup: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Инструменты разработчика",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = onLogin,
                label = { Text("Вход") },
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = onSync,
                label = { Text("Синхр.") },
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = onReset,
                label = { Text("Сброс") },
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = onFullSetup,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Полная настройка", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { menuExpanded = !menuExpanded },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                if (menuExpanded) "Скрыть меню" else "Быстрые действия",
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (menuExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
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
                    title = "Смена статуса",
                    subtitle = "Подтверждение / приход",
                    onClick = { SessionNotificationDevPreview.showStatusChanged(context) },
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
                    subtitle = "Перенести сегодняшние занятия в архив",
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
 *  - если уже вошёл — запускает синхронизацию текущего месяца.
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
                dayData = emptyMap(),
                syncState = SyncUiState(),
                isLoggedInToYClients = true,
                onOpenSettings = {},
                onOpenAppSettings = {},
            )
        }
    }
}
