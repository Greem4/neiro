package ru.greemlab.neiro.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.greemlab.neiro.push.PushRegistrar

/** После перезагрузки устройства — заново поставить фоновые задачи уведомлений. */
class SessionNotificationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        SessionNotificationCoordinator.initialize(context)
        PushRegistrar.onAppForeground(context)
    }
}
