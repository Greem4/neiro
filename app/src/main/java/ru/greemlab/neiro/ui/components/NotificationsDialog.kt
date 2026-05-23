package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.greemlab.neiro.R
import ru.greemlab.neiro.notifications.InAppNotification
import ru.greemlab.neiro.notifications.SessionEventType
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.util.RU_LOCALE
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU_LOCALE)

/**
 * Лента in-app уведомлений (колокольчик в [CalendarHeader]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsDialog(
    notifications: List<InAppNotification>,
    onDismiss: () -> Unit,
    onNotificationClick: (InAppNotification) -> Unit = {},
    onDismissNotification: (InAppNotification) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NotificationsContent(
            notifications = notifications,
            onDismiss = onDismiss,
            onNotificationClick = onNotificationClick,
            onDismissNotification = onDismissNotification,
            onClearAll = onClearAll,
        )
    }
}

/**
 * Выделенный контент диалога для возможности превью без самого [Dialog].
 * Это решает проблемы с рендерингом Dialog в Compose Preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsContent(
    notifications: List<InAppNotification>,
    onDismiss: () -> Unit,
    onNotificationClick: (InAppNotification) -> Unit,
    onDismissNotification: (InAppNotification) -> Unit,
    onClearAll: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.in_app_notifications_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.in_app_notifications_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            if (notifications.isEmpty()) {
                Text(
                    text = stringResource(R.string.in_app_notifications_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    items(notifications, key = { it.id }) { item ->
                        SwipeableNotificationItem(
                            item = item,
                            onClick = { onNotificationClick(item) },
                            onDismiss = { onDismissNotification(item) },
                        )
                    }
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.in_app_notifications_close))
            }

            if (notifications.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Start),
                ) {
                    Text(stringResource(R.string.in_app_notifications_clear))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNotificationItem(
    item: InAppNotification,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onDismiss()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.clip(shape),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            if (dismissState.progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer, shape)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.in_app_notifications_dismiss),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
        content = {
            NotificationListItem(item = item, onClick = onClick)
        },
    )
}

@Composable
private fun NotificationListItem(
    item: InAppNotification,
    onClick: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeLabel = remember(item.timestamp, zone) {
        timeFormatter.format(item.timestamp.atZone(zone))
    }
    val kind = remember(item.kind, item.title) { item.displayKind }
    val onTintedBackground = !item.read
    val textColors = rememberNotificationTextColors(
        kind = kind,
        read = item.read,
        onTintedBackground = onTintedBackground,
    )
    val titleText = remember(item.title, textColors) {
        buildInAppNotificationTitle(item, textColors)
    }
    val bodyText = remember(item.body, kind, textColors) {
        buildNotificationBody(item.body, kind, textColors)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (item.read) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Preview(showBackground = true, name = "All Notification Variants")
@Composable
private fun AllNotificationVariantsPreview() {
    val now = remember { 1716552000000L } // Fixed timestamp for 24.05.2024
    val dayStr = "24.05.2026"

    val notifications = listOf(
        InAppNotification(
            id = "new",
            title = "Новая запись: Анна",
            body = "$dayStr, 14:00–15:00 · Анна · Занятие",
            timestampEpochMillis = now,
            kind = SessionEventType.NEW_BOOKING.name,
        ),
        InAppNotification(
            id = "cancel",
            title = "Отмена: Борис",
            body = "$dayStr, 16:00–16:50 · Борис · Занятие",
            timestampEpochMillis = now - 60_000,
            kind = SessionEventType.CANCELLED.name,
        ),
        InAppNotification(
            id = "resched",
            title = "Перенос: Лев",
            body = "Было: $dayStr, 15:00–15:50 · Лев · Занятие\nСтало: $dayStr, 17:00–17:50 · Лев · Занятие",
            timestampEpochMillis = now - 120_000,
            kind = SessionEventType.RESCHEDULED.name,
        ),
        InAppNotification(
            id = "deleted",
            title = "Удалено: Мария",
            body = "Запись на $dayStr, 10:00–10:50 удалена из календаря",
            timestampEpochMillis = now - 180_000,
            kind = SessionEventType.DELETED.name,
        ),
        InAppNotification(
            id = "confirmed",
            title = "Подтвердил: Виктор",
            body = "Клиент подтвердил визит на $dayStr, 11:00",
            timestampEpochMillis = now - 240_000,
            kind = SessionEventType.CLIENT_CONFIRMED.name,
        ),
        InAppNotification(
            id = "arrived",
            title = "Пришёл: Елена",
            body = "Отметка «пришёл» для записи на $dayStr, 12:00",
            timestampEpochMillis = now - 300_000,
            kind = SessionEventType.CLIENT_ARRIVED.name,
        ),
        InAppNotification(
            id = "reminder",
            title = "Скоро: Дмитрий",
            body = "Занятие начнется через 30 минут ($dayStr, 18:00)",
            timestampEpochMillis = now - 360_000,
            kind = SessionEventType.REMINDER.name,
        ),
        InAppNotification(
            id = "today",
            title = "Сегодня 24 мая",
            body = "У вас 5 занятий на сегодня. Первое в 10:00.",
            timestampEpochMillis = now - 420_000,
            kind = SessionEventType.TODAY_DIGEST.name,
        ),
        InAppNotification(
            id = "tomorrow",
            title = "Завтра 25 мая",
            body = "На завтра запланировано 3 занятия.",
            timestampEpochMillis = now - 480_000,
            kind = SessionEventType.TOMORROW_DIGEST.name,
        ),
        InAppNotification(
            id = "archive",
            title = "Перенести в архив",
            body = "День 23.05.2026 завершен. Перенесите записи в архив.",
            timestampEpochMillis = now - 540_000,
            kind = SessionEventType.ARCHIVE_REMINDER.name,
        ),
        InAppNotification(
            id = "read",
            title = "Прочитанное: Иван (Пример)",
            body = "$dayStr, 09:00–09:50 · Иван · Занятие",
            timestampEpochMillis = now - 600_000,
            kind = SessionEventType.NEW_BOOKING.name,
            read = true,
        ),
    )

    NeiroTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            NotificationsContent(
                notifications = notifications,
                onDismiss = {},
                onNotificationClick = {},
                onDismissNotification = {},
                onClearAll = {},
            )
        }
    }
}

@Preview
@Composable
private fun NotificationsDialogPreview() {

    val now = remember { 1716552000000L }
    val day = LocalDate.of(2026, 5, 24)
    NeiroTheme {
        NotificationsContent(
            notifications = listOf(
                InAppNotification(
                    id = "1",
                    title = "Новая запись: Анна",
                    body = "24.05.2026, 14:00–15:00 · Анна · Занятие",
                    timestampEpochMillis = now,
                    relatedDateEpochDay = day.toEpochDay(),
                    kind = SessionEventType.NEW_BOOKING.name,
                ),
                InAppNotification(
                    id = "2",
                    title = "Отмена: Борис",
                    body = "24.05.2026, 16:00–16:50 · Борис · Занятие",
                    timestampEpochMillis = now - 60_000,
                    relatedDateEpochDay = day.toEpochDay(),
                    kind = SessionEventType.CANCELLED.name,
                ),
                InAppNotification(
                    id = "3",
                    title = "Перенос: Лев",
                    body = "Было: 24.05.2026, 15:00–15:50 · Лев · Занятие\nСтало: 24.05.2026, 17:00–17:50 · Лев · Занятие",
                    timestampEpochMillis = now - 120_000,
                    relatedDateEpochDay = day.toEpochDay(),
                    kind = SessionEventType.RESCHEDULED.name,
                ),
            ),
            onDismiss = {},
            onNotificationClick = {},
            onDismissNotification = {},
            onClearAll = {},
        )
    }
}
