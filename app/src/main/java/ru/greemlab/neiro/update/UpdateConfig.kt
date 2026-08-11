package ru.greemlab.neiro.update

import ru.greemlab.neiro.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Константы самообновления в одном месте: репозиторий, интервалы, включённость.
 *
 * Репозиторий и флаг приходят из `BuildConfig` (см. `app/build.gradle.kts`), а
 * не пишутся здесь строкой: сборка знает, релизная она или нет, а исходник —
 * нет. Ничего, что зависит от `Context`, тут не заводится намеренно — файл
 * читается юнит-тестами.
 */
object UpdateConfig {

    /** Завершающий слеш обязателен: без него Retrofit срежет последний сегмент. */
    const val GITHUB_API_URL = "https://api.github.com/"

    /** Версия API фиксируется заголовком — GitHub меняет формат только между версиями. */
    const val GITHUB_API_VERSION = "2022-11-28"

    const val GITHUB_ACCEPT = "application/vnd.github+json"

    /** Ассет с суммой APK; имя задаёт `release.yml`, менять только вместе с ним. */
    const val CHECKSUMS_ASSET = "SHA256SUMS.txt"

    /** Каталог скачанных APK внутри `cacheDir` — приватный, чистится перед попыткой. */
    const val DOWNLOAD_DIR = "updates"

    /** Раз в сутки: обновление приложения не срочная новость. */
    val CHECK_INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(1)

    /** 403 от GitHub с исчерпанным лимитом — повторять раньше часа бессмысленно. */
    val RATE_LIMIT_BACKOFF_MS: Long = TimeUnit.HOURS.toMillis(1)

    /** Самообновление разрешено только релизной сборке. */
    val isEnabled: Boolean
        get() = BuildConfig.UPDATE_ENABLED

    /** `Greem4/neiro` — в таком виде лежит в `BuildConfig`. */
    val repo: String
        get() = BuildConfig.UPDATE_REPO

    val owner: String
        get() = repo.substringBefore('/')

    val repoName: String
        get() = repo.substringAfter('/')

    /** GitHub требует User-Agent; заодно видно в логах, какая версия спрашивала. */
    val userAgent: String
        get() = "neiro-android/${BuildConfig.VERSION_NAME}"

    /**
     * Имя APK в релизе — контракт с `release.yml`: там файл переименовывается
     * из `neiro-v0.1.0-release.apk` (имя от Gradle) в `neiro-0.1.0.apk`.
     */
    fun apkAssetName(version: ReleaseVersion): String = "neiro-${version.versionName}.apk"
}
