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
    private val dataKey = stringPreferencesKey("day_data_json")
    private val profileKey = stringPreferencesKey("user_profile_json")
    private val themeKey = stringPreferencesKey("app_theme")

    /**
     * Поток темы приложения.
     * "system", "light", "dark"
     */
    val themeFlow: Flow<String> = context.dataStore.data
        .map { it[themeKey] ?: "system" }

    /**
     * Поток данных календаря.
     * Возвращает карту: Дата -> Список имен.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    val dayDataFlow: Flow<Map<LocalDate, List<String>>> = context.dataStore.data
        .map { preferences ->
            val json = preferences[dataKey] ?: "{}"
            try {
                val type = object : TypeToken<Map<String, List<String>>>() {}.type
                val rawMap: Map<String, List<String>> = gson.fromJson(json, type) ?: emptyMap()
                rawMap.mapKeys { LocalDate.parse(it.key) }
            } catch (_: Exception) {
                emptyMap()
            }
        }

    /**
     * Поток данных профиля пользователя.
     */
    val userProfileFlow: Flow<UserProfile> = context.dataStore.data
        .map { preferences ->
            UserProfileJson.fromJson(preferences[profileKey])
        }

    /**
     * Однократная миграция старых профилей (например, без флага isRegistered).
     */
    suspend fun migrateProfileIfNeeded() {
        context.dataStore.edit { prefs ->
            val json = prefs[profileKey] ?: return@edit
            val raw = UserProfileJson.fromJsonRaw(json)
            val normalized = raw.normalizeLegacy()
            if (normalized != raw) {
                prefs[profileKey] = UserProfileJson.toJson(normalized)
            }
        }
    }

    /**
     * Сохранить обновленные данные календаря.
     */
    suspend fun saveDayData(data: Map<LocalDate, List<String>>) {
        context.dataStore.edit { preferences ->
            val stringMap = data.mapKeys { it.key.toString() }
            val json = gson.toJson(stringMap)
            preferences[dataKey] = json
        }
    }

    /**
     * Сохранить профиль пользователя.
     */
    suspend fun saveUserProfile(profile: UserProfile) {
        context.dataStore.edit { preferences ->
            preferences[profileKey] = UserProfileJson.toJson(profile)
        }
    }

    /**
     * Сохранить тему приложения.
     */
    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { it[themeKey] = theme }
    }

    /**
     * Получить все данные в виде JSON для экспорта.
     */
    suspend fun getAllDataJson(): String {
        val data = context.dataStore.data.map { prefs ->
            mapOf(
                "day_data" to (prefs[dataKey] ?: "{}"),
                "user_profile" to (prefs[profileKey] ?: "{}"),
                "app_theme" to (prefs[themeKey] ?: "system"),
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
                fullData["day_data"]?.let { preferences[dataKey] = it }
                fullData["user_profile"]?.let { preferences[profileKey] = it }
                fullData["app_theme"]?.let { preferences[themeKey] = it }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
