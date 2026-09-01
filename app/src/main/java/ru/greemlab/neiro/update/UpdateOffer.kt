package ru.greemlab.neiro.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Найденное обновление, пережившее закрытие приложения.
 *
 * До этого результат проверки жил только в памяти `UpdateViewModel`, а на диске
 * оставался один `last_known_version_code` — число, из которого нельзя ни
 * скачать APK, ни показать «что изменилось». Поэтому уведомление о версии вело
 * на экран, где предложение приходилось добывать заново кнопкой «Проверить
 * обновления»: телефон знал про релиз, а приложение — нет.
 *
 * Здесь то же предложение записывается целиком, и экран «О программе»
 * поднимает его при открытии — из уведомления, из настроек, офлайн, всё равно.
 *
 * Поля пишутся вручную, а не рефлексией Gson: `UpdateInfo` — не сетевая модель,
 * правил R8 на неё нет, и обфусцированные имена полей однажды превратили бы
 * сохранённое предложение в пустой объект уже после выпуска.
 */
object UpdateOffer {

    fun encode(info: UpdateInfo): String = JsonObject().apply {
        addProperty(KEY_VERSION_CODE, info.version.versionCode)
        addProperty(KEY_TITLE, info.title)
        addProperty(KEY_NOTES, info.notes)
        addProperty(KEY_RELEASE_URL, info.releaseUrl)
        addProperty(KEY_APK_NAME, info.apkName)
        addProperty(KEY_APK_URL, info.apkUrl)
        addProperty(KEY_APK_SIZE, info.apkSizeBytes)
        addProperty(KEY_CHECKSUMS_URL, info.checksumsUrl)
    }.toString()

    /**
     * @return null, если записи нет или она неполная. Неполной считается та, по
     * которой нельзя нажать «Обновить»: без ссылки на APK или на файл сумм
     * загрузка упадёт, а предложение на экране пообещает то, чего не будет.
     */
    fun decode(json: String?): UpdateInfo? {
        if (json.isNullOrBlank()) return null
        val obj = try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            // Чужой или обрезанный JSON: запись потеряна, проверка сходит заново.
            return null
        }

        val version = ReleaseVersion.fromVersionCode(obj.int(KEY_VERSION_CODE)) ?: return null
        val apkUrl = obj.string(KEY_APK_URL)
        val checksumsUrl = obj.string(KEY_CHECKSUMS_URL)
        if (apkUrl.isBlank() || checksumsUrl.isBlank()) return null

        return UpdateInfo(
            version = version,
            title = obj.string(KEY_TITLE).ifBlank { "Neiro ${version.versionName}" },
            notes = obj.string(KEY_NOTES),
            releaseUrl = obj.string(KEY_RELEASE_URL),
            apkName = obj.string(KEY_APK_NAME),
            apkUrl = apkUrl,
            apkSizeBytes = obj.long(KEY_APK_SIZE),
            checksumsUrl = checksumsUrl,
        )
    }

    /**
     * Стоит ли ещё показывать сохранённое предложение. Чистая функция без
     * Android — здесь два случая, в которых запись обязана замолчать, и оба
     * наступают без новой проверки: пользователь обновился (в том числе руками,
     * из GitHub) и пользователь нажал «Пропустить».
     *
     * @return то же предложение или null, если показывать его больше нечего.
     */
    fun usable(
        info: UpdateInfo?,
        installedVersionCode: Int,
        skippedVersionCode: Int,
    ): UpdateInfo? {
        val version = info?.version ?: return null
        if (!version.isNewerThan(installedVersionCode)) return null
        if (version.versionCode <= skippedVersionCode) return null
        return info
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_TITLE = "title"
    private const val KEY_NOTES = "notes"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_APK_URL = "apk_url"
    private const val KEY_APK_SIZE = "apk_size"
    private const val KEY_CHECKSUMS_URL = "checksums_url"
}
