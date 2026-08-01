package ru.greemlab.neiro.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.notifications.ArchiveNotificationStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// corruptionHandler: битый calendar_data.preferences_pb не роняет приложение —
// стартуем с пустых prefs, снимок восстановится из sync-кэша при следующем sync.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calendar_data",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private const val DAY_DATA_KEY = "day_data_json"
private const val SAVED_DAY_DATA_KEY = "saved_day_data_json"
private const val PROFILE_KEY = "user_profile_json"
private const val THEME_KEY = "app_theme"
private const val EMPTY_OBJECT = "{}"
private const val SYNC_CACHE_NAME = "neiro_sync_cache"

/** Ключ метки времени в JSON-файле экспорта архива. */
const val ARCHIVE_EXPORTED_AT_KEY = "exported_at"

private val archiveExportDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

private val archiveExportFileNameFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm")

/** Имя файла по умолчанию при экспорте архива (с датой и временем). */
fun archiveExportSuggestedFileName(): String {
    val stamp = LocalDateTime.now().format(archiveExportFileNameFormatter)
    return "neiro_archive_$stamp.json"
}

const val THEME_SYSTEM = "system"
const val THEME_LIGHT = "light"
const val THEME_DARK = "dark"

/**
 * Снимок всего состояния приложения. Передаётся в peek-API,
 * чтобы первый кадр UI стартовал с готовыми данными.
 */
data class StoreSnapshot(
    val profile: UserProfile,
    val dayData: Map<LocalDate, List<String>>,
    val savedDayData: Map<LocalDate, List<String>>,
    val theme: String,
) {
    companion object {
        val Empty = StoreSnapshot(UserProfile(), emptyMap(), emptyMap(), THEME_SYSTEM)
    }
}

/**
 * Реализация [CalendarRepository] поверх Jetpack DataStore.
 *
 * Архитектура хранения двухслойная:
 *  1. Авторитативный источник — DataStore (асинхронный, переживает миграции).
 *  2. Синхронное зеркало в [SharedPreferences] — заполняет [StoreSnapshot]
 *     прямо в конструкторе, чтобы первый кадр UI был с реальными данными.
 *
 * Все write-операции сериализуются через [writeMutex] — это исключает гонки
 * параллельных корутин (например, быстрый ввод в полях профиля).
 */
class CalendarDataStore(context: Context) : CalendarRepository {

    private val appContext: Context = context.applicationContext
    private val gson: Gson = UserProfileJson.gson

    private val dataKey = stringPreferencesKey(DAY_DATA_KEY)
    private val savedDataKey = stringPreferencesKey(SAVED_DAY_DATA_KEY)
    private val profileKey = stringPreferencesKey(PROFILE_KEY)
    private val themeKey = stringPreferencesKey(THEME_KEY)

    private val syncCache: SharedPreferences =
        appContext.getSharedPreferences(SYNC_CACHE_NAME, Context.MODE_PRIVATE)

    private val cachedState: MutableStateFlow<StoreSnapshot> =
        MutableStateFlow(loadFromSyncCache())

    /** Все записи в DataStore идут только под этим mutex — параллельные апдейты не теряются. */
    private val writeMutex = Mutex()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dayDataJsonType = object : TypeToken<Map<String, List<String>>>() {}.type
    private val backupJsonType = object : TypeToken<Map<String, String>>() {}.type

    override fun peekSnapshot(): StoreSnapshot = cachedState.value

    override suspend fun warmUp() {
        // Под writer-локом: гидратация не гоняется с параллельной записью,
        // которая могла бы быть перезатёрта устаревшим snapshot'ом.
        writeMutex.withLock {
            val prefs = appContext.dataStore.data.first()
            val snapshot = parseSnapshot(prefs)
            cachedState.value = snapshot
            writeSyncCache(
                dayJson = prefs[dataKey],
                profileJson = prefs[profileKey],
                themeValue = prefs[themeKey],
            )
        }
    }

    private val snapshotsFlow: Flow<StoreSnapshot> = appContext.dataStore.data
        .map { prefs ->
            val snapshot = parseSnapshot(prefs)
            cachedState.value = snapshot
            writeSyncCache(
                dayJson = prefs[dataKey],
                profileJson = prefs[profileKey],
                themeValue = prefs[themeKey],
            )
            snapshot
        }
        // Gson-парс всего календаря и запись sync-кэша — не в контексте коллектора
        // (обычно viewModelScope/Main), иначе каждый sync дёргает UI-поток.
        .flowOn(Dispatchers.Default)
        .onStart { emit(cachedState.value) }
        .distinctUntilChanged()
        // shareIn: без этого каждый из 4 публичных Flow ниже — независимая подписка
        // на DataStore, т.е. N коллекторов = N Gson-парсов всего календаря и N
        // записей sync-кэша на каждую эмиссию (D3).
        .shareIn(appScope, SharingStarted.Eagerly, replay = 1)

    override val themeFlow: Flow<String> = snapshotsFlow
        .map { it.theme }
        .distinctUntilChanged()

    override val dayDataFlow: Flow<Map<LocalDate, List<String>>> = snapshotsFlow
        .map { it.dayData }
        .distinctUntilChanged()

    override val savedDayDataFlow: Flow<Map<LocalDate, List<String>>> = snapshotsFlow
        .map { it.savedDayData }
        .distinctUntilChanged()

    override val userProfileFlow: Flow<UserProfile> = snapshotsFlow
        .map { it.profile }
        .distinctUntilChanged()

    private fun parseSnapshot(prefs: Preferences): StoreSnapshot = StoreSnapshot(
        profile = UserProfileJson.fromJson(prefs[profileKey]),
        dayData = parseDayData(prefs[dataKey]),
        savedDayData = parseDayData(prefs[savedDataKey]),
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

    override suspend fun migrateProfileIfNeeded() {
        writeMutex.withLock {
            appContext.dataStore.edit { prefs ->
                val json = prefs[profileKey] ?: return@edit
                val raw = UserProfileJson.fromJsonRaw(json)
                val normalized = raw.normalizeLegacy()
                if (normalized != raw) {
                    prefs[profileKey] = UserProfileJson.toJson(normalized)
                }
            }
        }
    }

    override suspend fun updateProfile(transform: (UserProfile) -> UserProfile) {
        writeMutex.withLock {
            var updated: UserProfile? = null
            var json: String? = null
            // Читаем актуальное значение внутри edit, а не из cachedState:
            // RMW не зависит от того, успел ли завершиться warmUp.
            appContext.dataStore.edit { prefs ->
                val current = UserProfileJson.fromJson(prefs[profileKey])
                val next = transform(current)
                if (next == current) return@edit
                val serialized = UserProfileJson.toJson(next)
                prefs[profileKey] = serialized
                updated = next
                json = serialized
            }
            val next = updated ?: return@withLock
            // cached обновляется коллектором Flow, но peek-API должен видеть свежее значение
            // немедленно, поэтому подменяем атомарно здесь.
            cachedState.value = cachedState.value.copy(profile = next)
            writeSyncCache(profileJson = json)
        }
    }

    override suspend fun updateDayData(
        transform: (Map<LocalDate, List<String>>) -> Map<LocalDate, List<String>>,
    ) {
        writeMutex.withLock {
            var updated: Map<LocalDate, List<String>>? = null
            var serialized: String? = null
            appContext.dataStore.edit { prefs ->
                val current = parseDayData(prefs[dataKey])
                val next = transform(current)
                if (next == current) return@edit
                val json = serializeDayData(next)
                prefs[dataKey] = json
                updated = next
                serialized = json
            }
            val next = updated ?: return@withLock
            cachedState.value = cachedState.value.copy(dayData = next)
            writeSyncCache(dayJson = serialized)
        }
    }

    override suspend fun saveDayToArchive(date: LocalDate, data: List<String>) {
        writeMutex.withLock {
            val current = cachedState.value.savedDayData.toMutableMap()
            current[date] = data
            val serialized = serializeDayData(current)
            appContext.dataStore.edit { prefs -> prefs[savedDataKey] = serialized }
            cachedState.value = cachedState.value.copy(savedDayData = current)
            // Мы не пишем в syncCache архивные данные, так как они локальные и "вечные"
        }
    }

    override suspend fun deleteDayFromArchive(date: LocalDate) {
        writeMutex.withLock {
            val current = cachedState.value.savedDayData.toMutableMap()
            if (current.remove(date) != null) {
                val serialized = serializeDayData(current)
                appContext.dataStore.edit { prefs -> prefs[savedDataKey] = serialized }
                cachedState.value = cachedState.value.copy(savedDayData = current)
            }
        }
    }

    override suspend fun clearAllData() {
        writeMutex.withLock {
            appContext.dataStore.edit { prefs ->
                prefs.clear()
            }
            cachedState.value = StoreSnapshot.Empty
            writeSyncCache(
                dayJson = EMPTY_OBJECT,
                profileJson = EMPTY_OBJECT,
                themeValue = THEME_SYSTEM
            )
            // Легаси-ключ старых версий: writeSyncCache его не поддерживает,
            // но на старых установках он мог там залежаться.
            syncCache.edit().remove(SAVED_DAY_DATA_KEY).apply()
        }
    }

    override suspend fun saveTheme(theme: String) {
        writeMutex.withLock {
            appContext.dataStore.edit { prefs -> prefs[themeKey] = theme }
            cachedState.value = cachedState.value.copy(theme = theme)
            writeSyncCache(themeValue = theme)
        }
    }

    override suspend fun exportAllData(): String = withContext(Dispatchers.IO) {
        // Под writeMutex — иначе возможен torn read: чтение DataStore mid-write
        // параллельной операции (см. D2/updateProfile — тот же RMW-принцип).
        val savedDayJson = writeMutex.withLock {
            appContext.dataStore.data.first()[savedDataKey] ?: EMPTY_OBJECT
        }
        val data = linkedMapOf(
            ARCHIVE_EXPORTED_AT_KEY to LocalDateTime.now().format(archiveExportDateTimeFormatter),
            "saved_day_data" to savedDayJson,
            ArchiveNotificationStore.EXPORT_KEY to ArchiveNotificationStore.get(appContext).exportJson(),
            // Ручные правки цен из API не восстановить — история обязана быть в бэкапе.
            SalaryLedgerStore.EXPORT_KEY to SalaryLedgerStore.get(appContext).exportJson(),
        )
        gson.toJson(data)
    }

    override suspend fun restoreAllData(json: String): ImportResult {
        val parsed: Map<String, String>? = runCatching {
            withContext(Dispatchers.Default) {
                gson.fromJson<Map<String, String>>(json, backupJsonType)
            }
        }.getOrElse { error ->
            return when (error) {
                is JsonSyntaxException -> ImportResult.Failure("Файл повреждён: неверный JSON")
                else -> ImportResult.Failure("Не удалось прочитать файл")
            }
        }
        if (parsed == null) return ImportResult.Failure("Файл пуст")

        val savedDayJson = parsed["saved_day_data"]
            ?: return ImportResult.Failure("В файле нет данных архива")

        val parsedSavedDayData = parseDayData(savedDayJson)

        // Сначала DataStore, потом notifications: если edit упадёт, уведомления
        // не окажутся импортированы поверх несостоявшегося импорта календаря.
        writeMutex.withLock {
            appContext.dataStore.edit { prefs ->
                prefs[savedDataKey] = savedDayJson
            }
            cachedState.value = cachedState.value.copy(savedDayData = parsedSavedDayData)
        }

        parsed[ArchiveNotificationStore.EXPORT_KEY]?.let { notificationsJson ->
            ArchiveNotificationStore.get(appContext).importJson(notificationsJson)
        }

        // Ключа нет — историю ЗП не трогаем (не очищаем): старый файл бэкапа
        // ничего про неё не знает, а ручные правки восстановить нечем.
        parsed[SalaryLedgerStore.EXPORT_KEY]?.let { ledgerJson ->
            SalaryLedgerStore.get(appContext).importJson(ledgerJson)
        }

        return ImportResult.Success
    }

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
        // savedDayData: writeSyncCache никогда не поддерживает этот ключ в
        // актуальном состоянии — не читаем его отсюда, иначе на установках,
        // где ключ когда-то был записан старой версией, после clearAllData
        // холодный старт кратко показывал бы устаревший архив-«зомби».
        // Настоящее значение приходит почти сразу через warmUp()/snapshotsFlow.
        return StoreSnapshot(
            profile = UserProfileJson.fromJson(profileJson),
            dayData = parseDayData(dayJson),
            savedDayData = emptyMap(),
            theme = theme,
        )
    }

    private fun writeSyncCache(
        dayJson: String? = null,
        profileJson: String? = null,
        themeValue: String? = null,
    ) {
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
