package ru.greemlab.neiro.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.R
import ru.greemlab.neiro.data.ImportResult
import ru.greemlab.neiro.data.archiveExportSuggestedFileName
import ru.greemlab.neiro.data.THEME_DARK
import ru.greemlab.neiro.data.THEME_LIGHT
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.data.THEME_SYSTEM
import ru.greemlab.neiro.update.UpdateNotifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    onOpenNotificationSettings: () -> Unit = {},
    onOpenProfitSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    viewModel: AppSettingsViewModel = viewModel(),
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val glassEnabled by viewModel.glassEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Известная из прошлой проверки версия — только чтобы подписать пункт
    // «О программе». В сеть отсюда не ходим.
    val newerVersion = remember { UpdateNotifier.knownNewerVersion(context) }
    val autoSyncEnabled by viewModel.autoSyncEnabled.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.sessionNotificationsEnabled.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportData(context, uri) { result ->
            val text = when (result) {
                is ExportResult.Success -> "Архив экспортирован"
                is ExportResult.Failure -> "Ошибка экспорта архива: ${result.reason}"
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.importData(context, uri) { result ->
            val text = when (result) {
                is ImportResult.Success -> "Архив импортирован"
                is ImportResult.Failure -> "Ошибка импорта архива: ${result.reason}"
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки приложения") },
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SettingsSection(title = "Внешний вид") {
                SettingsGroupCard {
                    SettingsRadioRow(
                        title = "Системная",
                        selected = theme == THEME_SYSTEM,
                        onClick = { viewModel.setTheme(THEME_SYSTEM) },
                        icon = Icons.Rounded.SettingsSuggest,
                    )
                    SettingsRadioRow(
                        title = "Светлая",
                        selected = theme == THEME_LIGHT,
                        onClick = { viewModel.setTheme(THEME_LIGHT) },
                        icon = Icons.Rounded.LightMode,
                        showDivider = true,
                    )
                    SettingsRadioRow(
                        title = "Тёмная",
                        selected = theme == THEME_DARK,
                        onClick = { viewModel.setTheme(THEME_DARK) },
                        icon = Icons.Rounded.DarkMode,
                        showDivider = true,
                    )
                    SettingsSwitchRow(
                        title = "Стеклянный вид",
                        subtitle = "Диалоги становятся полупрозрачными, календарь за ними размывается",
                        icon = Icons.Rounded.BlurOn,
                        checked = glassEnabled,
                        onCheckedChange = viewModel::setGlassEnabled,
                        showDivider = true,
                    )
                }
            }

            SettingsSection(title = "Занятия") {
                SettingsGroupCard {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_notifications_title),
                        subtitle = stringResource(R.string.settings_notifications_subtitle),
                        icon = Icons.Rounded.Notifications,
                        checked = notificationsEnabled,
                        onCheckedChange = viewModel::setSessionNotificationsEnabled,
                    )
                    SettingsNavigationRow(
                        title = stringResource(R.string.notification_settings_configure),
                        onClick = onOpenNotificationSettings,
                        enabled = notificationsEnabled,
                        showDivider = true,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_profit_section)) {
                SettingsGroupCard {
                    SettingsNavigationRow(
                        title = stringResource(R.string.settings_profit_configure),
                        subtitle = stringResource(R.string.settings_profit_screen_hint),
                        icon = Icons.Rounded.Payments,
                        onClick = onOpenProfitSettings,
                    )
                }
            }

            SettingsSection(title = "YClients") {
                SettingsGroupCard {
                    SettingsSwitchRow(
                        title = "Автосинхронизация",
                        subtitle = "Полная синхронизация при открытии, если прошло больше суток",
                        icon = Icons.Rounded.Sync,
                        checked = autoSyncEnabled,
                        onCheckedChange = viewModel::setAutoSyncEnabled,
                    )
                }
            }

            SettingsSection(title = "О программе") {
                SettingsGroupCard {
                    SettingsNavigationRow(
                        title = stringResource(R.string.settings_about_row),
                        subtitle = if (newerVersion != null) {
                            stringResource(
                                R.string.settings_about_row_update_available,
                                newerVersion.versionName,
                            )
                        } else {
                            stringResource(R.string.settings_about_row_subtitle, BuildConfig.VERSION_NAME)
                        },
                        icon = Icons.Rounded.Info,
                        onClick = onOpenAbout,
                    )
                }
            }

            SettingsSection(title = "Архив") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch(archiveExportSuggestedFileName()) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        Icon(Icons.Rounded.Storage, contentDescription = null)
                        Text("Экспорт архива", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Icon(Icons.Rounded.Storage, contentDescription = null)
                        Text("Импорт архива", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
