package ru.greemlab.neiro.auth

import android.content.Context
import androidx.work.WorkManager
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.push.PushKeepAliveCoordinator
import ru.greemlab.neiro.push.PushRegistrar
import ru.greemlab.neiro.sync.AutoSyncCoordinator
import ru.greemlab.neiro.sync.SyncPreferences

/**
 * Единственная точка логаута YClients.
 *
 * Делает в порядке:
 * 1. Останавливает периодические задачи (auto-sync, live API, push keepalive, notifications).
 * 2. Отзывает регистрацию устройства на push-сервере.
 * 3. Чистит локальные токены и watermark sync.
 * 4. Сбрасывает состояние уведомлений (baseline, dedupe).
 *
 * Профиль, архивный календарь и тема НЕ затрагиваются.
 */
object LogoutCoordinator {

    suspend fun logout(context: Context) {
        val appContext = context.applicationContext

        AutoSyncCoordinator.cancelLegacyPeriodicSync(appContext)
        cancelLiveApiWorker(appContext)
        PushKeepAliveCoordinator.cancel(appContext)
        
        // TODO: реализовать в ETAP_3
        // SessionNotificationCoordinator.onLoggedOut(appContext)
        // Временно вызываем существующий метод для отмены уведомлений
        SessionNotificationCoordinator.onNotificationsToggled(appContext, false)

        PushRegistrar.onLogout(appContext)

        YClientsRepository.getInstance(appContext).logout()
        SyncPreferences.get(appContext).clearSyncState()
    }

    private fun cancelLiveApiWorker(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LIVE_API_WORK_NAME)
    }

    private const val LIVE_API_WORK_NAME = "yclients_live_api_refresh"
}
