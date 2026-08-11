package ru.greemlab.neiro.update

import android.content.Context

/**
 * Настройки и метаданные самообновления — по образцу
 * [ru.greemlab.neiro.sync.SyncPreferences]: тот же стиль, тот же
 * `get(context)`-синглтон, своё имя файла.
 *
 * Отдельные `SharedPreferences`, а не общие с синхронизацией: логаут чистит
 * `neiro_sync_prefs` целиком, а метка последней проверки GitHub к аккаунту
 * YClients отношения не имеет и переживать выход из аккаунта обязана.
 *
 * Реализует [UpdateCheckStore] — интерфейс, через который к хранилищу ходит
 * [UpdateChecker]. Ключи уведомлений, скачанного APK и отметки после установки
 * (ARCHITECTURE.md § Хранилище) появятся здесь на своих этапах: пустой
 * аксессор, который никто не читает, — тот же мёртвый код.
 */
class UpdatePreferences(context: Context) : UpdateCheckStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Проверять обновления автоматически. Ручная проверка кнопкой работает всегда. */
    var isAutoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    /**
     * Когда последний раз спрашивали GitHub — успешно или нет.
     *
     * `commit()`, а не `apply()`: запись идёт из воркера, процесс после неё
     * могут убить в любой момент, а потерянная метка стоит лишнего запроса при
     * каждом старте.
     */
    override var lastCheckEpochMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK_EPOCH, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHECK_EPOCH, value).commit()
        }

    /** Что видели в релизе в прошлый раз — кэш для офлайна и для троттлинга. */
    override var lastKnownVersionCode: Int
        get() = prefs.getInt(KEY_LAST_KNOWN_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_KNOWN_VERSION_CODE, value).apply()

    /**
     * До какого времени в GitHub ходить бессмысленно: 60 анонимных запросов в
     * час на IP исчерпаны. Отдельно от [lastCheckEpochMillis] потому, что
     * обычный троттлинг ручная проверка обходит, а этот — нет.
     */
    override var rateLimitedUntilMillis: Long
        get() = prefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_RATE_LIMITED_UNTIL, value).apply()

    /**
     * О какой версии уже говорили в шторке. Иначе телефон сообщал бы об одном и
     * том же выпуске каждые сутки, пока его не поставят.
     */
    var notifiedVersionCode: Int
        get() = prefs.getInt(KEY_NOTIFIED_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_NOTIFIED_VERSION_CODE, value).apply()

    /**
     * «Пропустить эту версию» — молчим, пока не выйдет следующая. Хранится
     * `versionCode`, а не флаг: следующий выпуск будет больше числом и снова
     * пробьётся к пользователю сам.
     */
    var skippedVersionCode: Int
        get() = prefs.getInt(KEY_SKIPPED_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_SKIPPED_VERSION_CODE, value).apply()

    /** Забыть, что и когда проверяли: следующая проверка пойдёт в сеть сразу. */
    fun clearCheckState() {
        prefs.edit()
            .remove(KEY_LAST_CHECK_EPOCH)
            .remove(KEY_LAST_KNOWN_VERSION_CODE)
            .remove(KEY_RATE_LIMITED_UNTIL)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "neiro_update_prefs"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"
        private const val KEY_LAST_CHECK_EPOCH = "last_check_epoch"
        private const val KEY_LAST_KNOWN_VERSION_CODE = "last_known_version_code"
        private const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until"
        private const val KEY_NOTIFIED_VERSION_CODE = "notified_version_code"
        private const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"

        @Volatile
        private var instance: UpdatePreferences? = null

        fun get(context: Context): UpdatePreferences =
            instance ?: synchronized(this) {
                instance ?: UpdatePreferences(context).also { instance = it }
            }
    }
}
