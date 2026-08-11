package ru.greemlab.neiro.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Разбор `SHA256SUMS.txt`. Формат — вывод `sha256sum`: сумма, пробелы, имя
 * файла; в бинарном режиме перед именем стоит звёздочка.
 *
 * Чистые функции без Android — проверяются тестами (`Sha256SumsParserTest`).
 */
object Sha256Sums {

    private val LINE_REGEX = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")

    /** null — строки про этот файл в списке нет, сверять нечем. */
    fun findChecksum(content: String?, fileName: String): String? {
        if (content.isNullOrBlank() || fileName.isBlank()) return null
        return content.lineSequence()
            .mapNotNull { LINE_REGEX.matchEntire(it.trim()) }
            .firstOrNull { it.groupValues[2].trim().equals(fileName, ignoreCase = true) }
            ?.groupValues
            ?.get(1)
            ?.lowercase()
    }
}

/**
 * Две обязательные проверки перед установкой — обе до неё, а не после.
 *
 * 1. **Сумма из релиза** — защищает от битой закачки и подменённого ассета.
 * 2. **Подпись APK против собственной** — защищает от главного тихого сценария:
 *    файл целый, но подписан не нашим ключом. Система откажется его ставить и
 *    покажет «Приложение не установлено» без объяснений; мы проверяем сами и
 *    говорим человеческим языком.
 */
object UpdateVerifier {

    /** @return причина отказа; null — файл можно ставить. */
    suspend fun verify(
        context: Context,
        apk: File,
        checksumsContent: String?,
    ): UpdateFailure? = withContext(Dispatchers.IO) {
        if (!apk.isFile || apk.length() == 0L) return@withContext UpdateFailure.DownloadFailed

        val expected = Sha256Sums.findChecksum(checksumsContent, apk.name)
        if (expected == null) {
            Log.w(TAG, "В SHA256SUMS.txt нет строки про ${apk.name}")
            apk.delete()
            return@withContext UpdateFailure.MalformedRelease
        }

        val actual = sha256(apk)
        if (!actual.equals(expected, ignoreCase = true)) {
            Log.w(TAG, "Сумма не сошлась: ждали $expected, получили $actual")
            apk.delete()
            return@withContext UpdateFailure.ChecksumMismatch
        }

        if (!signatureMatches(context.applicationContext, apk)) {
            Log.w(TAG, "Подпись APK не совпадает с нашей — файл удалён")
            apk.delete()
            return@withContext UpdateFailure.SignatureMismatch
        }

        null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Сертификаты сравниваем по SHA-256 их байтов, а не по объектам `Signature`:
     * так же, как это делает система, и без зависимости от порядка в массиве.
     */
    private fun signatureMatches(context: Context, apk: File): Boolean {
        val installed = certificateHashes(installedPackageInfo(context))
        val candidate = certificateHashes(archivePackageInfo(context, apk))
        if (installed.isEmpty() || candidate.isEmpty()) {
            // Прочитать подпись не вышло — считаем это несовпадением: пропустить
            // непроверенный APK хуже, чем отказаться от подозрительного.
            Log.w(TAG, "Подписи прочитать не удалось (установлено: ${installed.size}, файл: ${candidate.size})")
            return false
        }
        // Пересечение, а не строгое равенство: после ротации ключа у
        // установленной сборки в истории лежат оба сертификата, а у нового APK —
        // только новый. Система в такой паре обновление принимает, и наша
        // проверка не должна быть строже установщика.
        return installed.any { it in candidate }
    }

    private fun installedPackageInfo(context: Context): PackageInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    private fun archivePackageInfo(context: Context, apk: File): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
    }.getOrNull()

    private fun certificateHashes(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            when {
                signing == null -> null
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }

        return signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    private const val TAG = "UpdateVerifier"
}
