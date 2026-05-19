package ru.greemlab.neiro.data

import android.content.Context
import android.content.SharedPreferences
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

private const val DAY_DATA_KEY = "day_data_json"
private const val PROFILE_KEY = "user_profile_json"
private const val THEME_KEY = "app_theme"
private const val EMPTY_OBJECT = "{}"
private const val SYNC_CACHE_NAME = "neiro_sync_cache"

const val THEME_SYSTEM = "system"
const val THEME_LIGHT = "light"
const val THEME_DARK = "dark"

data class StoreSnapshot(
    val profile: UserProfile,
    val dayData: Map<LocalDate, List<String>>,
    val theme: String,
) {
    companion object {
        val Empty = StoreSnapshot(UserProfile(), emptyMap(), THEME_SYSTEM)
    }
}

/**
 * Управление постоянным хранением данных календаря и профиля.
 *
 * Архитектура двухслойная:
 *  1. Авторитативный источник — Jetpack DataStore (асинхронно, переживает миграции схемы).
 *  2. Синхронный зеркальный кэш на SharedPreferences — нужен только для того, чтобы
 *     первый кадр UI стартовал с уже готовыми данными без блокировки main-потока.
 *
 * После любой записи в DataStore мы дублируем JSON в [syncCache], чтобы при следующем
 * холодном старте UI получил данные мгновенно через [peekSnapshot].
 */
class CalendarDataStore(private val context: Context) {

    private val gson: Gson = UserProfileJson.gson
    private val dataKey = stringPreferencesKey(DAY_DATA_KEY)
    private val profileKey = stringPreferencesKey(PROFILE_KEY)
    private val themeKey = stringPreferencesKey(THEME_KEY)

    private val syncCache: SharedPreferences =
        context.getSharedPreferences(SYNC_CACHE_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cached: StoreSnapshot = loadFromSyncCache()

    private val dayDataJsonType = object : TypeToken<Map<String, List<String>>>() {}.type
    private val backupJsonType = object : TypeToken<Map<String, String>>() {}.type

    fun peekSnapshot(): StoreSnapshot = cached

    /** Гидратация из DataStore — выполняется в фоне, обновляет cached и sync-зеркало. */
    suspend fun warmUp() {
        val prefs = context.dataStore.data.first()
        val snapshot = parseSnapshot(prefs)
        cached = snapshot
        writeSyncCache(
            dayJson = prefs[dataKey],
            profileJson = prefs[profileKey],
            themeValue = prefs[themeKey],
        )
    }

    private val snapshots: Flow<StoreSnapshot> = context.dataStore.data
        .map { prefs ->
            val snapshot = parseSnapshot(prefs)
            cached = snapshot
            writeSyncCache(
                dayJson = prefs[dataKey],
                profileJson = prefs[profileKey],
                themeValue = prefs[themeKey],
            )
            snapshot
        }
        .onStart { emit(cached) }
        .distinctUntilChanged()

    val themeFlow: Flow<String> = snapshots
        .map { it.theme }
        .distinctUntilChanged()

    val dayDataFlow: Flow<Map<LocalDate, List<String>>> = snapshots
        .map { it.dayData }
        .distinctUntilChanged()

    val userProfileFlow: Flow<UserProfile> = snapshots
        .map { it.profile }
        .distinctUntilChanged()

    private fun parseSnapshot(prefs: Preferences): StoreSnapshot = StoreSnapshot(
        profile = UserProfileJson.fromJson(prefs[profileKey]),
        dayData = parseDayData(prefs[dataKey]),
        theme = prefs[themeKey] ?: THEME_SYSTEM,
    )

    private fun parseDayData(json: String?): Map<LocalDate, List<String>> {
        if (json.isNullOrEmpty() || json == EMPTY_OBJECT) return emptyMap()
        return runCatching {
            val rawMap: Map<String, List<String>> =
                gson.fromJson(json, dayDataJsonType) ?: return emptyMap()
            buildMap(rawMap.size) {
                for ((key, value) in rawMap) {
                    val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                    put(date, value)
                }
            }
        }.getOrDefault(emptyMap())
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
        val serialized = serializeDayData(data)
        context.dataStore.edit { prefs -> prefs[dataKey] = serialized }
        syncCache.edit().putString(DAY_DATA_KEY, serialized).apply()
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        val json = UserProfileJson.toJson(profile)
        context.dataStore.edit { prefs -> prefs[profileKey] = json }
        syncCache.edit().putString(PROFILE_KEY, json).apply()
    }

    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { prefs -> prefs[themeKey] = theme }
        syncCache.edit().putString(THEME_KEY, theme).apply()
    }

    suspend fun getAllDataJson(): String {
        val prefs = context.dataStore.data.first()
        val data = mapOf(
            "day_data" to (prefs[dataKey] ?: EMPTY_OBJECT),
            "user_profile" to (prefs[profileKey] ?: EMPTY_OBJECT),
            "app_theme" to (prefs[themeKey] ?: THEME_SYSTEM),
        )
        return gson.toJson(data)
    }

    suspend fun restoreAllDataFromJson(json: String): Boolean = runCatching {
        val fullData: Map<String, String> = gson.fromJson(json, backupJsonType)
            ?: return@runCatching false
        context.dataStore.edit { prefs ->
            fullData["day_data"]?.let { prefs[dataKey] = it }
            fullData["user_profile"]?.let { prefs[profileKey] = it }
            fullData["app_theme"]?.let { prefs[themeKey] = it }
        }
        writeSyncCache(
            dayJson = fullData["day_data"],
            profileJson = fullData["user_profile"],
            themeValue = fullData["app_theme"],
        )
        true
    }.getOrDefault(false)

    private fun serializeDayData(data: Map<LocalDate, List<String>>): String {
        if (data.isEmpty()) return EMPTY_OBJECT
        val stringMap = LinkedHashMap<String, List<String>>(data.size)
        for ((date, value) in data) stringMap[date.toString()] = value
        return gson.toJson(stringMap)
    }

    private fun loadFromSyncCache(): StoreSnapshot {
        val profileJson = syncCache.getString(PROFILE_KEY, null)
        val dayJson = syncCache.getString(DAY_DATA_KEY, null)
        val theme = syncCache.getString(THEME_KEY, null) ?: THEME_SYSTEM
        return StoreSnapshot(
            profile = UserProfileJson.fromJson(profileJson),
            dayData = parseDayData(dayJson),
            theme = theme,
        )
    }

    private fun writeSyncCache(dayJson: String?, profileJson: String?, themeValue: String?) {
        // Не пишем туда же, что и так лежит — экономим IO.
        val editor = syncCache.edit()
        var changed = false
        if (dayJson != null && syncCache.getString(DAY_DATA_KEY, null) != dayJson) {
            editor.putString(DAY_DATA_KEY, dayJson); changed = true
        }
        if (profileJson != null && syncCache.getString(PROFILE_KEY, null) != profileJson) {
            editor.putString(PROFILE_KEY, profileJson); changed = true
        }
        if (themeValue != null && syncCache.getString(THEME_KEY, null) != themeValue) {
            editor.putString(THEME_KEY, themeValue); changed = true
        }
        if (changed) editor.apply()
    }
}
