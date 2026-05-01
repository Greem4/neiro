package ru.greemlab.neiro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

// Делегат для создания DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "calendar_data")

/**
 * Класс для управления постоянным хранением данных календаря.
 * Использует Jetpack DataStore и GSON для сериализации.
 */
class CalendarDataStore(private val context: Context) {
    private val gson = Gson()
    private val DATA_KEY = stringPreferencesKey("day_data_json")

    /**
     * Поток данных календаря.
     * Возвращает карту: Дата -> Список имен.
     */
    val dayDataFlow: Flow<Map<LocalDate, List<String>>> = context.dataStore.data
        .map { preferences ->
            val json = preferences[DATA_KEY] ?: "{}"
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val rawMap: Map<String, List<String>> = gson.fromJson(json, type)
            
            // Конвертируем String ключи обратно в LocalDate
            rawMap.mapKeys { LocalDate.parse(it.key) }
        }

    /**
     * Сохранить обновленные данные в DataStore.
     */
    suspend fun saveDayData(data: Map<LocalDate, List<String>>) {
        context.dataStore.edit { preferences ->
            // Конвертируем LocalDate в String для JSON
            val stringMap = data.mapKeys { it.key.toString() }
            val json = gson.toJson(stringMap)
            preferences[DATA_KEY] = json
        }
    }
}
