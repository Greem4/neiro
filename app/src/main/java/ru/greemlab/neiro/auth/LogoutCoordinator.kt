package ru.greemlab.neiro.auth

import android.content.Context
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.notifications.ArchiveNotificationStore
import ru.greemlab.neiro.notifications.InAppNotificationStore
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
 * 2. Отзывает `device_token` на сервере — обязательно **до** очистки хранилища:
 *    отзывать нечем, если токен уже стёрт.
 * 3. Чистит локальную сессию и watermark sync.
 * 4. Сбрасывает состояние уведомлений (baseline, dedupe) и обе ленты — они
 *    держат имена клиентов прошлого аккаунта.
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
        // Лента — единственное место, где события аккаунта переживают выход из
        // него: после «сменить аккаунт» там оставались имена клиентов прошлого
        // сотрудника, даты и время его занятий.
        InAppNotificationStore.get(appContext).clearAll()
        ArchiveNotificationStore.get(appContext).clearAll()

        PushRegistrar.onLogout(appContext)

        YClientsRepository.getInstance(appContext).logout()
        SyncPreferences.get(appContext).clearSyncState()
    }
}
