package ru.greemlab.neiro.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import ru.greemlab.neiro.BuildConfig
import java.io.IOException

/**
 * Готовое к показу описание новой версии. Собирается один раз при проверке и
 * дальше кочует по этапам: уведомление (5), загрузка (6), установка (7), экран
 * «О программе» (8).
 */
data class UpdateInfo(
    val version: ReleaseVersion,
    /** Заголовок релиза; если в GitHub он пустой — «Neiro 0.2.0». */
    val title: String,
    /** Тело релиза — «что изменилось». Может быть пустым. */
    val notes: String,
    /** Страница релиза на GitHub — «открыть на GitHub». */
    val releaseUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val checksumsUrl: String,
)

/**
 * Почему обновление не состоялось. Перечисление, а не голая строка: каждая
 * причина ведёт себя по-разному — `NoNetwork` повторяем молча, `RateLimited`
 * ждёт час, `ChecksumMismatch` удаляет файл и кричит красным.
 *
 * Причины загрузки и установки объявлены здесь заранее: их источники —
 * `UpdateDownloader`, `UpdateVerifier` и `ApkInstaller` (этапы 6–7), а место
 * разбора у всех одно.
 */
enum class UpdateFailure {
    /** Сети нет или сервер не ответил — повторим позже, молча. */
    NoNetwork,

    /** 60 анонимных запросов в час на IP исчерпаны: раньше часа смысла нет. */
    RateLimited,

    /** Релизов в репозитории нет вовсе (или последний — черновик). */
    NoRelease,

    /** Релиз есть, но контракт нарушен: чужой тег, нет APK, нет суммы. */
    MalformedRelease,

    DownloadFailed,
    ChecksumMismatch,
    SignatureMismatch,
    NoSpace,
    InstallRejected,
    InstallFailed,
}

/** Чем закончилась проверка. Экранное состояние (`UpdateState`) появится на этапе 8. */
sealed interface UpdateStatus {

    /** Есть версия новее установленной. */
    data class Available(val info: UpdateInfo) : UpdateStatus

    /** Спросили — новее нечего. */
    data class UpToDate(val checkedAt: Long) : UpdateStatus

    /**
     * В сеть не ходили: сутки с прошлой проверки не прошли.
     * [knownVersionCode] — что видели в прошлый раз (0, если не видели ничего).
     */
    data class Throttled(val checkedAt: Long, val knownVersionCode: Int) : UpdateStatus

    /** Сборке нельзя обновлять себя: debug, prerelease или магазин. */
    data class Blocked(val why: UpdateBlockReason) : UpdateStatus

    data class Failed(val failure: UpdateFailure) : UpdateStatus
}

/**
 * То, что проверке нужно от хранилища.
 *
 * Интерфейс, а не сразу [UpdatePreferences], ради `UpdateCheckerTest`: настоящие
 * `SharedPreferences` в юнит-тесте на JVM отвечают «not mocked», и проверку,
 * которая ходит в них напрямую, пришлось бы гонять только на устройстве.
 */
interface UpdateCheckStore {
    var lastCheckEpochMillis: Long
    var lastKnownVersionCode: Int
    var rateLimitedUntilMillis: Long
}

/**
 * «Есть ли версия новее» — единственный вопрос, на который отвечает этот класс.
 *
 * Порядок (ARCHITECTURE.md § Проверка): гейт канала → троттлинг → запрос →
 * разбор тега → сравнение с `BuildConfig.VERSION_CODE` → запись метки времени.
 *
 * Ничего не качает и не показывает: скачивание идёт только по нажатию
 * пользователя (этап 6), уведомление — этап 5. Android здесь нет намеренно,
 * кроме фабрики [create]: всё остальное проверяется юнит-тестами.
 */
class UpdateChecker(
    private val api: GithubApi,
    private val store: UpdateCheckStore,
    private val installedVersionCode: Int,
    private val blockReason: () -> UpdateBlockReason?,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param force ручная проверка кнопкой: суточный троттлинг игнорируется,
     * но исчерпанный лимит GitHub — нет, там ждать всё равно нечего.
     */
    suspend fun check(force: Boolean = false): UpdateStatus {
        blockReason()?.let { return UpdateStatus.Blocked(it) }

        val startedAt = now()
        val lastCheck = store.lastCheckEpochMillis
        if (!force && lastCheck > 0L && startedAt - lastCheck < UpdateConfig.CHECK_INTERVAL_MS) {
            return UpdateStatus.Throttled(lastCheck, store.lastKnownVersionCode)
        }
        if (startedAt < store.rateLimitedUntilMillis) {
            return UpdateStatus.Failed(UpdateFailure.RateLimited)
        }

        val response = try {
            api.latestRelease(UpdateConfig.owner, UpdateConfig.repoName)
        } catch (e: CancellationException) {
            // Отмену не глушим и метку не пишем: запрос не состоялся не по вине
            // сети, и следующая проверка должна пойти как обычно.
            throw e
        } catch (e: IOException) {
            // Метка времени пишется и при неудаче тоже — иначе телефон без сети
            // дёргал бы GitHub при каждом запуске приложения.
            store.lastCheckEpochMillis = startedAt
            return UpdateStatus.Failed(UpdateFailure.NoNetwork)
        } catch (e: Exception) {
            // Ответ пришёл, но разобрать его не вышло (Gson на чужом JSON).
            store.lastCheckEpochMillis = startedAt
            return UpdateStatus.Failed(UpdateFailure.MalformedRelease)
        }

        store.lastCheckEpochMillis = startedAt

        if (!response.isSuccessful) {
            return UpdateStatus.Failed(failureFor(response, startedAt))
        }
        val release = response.body() ?: return UpdateStatus.Failed(UpdateFailure.MalformedRelease)
        return evaluate(release, startedAt)
    }

    /** Разбор успешного ответа. Сети здесь уже нет — только контракт релиза. */
    private fun evaluate(release: GithubRelease, checkedAt: Long): UpdateStatus {
        // `/releases/latest` черновики и пре-релизы и так не отдаёт, но проверить
        // два поля дешевле, чем однажды раскатать черновик на живые телефоны.
        if (release.draft || release.prerelease) {
            return UpdateStatus.Failed(UpdateFailure.NoRelease)
        }

        val version = ReleaseVersion.parseTag(release.tagName.orEmpty())
            ?: return UpdateStatus.Failed(UpdateFailure.MalformedRelease)

        store.lastKnownVersionCode = version.versionCode
        if (!version.isNewerThan(installedVersionCode)) {
            return UpdateStatus.UpToDate(checkedAt)
        }

        // Ассеты разбираем только для версии новее: релиз без APK — ошибка
        // сборки, но пока он не новее нашего, приложению до неё дела нет.
        val apk = ReleaseAssets.pickApk(release.assets, version)
            ?: return UpdateStatus.Failed(UpdateFailure.MalformedRelease)
        val checksums = ReleaseAssets.pickChecksums(release.assets)
            ?: return UpdateStatus.Failed(UpdateFailure.MalformedRelease)

        return UpdateStatus.Available(
            UpdateInfo(
                version = version,
                title = release.name?.takeIf { it.isNotBlank() } ?: "Neiro ${version.versionName}",
                notes = release.body.orEmpty().trim(),
                releaseUrl = release.htmlUrl.orEmpty(),
                apkName = apk.name.orEmpty(),
                apkUrl = apk.downloadUrl.orEmpty(),
                apkSizeBytes = apk.size,
                checksumsUrl = checksums.downloadUrl.orEmpty(),
            ),
        )
    }

    private fun failureFor(response: Response<*>, at: Long): UpdateFailure = when {
        isRateLimited(response) -> {
            store.rateLimitedUntilMillis = at + UpdateConfig.RATE_LIMIT_BACKOFF_MS
            UpdateFailure.RateLimited
        }
        // 404 — репозиторий публичный, но релизов в нём ещё нет.
        response.code() == HTTP_NOT_FOUND -> UpdateFailure.NoRelease
        // Остальное (5xx, прокси оператора, капторный портал) — «сейчас не
        // достучались»: ведём себя как при отсутствии сети, повторим позже.
        else -> UpdateFailure.NoNetwork
    }

    /**
     * Исчерпанный лимит GitHub отдаёт 403 (иногда 429) с `X-RateLimit-Remaining: 0`.
     * 403 без этого заголовка — что-то другое (блокировка, прокси), и час
     * молчать из-за него не надо.
     */
    private fun isRateLimited(response: Response<*>): Boolean {
        val code = response.code()
        if (code != HTTP_FORBIDDEN && code != HTTP_TOO_MANY_REQUESTS) return false
        val remaining = response.headers()[HEADER_RATE_LIMIT_REMAINING]
        return remaining == "0" || code == HTTP_TOO_MANY_REQUESTS
    }

    companion object {
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"

        /** Боевая сборка: настоящий GitHub, настоящие настройки, гейт канала. */
        fun create(context: Context): UpdateChecker {
            val appContext = context.applicationContext
            return UpdateChecker(
                api = UpdateClient.getApi(),
                store = UpdatePreferences.get(appContext),
                installedVersionCode = BuildConfig.VERSION_CODE,
                blockReason = { UpdateChannelGate.blockReason(appContext) },
            )
        }
    }
}
