package ru.greemlab.neiro.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Установка скачанного APK через `PackageInstaller` Session API.
 *
 * Не `Intent(ACTION_VIEW)` с `FileProvider`: этому пути не нужен внешний
 * каталог, он возвращает внятный статус установки, и только он умеет ставить
 * самообновление без второго системного окна.
 *
 * Помнить о главном: `STATUS_SUCCESS` означает, что процесс сейчас убьют и
 * заменят. Поэтому всё, что нужно запомнить о факте обновления, пишется **до**
 * `commit` и через `commit()`, а не `apply()` — асинхронная запись не успеет
 * (RISKS.md § Установка убивает процесс).
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** Разрешена ли приложению установка пакетов. Ниже API 26 разрешение не спрашивают. */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.applicationContext.packageManager.canRequestPackageInstalls()
    }

    /**
     * Экран системных настроек «Установка неизвестных приложений» для Neiro.
     * Обойти это нельзя никаким способом, и пытаться не нужно — задача экрана
     * объяснить одной фразой, зачем это, и открыть нужный переключатель сразу.
     */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.applicationContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Отдаёт файл системе. Дальше всё решает установщик: результат придёт
     * широковещательно в [UpdateInstallReceiver].
     *
     * @return причина отказа, если до установщика дело не дошло; null — сессия
     * отдана системе, ждём ответа.
     */
    suspend fun install(
        context: Context,
        apk: File,
        versionCode: Int,
    ): UpdateFailure? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        if (!apk.isFile || apk.length() == 0L) return@withContext UpdateFailure.DownloadFailed
        if (!canInstall(appContext)) return@withContext UpdateFailure.InstallRejected

        // Пишем ДО commit и синхронно: после успешной установки процесс будет
        // убит, и этот код может не выполниться вовсе.
        UpdatePreferences.get(appContext).recordInstallAttempt(versionCode)

        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ разрешает приложению обновить само себя без
                // системного окна, если подпись совпадает и разрешение уже
                // выдано. Это не обход защиты: система проверяет всё то же
                // самое, просто не спрашивает второй раз о том, на что
                // пользователь уже согласился. Не выполнилось условие —
                // вернётся STATUS_PENDING_USER_ACTION и покажем подтверждение.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: заявляем владение обновлениями Neiro — чужой
                // установщик тогда не поставит другую версию поверх молча.
                // Оговорка: система применяет это только при установке с нуля,
                // на обновлении уже стоящего пакета вызов ничего не меняет.
                // Раньше флаг не включали из-за RuStore (он ругался бы на своей
                // же установке); RuStore на паузе — вернётся, флаг убрать.
                setRequestUpdateOwnership(true)
            }
        }

        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_ENTRY_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
                    session.fsync(output)
                }
                session.commit(statusIntent(appContext, versionCode).intentSender)
            }
            Log.i(TAG, "Сессия установки $sessionId отдана системе")
            null
        } catch (e: IOException) {
            Log.w(TAG, "Установка не началась: ${e.message}")
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            UpdatePreferences.get(appContext).clearInstallAttempt()
            UpdateFailure.InstallFailed
        } catch (e: SecurityException) {
            Log.w(TAG, "Установщик отказал: ${e.message}")
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            UpdatePreferences.get(appContext).clearInstallAttempt()
            UpdateFailure.InstallRejected
        }
    }

    /**
     * `FLAG_MUTABLE` обязателен: система дописывает в интент свои extras
     * (статус, сообщение, интент подтверждения). `setPackage` — чтобы
     * широковещалку не поймал никто чужой.
     */
    private fun statusIntent(context: Context, versionCode: Int): PendingIntent {
        val intent = Intent(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            .setPackage(context.packageName)
            .putExtra(UpdateInstallReceiver.EXTRA_VERSION_CODE, versionCode)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_STATUS, intent, flags)
    }

    private const val APK_ENTRY_NAME = "neiro_update.apk"
    private const val BUFFER_SIZE = 64 * 1024
    private const val REQUEST_STATUS = 4_300
}
