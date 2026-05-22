package ru.greemlab.neiro.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.R
import ru.greemlab.neiro.notifications.SessionNotificationPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionNotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SessionNotificationSettingsViewModel = viewModel(),
) {
    val settings by remember { derivedStateOf { viewModel.state } }
    var reminderExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            MasterSwitchCard(
                checked = settings.isEnabled,
                onCheckedChange = viewModel::setEnabled,
            )

            if (settings.isEnabled) {
                SettingsSection(title = stringResource(R.string.notification_settings_changes)) {
                    EventToggle(
                        title = stringResource(R.string.notification_settings_new),
                        subtitle = stringResource(R.string.notification_settings_new_hint),
                        icon = Icons.Rounded.Event,
                        checked = settings.notifyNewBooking,
                        onCheckedChange = viewModel::setNotifyNewBooking,
                    )
                    EventToggle(
                        title = stringResource(R.string.notification_settings_cancelled),
                        subtitle = stringResource(R.string.notification_settings_cancelled_hint),
                        icon = Icons.Rounded.EventBusy,
                        checked = settings.notifyCancelled,
                        onCheckedChange = viewModel::setNotifyCancelled,
                    )
                    EventToggle(
                        title = stringResource(R.string.notification_settings_rescheduled),
                        subtitle = stringResource(R.string.notification_settings_rescheduled_hint),
                        icon = Icons.Rounded.EventRepeat,
                        checked = settings.notifyRescheduled,
                        onCheckedChange = viewModel::setNotifyRescheduled,
                    )
                    EventToggle(
                        title = stringResource(R.string.notification_settings_deleted),
                        subtitle = stringResource(R.string.notification_settings_deleted_hint),
                        icon = Icons.Rounded.EventBusy,
                        checked = settings.notifyDeleted,
                        onCheckedChange = viewModel::setNotifyDeleted,
                    )
                    EventToggle(
                        title = stringResource(R.string.notification_settings_status),
                        subtitle = stringResource(R.string.notification_settings_status_hint),
                        icon = Icons.Rounded.TrendingUp,
                        checked = settings.notifyStatusChanged,
                        onCheckedChange = viewModel::setNotifyStatusChanged,
                    )
                }

                SettingsSection(title = stringResource(R.string.notification_settings_schedule)) {
                    EventToggle(
                        title = stringResource(R.string.notification_settings_reminder),
                        subtitle = stringResource(
                            R.string.notification_settings_reminder_hint,
                            settings.reminderMinutesBefore,
                        ),
                        icon = Icons.Rounded.Schedule,
                        checked = settings.notifyReminder,
                        onCheckedChange = viewModel::setNotifyReminder,
                    )

                    if (settings.notifyReminder) {
                        ReminderMinutesSelector(
                            selected = settings.reminderMinutesBefore,
                            expanded = reminderExpanded,
                            onExpandedChange = { reminderExpanded = it },
                            onSelect = {
                                viewModel.setReminderMinutes(it)
                                reminderExpanded = false
                            },
                        )
                    }

                    EventToggle(
                        title = stringResource(R.string.notification_settings_digest),
                        subtitle = stringResource(R.string.notification_settings_digest_hint),
                        icon = Icons.Rounded.Today,
                        checked = settings.notifyTodayDigest,
                        onCheckedChange = viewModel::setNotifyTodayDigest,
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterSwitchCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notifications_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.notification_settings_master_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun EventToggle(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderMinutesSelector(
    selected: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp),
    ) {
        OutlinedTextField(
            value = stringResource(R.string.notification_settings_reminder_minutes, selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.notification_settings_reminder_lead)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            SessionNotificationPreferences.REMINDER_MINUTE_OPTIONS.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notification_settings_reminder_minutes, minutes)) },
                    onClick = { onSelect(minutes) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}
