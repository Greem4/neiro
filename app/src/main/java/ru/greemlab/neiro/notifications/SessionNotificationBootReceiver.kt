package ru.greemlab.neiro.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.push.PushRegistrar

/** После перезагрузки устройства — заново поставить фоновые задачи уведомлений. */
class SessionNotificationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        // Prefs-чтение и enqueue — не на main-потоке onReceive.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                SessionNotificationCoordinator.initialize(appContext)
                PushRegistrar.onAppForeground(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
