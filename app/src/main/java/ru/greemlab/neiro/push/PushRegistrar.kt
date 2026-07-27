package ru.greemlab.neiro.push

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.data.network.YClientsRepository
import kotlin.coroutines.resume

/**
 * Регистрация телефона на push-сервере.
 */
object PushRegistrar {

    private const val REGISTER_RETRY_COUNT = 3
    private const val TAG = "PushRegistrar"
    private const val PREFS = "neiro_push_registrar"
    private const val KEY_PENDING_UNREGISTER = "pending_unregister"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        retryPendingUnregisterIfNeeded(appContext)
        if (!PushConfig.isActive) return
        scope.launch {
            if (registerIfLoggedIn(appContext)) {
                PushKeepAliveCoordinator.schedule(appContext)
            }
        }
    }

    /** Незавершённый unregister с прошлого logout (сеть отвалилась) — повторяем на старте. */
    private fun retryPendingUnregisterIfNeeded(context: Context) {
        if (!PushConfig.isServerConfigured) return
        if (!prefs(context).getBoolean(KEY_PENDING_UNREGISTER, false)) return
        scope.launch { unregister(context) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun onLoginSuccess(context: Context) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        scope.launch {
            if (registerIfLoggedIn(appContext)) {
                PushKeepAliveCoordinator.schedule(appContext)
            }
        }
    }

    /** Suspend, чтобы logout мог дождаться unregister — иначе при быстром
     *  завершении процесса устройство остаётся на push-сервере. */
    suspend fun onLogout(context: Context) {
        val appContext = context.applicationContext
        PushKeepAliveCoordinator.cancel(appContext)
        // Иначе после входа под другим аккаунтом догон начнётся с чужого id
        // и пропустит его события (app.md §6.5).
        PushEventsCursor.reset(appContext)
        if (!PushConfig.isServerConfigured) return
        unregister(appContext)
    }

    fun onFcmTokenRefresh(context: Context, token: String) {
        if (!PushConfig.isActive) return
        scope.launch {
            registerWithToken(context.applicationContext, token)
        }
    }

    /** Повторная регистрация при возврате в приложение — актуальный FCM-токен на сервере. */
    fun onAppForeground(context: Context) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        scope.launch {
            if (registerIfLoggedIn(appContext)) {
                PushKeepAliveCoordinator.schedule(appContext)
            }
        }
    }

    /**
     * То же, что [onAppForeground], но suspend — вызывающий может дождаться
     * регистрации, прежде чем идти за событиями. Через [onAppForeground] это
     * не выходит: он уходит в свою корутину и возвращается сразу, из-за чего
     * догон обгонял регистрацию и стучался по неизвестному серверу device_id.
     */
    suspend fun onAppForegroundNow(context: Context): Boolean {
        if (!PushConfig.isActive) return false
        val appContext = context.applicationContext
        val registered = registerIfLoggedIn(appContext)
        if (registered) {
            PushKeepAliveCoordinator.schedule(appContext)
        }
        return registered
    }

    suspend fun registerNow(context: Context): Boolean {
        return registerIfLoggedIn(context.applicationContext)
    }

    private suspend fun registerIfLoggedIn(context: Context): Boolean {
        if (!PushConfig.isActive) return false
        val repository = YClientsRepository.getInstance(context)
        if (!repository.isLoggedIn.first()) return false
        if (repository.staffId == null) {
            repository.detectAndSaveStaffId()
        }

        val token = fetchFcmToken() ?: return false
        return registerWithToken(context, token)
    }

    private suspend fun registerWithToken(context: Context, fcmToken: String): Boolean {
        val api = PushClient.getApi() ?: return false
        val repository = YClientsRepository.getInstance(context)
        val staffId = repository.staffId ?: return false
        val userToken = repository.userToken ?: return false
        val partnerToken = repository.partnerToken
        if (partnerToken.isBlank()) return false

        val body = RegisterDeviceRequest(
            deviceId = PushDeviceId.get(context),
            fcmToken = fcmToken,
            companyId = repository.companyId,
            staffId = staffId,
            partnerToken = partnerToken,
            userToken = userToken,
            label = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
        )

        return withContext(Dispatchers.IO) {
            repeat(REGISTER_RETRY_COUNT) { attempt ->
                val outcome = runCatching {
                    api.registerDevice(PushClient.authHeader(), body)
                }
                val response = outcome.getOrNull()
                when {
                    response != null && response.isSuccessful -> {
                        // Курсор ещё не задан (новое устройство) — принять начальное
                        // значение из ответа регистрации; известное устройство своё
                        // не отдаёт (app.md §6.4).
                        response.body()?.lastEventId?.let { PushEventsCursor.setIfAbsent(context, it) }
                        return@withContext true
                    }
                    response != null && response.code() in 400..499 -> {
                        // 4xx: невалидный токен/payload — retry бесполезен.
                        return@withContext false
                    }
                    attempt < REGISTER_RETRY_COUNT - 1 -> {
                        delay(1_000L * (1 shl attempt))
                    }
                }
            }
            false
        }
    }

    private suspend fun unregister(context: Context) {
        val api = PushClient.getApi() ?: return
        val deviceId = PushDeviceId.get(context)
        val success = withContext(Dispatchers.IO) {
            runCatching { api.unregisterDevice(PushClient.authHeader(), deviceId) }
                .map { it.isSuccessful }
                .getOrDefault(false)
        }
        if (success) {
            prefs(context).edit().remove(KEY_PENDING_UNREGISTER).apply()
        } else {
            Log.w(TAG, "unregister failed, will retry on next app start")
            prefs(context).edit().putBoolean(KEY_PENDING_UNREGISTER, true).apply()
        }
    }

    private suspend fun fetchFcmToken(): String? {
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
