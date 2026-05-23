package ru.greemlab.neiro.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import ru.greemlab.neiro.data.ImportResult
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.notifications.SessionNotificationPreferences
import ru.greemlab.neiro.sync.AutoSyncCoordinator
import ru.greemlab.neiro.sync.SyncPreferences
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CalendarRepository = CalendarDataStoreProvider.get(application)
    private val syncPreferences = SyncPreferences.get(application)
    private val notificationPreferences = SessionNotificationPreferences.get(application)
    private val profitDisplayPreferences = ProfitDisplayPreferences.get(application)

    val theme: StateFlow<String> = repository.themeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CalendarDataStoreProvider.peekTheme(application),
        )

    fun setTheme(theme: String) {
        viewModelScope.launch { repository.saveTheme(theme) }
    }

    fun isAutoSyncEnabled(): Boolean = syncPreferences.isAutoSyncEnabled

    fun setAutoSyncEnabled(enabled: Boolean) {
        syncPreferences.isAutoSyncEnabled = enabled
        AutoSyncCoordinator.onAutoSyncToggled(getApplication(), enabled)
    }

    fun isSessionNotificationsEnabled(): Boolean = notificationPreferences.isEnabled

    fun setSessionNotificationsEnabled(enabled: Boolean) {
        notificationPreferences.isEnabled = enabled
        viewModelScope.launch {
            SessionNotificationCoordinator.onNotificationsToggled(getApplication(), enabled)
        }
    }

    fun profitDisplaySettings(): ProfitDisplaySettings = profitDisplayPreferences.read()

    fun setProfitDisplaySettings(settings: ProfitDisplaySettings) {
        profitDisplayPreferences.save(settings)
    }

    /**
     * Экспорт всех данных в указанный URI. Возвращает [ExportResult].
     * Чтение и запись файла выполняются на IO-диспатчере, чтобы не блокировать main.
     */
    fun exportData(context: Context, uri: Uri, onResult: (ExportResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val data = repository.exportAllData()
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer -> writer.write(data) }
                    } ?: throw IOException("Не удалось открыть файл для записи")
                    ExportResult.Success
                }.getOrElse { ExportResult.Failure(it.message ?: "Неизвестная ошибка") }
            }
            onResult(result)
        }
    }

    /**
     * Импорт данных из указанного URI. Парсит файл в фоне и применяет атомарно
     * через [CalendarRepository.restoreAllData].
     */
    fun importData(context: Context, uri: Uri, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val content = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).use { it.readText() }
                    }
                }.getOrNull()
                if (content.isNullOrBlank()) {
                    ImportResult.Failure("Файл пуст или не читается")
                } else {
                    repository.restoreAllData(content)
                }
            }
            onResult(result)
        }
    }
}

sealed interface ExportResult {
    data object Success : ExportResult
    data class Failure(val reason: String) : ExportResult
}
