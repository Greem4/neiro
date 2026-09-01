package ru.greemlab.neiro.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Сохранённое предложение обновиться: то, из-за чего экран «О программе»
 * открывается готовым, а не с пустой кнопкой «Проверить обновления».
 *
 * Проверяется здесь, а не глазами на телефоне, потому что ломается это тихо:
 * запись переживает перезапуск, и неверно разобранное поле выяснилось бы уже
 * после выпуска — предложением, которое не скачивается.
 */
class UpdateOfferTest {

    private val info = UpdateInfo(
        version = ReleaseVersion(0, 2, 4),
        title = "Neiro 0.2.4",
        notes = "- Разбор дня по плиткам\n- Мелкие правки",
        releaseUrl = "https://github.com/Greem4/neiro/releases/tag/v0.2.4",
        apkName = "neiro-0.2.4.apk",
        apkUrl = "https://github.com/Greem4/neiro/releases/download/v0.2.4/neiro-0.2.4.apk",
        apkSizeBytes = 15_728_640,
        checksumsUrl = "https://github.com/Greem4/neiro/releases/download/v0.2.4/SHA256SUMS",
    )

    @Test
    fun `запись переживает круг через строку без потерь`() {
        assertEquals(info, UpdateOffer.decode(UpdateOffer.encode(info)))
    }

    @Test
    fun `пустой строки и мусора не пугаемся`() {
        assertNull(UpdateOffer.decode(null))
        assertNull(UpdateOffer.decode(""))
        assertNull(UpdateOffer.decode("{\"version_code\":"))
        assertNull(UpdateOffer.decode("не json вовсе"))
    }

    @Test
    fun `без ссылки на APK предложения нет`() {
        // Кнопка «Обновить» по такой записи привела бы к ошибке загрузки.
        val json = UpdateOffer.encode(info.copy(apkUrl = ""))
        assertNull(UpdateOffer.decode(json))
    }

    @Test
    fun `без файла сумм предложения нет`() {
        // Скачать вышло бы, а сверить — нет: установка такого APK запрещена.
        val json = UpdateOffer.encode(info.copy(checksumsUrl = ""))
        assertNull(UpdateOffer.decode(json))
    }

    @Test
    fun `версию новее показываем`() {
        val usable = UpdateOffer.usable(info, installedVersionCode = 203, skippedVersionCode = 0)
        assertEquals(info, usable)
    }

    @Test
    fun `после установки предложение молчит`() {
        // Обновиться могли и мимо приложения — руками из GitHub.
        assertNull(UpdateOffer.usable(info, installedVersionCode = 204, skippedVersionCode = 0))
    }

    @Test
    fun `пропущенная версия молчит`() {
        assertNull(UpdateOffer.usable(info, installedVersionCode = 203, skippedVersionCode = 204))
    }

    @Test
    fun `следующая после пропущенной снова показывается`() {
        val next = info.copy(version = ReleaseVersion(0, 2, 5))
        assertEquals(next, UpdateOffer.usable(next, installedVersionCode = 203, skippedVersionCode = 204))
    }
}
