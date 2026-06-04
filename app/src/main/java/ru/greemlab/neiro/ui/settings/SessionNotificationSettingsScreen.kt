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
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.R
import ru.greemlab.neiro.notifications.ScheduledNotificationTime
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
                        title = stringResource(R.string.notification_settings_confirmed),
                        subtitle = stringResource(R.string.notification_settings_confirmed_hint),
                        icon = Icons.Rounded.CheckCircle,
                        checked = settings.notifyClientConfirmed,
                        onCheckedChange = viewModel::setNotifyClientConfirmed,
                    )
                    EventToggle(
                        title = stringResource(R.string.notification_settings_arrived),
                        subtitle = stringResource(R.string.notification_settings_arrived_hint),
                        icon = Icons.Rounded.AddCircle,
                        checked = settings.notifyClientArrived,
                        onCheckedChange = viewModel::setNotifyClientArrived,
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
                        subtitle = stringResource(
                            R.string.notification_settings_digest_hint,
                            settings.todayDigestTime.formatForDisplay(),
                        ),
                        icon = Icons.Rounded.Today,
                        checked = settings.notifyTodayDigest,
                        onCheckedChange = viewModel::setNotifyTodayDigest,
                    )
                    if (settings.notifyTodayDigest) {
                        NotificationTimeSelector(
                            time = settings.todayDigestTime,
                            onTimeChange = viewModel::setTodayDigestTime,
                        )
                    }

                    EventToggle(
                        title = stringResource(R.string.notification_settings_tomorrow_digest),
                        subtitle = stringResource(
                            R.string.notification_settings_tomorrow_digest_hint,
                            settings.tomorrowDigestTime.formatForDisplay(),
                        ),
                        icon = Icons.Rounded.WbTwilight,
                        checked = settings.notifyTomorrowDigest,
                        onCheckedChange = viewModel::setNotifyTomorrowDigest,
                    )
                    if (settings.notifyTomorrowDigest) {
                        NotificationTimeSelector(
                            time = settings.tomorrowDigestTime,
                            onTimeChange = viewModel::setTomorrowDigestTime,
                        )
                    }

                    EventToggle(
                        title = stringResource(R.string.notification_settings_archive_reminder),
                        subtitle = stringResource(
                            R.string.notification_settings_archive_reminder_hint,
                            settings.archiveReminderTime.formatForDisplay(),
                        ),
                        icon = Icons.Rounded.Storage,
                        checked = settings.notifyArchiveReminder,
                        onCheckedChange = viewModel::setNotifyArchiveReminder,
                    )
                    if (settings.notifyArchiveReminder) {
                        NotificationTimeSelector(
                            time = settings.archiveReminderTime,
                            onTimeChange = viewModel::setArchiveReminderTime,
                        )
                    }
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
private fun NotificationTimeSelector(
    time: ScheduledNotificationTime,
    onTimeChange: (ScheduledNotificationTime) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showPicker = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp),
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                R.string.notification_settings_delivery_time,
                time.formatForDisplay(),
            ),
        )
    }

    if (showPicker) {
        val pickerState = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.notification_settings_delivery_time_dialog)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTimeChange(
                            ScheduledNotificationTime(pickerState.hour, pickerState.minute),
                        )
                        showPicker = false
                    },
                ) {
                    Text(stringResource(R.string.notification_settings_time_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.notification_settings_time_cancel))
                }
            },
            text = { TimePicker(state = pickerState) },
        )
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

