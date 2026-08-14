package ru.greemlab.neiro.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.R
import ru.greemlab.neiro.update.UpdateBlockReason
import ru.greemlab.neiro.update.UpdateFailure
import ru.greemlab.neiro.update.UpdateInfo
import ru.greemlab.neiro.update.UpdateState
import ru.greemlab.neiro.update.UpdateViewModel
import ru.greemlab.neiro.update.isBusy
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «О программе» — здесь живёт кнопка «Проверить обновления». До этого экрана
 * обновление существует только в фоне и в уведомлении.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: UpdateViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoCheck by viewModel.autoCheckEnabled.collectAsStateWithLifecycle()
    val lastCheckAt by viewModel.lastCheckAt.collectAsStateWithLifecycle()
    val justUpdatedTo by viewModel.justUpdatedTo.collectAsStateWithLifecycle()
    val needsInstallPermission by viewModel.needsInstallPermission.collectAsStateWithLifecycle()

    // Разрешение выдаётся в системных настройках, то есть за пределами
    // приложения: единственный момент, когда его стоит перечитать, — возврат
    // сюда. В теле композиции это был binder-вызов на каждый кадр прогресса.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstallPermission()
    }
    val context = LocalContext.current

    fun openUrl(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Устройство без браузера — редкость, но падать из-за неё экран не должен.
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            justUpdatedTo?.let { version ->
                SettingsGroupCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.about_just_updated, version),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = viewModel::dismissJustUpdated) {
                            Text(stringResource(R.string.about_dismiss_error))
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.about_section_version)) {
                SettingsGroupCard {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "Neiro ${viewModel.versionName}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.about_build, viewModel.versionCode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    )
                }
            }

            when (val current = state) {
                is UpdateState.Blocked -> BlockedCard(current.why)

                else -> UpdateCard(
                    state = current,
                    autoCheck = autoCheck,
                    lastCheckAt = lastCheckAt,
                    onCheck = { viewModel.check() },
                    onAutoCheckChange = viewModel::setAutoCheckEnabled,
                    onDownload = { info -> viewModel.downloadAndInstall(info) },
                    onInstall = { info, apk -> viewModel.install(info, apk) },
                    onSkip = { info -> viewModel.skip(info) },
                    onDismissFailure = viewModel::dismissFailure,
                    onOpenRelease = { info -> openUrl(info.releaseUrl) },
                    onOpenInstallSettings = {
                        try {
                            context.startActivity(viewModel.installPermissionIntent())
                        } catch (_: ActivityNotFoundException) {
                            Unit
                        }
                    },
                    needsInstallPermission = needsInstallPermission,
                )
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    autoCheck: Boolean,
    lastCheckAt: Long,
    onCheck: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (UpdateInfo, File) -> Unit,
    onSkip: (UpdateInfo) -> Unit,
    onDismissFailure: () -> Unit,
    onOpenRelease: (UpdateInfo) -> Unit,
    onOpenInstallSettings: () -> Unit,
    needsInstallPermission: Boolean,
) {
    SettingsSection(title = stringResource(R.string.about_section_updates)) {
        SettingsGroupCard {
            SettingsNavigationRow(
                title = stringResource(R.string.about_check_updates),
                subtitle = lastCheckSubtitle(state, lastCheckAt),
                icon = Icons.Rounded.Refresh,
                enabled = !state.isBusy,
                onClick = onCheck,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.about_auto_check),
                subtitle = stringResource(R.string.about_auto_check_hint),
                checked = autoCheck,
                onCheckedChange = onAutoCheckChange,
                showDivider = true,
            )
        }

        when (state) {
            is UpdateState.Available -> AvailableBlock(
                info = state.info,
                onDownload = onDownload,
                onSkip = onSkip,
                onOpenRelease = onOpenRelease,
                needsInstallPermission = needsInstallPermission,
                onOpenInstallSettings = onOpenInstallSettings,
            )

            is UpdateState.Downloading -> ProgressBlock(
                text = stringResource(R.string.about_downloading, state.percent),
                percent = state.percent,
            )

            is UpdateState.Verifying -> ProgressBlock(
                text = stringResource(R.string.about_verifying),
                percent = null,
            )

            // Скачали, но не поставили — обычно потому, что закрыли системный
            // диалог. Качать заново незачем: файл на месте и уже проверен.
            is UpdateState.ReadyToInstall -> ReadyToInstallBlock(
                versionName = state.info.version.versionName,
                needsInstallPermission = needsInstallPermission,
                onInstall = { onInstall(state.info, state.apk) },
                onOpenInstallSettings = onOpenInstallSettings,
            )

            is UpdateState.Installing -> ProgressBlock(
                text = stringResource(R.string.about_installing),
                percent = null,
            )

            is UpdateState.AwaitingConfirmation -> HintCard(
                text = stringResource(R.string.about_awaiting_confirmation),
            )

            is UpdateState.Failed -> FailureBlock(
                failure = state.reason,
                onDismiss = onDismissFailure,
            )

            is UpdateState.UpToDate -> HintCard(text = stringResource(R.string.about_up_to_date))

            else -> Unit
        }
    }
}

@Composable
private fun AvailableBlock(
    info: UpdateInfo,
    onDownload: (UpdateInfo) -> Unit,
    onSkip: (UpdateInfo) -> Unit,
    onOpenRelease: (UpdateInfo) -> Unit,
    needsInstallPermission: Boolean,
    onOpenInstallSettings: () -> Unit,
) {
    SettingsGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.about_available, info.version.versionName),
                style = MaterialTheme.typography.titleMedium,
            )
            if (info.notes.isNotBlank()) {
                Text(
                    text = stringResource(R.string.about_whats_new),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // Markdown из GitHub рендерить нечем и не нужно: показываем
                    // как есть, а за полным текстом — ссылка на релиз.
                    text = info.notes,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = NOTES_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (needsInstallPermission) {
                Text(
                    text = stringResource(R.string.about_install_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenInstallSettings,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Text(stringResource(R.string.about_install_permission_action))
                }
            }

            AdaptiveActionRow {
                Button(
                    onClick = { onDownload(info) },
                    contentPadding = ACTION_BUTTON_PADDING,
                ) {
                    Icon(
                        Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.about_update),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = { onSkip(info) },
                    contentPadding = ACTION_BUTTON_PADDING,
                ) {
                    Text(text = stringResource(R.string.about_skip), maxLines = 1)
                }
            }

            TextButton(onClick = { onOpenRelease(info) }) {
                Text(stringResource(R.string.about_open_github))
            }
        }
    }
}

/**
 * Кнопки действия: пока обе подписи помещаются в строку — стоят рядом и
 * одинаковой ширины, не помещаются (крупный системный шрифт, узкий экран) —
 * встают друг под другом во всю ширину.
 *
 * `weight(1f)` так не умеет: он делит строку пополам независимо от того, влезает
 * подпись или нет, и текст с иконкой выезжает за границу кнопки.
 *
 * Свой `Layout` вместо `FlowRow` — по той же причине, что и `ChipFlowRow` в
 * `ProfileYearStatsSection`: у `FlowRow` менялась сигнатура между версиями
 * Compose, а `Layout` стабилен.
 */
@Composable
private fun AdaptiveActionRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}

        val gap = spacing.roundToPx()
        val gaps = gap * (measurables.size - 1)
        // Рядом кнопки одинаковой ширины, значит решает самая «широкая» подпись:
        // если ей не хватит своей доли строки — в строку не встаёт никто.
        val widest = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val available = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            widest * measurables.size + gaps
        }
        val sideBySide = widest * measurables.size + gaps <= available
        val childWidth = if (sideBySide) (available - gaps) / measurables.size else available

        val placeables = measurables.map { measurable ->
            measurable.measure(
                Constraints(
                    minWidth = childWidth,
                    maxWidth = childWidth,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                ),
            )
        }
        val height = if (sideBySide) {
            placeables.maxOf { it.height }
        } else {
            placeables.sumOf { it.height } + gaps
        }

        layout(available, height) {
            var offset = 0
            placeables.forEach { placeable ->
                if (sideBySide) {
                    placeable.placeRelative(offset, 0)
                    offset += placeable.width + gap
                } else {
                    placeable.placeRelative(0, offset)
                    offset += placeable.height + gap
                }
            }
        }
    }
}

@Composable
private fun ReadyToInstallBlock(
    versionName: String,
    needsInstallPermission: Boolean,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    SettingsGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.about_ready_to_install, versionName),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (needsInstallPermission) {
                Text(
                    text = stringResource(R.string.about_install_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenInstallSettings,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Text(stringResource(R.string.about_install_permission_action))
                }
            }
            Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.about_install_now))
            }
        }
    }
}

@Composable
private fun ProgressBlock(text: String, percent: Int?) {
    SettingsGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            if (percent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FailureBlock(failure: UpdateFailure, onDismiss: () -> Unit) {
    SettingsGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(failureTextRes(failure)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.about_dismiss_error))
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    SettingsGroupCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
private fun BlockedCard(why: UpdateBlockReason) {
    val text = when (why) {
        UpdateBlockReason.NotReleaseBuild -> stringResource(R.string.about_blocked_debug)
        UpdateBlockReason.InstalledFromRuStore -> stringResource(R.string.about_blocked_rustore)
        UpdateBlockReason.InstalledFromPlay -> stringResource(R.string.about_blocked_play)
    }
    SettingsSection(title = stringResource(R.string.about_section_updates)) {
        HintCard(text = text)
    }
}

@Composable
private fun lastCheckSubtitle(state: UpdateState, lastCheckAt: Long): String = when {
    state is UpdateState.Checking -> stringResource(R.string.about_checking)
    lastCheckAt <= 0L -> stringResource(R.string.about_never_checked)
    else -> stringResource(R.string.about_last_check, formatCheckedAt(lastCheckAt))
}

private fun formatCheckedAt(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val time = moment.toLocalTime().format(TIME_FORMAT)
    return if (moment.toLocalDate() == LocalDate.now(zone)) {
        "сегодня в $time"
    } else {
        "${moment.toLocalDate().format(DATE_FORMAT)} в $time"
    }
}

private fun failureTextRes(failure: UpdateFailure): Int = when (failure) {
    UpdateFailure.NoNetwork -> R.string.about_error_no_network
    UpdateFailure.RateLimited -> R.string.about_error_rate_limited
    UpdateFailure.NoRelease -> R.string.about_error_no_release
    UpdateFailure.MalformedRelease -> R.string.about_error_malformed
    UpdateFailure.DownloadFailed -> R.string.about_error_download
    UpdateFailure.ChecksumMismatch -> R.string.about_error_checksum
    UpdateFailure.SignatureMismatch -> R.string.about_error_signature
    UpdateFailure.NoSpace -> R.string.about_error_no_space
    UpdateFailure.InstallRejected -> R.string.about_error_install_rejected
    UpdateFailure.InstallFailed -> R.string.about_error_install_failed
}

private const val NOTES_MAX_LINES = 8

/**
 * Уже поджатые поля кнопки: стандартные 24.dp по горизонтали при крупном шрифте
 * съедают место, которое нужно самой подписи.
 */
private val ACTION_BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
