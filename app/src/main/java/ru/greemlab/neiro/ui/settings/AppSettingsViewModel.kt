package ru.greemlab.neiro.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import java.io.BufferedReader
import java.io.InputStreamReader

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = CalendarDataStoreProvider.get(application)

    val theme: StateFlow<String> = dataStore.themeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "system",
        )

    fun setTheme(theme: String) {
        viewModelScope.launch {
            dataStore.saveTheme(theme)
        }
    }

    suspend fun exportData(): String {
        return dataStore.getAllDataJson()
    }

    fun importData(context: Context, uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
                }
                if (content != null) {
                    val success = dataStore.restoreAllDataFromJson(content)
                    onResult(success)
                } else {
                    onResult(false)
                }
            } catch (_: Exception) {
                onResult(false)
            }
        }
    }
}
