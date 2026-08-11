package ru.greemlab.neiro.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Присутствие телефона на push-сервере.
 *
 * Регистрации как отдельного шага больше нет: устройство появляется на сервере
 * вместе со входом — `device_id` и токен FCM едут в том же запросе
 * (docs/neiro-push/ARCHITECTURE.md § Вход). Здесь остаётся то, что происходит
 * после: поддержание актуального токена FCM, планирование keepalive и отзыв
 * устройства при выходе.
 */
object PushRegistrar {

    private const val TAG = "PushRegistrar"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (!PushConfig.isServerConfigured) return
        scope.launch {
            // Незавершённый с прошлого выхода отзыв устройства: пока он не
            // прошёл, сервер продолжает слать пуши на чужой теперь телефон.
            YClientsRepository.getInstance(appContext).retryPendingRevoke()
            if (!PushConfig.isActive) return@launch
            if (refreshDeviceState(appContext)) {
                PushKeepAliveCoordinator.schedule(appContext)
            }
        }
    }

    fun onLoginSuccess(context: Context) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        // Устройство уже зарегистрировано входом — остаётся включить keepalive.
        PushKeepAliveCoordinator.schedule(appContext)
    }

    /**
     * Suspend, чтобы logout мог дождаться отзыва — иначе при быстром завершении
     * процесса устройство остаётся на push-сервере.
     *
     * Вызывается **до** очистки хранилища: отзыв идёт `device_token`-ом,
     * которого после неё уже не будет.
     */
    suspend fun onLogout(context: Context) {
        val appContext = context.applicationContext
        PushKeepAliveCoordinator.cancel(appContext)
        // Иначе после входа под другим аккаунтом догон начнётся с чужого id
        // и пропустит его события (app.md §6.5).
        PushEventsCursor.reset(appContext)
        if (!PushConfig.isServerConfigured) return
        if (!YClientsRepository.getInstance(appContext).revokeDeviceOnServer()) {
            Log.w(TAG, "отзыв устройства не прошёл, повторим при следующем старте")
        }
    }

    fun onFcmTokenRefresh(context: Context, token: String) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        scope.launch {
            YClientsRepository.getInstance(appContext).updateFcmToken(token)
        }
    }

    /** Возврат в приложение — сверяем состояние сессии и токен FCM с сервером. */
    fun onAppForeground(context: Context) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        scope.launch {
            if (refreshDeviceState(appContext)) {
                PushKeepAliveCoordinator.schedule(appContext)
            }
        }
    }

    /**
     * То же, что [onAppForeground], но suspend — вызывающий может дождаться
     * результата, прежде чем идти за событиями. Через [onAppForeground] это
     * не выходит: он уходит в свою корутину и возвращается сразу.
     */
    suspend fun onAppForegroundNow(context: Context): Boolean {
        if (!PushConfig.isActive) return false
        val appContext = context.applicationContext
        val ready = refreshDeviceState(appContext)
        if (ready) {
            PushKeepAliveCoordinator.schedule(appContext)
        }
        return ready
    }

    /**
     * Сверка состояния устройства с сервером: сессия и токен FCM.
     *
     * Возвращает «вход выполнен» — именно это, а не успех запросов: keepalive
     * нужен и тогда, когда сеть сейчас недоступна, иначе один неудачный запрос
     * останавливал бы догон событий до перезапуска приложения.
     *
     * Токен FCM отправляем при каждом возврате в приложение: Firebase меняет
     * его молча, и `onNewToken` до телефона доходит не всегда.
     */
    suspend fun refreshDeviceState(context: Context): Boolean {
        val repository = YClientsRepository.getInstance(context)
        if (!repository.isLoggedIn.first()) return false
        repository.refreshSession()
        PushFcmToken.fetch()?.let { repository.updateFcmToken(it) }
        return repository.isLoggedIn.first()
    }
}
