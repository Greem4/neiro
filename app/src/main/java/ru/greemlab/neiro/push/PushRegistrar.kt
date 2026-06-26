package ru.greemlab.neiro.push

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.sync.YClientsCalendarSync
import kotlin.coroutines.resume

/**
 * Регистрация телефона на push-сервере и обработка FCM «sync».
 */
object PushRegistrar {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        if (!PushConfig.isActive) return
        scope.launch {
            registerIfLoggedIn(context.applicationContext)
        }
    }

    fun onLoginSuccess(context: Context) {
        if (!PushConfig.isActive) return
        scope.launch {
            registerIfLoggedIn(context.applicationContext)
        }
    }

    fun onLogout(context: Context) {
        if (!PushConfig.isServerConfigured) return
        scope.launch {
            unregister(context.applicationContext)
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
        scope.launch {
            registerIfLoggedIn(context.applicationContext)
        }
    }

    fun onSyncPush(context: Context) {
        if (!PushConfig.isServerConfigured) return
        scope.launch {
            val appContext = context.applicationContext
            val repository = YClientsRepository.getInstance(appContext)
            if (!repository.isLoggedIn.first()) return@launch
            YClientsCalendarSync.get(appContext).refreshLiveRange()
        }
    }

    private suspend fun registerIfLoggedIn(context: Context) {
        if (!PushConfig.isActive) return
        val repository = YClientsRepository.getInstance(context)
        if (!repository.isLoggedIn.first()) return
        if (repository.staffId == null) {
            repository.detectAndSaveStaffId()
        }

        val token = fetchFcmToken() ?: return
        registerWithToken(context, token)
    }

    private suspend fun registerWithToken(context: Context, fcmToken: String) {
        val api = PushClient.getApi() ?: return
        val repository = YClientsRepository.getInstance(context)
        val staffId = repository.staffId ?: return
        val userToken = repository.userToken ?: return
        val partnerToken = repository.partnerToken
        if (partnerToken.isBlank()) return

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

        withContext(Dispatchers.IO) {
            runCatching {
                api.registerDevice(PushClient.authHeader(), body)
            }
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
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
}
