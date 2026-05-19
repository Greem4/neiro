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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "calendar_data")

data class StoreSnapshot(
    val profile: UserProfile,
    val dayData: Map<LocalDate, List<String>>,
    val theme: String,
) {
    companion object {
        val Empty = StoreSnapshot(UserProfile(), emptyMap(), "system")
    }
}

/**
 * Класс для управления постоянным хранением данных календаря и профиля.
 * Использует Jetpack DataStore и GSON для сериализации.
 */
class CalendarDataStore(private val context: Context) {
    private val gson = Gson()
    private val dataKey = stringPreferencesKey("day_data_json")
    private val profileKey = stringPreferencesKey("user_profile_json")
    private val themeKey = stringPreferencesKey("app_theme")

    @Volatile
    private var cached: StoreSnapshot = StoreSnapshot.Empty

    private val dayDataJsonType = object : TypeToken<Map<String, List<String>>>() {}.type

    fun peekSnapshot(): StoreSnapshot = cached

    /** Прогрев кэша до показа UI — один проход чтения и парсинга. */
    suspend fun warmUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            cached = parseSnapshot(context.dataStore.data.first())
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun snapshots(): Flow<StoreSnapshot> = context.dataStore.data
        .map { prefs ->
            parseSnapshot(prefs).also { cached = it }
        }
        .onStart { emit(cached) }

    @RequiresApi(Build.VERSION_CODES.O)
    val themeFlow: Flow<String> = snapshots()
        .map { it.theme }
        .distinctUntilChanged()

    @RequiresApi(Build.VERSION_CODES.O)
    val dayDataFlow: Flow<Map<LocalDate, List<String>>> = snapshots()
        .map { it.dayData }
        .distinctUntilChanged()

    @RequiresApi(Build.VERSION_CODES.O)
    val userProfileFlow: Flow<UserProfile> = snapshots()
        .map { it.profile }
        .distinctUntilChanged()

    @RequiresApi(Build.VERSION_CODES.O)
    private fun parseSnapshot(prefs: Preferences): StoreSnapshot = StoreSnapshot(
        profile = UserProfileJson.fromJson(prefs[profileKey]),
        dayData = parseDayData(prefs[dataKey]),
        theme = prefs[themeKey] ?: "system",
    )

    @RequiresApi(Build.VERSION_CODES.O)
    private fun parseDayData(json: String?): Map<LocalDate, List<String>> {
        val raw = json ?: "{}"
        return try {
            val rawMap: Map<String, List<String>> = gson.fromJson(raw, dayDataJsonType) ?: emptyMap()
            rawMap.mapKeys { LocalDate.parse(it.key) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

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

    suspend fun saveDayData(data: Map<LocalDate, List<String>>) {
        context.dataStore.edit { preferences ->
            val stringMap = data.mapKeys { it.key.toString() }
            preferences[dataKey] = gson.toJson(stringMap)
        }
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        context.dataStore.edit { preferences ->
            preferences[profileKey] = UserProfileJson.toJson(profile)
        }
    }

    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { it[themeKey] = theme }
    }

    suspend fun getAllDataJson(): String {
        val prefs = context.dataStore.data.first()
        val data = mapOf(
            "day_data" to (prefs[dataKey] ?: "{}"),
            "user_profile" to (prefs[profileKey] ?: "{}"),
            "app_theme" to (prefs[themeKey] ?: "system"),
        )
        return gson.toJson(data)
    }

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
