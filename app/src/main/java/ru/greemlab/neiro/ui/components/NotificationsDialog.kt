package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.greemlab.neiro.R
import ru.greemlab.neiro.notifications.InAppNotification
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.util.RU_LOCALE
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU_LOCALE)

/**
 * Лента in-app уведомлений (колокольчик в [CalendarHeader]).
 */
@Composable
fun NotificationsDialog(
    notifications: List<InAppNotification>,
    onDismiss: () -> Unit,
    onNotificationClick: (InAppNotification) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
                            NotificationListItem(
                                item = item,
                                onClick = { onNotificationClick(item) },
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End),
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
}

@Composable
private fun NotificationListItem(
    item: InAppNotification,
    onClick: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val timeLabel = timeFormatter.format(item.timestamp.atZone(zone))

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (item.read) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (item.read) FontWeight.Medium else FontWeight.SemiBold,
            )
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Preview
@Composable
private fun NotificationsDialogPreview() {
    val now = System.currentTimeMillis()
    NeiroTheme {
        NotificationsDialog(
            notifications = listOf(
                InAppNotification(
                    id = "1",
                    title = "Новая запись: Анна",
                    body = "14:00–15:00 · Анна · Занятие",
                    timestampEpochMillis = now,
                    relatedDateEpochDay = LocalDate.now().toEpochDay(),
                ),
            ),
            onDismiss = {},
        )
    }
}
