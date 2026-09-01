package ru.greemlab.neiro.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Текущий токен FCM.
 *
 * Нужен в двух местах — при входе (устройство регистрируется тем же запросом)
 * и при обновлении токена, — поэтому живёт отдельно от [PushRegistrar].
 *
 * `null` — штатный исход: FCM выключен в сборке, на устройстве нет
 * Google-сервисов или Firebase не ответил. Ни вход, ни работа с данными от
 * этого не зависят, теряются только пуши — но молчать об этом нельзя:
 * причина неудачи уходит в [PushDeliveryDiagnostics] и оттуда на экран
 * настроек уведомлений.
 */
object PushFcmToken {

    private const val TAG = "PushFcmToken"

    suspend fun fetch(context: Context): String? {
        if (!PushConfig.isFcmEnabled) return null
        val appContext = context.applicationContext
        return suspendCancellableCoroutine { cont ->
            val task = com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            task.addOnSuccessListener { value ->
                PushDeliveryDiagnostics.onTokenReceived(appContext)
                if (cont.isActive) cont.resume(value)
            }
            task.addOnFailureListener { error ->
                // Без текста ошибки «нет Google-сервисов», «нет сети» и
                // «Firebase не настроен» выглядят одинаково — молчащим null.
                Log.w(TAG, "Firebase не отдал токен", error)
                PushDeliveryDiagnostics.onTokenFailed(
                    appContext,
                    error.message ?: error::class.java.simpleName,
                )
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation {
                // Firebase Task нельзя отменить, но мы перестаём слушать.
            }
        }
    }
}
