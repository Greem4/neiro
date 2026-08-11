package ru.greemlab.neiro.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Кнопка «Пропустить» в уведомлении о новой версии.
 *
 * Отдельный receiver, а не `startActivity`: нажатие не должно открывать
 * приложение — человек как раз сказал, что заниматься этим сейчас не хочет.
 * `exported="false"` в манифесте: сообщение приходит только от собственного
 * `PendingIntent`.
 */
class UpdateNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SKIP_VERSION) return

        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
        if (versionCode <= 0) return

        val appContext = context.applicationContext
        // Пишем и «пропущено», и «уже уведомляли»: иначе следующая проверка
        // сочла бы версию неувиденной и показала бы уведомление снова.
        UpdatePreferences.get(appContext).apply {
            skippedVersionCode = versionCode
            notifiedVersionCode = maxOf(notifiedVersionCode, versionCode)
        }
        UpdateNotifier.cancel(appContext)
        Log.i(TAG, "Версия $versionCode пропущена пользователем")
    }

    companion object {
        const val ACTION_SKIP_VERSION = "ru.greemlab.neiro.update.SKIP_VERSION"
        const val EXTRA_VERSION_CODE = "version_code"

        private const val TAG = "UpdateNotifier"
    }
}
