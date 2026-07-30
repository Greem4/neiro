package ru.greemlab.neiro.auth

import android.content.Context
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.push.PushEventsSyncCoordinator
import ru.greemlab.neiro.push.PushKeepAliveCoordinator
import ru.greemlab.neiro.push.PushRegistrar
import ru.greemlab.neiro.sync.AutoSyncCoordinator
import ru.greemlab.neiro.sync.SyncPreferences

/**
 * Единственная точка логаута YClients.
 *
 * Делает в порядке:
 * 1. Останавливает периодические задачи (auto-sync, push keepalive, догон событий, notifications).
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
        PushKeepAliveCoordinator.cancel(appContext)
        // Иначе идущий догон допишет курсор уже после его сброса в PushRegistrar.onLogout
        // и применит события старого аккаунта к календарю следующего.
        PushEventsSyncCoordinator.cancel(appContext)

        SessionNotificationCoordinator.onLoggedOut(appContext)

        PushRegistrar.onLogout(appContext)

        YClientsRepository.getInstance(appContext).logout()
        SyncPreferences.get(appContext).clearSyncState()
    }
}
