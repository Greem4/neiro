package ru.greemlab.neiro.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выбор файлов из релиза. Контракт держит `release.yml`, но приложение обязано
 * пережить релиз, собранный руками: лишний APK — не повод угадывать, какой из
 * двух ставить.
 */
class AssetPickerTest {

    private val version = ReleaseVersion(0, 2, 0)

    @Test
    fun `APK находится по точному имени среди прочих файлов`() {
        val assets = listOf(
            asset("mapping-0.2.0.txt"),
            asset("neiro-0.2.0.apk"),
            asset("SHA256SUMS.txt"),
        )

        assertEquals("neiro-0.2.0.apk", ReleaseAssets.pickApk(assets, version)?.name)
    }

    @Test
    fun `единственный APK берётся даже с чужим именем`() {
        val assets = listOf(asset("neiro-release.apk"), asset("SHA256SUMS.txt"))

        assertEquals("neiro-release.apk", ReleaseAssets.pickApk(assets, version)?.name)
    }

    @Test
    fun `два APK без точного совпадения — ошибка, а не выбор наугад`() {
        val assets = listOf(asset("neiro-arm64.apk"), asset("neiro-armv7.apk"))

        assertNull(ReleaseAssets.pickApk(assets, version))
    }

    @Test
    fun `точное имя сильнее второго APK в релизе`() {
        val assets = listOf(asset("neiro-0.2.0.apk"), asset("neiro-debug.apk"))

        assertEquals("neiro-0.2.0.apk", ReleaseAssets.pickApk(assets, version)?.name)
    }

    @Test
    fun `релиз без APK — ошибка`() {
        val assets = listOf(asset("SHA256SUMS.txt"), asset("mapping-0.2.0.txt"))

        assertNull(ReleaseAssets.pickApk(assets, version))
    }

    @Test
    fun `пустой и отсутствующий список ассетов не роняют разбор`() {
        assertNull(ReleaseAssets.pickApk(emptyList(), version))
        assertNull(ReleaseAssets.pickApk(null, version))
        assertNull(ReleaseAssets.pickChecksums(null))
    }

    @Test
    fun `ассет без ссылки на скачивание не годится`() {
        val assets = listOf(GithubAsset("neiro-0.2.0.apk", downloadUrl = null, contentType = null))

        assertNull(ReleaseAssets.pickApk(assets, version))
    }

    @Test
    fun `сумма находится по имени независимо от регистра`() {
        val assets = listOf(asset("neiro-0.2.0.apk"), asset("sha256sums.txt"))

        assertEquals("sha256sums.txt", ReleaseAssets.pickChecksums(assets)?.name)
    }

    @Test
    fun `релиз без файла сумм — сверять нечем`() {
        val assets = listOf(asset("neiro-0.2.0.apk"))

        assertNull(ReleaseAssets.pickChecksums(assets))
    }

    private fun asset(name: String) = GithubAsset(
        name = name,
        downloadUrl = "https://github.com/Greem4/neiro/releases/download/v0.2.0/$name",
        size = 1_024,
        contentType = "application/octet-stream",
    )
}
