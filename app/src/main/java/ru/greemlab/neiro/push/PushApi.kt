package ru.greemlab.neiro.push

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PushApi {

    @POST("v1/devices/register")
    suspend fun registerDevice(
        @Header("Authorization") authorization: String,
        @Body body: RegisterDeviceRequest,
    ): Response<RegisterDeviceResponse>

    @DELETE("v1/devices/{deviceId}")
    suspend fun unregisterDevice(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String,
    ): Response<Unit>

    @GET("v1/devices/{deviceId}/events")
    suspend fun getEvents(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String,
        @Query("since") since: Long,
        @Query("limit") limit: Int = 100,
    ): Response<EventsResponse>

    @POST("v1/devices/{deviceId}/events/ack")
    suspend fun ackEvents(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String,
        @Body body: AckEventsRequest,
    ): Response<Unit>
}

data class RegisterDeviceRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("fcm_token") val fcmToken: String,
    @SerializedName("company_id") val companyId: Int,
    @SerializedName("staff_id") val staffId: Int,
    @SerializedName("partner_token") val partnerToken: String,
    @SerializedName("user_token") val userToken: String,
    val label: String? = null,
    @SerializedName("app_version") val appVersion: String? = null,
)

data class RegisterDeviceResponse(
    @SerializedName("account_id") val accountId: Int,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("last_event_id") val lastEventId: Long,
)

data class AckEventsRequest(
    @SerializedName("last_event_id") val lastEventId: Long,
)

/** Ответ догона — `events` зеркалит `EventPayload` через уже существующий [PushSessionEvent]. */
data class EventsResponse(
    val events: List<PushSessionEvent>,
    @SerializedName("last_event_id") val lastEventId: Long,
    @SerializedName("has_more") val hasMore: Boolean,
)
