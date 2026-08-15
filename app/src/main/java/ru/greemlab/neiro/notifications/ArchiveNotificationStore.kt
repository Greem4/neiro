package ru.greemlab.neiro.notifications

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.UUID

/**
 * Журнал уведомлений вкладки «Архив»: только накопление, без удаления из UI.
 */
class ArchiveNotificationStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<InAppNotification>>() {}.type

    private val _items = MutableStateFlow(loadItems())
    val items: StateFlow<List<InAppNotification>> = _items.asStateFlow()

    private fun loadItems(): List<InAppNotification> =
        runCatching {
            val json = prefs.getString(KEY_ITEMS, null) ?: return@runCatching emptyList()
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson<List<InAppNotification>>(json, listType) ?: emptyList()
            prune(parsed)
        }.getOrElse { emptyList() }

    private fun persist(items: List<InAppNotification>) {
        val pruned = prune(items)
        prefs.edit()
            .putString(KEY_ITEMS, gson.toJson(pruned))
            .apply()
        _items.value = pruned
    }

    @Synchronized
    fun append(
        title: String,
        body: String,
        relatedDate: LocalDate? = null,
        dedupeKey: String? = null,
        highlightSlotKey: String? = null,
        kind: SessionEventType? = null,
        timestampEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val current = _items.value
        if (dedupeKey != null && current.any { it.dedupeKey == dedupeKey }) return

        val entry = InAppNotification(
            id = UUID.randomUUID().toString(),
            title = title,
            body = body,
            timestampEpochMillis = timestampEpochMillis,
            relatedDateEpochDay = relatedDate?.toEpochDay(),
            dedupeKey = dedupeKey,
            highlightSlotKey = highlightSlotKey,
            kind = kind?.name,
            read = false,
        )
        persist(listOf(entry) + current)
    }

    @Synchronized
    fun markAllRead() {
        persist(_items.value.map { it.copy(read = true) })
    }

    /**
     * Стереть архив уведомлений целиком.
     *
     * Трогает только это устройство: уже сделанный экспорт архива живёт своим
     * файлом, [exportJson] его не переписывает.
     */
    @Synchronized
    fun clearAll() {
        persist(emptyList())
    }

    /** JSON-массив уведомлений для экспорта архива. */
    fun exportJson(): String = gson.toJson(_items.value)

    /**
     * Восстановление из бэкапа. При ошибке разбора список не меняется.
     *
     * @return `true`, если данные применены.
     */
    @Synchronized
    fun importJson(json: String): Boolean {
        if (json.isBlank()) return false
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson<List<InAppNotification>>(json, listType) ?: emptyList()
            persist(parsed)
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val MAX_ITEMS = 5_000
        private const val PREFS_NAME = "neiro_archive_notifications"
        private const val KEY_ITEMS = "items_json"

        /** Ключ в JSON-файле экспорта архива. */
        const val EXPORT_KEY = "archive_notifications"

        @Volatile
        private var instance: ArchiveNotificationStore? = null

        fun get(context: Context): ArchiveNotificationStore =
            instance ?: synchronized(this) {
                instance ?: ArchiveNotificationStore(context).also { instance = it }
            }

        fun prune(items: List<InAppNotification>): List<InAppNotification> =
            items
                .sortedByDescending { it.timestampEpochMillis }
                .take(MAX_ITEMS)
    }
}
