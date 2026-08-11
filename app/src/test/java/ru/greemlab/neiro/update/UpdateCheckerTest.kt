package ru.greemlab.neiro.update

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений на подменённом ответе GitHub: настоящей сети и Android
 * здесь нет, поэтому проверяется ровно то, ради чего [UpdateChecker] написан —
 * порядок «гейт → троттлинг → запрос → разбор» и поведение при каждой ошибке.
 *
 * Установленная версия во всех тестах — 0.1.0 (versionCode 100).
 */
class UpdateCheckerTest {

    private val installedVersionCode = 100

    // -------- гейт канала --------

    @Test
    fun `debug-сборке проверять нечего, до сети дело не доходит`() = runBlocking {
        val api = FakeGithubApi(Response.success(release(tag = "v0.2.0")))
        val checker = checker(api, blockReason = { UpdateBlockReason.NotReleaseBuild })

        val status = checker.check()

        assertEquals(UpdateStatus.Blocked(UpdateBlockReason.NotReleaseBuild), status)
        assertEquals(0, api.calls)
    }

    // -------- сравнение версий --------

    @Test
    fun `версия новее — обновление с ссылками из релиза`() = runBlocking {
        val api = FakeGithubApi(Response.success(release(tag = "v0.2.0")))

        val status = checker(api).check()

        val info = (status as UpdateStatus.Available).info
        assertEquals(ReleaseVersion(0, 2, 0), info.version)
        assertEquals("neiro-0.2.0.apk", info.apkName)
        assertEquals("https://github.com/Greem4/neiro/releases/download/v0.2.0/neiro-0.2.0.apk", info.apkUrl)
        assertEquals("https://github.com/Greem4/neiro/releases/download/v0.2.0/SHA256SUMS.txt", info.checksumsUrl)
        assertEquals("Neiro 0.2.0", info.title)
        assertEquals("Что изменилось", info.notes)
    }

    @Test
    fun `та же версия — обновлений нет`() = runBlocking {
        val api = FakeGithubApi(Response.success(release(tag = "v0.1.0")))

        assertTrue(checker(api).check() is UpdateStatus.UpToDate)
    }

    @Test
    fun `релиз старее установленной сборки не предлагается`() = runBlocking {
        val api = FakeGithubApi(Response.success(release(tag = "v0.0.9")))

        assertTrue(checker(api).check() is UpdateStatus.UpToDate)
    }

    @Test
    fun `увиденная версия запоминается для офлайна`() = runBlocking {
        val store = FakeStore()
        val api = FakeGithubApi(Response.success(release(tag = "v0.2.0")))

        checker(api, store = store).check()

        assertEquals(200, store.lastKnownVersionCode)
    }

    // -------- троттлинг --------

    @Test
    fun `вторая проверка в те же сутки в сеть не идёт`() = runBlocking {
        val store = FakeStore()
        val api = FakeGithubApi(Response.success(release(tag = "v0.2.0")))
        var now = 1_000_000L
        val checker = checker(api, store = store, now = { now })

        checker.check()
        now += TimeUnit.HOURS.toMillis(5)
        val status = checker.check()

        assertEquals(1, api.calls)
        assertEquals(UpdateStatus.Throttled(1_000_000L, 200), status)
    }

    @Test
    fun `через сутки спрашиваем снова`() = runBlocking {
        val api = FakeGithubApi(Response.success(release(tag = "v0.2.0")))
        var now = 1_000_000L
        val checker = checker(api, now = { now })

        checker.check()
        now += TimeUnit.DAYS.toMillis(1)
        checker.check()

        assertEquals(2, api.calls)
    }

    @Test
    fun `ручная проверка троттлинг не соблюдает`() = runBlocking {
        val api = FakeGithubApi(Response.success(release(tag = "v0.2.0")))
        val checker = checker(api, now = { 1_000_000L })

        checker.check()
        val status = checker.check(force = true)

        assertEquals(2, api.calls)
        assertTrue(status is UpdateStatus.Available)
    }

    // -------- ошибки --------

    @Test
    fun `нет сети — метка времени всё равно пишется`() = runBlocking {
        val store = FakeStore()
        val api = FakeGithubApi { throw IOException("сети нет") }

        val status = checker(api, store = store, now = { 1_000_000L }).check()

        assertEquals(UpdateStatus.Failed(UpdateFailure.NoNetwork), status)
        // Иначе телефон без сети дёргал бы GitHub при каждом запуске.
        assertEquals(1_000_000L, store.lastCheckEpochMillis)
    }

    @Test
    fun `исчерпанный лимит GitHub молчит час даже по кнопке`() = runBlocking {
        val store = FakeStore()
        val api = FakeGithubApi(errorResponse(403, mapOf("X-RateLimit-Remaining" to "0")))
        var now = 1_000_000L
        val checker = checker(api, store = store, now = { now })

        assertEquals(UpdateStatus.Failed(UpdateFailure.RateLimited), checker.check())

        now += TimeUnit.MINUTES.toMillis(30)
        val forced = checker.check(force = true)

        assertEquals(UpdateStatus.Failed(UpdateFailure.RateLimited), forced)
        assertEquals(1, api.calls)

        now += TimeUnit.MINUTES.toMillis(31)
        checker.check(force = true)
        assertEquals(2, api.calls)
    }

    @Test
    fun `403 без счётчика лимита — не лимит, а обычная неудача`() = runBlocking {
        val store = FakeStore()
        val api = FakeGithubApi(errorResponse(403))

        val status = checker(api, store = store).check()

        assertEquals(UpdateStatus.Failed(UpdateFailure.NoNetwork), status)
        assertEquals(0L, store.rateLimitedUntilMillis)
    }

    @Test
    fun `релизов в репозитории ещё нет`() = runBlocking {
        val api = FakeGithubApi(errorResponse(404))

        assertEquals(UpdateStatus.Failed(UpdateFailure.NoRelease), checker(api).check())
    }

    @Test
    fun `сервер отвечает пятисоткой — ведём себя как без сети`() = runBlocking {
        val api = FakeGithubApi(errorResponse(502))

        assertEquals(UpdateStatus.Failed(UpdateFailure.NoNetwork), checker(api).check())
    }

    @Test
    fun `черновик и пре-релиз не раскатываются`() = runBlocking {
        val draft = FakeGithubApi(Response.success(release(tag = "v0.2.0", draft = true)))
        val pre = FakeGithubApi(Response.success(release(tag = "v0.2.0", prerelease = true)))

        assertEquals(UpdateStatus.Failed(UpdateFailure.NoRelease), checker(draft).check())
        assertEquals(UpdateStatus.Failed(UpdateFailure.NoRelease), checker(pre).check())
    }

    @Test
    fun `тег не нашей схемы — ошибка, а не догадка`() = runBlocking {
        val api = FakeGithubApi(Response.success(release(tag = "release-2026-08")))

        assertEquals(UpdateStatus.Failed(UpdateFailure.MalformedRelease), checker(api).check())
    }

    @Test
    fun `пустой ответ разобрать нечем`() = runBlocking {
        val api = FakeGithubApi(Response.success<GithubRelease>(null))

        assertEquals(UpdateStatus.Failed(UpdateFailure.MalformedRelease), checker(api).check())
    }

    @Test
    fun `новая версия без APK — обновление отменяется`() = runBlocking {
        val api = FakeGithubApi(
            Response.success(
                release(tag = "v0.2.0", assets = listOf(asset("SHA256SUMS.txt", "v0.2.0"))),
            ),
        )

        assertEquals(UpdateStatus.Failed(UpdateFailure.MalformedRelease), checker(api).check())
    }

    @Test
    fun `новая версия без суммы — сверять нечем, отменяется`() = runBlocking {
        val api = FakeGithubApi(
            Response.success(
                release(tag = "v0.2.0", assets = listOf(asset("neiro-0.2.0.apk", "v0.2.0"))),
            ),
        )

        assertEquals(UpdateStatus.Failed(UpdateFailure.MalformedRelease), checker(api).check())
    }

    // -------- вспомогательное --------

    private fun checker(
        api: GithubApi,
        store: UpdateCheckStore = FakeStore(),
        blockReason: () -> UpdateBlockReason? = { null },
        now: () -> Long = { 1_000_000L },
    ) = UpdateChecker(
        api = api,
        store = store,
        installedVersionCode = installedVersionCode,
        blockReason = blockReason,
        now = now,
    )

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GithubAsset>? = null,
    ): GithubRelease {
        val version = tag.removePrefix("v")
        return GithubRelease(
            tagName = tag,
            name = null,
            body = "Что изменилось",
            draft = draft,
            prerelease = prerelease,
            htmlUrl = "https://github.com/Greem4/neiro/releases/tag/$tag",
            assets = assets ?: listOf(
                asset("neiro-$version.apk", tag),
                asset("SHA256SUMS.txt", tag),
            ),
        )
    }

    private fun asset(name: String, tag: String) = GithubAsset(
        name = name,
        downloadUrl = "https://github.com/Greem4/neiro/releases/download/$tag/$name",
        size = 15_000_000L,
        contentType = "application/octet-stream",
    )

    private fun errorResponse(
        code: Int,
        headers: Map<String, String> = emptyMap(),
    ): Response<GithubRelease> {
        val raw = okhttp3.Response.Builder()
            .code(code)
            .message("ошибка $code")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://api.github.com/").build())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return Response.error(
            "{}".toResponseBody("application/json".toMediaTypeOrNull()),
            raw,
        )
    }

    private class FakeStore(
        override var lastCheckEpochMillis: Long = 0L,
        override var lastKnownVersionCode: Int = 0,
        override var rateLimitedUntilMillis: Long = 0L,
    ) : UpdateCheckStore

    /** Один заранее заготовленный ответ и счётчик обращений — им проверяется троттлинг. */
    private class FakeGithubApi(
        private val answer: () -> Response<GithubRelease>,
    ) : GithubApi {

        constructor(response: Response<GithubRelease>) : this({ response })

        var calls = 0
            private set

        override suspend fun latestRelease(owner: String, repo: String): Response<GithubRelease> {
            calls++
            return answer()
        }
    }
}
