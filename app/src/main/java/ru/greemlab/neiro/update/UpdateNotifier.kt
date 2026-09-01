package ru.greemlab.neiro.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.greemlab.neiro.MainActivity
import ru.greemlab.neiro.R
import ru.greemlab.neiro.notifications.NeiroNotificationBranding

/**
 * Системное уведомление «вышла новая версия».
 *
 * Отдельный канал `app_updates` с важностью `DEFAULT`: новость не срочная,
 * звенеть и вибрировать ей незачем, а отключить её отдельно от напоминаний о
 * занятиях пользователь должен уметь — на то каналы и придуманы.
 *
 * Лента уведомлений внутри приложения (`InAppNotificationStore`) намеренно не
 * трогается: она устроена вокруг событий занятий (`SessionEventType`), и «вышла
 * версия» туда не ложится без правки чужой модели. Признак новой версии внутри
 * приложения — точка на пункте «О программе» (этап 8).
 */
object UpdateNotifier {

    const val CHANNEL_ID = "app_updates"

    /** Фиксированный id: об обновлении говорим одним уведомлением, не стопкой. */
    private const val NOTIFICATION_ID = 10_006

    /**
     * Показывает уведомление, если о версии ещё не говорили и её не пропускали.
     *
     * @return true, если уведомление реально ушло в шторку.
     */
    fun notifyIfNeeded(context: Context, info: UpdateInfo): Boolean {
        val appContext = context.applicationContext
        val preferences = UpdatePreferences.get(appContext)
        val versionCode = info.version.versionCode

        val allowed = shouldNotify(
            versionCode = versionCode,
            notifiedVersionCode = preferences.notifiedVersionCode,
            skippedVersionCode = preferences.skippedVersionCode,
        )
        if (!allowed) return false

        ensureChannel(appContext)
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            // Разрешения нет — отметку не ставим: выдаст позже, узнает о версии.
            return false
        }

        val title = appContext.getString(R.string.notification_update_title, info.version.versionName)
        val body = firstNotesLine(info) ?: appContext.getString(R.string.notification_update_body)

        val notification = NeiroNotificationBranding.apply(
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(appContext))
                .addAction(
                    0,
                    appContext.getString(R.string.notification_update_skip),
                    skipIntent(appContext, versionCode),
                ),
            appContext,
        ).build()

        val shown = try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS не выдан или ошибка AppOps (uid -1).
            false
        }

        // Отметка ставится только после успешного показа: иначе одно неудачное
        // уведомление навсегда похоронило бы новость об этой версии.
        if (shown) preferences.notifiedVersionCode = versionCode
        return shown
    }

    /**
     * Известна ли версия новее установленной — для точки на пункте «О программе».
     * Берётся из сохранённого предложения, в сеть не ходит.
     *
     * Источник тот же, что у экрана: точка обещает готовую кнопку «Обновить», и
     * если предложения нет (релиз без APK, откат), обещать нечего.
     *
     * @return версия для подписи или null, если показывать нечего.
     */
    fun knownNewerVersion(context: Context): ReleaseVersion? {
        val appContext = context.applicationContext
        if (!UpdateChannelGate.isAllowed(appContext)) return null
        return UpdatePreferences.get(appContext).usableUpdateOffer()?.version
    }

    /**
     * Политика «когда вообще стоит говорить». Чистая функция без Android —
     * проверяется тестами (`UpdateNotificationPolicyTest`), потому что именно
     * здесь живут все три случая, которые легко перепутать: та же версия
     * дважды, пропущенная версия, следующая после пропущенной.
     */
    fun shouldNotify(versionCode: Int, notifiedVersionCode: Int, skippedVersionCode: Int): Boolean {
        // Пропущенная версия и всё, что её старее, молчит до следующего выпуска.
        if (versionCode <= skippedVersionCode) return false
        return versionCode > notifiedVersionCode
    }

    /**
     * «Нажмите, чтобы установить» — когда установщик просит подтверждение, а
     * приложение в фоне: с Android 10 запуск активити оттуда запрещён, и без
     * этого уведомления обновление тихо повисло бы.
     */
    fun notifyInstallReady(context: Context, confirmIntent: Intent): Boolean {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return false

        val pending = PendingIntent.getActivity(
            appContext,
            REQUEST_INSTALL,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NeiroNotificationBranding.apply(
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.notification_update_install_title))
                .setContentText(appContext.getString(R.string.notification_update_install_body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending),
            appContext,
        ).build()

        return try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID_INSTALL, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Убрать уведомления пакета — после установки или по «Пропустить». */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).apply {
            cancel(NOTIFICATION_ID)
            cancel(NOTIFICATION_ID_INSTALL)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.notification_channel_updates_desc)
                enableVibration(false)
                lightColor = NeiroNotificationBranding.channelLightColor(appContext)
            }
            manager.createNotificationChannel(channel)
        } catch (_: SecurityException) {
            // Ошибки AppOps/UID при создании канала на некоторых устройствах.
        }
    }

    /**
     * Первая содержательная строка описания релиза. Заголовки Markdown и
     * пустые строки пропускаем: «## Добавлено» в шторке ничего не объясняет.
     */
    private fun firstNotesLine(info: UpdateInfo): String? = info.notes
        .lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.takeIf { it.isNotBlank() }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ABOUT, true)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun skipIntent(context: Context, versionCode: Int): PendingIntent {
        val intent = Intent(context, UpdateNotificationReceiver::class.java).apply {
            action = UpdateNotificationReceiver.ACTION_SKIP_VERSION
            putExtra(UpdateNotificationReceiver.EXTRA_VERSION_CODE, versionCode)
        }
        return PendingIntent.getBroadcast(
            context,
            // Свой requestCode на версию: иначе FLAG_UPDATE_CURRENT переписал бы
            // extras уже висящего уведомления о прошлом выпуске.
            REQUEST_SKIP_BASE + (versionCode % 1000),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Подтверждение установки — своё уведомление: оно живёт отдельно от новости о версии. */
    private const val NOTIFICATION_ID_INSTALL = 10_007

    private const val REQUEST_OPEN = 4_100
    private const val REQUEST_SKIP_BASE = 4_200
    private const val REQUEST_INSTALL = 4_400
}
