package ru.greemlab.neiro.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор тега и сравнение версий.
 *
 * Формула versionCode продублирована в `app/build.gradle.kts`. Разойдутся —
 * приложение начнёт предлагать обновление на само себя, и заметит это только
 * пользователь. Поэтому числа здесь написаны руками, а не посчитаны той же
 * формулой: тест должен ловить смену формулы, а не повторять её.
 */
class ReleaseVersionTest {

    @Test
    fun `тег v0_2_0 разбирается в versionCode 200`() {
        val version = ReleaseVersion.parseTag("v0.2.0")

        assertEquals(200, version?.versionCode)
        assertEquals("0.2.0", version?.versionName)
    }

    @Test
    fun `мажор и минор попадают в разные разряды`() {
        assertEquals(10_000, ReleaseVersion.parseTag("v1.0.0")?.versionCode)
        assertEquals(10_203, ReleaseVersion.parseTag("v1.2.3")?.versionCode)
        assertEquals(9_999, ReleaseVersion.parseTag("v0.99.99")?.versionCode)
    }

    @Test
    fun `пробелы вокруг тега не мешают`() {
        assertEquals(100, ReleaseVersion.parseTag("  v0.1.0\n")?.versionCode)
    }

    @Test
    fun `тег без префикса v не наш`() {
        assertNull(ReleaseVersion.parseTag("0.2.0"))
    }

    @Test
    fun `тег из двух чисел не наш`() {
        assertNull(ReleaseVersion.parseTag("v0.2"))
    }

    @Test
    fun `суффикс релиз-кандидата не наш`() {
        assertNull(ReleaseVersion.parseTag("v0.2.0-rc1"))
    }

    @Test
    fun `patch больше 99 ломает монотонность и отвергается`() {
        // v1.2.300 дал бы 10 500 — столько же, сколько v1.7.0.
        assertNull(ReleaseVersion.parseTag("v1.2.300"))
    }

    @Test
    fun `minor больше 99 отвергается по той же причине`() {
        assertNull(ReleaseVersion.parseTag("v1.100.0"))
    }

    @Test
    fun `пустой тег и мусор отвергаются`() {
        assertNull(ReleaseVersion.parseTag(""))
        assertNull(ReleaseVersion.parseTag("latest"))
        assertNull(ReleaseVersion.parseTag("v0.1.0.1"))
    }

    @Test
    fun `версия 0_10_0 новее 0_9_0`() {
        val newer = ReleaseVersion.parseTag("v0.10.0")!!
        val older = ReleaseVersion.parseTag("v0.9.0")!!

        // По строке «0.10.0» меньше «0.9.0» — ровно тот случай, ради которого
        // сравнение идёт по числу.
        assertTrue(newer > older)
        assertTrue(newer.isNewerThan(older.versionCode))
        assertFalse(older.isNewerThan(newer.versionCode))
    }

    @Test
    fun `равные версии обновлением не считаются`() {
        val version = ReleaseVersion.parseTag("v0.1.0")!!

        assertFalse(version.isNewerThan(version.versionCode))
        assertEquals(0, version.compareTo(ReleaseVersion(0, 1, 0)))
    }

    @Test
    fun `перескок через мажор считается обновлением`() {
        val remote = ReleaseVersion.parseTag("v2.0.0")!!

        assertTrue(remote.isNewerThan(ReleaseVersion(0, 99, 99).versionCode))
    }

    @Test
    fun `откат на старую версию обновлением не считается`() {
        val remote = ReleaseVersion.parseTag("v0.1.0")!!

        assertFalse(remote.isNewerThan(ReleaseVersion(0, 2, 0).versionCode))
    }
}
