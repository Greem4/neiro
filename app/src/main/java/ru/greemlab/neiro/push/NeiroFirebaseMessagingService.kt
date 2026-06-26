package ru.greemlab.neiro.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM: сервер шлёт data { action: sync } — подтягиваем календарь и показываем уведомления.
 */
class NeiroFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["action"] == "sync") {
            PushSyncCoordinator.enqueue(applicationContext)
        }
    }

    override fun onNewToken(token: String) {
        PushRegistrar.onFcmTokenRefresh(applicationContext, token)
    }
}
