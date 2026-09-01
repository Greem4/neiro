package ru.greemlab.neiro.update

import android.content.Context
import ru.greemlab.neiro.BuildConfig

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

    /**
     * Последнее найденное обновление целиком — то самое предложение, которое
     * показывает экран «О программе».
     *
     * Отдельно от [lastKnownVersionCode] потому, что это разные вопросы:
     * там — «что вообще лежит в релизе» (нужно проверке и троттлингу, пишется
     * даже для версии не новее нашей), здесь — «что предложить пользователю».
     * Экран и точка «доступна версия» читают только это поле, иначе получились
     * бы две копии одного ответа, расходящиеся при откате релиза.
     *
     * Пишется в [UpdateCheckCoordinator.checkNow] — единственной точке проверки.
     */
    var availableUpdate: UpdateInfo?
        get() = UpdateOffer.decode(prefs.getString(KEY_AVAILABLE_UPDATE, null))
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_AVAILABLE_UPDATE)
            } else {
                editor.putString(KEY_AVAILABLE_UPDATE, UpdateOffer.encode(value))
            }
            editor.apply()
        }

    /**
     * Предложение, которое ещё имеет смысл показывать: не про установленную
     * версию и не про пропущенную.
     *
     * Устаревшую запись стираем на месте — иначе «Пропустить», нажатое на
     * экране, пришлось бы дублировать в каждом читателе.
     */
    fun usableUpdateOffer(): UpdateInfo? {
        val offer = UpdateOffer.usable(
            info = availableUpdate,
            installedVersionCode = BuildConfig.VERSION_CODE,
            skippedVersionCode = skippedVersionCode,
        )
        if (offer == null) availableUpdate = null
        return offer
    }

    /** Скачанный и проверенный, но ещё не установленный APK. */
    var pendingApkPath: String?
        get() = prefs.getString(KEY_PENDING_APK_PATH, null)
        set(value) = prefs.edit().putString(KEY_PENDING_APK_PATH, value).apply()

    var pendingVersionCode: Int
        get() = prefs.getInt(KEY_PENDING_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_PENDING_VERSION_CODE, value).apply()

    /**
     * С какой версии уходили, когда отдавали APK установщику. Читается после
     * перезапуска: больше нуля и меньше текущей — обновление состоялось.
     */
    val updatedFromVersionCode: Int
        get() = prefs.getInt(KEY_UPDATED_FROM_VERSION_CODE, 0)

    /**
     * Отметка «сейчас будет установка». Пишется **до** `commit` сессии и через
     * `commit()`, а не `apply()`: после успешной установки процесс убивают, и
     * асинхронная запись не успеет (RISKS.md § Установка убивает процесс).
     */
    fun recordInstallAttempt(targetVersionCode: Int) {
        prefs.edit()
            .putInt(KEY_UPDATED_FROM_VERSION_CODE, BuildConfig.VERSION_CODE)
            .putInt(KEY_PENDING_VERSION_CODE, targetVersionCode)
            .commit()
    }

    /**
     * Прочитать отметку об установке и стереть её: «Обновлено до 0.1.2»
     * показывается один раз, а не при каждом открытии экрана.
     *
     * @return версия, с которой уходили, или 0 — обновления не было.
     */
    fun consumeUpdatedFrom(): Int {
        val from = updatedFromVersionCode
        if (from > 0) clearInstallAttempt()
        return from
    }

    /** Установка не началась или провалилась — отметка не должна пережить попытку. */
    fun clearInstallAttempt() {
        prefs.edit()
            .remove(KEY_UPDATED_FROM_VERSION_CODE)
            .remove(KEY_PENDING_VERSION_CODE)
            .apply()
    }

    /** Забыть скачанный файл: он установлен, удалён или не прошёл проверку. */
    fun clearPendingApk() {
        prefs.edit()
            .remove(KEY_PENDING_APK_PATH)
            .remove(KEY_PENDING_VERSION_CODE)
            .apply()
    }

    /** Забыть, что и когда проверяли: следующая проверка пойдёт в сеть сразу. */
    fun clearCheckState() {
        prefs.edit()
            .remove(KEY_LAST_CHECK_EPOCH)
            .remove(KEY_LAST_KNOWN_VERSION_CODE)
            .remove(KEY_RATE_LIMITED_UNTIL)
            .remove(KEY_AVAILABLE_UPDATE)
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
        private const val KEY_PENDING_APK_PATH = "pending_apk_path"
        private const val KEY_PENDING_VERSION_CODE = "pending_version_code"
        private const val KEY_UPDATED_FROM_VERSION_CODE = "updated_from_version_code"
        private const val KEY_AVAILABLE_UPDATE = "available_update"

        @Volatile
        private var instance: UpdatePreferences? = null

        fun get(context: Context): UpdatePreferences =
            instance ?: synchronized(this) {
                instance ?: UpdatePreferences(context).also { instance = it }
            }
    }
}
