package ru.greemlab.neiro.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

/**
 * Метаданные записи, которых нет в строке дня (FOUNDATION 8.3).
 *
 * Формат строки расширить нельзя: `SessionParser.parseStudent` делает
 * `split("|", limit = 5)`, и `comment` намеренно глотает весь хвост — дописать
 * `service_id` в конец физически невозможно.
 *
 * Имена полей в JSON закреплены аннотациями: иначе в release их даёт R8, и
 * накопленная история перестаёт читаться после каждой пересборки — а копить её
 * годами и есть весь смысл этого хранилища.
 */
data class SessionMeta(
    @SerializedName("recordId")
    val recordId: Long? = null,
    @SerializedName("serviceId")
    val serviceId: Long? = null,
    @SerializedName("activityId")
    val activityId: Long? = null,
    /** Базовая цена на момент записи — не оплата клиента. */
    @SerializedName("firstCost")
    val firstCost: Double? = null,
)

/**
 * Хранилище метаданных по ключу слота (`SessionSlotKey`).
 *
 * **Пока никем не читается — это осознанно.** Смысл в том, чтобы начать писать
 * сейчас и через полгода иметь историю, если понадобится карта услуг.
 */
class SessionMetaStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, SessionMeta>>() {}.type

    @Synchronized
    fun putAll(metaByKey: Map<String, SessionMeta>) {
        if (metaByKey.isEmpty()) return
        val merged = read() + metaByKey
        val pruned = if (merged.size > MAX_ENTRIES) {
            merged.entries.drop(merged.size - MAX_ENTRIES).associate { it.key to it.value }
        } else {
            merged
        }
        prefs.edit().putString(KEY_ITEMS, gson.toJson(pruned)).apply()
    }

    fun get(slotKey: String): SessionMeta? = read()[slotKey]

    /** Битые данные означают пустую карту: sidecar никогда не должен ронять синк. */
    fun read(): Map<String, SessionMeta> = runCatching {
        val json = prefs.getString(KEY_ITEMS, null) ?: return@runCatching emptyMap()
        gson.fromJson<Map<String, SessionMeta>>(json, mapType) ?: emptyMap()
    }.getOrElse { emptyMap() }

    companion object {
        private const val PREFS_NAME = "neiro_session_meta"
        private const val KEY_ITEMS = "meta_json"

        /** Верхняя граница: примерно 10 лет записей одного специалиста. */
        private const val MAX_ENTRIES = 20_000

        @Volatile
        private var instance: SessionMetaStore? = null

        fun get(context: Context): SessionMetaStore =
            instance ?: synchronized(this) {
                instance ?: SessionMetaStore(context).also { instance = it }
            }
    }
}
