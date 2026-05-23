package ru.greemlab.neiro.notifications

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Лента in-app уведомлений: хранение ~[RETENTION_DAYS] дней, поток для UI.
 */
class InAppNotificationStore private constructor(context: Context) {

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

    fun append(
        title: String,
        body: String,
        relatedDate: LocalDate? = null,
        dedupeKey: String? = null,
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
            read = false,
        )
        persist(listOf(entry) + current)
    }

    fun markAllRead() {
        persist(_items.value.map { it.copy(read = true) })
    }

    fun clearAll() {
        persist(emptyList())
    }

    companion object {
        const val RETENTION_DAYS = 10
        private const val MAX_ITEMS = 300
        private const val PREFS_NAME = "in_app_notifications"
        private const val KEY_ITEMS = "items_json"

        @Volatile
        private var instance: InAppNotificationStore? = null

        fun get(context: Context): InAppNotificationStore =
            instance ?: synchronized(this) {
                instance ?: InAppNotificationStore(context).also { instance = it }
            }

        fun prune(items: List<InAppNotification>, nowMillis: Long = System.currentTimeMillis()): List<InAppNotification> {
            val cutoff = nowMillis - TimeUnit.DAYS.toMillis(RETENTION_DAYS.toLong())
            return items
                .filter { it.timestampEpochMillis >= cutoff }
                .sortedByDescending { it.timestampEpochMillis }
                .take(MAX_ITEMS)
        }
    }
}
