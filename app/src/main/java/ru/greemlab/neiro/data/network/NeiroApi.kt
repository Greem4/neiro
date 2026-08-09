package ru.greemlab.neiro.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Сервис Neiro на Pi: вход, сессия и токен FCM (docs/neiro-push/API.md).
 *
 * Пароль YClients уходит ровно в один метод — [login], и дальше сервера не
 * идёт. Всё остальное авторизуется `device_token`, который подставляет
 * интерцептор [YClientsClient].
 */
interface NeiroApi {

    /**
     * Вход по логину и паролю YClients. Заодно регистрирует устройство:
     * `device_id` и `fcm_token` в теле — отдельного вызова регистрации больше
     * нет (ARCHITECTURE.md § Вход).
     */
    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    /**
     * Отзывает `device_token` этого устройства. Аккаунт на сервере остаётся.
     *
     * Токен передаётся явно: отзыв бывает отложенным (сеть отвалилась при
     * выходе), и подставить текущий значило бы убить сессию, под которой
     * пользователь уже успел войти заново.
     */
    @POST("v1/auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String): Response<Unit>

    /** Состояние сессии без похода в YClients — спрашивается при запуске. */
    @GET("v1/session")
    suspend fun session(): Response<SessionResponse>

    /** Firebase перевыпустил токен — сообщаем серверу, куда слать пуши. */
    @POST("v1/devices/fcm")
    suspend fun updateFcmToken(@Body body: FcmTokenRequest): Response<Unit>
}

data class LoginRequest(
    val login: String,
    val password: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("fcm_token") val fcmToken: String,
    val label: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
)

data class NeiroAccount(
    @SerializedName("company_id") val companyId: Int = 0,
    @SerializedName("staff_id") val staffId: Int = 0,
    @SerializedName("user_name") val userName: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
)

data class LoginResponse(
    @SerializedName("device_token") val deviceToken: String?,
    val account: NeiroAccount?,
    @SerializedName("last_event_id") val lastEventId: Long = 0,
)

data class SessionResponse(
    val account: NeiroAccount?,
    @SerializedName("reauth_required") val reauthRequired: Boolean = false,
    @SerializedName("last_event_id") val lastEventId: Long = 0,
)

data class FcmTokenRequest(
    @SerializedName("fcm_token") val fcmToken: String,
)

/** Тело отказа самого сервиса: `{"detail": "reauth_required"}`. */
data class NeiroError(
    val detail: String? = null,
)
