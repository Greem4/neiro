package ru.greemlab.neiro.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Чем закончилась загрузка файла. */
sealed interface DownloadOutcome {
    data class Success(val apk: File) : DownloadOutcome
    data class Failed(val failure: UpdateFailure) : DownloadOutcome
}

/**
 * Скачивание APK обновления.
 *
 * Файл кладём в `cacheDir/updates` — приватный каталог: снаружи его не видно,
 * а при нехватке места система вычистит его сама. Перед каждой попыткой каталог
 * очищается: два APK по 15 МБ на забитом телефоне — лишняя причина отказа.
 *
 * Запускается **только** по действию пользователя. Автоматически не качаем
 * никогда: пятнадцать мегабайт по мобильному интернету без спроса не прощают.
 */
class UpdateDownloader(
    private val client: OkHttpClient = UpdateClient.getHttpClient(),
) {

    /**
     * @param onProgress проценты, не чаще раза в [PROGRESS_INTERVAL_MS] — иначе
     * Compose дёргался бы на каждый прочитанный буфер.
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dir = prepareDirectory(appContext)
            ?: return@withContext DownloadOutcome.Failed(UpdateFailure.DownloadFailed)

        // Запас сверх размера APK: система пишет файл целиком, и упереться в
        // ноль на последнем мегабайте — худший момент для отказа.
        val required = info.apkSizeBytes + (info.apkSizeBytes / 5)
        if (info.apkSizeBytes > 0 && dir.usableSpace < required) {
            Log.w(TAG, "Мало места: нужно $required, свободно ${dir.usableSpace}")
            return@withContext DownloadOutcome.Failed(UpdateFailure.NoSpace)
        }

        val target = File(dir, info.apkName.ifBlank { "neiro-${info.version.versionName}.apk" })
        try {
            downloadTo(info.apkUrl, target, info.apkSizeBytes, onProgress)
            DownloadOutcome.Success(target)
        } catch (e: IOException) {
            // Оборванная закачка оставляет огрызок файла — он бесполезен и
            // мешает следующей попытке пройти проверку суммы.
            target.delete()
            Log.w(TAG, "Загрузка не удалась: ${e.message}")
            DownloadOutcome.Failed(UpdateFailure.DownloadFailed)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    /** Текстовый ассет с суммами — он маленький, читаем целиком в память. */
    suspend fun downloadChecksums(url: String): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (e: IOException) {
            Log.w(TAG, "SHA256SUMS.txt не скачался: ${e.message}")
            null
        }
    }

    private suspend fun downloadTo(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
    ) {
        if (url.isBlank()) throw IOException("У ассета нет ссылки на скачивание")

        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Пустой ответ")
            val total = if (expectedSize > 0) expectedSize else body.contentLength()

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var lastReport = 0L

                    while (true) {
                        // Пользователь ушёл с экрана — закачку прекращаем, а не
                        // дотягиваем 15 МБ «на всякий случай».
                        coroutineContext.ensureActive()

                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val nowMs = System.currentTimeMillis()
                        if (total > 0 && nowMs - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = nowMs
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                    output.fd.sync()
                }
            }
            onProgress(100)
        }
    }

    /** @return каталог загрузок, пустой; null — создать не вышло. */
    private fun prepareDirectory(context: Context): File? {
        val dir = File(context.cacheDir, UpdateConfig.DOWNLOAD_DIR)
        dir.listFiles()?.forEach { it.delete() }
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir.takeIf { it.isDirectory }
    }

    companion object {
        private const val TAG = "UpdateDownloader"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 200L

        /** Удалить скачанное — после установки, отказа или неудачной проверки. */
        fun clearDownloads(context: Context) {
            val dir = File(context.applicationContext.cacheDir, UpdateConfig.DOWNLOAD_DIR)
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
