package ru.greemlab.neiro.data

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.LocalDate

// Делегат для создания DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "calendar_data")

/**
 * Класс для управления постоянным хранением данных календаря и профиля.
 * Использует Jetpack DataStore и GSON для сериализации.
 */
class CalendarDataStore(private val context: Context) {
    private val gson = Gson()
    private val DATA_KEY = stringPreferencesKey("day_data_json")
    private val PROFILE_KEY = stringPreferencesKey("user_profile_json")
    private val THEME_KEY = stringPreferencesKey("app_theme")

    /**
     * Поток темы приложения.
     * "system", "light", "dark"
     */
    val themeFlow: Flow<String> = context.dataStore.data
        .map { it[THEME_KEY] ?: "system" }

    /**
     * Поток данных календаря.
     * Возвращает карту: Дата -> Список имен.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    val dayDataFlow: Flow<Map<LocalDate, List<String>>> = context.dataStore.data
        .map { preferences ->
            val json = preferences[DATA_KEY] ?: "{}"
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            val rawMap: Map<String, List<String>> = gson.fromJson(json, type)
            
            // Конвертируем String ключи обратно в LocalDate
            rawMap.mapKeys { LocalDate.parse(it.key) }
        }

    /**
     * Поток данных профиля пользователя.
     */
    val userProfileFlow: Flow<UserProfile> = context.dataStore.data
        .map { preferences ->
            val json = preferences[PROFILE_KEY] ?: return@map UserProfile()
            gson.fromJson(json, UserProfile::class.java)
        }

    /**
     * Сохранить обновленные данные календаря.
     */
    suspend fun saveDayData(data: Map<LocalDate, List<String>>) {
        context.dataStore.edit { preferences ->
            val stringMap = data.mapKeys { it.key.toString() }
            val json = gson.toJson(stringMap)
            preferences[DATA_KEY] = json
        }
    }

    /**
     * Сохранить профиль пользователя.
     */
    suspend fun saveUserProfile(profile: UserProfile) {
        context.dataStore.edit { preferences ->
            val json = gson.toJson(profile)
            preferences[PROFILE_KEY] = json
        }
    }

    /**
     * Сохранить тему приложения.
     */
    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { it[THEME_KEY] = theme }
    }

    /**
     * Получить все данные в виде JSON для экспорта.
     */
    suspend fun getAllDataJson(): String {
        val data = context.dataStore.data.map { prefs ->
            mapOf(
                "day_data" to (prefs[DATA_KEY] ?: "{}"),
                "user_profile" to (prefs[PROFILE_KEY] ?: "{}")
            )
        }
        return gson.toJson(data.first())
    }

    /**
     * Восстановить данные из JSON.
     */
    suspend fun restoreAllDataFromJson(json: String): Boolean {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val fullData: Map<String, String> = gson.fromJson(json, type)
            
            context.dataStore.edit { preferences ->
                fullData["day_data"]?.let { preferences[DATA_KEY] = it }
                fullData["user_profile"]?.let { preferences[PROFILE_KEY] = it }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
