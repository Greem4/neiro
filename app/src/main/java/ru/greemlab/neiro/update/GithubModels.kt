package ru.greemlab.neiro.update

import com.google.gson.annotations.SerializedName

/**
 * Ответ `GET /repos/{owner}/{repo}/releases/latest` — только те поля, которые
 * нужны приложению.
 *
 * Все поля объявлены nullable намеренно: Gson создаёт объект в обход
 * конструктора, значения по умолчанию не применяются, и отсутствующее в JSON
 * поле окажется null даже у non-null типа. Лучше честный null, чем
 * `NullPointerException` в неожиданном месте.
 */
data class GithubRelease(
    @SerializedName("tag_name") val tagName: String?,
    val name: String?,
    val body: String?,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerializedName("html_url") val htmlUrl: String?,
    val assets: List<GithubAsset>?,
)

/** Файл, приложенный к релизу: APK или `SHA256SUMS.txt`. */
data class GithubAsset(
    val name: String?,
    @SerializedName("browser_download_url") val downloadUrl: String?,
    val size: Long = 0,
    @SerializedName("content_type") val contentType: String?,
)

/**
 * Выбор нужных файлов из релиза. Чистые функции без Android — проверяются
 * тестами (`AssetPickerTest`).
 *
 * Контракт релиза (ARCHITECTURE.md § Источник правды): ровно один `.apk` с
 * именем `neiro-<версия>.apk` и ассет `SHA256SUMS.txt`. Наугад из двух APK не
 * выбираем: релиз с двумя APK — ошибка сборки, а не повод угадать.
 */
object ReleaseAssets {

    /** null — контракт нарушен: APK нет вовсе или их больше одного. */
    fun pickApk(assets: List<GithubAsset>?, version: ReleaseVersion): GithubAsset? {
        val usable = assets?.filter { !it.name.isNullOrBlank() && !it.downloadUrl.isNullOrBlank() }
            ?: return null

        val expectedName = UpdateConfig.apkAssetName(version)
        usable.firstOrNull { it.name.equals(expectedName, ignoreCase = true) }?.let { return it }

        // Имя не совпало (переименовали релиз руками) — берём единственный APK.
        // singleOrNull возвращает null и когда APK ни одного, и когда их два.
        return usable.singleOrNull { it.name.orEmpty().endsWith(APK_SUFFIX, ignoreCase = true) }
    }

    /** Ассет с суммой; null — сверять нечем, обновление отменяется. */
    fun pickChecksums(assets: List<GithubAsset>?): GithubAsset? =
        assets?.firstOrNull {
            it.name.equals(UpdateConfig.CHECKSUMS_ASSET, ignoreCase = true) &&
                !it.downloadUrl.isNullOrBlank()
        }

    private const val APK_SUFFIX = ".apk"
}
