package ru.greemlab.neiro.push

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
 * этого не зависят, теряются только пуши.
 */
object PushFcmToken {

    suspend fun fetch(): String? {
        if (!PushConfig.isFcmEnabled) return null
        return suspendCancellableCoroutine { cont ->
            val task = com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            task.addOnSuccessListener { value ->
                if (cont.isActive) cont.resume(value)
            }
            task.addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation {
                // Firebase Task нельзя отменить, но мы перестаём слушать.
            }
        }
    }
}
