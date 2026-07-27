package ru.greemlab.neiro.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.push.PushConfig
import ru.greemlab.neiro.push.PushEventsSyncer
import ru.greemlab.neiro.push.PushKeepAliveCoordinator
import ru.greemlab.neiro.push.PushRegistrar

/**
 * Разовая (не периодическая) подтяжка YClients API, независимая от [AutoSyncCoordinator]:
 * - сразу после входа;
 * - при каждом возврате в приложение (onStart).
 *
 * Никакого локального опроса по таймеру — сервер сам опрашивает YClients и шлёт
 * push при изменениях (см. [ru.greemlab.neiro.push.NeiroFirebaseMessagingService]), дублировать
 * эту нагрузку клиентом не нужно. [PushKeepAliveCoordinator] лишь переустанавливает
 * FCM-регистрацию, календарь не опрашивает.
 */
object LiveApiCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val yclientsRepository = YClientsRepository.getInstance(appContext)
        val serverPushActive = PushConfig.isActive

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch {
                        if (!yclientsRepository.isLoggedIn.first()) return@launch
                        if (serverPushActive) {
                            // Дожидаемся регистрации: прежний onAppForeground уходил
                            // в свою корутину, и догон стартовал параллельно — на
                            // первом запуске успевал спросить события по device_id,
                            // которого на сервере ещё нет, и получал 404.
                            PushRegistrar.onAppForegroundNow(appContext)
                            // Догон рядом с refreshNow: закрывает дыру нуджа при
                            // открытом приложении, когда синка ещё не было (app.md §6.2).
                            runCatching { PushEventsSyncer.syncNow(appContext) }
                        }
                        refreshNow(appContext)
                    }
                }
            },
        )

        scope.launch {
            yclientsRepository.isLoggedIn.collect { loggedIn ->
                if (loggedIn) {
                    if (serverPushActive) {
                        PushKeepAliveCoordinator.schedule(appContext)
                    }
                    refreshNow(appContext)
                } else if (serverPushActive) {
                    PushKeepAliveCoordinator.cancel(appContext)
                }
            }
        }
    }

    private suspend fun refreshNow(context: Context) {
        val yclientsRepository = YClientsRepository.getInstance(context)
        if (!yclientsRepository.isLoggedIn.first()) return
        YClientsCalendarSync.get(context).refreshLiveRange()
    }
}
