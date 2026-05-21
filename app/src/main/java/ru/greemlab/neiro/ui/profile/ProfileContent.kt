package ru.greemlab.neiro.ui.profile

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
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ProfitGreen
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

    ProfileContentImpl(
        profile = profile,
        dayData = dayData,
        syncState = syncState,
        isLoggedInToYClients = isLoggedIn,
        onOpenSettings = onOpenSettings,
        onOpenAppSettings = onOpenAppSettings,
        onOpenYClients = onOpenYClients,
        onSyncNow = syncViewModel::syncCurrentMonth,
        modifier = modifier,
    )
}

@Composable
private fun ProfileContentImpl(
    profile: UserProfile,
    dayData: Map<LocalDate, List<String>>,
    syncState: SyncUiState,
    isLoggedInToYClients: Boolean,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenYClients: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    modifier: Modifier = Modifier,
    nameStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    professionStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val scrollState = rememberScrollState()
    val today = remember { LocalDate.now() }

    val totals: ProfileTotals = remember(dayData, profile.pricePerSession, profile.monthlyTaxAmount, today) {
        computeProfileTotals(dayData, profile.pricePerSession, today, profile.monthlyTaxAmount)
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

        Button(
            onClick = onOpenYClients,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            Icon(Icons.Rounded.CloudSync, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("YClients")
        }

        SyncNowCard(
            syncState = syncState,
            isLoggedIn = isLoggedInToYClients,
            onSyncNow = onSyncNow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
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
    }
}

/**
 * Карточка «Синхронизировать сейчас».
 *
 * Видна всегда, но активна только когда пользователь вошёл в YClients —
 * иначе нажатие ничего бы не дало. Подсвечивает статус последнего синка:
 *  - индикатор загрузки во время запроса;
 *  - зелёная подпись с количеством новых записей при успехе;
 *  - красная подпись с текстом ошибки.
 */
@Composable
private fun SyncNowCard(
    syncState: SyncUiState,
    isLoggedIn: Boolean,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLoading = syncState.isLoading
    val hasError = syncState.error != null && !isLoading
    val hasSuccess = syncState.showSuccess && !isLoading && syncState.error == null

    val containerColor = when {
        hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        hasSuccess -> ProfitGreen.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Синхронизация YClients",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = syncSubtitle(syncState, isLoggedIn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSyncNow,
                enabled = isLoggedIn && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Синхронизирую…")
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Синхронизировать сейчас")
                }
            }

            if (hasSuccess || hasError) {
                Spacer(modifier = Modifier.height(10.dp))
                SyncStatusLine(
                    syncState = syncState,
                    hasError = hasError,
                    hasSuccess = hasSuccess,
                )
            }
        }
    }
}

@Composable
private fun SyncStatusLine(
    syncState: SyncUiState,
    hasError: Boolean,
    hasSuccess: Boolean,
) {
    val (icon, tint, text) = when {
        hasError -> Triple(
            Icons.Rounded.ErrorOutline,
            MaterialTheme.colorScheme.error,
            syncState.error.orEmpty(),
        )
        hasSuccess -> {
            val count = syncState.syncedCount
            val countLabel = if (count > 0) {
                "Готово · добавлено $count ${plural(count, "запись", "записи", "записей")}"
            } else {
                "Готово · новых записей нет"
            }
            Triple(Icons.Rounded.CheckCircle, ProfitGreen, countLabel)
        }
        else -> Triple(Icons.Rounded.Sync, MaterialTheme.colorScheme.onSurfaceVariant, "")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

private fun syncSubtitle(state: SyncUiState, isLoggedIn: Boolean): String {
    if (!isLoggedIn) return "Войдите в YClients, чтобы загружать записи"
    if (state.isLoading) return "Загружаю записи за текущий месяц"
    val last = state.lastSyncDate
    return if (last != null) {
        "Последний раз: " + last.format(LAST_SYNC_FORMATTER)
    } else {
        "Загрузит весь текущий месяц"
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
