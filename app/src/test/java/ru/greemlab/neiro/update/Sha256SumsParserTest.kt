package ru.greemlab.neiro.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор `SHA256SUMS.txt`. Файл делает `sha256sum` в релизном workflow, но
 * приложение обязано пережить и список из нескольких файлов, и бинарный режим
 * со звёздочкой, и лишние пробелы: не сошлось — обновление отменяется, а не
 * ставится «как-нибудь».
 */
class Sha256SumsParserTest {

    private val apkHash = "9f2c8d1b4a7e6f0c3d5b8a1e4f7c0d2b5a8e1f4c7d0b3a6e9f2c5d8b1a4e7f0c"
    private val mappingHash = "1a2b3c4d5e6f70819293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"

    @Test
    fun `нужная строка находится среди нескольких`() {
        val content = """
            $mappingHash  mapping-0.2.0.txt
            $apkHash  neiro-0.2.0.apk
        """.trimIndent()

        assertEquals(apkHash, Sha256Sums.findChecksum(content, "neiro-0.2.0.apk"))
    }

    @Test
    fun `бинарный режим со звёздочкой разбирается`() {
        val content = "$apkHash *neiro-0.2.0.apk"

        assertEquals(apkHash, Sha256Sums.findChecksum(content, "neiro-0.2.0.apk"))
    }

    @Test
    fun `лишние пробелы и пустые строки не мешают`() {
        val content = "\n   $apkHash   neiro-0.2.0.apk   \n\n"

        assertEquals(apkHash, Sha256Sums.findChecksum(content, "neiro-0.2.0.apk"))
    }

    @Test
    fun `сумма приводится к нижнему регистру`() {
        val content = "${apkHash.uppercase()}  neiro-0.2.0.apk"

        assertEquals(apkHash, Sha256Sums.findChecksum(content, "neiro-0.2.0.apk"))
    }

    @Test
    fun `нужного имени в списке нет`() {
        val content = "$mappingHash  mapping-0.2.0.txt"

        assertNull(Sha256Sums.findChecksum(content, "neiro-0.2.0.apk"))
    }

    @Test
    fun `мусор вместо суммы не проходит`() {
        // Короткая строка и текст ошибки вместо файла — GitHub иногда отдаёт
        // HTML вместо ассета, и такой «ответ» не должен стать суммой.
        assertNull(Sha256Sums.findChecksum("deadbeef  neiro-0.2.0.apk", "neiro-0.2.0.apk"))
        assertNull(Sha256Sums.findChecksum("<html>404</html>", "neiro-0.2.0.apk"))
    }

    @Test
    fun `пустой и отсутствующий файл не роняют разбор`() {
        assertNull(Sha256Sums.findChecksum(null, "neiro-0.2.0.apk"))
        assertNull(Sha256Sums.findChecksum("", "neiro-0.2.0.apk"))
        assertNull(Sha256Sums.findChecksum("$apkHash  neiro-0.2.0.apk", ""))
    }
}
