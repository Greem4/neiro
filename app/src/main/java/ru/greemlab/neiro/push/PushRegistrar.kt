package ru.greemlab.neiro.push

import android.content.Context
import android.os.Build
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
 * Регистрация телефона на push-сервере и обработка FCM «sync».
 */
object PushRegistrar {

    private const val REGISTER_RETRY_COUNT = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        scope.launch {
            if (registerIfLoggedIn(appContext)) {
                PushKeepAliveCoordinator.schedule(appContext)
            }
        }
    }

    fun onLoginSuccess(context: Context) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        scope.launch {
            if (registerIfLoggedIn(appContext)) {
                PushKeepAliveCoordinator.schedule(appContext)
            }
        }
    }

    fun onLogout(context: Context) {
        val appContext = context.applicationContext
        PushKeepAliveCoordinator.cancel(appContext)
        if (!PushConfig.isServerConfigured) return
        scope.launch {
            unregister(appContext)
        }
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
                    response != null && response.isSuccessful -> return@withContext true
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
        withContext(Dispatchers.IO) {
            runCatching {
                api.unregisterDevice(PushClient.authHeader(), deviceId)
            }
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
